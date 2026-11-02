#pragma once
// Minimaler JSON-Schreiber und -Parser (nur das, was das Protokoll braucht).
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace json
{
	// ---------- Schreiben ----------
	void        Escape(std::string& a_out, std::string_view a_str);
	std::string Quote(std::string_view a_str);
	std::string Num(double a_v);  // NaN/Inf -> null

	class Writer
	{
	public:
		Writer& BeginObject();
		Writer& EndObject();
		Writer& BeginArray();
		Writer& EndArray();
		Writer& Key(std::string_view a_key);
		Writer& Str(std::string_view a_v);
		Writer& Int(std::int64_t a_v);
		Writer& UInt(std::uint64_t a_v);
		Writer& Dbl(double a_v);
		Writer& Bool(bool a_v);
		Writer& Null();
		Writer& Raw(std::string_view a_json);  // bereits fertiges JSON einfügen

		// Kurzformen für Objekt-Felder
		Writer& KV(std::string_view k, std::string_view v) { return Key(k).Str(v); }
		Writer& KV(std::string_view k, const char* v) { return Key(k).Str(v ? v : ""); }
		Writer& KI(std::string_view k, std::int64_t v) { return Key(k).Int(v); }
		Writer& KD(std::string_view k, double v) { return Key(k).Dbl(v); }
		Writer& KB(std::string_view k, bool v) { return Key(k).Bool(v); }

		const std::string& str() const { return _s; }
		std::string&&      take() { return std::move(_s); }

	private:
		void Sep();

		std::string       _s;
		std::vector<bool> _first;  // pro Ebene: noch kein Element geschrieben
		bool              _afterKey{ false };
	};

	// ---------- Lesen ----------
	struct Value
	{
		enum class Type
		{
			Null,
			Bool,
			Number,
			String,
			Array,
			Object
		};
		Type                                          type{ Type::Null };
		bool                                          b{ false };
		double                                        n{ 0 };
		std::string                                   s;
		std::vector<Value>                            arr;
		std::vector<std::pair<std::string, Value>>    obj;

		const Value* Get(std::string_view a_key) const;
		std::string  GetStr(std::string_view a_key, std::string_view a_def = {}) const;
		double       GetNum(std::string_view a_key, double a_def = 0) const;
		bool         GetBool(std::string_view a_key, bool a_def = false) const;
		bool         IsObject() const { return type == Type::Object; }
	};

	bool Parse(std::string_view a_text, Value& a_out);
}
