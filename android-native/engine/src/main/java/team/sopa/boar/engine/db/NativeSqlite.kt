package team.sopa.boar.engine.db

import kotlin.concurrent.withLock

internal object SqliteNative {
    init { System.loadLibrary("boar_engine") }
    external fun open(path: ByteArray, flags: Int): Long
    external fun close(db: Long)
    external fun errmsg(db: Long): ByteArray
    external fun exec(db: Long, sql: ByteArray): Int
    external fun changes(db: Long): Long
    external fun lastInsertRowid(db: Long): Long
    external fun prepare(db: Long, sql: ByteArray): Long
    external fun bindNull(st: Long, i: Int): Int
    external fun bindLong(st: Long, i: Int, v: Long): Int
    external fun bindDouble(st: Long, i: Int, v: Double): Int
    external fun bindText(st: Long, i: Int, v: ByteArray): Int
    external fun bindBlob(st: Long, i: Int, v: ByteArray): Int
    external fun step(st: Long): Int
    external fun reset(st: Long): Int
    external fun finalize(st: Long)
    external fun columnCount(st: Long): Int
    external fun columnType(st: Long, i: Int): Int
    external fun columnName(st: Long, i: Int): ByteArray
    external fun columnLong(st: Long, i: Int): Long
    external fun columnDouble(st: Long, i: Int): Double
    external fun columnText(st: Long, i: Int): ByteArray?
    external fun columnBlob(st: Long, i: Int): ByteArray?
    external fun zstdDecompress(src: ByteArray): ByteArray?
}

/**
 * The bundled SQLite (with FTS5) through JNI. One connection shared by every caller: a reentrant lock holds it for each
 * call and for a whole transaction, so another thread's statements can't land inside someone else's BEGIN…COMMIT.
 */
class NativeSqlite private constructor(private var db: Long) : SqlDatabase {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var txDepth = 0

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = lock.withLock { queryLocked(sql, args, map) }

    private fun <T> queryLocked(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val st = prepare(sql, args)
        try {
            val row = NativeRow(st)
            val out = ArrayList<T>()
            while (true) {
                when (val rc = SqliteNative.step(st)) {
                    SQLITE_ROW -> out += map(row)
                    SQLITE_DONE -> return out
                    else -> throw error(rc)
                }
            }
        } finally {
            SqliteNative.finalize(st)
        }
    }

    override fun execute(sql: String, args: List<Any?>): Long = lock.withLock {
        val st = prepare(sql, args)
        try {
            var rc: Int
            do { rc = SqliteNative.step(st) } while (rc == SQLITE_ROW)
            if (rc != SQLITE_DONE) throw error(rc)
            SqliteNative.changes(db)
        } finally {
            SqliteNative.finalize(st)
        }
    }

    override fun exec(script: String) = lock.withLock {
        if (db == 0L) throw SqlException(SQLITE_MISUSE, "database is closed")
        val rc = SqliteNative.exec(db, script.toByteArray())
        if (rc != SQLITE_OK) throw error(rc)
    }

    override fun lastInsertRowid(): Long = SqliteNative.lastInsertRowid(db)

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        if (txDepth > 0) return@withLock block()
        exec("BEGIN")
        txDepth++
        try {
            val r = block()
            exec("COMMIT")
            r
        } catch (e: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw e
        } finally {
            txDepth--
        }
    }

    override fun close() = lock.withLock {
        if (db != 0L) { SqliteNative.close(db); db = 0L }
    }

    private fun prepare(sql: String, args: List<Any?>): Long {
        // A pack closed by reloadPacks while a search still holds it: an error the retriever skips, never a native crash.
        if (db == 0L) throw SqlException(SQLITE_MISUSE, "database is closed")
        val st = SqliteNative.prepare(db, sql.toByteArray())
        if (isError(st)) throw SqlException((-st).toInt(), "${msg()} in: ${sql.take(200)}")
        args.forEachIndexed { k, v ->
            val i = k + 1
            val rc = when (v) {
                null -> SqliteNative.bindNull(st, i)
                is String -> SqliteNative.bindText(st, i, v.toByteArray())
                is ByteArray -> SqliteNative.bindBlob(st, i, v)
                is Long -> SqliteNative.bindLong(st, i, v)
                is Int -> SqliteNative.bindLong(st, i, v.toLong())
                is Boolean -> SqliteNative.bindLong(st, i, if (v) 1 else 0)
                is Double -> SqliteNative.bindDouble(st, i, v)
                is Float -> SqliteNative.bindDouble(st, i, v.toDouble())
                else -> { SqliteNative.finalize(st); throw IllegalArgumentException("can't bind ${v::class}") }
            }
            if (rc != SQLITE_OK) { SqliteNative.finalize(st); throw error(rc) }
        }
        return st
    }

    private fun msg() = String(SqliteNative.errmsg(db))
    private fun error(rc: Int) = SqlException(rc, msg())

    private class NativeRow(private val st: Long) : SqlRow {
        override val columnCount: Int get() = SqliteNative.columnCount(st)
        override fun columnName(i: Int) = String(SqliteNative.columnName(st, i))
        override fun isNull(i: Int) = SqliteNative.columnType(st, i) == SQLITE_NULL
        override fun long(i: Int) = SqliteNative.columnLong(st, i)
        override fun double(i: Int) = SqliteNative.columnDouble(st, i)
        override fun string(i: Int) = SqliteNative.columnText(st, i)?.let { String(it) }
        override fun blob(i: Int) = SqliteNative.columnBlob(st, i)
    }

    companion object {
        private const val SQLITE_OK = 0
        private const val SQLITE_MISUSE = 21
        private const val SQLITE_ROW = 100
        private const val SQLITE_DONE = 101
        private const val SQLITE_NULL = 5
        private const val OPEN_READONLY = 0x1
        private const val OPEN_READWRITE = 0x2
        private const val OPEN_CREATE = 0x4
        /** Lets ATTACH take a "file:...?mode=ro" name; a plain path (no "file:" prefix) is still a plain path. */
        private const val OPEN_URI = 0x40

        /**
         * The bridge returns a handle (a pointer) or an SQLite error code negated. Only a small negative number is an
         * error: on Android 11 to 13 the allocator tags a pointer's top byte, so a valid handle can read as negative too
         * (the Labs build crashed at first launch on a POCO F3, Android 13, Oct 17).
         */
        internal fun isError(handle: Long) = handle in -0xFFFFFL..-1L

        fun open(path: String, readOnly: Boolean = false): NativeSqlite {
            val flags = (if (readOnly) OPEN_READONLY else OPEN_READWRITE or OPEN_CREATE) or OPEN_URI
            val h = SqliteNative.open(path.toByteArray(), flags)
            if (h == 0L || isError(h)) throw SqlException((-h).toInt(), "can't open $path")
            return NativeSqlite(h)
        }

        val zstd = Zstd { src -> SqliteNative.zstdDecompress(src) ?: throw IllegalStateException("zstd: corrupt block") }
    }
}
