#pragma once
#include <cstdint>
#include <string>

namespace Protocol
{
	struct Config
	{
		std::string   address{ "127.0.0.1" };
		std::uint16_t port{ 8767 };
		int           gameTimeoutMs{ 1500 };
	};

	// Startet den Server-Thread (idempotent).
	void Start(const Config& a_config);
	void Stop();
}
