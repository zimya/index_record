#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace pili {

enum class Site : uint8_t { None, Video, MainList };
Site IdentifySite(const char* host);

// Keeps framing state, not plaintext. Emits normalized HTTP/1.1 envelopes for
// selected responses; the request half contains no query, headers, or body
// from the wire. The sink consumes its view synchronously.
class HttpTap {
public:
    using Emit = bool (*)(void*, bool inbound, std::string_view bytes);
    explicit HttpTap(Site site) : site_(site) {}
    bool Feed(bool inbound, std::string_view bytes, Emit emit, void* context);

private:
    enum class Field : uint8_t { Ignore, Host, Length, Transfer, Encoding, Type, GrpcEncoding };
    enum class Phase : uint8_t { Head, Fixed, ChunkSize, ChunkLF, Chunk, ChunkCR, ChunkEnd, Trailers, UntilClose };

    struct Head {
        unsigned bytes = 0, column = 0, part = 0, token = 0, status = 0;
        unsigned valueSize = 0;
        uint32_t matches = UINT32_MAX, candidates = UINT32_MAX;
        size_t length = 0;
        int host = -1, encoding = -1, type = -1, grpcEncoding = -1;
        Field field = Field::Ignore;
        bool first = true, cr = false, colon = false, trailingSpace = false;
        bool query = false, route = false, head = false, connect = false;
        bool hasLength = false, chunked = false, hasHost = false;

        bool Character(bool inbound, Site site, char c);
        bool EndLine(bool inbound);
        bool StartLine(bool inbound, Site site, char c);
        bool Header(char c);
    };

    struct Stream {
        Head header;
        Phase phase = Phase::Head;
        size_t left = 0, total = 0;
        unsigned line = 0;
        bool forward = false, digits = false, extension = false, cr = false;
    };
    struct Request { bool eligible = false, head = false; };

    bool BeginBody(bool inbound, Stream& stream, Emit emit, void* context);
    bool BodyByte(bool inbound, Stream& stream, char c, Emit emit, void* context);

    Site site_;
    Stream request_, response_;
    std::array<Request, 8> pending_{};
    size_t first_ = 0, count_ = 0;
};

}  // namespace pili
