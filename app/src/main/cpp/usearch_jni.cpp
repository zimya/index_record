#include <jni.h>
#include <android/log.h>

#define USEARCH_USE_FP16LIB 0
#define USEARCH_USE_SIMSIMD 0
#define USEARCH_USE_OPENMP 0

#include <usearch/index_dense.hpp>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <queue>
#include <string>
#include <utility>
#include <vector>

using namespace unum::usearch;

namespace {

inline index_dense_t* as_index(jlong ptr) {
    return reinterpret_cast<index_dense_t*>(static_cast<std::uintptr_t>(ptr));
}

inline jlong to_ptr(index_dense_t* idx) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(idx));
}

void throw_runtime_error(JNIEnv* env, char const* msg) {
    jclass ex_cls = env->FindClass("java/lang/IllegalStateException");
    if (ex_cls) {
        env->ThrowNew(ex_cls, msg ? msg : "USearch native error");
    }
}

std::string to_std_string(JNIEnv* env, jstring s) {
    if (!s) return {};
    char const* utf = env->GetStringUTFChars(s, nullptr);
    if (!utf) return {};
    jsize len = env->GetStringUTFLength(s);
    std::string out(utf, static_cast<std::size_t>(len));
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeCreate(
    JNIEnv* env, jclass,
    jlong dimensions, jstring metric_str, jstring quant_str,
    jlong capacity, jlong connectivity, jlong expansion_add, jlong expansion_search
) {
    std::string metric_name = to_std_string(env, metric_str);
    std::string quant_name = to_std_string(env, quant_str);

    metric_kind_t metric_kind = metric_from_name(metric_name.c_str(), metric_name.size());
    scalar_kind_t scalar_kind = scalar_kind_from_name(quant_name.c_str(), quant_name.size());

    metric_punned_t metric(static_cast<std::size_t>(dimensions), metric_kind, scalar_kind);
    if (metric.missing()) {
        throw_runtime_error(env, "Invalid USearch metric or quantization");
        return 0;
    }

    index_dense_config_t config(
        static_cast<std::size_t>(connectivity),
        static_cast<std::size_t>(expansion_add),
        static_cast<std::size_t>(expansion_search)
    );

    auto made = index_dense_t::make(metric, config);
    if (!made) {
        throw_runtime_error(env, made.error.release());
        return 0;
    }

    if (capacity > 0 && !made.index.try_reserve(static_cast<std::size_t>(capacity))) {
        throw_runtime_error(env, "Failed to reserve initial USearch capacity");
        return 0;
    }

    auto* heap_index = new index_dense_t(std::move(made.index));
    return to_ptr(heap_index);
}

JNIEXPORT jlong JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeOpen(
    JNIEnv* env, jclass, jstring path_str, jboolean view_only,
    jlong expansion_add, jlong expansion_search
) {
    std::string path = to_std_string(env, path_str);
    auto made = index_dense_t::make(path.c_str(), view_only == JNI_TRUE);
    if (!made) {
        throw_runtime_error(env, made.error.release());
        return 0;
    }

    // index_dense_t::make(path) leaves expansion_add/expansion_search at 0 because
    // they are not serialized in the file header. Restoring them avoids broken
    // top_limit=0 graph insertions after reopening an existing index.
    std::size_t exp_add = expansion_add > 0 ? static_cast<std::size_t>(expansion_add) : 128;
    std::size_t exp_search = expansion_search > 0 ? static_cast<std::size_t>(expansion_search) : 64;
    made.index.change_expansion_add(exp_add);
    made.index.change_expansion_search(exp_search);

    if (view_only != JNI_TRUE) {
        std::size_t n = made.index.size();
        auto st = made.index.stats();
        std::size_t conn = made.index.connectivity();
        if (conn == 0) conn = 16;
        std::size_t min_expected_edges = n * std::max<std::size_t>(4, conn / 4);
        if (n > 64 && st.edges < min_expected_edges) {
            auto t0 = std::chrono::steady_clock::now();
            std::size_t dims = made.index.dimensions();
            metric_punned_t metric(dims, made.index.metric_kind(), made.index.scalar_kind());
            index_dense_config_t config(conn, exp_add, exp_search);
            auto rebuilt = index_dense_t::make(metric, config);
            if (rebuilt && rebuilt.index.try_reserve(std::max<std::size_t>(n, 1024))) {
                std::vector<index_dense_t::vector_key_t> keys(n);
                made.index.export_keys(keys.data(), 0, n);
                std::vector<float> vec_buf(dims);
                bool ok = true;
                for (index_dense_t::vector_key_t key : keys) {
                    if (made.index.get(key, vec_buf.data()) > 0) {
                        auto add_res = rebuilt.index.add(key, vec_buf.data());
                        if (!add_res) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok && rebuilt.index.size() > 0) {
                    auto new_st = rebuilt.index.stats();
                    std::string tmp_path = path + ".rebuild.tmp";
                    auto save_res = rebuilt.index.save(tmp_path.c_str());
                    if (save_res) {
                        std::remove(path.c_str());
                        std::rename(tmp_path.c_str(), path.c_str());
                    } else {
                        std::remove(tmp_path.c_str());
                    }
                    made.index = std::move(rebuilt.index);
                    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - t0
                    ).count();
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        "usearch_jni",
                        "Rebuilt fractured HNSW index: size=%zu old_edges=%zu new_edges=%zu elapsed_ms=%lld",
                        n,
                        st.edges,
                        new_st.edges,
                        static_cast<long long>(ms)
                    );
                }
            }
        }
    }

    auto* heap_index = new index_dense_t(std::move(made.index));
    return to_ptr(heap_index);
}

JNIEXPORT void JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeDestroy(
    JNIEnv*, jclass, jlong ptr
) {
    auto* idx = as_index(ptr);
    delete idx;
}

JNIEXPORT void JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeSave(
    JNIEnv* env, jclass, jlong ptr, jstring path_str
) {
    auto* idx = as_index(ptr);
    if (!idx) {
        throw_runtime_error(env, "Null USearch index");
        return;
    }
    std::string path = to_std_string(env, path_str);
    auto res = idx->save(path.c_str());
    if (!res) {
        throw_runtime_error(env, res.error.release());
    }
}

JNIEXPORT jlong JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeSize(
    JNIEnv*, jclass, jlong ptr
) {
    auto* idx = as_index(ptr);
    return idx ? static_cast<jlong>(idx->size()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeDimensions(
    JNIEnv*, jclass, jlong ptr
) {
    auto* idx = as_index(ptr);
    return idx ? static_cast<jlong>(idx->dimensions()) : 0L;
}

JNIEXPORT jboolean JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeContains(
    JNIEnv*, jclass, jlong ptr, jlong key
) {
    auto* idx = as_index(ptr);
    if (!idx) return JNI_FALSE;
    return idx->contains(static_cast<index_dense_t::vector_key_t>(key)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeRemove(
    JNIEnv*, jclass, jlong ptr, jlong key
) {
    auto* idx = as_index(ptr);
    if (!idx) return JNI_FALSE;
    auto res = idx->remove(static_cast<index_dense_t::vector_key_t>(key));
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeAdd(
    JNIEnv* env, jclass, jlong ptr, jlong key, jfloatArray vector_arr
) {
    auto* idx = as_index(ptr);
    if (!idx || !vector_arr) {
        throw_runtime_error(env, "Null USearch index or vector");
        return;
    }

    jsize len = env->GetArrayLength(vector_arr);
    if (static_cast<std::size_t>(len) != idx->dimensions()) {
        throw_runtime_error(env, "Vector dimension mismatch");
        return;
    }

    jfloat* vec = env->GetFloatArrayElements(vector_arr, nullptr);
    if (!vec) {
        throw_runtime_error(env, "Failed to read vector array");
        return;
    }

    auto ukey = static_cast<index_dense_t::vector_key_t>(key);
    if (idx->contains(ukey)) {
        idx->remove(ukey);
    }

    if (idx->size() >= idx->capacity()) {
        std::size_t next_cap = std::max<std::size_t>(idx->capacity() * 2, idx->size() + 128);
        if (!idx->try_reserve(next_cap)) {
            env->ReleaseFloatArrayElements(vector_arr, vec, JNI_ABORT);
            throw_runtime_error(env, "Failed to grow USearch capacity");
            return;
        }
    }

    auto res = idx->add(ukey, reinterpret_cast<float const*>(vec));
    env->ReleaseFloatArrayElements(vector_arr, vec, JNI_ABORT);
    if (!res) {
        throw_runtime_error(env, res.error.release());
    }
}

JNIEXPORT jint JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeSearchFiltered(
    JNIEnv* env, jclass,
    jlong ptr, jfloatArray query_arr, jint limit,
    jlong key_mask, jlong expected_value,
    jlongArray out_keys, jfloatArray out_distances
) {
    auto* idx = as_index(ptr);
    if (!idx || !query_arr || !out_keys || !out_distances || limit <= 0) {
        return 0;
    }

    if (idx->size() == 0) {
        return 0;
    }

    jsize qlen = env->GetArrayLength(query_arr);
    if (static_cast<std::size_t>(qlen) != idx->dimensions()) {
        throw_runtime_error(env, "Query vector dimension mismatch");
        return 0;
    }

    jfloat* query = env->GetFloatArrayElements(query_arr, nullptr);
    if (!query) {
        throw_runtime_error(env, "Failed to read query vector");
        return 0;
    }

    auto mask_u = static_cast<std::uint64_t>(key_mask);
    auto expected_u = static_cast<std::uint64_t>(expected_value);

    auto search_res = (mask_u == 0ULL)
        ? idx->search(reinterpret_cast<float const*>(query), static_cast<std::size_t>(limit))
        : idx->filtered_search(
            reinterpret_cast<float const*>(query),
            static_cast<std::size_t>(limit),
            [mask_u, expected_u](index_dense_t::vector_key_t k) noexcept {
                return (static_cast<std::uint64_t>(k) & mask_u) == expected_u;
            }
        );

    env->ReleaseFloatArrayElements(query_arr, query, JNI_ABORT);

    if (!search_res) {
        throw_runtime_error(env, search_res.error.release());
        return 0;
    }

    std::size_t max_out = static_cast<std::size_t>(
        std::min(env->GetArrayLength(out_keys), env->GetArrayLength(out_distances))
    );
    std::size_t count = std::min<std::size_t>(search_res.size(), max_out);
    if (count == 0) return 0;

    std::vector<index_dense_t::vector_key_t> keys_buf(count);
    std::vector<index_dense_t::distance_t> dist_buf(count);
    std::size_t dumped = search_res.dump_to(keys_buf.data(), dist_buf.data(), count);

    std::vector<jlong> jkeys(dumped);
    std::vector<jfloat> jdists(dumped);
    for (std::size_t i = 0; i < dumped; ++i) {
        jkeys[i] = static_cast<jlong>(keys_buf[i]);
        jdists[i] = static_cast<jfloat>(dist_buf[i]);
    }

    env->SetLongArrayRegion(out_keys, 0, static_cast<jsize>(dumped), jkeys.data());
    env->SetFloatArrayRegion(out_distances, 0, static_cast<jsize>(dumped), jdists.data());
    return static_cast<jint>(dumped);
}

JNIEXPORT jint JNICALL
Java_com_melonhu_indexrecord_search_UsearchNative_nativeSearchInKeys(
    JNIEnv* env, jclass,
    jlong ptr, jfloatArray query_arr, jlongArray candidate_keys_arr, jint limit,
    jlongArray out_keys, jfloatArray out_distances
) {
    auto* idx = as_index(ptr);
    if (!idx || !query_arr || !candidate_keys_arr || !out_keys || !out_distances || limit <= 0) {
        return 0;
    }

    jsize num_candidates = env->GetArrayLength(candidate_keys_arr);
    if (num_candidates == 0 || idx->size() == 0) {
        return 0;
    }

    std::size_t dims = idx->dimensions();
    jsize qlen = env->GetArrayLength(query_arr);
    if (static_cast<std::size_t>(qlen) != dims) {
        throw_runtime_error(env, "Query vector dimension mismatch");
        return 0;
    }

    jfloat* query = env->GetFloatArrayElements(query_arr, nullptr);
    if (!query) {
        throw_runtime_error(env, "Failed to read query vector");
        return 0;
    }

    jlong* cand_keys = env->GetLongArrayElements(candidate_keys_arr, nullptr);
    if (!cand_keys) {
        env->ReleaseFloatArrayElements(query_arr, query, JNI_ABORT);
        throw_runtime_error(env, "Failed to read candidate keys");
        return 0;
    }

    struct ScoredItem {
        jlong key;
        float distance;
        bool operator<(ScoredItem const& other) const {
            return distance < other.distance; // Max-heap: largest distance on top
        }
    };
    std::priority_queue<ScoredItem> top_heap;
    std::vector<float> vec_buf(dims);

    for (jsize i = 0; i < num_candidates; ++i) {
        auto ukey = static_cast<index_dense_t::vector_key_t>(cand_keys[i]);
        if (!idx->contains(ukey)) continue;
        if (idx->get(ukey, vec_buf.data()) == 0) continue;

        float dot = 0.0f;
        for (std::size_t d = 0; d < dims; ++d) {
            dot += query[d] * vec_buf[d];
        }
        float dist = 1.0f - dot;

        if (static_cast<jint>(top_heap.size()) < limit) {
            top_heap.push({cand_keys[i], dist});
        } else if (dist < top_heap.top().distance) {
            top_heap.pop();
            top_heap.push({cand_keys[i], dist});
        }
    }

    env->ReleaseLongArrayElements(candidate_keys_arr, cand_keys, JNI_ABORT);
    env->ReleaseFloatArrayElements(query_arr, query, JNI_ABORT);

    jsize count = static_cast<jsize>(top_heap.size());
    std::vector<ScoredItem> sorted_results(count);
    for (jsize i = count - 1; i >= 0; --i) {
        sorted_results[i] = top_heap.top();
        top_heap.pop();
    }

    std::vector<jlong> jkeys(count);
    std::vector<jfloat> jdists(count);
    for (jsize i = 0; i < count; ++i) {
        jkeys[i] = sorted_results[i].key;
        jdists[i] = sorted_results[i].distance;
    }

    env->SetLongArrayRegion(out_keys, 0, count, jkeys.data());
    env->SetFloatArrayRegion(out_distances, 0, count, jdists.data());
    return count;
}

} // extern "C"
