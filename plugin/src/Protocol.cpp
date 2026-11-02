// Protokoll angelehnt an SkyrimWebSocket (subscribe / unsubscribe / query / heartbeat / command).
// Alle Nachrichten: UTF-8-JSON mit Pflichtfeld "type".
#include "Protocol.h"

#include "Game.h"
#include "Log.h"
#include "Json.h"
#include "WsServer.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <map>
#include <set>
#include <thread>
#include <tuple>
#include <vector>

#include "F4SE/F4SE.h"

namespace Protocol
{
	namespace
	{
		using Clock = std::chrono::steady_clock;

		struct Subscription
		{
			std::vector<std::pair<std::string, std::string>> fields;  // alias -> key
			int                                              frequencyMs{ 500 };
			bool                                             sendOnChange{ false };
			Clock::time_point                                due{};
			std::string                                      last;
		};

		struct ClientState
		{
			std::map<std::string, Subscription> subs;
		};

		Config                       g_cfg;
		std::atomic_bool             g_stop{ false };
		std::thread                  g_thread;
		WsServer*                    g_server{ nullptr };
		std::map<int, ClientState>   g_clients;
		std::vector<int>             g_dropped;

		std::int64_t NowMs()
		{
			return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
		}

		void SendError(int c, const std::string& id, const std::string& msg)
		{
			json::Writer w;
			w.BeginObject().KV("type", "error");
			if (!id.empty())
				w.KV("id", id);
			w.KV("message", msg).EndObject();
			g_server->Send(c, w.str());
		}

		std::vector<std::pair<std::string, std::string>> ParseFields(const json::Value& msg)
		{
			std::vector<std::pair<std::string, std::string>> out;
			if (auto f = msg.Get("fields")) {
				if (f->type == json::Value::Type::Object) {
					for (auto& [alias, v] : f->obj)
						if (v.type == json::Value::Type::String)
							out.emplace_back(alias, v.s);
				} else if (f->type == json::Value::Type::Array) {
					for (auto& v : f->arr)
						if (v.type == json::Value::Type::String)
							out.emplace_back(v.s, v.s);
				}
			}
			return out;
		}

		// Sammelt alle Schlüssel in EINEM Spielthread-Task
		bool CollectKeys(const std::set<std::string>& keys, std::map<std::string, std::string>& out)
		{
			auto result = std::make_shared<std::map<std::string, std::string>>();
			bool ok = Game::RunOnGameThread([keys, result]() {
				for (auto& k : keys)
					(*result)[k] = Game::Collect(k);
			},
				g_cfg.gameTimeoutMs);
			if (ok)
				out = *result;
			return ok;
		}

		std::string BuildData(const std::string& id, const std::vector<std::pair<std::string, std::string>>& fields,
			const std::map<std::string, std::string>& values)
		{
			json::Writer w;
			w.BeginObject().KV("type", "data").KV("id", id).Key("data").BeginObject();
			for (auto& [alias, key] : fields) {
				auto it = values.find(key);
				w.Key(alias).Raw(it == values.end() ? "null" : it->second);
			}
			w.EndObject().EndObject();
			return w.take();
		}

		void HandleMessage(int c, const std::string& text)
		{
			json::Value msg;
			if (!json::Parse(text, msg) || !msg.IsObject()) {
				SendError(c, "", "invalid_json");
				return;
			}
			const auto type = msg.GetStr("type");
			const auto id = msg.GetStr("id");
			auto&      cs = g_clients[c];

			if (type == "heartbeat") {
				json::Writer w;
				w.BeginObject().KV("type", "heartbeat").KI("ts", NowMs()).KB("ready", Game::IsReady()).EndObject();
				g_server->Send(c, w.str());
			} else if (type == "subscribe") {
				if (id.empty())
					return SendError(c, "", "missing_id");
				Subscription s;
				s.fields = ParseFields(msg);
				if (auto st = msg.Get("settings")) {
					s.frequencyMs = std::max(50, static_cast<int>(st->GetNum("frequency", 500)));
					s.sendOnChange = st->GetBool("sendOnChange", false);
				}
				s.due = Clock::now();
				cs.subs[id] = std::move(s);
				Log::Info("Client {}: subscribe '{}' ({} Felder)", c, id, cs.subs[id].fields.size());
			} else if (type == "unsubscribe") {
				if (id.empty())
					cs.subs.clear();
				else
					cs.subs.erase(id);
			} else if (type == "unsubscribe_all") {
				cs.subs.clear();
			} else if (type == "query") {
				auto                               fields = ParseFields(msg);
				std::set<std::string>              keys;
				std::map<std::string, std::string> values;
				for (auto& f : fields)
					keys.insert(f.second);
				if (!CollectKeys(keys, values))
					return SendError(c, id, "game_busy");
				g_server->Send(c, BuildData(id, fields, values));
			} else if (type == "command") {
				const auto cmd = msg.GetStr("command");
				json::Value empty;
				empty.type = json::Value::Type::Object;
				const json::Value* params = msg.Get("params");
				if (!params)
					params = &empty;
				auto        pcopy = std::make_shared<json::Value>(*params);
				auto        out = std::make_shared<std::tuple<bool, std::string, std::string>>(false, "null", "");
				const bool  ran = Game::RunOnGameThread([cmd, pcopy, out]() {
                    bool        ok = false;
                    std::string err;
                    auto        res = Game::Execute(cmd, *pcopy, ok, err);
                    *out = { ok, res, err };
                },
					 g_cfg.gameTimeoutMs);
				json::Writer w;
				w.BeginObject().KV("type", "commandResult").KV("id", id).KV("command", cmd);
				if (!ran) {
					w.KB("success", false).KV("error", "game_busy");
				} else {
					auto& [ok, res, err] = *out;
					w.KB("success", ok).Key("result").Raw(res);
					if (!err.empty())
						w.KV("error", err);
				}
				w.EndObject();
				g_server->Send(c, w.str());
				Log::Info("Client {}: command '{}' -> {}", c, cmd, ran && std::get<0>(*out));
				// Abos sofort aktualisieren, damit die App den neuen Zustand sieht
				for (auto& [sid, s] : cs.subs)
					s.due = Clock::now();
			} else {
				SendError(c, id, "unknown_type");
			}
		}

		void Tick()
		{
			for (int d : g_dropped)
				g_clients.erase(d);
			g_dropped.clear();

			const auto            now = Clock::now();
			std::set<std::string> keys;
			for (auto& [c, cs] : g_clients) {
				if (g_server->Pending(c) > (4u << 20))
					continue;  // Rückstau: Client liest nicht mit
				for (auto& [id, s] : cs.subs)
					if (now >= s.due)
						for (auto& f : s.fields)
							keys.insert(f.second);
			}
			if (keys.empty())
				return;
			std::map<std::string, std::string> values;
			if (!CollectKeys(keys, values))
				return;  // Spielthread blockiert (Laden) – später erneut

			for (auto& [c, cs] : g_clients) {
				if (g_server->Pending(c) > (4u << 20))
					continue;
				for (auto& [id, s] : cs.subs) {
					if (now < s.due)
						continue;
					s.due = now + std::chrono::milliseconds(s.frequencyMs);
					auto payload = BuildData(id, s.fields, values);
					if (s.sendOnChange && payload == s.last)
						continue;
					s.last = payload;
					g_server->Send(c, payload);
				}
			}
		}

		void Run()
		{
			WsServer server;
			g_server = &server;
			server.onLog = [](const std::string& m) { Log::Info("[ws] {}", m); };
			server.onConnect = [](int c, bool connected) {
				if (connected) {
					g_clients[c] = {};
					json::Writer w;
					w.BeginObject().KV("type", "hello").KV("server", "ThorPipBoy").KI("protocol", 1);
					w.KV("version", std::to_string(PLUGIN_VERSION_MAJOR) + "." + std::to_string(PLUGIN_VERSION_MINOR) + "." + std::to_string(PLUGIN_VERSION_PATCH));
					w.KB("ready", Game::IsReady()).Key("keys").BeginArray();
					for (auto& k : Game::Keys())
						w.Str(k);
					w.EndArray().EndObject();
					g_server->Send(c, w.str());
				} else {
					g_dropped.push_back(c);
				}
			};
			server.onMessage = [](int c, const std::string& t) { HandleMessage(c, t); };

			int tries = 0;
			while (!g_stop && !server.Start(g_cfg.address, g_cfg.port)) {
				if (++tries % 10 == 1)
					Log::Warn("WebSocket-Server {}:{} konnte nicht starten, neuer Versuch", g_cfg.address, g_cfg.port);
				std::this_thread::sleep_for(std::chrono::seconds(2));
			}
			Log::Info("WebSocket-Server lauscht auf ws://{}:{}", g_cfg.address, g_cfg.port);
			while (!g_stop) {
				server.Poll(25);
				Tick();
			}
			server.Stop();
			g_server = nullptr;
		}
	}

	void Start(const Config& a_config)
	{
		if (g_thread.joinable())
			return;
		g_cfg = a_config;
		g_stop = false;
		g_thread = std::thread(Run);
	}

	void Stop()
	{
		g_stop = true;
		if (g_thread.joinable())
			g_thread.join();
	}
}
