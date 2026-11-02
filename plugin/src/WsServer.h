#pragma once
// Kleiner WebSocket-Server (RFC 6455, nur Text-Frames) auf Winsock-Basis.
// Läuft komplett auf dem Server-Thread: Poll() blockiert höchstens a_timeoutMs.
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <string>

class WsServer
{
public:
	using LogFn = std::function<void(const std::string&)>;
	using MessageFn = std::function<void(int a_client, const std::string& a_text)>;
	using ConnectFn = std::function<void(int a_client, bool a_connected)>;

	WsServer();
	~WsServer();

	bool Start(const std::string& a_address, std::uint16_t a_port);
	void Stop();
	void Poll(int a_timeoutMs);
	bool Send(int a_client, const std::string& a_text);
	void Close(int a_client);
	std::size_t ClientCount() const { return _clients.size(); }
	std::size_t Pending(int a_client) const;  // noch nicht gesendete Bytes

	LogFn     onLog;
	MessageFn onMessage;
	ConnectFn onConnect;

private:
	struct Client;
	void Accept();
	void Read(Client& a_client);
	void Flush(Client& a_client);
	void ProcessHandshake(Client& a_client);
	void ProcessFrames(Client& a_client);
	void Drop(int a_id);
	void Log(const std::string& a_msg)
	{
		if (onLog)
			onLog(a_msg);
	}

	std::uintptr_t                              _listen;
	int                                         _nextId{ 1 };
	std::map<int, std::unique_ptr<Client>>      _clients;
	bool                                        _wsaInit{ false };
};
