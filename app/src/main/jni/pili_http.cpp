#include "pili_http.h"

#include <algorithm>
#include <cstdio>

namespace pili {
namespace {
using namespace std::literals;
constexpr size_t kMaxBody = 8 * 1024 * 1024;
constexpr unsigned kMaxHead = 32 * 1024;
constexpr std::array kHosts = {"api.bilibili.com"sv, "app.bilibili.com"sv};
constexpr std::array kAuthorities = {kHosts[0], kHosts[1], "api.bilibili.com:443"sv, "app.bilibili.com:443"sv};
constexpr std::array kPaths = {"/x/web-interface/wbi/view"sv, "/bilibili.main.community.reply.v1.Reply/MainList"sv};
constexpr std::array kMethods = {"GET"sv, "POST"sv, "HEAD"sv, "CONNECT"sv};
constexpr std::array kVersion = {"HTTP/1.1"sv};
constexpr std::array kHeaders = {"host"sv, "content-length"sv, "transfer-encoding"sv,
    "content-encoding"sv, "content-type"sv, "grpc-encoding"sv};
constexpr std::array kTransfer = {"chunked"sv};
constexpr std::array kEncodings = {"identity"sv, "gzip"sv};
constexpr std::array kTypes = {"application/json"sv, "application/grpc"sv, "application/grpc+proto"sv,
    "application/grpc-web"sv, "application/grpc-web+proto"sv, "application/octet-stream"sv};
constexpr std::array kGrpcEncodings = {"identity"sv, "gzip"sv};
constexpr std::array kRequests = {
    "GET /x/web-interface/wbi/view HTTP/1.1\r\nHost: api.bilibili.com\r\nContent-Length: 0\r\n\r\n"sv,
    "POST /bilibili.main.community.reply.v1.Reply/MainList HTTP/1.1\r\nHost: app.bilibili.com\r\nContent-Length: 0\r\n\r\n"sv,
};

char Lower(char c) { return c >= 'A' && c <= 'Z' ? static_cast<char>(c + ('a' - 'A')) : c; }
bool Ows(char c) { return c == ' ' || c == '\t'; }
bool Visible(char c) { return static_cast<unsigned char>(c) >= 0x20 && c != 0x7f; }
bool Digit(char c) { return c >= '0' && c <= '9'; }
size_t Index(Site site) { return site == Site::MainList ? 1 : 0; }

template <size_t N>
void Match(const std::array<std::string_view, N>& words, uint32_t& bits, unsigned pos, char c) {
    for (size_t i = 0; i < N; ++i) {
        if (pos >= words[i].size() || words[i][pos] != c) bits &= ~(1U << i);
    }
}

template <size_t N>
int Exact(const std::array<std::string_view, N>& words, uint32_t bits, unsigned size) {
    for (size_t i = 0; i < N; ++i) {
        if ((bits & (1U << i)) && words[i].size() == size) return static_cast<int>(i);
    }
    return -1;
}
}  // namespace

Site IdentifySite(const char* host) {
    if (!host) return Site::None;
    uint32_t bits = UINT32_MAX;
    for (unsigned i = 0; i <= kHosts[0].size(); ++i) {
        if (!host[i]) {
            const int match = Exact(kHosts, bits, i);
            return match < 0 ? Site::None : static_cast<Site>(match + 1);
        }
        Match(kHosts, bits, i, Lower(host[i]));
        if (!(bits & 3)) break;
    }
    return Site::None;
}

bool HttpTap::Head::StartLine(bool inbound, Site site, char c) {
    if (!Visible(c)) return false;
    if (inbound) {
        if (column < 8) Match(kVersion, matches, column, c);
        else if (column == 8 || column == 12) return c == ' ';
        else if (column < 12) {
            if (!Digit(c)) return false;
            status = status * 10 + (c - '0');
        }
        return true;
    }
    if (c == ' ') {
        if (part == 0 && token) {
            const int method = Exact(kMethods, matches, token);
            route = method == static_cast<int>(Index(site));
            head = method == 2;
            connect = method == 3;
        } else if (part == 1 && token) {
            route = route && Exact(kPaths, matches, token) == static_cast<int>(Index(site));
        } else return false;
        ++part;
        token = 0;
        matches = UINT32_MAX;
        return true;
    }
    if (part == 0) Match(kMethods, matches, token++, c);
    else if (part == 1) {
        if (c == '?') query = true;
        if (!query) Match(kPaths, matches, token++, c);
    } else Match(kVersion, matches, token++, c);
    return true;
}

bool HttpTap::Head::Header(char c) {
    if (!colon) {
        if (c == ':' && column) {
            colon = true;
            field = static_cast<Field>(1 + Exact(kHeaders, matches, column));
            return true;
        }
        if (!Visible(c) || c == ' ' || c == ':') return false;  // No obs-fold or whitespace before colon.
        Match(kHeaders, matches, column, Lower(c));
        return true;
    }
    if (field == Field::Ignore) return Visible(c) || c == '\t';
    if (Ows(c)) {
        trailingSpace = valueSize != 0;
        return true;
    }
    if (trailingSpace && (field != Field::Type || c != ';')) return false;
    switch (field) {
        case Field::Host: Match(kAuthorities, candidates, valueSize, Lower(c)); break;
        case Field::Length:
            if (hasLength || !Digit(c) || length > (kMaxBody - (c - '0')) / 10) return false;
            length = length * 10 + (c - '0');
            break;
        case Field::Transfer: Match(kTransfer, candidates, valueSize, Lower(c)); break;
        case Field::Encoding: Match(kEncodings, candidates, valueSize, Lower(c)); break;
        case Field::Type:
            if (c == ';') {
                // Ignore parameters rather than copying them out of the process.
                if (type != -1) return false;
                type = Exact(kTypes, candidates, valueSize);
                if (type < 0) type = -2;
                field = Field::Ignore;
                return true;
            }
            Match(kTypes, candidates, valueSize, Lower(c));
            break;
        case Field::GrpcEncoding: Match(kGrpcEncodings, candidates, valueSize, Lower(c)); break;
        case Field::Ignore: break;
    }
    ++valueSize;
    return true;
}

bool HttpTap::Head::Character(bool inbound, Site site, char c) {
    if (++bytes > kMaxHead) return false;
    if (cr) return c == '\n';
    if (c == '\r') { cr = true; return true; }
    const bool ok = first ? StartLine(inbound, site, c) : Header(c);
    ++column;
    return ok;
}

bool HttpTap::Head::EndLine(bool inbound) {
    if (first) {
        if (inbound ? (column < 12 || !(matches & 1) || status < 100 || status > 599)
                    : (part != 2 || Exact(kVersion, matches, token) != 0 || connect)) return false;
        first = false;
    } else {
        if (!colon) return false;
        switch (field) {
            case Field::Host:
                if (hasHost) return false;
                hasHost = true;
                host = Exact(kAuthorities, candidates, valueSize);
                break;
            case Field::Length:
                if (hasLength || !valueSize) return false;
                hasLength = true;
                break;
            case Field::Transfer:
                if (chunked || Exact(kTransfer, candidates, valueSize) != 0) return false;
                chunked = true;
                break;
            case Field::Encoding:
                if (encoding != -1) return false;
                encoding = Exact(kEncodings, candidates, valueSize);
                if (encoding < 0) encoding = -2;
                break;
            case Field::Type:
                if (type != -1) return false;
                type = Exact(kTypes, candidates, valueSize);
                if (type < 0) type = -2;
                break;
            case Field::GrpcEncoding:
                if (grpcEncoding != -1) return false;
                grpcEncoding = Exact(kGrpcEncodings, candidates, valueSize);
                if (grpcEncoding < 0) grpcEncoding = -2;
                break;
            case Field::Ignore: break;
        }
    }
    cr = colon = trailingSpace = false;
    field = Field::Ignore;
    column = valueSize = 0;
    matches = candidates = UINT32_MAX;
    return true;
}

bool HttpTap::BeginBody(bool inbound, Stream& stream, Emit emit, void* context) {
    const Head& h = stream.header;
    if (h.chunked && h.hasLength) return false;
    const size_t site = Index(site_);
    if (!inbound) {
        if (count_ == pending_.size()) return false;
        pending_[(first_ + count_++) % pending_.size()] = {
            h.route && h.host >= 0 && static_cast<size_t>(h.host) % 2 == site, h.head};
    } else {
        if (!count_ || h.status == 101) return false;
        if (h.status < 200) {
            if (h.hasLength || h.chunked) return false;
            stream = {};
            return true;
        }
        // Dart may abandon an in-flight request body on an early final reply.
        if (count_ == 1 && request_.phase != Phase::Head) return false;
        if (h.status == 204 && (h.chunked || h.length)) return false;
        const Request request = pending_[first_];
        first_ = (first_ + 1) % pending_.size();
        --count_;
        if (request.head || h.status == 204 || h.status == 304) { stream = {}; return true; }
        const bool expectedType = h.type == -1 || (site == 0 ? h.type == 0 : h.type >= 1);
        stream.forward = request.eligible && h.status == 200 && expectedType &&
            h.encoding != -2 && h.grpcEncoding != -2 && (h.chunked || h.length > 0);
        if (stream.forward) {
            char header[384];
            char framing[64];
            if (h.chunked) std::snprintf(framing, sizeof(framing), "Transfer-Encoding: chunked\r\n");
            else std::snprintf(framing, sizeof(framing), "Content-Length: %zu\r\n", h.length);
            const int n = std::snprintf(header, sizeof(header), "HTTP/1.1 200 OK\r\n%s%s%s\r\n", framing,
                h.encoding == 1 ? "Content-Encoding: gzip\r\n" : "",
                h.grpcEncoding == 1 ? "Grpc-Encoding: gzip\r\n" : "");
            if (n < 0 || static_cast<size_t>(n) >= sizeof(header) ||
                !emit(context, false, kRequests[site]) || !emit(context, true, {header, static_cast<size_t>(n)})) return false;
        }
    }
    if (h.chunked) stream.phase = Phase::ChunkSize;
    else if (h.length) { stream.left = h.length; stream.phase = Phase::Fixed; }
    else if (inbound && !h.hasLength) stream.phase = Phase::UntilClose;
    else stream = {};
    return true;
}

bool HttpTap::BodyByte(bool inbound, Stream& s, char c, Emit emit, void* context) {
    switch (s.phase) {
        case Phase::ChunkSize: {
            if (++s.line > 1024) return false;
            if (c == '\r' && s.digits) { s.phase = Phase::ChunkLF; return true; }
            if ((c == ';' && s.digits) || s.extension) { s.extension = true; return Visible(c); }
            const char lc = Lower(c);
            const int digit = Digit(c) ? c - '0' : lc >= 'a' && lc <= 'f' ? lc - 'a' + 10 : -1;
            if (digit < 0 || s.left > (kMaxBody - digit) / 16) return false;
            s.digits = true;
            s.left = 16 * s.left + digit;
            return true;
        }
        case Phase::ChunkLF:
            if (c != '\n' || s.left > kMaxBody - s.total) return false;
            s.line = 0;
            if (!s.left) { s.phase = Phase::Trailers; s.total = 0; return true; }
            s.total += s.left;
            s.phase = Phase::Chunk;
            if (s.forward) {
                char line[24];
                const int n = std::snprintf(line, sizeof(line), "%zx\r\n", s.left);
                return n > 0 && static_cast<size_t>(n) < sizeof(line) && emit(context, inbound, {line, static_cast<size_t>(n)});
            }
            return true;
        case Phase::ChunkCR: s.phase = Phase::ChunkEnd; return c == '\r';
        case Phase::ChunkEnd:
            if (c != '\n') return false;
            s.phase = Phase::ChunkSize;
            s.digits = s.extension = false;
            return !s.forward || emit(context, inbound, "\r\n");
        case Phase::Trailers:
            if (++s.total > kMaxHead) return false;
            if (s.cr) {
                if (c != '\n') return false;
                if (!s.line) {
                    if (s.forward && !emit(context, inbound, "0\r\n\r\n")) return false;
                    s = {};
                } else { s.line = 0; s.cr = false; }
            } else if (c == '\r') s.cr = true;
            else if (Visible(c) || c == '\t') ++s.line;
            else return false;
            return true;
        default: return false;
    }
}

bool HttpTap::Feed(bool inbound, std::string_view bytes, Emit emit, void* context) {
    Stream& s = inbound ? response_ : request_;
    while (!bytes.empty()) {
        if (s.phase == Phase::UntilClose) return true;
        if (s.phase == Phase::Fixed || s.phase == Phase::Chunk) {
            const size_t size = std::min(s.left, bytes.size());
            if (s.forward && !emit(context, inbound, bytes.substr(0, size))) return false;
            bytes.remove_prefix(size);
            s.left -= size;
            if (!s.left) {
                if (s.phase == Phase::Fixed) s = {};
                else s.phase = Phase::ChunkCR;
            }
            continue;
        }
        const char c = bytes.front();
        bytes.remove_prefix(1);
        if (s.phase != Phase::Head) {
            if (!BodyByte(inbound, s, c, emit, context)) return false;
        } else {
            if (!s.header.Character(inbound, site_, c)) return false;
            if (c != '\n') continue;
            if (!s.header.first && !s.header.column) {
                if (!BeginBody(inbound, s, emit, context)) return false;
            } else if (!s.header.EndLine(inbound)) return false;
        }
    }
    return true;
}
}  // namespace pili
