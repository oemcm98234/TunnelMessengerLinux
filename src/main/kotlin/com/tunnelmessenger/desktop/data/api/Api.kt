package com.tunnelmessenger.desktop.data.api

import com.tunnelmessenger.desktop.data.crypto.Tme1
import com.tunnelmessenger.desktop.data.model.AuthResp
import com.tunnelmessenger.desktop.data.model.BlockCheckResp
import com.tunnelmessenger.desktop.data.model.BlockEntry
import com.tunnelmessenger.desktop.data.model.Chat
import com.tunnelmessenger.desktop.data.model.ChatResp
import com.tunnelmessenger.desktop.data.model.ChatsResp
import com.tunnelmessenger.desktop.data.model.ContactsResp
import com.tunnelmessenger.desktop.data.model.HealthResp
import com.tunnelmessenger.desktop.data.model.KeyResp
import com.tunnelmessenger.desktop.data.model.MeResp
import com.tunnelmessenger.desktop.data.model.MessagesResp
import com.tunnelmessenger.desktop.data.model.MsgResp
import com.tunnelmessenger.desktop.data.model.OkResp
import com.tunnelmessenger.desktop.data.model.ReactResp
import com.tunnelmessenger.desktop.data.model.SearchResp
import com.tunnelmessenger.desktop.data.model.UpdateCheckResp
import com.tunnelmessenger.desktop.data.model.UpdateInfo
import com.tunnelmessenger.desktop.data.model.UserShort
import com.tunnelmessenger.desktop.net.HttpRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ApiError(val code: Int, message: String) : Exception(message)

/**
 * REST-клиент Tunnel Messenger. Транспорт — HTTP (без TLS: шифрование
 * обеспечивает AmneziaWG-туннель). Авторизация: Bearer-токен.
 */
class Api(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** Клиент маршрутизируется через локальный SOCKS5-прокси туннеля (если поднят). */
    private val http get() = HttpRouter.client()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun base(): String = baseUrlProvider().trimEnd('/')

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun buildRequest(
        method: String,
        path: String,
        body: JsonElement? = null,
        auth: Boolean = true,
    ): Request {
        val bodyStr = body?.let { json.encodeToString(JsonElement.serializer(), it) }
        val media = jsonMedia
        val rb = Request.Builder().url(base() + path)
        if (auth) tokenProvider()?.let { rb.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            "PATCH" -> rb.patch((bodyStr ?: "").toRequestBody(media))
            "PUT" -> rb.put((bodyStr ?: "").toRequestBody(media))
            else -> rb.post((bodyStr ?: "").toRequestBody(media))
        }
        return rb.build()
    }

    /** Выполнить запрос, вернуть JSON-объект ответа. */
    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        auth: Boolean = true,
    ): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(buildRequest(method, path, body, auth)).execute().use { resp ->
            handle(resp)
        }
    }

    private fun handle(resp: Response): JsonObject {
        val text = resp.body?.string().orEmpty()
        val obj = if (text.isNotBlank()) {
            try {
                json.decodeFromString(JsonObject.serializer(), text)
            } catch (_: Exception) {
                null
            }
        } else null
        if (!resp.isSuccessful) {
            val err = obj?.get("error")?.toString()?.trim('"') ?: "HTTP ${resp.code}"
            throw ApiError(resp.code, err)
        }
        return obj ?: JsonObject(emptyMap())
    }

    // ----------------------------------------------------------- авторизация

    suspend fun register(username: String, password: String): AuthResp {
        val body = buildJsonObject { put("username", username); put("password", password) }
        return json.decodeFromJsonElement(AuthResp.serializer(), request("POST", "/api/auth/register", body))
    }

    suspend fun login(username: String, password: String): AuthResp {
        val body = buildJsonObject { put("username", username); put("password", password) }
        return json.decodeFromJsonElement(AuthResp.serializer(), request("POST", "/api/auth/login", body))
    }

    suspend fun logout(): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/auth/logout"))

    suspend fun me(): MeResp = json.decodeFromJsonElement(MeResp.serializer(), request("GET", "/api/me"))

    suspend fun health(): HealthResp =
        json.decodeFromJsonElement(HealthResp.serializer(), request("GET", "/api/health", auth = false))

    suspend fun setNickname(nickname: String): OkResp {
        val body = buildJsonObject { put("nickname", nickname) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/me/nickname", body))
    }

    /** v8.2: безвозвратное удаление своего аккаунта на сервере
     *  (клиент обязан запросить двойное подтверждение до вызова). */
    suspend fun accountDelete(): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/account/delete"))

    // ------------------------------------------------------- пользователи

    suspend fun searchUsers(q: String): SearchResp =
        json.decodeFromJsonElement(SearchResp.serializer(), request("GET", "/api/users/search?q=${enc(q)}"))

    /** Профиль пользователя: {username, nickname, bio, online, last_seen} (плоский объект). */
    suspend fun userProfile(username: String): UserShort =
        json.decodeFromJsonElement(UserShort.serializer(), request("GET", "/api/users/${enc(username)}"))

    suspend fun chatGet(chatId: Long): Chat {
        val resp = json.decodeFromJsonElement(ChatResp.serializer(), request("GET", "/api/chats/$chatId"))
        return resp.chat ?: throw ApiError(500, "пустой ответ сервера")
    }

    suspend fun userKey(username: String): String? = try {
        val resp = json.decodeFromJsonElement(KeyResp.serializer(), request("GET", "/api/users/${enc(username)}/key"))
        resp.public_key
    } catch (e: ApiError) {
        if (e.code == 404) null else throw e
    }

    suspend fun publishKey(publicKeyB64: String): OkResp {
        val body = buildJsonObject { put("public_key", publicKeyB64) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/keys", body))
    }

    // ------------------------------------------------------------ контакты

    suspend fun contacts(): ContactsResp =
        json.decodeFromJsonElement(ContactsResp.serializer(), request("GET", "/api/contacts"))

    suspend fun contactAdd(address: String): OkResp {
        val body = buildJsonObject { put("address", address) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/contacts", body))
    }

    suspend fun contactDelete(address: String): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/contacts/${enc(address)}"))

    // ------------------------------------------------------- блокировки (v11)

    suspend fun blocksList(): List<BlockEntry> {
        val resp = request("GET", "/api/blocks")
        val arr = resp["blocks"] as? JsonArray ?: JsonArray(emptyList())
        return arr.map { json.decodeFromJsonElement(BlockEntry.serializer(), it) }
    }

    suspend fun blockAdd(address: String): OkResp {
        val body = buildJsonObject { put("address", address) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/blocks", body))
    }

    suspend fun blockDelete(address: String): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/blocks/${enc(address)}"))

    /** Проверить статус блокировки с адресом (для UI чата/профиля). */
    suspend fun blockCheck(address: String): BlockCheckResp =
        json.decodeFromJsonElement(
            BlockCheckResp.serializer(), request("GET", "/api/blocks/check?address=${enc(address)}")
        )

    // ------------------------------------------------------- обновления (v11)
    // Все эндпоинты обновлений идут ЧЕРЕЗ ТУННЕЛЬ (как и остальные запросы
    // мессенджера) — приватность: реальный IP не раскрывается серверу.
    // Эндпоинты /api/updates/* и /updates/* публичны (без авторизации),
    // но идут через SOCKS5 туннеля.

    /** Метаданные последней версии клиента для платформы. */
    suspend fun updatesLatest(platform: String): UpdateInfo =
        json.decodeFromJsonElement(
            UpdateInfo.serializer(), request("GET", "/api/updates/latest?platform=${enc(platform)}", auth = false)
        )

    /** Сравнить текущую версию клиента с серверной. */
    suspend fun updatesCheck(currentVersionCode: Int, platform: String): UpdateCheckResp =
        json.decodeFromJsonElement(
            UpdateCheckResp.serializer(),
            request("GET", "/api/updates/check?platform=${enc(platform)}&version=$currentVersionCode", auth = false)
        )

    /**
     * Скачать файл обновления во временный поток с прогрессом. НЕ через
     * стандартный request(), т.к. нужен бинарный поток. Файл пишется в [out];
     * onProgress(скачано, всего). Возвращает размер файла.
     *
     * Идёт через туннель (как все остальные запросы).
     */
    suspend fun downloadUpdate(
        filename: String,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val url = base() + "/updates/${enc(filename)}"
        val rb = Request.Builder().url(url).get()
        // файл обновления — публичный, без Bearer-токена
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, "ошибка загрузки (${resp.code})")
            val total = resp.body?.contentLength() ?: -1L
            val src = resp.body?.byteStream() ?: throw ApiError(500, "пустой ответ сервера")
            val buf = ByteArray(64 * 1024)
            var got = 0L
            var markGot = 0L
            var markMs = 0L
            while (true) {
                val n = src.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                got += n
                val now = System.currentTimeMillis()
                if (got - markGot >= 128 * 1024 || now - markMs >= 150) {
                    onProgress(got, total)
                    markGot = got
                    markMs = now
                }
            }
            out.flush()
            if (total > 0) onProgress(total, total)
            got
        }
    }

    /**
     * v13: скачать текстовый чейнжлог обновления — GET /updates/<имя>.txt
     * (публичный файл рядом с файлом релиза, без Bearer-токена; сервер отдаёт
     * text/plain; charset=utf-8). Кнопка «Что нового?» в настройках.
     */
    suspend fun downloadUpdateText(filename: String): String = withContext(Dispatchers.IO) {
        val url = base() + "/updates/${enc(filename)}"
        val rb = Request.Builder().url(url).get()
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, "чейнжлог недоступен (${resp.code})")
            resp.body?.string() ?: throw ApiError(500, "пустой ответ сервера")
        }
    }

    /**
     * МНОГОПОТОЧНОЕ скачивание файла обновления — сегменты
     * по 4 МиБ параллельными Range-запросами, как файлы сообщений (v12-o/
     * v13-d): одна TCP-сессия через userspace-движок туннеля ограничена
     * окном/RTT (типично 200-300 КиБ/с), 8-12 потоков со старта (число
     * случайное, анти-DPI) с адаптивным разгоном до 24-32 агрегируют
     * пропускную способность. Сервер раздаёт файлы из каталога /updates/
     * через FileResponse
     * с нативной поддержкой Range (206 + Content-Range).
     *
     * Сегменты собираются во временный файл в [tempDir], затем он
     * переименовывается в [dest] (итоговый файл). Возвращает размер
     * скачанного файла или null — сервер не поддержал Range (200 на пробу) /
     * файл меньше порога параллельности: вызывающий уходит на обычное
     * последовательное скачивание [downloadUpdate].
     *
     * Ретраи сегмента: до 4 попыток на 429/5xx с учётом Retry-After
     * (лимит update_download на сервере поднят именно под параллельную
     * докачку — см. docs/UPDATES.md).
     */
    suspend fun downloadUpdateParallel(
        filename: String,
        dest: File,
        tempDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long? = withContext(Dispatchers.IO) {
        // проба поддержки Range: просим 1 байт; заодно из Content-Range
        // узнаём полный размер ("bytes 0-0/<size>")
        val probe = Request.Builder().url(base() + "/updates/${enc(filename)}")
            .header("Range", "bytes=0-0").get()
        val (rangeOk, size) = http.newCall(probe.build()).execute().use { resp ->
            if (resp.code == 404) throw ApiError(404, "обновление не найдено (404)")
            val cr = resp.header("Content-Range")
            val total = cr?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: -1L
            (resp.code == 206) to total
        }
        if (!rangeOk || size <= 0 || size < PARALLEL_MIN_BYTES) return@withContext null

        val tmp = File.createTempFile("tmupd", ".part", tempDir)
        val done = AtomicLong(0)
        var markDone = 0L
        var markMs = 0L
        fun tick() {
            val now = System.currentTimeMillis()
            val cur = done.get()
            if (cur - markDone >= 256 * 1024 || now - markMs >= 150) {
                onProgress(cur, size)
                markDone = cur
                markMs = now
            }
        }
        try {
            RandomAccessFile(tmp, "rw").use { raf -> raf.setLength(size) }
            // общая очередь сегментов 4 МиБ — медленный поток не держит
            // большой кусок, работу подхватывают добавленные разгоном потоки
            val segCount = ((size + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)
            val nextSeg = AtomicInteger(0)
            val takeSeg: () -> Int = {
                val s = nextSeg.getAndIncrement()
                if (s < segCount) s else -1
            }
            val laneWorker: suspend () -> Unit = {
                while (true) {
                    val seq = takeSeg()
                    if (seq < 0) break
                    val from = seq.toLong() * PART_SIZE
                    val to = minOf(size, from + PART_SIZE) - 1
                    var lastErr: Exception? = null
                    var waitMs = 0L
                    for (att in 0 until 4) {
                        try {
                            val rb = Request.Builder()
                                .url(base() + "/updates/${enc(filename)}")
                                .header("Range", "bytes=$from-$to").get()
                            http.newCall(rb.build()).execute().use { resp ->
                                if (resp.code == 429 || resp.code >= 500) {
                                    val ra = resp.header("Retry-After")?.trim()?.toLongOrNull()
                                        ?: (att + 1L)
                                    waitMs = ra.coerceAtMost(10L) * 1000L
                                    throw ApiError(resp.code,
                                        "превышен лимит/сбой (${resp.code})")
                                }
                                if (resp.code != 206) {
                                    throw ApiError(resp.code, "Range не поддержан (${resp.code})")
                                }
                                val stream = resp.body?.byteStream()
                                    ?: throw ApiError(500, "пустой ответ")
                                val buf = ByteArray(256 * 1024)
                                var pos = from
                                RandomAccessFile(tmp, "rw").use { raf ->
                                    while (pos <= to) {
                                        val want = minOf(buf.size.toLong(), to - pos + 1).toInt()
                                        val n = stream.read(buf, 0, want)
                                        if (n < 0) break
                                        raf.seek(pos)
                                        raf.write(buf, 0, n)
                                        pos += n
                                        done.addAndGet(n.toLong())
                                        tick()
                                    }
                                }
                                if (pos != to + 1) throw ApiError(500, "файл оборван (Range)")
                            }
                            // успех сегмента: вне use-ламбды — break не выходит
                            // из inline-лямбды (экспериментальная фича), а lastErr
                            // не модифицируется замыканием (сохраняется smart-cast)
                            lastErr = null
                            break
                        } catch (e: ApiError) {
                            lastErr = e
                            if (att == 3) break
                            if (waitMs <= 0) waitMs = 600L * (att + 1)
                            delay(waitMs)
                        } catch (e: java.io.IOException) {
                            lastErr = e
                            if (att == 3) break
                            delay(600L * (att + 1))
                        }
                    }
                    if (lastErr != null) throw lastErr
                }
            }
            val jobs = CopyOnWriteArrayList<Job>()
            coroutineScope {
                val start = minOf(randomStartLanes(), segCount).coerceAtLeast(1)
                val cap = maxOf(start, randomCapLanes())
                repeat(start) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                // адаптивный разгон (как в uploadFileParallel) — меряем
                // принятые байты; насыщение канала останавливает разгон
                val ramp = if (segCount > start) launch(Dispatchers.IO) {
                    var lastBytes = done.get()
                    var lastT = System.currentTimeMillis()
                    var lastSpeed = 0.0
                    while (true) {
                        delay(RAMP_MS)
                        if (jobs.size >= cap) continue
                        val b = done.get()
                        val now = System.currentTimeMillis()
                        val dt = now - lastT
                        if (dt <= 0) continue
                        val speed = (b - lastBytes).toDouble() / dt
                        if (lastSpeed > 0 && speed > 0 && speed < lastSpeed * (1 + RAMP_GAIN_MIN)) break
                        lastBytes = b
                        lastT = now
                        lastSpeed = speed
                        val add = minOf(RAMP_STEP, cap - jobs.size)
                        repeat(add) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                    }
                } else null
                try {
                    jobs.joinAll()
                } finally {
                    ramp?.cancel()
                }
            }
            if (done.get() != size) throw ApiError(500, "размер не сошёлся")
            onProgress(size, size)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                // разные ФС (tempDir и updatesDir) — копируем
                tmp.inputStream().use { ins ->
                    dest.outputStream().use { outs ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                        }
                    }
                }
                tmp.delete()
            }
            size
        } finally {
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- чаты

    suspend fun chats(): ChatsResp = json.decodeFromJsonElement(ChatsResp.serializer(), request("GET", "/api/chats"))

    suspend fun chatCreate(address: String, isP2p: Boolean): Chat {
        val body = buildJsonObject { put("address", address); put("is_p2p", isP2p) }
        return json.decodeFromJsonElement(ChatResp.serializer(), request("POST", "/api/chats", body)).chat
            ?: throw ApiError(500, "пустой ответ сервера")
    }

    suspend fun chatCreateGroup(title: String, members: List<String>): Chat {
        val body = buildJsonObject {
            put("title", title)
            put("members", JsonArray(members.map { JsonPrimitive(it) }))
        }
        return json.decodeFromJsonElement(ChatResp.serializer(), request("POST", "/api/chats", body)).chat
            ?: throw ApiError(500, "пустой ответ сервера")
    }

    suspend fun chatAddMember(chatId: Long, address: String): OkResp {
        val body = buildJsonObject { put("address", address) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/chats/$chatId/members", body))
    }

    suspend fun chatRemoveMember(chatId: Long, username: String): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/chats/$chatId/members/${enc(username)}"))

    suspend fun chatSetOwner(chatId: Long, username: String): OkResp {
        val body = buildJsonObject { put("username", username) }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/chats/$chatId/owner", body))
    }

    suspend fun chatLeave(chatId: Long): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/chats/$chatId/members/me"))

    suspend fun chatDelete(chatId: Long): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/chats/$chatId"))

    suspend fun chatClear(chatId: Long): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/chats/$chatId/clear"))

    // ------------------------------------------------------------ сообщения

    suspend fun messages(chatId: Long, afterId: Long = 0, beforeId: Long = 0, limit: Int = 200): MessagesResp {
        var path = "/api/chats/$chatId/messages?limit=$limit"
        if (afterId > 0) path += "&after_id=$afterId"
        if (beforeId > 0) path += "&before_id=$beforeId"
        return json.decodeFromJsonElement(MessagesResp.serializer(), request("GET", path))
    }

    suspend fun messageSend(
        chatId: Long,
        type: String,
        body: String,
        clientId: String,
        fileId: String? = null,
    ): MsgResp {
        val obj = buildJsonObject {
            put("type", type)
            put("body", body)
            put("client_id", clientId)
            if (fileId != null) put("file_id", fileId)
        }
        return json.decodeFromJsonElement(MsgResp.serializer(), request("POST", "/api/chats/$chatId/messages", obj))
    }

    suspend fun messageReact(chatId: Long, mid: Long, emoji: String): ReactResp {
        val body = buildJsonObject { put("emoji", emoji) }
        return json.decodeFromJsonElement(
            ReactResp.serializer(), request("POST", "/api/chats/$chatId/messages/$mid/react", body)
        )
    }

    suspend fun messageDelete(chatId: Long, mid: Long): OkResp =
        json.decodeFromJsonElement(OkResp.serializer(), request("DELETE", "/api/chats/$chatId/messages/$mid"))

    suspend fun messageEdit(chatId: Long, mid: Long, newEnvelope: String): OkResp {
        val body = buildJsonObject { put("body", newEnvelope) }
        return json.decodeFromJsonElement(
            OkResp.serializer(), request("POST", "/api/chats/$chatId/messages/$mid/edit", body)
        )
    }

    suspend fun read(chatId: Long, lastReadId: Long, lastReadClientId: String? = null): OkResp {
        val body = buildJsonObject {
            put("last_read_id", lastReadId)
            if (lastReadClientId != null) put("last_read_client_id", lastReadClientId)
        }
        return json.decodeFromJsonElement(OkResp.serializer(), request("POST", "/api/chats/$chatId/read", body))
    }

    // ---------------------------------------------------------------- файлы

    /**
     * Потоковая загрузка УЖЕ ЗАШИФРОВАННОГО потока (TME1) в /api/files.
     * keyB64 — ключ AES, source — открытый файл, onProgress — байты шифротекста.
     * Возвращает file_id.
     */
    suspend fun uploadEncryptedFile(
        source: InputStream,
        plainSize: Long,
        keyB64: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String =
        withContext(Dispatchers.IO) {
            val encSize = Tme1.encryptedSize(plainSize)
            val requestBody: RequestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = encSize
                override fun writeTo(sink: BufferedSink) {
                    var sent = 0L
                    var markSent = 0L
                    var markMs = 0L
                    val counting = object : OutputStream() {
                        override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            sink.write(b, off, len)
                            sent += len
                            val now = System.currentTimeMillis()
                            // троттлинг колбэка: раз в ≥128 КиБ или ≥150 мс
                            if (sent - markSent >= 128 * 1024 || now - markMs >= 150) {
                                onProgress(sent, encSize)
                                markSent = sent
                                markMs = now
                            }
                        }
                        override fun flush() { sink.flush() }
                        override fun close() { sink.close() }
                    }
                    Tme1.encryptStream(source, keyB64, counting)
                    onProgress(encSize, encSize)
                }
            }
            val part = MultipartBody.Part.createFormData("file", "e2e.tme1", requestBody)
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addPart(part).build()
            val rb = Request.Builder().url(base() + "/api/files").post(multipart)
            tokenProvider()?.let { rb.header("Authorization", "Bearer $it") }
            http.newCall(rb.build()).execute().use { resp ->
                val obj = handle(resp)
                obj["file_id"]?.toString()?.trim('"') ?: throw ApiError(500, "нет file_id")
            }
        }

    /**
     * Потоковое скачивание файла с расшифрованием в out; onProgress — открытые байты.
     */
    suspend fun downloadDecryptedFile(
        fileId: String,
        keyB64: String,
        plainSize: Long,
        out: OutputStream,
        tempDir: java.io.File? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Unit = withContext(Dispatchers.IO) {
        val blobSize = Tme1.encryptedSize(plainSize)
        // v12-o: большие файлы качаем параллельными Range-запросами — одна
        // TCP-сессия через userspace-движок туннеля ограничена окном/RTT
        // (типично 200-300 КиБ/с); v13: 8-12 потоков, число выбирается
        // случайно для каждой передачи (анти-DPI), агрегируют throughput.
        if (tempDir != null && blobSize >= PARALLEL_MIN_BYTES) {
            val ok = runCatching { downloadParallel(fileId, keyB64, plainSize, blobSize, out, onProgress, tempDir) }
                .getOrElse { ex ->
                    if (ex is LegacyNeeded) null else throw ex
                }
            if (ok != null) return@withContext
            // сервер без Range (200 вместо 206) → обычное стриминговое скачивание
        }
        val rb = Request.Builder().url(base() + "/api/files/${enc(fileId)}").get()
        tokenProvider()?.let { rb.header("Authorization", "Bearer $it") }
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, "файл недоступен (${resp.code})")
            val stream = resp.body?.byteStream() ?: throw ApiError(500, "пустой ответ")
            var got = 0L
            var markGot = 0L
            var markMs = 0L
            val counting = object : InputStream() {
                override fun read(): Int {
                    val v = stream.read()
                    if (v >= 0) count(1)
                    return v
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = stream.read(b, off, len)
                    if (n > 0) count(n)
                    return n
                }
                private fun count(n: Int) {
                    got += n
                    val now = System.currentTimeMillis()
                    if (got - markGot >= 128 * 1024 || now - markMs >= 150) {
                        onProgress(got, plainSize)
                        markGot = got
                        markMs = now
                    }
                }
                override fun close() { stream.close() }
            }
            Tme1.decryptStream(counting, keyB64, plainSize, out)
            if (plainSize > 0) onProgress(plainSize, plainSize)
        }
    }

    // ------------------------------------------------------ v12-o: параллельный перенос файлов

    /** Минимальный размер (байт), с которого включается многопоточная передача. */
    private val PARALLEL_MIN_BYTES = 4L * 1024 * 1024
    /** Размер одной части при параллельной отправке / одного сегмента при скачивании. */
    private val PART_SIZE = 4L * 1024 * 1024
    /** Стартовое число параллельных потоков (анти-DPI: случайное на передачу). */
    private val LANES_MIN = 8
    private val LANES_MAX = 12
    /** v13-d: потолок разгона числа потоков (анти-DPI: случайный на передачу). */
    private val LANES_CAP_MIN = 24
    private val LANES_CAP_MAX = 32
    /** v13-d: разгон — шаг добавления потоков и интервал измерения прироста. */
    private val RAMP_STEP = 4
    private val RAMP_MS = 2000L
    /** v13-d: прирост скорости за шаг меньше 5 % — канал насыщен, разгон стоп. */
    private val RAMP_GAIN_MIN = 0.05

    /**
     * v13: стартовое число параллельных потоков СЛУЧАЙНО 8..12 на каждую
     * передачу — фиксированное число соединений образует характерный
     * сигнатурный паттерн для DPI.
     * v13-d: поверх старта потоки ДОБАВЛЯЮТСЯ адаптивно (по RAMP_STEP каждые
     * RAMP_MS) до случайного потолка [randomCapLanes], пока агрегатная
     * скорость растёт — один поток через userspace-движок туннеля ограничен
     * окном/RTT (~200-350 КиБ/с), потоки агрегируют пропускную способность.
     */
    private fun randomStartLanes(): Int = kotlin.random.Random.nextInt(LANES_MIN, LANES_MAX + 1)

    /** v13-d: случайный потолок числа параллельных потоков (анти-DPI). */
    private fun randomCapLanes(): Int = kotlin.random.Random.nextInt(LANES_CAP_MIN, LANES_CAP_MAX + 1)

    /** Сервер не поддерживает новый API — клиенту нужно уйти на старый путь. */
    private class LegacyNeeded : Exception()

    /**
     * Параллельная отправка готового TME1-блоба: init → части по 4 МиБ в
     * 8-12 потоков (число случайное, анти-DPI) → commit. Вернёт file_id
     * или null, если сервер старый (нет POST /api/files/init) — тогда
     * вызывающий уходит на multipart.
     *
     * v13: прогресс [onProgress] считает ТОЛЬКО байты, подтверждённые
     * сервером (ответ на PUT части). Раньше считались байты, ушедшие
     * в сокет: при быстром локальном канале счётчик мгновенно взлетал
     * к объёму буферов и замирал, пока туннель не разгружал их.
     *
     * v13-d: части разбираются воркерами из ОБЩЕЙ очереди (а не закреплены
     * за потоками — медленный поток не держит свой кусок), число потоков
     * разгоняется адаптивно: старт 8..12, каждые ~2 с +4, до случайного
     * потолка 24..32, пока скорость «в сокет» растёт ≥ +5 % за шаг.
     */
    suspend fun uploadFileParallel(
        blob: java.io.File,
        name: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String? = withContext(Dispatchers.IO) {
        val size = blob.length()
        val uid = runCatching {
            val resp = request("POST", "/api/files/init", buildJsonObject {
                put("name", name)
                put("size", size)
            })
            resp["upload_id"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
        }.getOrElse { ex ->
            if (ex is ApiError && (ex.code == 404 || ex.code == 405 || ex.code == 501)) null else throw ex
        } ?: return@withContext null

        val partCount = ((size + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)
        // v13-c: прогресс = max(подтверждено, min(в_сокет, подтверждено + PART_SIZE)).
        // Полоса появляется и ползёт СРАЗУ (по факту трафика), но не убегает
        // дальше одной части от реального подтверждения — «нарисованные»
        // буферами байты больше не показываются (регресс v13 не возвращается).
        val done = AtomicLong(0)
        val sockBytes = AtomicLong(0)
        var markShown = 0L
        var markMs = 0L
        fun tick() {
            val conf = done.get()
            val shown = maxOf(conf, minOf(sockBytes.get(), conf + PART_SIZE))
            val now = System.currentTimeMillis()
            if (shown - markShown >= 128 * 1024 || now - markMs >= 150) {
                onProgress(shown, size)
                markShown = shown
                markMs = now
            }
        }

        val uploadPart: suspend (Int) -> Unit = { seq ->
            val from = seq.toLong() * PART_SIZE
            val len = minOf(PART_SIZE, size - from)
            val body: RequestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = len
                override fun writeTo(sink: BufferedSink) {
                    java.io.RandomAccessFile(blob, "r").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(256 * 1024)
                        var remaining = len
                        while (remaining > 0) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("блоб оборван")
                            sink.write(buf, 0, n)
                            // v13-c: считаем ушедшие в сокет байты — прогресс
                            // двигается с первых килобайт, а не после первой
                            // подтверждённой части 4 МиБ (~15-20 с на туннеле)
                            sockBytes.addAndGet(n.toLong())
                            tick()
                            remaining -= n
                        }
                    }
                }
            }
            val rb = Request.Builder()
                .url("${base()}/api/files/$uid/part?seq=$seq")
                .put(body)
            tokenProvider()?.let { rb.header("Authorization", "Bearer $it") }
            http.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 404 || resp.code == 405 || resp.code == 501) throw LegacyNeeded()
                    throw ApiError(resp.code, "часть $seq не принята (${resp.code})")
                }
            }
            // v13: часть считается загруженной только после подтверждения
            // сервера (200); v13-c: показанное значение монотонно не убывает
            // (max с «ушедшими в сокет», ограниченными подтверждением + часть)
            done.addAndGet(len)
            tick()
            onProgress(maxOf(done.get(), minOf(sockBytes.get(), done.get() + PART_SIZE)), size)
        }

        // v13-d: общий пул задач — части разбираются воркерами из очереди
        // (AtomicInteger), а не закрепляются за потоками по кругу.
        val nextSeq = AtomicInteger(0)
        val takeSeq: () -> Int = {
            val s = nextSeq.getAndIncrement()
            if (s < partCount) s else -1
        }
        val laneWorker: suspend () -> Unit = {
            while (true) {
                val seq = takeSeq()
                if (seq < 0) break
                uploadPart(seq)
            }
        }
        val jobs = CopyOnWriteArrayList<Job>()
        try {
            coroutineScope {
                val start = minOf(randomStartLanes(), partCount).coerceAtLeast(1)
                val cap = maxOf(start, randomCapLanes())
                repeat(start) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                // v13-d: адаптивный разгон — пока есть работа и есть запас до
                // потолка, каждые RAMP_MS добавляем до RAMP_STEP потоков, если
                // скорость «в сокет» продолжает расти (≥ +5 % за шаг). Не
                // выросла — канал/движок насыщены, лишние соединения не плодим.
                val ramp = if (partCount > start) launch(Dispatchers.IO) {
                    var lastBytes = sockBytes.get()
                    var lastT = System.currentTimeMillis()
                    var lastSpeed = 0.0
                    while (true) {
                        delay(RAMP_MS)
                        if (jobs.size >= cap) continue
                        val b = sockBytes.get()
                        val now = System.currentTimeMillis()
                        val dt = now - lastT
                        if (dt <= 0) continue
                        val speed = (b - lastBytes).toDouble() / dt
                        if (lastSpeed > 0 && speed > 0 && speed < lastSpeed * (1 + RAMP_GAIN_MIN)) break
                        lastBytes = b
                        lastT = now
                        lastSpeed = speed
                        val add = minOf(RAMP_STEP, cap - jobs.size)
                        repeat(add) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                    }
                } else null
                try {
                    jobs.joinAll()
                } finally {
                    ramp?.cancel()
                }
            }
            if (done.get() != size) throw ApiError(500, "не все части загружены")
            val resp = request("POST", "/api/files/$uid/commit", buildJsonObject {})
            resp["file_id"]?.toString()?.trim('"')
                ?: throw ApiError(500, "commit: нет file_id")
        } catch (ex: Exception) {
            if (ex is LegacyNeeded) {
                runCatching { request("POST", "/api/files/$uid/abort", buildJsonObject {}) }
                return@withContext null
            }
            runCatching { request("POST", "/api/files/$uid/abort", buildJsonObject {}) }
            throw ex
        }
    }

    /**
     * Параллельное скачивание: проба Range (0-0) → TME1-блоб качается
     * сегментами по 4 МиБ из общей очереди; старт 8..12 потоков (случайный,
     * анти-DPI), v13-d: каждые ~2 с добавляется до +4 потоков (потолок
     * случайный 24..32), пока агрегатная скорость растёт. Сегменты пишутся
     * во временный файл на свои смещения → расшифрование в out.
     * Возвращает true — скачано параллельно; null — сервер без Range (нужен
     * старый путь); исключения — реальные ошибки (кроме пробы Range).
     */
    private suspend fun downloadParallel(
        fileId: String,
        keyB64: String,
        plainSize: Long,
        blobSize: Long,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit,
        tempDir: java.io.File,
    ): Boolean? = withContext(Dispatchers.IO) {
        // проба поддержки Range: просим 1 байт
        val probe = Request.Builder().url(base() + "/api/files/${enc(fileId)}")
            .header("Range", "bytes=0-0").get()
        tokenProvider()?.let { probe.header("Authorization", "Bearer $it") }
        val rangeOk = http.newCall(probe.build()).execute().use { resp ->
            if (resp.code == 404) throw ApiError(404, "файл не найден")
            resp.code == 206
        }
        if (!rangeOk) return@withContext null

        val tmp = java.io.File.createTempFile("tmdl", ".tme1", tempDir)
        val done = AtomicLong(0)
        var markDone = 0L
        var markMs = 0L
        fun tick() {
            val now = System.currentTimeMillis()
            val cur = done.get()
            if (cur - markDone >= 256 * 1024 || now - markMs >= 150) {
                onProgress(cur, plainSize)
                markDone = cur
                markMs = now
            }
        }
        try {
            java.io.RandomAccessFile(tmp, "rw").use { raf -> raf.setLength(blobSize) }
            // v13-d: общая очередь сегментов по 4 МиБ (вместо «равных отрезков
            // на поток») — медленный поток не держит большой кусок, работу
            // подхватывают добавленные разгоном потоки.
            val segCount = ((blobSize + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)
            val nextSeg = AtomicInteger(0)
            val takeSeg: () -> Int = {
                val s = nextSeg.getAndIncrement()
                if (s < segCount) s else -1
            }
            val laneWorker: suspend () -> Unit = {
                while (true) {
                    val seq = takeSeg()
                    if (seq < 0) break
                    val from = seq.toLong() * PART_SIZE
                    val to = minOf(blobSize, from + PART_SIZE) - 1
                    val rb = Request.Builder()
                        .url(base() + "/api/files/${enc(fileId)}")
                        .header("Range", "bytes=$from-$to").get()
                    tokenProvider()?.let { rb.header("Authorization", "Bearer $it") }
                    http.newCall(rb.build()).execute().use { resp ->
                        if (resp.code != 206) {
                            throw ApiError(resp.code, "Range не поддержан (${resp.code})")
                        }
                        val stream = resp.body?.byteStream()
                            ?: throw ApiError(500, "пустой ответ")
                        val buf = ByteArray(256 * 1024)
                        var pos = from
                        java.io.RandomAccessFile(tmp, "rw").use { raf ->
                            while (pos <= to) {
                                val want = minOf(buf.size.toLong(), to - pos + 1).toInt()
                                val n = stream.read(buf, 0, want)
                                if (n < 0) break
                                raf.seek(pos)
                                raf.write(buf, 0, n)
                                pos += n
                                done.addAndGet(n.toLong())
                                tick()
                            }
                        }
                        if (pos != to + 1) throw ApiError(500, "файл оборван (Range)")
                    }
                }
            }
            val jobs = CopyOnWriteArrayList<Job>()
            coroutineScope {
                val start = minOf(randomStartLanes(), segCount).coerceAtLeast(1)
                val cap = maxOf(start, randomCapLanes())
                repeat(start) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                // v13-d: адаптивный разгон (см. uploadFileParallel) — меряем
                // принятые байты; насыщение канала останавливает разгон.
                val ramp = if (segCount > start) launch(Dispatchers.IO) {
                    var lastBytes = done.get()
                    var lastT = System.currentTimeMillis()
                    var lastSpeed = 0.0
                    while (true) {
                        delay(RAMP_MS)
                        if (jobs.size >= cap) continue
                        val b = done.get()
                        val now = System.currentTimeMillis()
                        val dt = now - lastT
                        if (dt <= 0) continue
                        val speed = (b - lastBytes).toDouble() / dt
                        if (lastSpeed > 0 && speed > 0 && speed < lastSpeed * (1 + RAMP_GAIN_MIN)) break
                        lastBytes = b
                        lastT = now
                        lastSpeed = speed
                        val add = minOf(RAMP_STEP, cap - jobs.size)
                        repeat(add) { jobs += launch(Dispatchers.IO) { laneWorker() } }
                    }
                } else null
                try {
                    jobs.joinAll()
                } finally {
                    ramp?.cancel()
                }
            }
            if (done.get() != blobSize) throw ApiError(500, "размер не сошёлся")
            onProgress(plainSize, plainSize)
            tmp.inputStream().buffered(Tme1.CHUNK).use { src ->
                Tme1.decryptStream(src, keyB64, plainSize, out)
            }
            true
        } finally {
            tmp.delete()
        }
    }
}
