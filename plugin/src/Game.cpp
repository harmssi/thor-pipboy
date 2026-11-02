#include "Game.h"

#include "F4SE/F4SE.h"
#include "RE/Fallout.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <limits>
#include <mutex>

namespace Game
{
	namespace
	{
		std::atomic_bool g_ready{ false };

		std::string Hex(std::uint32_t a_v)
		{
			char buf[16];
			std::snprintf(buf, sizeof(buf), "0x%08X", a_v);
			return buf;
		}

		std::string_view Name(const RE::TESForm* a_form)
		{
			if (!a_form)
				return {};
			return RE::TESFullName::GetFullName(*a_form);
		}

		// ---------------------------------------------------------------
		// Pip-Boy-Datenbaum (internes Modell der früheren Companion-App)
		// ---------------------------------------------------------------
		void DumpValue(json::Writer& w, RE::PipboyValue* v, int depth)
		{
			if (!v || depth > 24) {
				w.Null();
				return;
			}
			auto* raw = reinterpret_cast<std::uint8_t*>(v) + 0x18;  // Wert direkt hinter PipboyValue
			switch (v->GetType()) {
			case RE::PipboyValue::kBool:
				w.Bool(*reinterpret_cast<bool*>(raw));
				break;
			case RE::PipboyValue::kInt8:
				w.Int(*reinterpret_cast<std::int8_t*>(raw));
				break;
			case RE::PipboyValue::kUint8:
				w.Int(*reinterpret_cast<std::uint8_t*>(raw));
				break;
			case RE::PipboyValue::kInt32:
				w.Int(*reinterpret_cast<std::int32_t*>(raw));
				break;
			case RE::PipboyValue::kUint32:
				w.UInt(*reinterpret_cast<std::uint32_t*>(raw));
				break;
			case RE::PipboyValue::kFloat:
				w.Dbl(*reinterpret_cast<float*>(raw));
				break;
			case RE::PipboyValue::kString:
				{
					auto* s = reinterpret_cast<RE::BSFixedString*>(raw);
					w.Str(s->c_str() ? std::string_view(s->c_str()) : std::string_view());
					break;
				}
			case RE::PipboyValue::kArray:
				{
					auto* a = static_cast<RE::PipboyArray*>(v);
					w.BeginArray();
					for (auto* e : a->elements)
						DumpValue(w, e, depth + 1);
					w.EndArray();
					break;
				}
			case RE::PipboyValue::kObject:
				{
					auto* o = static_cast<RE::PipboyObject*>(v);
					w.BeginObject();
					for (auto& entry : o->memberMap) {
						const char* k = entry.first.c_str();
						w.Key(k ? k : "");
						DumpValue(w, entry.second, depth + 1);
					}
					w.EndObject();
					break;
				}
			default:
				w.Null();
			}
		}

		RE::PipboyValue* FindMember(RE::PipboyValue* v, std::string_view name)
		{
			if (!v || v->GetType() != RE::PipboyValue::kObject)
				return nullptr;
			auto* o = static_cast<RE::PipboyObject*>(v);
			for (auto& entry : o->memberMap) {
				const char* k = entry.first.c_str();
				if (k && name == k)
					return entry.second;
			}
			return nullptr;
		}

		// Pfad wie "Map.World.Player" im Baum auflösen
		RE::PipboyValue* Resolve(std::string_view path)
		{
			auto* mgr = RE::PipboyDataManager::GetSingleton();
			if (!mgr)
				return nullptr;
			RE::PipboyValue* cur = &mgr->rootObject;
			while (!path.empty() && cur) {
				auto dot = path.find('.');
				auto part = path.substr(0, dot);
				cur = FindMember(cur, part);
				path = dot == std::string_view::npos ? std::string_view() : path.substr(dot + 1);
			}
			return cur;
		}

		struct DataGroupLock
		{
			RE::PipboyDataManager* mgr;
			explicit DataGroupLock(RE::PipboyDataManager* a_mgr) :
				mgr(a_mgr)
			{
				if (mgr)
					for (auto* g : mgr->dataGroups)
						if (g)
							REX::W32::EnterCriticalSection(&g->pipboyDataMutex);
			}
			~DataGroupLock()
			{
				if (mgr)
					for (auto* g : mgr->dataGroups)
						if (g)
							REX::W32::LeaveCriticalSection(&g->pipboyDataMutex);
			}
		};

		std::string DumpPath(std::string_view path)
		{
			auto*        mgr = RE::PipboyDataManager::GetSingleton();
			DataGroupLock lock(mgr);
			auto*        v = Resolve(path);
			if (!v)
				return "null";
			json::Writer w;
			DumpValue(w, v, 0);
			return w.take();
		}

		// Ein einzelner primitiver Wert aus dem Baum als double (NaN falls fehlt)
		double TreeNum(std::string_view path)
		{
			auto* v = Resolve(path);
			if (!v)
				return std::numeric_limits<double>::quiet_NaN();
			auto* raw = reinterpret_cast<std::uint8_t*>(v) + 0x18;
			switch (v->GetType()) {
			case RE::PipboyValue::kBool:
				return *reinterpret_cast<bool*>(raw) ? 1 : 0;
			case RE::PipboyValue::kInt8:
				return *reinterpret_cast<std::int8_t*>(raw);
			case RE::PipboyValue::kUint8:
				return *reinterpret_cast<std::uint8_t*>(raw);
			case RE::PipboyValue::kInt32:
				return *reinterpret_cast<std::int32_t*>(raw);
			case RE::PipboyValue::kUint32:
				return *reinterpret_cast<std::uint32_t*>(raw);
			case RE::PipboyValue::kFloat:
				return *reinterpret_cast<float*>(raw);
			default:
				return std::numeric_limits<double>::quiet_NaN();
			}
		}

		// ---------------------------------------------------------------
		// Direkte Spiel-APIs
		// ---------------------------------------------------------------
		RE::PlayerCharacter* Player()
		{
			auto* pc = RE::PlayerCharacter::GetSingleton();
			if (!pc || !pc->GetParentCell())
				return nullptr;
			return pc;
		}

		float AV(RE::PlayerCharacter* pc, RE::ActorValueInfo* info)
		{
			return (pc && info) ? pc->GetActorValue(*info) : 0.0F;
		}
		float AVMax(RE::PlayerCharacter* pc, RE::ActorValueInfo* info)
		{
			return (pc && info) ? pc->GetPermanentActorValue(*info) : 0.0F;
		}
		float AVBase(RE::PlayerCharacter* pc, RE::ActorValueInfo* info)
		{
			return (pc && info) ? pc->GetBaseActorValue(*info) : 0.0F;
		}

		std::string CollectStats()
		{
			auto* pc = Player();
			auto* avs = RE::ActorValue::GetSingleton();
			if (!pc || !avs)
				return "null";
			json::Writer w;
			w.BeginObject();
			const char* name = pc->GetDisplayFullName();
			w.KV("name", name ? name : "");
			w.KI("level", pc->GetLevel());
			{
				auto*        mgr = RE::PipboyDataManager::GetSingleton();
				DataGroupLock lock(mgr);
				w.KD("xpProgress", TreeNum("PlayerInfo.XPProgressPct"));
			}
			w.KD("hp", AV(pc, avs->health));
			w.KD("hpMax", AVMax(pc, avs->health));
			w.KD("ap", AV(pc, avs->actionPoints));
			w.KD("apMax", AVMax(pc, avs->actionPoints));
			w.KD("rads", AV(pc, avs->rads));
			w.KD("caps", static_cast<double>(pc->GetGoldAmount()));
			w.KD("weight", pc->GetWeightInContainer());
			w.KD("weightMax", AV(pc, avs->carryWeight));
			w.KD("damageResist", AV(pc, avs->damageResistance));
			w.KD("energyResist", AV(pc, avs->energyResistance));
			w.KD("radResist", AV(pc, avs->radExposureResistance));
			w.EndObject();
			return w.take();
		}

		std::string CollectSpecial()
		{
			auto* pc = Player();
			auto* avs = RE::ActorValue::GetSingleton();
			if (!pc || !avs)
				return "null";
			const std::pair<const char*, RE::ActorValueInfo*> list[] = {
				{ "S", avs->strength }, { "P", avs->perception }, { "E", avs->endurance }, { "C", avs->charisma },
				{ "I", avs->intelligence }, { "A", avs->agility }, { "L", avs->luck }
			};
			json::Writer w;
			w.BeginArray();
			for (auto& [k, info] : list) {
				w.BeginObject().KV("key", k).KD("value", AV(pc, info)).KD("base", AVBase(pc, info)).EndObject();
			}
			w.EndArray();
			return w.take();
		}

		std::string CollectLimbs()
		{
			auto* pc = Player();
			auto* avs = RE::ActorValue::GetSingleton();
			if (!pc || !avs)
				return "null";
			const std::pair<const char*, RE::ActorValueInfo*> list[] = {
				{ "head", avs->perceptionCondition }, { "torso", avs->enduranceCondition },
				{ "leftArm", avs->leftAttackCondition }, { "rightArm", avs->rightAttackCondition },
				{ "leftLeg", avs->leftMobiltyCondition }, { "rightLeg", avs->rightMobilityCondition }
			};
			json::Writer w;
			w.BeginObject();
			for (auto& [k, info] : list) {
				const float cur = AV(pc, info);
				const float max = AVMax(pc, info);
				w.Key(k).BeginObject().KD("value", cur).KD("max", max).KB("crippled", info && cur <= 0.0F).EndObject();
			}
			w.EndObject();
			return w.take();
		}

		const char* Category(RE::ENUM_FORM_ID t)
		{
			switch (t) {
			case RE::ENUM_FORM_ID::kWEAP:
				return "weapon";
			case RE::ENUM_FORM_ID::kARMO:
				return "apparel";
			case RE::ENUM_FORM_ID::kALCH:
				return "aid";
			case RE::ENUM_FORM_ID::kAMMO:
				return "ammo";
			case RE::ENUM_FORM_ID::kNOTE:
				return "holotape";
			case RE::ENUM_FORM_ID::kKEYM:
				return "key";
			case RE::ENUM_FORM_ID::kBOOK:
				return "book";
			case RE::ENUM_FORM_ID::kMISC:
				return "junk";
			default:
				return "misc";
			}
		}

		std::string CollectInventory()
		{
			auto* pc = Player();
			if (!pc || !pc->inventoryList)
				return "null";
			auto*        inv = pc->inventoryList;
			json::Writer w;
			w.BeginArray();
			RE::BSAutoLock<RE::BSReadWriteLock, RE::BSAutoLockReadLockPolicy> lock(inv->rwLock);
			for (auto& item : inv->data) {
				auto* obj = item.object;
				if (!obj)
					continue;
				const auto    type = obj->GetFormType();
				std::uint32_t idx = 0;
				for (auto* st = item.stackData.get(); st; st = st->nextStack.get(), ++idx) {
					const char* nm = item.GetDisplayFullName(idx);
					if (!nm || !*nm)
						continue;
					auto* inst = item.GetInstanceData(idx);
					w.BeginObject();
					w.KV("id", Hex(obj->GetFormID()));
					w.KI("stack", idx);
					w.KV("name", nm);
					w.KV("type", Category(type));
					w.KI("count", st->GetCount());
					w.KB("equipped", st->IsEquipped());
					w.KD("weight", RE::TESWeightForm::GetFormWeight(obj, inst));
					w.KI("value", RE::TESValueForm::GetFormValue(obj, inst));
					w.EndObject();
				}
			}
			w.EndArray();
			return w.take();
		}

		std::string CollectQuests()
		{
			auto* pc = Player();
			if (!pc)
				return "null";
			json::Writer w;
			w.BeginArray();
			RE::BSAutoLock<RE::BSSpinLock> lock(pc->questTargetLock);
			for (auto& inst : pc->objectives) {
				auto* obj = inst.objective;
				if (!obj)
					continue;
				const auto state = static_cast<int>(inst.enstanceState.get());
				// nur angezeigte Ziele (aktiv, erledigt, fehlgeschlagen)
				if (state != 1 && state != 3 && state != 5)
					continue;
				auto*       q = obj->ownerQuest;
				const char* text = obj->displayText.c_str();
				w.BeginObject();
				w.KV("quest", Name(q));
				w.KV("questId", q ? Hex(q->GetFormID()) : "");
				w.KV("objective", text ? text : "");
				w.KI("index", obj->index);
				w.KV("state", state == 1 ? "active" : state == 3 ? "completed" : "failed");
				w.EndObject();
			}
			w.EndArray();
			return w.take();
		}

		std::string CollectLocation()
		{
			auto* pc = Player();
			if (!pc)
				return "null";
			auto* cell = pc->GetParentCell();
			auto  pos = pc->GetPosition();
			json::Writer w;
			w.BeginObject();
			w.KD("x", pos.x).KD("y", pos.y).KD("z", pos.z);
			w.KD("heading", pc->data.angle.z);
			w.KB("interior", cell && cell->IsInterior());
			w.KV("cell", Name(cell));
			w.KV("location", Name(pc->currentLocation));
			auto* ws = (cell && !cell->IsInterior()) ? cell->worldSpace : nullptr;
			w.KV("worldspace", Name(ws));
			w.KV("worldspaceId", ws ? Hex(ws->GetFormID()) : "");
			w.EndObject();
			return w.take();
		}

		std::string CollectTime()
		{
			auto* cal = RE::Calendar::GetSingleton();
			if (!cal)
				return "null";
			auto g = [](RE::TESGlobal* a) { return a ? static_cast<double>(a->GetValue()) : std::numeric_limits<double>::quiet_NaN(); };
			json::Writer w;
			w.BeginObject();
			w.KD("year", g(cal->gameYear));
			w.KD("month", g(cal->gameMonth));
			w.KD("day", g(cal->gameDay));
			w.KD("hour", g(cal->gameHour));
			w.KD("daysPassed", g(cal->gameDaysPassed));
			w.EndObject();
			return w.take();
		}

		std::string CollectRadio()
		{
			json::Writer w;
			w.BeginObject();
			w.KB("enabled", RE::RadioManager::QPlayerRadioEnabled());
			w.KD("frequency", RE::RadioManager::QCurrentPlayerFreq());
			w.Key("stations").Raw(DumpPath("Radio"));
			w.EndObject();
			return w.take();
		}

		// Inventareintrag (formID + Stack-Index) suchen und a_fn darauf anwenden
		template <class F>
		bool WithItem(RE::PlayerCharacter* pc, std::uint32_t formID, std::uint32_t stack, F&& fn)
		{
			auto* inv = pc->inventoryList;
			if (!inv)
				return false;
			RE::TESBoundObject*    obj = nullptr;
			RE::TBO_InstanceData*  inst = nullptr;
			bool                   equipped = false;
			bool                   found = false;
			{
				RE::BSAutoLock<RE::BSReadWriteLock, RE::BSAutoLockReadLockPolicy> lock(inv->rwLock);
				for (auto& item : inv->data) {
					if (!item.object || item.object->GetFormID() != formID)
						continue;
					std::uint32_t idx = 0;
					for (auto* st = item.stackData.get(); st; st = st->nextStack.get(), ++idx) {
						if (idx != stack)
							continue;
						obj = item.object;
						inst = item.GetInstanceData(idx);
						equipped = st->IsEquipped();
						found = true;
						break;
					}
					break;
				}
			}
			if (!found)
				return false;
			return fn(obj, inst, equipped);
		}
	}

	void SetReady(bool a_ready) { g_ready = a_ready; }
	bool IsReady() { return g_ready; }

	bool RunOnGameThread(std::function<void()> a_fn, int a_timeoutMs)
	{
		struct State
		{
			std::mutex              m;
			std::condition_variable cv;
			bool                    done{ false };
		};
		auto task = F4SE::GetTaskInterface();
		if (!task)
			return false;
		auto st = std::make_shared<State>();
		task->AddTask([st, fn = std::move(a_fn)]() {
			try {
				fn();
			} catch (...) {
			}
			{
				std::lock_guard lk(st->m);
				st->done = true;
			}
			st->cv.notify_all();
		});
		std::unique_lock lk(st->m);
		return st->cv.wait_for(lk, std::chrono::milliseconds(a_timeoutMs), [&] { return st->done; });
	}

	std::vector<std::string> Keys()
	{
		return { "game", "player.stats", "player.special", "player.limbs", "player.inventory", "player.quests",
			"player.location", "world.time", "radio", "pipboy", "pipboy.<Pfad>" };
	}

	std::string Collect(const std::string& k)
	{
		if (k == "game") {
			json::Writer w;
			w.BeginObject().KB("ready", g_ready.load()).KB("player", Player() != nullptr);
			auto* ui = RE::UI::GetSingleton();
			w.KB("pipboyOpen", ui && ui->GetMenuOpen("PipboyMenu"));
			w.EndObject();
			return w.take();
		}
		if (!g_ready)
			return "null";
		if (k == "player.stats")
			return CollectStats();
		if (k == "player.special")
			return CollectSpecial();
		if (k == "player.limbs")
			return CollectLimbs();
		if (k == "player.inventory")
			return CollectInventory();
		if (k == "player.quests")
			return CollectQuests();
		if (k == "player.location")
			return CollectLocation();
		if (k == "world.time")
			return CollectTime();
		if (k == "radio")
			return CollectRadio();
		if (k == "pipboy")
			return DumpPath("");
		if (k.starts_with("pipboy."))
			return DumpPath(std::string_view(k).substr(7));
		return "null";
	}

	std::string Execute(const std::string& cmd, const json::Value& p, bool& ok, std::string& err)
	{
		ok = false;
		auto* pc = Player();
		if (!g_ready || !pc) {
			err = "not_ready";
			return "null";
		}
		auto* eq = RE::ActorEquipManager::GetSingleton();

		if (cmd == "equip" || cmd == "use" || cmd == "unequip" || cmd == "toggle") {
			if (!eq) {
				err = "no_equip_manager";
				return "null";
			}
			const auto formID = static_cast<std::uint32_t>(p.GetNum("id", 0));
			const auto stack = static_cast<std::uint32_t>(p.GetNum("stack", 0));
			std::string action;
			bool        res = WithItem(pc, formID, stack, [&](RE::TESBoundObject* obj, RE::TBO_InstanceData* inst, bool equipped) {
                RE::BGSObjectInstance oi(obj, inst);
                const bool            consumable = obj->GetFormType() == RE::ENUM_FORM_ID::kALCH;
                bool                  unequip = cmd == "unequip" || (cmd == "toggle" && equipped && !consumable);
                if (unequip) {
                    action = "unequipped";
                    return eq->UnequipObject(pc, &oi, 1, nullptr, stack, false, false, true, true, nullptr);
                }
                if (equipped && !consumable && cmd != "use") {
                    action = "already_equipped";
                    return true;
                }
                action = consumable ? "used" : "equipped";
                const bool r = eq->EquipObject(pc, oi, stack, 1, nullptr, false, false, true, true, false);
                // Verbrauchsgüter: EquipObject liefert false, obwohl der Gegenstand benutzt wurde (am Gerät geprüft)
                return consumable ? true : r;
            });
			if (!res) {
				err = action.empty() ? "item_not_found" : "equip_failed";
				return "null";
			}
			ok = true;
			json::Writer w;
			w.BeginObject().KV("action", action).KV("id", Hex(formID)).KI("stack", stack).EndObject();
			return w.take();
		}
		if (cmd == "radio") {
			const bool cur = RE::RadioManager::QPlayerRadioEnabled();
			const bool want = p.Get("enable") ? p.GetBool("enable") : !cur;
			RE::RadioManager::EnablePlayerRadio(want, true);
			ok = true;
			json::Writer w;
			w.BeginObject().KB("enabled", want).EndObject();
			return w.take();
		}
		err = "unknown_command";
		return "null";
	}
}
