#include "Json.h"

#include <cctype>
#include <charconv>
#include <cmath>
#include <cstdio>

namespace json
{
	void Escape(std::string& o, std::string_view s)
	{
		o.push_back('"');
		for (unsigned char c : s) {
			switch (c) {
			case '"':
				o += "\\\"";
				break;
			case '\\':
				o += "\\\\";
				break;
			case '\n':
				o += "\\n";
				break;
			case '\r':
				o += "\\r";
				break;
			case '\t':
				o += "\\t";
				break;
			default:
				if (c < 0x20) {
					char buf[8];
					std::snprintf(buf, sizeof(buf), "\\u%04x", c);
					o += buf;
				} else {
					o.push_back(static_cast<char>(c));
				}
			}
		}
		o.push_back('"');
	}

	std::string Quote(std::string_view s)
	{
		std::string o;
		Escape(o, s);
		return o;
	}

	std::string Num(double v)
	{
		if (!std::isfinite(v))
			return "null";
		if (v == std::floor(v) && std::fabs(v) < 1e15) {
			return std::to_string(static_cast<long long>(v));
		}
		char buf[32];
		std::snprintf(buf, sizeof(buf), "%.6g", v);
		return buf;
	}

	void Writer::Sep()
	{
		if (_afterKey) {
			_afterKey = false;
			return;
		}
		if (!_first.empty()) {
			if (!_first.back())
				_s.push_back(',');
			_first.back() = false;
		}
	}

	Writer& Writer::BeginObject()
	{
		Sep();
		_s.push_back('{');
		_first.push_back(true);
		return *this;
	}
	Writer& Writer::EndObject()
	{
		_s.push_back('}');
		_first.pop_back();
		return *this;
	}
	Writer& Writer::BeginArray()
	{
		Sep();
		_s.push_back('[');
		_first.push_back(true);
		return *this;
	}
	Writer& Writer::EndArray()
	{
		_s.push_back(']');
		_first.pop_back();
		return *this;
	}
	Writer& Writer::Key(std::string_view k)
	{
		Sep();
		Escape(_s, k);
		_s.push_back(':');
		_afterKey = true;
		return *this;
	}
	Writer& Writer::Str(std::string_view v)
	{
		Sep();
		Escape(_s, v);
		return *this;
	}
	Writer& Writer::Int(std::int64_t v)
	{
		Sep();
		_s += std::to_string(v);
		return *this;
	}
	Writer& Writer::UInt(std::uint64_t v)
	{
		Sep();
		_s += std::to_string(v);
		return *this;
	}
	Writer& Writer::Dbl(double v)
	{
		Sep();
		_s += Num(v);
		return *this;
	}
	Writer& Writer::Bool(bool v)
	{
		Sep();
		_s += v ? "true" : "false";
		return *this;
	}
	Writer& Writer::Null()
	{
		Sep();
		_s += "null";
		return *this;
	}
	Writer& Writer::Raw(std::string_view j)
	{
		Sep();
		_s += j.empty() ? std::string_view("null") : j;
		return *this;
	}

	// ---------- Parser ----------
	const Value* Value::Get(std::string_view k) const
	{
		if (type != Type::Object)
			return nullptr;
		for (auto& [key, v] : obj)
			if (key == k)
				return &v;
		return nullptr;
	}
	std::string Value::GetStr(std::string_view k, std::string_view d) const
	{
		auto v = Get(k);
		if (v && v->type == Type::String)
			return v->s;
		if (v && v->type == Type::Number)
			return Num(v->n);
		return std::string(d);
	}
	double Value::GetNum(std::string_view k, double d) const
	{
		auto v = Get(k);
		if (!v)
			return d;
		if (v->type == Type::Number)
			return v->n;
		if (v->type == Type::Bool)
			return v->b ? 1 : 0;
		if (v->type == Type::String && !v->s.empty()) {
			// erlaubt "0x0001F669" oder "123"
			try {
				return static_cast<double>(std::stoull(v->s, nullptr, 0));
			} catch (...) {
			}
		}
		return d;
	}
	bool Value::GetBool(std::string_view k, bool d) const
	{
		auto v = Get(k);
		if (!v)
			return d;
		if (v->type == Type::Bool)
			return v->b;
		if (v->type == Type::Number)
			return v->n != 0;
		return d;
	}

	namespace
	{
		struct P
		{
			std::string_view t;
			std::size_t      i{ 0 };
			int              depth{ 0 };

			void ws()
			{
				while (i < t.size() && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t'))
					++i;
			}
			bool lit(std::string_view l)
			{
				if (t.substr(i, l.size()) == l) {
					i += l.size();
					return true;
				}
				return false;
			}
			static void utf8(std::string& o, unsigned cp)
			{
				if (cp < 0x80)
					o.push_back(static_cast<char>(cp));
				else if (cp < 0x800) {
					o.push_back(static_cast<char>(0xC0 | (cp >> 6)));
					o.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				} else if (cp < 0x10000) {
					o.push_back(static_cast<char>(0xE0 | (cp >> 12)));
					o.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
					o.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				} else {
					o.push_back(static_cast<char>(0xF0 | (cp >> 18)));
					o.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
					o.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
					o.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
				}
			}
			bool str(std::string& o)
			{
				if (i >= t.size() || t[i] != '"')
					return false;
				++i;
				while (i < t.size()) {
					char c = t[i++];
					if (c == '"')
						return true;
					if (c != '\\') {
						o.push_back(c);
						continue;
					}
					if (i >= t.size())
						return false;
					char e = t[i++];
					switch (e) {
					case 'n':
						o.push_back('\n');
						break;
					case 'r':
						o.push_back('\r');
						break;
					case 't':
						o.push_back('\t');
						break;
					case 'b':
						o.push_back('\b');
						break;
					case 'f':
						o.push_back('\f');
						break;
					case 'u':
						{
							if (i + 4 > t.size())
								return false;
							unsigned cp = 0;
							auto     r = std::from_chars(t.data() + i, t.data() + i + 4, cp, 16);
							if (r.ec != std::errc())
								return false;
							i += 4;
							utf8(o, cp);
							break;
						}
					default:
						o.push_back(e);
					}
				}
				return false;
			}
			bool val(Value& v)
			{
				if (++depth > 64)
					return false;
				ws();
				if (i >= t.size())
					return false;
				char c = t[i];
				bool ok = false;
				if (c == '{') {
					++i;
					v.type = Value::Type::Object;
					ws();
					if (i < t.size() && t[i] == '}') {
						++i;
						ok = true;
					} else {
						while (true) {
							ws();
							std::string k;
							if (!str(k))
								break;
							ws();
							if (i >= t.size() || t[i] != ':')
								break;
							++i;
							Value child;
							if (!val(child))
								break;
							v.obj.emplace_back(std::move(k), std::move(child));
							ws();
							if (i < t.size() && t[i] == ',') {
								++i;
								continue;
							}
							if (i < t.size() && t[i] == '}') {
								++i;
								ok = true;
							}
							break;
						}
					}
				} else if (c == '[') {
					++i;
					v.type = Value::Type::Array;
					ws();
					if (i < t.size() && t[i] == ']') {
						++i;
						ok = true;
					} else {
						while (true) {
							Value child;
							if (!val(child))
								break;
							v.arr.push_back(std::move(child));
							ws();
							if (i < t.size() && t[i] == ',') {
								++i;
								continue;
							}
							if (i < t.size() && t[i] == ']') {
								++i;
								ok = true;
							}
							break;
						}
					}
				} else if (c == '"') {
					v.type = Value::Type::String;
					ok = str(v.s);
				} else if (lit("true")) {
					v.type = Value::Type::Bool;
					v.b = true;
					ok = true;
				} else if (lit("false")) {
					v.type = Value::Type::Bool;
					ok = true;
				} else if (lit("null")) {
					ok = true;
				} else {
					std::size_t s = i;
					while (i < t.size() && (std::isdigit(static_cast<unsigned char>(t[i])) || t[i] == '-' || t[i] == '+' || t[i] == '.' || t[i] == 'e' || t[i] == 'E'))
						++i;
					if (i > s) {
						v.type = Value::Type::Number;
						try {
							v.n = std::stod(std::string(t.substr(s, i - s)));
							ok = true;
						} catch (...) {
						}
					}
				}
				--depth;
				return ok;
			}
		};
	}

	bool Parse(std::string_view a_text, Value& a_out)
	{
		P p{ a_text };
		return p.val(a_out);
	}
}
