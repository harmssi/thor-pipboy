// ThorPipBoy – F4SE-Plugin: Pip-Boy-Daten per WebSocket für eine Begleit-App.
#include "F4SE/F4SE.h"
#include "RE/Fallout.h"

#include "Game.h"
#include "Log.h"
#include "Protocol.h"

#include <filesystem>
#include <fstream>

F4SE_PLUGIN_VERSION = []() noexcept {
	F4SE::PluginVersionData v{};
	v.PluginVersion({ PLUGIN_VERSION_MAJOR, PLUGIN_VERSION_MINOR, PLUGIN_VERSION_PATCH, 0 });
	v.PluginName("ThorPipBoy");
	v.AuthorName("harmssi");
	v.UsesSigScanning(false);
	v.UsesAddressLibrary(true);
	v.HasNoStructUse(false);
	v.IsLayoutDependent(true);
	v.CompatibleVersions({ F4SE::RUNTIME_1_11_240, F4SE::RUNTIME_1_11_221, F4SE::RUNTIME_1_11_191 });
	v.MinimumRequiredXSEVersion({ 0, 7, 2, 0 });
	return v;
}();

namespace
{
	// Data/F4SE/Plugins/ThorPipBoy.ini – einfache key=value-Zeilen ([Server] optional)
	Protocol::Config LoadConfig()
	{
		Protocol::Config cfg;
		wchar_t          buf[REX::W32::MAX_PATH]{};
		REX::W32::GetModuleFileNameW(REX::W32::GetCurrentModule(), buf, REX::W32::MAX_PATH);
		std::filesystem::path ini(buf);
		ini.replace_extension(L".ini");
		std::ifstream in(ini);
		std::string      line;
		auto             trim = [](std::string s) {
            s.erase(0, s.find_first_not_of(" \t\r"));
            s.erase(s.find_last_not_of(" \t\r") + 1);
            return s;
		};
		while (std::getline(in, line)) {
			line = trim(line);
			if (line.empty() || line[0] == ';' || line[0] == '#' || line[0] == '[')
				continue;
			auto eq = line.find('=');
			if (eq == std::string::npos)
				continue;
			auto k = trim(line.substr(0, eq));
			auto v = trim(line.substr(eq + 1));
			try {
				if (k == "ListenAddress")
					cfg.address = v;
				else if (k == "Port")
					cfg.port = static_cast<std::uint16_t>(std::stoi(v));
				else if (k == "GameTimeoutMs")
					cfg.gameTimeoutMs = std::stoi(v);
			} catch (...) {
			}
		}
		return cfg;
	}

	void OnMessage(F4SE::MessagingInterface::Message* a_msg)
	{
		switch (a_msg->type) {
		case F4SE::MessagingInterface::kGameDataReady:
			Log::Info("Spieldaten bereit");
			break;
		case F4SE::MessagingInterface::kPreLoadGame:
			Game::SetReady(false);
			break;
		case F4SE::MessagingInterface::kPostLoadGame:
			Game::SetReady(a_msg->data != nullptr);
			Log::Info("Spielstand geladen (ok={})", a_msg->data != nullptr);
			break;
		case F4SE::MessagingInterface::kNewGame:
			Game::SetReady(true);
			Log::Info("Neues Spiel");
			break;
		default:
			break;
		}
	}
}

F4SE_PLUGIN_LOAD(const F4SE::LoadInterface* a_f4se)
{
	F4SE::Init(a_f4se, { .logName = "ThorPipBoy" });
	Log::Init();
	Log::Info("ThorPipBoy {}.{}.{} geladen (F4SE {}, Spiel {})", PLUGIN_VERSION_MAJOR, PLUGIN_VERSION_MINOR, PLUGIN_VERSION_PATCH,
		F4SE::GetF4SEVersion().string(), REX::FModule::GetExecutingModule().GetFileVersion().string());

	if (auto* m = F4SE::GetMessagingInterface())
		m->RegisterListener(OnMessage);

	auto cfg = LoadConfig();
	Protocol::Start(cfg);
	return true;
}
