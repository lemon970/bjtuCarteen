// Minimal JSON parser/writer for the analysis CLI.
// Tailored to the SimulationReport schema produced by the Java backend.
// Not a full RFC 8259 implementation: handles the subset our reports use
// (objects, arrays, strings, numbers, true/false/null).
#ifndef ANALYSIS_JSON_UTIL_H
#define ANALYSIS_JSON_UTIL_H

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <variant>
#include <vector>

namespace analysis {

class JsonValue;
using JsonObject = std::map<std::string, JsonValue>;
using JsonArray = std::vector<JsonValue>;

class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    JsonValue() : type_(Type::Null) {}
    JsonValue(std::nullptr_t) : type_(Type::Null) {}
    JsonValue(bool b) : type_(Type::Bool), boolValue_(b) {}
    JsonValue(int n) : type_(Type::Number), numberValue_(static_cast<double>(n)) {}
    JsonValue(long long n) : type_(Type::Number), numberValue_(static_cast<double>(n)) {}
    JsonValue(double n) : type_(Type::Number), numberValue_(n) {}
    JsonValue(const char* s) : type_(Type::String), stringValue_(s) {}
    JsonValue(std::string s) : type_(Type::String), stringValue_(std::move(s)) {}
    JsonValue(JsonArray a) : type_(Type::Array), arrayValue_(std::make_shared<JsonArray>(std::move(a))) {}
    JsonValue(JsonObject o) : type_(Type::Object), objectValue_(std::make_shared<JsonObject>(std::move(o))) {}

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    double asNumber(double fallback = 0.0) const {
        if (type_ == Type::Number) return numberValue_;
        if (type_ == Type::Bool) return boolValue_ ? 1.0 : 0.0;
        return fallback;
    }
    long long asLong(long long fallback = 0) const {
        return type_ == Type::Number ? static_cast<long long>(numberValue_) : fallback;
    }
    const std::string& asString() const {
        static const std::string empty;
        return type_ == Type::String ? stringValue_ : empty;
    }
    const JsonArray& asArray() const {
        static const JsonArray empty;
        return (type_ == Type::Array && arrayValue_) ? *arrayValue_ : empty;
    }
    const JsonObject& asObject() const {
        static const JsonObject empty;
        return (type_ == Type::Object && objectValue_) ? *objectValue_ : empty;
    }

    // Read-only path lookup, dot-separated keys. Returns Null on miss.
    const JsonValue& path(const std::string& dotted) const {
        static const JsonValue null;
        const JsonValue* cur = this;
        std::size_t start = 0;
        while (start <= dotted.size()) {
            std::size_t end = dotted.find('.', start);
            std::string key = dotted.substr(start, end == std::string::npos ? std::string::npos : end - start);
            if (!cur->isObject()) return null;
            auto& obj = *cur->objectValue_;
            auto it = obj.find(key);
            if (it == obj.end()) return null;
            cur = &it->second;
            if (end == std::string::npos) break;
            start = end + 1;
        }
        return *cur;
    }

private:
    Type type_;
    bool boolValue_ = false;
    double numberValue_ = 0.0;
    std::string stringValue_;
    std::shared_ptr<JsonArray> arrayValue_;
    std::shared_ptr<JsonObject> objectValue_;
};

class JsonParser {
public:
    static JsonValue parseFile(const std::string& path) {
        std::ifstream in(path, std::ios::binary);
        if (!in) {
            throw std::runtime_error("cannot open json file: " + path);
        }
        std::stringstream ss;
        ss << in.rdbuf();
        return parseString(ss.str());
    }

    static JsonValue parseString(const std::string& src) {
        JsonParser p(src);
        p.skipWs();
        JsonValue v = p.parseValue();
        p.skipWs();
        if (p.pos_ != p.src_.size()) {
            throw std::runtime_error("trailing characters after json document");
        }
        return v;
    }

private:
    explicit JsonParser(const std::string& s) : src_(s) {}
    const std::string& src_;
    std::size_t pos_ = 0;

    void skipWs() {
        while (pos_ < src_.size()) {
            char c = src_[pos_];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') ++pos_;
            else break;
        }
    }
    char peek() {
        if (pos_ >= src_.size()) throw std::runtime_error("unexpected end of json");
        return src_[pos_];
    }
    char eat() {
        if (pos_ >= src_.size()) throw std::runtime_error("unexpected end of json");
        return src_[pos_++];
    }
    void expect(char c) {
        if (eat() != c) throw std::runtime_error(std::string("expected '") + c + "' in json");
    }

    JsonValue parseValue() {
        skipWs();
        char c = peek();
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return JsonValue(parseString());
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') { expectLiteral("null"); return JsonValue(nullptr); }
        return parseNumber();
    }

    void expectLiteral(const char* lit) {
        for (const char* p = lit; *p; ++p) {
            if (eat() != *p) throw std::runtime_error("invalid literal in json");
        }
    }
    JsonValue parseBool() {
        if (peek() == 't') { expectLiteral("true"); return JsonValue(true); }
        expectLiteral("false");
        return JsonValue(false);
    }
    JsonValue parseNumber() {
        std::size_t begin = pos_;
        if (peek() == '-') ++pos_;
        while (pos_ < src_.size()) {
            char c = src_[pos_];
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') ++pos_;
            else break;
        }
        if (begin == pos_) throw std::runtime_error("expected number in json");
        return JsonValue(std::stod(src_.substr(begin, pos_ - begin)));
    }
    std::string parseString() {
        expect('"');
        std::string out;
        while (true) {
            char c = eat();
            if (c == '"') return out;
            if (c == '\\') {
                char esc = eat();
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        if (pos_ + 4 > src_.size()) throw std::runtime_error("bad unicode escape");
                        // Best-effort: ignore astral plane, decode BMP into UTF-8.
                        unsigned int code = std::stoul(src_.substr(pos_, 4), nullptr, 16);
                        pos_ += 4;
                        if (code < 0x80) out.push_back(static_cast<char>(code));
                        else if (code < 0x800) {
                            out.push_back(static_cast<char>(0xC0 | (code >> 6)));
                            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                        } else {
                            out.push_back(static_cast<char>(0xE0 | (code >> 12)));
                            out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                            out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                        }
                        break;
                    }
                    default: throw std::runtime_error("invalid string escape");
                }
            } else {
                out.push_back(c);
            }
        }
    }
    JsonValue parseArray() {
        expect('[');
        JsonArray arr;
        skipWs();
        if (peek() == ']') { ++pos_; return JsonValue(std::move(arr)); }
        while (true) {
            arr.push_back(parseValue());
            skipWs();
            char c = eat();
            if (c == ']') return JsonValue(std::move(arr));
            if (c != ',') throw std::runtime_error("expected ',' or ']' in array");
        }
    }
    JsonValue parseObject() {
        expect('{');
        JsonObject obj;
        skipWs();
        if (peek() == '}') { ++pos_; return JsonValue(std::move(obj)); }
        while (true) {
            skipWs();
            std::string key = parseString();
            skipWs();
            expect(':');
            obj.emplace(std::move(key), parseValue());
            skipWs();
            char c = eat();
            if (c == '}') return JsonValue(std::move(obj));
            if (c != ',') throw std::runtime_error("expected ',' or '}' in object");
        }
    }
};

class JsonWriter {
public:
    static std::string write(const JsonValue& v, bool pretty = true) {
        std::string out;
        writeValue(v, out, pretty, 0);
        return out;
    }
private:
    static void indent(std::string& out, int depth) {
        for (int i = 0; i < depth; ++i) out.append("  ");
    }
    static void writeString(const std::string& s, std::string& out) {
        out.push_back('"');
        for (char c : s) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (static_cast<unsigned char>(c) < 0x20) {
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                        out.append(buf);
                    } else {
                        out.push_back(c);
                    }
            }
        }
        out.push_back('"');
    }
    static void writeNumber(double n, std::string& out) {
        if (std::isnan(n) || std::isinf(n)) {
            out.append("null");
            return;
        }
        std::ostringstream oss;
        if (n == static_cast<long long>(n) && std::abs(n) < 1e15) {
            oss << static_cast<long long>(n);
        } else {
            oss.precision(10);
            oss << n;
        }
        out.append(oss.str());
    }
    static void writeValue(const JsonValue& v, std::string& out, bool pretty, int depth) {
        switch (v.type()) {
            case JsonValue::Type::Null: out.append("null"); break;
            case JsonValue::Type::Bool: out.append(v.asNumber() != 0 ? "true" : "false"); break;
            case JsonValue::Type::Number: writeNumber(v.asNumber(), out); break;
            case JsonValue::Type::String: writeString(v.asString(), out); break;
            case JsonValue::Type::Array: {
                const auto& arr = v.asArray();
                if (arr.empty()) { out.append("[]"); return; }
                out.push_back('[');
                if (pretty) out.push_back('\n');
                for (std::size_t i = 0; i < arr.size(); ++i) {
                    if (pretty) indent(out, depth + 1);
                    writeValue(arr[i], out, pretty, depth + 1);
                    if (i + 1 < arr.size()) out.push_back(',');
                    if (pretty) out.push_back('\n');
                }
                if (pretty) indent(out, depth);
                out.push_back(']');
                break;
            }
            case JsonValue::Type::Object: {
                const auto& obj = v.asObject();
                if (obj.empty()) { out.append("{}"); return; }
                out.push_back('{');
                if (pretty) out.push_back('\n');
                std::size_t i = 0;
                for (const auto& [k, val] : obj) {
                    if (pretty) indent(out, depth + 1);
                    writeString(k, out);
                    out.append(pretty ? ": " : ":");
                    writeValue(val, out, pretty, depth + 1);
                    if (++i < obj.size()) out.push_back(',');
                    if (pretty) out.push_back('\n');
                }
                if (pretty) indent(out, depth);
                out.push_back('}');
                break;
            }
        }
    }
};

}  // namespace analysis

#endif  // ANALYSIS_JSON_UTIL_H
