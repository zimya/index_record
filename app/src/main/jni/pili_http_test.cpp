// Host-side test: c++ -std=c++17 -Wall -Wextra -Werror pili_http.cpp pili_http_test.cpp -o /tmp/pili_http_test
#include "pili_http.h"

#include <cassert>
#include <string>
#include <utility>
#include <vector>

namespace {
using namespace std::literals;
struct Capture {
    explicit Capture(pili::Site site) : tap(site) {}
    pili::HttpTap tap;
    std::vector<std::pair<bool, std::string>> events;
    static bool Emit(void* ctx, bool inbound, std::string_view bytes) {
        auto& events = static_cast<Capture*>(ctx)->events;
        if (events.empty() || events.back().first != inbound) events.emplace_back(inbound, "");
        events.back().second += bytes;
        return true;
    }
    bool Feed(bool inbound, std::string_view bytes) { return tap.Feed(inbound, bytes, Emit, this); }
    void Fragment(bool inbound, std::string_view bytes) {
        for (char byte : bytes) assert(Feed(inbound, {&byte, 1}));
    }
};

constexpr auto kVideo = "GET /x/web-interface/wbi/view?token=secret HTTP/1.1\r\n"
    "Host: API.BILIBILI.COM:443\r\nCookie: session=secret\r\nAuthorization: Bearer secret\r\n\r\n"sv;
constexpr auto kJson = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
    "Content-Length: 2\r\nSet-Cookie: session=secret\r\n\r\n{}"sv;
constexpr auto kMain = "POST /bilibili.main.community.reply.v1.Reply/MainList HTTP/1.1\r\n"
    "Host: app.bilibili.com\r\nContent-Length: 6\r\n\r\nsecret"sv;
}

int main() {
    assert(pili::IdentifySite("APP.BILIBILI.COM") == pili::Site::MainList);
    assert(pili::IdentifySite("api.bilibili.com.example") == pili::Site::None);
    assert(pili::IdentifySite("grpc.biliapi.net") == pili::Site::None);
    assert(pili::IdentifySite(nullptr) == pili::Site::None);

    Capture video(pili::Site::Video);
    video.Fragment(false, "GET /unrelated HTTP/1.1\r\nHost: api.bilibili.com\r\n\r\n");
    video.Fragment(false, kVideo);
    video.Fragment(true, "HTTP/1.1 103 Early Hints\r\nLink: secret\r\n\r\n");
    video.Fragment(true, "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecret");
    assert(video.events.empty());
    video.Fragment(true, kJson);
    assert(video.events.size() == 2);
    assert(!video.events[0].first && video.events[0].second ==
        "GET /x/web-interface/wbi/view HTTP/1.1\r\nHost: api.bilibili.com\r\nContent-Length: 0\r\n\r\n");
    assert(video.events[1].first && video.events[1].second == "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");

    Capture main(pili::Site::MainList);
    main.Fragment(false, kMain);
    main.Fragment(true, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/grpc\r\n"
        "grpc-encoding: gzip\r\n\r\n2;secret\r\nab\r\n1\r\nc\r\n0\r\nSet-Cookie: secret\r\n\r\n");
    assert(main.events.size() == 2);
    assert(main.events[0].second == "POST /bilibili.main.community.reply.v1.Reply/MainList HTTP/1.1\r\n"
        "Host: app.bilibili.com\r\nContent-Length: 0\r\n\r\n");
    assert(main.events[1].second == "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nGrpc-Encoding: gzip\r\n\r\n"
        "2\r\nab\r\n1\r\nc\r\n0\r\n\r\n");

    for (const auto request : {"GET /x/web-interface/wbi/view HTTP/1.1\r\nHost: app.bilibili.com\r\n\r\n"sv,
        "GET /x/web-interface/wbi/view/ HTTP/1.1\r\nHost: api.bilibili.com\r\n\r\n"sv}) {
        Capture rejected(pili::Site::Video);
        assert(rejected.Feed(false, request));
        assert(rejected.Feed(true, kJson));
        assert(rejected.events.empty());
    }

    Capture malformed(pili::Site::Video);
    assert(malformed.Feed(false, kVideo));
    assert(!malformed.Feed(true, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n"));
    assert(malformed.events.empty());
    Capture early(pili::Site::MainList);
    assert(early.Feed(false, kMain.substr(0, kMain.size() - 6)));
    assert(!early.Feed(true, kJson));
    assert(early.events.empty());
    Capture http2(pili::Site::Video);
    assert(!http2.Feed(false, "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"));
    Capture oversized(pili::Site::Video);
    assert(!oversized.Feed(false, std::string(33 * 1024, 'X')));
}
