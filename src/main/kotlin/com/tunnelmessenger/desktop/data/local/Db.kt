package com.tunnelmessenger.desktop.data.local

import com.tunnelmessenger.desktop.data.crypto.SecureStore
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Локальный кэш: чаты, сообщения (с расшифрованным текстом), P2P-история,
 * кэш E2E-ключей и курсоров. Чаты без истории на сервере живут ТОЛЬКО здесь.
 *
 * Порт SQLite-хелперов Android на sqlite-jdbc: один Connection, все методы
 * synchronized (SQLite-соединение не потокобезопасно), WAL включается PRAGMA.
 *
 * Приватность «данных в покое»: расшифрованные тексты сообщений, метаданные
 * файлов (в file_json лежат ключи TME1) и превью чатов хранятся зашифрованными
 * (AES-GCM на DEK, который сам запечатан мастер-ключом — см. SecureStore).
 * Старые (открытые) значения читаются прозрачно и перезаписываются
 * зашифрованными при первой же правке.
 */
class Db(private val dbFile: File) {

    companion object {
        /** Удалить файлы БД (включая WAL/journal) — при очистке истории. */
        fun deleteFiles(file: File) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(file.absolutePath + suffix).delete()
            }
        }
    }

    private val conn: Connection
    private val lock = Any()

    init {
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.absolutePath)
        synchronized(lock) {
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
            }
            createSchema()
        }
    }

    private fun createSchema() {
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS chats(
                id INTEGER PRIMARY KEY,
                json TEXT NOT NULL,
                peer_read INTEGER DEFAULT 0,
                peer_delivered INTEGER DEFAULT 0
            )"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS messages(
                chat_id INTEGER NOT NULL,
                mid INTEGER NOT NULL,
                client_id TEXT,
                sender TEXT,
                kind TEXT DEFAULT 'user',
                type TEXT DEFAULT 'text',
                body_enc TEXT,
                body_plain TEXT,
                file_json TEXT,
                status TEXT DEFAULT 'sent',
                created_at REAL DEFAULT 0,
                edited_at REAL,
                reactions TEXT,
                PRIMARY KEY(chat_id, mid)
            )"""
            )
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_client ON messages(chat_id, client_id)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS p2p_messages(
                chat_id INTEGER NOT NULL,
                client_id TEXT NOT NULL,
                sender TEXT,
                type TEXT DEFAULT 'text',
                body_plain TEXT,
                file_json TEXT,
                status TEXT DEFAULT 'sent',
                created_at REAL DEFAULT 0,
                PRIMARY KEY(chat_id, client_id)
            )"""
            )
            st.execute("CREATE TABLE IF NOT EXISTS keys(username TEXT PRIMARY KEY, pk TEXT, ts REAL)")
            st.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
        }
    }

    /** Закрыть соединение (перед пересозданием БД). */
    fun close() {
        synchronized(lock) { runCatching { conn.close() } }
    }

    // -------------------------------------------------------- sql-хелперы

    private fun exec(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, block: (ResultSet) -> T): T {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> return block(rs) }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        for ((i, a) in args.withIndex()) {
            when (a) {
                null -> ps.setObject(i + 1, null)
                is String -> ps.setString(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                is Double -> ps.setDouble(i + 1, a)
                is Boolean -> ps.setInt(i + 1, if (a) 1 else 0)
                else -> ps.setObject(i + 1, a)
            }
        }
    }

    private fun ResultSet.str(col: Int): String? = getString(col)
    private fun ResultSet.dbl(col: Int): Double = getDouble(col)

    /** Полная очистка локального кэша (выход из аккаунта). */
    fun wipe() {
        synchronized(lock) {
            exec("DELETE FROM chats")
            exec("DELETE FROM messages")
            exec("DELETE FROM p2p_messages")
            exec("DELETE FROM kv")
        }
    }

    // ------------------------------------------------------------ kv / cursors

    fun kvGet(key: String): String? {
        synchronized(lock) {
            return query("SELECT v FROM kv WHERE k=?", key) { c ->
                if (c.next()) {
                    // превью содержат текст сообщений — они зашифрованы
                    if (key.startsWith("preview:")) SecureStore.openField(c.str(1))
                    else c.str(1)
                } else null
            }
        }
    }

    fun kvSet(key: String, value: String) {
        synchronized(lock) {
            val v = if (key.startsWith("preview:")) SecureStore.sealField(value) else value
            exec("INSERT OR REPLACE INTO kv(k, v) VALUES(?, ?)", key, v)
        }
    }

    // ------------------------------------------------------------------ keys

    fun keyGet(usernameLower: String): String? {
        synchronized(lock) {
            return query("SELECT pk FROM keys WHERE username=?", usernameLower) { c ->
                if (c.next()) c.str(1) else null
            }
        }
    }

    fun keyPut(usernameLower: String, pk: String) {
        synchronized(lock) {
            exec(
                "INSERT OR REPLACE INTO keys(username, pk, ts) VALUES(?, ?, ?)",
                usernameLower, pk, System.currentTimeMillis() / 1000.0,
            )
        }
    }

    /** v8.2: у пользователя сменился публичный ключ E2E — кэш недействителен. */
    fun keyDelete(usernameLower: String) {
        synchronized(lock) { exec("DELETE FROM keys WHERE username=?", usernameLower) }
    }

    /** v8.2: полная очистка кэша ключей (после смены своей пары E2E). */
    fun keysClear() {
        synchronized(lock) { exec("DELETE FROM keys") }
    }

    // ----------------------------------------------------------------- chats

    fun chatPut(id: Long, json: String) {
        synchronized(lock) {
            // v8.x: INSERT OR REPLACE удалял колонки peer_read/peer_delivered
            // (сбрасывал их в 0). Из-за этого после отправки нового сообщения
            // ВСЕ старые свои сообщения «откатывались» с ✓✓ на ✓, пока
            // собеседник снова не зайдёт в чат. Сохраняем курсоры собеседника
            // при каждой перезаписи чата.
            val cursors = chatCursorsLocked(id)
            exec(
                "INSERT OR REPLACE INTO chats(id, json, peer_read, peer_delivered) VALUES(?, ?, ?, ?)",
                id, json, cursors.first, cursors.second,
            )
        }
    }

    fun chatDelete(id: Long) {
        synchronized(lock) {
            exec("DELETE FROM chats WHERE id=?", id)
            exec("DELETE FROM messages WHERE chat_id=?", id)
            exec("DELETE FROM p2p_messages WHERE chat_id=?", id)
        }
    }

    fun chatGet(id: Long): String? {
        synchronized(lock) {
            return query("SELECT json FROM chats WHERE id=?", id) { c ->
                if (c.next()) c.str(1) else null
            }
        }
    }

    fun chatsAll(): List<Pair<Long, String>> {
        synchronized(lock) {
            return query("SELECT id, json FROM chats") { c ->
                val out = mutableListOf<Pair<Long, String>>()
                while (c.next()) out.add(c.getLong(1) to (c.str(2) ?: ""))
                out
            }
        }
    }

    /** курсоры собеседника для отображения статусов */
    fun chatCursors(chatId: Long): Pair<Long, Long> {
        synchronized(lock) { return chatCursorsLocked(chatId) }
    }

    private fun chatCursorsLocked(chatId: Long): Pair<Long, Long> {
        return query("SELECT peer_read, peer_delivered FROM chats WHERE id=?", chatId) { c ->
            if (c.next()) c.getLong(1) to c.getLong(2) else 0L to 0L
        }
    }

    fun chatCursorsSet(chatId: Long, read: Long, delivered: Long) {
        synchronized(lock) {
            exec("UPDATE chats SET peer_read=?, peer_delivered=? WHERE id=?", read, delivered, chatId)
        }
    }

    // -------------------------------------------------------------- messages

    fun msgPut(
        chatId: Long, mid: Long, clientId: String?, sender: String?, kind: String,
        type: String, bodyEnc: String?, bodyPlain: String?, fileJson: String?,
        status: String, createdAt: Double, editedAt: Double?, reactions: String?,
    ) {
        synchronized(lock) {
            // эхо собственного сообщения с тем же client_id заменяет локальную копию
            val existing = clientId?.let { findMidByClientIdLocked(chatId, it) }
            if (existing != null && existing != mid) {
                exec("DELETE FROM messages WHERE chat_id=? AND mid=?", chatId, existing)
            }
            exec(
                """INSERT OR REPLACE INTO messages(
                    chat_id, mid, client_id, sender, kind, type,
                    body_enc, body_plain, file_json, status, created_at, edited_at, reactions
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                chatId, mid, clientId, sender, kind, type,
                bodyEnc, SecureStore.sealField(bodyPlain), SecureStore.sealField(fileJson),
                status, createdAt, editedAt, reactions,
            )
        }
    }

    fun findMidByClientId(chatId: Long, clientId: String): Long? {
        synchronized(lock) { return findMidByClientIdLocked(chatId, clientId) }
    }

    private fun findMidByClientIdLocked(chatId: Long, clientId: String): Long? {
        return query(
            "SELECT mid FROM messages WHERE chat_id=? AND client_id=?", chatId, clientId
        ) { c -> if (c.next()) c.getLong(1) else null }
    }

    fun msgUpdateDecrypted(chatId: Long, mid: Long, bodyPlain: String?) {
        synchronized(lock) {
            exec(
                "UPDATE messages SET body_plain=? WHERE chat_id=? AND mid=?",
                SecureStore.sealField(bodyPlain), chatId, mid,
            )
        }
    }

    fun msgUpdateFileJson(chatId: Long, mid: Long, fileJson: String?) {
        synchronized(lock) {
            exec(
                "UPDATE messages SET file_json=? WHERE chat_id=? AND mid=?",
                SecureStore.sealField(fileJson), chatId, mid,
            )
        }
    }

    fun msgUpdateStatus(chatId: Long, mid: Long, status: String) {
        synchronized(lock) {
            exec("UPDATE messages SET status=? WHERE chat_id=? AND mid=?", status, chatId, mid)
        }
    }

    /** v8.x: статус оптимистичного сообщения по client_id (пометка «не отправлено»). */
    fun msgUpdateStatusByClientId(chatId: Long, clientId: String, status: String) {
        synchronized(lock) {
            exec(
                "UPDATE messages SET status=? WHERE chat_id=? AND client_id=?",
                status, chatId, clientId,
            )
        }
    }

    fun msgUpdateReactions(chatId: Long, mid: Long, reactionsJson: String?) {
        synchronized(lock) {
            exec(
                "UPDATE messages SET reactions=? WHERE chat_id=? AND mid=?",
                reactionsJson, chatId, mid,
            )
        }
    }

    fun msgMarkDeleted(chatId: Long, mid: Long) {
        synchronized(lock) {
            exec(
                """UPDATE messages SET type='deleted', body_plain=NULL, body_enc=NULL, file_json=NULL
                   WHERE chat_id=? AND mid=?""",
                chatId, mid,
            )
        }
    }

    fun msgReplaceEdited(
        chatId: Long, mid: Long, bodyEnc: String?, bodyPlain: String?, editedAt: Double,
    ) {
        synchronized(lock) {
            exec(
                """UPDATE messages SET body_enc=?, body_plain=?, edited_at=?, type='text'
                   WHERE chat_id=? AND mid=?""",
                bodyEnc, SecureStore.sealField(bodyPlain), editedAt, chatId, mid,
            )
        }
    }

    /** Row -> msg fields mapping выполняет Repository */
    fun messagesRange(chatId: Long, limit: Int = 400): List<MsgRow> {
        synchronized(lock) {
            return query(
                """SELECT mid, client_id, sender, kind, type, body_enc, body_plain, file_json,
                      status, created_at, edited_at, reactions
               FROM messages WHERE chat_id=? ORDER BY created_at ASC, mid ASC LIMIT ?""",
                chatId, limit,
            ) { c ->
                val out = mutableListOf<MsgRow>()
                while (c.next()) out.add(rowFrom(c))
                out
            }
        }
    }

    fun msgDelete(chatId: Long, mid: Long) {
        synchronized(lock) {
            exec("DELETE FROM messages WHERE chat_id=? AND mid=?", chatId, mid)
        }
    }

    fun chatClear(chatId: Long) {
        synchronized(lock) {
            exec("DELETE FROM messages WHERE chat_id=?", chatId)
        }
    }

    /** Максимальный id сообщения в чате (для курсора «прочитано»). */
    fun messagesMaxMid(chatId: Long): Long? {
        synchronized(lock) {
            return query("SELECT MAX(mid) FROM messages WHERE chat_id=?", chatId) { c ->
                if (c.next() && c.getObject(1) != null) c.getLong(1) else null
            }
        }
    }

    private fun rowFrom(c: ResultSet): MsgRow = MsgRow(
        mid = c.getLong(1),
        clientId = c.str(2),
        sender = c.str(3),
        kind = c.str(4) ?: "user",
        type = c.str(5) ?: "text",
        bodyEnc = c.str(6),
        bodyPlain = SecureStore.openField(c.str(7)),
        fileJson = SecureStore.openField(c.str(8)),
        status = c.str(9) ?: "sent",
        createdAt = c.dbl(10),
        editedAt = if (c.getObject(11) == null) null else c.dbl(11),
        reactions = c.str(12),
    )

    // ----------------------------------------------------------- p2p history

    fun p2pPut(
        chatId: Long, clientId: String, sender: String, type: String,
        bodyPlain: String?, fileJson: String?, status: String, createdAt: Double,
    ) {
        synchronized(lock) {
            exec(
                """INSERT OR REPLACE INTO p2p_messages(
                    chat_id, client_id, sender, type, body_plain, file_json, status, created_at
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)""",
                chatId, clientId, sender, type,
                SecureStore.sealField(bodyPlain), SecureStore.sealField(fileJson),
                status, createdAt,
            )
        }
    }

    fun p2pStatus(chatId: Long, clientId: String, status: String) {
        synchronized(lock) {
            exec(
                "UPDATE p2p_messages SET status=? WHERE chat_id=? AND client_id=?",
                status, chatId, clientId,
            )
        }
    }

    /** v8: есть ли уже P2P-сообщение с этим client_id (дедуп импорта истории). */
    fun p2pHas(chatId: Long, clientId: String): Boolean {
        synchronized(lock) {
            return query(
                "SELECT 1 FROM p2p_messages WHERE chat_id=? AND client_id=? LIMIT 1",
                chatId, clientId,
            ) { c -> c.next() }
        }
    }

    fun p2pFileJson(chatId: Long, clientId: String, fileJson: String?) {
        synchronized(lock) {
            exec(
                "UPDATE p2p_messages SET file_json=? WHERE chat_id=? AND client_id=?",
                SecureStore.sealField(fileJson), chatId, clientId,
            )
        }
    }

    fun p2pMessages(chatId: Long, limit: Int = 400): List<MsgRow> {
        synchronized(lock) {
            return query(
                """SELECT client_id, sender, type, body_plain, file_json, status, created_at
               FROM p2p_messages WHERE chat_id=? ORDER BY created_at ASC LIMIT ?""",
                chatId, limit,
            ) { c ->
                val out = mutableListOf<MsgRow>()
                while (c.next()) {
                    out.add(
                        MsgRow(
                            mid = -1, clientId = c.str(1), sender = c.str(2),
                            // v12: системные сообщения звонков хранятся с type='system'
                            kind = if ((c.str(3) ?: "text") == "system") "system" else "user",
                            type = c.str(3) ?: "text",
                            bodyEnc = null, bodyPlain = SecureStore.openField(c.str(4)),
                            fileJson = SecureStore.openField(c.str(5)), status = c.str(6) ?: "sent",
                            createdAt = c.dbl(7), editedAt = null, reactions = null,
                        )
                    )
                }
                out
            }
        }
    }
}

/** Универсальная строка сообщения для UI/Repository */
data class MsgRow(
    val mid: Long,
    val clientId: String?,
    val sender: String?,
    val kind: String,
    val type: String,
    val bodyEnc: String?,
    val bodyPlain: String?,
    val fileJson: String?,
    val status: String,
    val createdAt: Double,
    val editedAt: Double?,
    val reactions: String?,
)
