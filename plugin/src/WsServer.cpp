// Absichtlich ohne CommonLib-Header: nur Winsock + Standardbibliothek.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>

#include "WsServer.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <vector>

namespace
{
	// ---- SHA-1 (für den Handshake) ----
	std::array<std::uint8_t, 20> Sha1(const std::string& a_msg)
	{
		std::uint32_t h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
		std::vector<std::uint8_t> m(a_msg.begin(), a_msg.end());
		const std::uint64_t        bits = static_cast<std::uint64_t>(m.size()) * 8;
		m.push_back(0x80);
		while (m.size() % 64 != 56)
			m.push_back(0);
		for (int i = 7; i >= 0; --i)
			m.push_back(static_cast<std::uint8_t>(bits >> (i * 8)));
		auto rol = [](std::uint32_t v, int n) { return (v << n) | (v >> (32 - n)); };
		for (std::size_t off = 0; off < m.size(); off += 64) {
			std::uint32_t w[80];
			for (int i = 0; i < 16; ++i)
				w[i] = (m[off + i * 4] << 24) | (m[off + i * 4 + 1] << 16) | (m[off + i * 4 + 2] << 8) | m[off + i * 4 + 3];
			for (int i = 16; i < 80; ++i)
				w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
			std::uint32_t a = h0, b = h1, c = h2, d = h3, e = h4;
			for (int i = 0; i < 80; ++i) {
				std::uint32_t f, k;
				if (i < 20) {
					f = (b & c) | (~b & d);
					k = 0x5A827999;
				} else if (i < 40) {
					f = b ^ c ^ d;
					k = 0x6ED9EBA1;
				} else if (i < 60) {
					f = (b & c) | (b & d) | (c & d);
					k = 0x8F1BBCDC;
				} else {
					f = b ^ c ^ d;
					k = 0xCA62C1D6;
				}
				std::uint32_t t = rol(a, 5) + f + e + k + w[i];
				e = d;
				d = c;
				c = rol(b, 30);
				b = a;
				a = t;
			}
			h0 += a;
			h1 += b;
			h2 += c;
			h3 += d;
			h4 += e;
		}
		std::array<std::uint8_t, 20> out{};
		std::uint32_t                hs[5] = { h0, h1, h2, h3, h4 };
		for (int i = 0; i < 5; ++i)
			for (int j = 0; j < 4; ++j)
				out[i * 4 + j] = static_cast<std::uint8_t>(hs[i] >> (24 - j * 8));
		return out;
	}

	std::string Base64(const std::uint8_t* d, std::size_t n)
	{
		static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		std::string        o;
		for (std::size_t i = 0; i < n; i += 3) {
			std::uint32_t v = d[i] << 16;
			if (i + 1 < n)
				v |= d[i + 1] << 8;
			if (i + 2 < n)
				v |= d[i + 2];
			o.push_back(tbl[(v >> 18) & 63]);
			o.push_back(tbl[(v >> 12) & 63]);
			o.push_back(i + 1 < n ? tbl[(v >> 6) & 63] : '=');
			o.push_back(i + 2 < n ? tbl[v & 63] : '=');
		}
		return o;
	}

	std::string Lower(std::string s)
	{
		std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
		return s;
	}

	constexpr std::size_t kMaxInbound = 1 << 20;     // 1 MiB
	constexpr std::size_t kMaxOutbound = 32 << 20;   // 32 MiB, danach Verbindung kappen
}

struct WsServer::Client
{
	int         id{ 0 };
	SOCKET      sock{ INVALID_SOCKET };
	bool        upgraded{ false };
	bool        closing{ false };
	std::string in;
	std::string out;
	std::string fragment;  // für fragmentierte Nachrichten
};

WsServer::WsServer() :
	_listen(static_cast<std::uintptr_t>(INVALID_SOCKET))
{}

WsServer::~WsServer() { Stop(); }

bool WsServer::Start(const std::string& a_address, std::uint16_t a_port)
{
	WSADATA wsa{};
	if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
		Log("WSAStartup fehlgeschlagen");
		return false;
	}
	_wsaInit = true;

	SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
	if (s == INVALID_SOCKET) {
		Log("socket() fehlgeschlagen");
		return false;
	}
	BOOL yes = TRUE;
	setsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&yes), sizeof(yes));

	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(a_port);
	if (inet_pton(AF_INET, a_address.c_str(), &addr.sin_addr) != 1)
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

	if (bind(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SOCKET_ERROR) {
		Log("bind() fehlgeschlagen, Fehler " + std::to_string(WSAGetLastError()));
		closesocket(s);
		return false;
	}
	if (listen(s, 8) == SOCKET_ERROR) {
		Log("listen() fehlgeschlagen");
		closesocket(s);
		return false;
	}
	u_long nb = 1;
	ioctlsocket(s, FIONBIO, &nb);
	_listen = static_cast<std::uintptr_t>(s);
	return true;
}

void WsServer::Stop()
{
	for (auto& [id, c] : _clients)
		closesocket(c->sock);
	_clients.clear();
	if (static_cast<SOCKET>(_listen) != INVALID_SOCKET) {
		closesocket(static_cast<SOCKET>(_listen));
		_listen = static_cast<std::uintptr_t>(INVALID_SOCKET);
	}
	if (_wsaInit) {
		WSACleanup();
		_wsaInit = false;
	}
}

std::size_t WsServer::Pending(int a_client) const
{
	auto it = _clients.find(a_client);
	return it == _clients.end() ? 0 : it->second->out.size();
}

void WsServer::Poll(int a_timeoutMs)
{
	const SOCKET ls = static_cast<SOCKET>(_listen);
	if (ls == INVALID_SOCKET)
		return;
	fd_set rd, wr;
	FD_ZERO(&rd);
	FD_ZERO(&wr);
	FD_SET(ls, &rd);
	for (auto& [id, c] : _clients) {
		FD_SET(c->sock, &rd);
		if (!c->out.empty())
			FD_SET(c->sock, &wr);
	}
	timeval tv{ a_timeoutMs / 1000, (a_timeoutMs % 1000) * 1000 };
	int     n = select(0, &rd, &wr, nullptr, &tv);
	if (n == SOCKET_ERROR || n == 0)
		return;

	if (FD_ISSET(ls, &rd))
		Accept();

	std::vector<int> ids;
	for (auto& [id, c] : _clients)
		ids.push_back(id);
	for (int id : ids) {
		auto it = _clients.find(id);
		if (it == _clients.end())
			continue;
		Client& c = *it->second;
		if (FD_ISSET(c.sock, &rd))
			Read(c);
		it = _clients.find(id);
		if (it == _clients.end())
			continue;
		if (FD_ISSET(c.sock, &wr))
			Flush(c);
	}
}

void WsServer::Accept()
{
	while (true) {
		SOCKET s = accept(static_cast<SOCKET>(_listen), nullptr, nullptr);
		if (s == INVALID_SOCKET)
			return;
		u_long nb = 1;
		ioctlsocket(s, FIONBIO, &nb);
		BOOL nodelay = TRUE;
		setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));
		auto c = std::make_unique<Client>();
		c->id = _nextId++;
		c->sock = s;
		Log("Client " + std::to_string(c->id) + " verbunden (TCP)");
		_clients[c->id] = std::move(c);
	}
}

void WsServer::Read(Client& c)
{
	char buf[16384];
	while (true) {
		int r = recv(c.sock, buf, sizeof(buf), 0);
		if (r > 0) {
			c.in.append(buf, r);
			if (c.in.size() > kMaxInbound) {
				Drop(c.id);
				return;
			}
			continue;
		}
		if (r == 0) {
			Drop(c.id);
			return;
		}
		if (WSAGetLastError() == WSAEWOULDBLOCK)
			break;
		Drop(c.id);
		return;
	}
	const int id = c.id;
	if (!c.upgraded)
		ProcessHandshake(c);
	auto it = _clients.find(id);
	if (it != _clients.end() && it->second->upgraded)
		ProcessFrames(*it->second);
}

void WsServer::ProcessHandshake(Client& c)
{
	auto end = c.in.find("\r\n\r\n");
	if (end == std::string::npos)
		return;
	std::string req = c.in.substr(0, end);
	c.in.erase(0, end + 4);

	std::string key;
	std::size_t pos = 0;
	while (pos < req.size()) {
		auto        nl = req.find("\r\n", pos);
		std::string line = req.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
		pos = nl == std::string::npos ? req.size() : nl + 2;
		auto colon = line.find(':');
		if (colon == std::string::npos)
			continue;
		if (Lower(line.substr(0, colon)) == "sec-websocket-key") {
			key = line.substr(colon + 1);
			key.erase(0, key.find_first_not_of(" \t"));
			key.erase(key.find_last_not_of(" \t") + 1);
		}
	}
	if (key.empty()) {
		// kein WebSocket: einfache Statusseite für curl/Browser
		std::string body = "{\"server\":\"ThorPipBoy\",\"websocket\":true}\n";
		c.out += "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: " +
		         std::to_string(body.size()) + "\r\n\r\n" + body;
		c.closing = true;
		Flush(c);
		return;
	}
	auto digest = Sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
	c.out += "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
	         Base64(digest.data(), digest.size()) + "\r\n\r\n";
	c.upgraded = true;
	const int id = c.id;
	Flush(c);
	if (_clients.count(id) && onConnect)
		onConnect(id, true);
}

void WsServer::ProcessFrames(Client& c)
{
	const int id = c.id;
	while (true) {
		auto it = _clients.find(id);
		if (it == _clients.end())
			return;
		Client& cl = *it->second;
		auto&   b = cl.in;
		if (b.size() < 2)
			return;
		auto*         p = reinterpret_cast<const std::uint8_t*>(b.data());
		bool          fin = p[0] & 0x80;
		int           op = p[0] & 0x0F;
		bool          masked = p[1] & 0x80;
		std::uint64_t len = p[1] & 0x7F;
		std::size_t   hdr = 2;
		if (len == 126) {
			if (b.size() < 4)
				return;
			len = (p[2] << 8) | p[3];
			hdr = 4;
		} else if (len == 127) {
			if (b.size() < 10)
				return;
			len = 0;
			for (int i = 0; i < 8; ++i)
				len = (len << 8) | p[2 + i];
			hdr = 10;
		}
		if (len > kMaxInbound) {
			Drop(id);
			return;
		}
		std::uint8_t mask[4]{};
		if (masked) {
			if (b.size() < hdr + 4)
				return;
			std::memcpy(mask, p + hdr, 4);
			hdr += 4;
		}
		if (b.size() < hdr + len)
			return;
		std::string payload = b.substr(hdr, static_cast<std::size_t>(len));
		if (masked)
			for (std::size_t i = 0; i < payload.size(); ++i)
				payload[i] ^= mask[i % 4];
		b.erase(0, hdr + static_cast<std::size_t>(len));

		switch (op) {
		case 0x0:  // Fortsetzung
		case 0x1:  // Text
		case 0x2:  // Binär (wie Text behandeln)
			cl.fragment += payload;
			if (fin) {
				std::string msg = std::move(cl.fragment);
				cl.fragment.clear();
				if (onMessage)
					onMessage(id, msg);
			}
			break;
		case 0x8:  // Close
			{
				std::string f;
				f.push_back(static_cast<char>(0x88));
				f.push_back(0);
				cl.out += f;
				cl.closing = true;
				Flush(cl);
				return;
			}
		case 0x9:  // Ping -> Pong
			{
				std::string f;
				f.push_back(static_cast<char>(0x8A));
				f.push_back(static_cast<char>(std::min<std::size_t>(payload.size(), 125)));
				f += payload.substr(0, 125);
				cl.out += f;
				Flush(cl);
				break;
			}
		default:
			break;
		}
	}
}

bool WsServer::Send(int a_client, const std::string& a_text)
{
	auto it = _clients.find(a_client);
	if (it == _clients.end() || !it->second->upgraded)
		return false;
	Client&     c = *it->second;
	std::string f;
	f.push_back(static_cast<char>(0x81));
	const std::size_t n = a_text.size();
	if (n < 126) {
		f.push_back(static_cast<char>(n));
	} else if (n < 65536) {
		f.push_back(126);
		f.push_back(static_cast<char>((n >> 8) & 0xFF));
		f.push_back(static_cast<char>(n & 0xFF));
	} else {
		f.push_back(127);
		for (int i = 7; i >= 0; --i)
			f.push_back(static_cast<char>((static_cast<std::uint64_t>(n) >> (i * 8)) & 0xFF));
	}
	c.out += f;
	c.out += a_text;
	if (c.out.size() > kMaxOutbound) {
		Log("Client " + std::to_string(c.id) + " liest nicht mit, Verbindung getrennt");
		Drop(c.id);
		return false;
	}
	Flush(c);
	return true;
}

void WsServer::Flush(Client& c)
{
	const int id = c.id;
	while (!c.out.empty()) {
		int w = send(c.sock, c.out.data(), static_cast<int>(std::min<std::size_t>(c.out.size(), 1 << 20)), 0);
		if (w > 0) {
			c.out.erase(0, w);
			continue;
		}
		if (w == SOCKET_ERROR && WSAGetLastError() == WSAEWOULDBLOCK)
			return;
		Drop(id);
		return;
	}
	if (c.closing)
		Drop(id);
}

void WsServer::Close(int a_client)
{
	auto it = _clients.find(a_client);
	if (it == _clients.end())
		return;
	it->second->closing = true;
	Flush(*it->second);
}

void WsServer::Drop(int a_id)
{
	auto it = _clients.find(a_id);
	if (it == _clients.end())
		return;
	const bool wasWs = it->second->upgraded;
	closesocket(it->second->sock);
	_clients.erase(it);
	Log("Client " + std::to_string(a_id) + " getrennt");
	if (wasWs && onConnect)
		onConnect(a_id, false);
}
