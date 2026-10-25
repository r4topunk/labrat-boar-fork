// SQLite (bundled 3.53.4 with FTS5, so it doesn't depend on the phone's own build) and zstd decompression,
// as a thin statement API for team.sopa.boar.engine.db. Text crosses as UTF-8 byte arrays.
#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

#include "sqlite3.h"
#define ZSTD_STATIC_LINKING_ONLY
#include "zstd.h"

namespace {
sqlite3 * DB(jlong h) { return reinterpret_cast<sqlite3 *>(h); }
sqlite3_stmt * ST(jlong h) { return reinterpret_cast<sqlite3_stmt *>(h); }

std::string utf8(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(n, '\0');
    if (n) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray bytes(JNIEnv * env, const void * p, int n) {
    jbyteArray a = env->NewByteArray(n);
    if (n > 0) env->SetByteArrayRegion(a, 0, n, reinterpret_cast<const jbyte *>(p));
    return a;
}
}  // namespace

extern "C" {

#define FN(name) Java_team_sopa_boar_engine_db_SqliteNative_##name

JNIEXPORT jlong JNICALL FN(open)(JNIEnv * env, jobject, jbyteArray jpath, jint flags) {
    std::string path = utf8(env, jpath);
    sqlite3 * db = nullptr;
    int rc = sqlite3_open_v2(path.c_str(), &db, flags | SQLITE_OPEN_FULLMUTEX, nullptr);
    if (rc != SQLITE_OK) {
        if (db) sqlite3_close_v2(db);
        return -(jlong) rc;  // negative = error code
    }
    sqlite3_busy_timeout(db, 5000);
    sqlite3_extended_result_codes(db, 1);
    return reinterpret_cast<jlong>(db);
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *, jobject, jlong h) { sqlite3_close_v2(DB(h)); }

JNIEXPORT jbyteArray JNICALL FN(errmsg)(JNIEnv * env, jobject, jlong h) {
    const char * m = sqlite3_errmsg(DB(h));
    return bytes(env, m, (int) strlen(m));
}

JNIEXPORT jint JNICALL FN(exec)(JNIEnv * env, jobject, jlong h, jbyteArray jsql) {
    std::string sql = utf8(env, jsql);
    return sqlite3_exec(DB(h), sql.c_str(), nullptr, nullptr, nullptr);
}

JNIEXPORT jlong JNICALL FN(changes)(JNIEnv *, jobject, jlong h) { return sqlite3_changes64(DB(h)); }
JNIEXPORT jlong JNICALL FN(lastInsertRowid)(JNIEnv *, jobject, jlong h) { return sqlite3_last_insert_rowid(DB(h)); }

JNIEXPORT jlong JNICALL FN(prepare)(JNIEnv * env, jobject, jlong h, jbyteArray jsql) {
    std::string sql = utf8(env, jsql);
    sqlite3_stmt * st = nullptr;
    int rc = sqlite3_prepare_v3(DB(h), sql.c_str(), (int) sql.size(), 0, &st, nullptr);
    if (rc != SQLITE_OK) return -(jlong) rc;
    return reinterpret_cast<jlong>(st);
}

JNIEXPORT jint JNICALL FN(bindNull)(JNIEnv *, jobject, jlong s, jint i) { return sqlite3_bind_null(ST(s), i); }
JNIEXPORT jint JNICALL FN(bindLong)(JNIEnv *, jobject, jlong s, jint i, jlong v) { return sqlite3_bind_int64(ST(s), i, v); }
JNIEXPORT jint JNICALL FN(bindDouble)(JNIEnv *, jobject, jlong s, jint i, jdouble v) { return sqlite3_bind_double(ST(s), i, v); }
JNIEXPORT jint JNICALL FN(bindText)(JNIEnv * env, jobject, jlong s, jint i, jbyteArray v) {
    std::string t = utf8(env, v);
    return sqlite3_bind_text64(ST(s), i, t.data(), t.size(), SQLITE_TRANSIENT, SQLITE_UTF8);
}
JNIEXPORT jint JNICALL FN(bindBlob)(JNIEnv * env, jobject, jlong s, jint i, jbyteArray v) {
    std::string b = utf8(env, v);
    return sqlite3_bind_blob64(ST(s), i, b.data(), b.size(), SQLITE_TRANSIENT);
}

JNIEXPORT jint JNICALL FN(step)(JNIEnv *, jobject, jlong s) { return sqlite3_step(ST(s)); }
JNIEXPORT jint JNICALL FN(reset)(JNIEnv *, jobject, jlong s) { sqlite3_clear_bindings(ST(s)); return sqlite3_reset(ST(s)); }
JNIEXPORT void JNICALL FN(finalize)(JNIEnv *, jobject, jlong s) { sqlite3_finalize(ST(s)); }

JNIEXPORT jint JNICALL FN(columnCount)(JNIEnv *, jobject, jlong s) { return sqlite3_column_count(ST(s)); }
JNIEXPORT jint JNICALL FN(columnType)(JNIEnv *, jobject, jlong s, jint i) { return sqlite3_column_type(ST(s), i); }
JNIEXPORT jbyteArray JNICALL FN(columnName)(JNIEnv * env, jobject, jlong s, jint i) {
    const char * n = sqlite3_column_name(ST(s), i);
    return bytes(env, n, n ? (int) strlen(n) : 0);
}
JNIEXPORT jlong JNICALL FN(columnLong)(JNIEnv *, jobject, jlong s, jint i) { return sqlite3_column_int64(ST(s), i); }
JNIEXPORT jdouble JNICALL FN(columnDouble)(JNIEnv *, jobject, jlong s, jint i) { return sqlite3_column_double(ST(s), i); }
JNIEXPORT jbyteArray JNICALL FN(columnText)(JNIEnv * env, jobject, jlong s, jint i) {
    const unsigned char * t = sqlite3_column_text(ST(s), i);
    if (!t) return nullptr;
    return bytes(env, t, sqlite3_column_bytes(ST(s), i));
}
JNIEXPORT jbyteArray JNICALL FN(columnBlob)(JNIEnv * env, jobject, jlong s, jint i) {
    const void * b = sqlite3_column_blob(ST(s), i);
    int n = sqlite3_column_bytes(ST(s), i);
    if (!b && n == 0) return sqlite3_column_type(ST(s), i) == SQLITE_NULL ? nullptr : bytes(env, "", 0);
    return bytes(env, b, n);
}

// One zstd frame (or several concatenated) → bytes. Streams, so frames without a content size work too.
JNIEXPORT jbyteArray JNICALL FN(zstdDecompress)(JNIEnv * env, jobject, jbyteArray jsrc) {
    std::string src = utf8(env, jsrc);
    ZSTD_DCtx * dctx = ZSTD_createDCtx();
    std::vector<char> out;
    unsigned long long hint = ZSTD_getFrameContentSize(src.data(), src.size());
    if (hint != ZSTD_CONTENTSIZE_UNKNOWN && hint != ZSTD_CONTENTSIZE_ERROR && hint < (1ull << 31)) out.reserve((size_t) hint);
    std::vector<char> buf(ZSTD_DStreamOutSize());
    ZSTD_inBuffer in = {src.data(), src.size(), 0};
    bool failed = false;
    size_t last = 0;
    // Feed all input; keep draining while a call fills the whole output buffer.
    while (true) {
        ZSTD_outBuffer o = {buf.data(), buf.size(), 0};
        last = ZSTD_decompressStream(dctx, &o, &in);
        if (ZSTD_isError(last)) { failed = true; break; }
        out.insert(out.end(), buf.data(), buf.data() + o.pos);
        if (in.pos == in.size && o.pos < o.size) break;
    }
    if (!failed && last != 0) failed = true;  // truncated frame
    ZSTD_freeDCtx(dctx);
    if (failed) return nullptr;
    return bytes(env, out.data(), (int) out.size());
}

}  // extern "C"
