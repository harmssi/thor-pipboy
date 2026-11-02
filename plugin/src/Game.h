#pragma once
// Spielseite: Datensammler und Befehle. Alles außer IsReady/SetReady/RunOnGameThread
// MUSS auf dem Spielthread laufen (über RunOnGameThread).
#include <functional>
#include <string>
#include <vector>

#include "Json.h"

namespace Game
{
	void SetReady(bool a_ready);
	bool IsReady();

	// Führt a_fn über die F4SE-Task-Queue auf dem Spielthread aus und wartet.
	// false = Timeout (Ladebildschirm, Spiel hängt, ...). a_fn läuft dann ggf. später noch.
	bool RunOnGameThread(std::function<void()> a_fn, int a_timeoutMs);

	// Bekannte Registry-Schlüssel (für "hello"/"keys").
	std::vector<std::string> Keys();

	// Liefert den JSON-Wert zu einem Registry-Schlüssel ("null" falls unbekannt/nicht bereit).
	std::string Collect(const std::string& a_key);

	// Führt einen Befehl aus; liefert JSON-Objekt für "result" und setzt a_ok.
	std::string Execute(const std::string& a_command, const json::Value& a_params, bool& a_ok, std::string& a_error);
}
