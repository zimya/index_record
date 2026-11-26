#include "pili_http.h"

#include <jni.h>

#if !defined(__aarch64__)
#error The bridge is specific to the stock arm64 engine.
#endif

#include <elf.h>
#include <link.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <string_view>

namespace {

// Public ABI prefix of JingMatrix/LSPosed native/include/core/native_api.h.
using Hook = int (*)(void*, void*, void**);
using Unhook = int (*)(void*);
using ModuleLoaded = void (*)(const char*, void*);
struct NativeAPIEntries { uint32_t version; Hook hookFunc; Unhook unhookFunc; };
constexpr uint32_t kNativeApiVersion = 2;

constexpr char kEngine[] = "libflutter.so";
constexpr char kModule[] = "libindexrecord_pili.so";
constexpr std::array<uint8_t, 20> kBuildId = {
    0x5c, 0x0a, 0x94, 0x1b, 0xda, 0x2b, 0x55, 0xbf, 0xb6, 0x8e,
    0x94, 0xa2, 0x7d, 0x70, 0x3b, 0x0b, 0x75, 0xa2, 0x4c, 0x0c,
};
constexpr uintptr_t kRead = 0x77e0d8, kWrite = 0x77e8d8;
constexpr uintptr_t kConnect = 0x8c7088, kFree = 0x8c6d84;
constexpr size_t kSslField = 0x18;  // secure_socket_filter.cc DWARF; also loaded by FreeResources.
constexpr size_t kMaxIo = 64 * 1024;

bool IsFile(const char* path, const char* file) {
    if (!path) return false;
    const char* slash = std::strrchr(path, '/');
    return std::strcmp(slash ? slash + 1 : path, file) == 0;
}

bool InLoadSegment(const dl_phdr_info& image, ElfW(Addr) address, ElfW(Xword) size, unsigned flags) {
    for (size_t i = 0; i < image.dlpi_phnum; ++i) {
        const auto& ph = image.dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || (ph.p_flags & flags) != flags || address < ph.p_vaddr) continue;
        const auto offset = address - ph.p_vaddr;
        if (offset <= ph.p_filesz && size <= ph.p_filesz - offset &&
            offset <= ph.p_memsz && size <= ph.p_memsz - offset &&
            address <= UINTPTR_MAX - image.dlpi_addr && size <= UINTPTR_MAX - image.dlpi_addr - address) return true;
    }
    return false;
}

bool HasBuildId(const dl_phdr_info& image) {
    bool matched = false;
    for (size_t i = 0; i < image.dlpi_phnum; ++i) {
        const auto& ph = image.dlpi_phdr[i];
        if (ph.p_type != PT_NOTE) continue;
        if (ph.p_filesz > 64 * 1024 || !InLoadSegment(image, ph.p_vaddr, ph.p_filesz, PF_R)) return false;
        auto* ptr = reinterpret_cast<const uint8_t*>(image.dlpi_addr + ph.p_vaddr);
        size_t left = ph.p_filesz;
        while (left >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note;
            std::memcpy(&note, ptr, sizeof(note));
            ptr += sizeof(note);
            left -= sizeof(note);
            const size_t name = (static_cast<size_t>(note.n_namesz) + 3) & ~size_t{3};
            const size_t desc = (static_cast<size_t>(note.n_descsz) + 3) & ~size_t{3};
            if (name > left || desc > left - name) return false;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && !std::memcmp(ptr, "GNU", 4)) {
                if (matched || note.n_descsz != kBuildId.size() ||
                    std::memcmp(ptr + name, kBuildId.data(), kBuildId.size())) return false;
                matched = true;
            }
            ptr += name + desc;
            left -= name + desc;
        }
    }
    return matched;
}

struct Engine { uintptr_t bias = 0; unsigned matches = 0; };
int FindEngine(dl_phdr_info* image, size_t, void* data) {
    if (!IsFile(image->dlpi_name, kEngine) || !HasBuildId(*image)) return 0;
    for (uintptr_t address : {kRead, kWrite, kConnect, kFree}) {
        // Validate an executable window, not just the entry byte, for inline patching.
        if (!InLoadSegment(*image, address, 16, PF_X | PF_R)) return 0;
    }
    auto& found = *static_cast<Engine*>(data);
    found.bias = image->dlpi_addr;
    return ++found.matches > 1;
}

struct Callbacks { JavaVM* vm; jclass type; jmethodID bytes; jmethodID closed; };
Callbacks g_java{};  // Immutable after publication; the hooks live for the process lifetime.
std::mutex g_javaMutex;
std::atomic<const Callbacks*> g_callbacks{nullptr};
std::atomic<Hook> g_hook{nullptr};
enum class Install { Armed, Installing, Installed, Failed };
std::atomic<Install> g_install{Install::Armed};
thread_local bool g_inJava = false;

class Env {
public:
    explicit Env(JavaVM* vm) : vm_(vm) {
        void* env = nullptr;
        const jint status = vm_->GetEnv(&env, JNI_VERSION_1_6);
        if (status == JNI_OK) env_ = static_cast<JNIEnv*>(env);
        else if (status == JNI_EDETACHED) {
            attached_ = vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK;
            if (!attached_) env_ = nullptr;
        }
    }
    ~Env() { if (attached_) vm_->DetachCurrentThread(); }
    JNIEnv* get() const { return env_; }
private:
    JavaVM* vm_;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

struct JavaCall {
    JavaCall() { g_inJava = true; }
    ~JavaCall() { g_inJava = false; }
};

struct Connection {
    Connection(void* filter, void* ssl, pili::Site site) : filter(filter), ssl(ssl), tap(site) {}
    void* const filter;
    void* const ssl;
    jlong id = 0;
    std::mutex mutex;
    pili::HttpTap tap;
    std::atomic<bool> disabled{false};
    bool announced = false;
};

std::mutex g_registryMutex;
std::array<std::shared_ptr<Connection>, 64> g_connections;
jlong g_nextId = 1;

void Track(void* filter, void* ssl, pili::Site site) {
    if (!ssl) return;
    std::lock_guard lock(g_registryMutex);
    std::shared_ptr<Connection>* empty = nullptr;
    for (auto& entry : g_connections) {
        if (!entry) { if (!empty) empty = &entry; }
        else if (entry->filter == filter || entry->ssl == ssl) return;
    }
    if (!empty || g_nextId == INT64_MAX) return;
    try {
        auto connection = std::make_shared<Connection>(filter, ssl, site);
        connection->id = g_nextId++;
        *empty = std::move(connection);
    } catch (const std::bad_alloc&) {
        // Observation is best-effort; never fail the host's Connect.
    }
}

std::shared_ptr<Connection> Find(void* ssl) {
    if (g_inJava || g_install.load() != Install::Installed) return {};
    std::lock_guard lock(g_registryMutex);
    for (const auto& entry : g_connections) if (entry && entry->ssl == ssl) return entry;
    return {};
}

std::shared_ptr<Connection> Remove(void* filter) {
    std::lock_guard lock(g_registryMutex);
    for (auto& entry : g_connections) {
        if (entry && entry->filter == filter) {
            entry->disabled = true;
            return std::move(entry);
        }
    }
    return {};
}

void CloseLocked(Connection& connection) {
    connection.disabled = true;
    if (!connection.announced) return;
    connection.announced = false;
    const auto& cb = *g_callbacks.load();
    if (!cb.closed || g_inJava) return;
    Env env(cb.vm);
    JNIEnv* jni = env.get();
    if (!jni || jni->ExceptionCheck()) return;
    JavaCall guard;
    jni->CallStaticVoidMethod(cb.type, cb.closed, connection.id);
    if (jni->ExceptionCheck()) jni->ExceptionClear();
}

struct Delivery {
    Connection& connection;
    const Callbacks& cb;
    std::optional<Env> env{};
    unsigned calls = 0;
};
bool Emit(void* context, bool inbound, std::string_view bytes) {
    auto& d = *static_cast<Delivery*>(context);
    if (d.connection.disabled || ++d.calls > 256) return false;
    if (!d.env) d.env.emplace(d.cb.vm);
    JNIEnv* jni = d.env->get();
    if (!jni || jni->ExceptionCheck()) return false;
    auto array = jni->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array) {
        jni->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
        if (!jni->ExceptionCheck()) {
            d.connection.announced = true;
            JavaCall guard;
            jni->CallStaticVoidMethod(d.cb.type, d.cb.bytes, d.connection.id, static_cast<jboolean>(inbound), array);
        }
        jni->DeleteLocalRef(array);
    }
    if (jni->ExceptionCheck()) { jni->ExceptionClear(); return false; }
    return array != nullptr && !d.connection.disabled;
}

// The pinned Dart SSLFilter serializes SSL I/O for a socket. Keep serialization
// around parsing and callbacks as well; don't hold the registry or loader lock here.
// The Java sink must enqueue any follow-up work instead of waiting for network I/O.
void Observe(Connection& connection, bool inbound, const void* data, int result, int requested) {
    if (result <= 0) return;
    std::lock_guard lock(connection.mutex);
    if (connection.disabled) return;
    if (!data || result > requested || static_cast<size_t>(result) > kMaxIo) { CloseLocked(connection); return; }
    Delivery delivery{connection, *g_callbacks.load()};
    if (!connection.tap.Feed(inbound, {static_cast<const char*>(data), static_cast<size_t>(result)}, Emit, &delivery)) {
        CloseLocked(connection);
    }
}

template <typename Fn>
struct HookSlot {
    std::mutex publication;
    std::atomic<Fn> backup{nullptr};

    Fn Original() {
        if (Fn fn = backup.load()) return fn;
        // Dobby can patch the entry before returning the trampoline. Wait for
        // publication on this slow path, never on a successfully installed hook.
        std::lock_guard lock(publication);
        return backup.load();
    }
    bool InstallAt(Hook hook, uintptr_t address, Fn replacement) {
        std::lock_guard lock(publication);
        void* original = nullptr;
        const int status = hook(reinterpret_cast<void*>(address), reinterpret_cast<void*>(replacement), &original);
        backup.store(reinterpret_cast<Fn>(original));
        return status == 0 && original;
    }
};

HookSlot<int (*)(void*, void*, int)> g_read;
HookSlot<int (*)(void*, const void*, int)> g_write;
HookSlot<void (*)(void*, const char*, void*, bool, bool, bool, void*)> g_connect;
HookSlot<void (*)(void*)> g_free;

int Read(void* ssl, void* bytes, int size) {
    const int before = errno;
    auto original = g_read.Original();
    auto connection = Find(ssl);
    errno = before;
    const int result = original(ssl, bytes, size);
    const int after = errno;
    if (connection) Observe(*connection, true, bytes, result, size);
    connection.reset();
    errno = after;
    return result;
}

int Write(void* ssl, const void* bytes, int size) {
    const int before = errno;
    auto original = g_write.Original();
    auto connection = Find(ssl);
    errno = before;
    const int result = original(ssl, bytes, size);
    const int after = errno;
    if (connection) Observe(*connection, false, bytes, result, size);
    connection.reset();
    errno = after;
    return result;
}

void Connect(void* filter, const char* host, void* ctx, bool server, bool requestCert,
             bool requireCert, void* protocols) {
    const int before = errno;
    auto original = g_connect.Original();
    const auto site = !server && !g_inJava && g_install.load() == Install::Installed
        ? pili::IdentifySite(host) : pili::Site::None;
    errno = before;
    original(filter, host, ctx, server, requestCert, requireCert, protocols);
    const int after = errno;
    if (site != pili::Site::None) {
        void* ssl;
        std::memcpy(&ssl, static_cast<const char*>(filter) + kSslField, sizeof(ssl));
        Track(filter, ssl, site);
    }
    errno = after;
}

void Free(void* filter) {
    const int before = errno;
    auto original = g_free.Original();
    auto connection = g_install.load() == Install::Installed ? Remove(filter) : nullptr;
    errno = before;
    original(filter);
    const int after = errno;
    if (connection && !g_inJava) {
        std::lock_guard lock(connection->mutex);
        CloseLocked(*connection);
    }
    connection.reset();
    errno = after;
}

bool TryInstall() {
    Hook hook = g_hook.load();
    if (!hook || !g_callbacks.load()) return false;
    auto state = g_install.load();
    if (state != Install::Armed) return state != Install::Failed;
    Engine engine;
    dl_iterate_phdr(FindEngine, &engine);
    if (engine.matches != 1) return true;  // Stay armed; there is no verified, unique engine yet.
    if (!g_install.compare_exchange_strong(state, Install::Installing)) return state != Install::Failed;
    // No runtime dlopen/unhook here: the LSPosed module callback can hold its
    // own loader registry lock, and a successfully patched entry may be live.
    const bool ok = g_free.InstallAt(hook, engine.bias + kFree, Free) &&
        g_read.InstallAt(hook, engine.bias + kRead, Read) &&
        g_write.InstallAt(hook, engine.bias + kWrite, Write) &&
        g_connect.InstallAt(hook, engine.bias + kConnect, Connect);
    // A partial install stays pass-through for the lifetime of this process.
    g_install.store(ok ? Install::Installed : Install::Failed);
    return ok;
}

void OnModuleLoaded(const char* name, void* handle) {
    if (handle && (IsFile(name, kEngine) || IsFile(name, kModule))) TryInstall();
}

}  // namespace

extern "C" JNIEXPORT ModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (!entries || entries->version != kNativeApiVersion || !entries->hookFunc) return nullptr;
    Hook empty = nullptr;
    if (!g_hook.compare_exchange_strong(empty, entries->hookFunc)) return nullptr;
    return OnModuleLoaded;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_melonhu_indexrecord_hook_PiliPlusHook_nativeStart(JNIEnv* env, jclass type) {
    if (!g_hook.load()) return JNI_FALSE;
    {
        std::lock_guard lock(g_javaMutex);
        if (const auto* current = g_callbacks.load()) {
            if (!env->IsSameObject(current->type, type)) return JNI_FALSE;
        } else {
            Callbacks cb{};
            if (env->GetJavaVM(&cb.vm) != JNI_OK) return JNI_FALSE;
            cb.bytes = env->GetStaticMethodID(type, "onTlsBytes", "(JZ[B)V");
            if (!cb.bytes) { if (env->ExceptionCheck()) env->ExceptionClear(); return JNI_FALSE; }
            cb.closed = env->GetStaticMethodID(type, "onTlsClosed", "(J)V");
            if (env->ExceptionCheck()) env->ExceptionClear();  // Optional callback.
            cb.type = static_cast<jclass>(env->NewGlobalRef(type));
            if (!cb.type) { if (env->ExceptionCheck()) env->ExceptionClear(); return JNI_FALSE; }
            g_java = cb;
            g_callbacks.store(&g_java);
        }
    }
    return TryInstall() ? JNI_TRUE : JNI_FALSE;
}
