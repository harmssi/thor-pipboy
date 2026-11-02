#include "Log.h"

#include "F4SE/F4SE.h"

#include <ctime>
#include <cstdio>
#include <filesystem>
#include <mutex>

namespace Log
{
	namespace
	{
		std::mutex g_m;
		std::FILE* g_f{ nullptr };
	}

	void Init()
	{
		wchar_t buf[REX::W32::MAX_PATH]{};
		REX::W32::GetModuleFileNameW(REX::W32::GetCurrentModule(), buf, REX::W32::MAX_PATH);
		std::filesystem::path p(buf);
		p.replace_extension(L".log");
		std::lock_guard lk(g_m);
		g_f = _wfopen(p.c_str(), L"w");
	}

	void Write(const char* a_level, const std::string& a_msg)
	{
		if (a_level[0] == 'W')
			REX::WARN("{}", a_msg);
		else
			REX::INFO("{}", a_msg);
		std::lock_guard lk(g_m);
		if (!g_f)
			return;
		const std::time_t now = std::time(nullptr);
		std::tm           tm{};
		localtime_s(&tm, &now);
		std::fprintf(g_f, "%02d:%02d:%02d [%s] %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec, a_level, a_msg.c_str());
		std::fflush(g_f);
	}
}
