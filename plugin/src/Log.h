#pragma once
// Zusätzliches Log neben der DLL (Data/F4SE/Plugins/ThorPipBoy.log): auf dem Thor liegt das
// F4SE-Log im Proton-Prefix (intern, per adb nicht lesbar), das Spielverzeichnis auf der SD schon.
#include <format>
#include <string>

namespace Log
{
	void Init();
	void Write(const char* a_level, const std::string& a_msg);

	template <class... A>
	void Info(std::format_string<A...> f, A&&... a) { Write("I", std::format(f, std::forward<A>(a)...)); }
	template <class... A>
	void Warn(std::format_string<A...> f, A&&... a) { Write("W", std::format(f, std::forward<A>(a)...)); }
}
