package com.tunnelmessenger.desktop.data.crypto

import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.utils.LibraryLoader
import java.security.SecureRandom
import java.util.Base64

/**
 * E2E-шифрование Tunnel Messenger (docs/E2E.md), совместимо с TUI (PyNaCl)
 * и веб-клиентом (TweetNaCl/WebCrypto):
 *
 *  - ключи X25519 (32 байта), приватный ключ никогда не покидает устройство;
 *  - текст: "E2E1:" + base64(JSON{"n","r","s"}) — NaCl box (X25519+XSalsa20-Poly1305);
 *  - группы: "E2E1G:" + base64(JSON{"b": {user: {"n","r"}}}) — бокс на каждого участника;
 *  - файлы: формат TME1 (AES-256-GCM чанками) — см. Tme1.kt.
 *
 * Нативный libsodium через JNA-маппинг lazysodium-java: класс Sodium содержит
 * прямые snake_case-привязки к C-функциям (каждая возвращает 0 при успехе).
 * На этапе инициализации пробуем системную libsodium, затем — библиотеку из
 * ресурсов com/goterl/lazysodium/lib; если нет ни той, ни другой — понятное
 * исключение (E2E невозможен).
 */
object E2eCrypto {

    private val random = SecureRandom()

    // SodiumJava — единственный экземпляр на процесс (sodium_init вызывается
    // в конструкторе); все методы — прямые snake_case-привязки к libsodium
    // (возвращают 0 при успехе), см. интерфейс com.goterl.lazysodium.Sodium.
    private val sodium: SodiumJava by lazy { loadSodium() }

    private fun loadSodium(): SodiumJava {
        // 1) системная libsodium (ldconfig/пути по умолчанию)
        try {
            return SodiumJava(LibraryLoader.Mode.PREFER_SYSTEM)
        } catch (_: Throwable) {
            // пробуем дальше
        }
        // 2) bundled-библиотека из ресурсов jar (linux64/libsodium.so, windows64/libsodium.dll, …)
        try {
            return SodiumJava(LibraryLoader.Mode.PREFER_BUNDLED)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Не найдена libsodium: ни системная, ни bundled-библиотека lazysodium-java. " +
                    "E2E-шифрование недоступно — установите libsodium (libsodium23 / libsodium-dev).",
                e,
            )
        }
    }

    // ------------------------------------------------------------- base64

    /** base64 без переносов (аналог android Base64.NO_WRAP). */
    fun b64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    /** Лояльное декодирование (пробельные символы игнорируются, как у Android). */
    fun unb64(s: String): ByteArray? = try {
        Base64.getMimeDecoder().decode(s)
    } catch (_: Exception) {
        null
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun randomB64(n: Int): String = b64(randomBytes(n))

    // ------------------------------------------------------------- ключи

    /** Новая пара X25519: (sk_b64, pk_b64) — crypto_box_keypair. */
    fun generateKeypair(): Pair<String, String> {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        require(sodium.crypto_box_keypair(pk, sk) == 0) { "crypto_box_keypair failed" }
        return b64(sk) to b64(pk)
    }

    /** Публичный ключ из приватного — crypto_scalarmult_base. */
    fun publicFromPrivate(skB64: String): String? {
        val sk = unb64(skB64) ?: return null
        if (sk.size != 32) return null
        val pk = ByteArray(32)
        return if (sodium.crypto_scalarmult_base(pk, sk) == 0) b64(pk) else null
    }

    // -------------------------------------------------------------- E2E1

    /**
     * Зашифровать текст для получателя + копию себе.
     * Возвращает "E2E1:" + b64(JSON{"n": b64(nonce24), "r": b64(box_r), "s": b64(box_s)}).
     */
    fun encryptText(plaintext: String, recipientPkB64: String, senderSkB64: String): String? {
        val pkR = unb64(recipientPkB64) ?: return null
        val skS = unb64(senderSkB64) ?: return null
        val pkS = publicFromPrivate(senderSkB64)?.let { unb64(it) } ?: return null
        val pt = plaintext.toByteArray(Charsets.UTF_8)
        val nonce = randomBytes(24)
        val boxR = ByteArray(pt.size + 16)
        val boxS = ByteArray(pt.size + 16)
        if (sodium.crypto_box_easy(boxR, pt, pt.size.toLong(), nonce, pkR, skS) != 0) return null
        if (sodium.crypto_box_easy(boxS, pt, pt.size.toLong(), nonce, pkS, skS) != 0) return null
        val inner = """{"n":"${b64(nonce)}","r":"${b64(boxR)}","s":"${b64(boxS)}"}"""
        return PREFIX_TEXT + b64(inner.toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Расшифровать E2E1-тело. fromSelf=true — открыть копию "s" (своя история).
     * null — не удалось (нет ключа отправителя / изменился ключ).
     */
    fun decryptText(body: String?, senderPkB64: String?, mySkB64: String, fromSelf: Boolean = false): String? {
        if (body == null || !body.startsWith(PREFIX_TEXT)) return null
        return try {
            val raw = unb64(body.substring(PREFIX_TEXT.length)) ?: return null
            val inner = String(raw, Charsets.ISO_8859_1)
            val nonce = unb64(extractJson(inner, "n") ?: return null) ?: return null
            val which = if (fromSelf) "s" else "r"
            val ct = unb64(extractJson(inner, which) ?: return null) ?: return null
            val pk = if (fromSelf) {
                publicFromPrivate(mySkB64)?.let { unb64(it) } ?: return null
            } else {
                senderPkB64?.let { unb64(it) } ?: return null
            }
            val sk = unb64(mySkB64) ?: return null
            val pt = ByteArray(maxOf(0, ct.size - 16))
            if (sodium.crypto_box_open_easy(pt, ct, ct.size.toLong(), nonce, pk, sk) != 0) return null
            String(pt, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- E2E1G

    /**
     * Групповой конверт: бокс на каждого участника (включая себя).
     * memberPks: username -> pk_b64 (участники без ключа пропускаются).
     */
    fun encryptGroupText(plaintext: String, memberPks: Map<String, String>, senderSkB64: String): String? {
        val sk = unb64(senderSkB64) ?: return null
        val pt = plaintext.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("""{"b":{""")
        var first = true
        for ((user, pkB64) in memberPks) {
            if (pkB64.isBlank()) continue
            val pkU = unb64(pkB64) ?: continue
            val nonce = randomBytes(24)
            val ct = ByteArray(pt.size + 16)
            // для самого отправителя в map передаётся его собственный pk —
            // так автор читает свою историю на сервере (копия "s" для групп)
            if (sodium.crypto_box_easy(ct, pt, pt.size.toLong(), nonce, pkU, sk) != 0) continue
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(jsonEscape(user.lowercase())).append("\":")
            sb.append("""{"n":"${b64(nonce)}","r":"${b64(ct)}"}""")
        }
        if (first) return null // ни одного бокса
        sb.append("}}")
        return PREFIX_GROUP + b64(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /** Расшифровать свой бокс из группового конверта.
     *
     * v12: федеративные группы — в конверте от участника другого сервера
     * бокс адресован по ПОЛНОМУ адресу ('bob@домен'), от локальных — по
     * username. Пробуем оба варианта (myFedAddress = 'bob@свой-домен'). */
    fun decryptGroupText(
        body: String?,
        senderPkB64: String?,
        myUsername: String,
        mySkB64: String,
        myFedAddress: String? = null,
    ): String? {
        if (body == null || !body.startsWith(PREFIX_GROUP)) return null
        return try {
            val raw = unb64(body.substring(PREFIX_GROUP.length)) ?: return null
            val inner = String(raw, Charsets.ISO_8859_1)
            val my = myUsername.lowercase()
            // найдём сегмент "<my>":{"n":"...","r":"..."}; федеративный адрес — запасной вариант
            val seg = extractGroupSegment(inner, my)
                ?: (myFedAddress?.takeIf { it.isNotBlank() && !it.equals(my, ignoreCase = true) }
                    ?.let { extractGroupSegment(inner, it.lowercase()) })
                ?: return null
            val nonce = unb64(extractJson(seg, "n") ?: return null) ?: return null
            val ct = unb64(extractJson(seg, "r") ?: return null) ?: return null
            val pk = senderPkB64?.let { unb64(it) } ?: return null
            val sk = unb64(mySkB64) ?: return null
            val pt = ByteArray(maxOf(0, ct.size - 16))
            if (sodium.crypto_box_open_easy(pt, ct, ct.size.toLong(), nonce, pk, sk) != 0) return null
            String(pt, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------- фреймы звонка

    /**
     * Бокс одного аудиофрейма звонка ТОЛЬКО получателю (без самокопии "s" —
     * она в звонке не нужна и удваивала бы трафик). Формат:
     * "E2E1F:" + base64(JSON{"n": b64(nonce24), "r": b64(box)}).
     */
    fun encryptFrame(plaintext: String, recipientPkB64: String, senderSkB64: String): String? {
        val pkR = unb64(recipientPkB64) ?: return null
        val skS = unb64(senderSkB64) ?: return null
        val pt = plaintext.toByteArray(Charsets.UTF_8)
        val nonce = randomBytes(24)
        val boxR = ByteArray(pt.size + 16)
        if (sodium.crypto_box_easy(boxR, pt, pt.size.toLong(), nonce, pkR, skS) != 0) return null
        val inner = """{"n":"${b64(nonce)}","r":"${b64(boxR)}"}"""
        return PREFIX_FRAME + b64(inner.toByteArray(Charsets.ISO_8859_1))
    }

    /** Расшифровать фрейм звонка; null — битый/чужой фрейм. */
    fun decryptFrame(envelope: String?, senderPkB64: String?, mySkB64: String): String? {
        if (envelope == null || !envelope.startsWith(PREFIX_FRAME)) return null
        return try {
            val raw = unb64(envelope.substring(PREFIX_FRAME.length)) ?: return null
            val inner = String(raw, Charsets.ISO_8859_1)
            val nonce = unb64(extractJson(inner, "n") ?: return null) ?: return null
            val ct = unb64(extractJson(inner, "r") ?: return null) ?: return null
            val pk = senderPkB64?.let { unb64(it) } ?: return null
            val sk = unb64(mySkB64) ?: return null
            val pt = ByteArray(maxOf(0, ct.size - 16))
            if (sodium.crypto_box_open_easy(pt, ct, ct.size.toLong(), nonce, pk, sk) != 0) return null
            String(pt, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------- json-хелперы

    /** Достать значение строкового поля "key":"value" из простого JSON без парсера. */
    private fun extractJson(json: String, key: String): String? {
        val pat = "\"$key\""
        val ki = json.indexOf(pat)
        if (ki < 0) return null
        val colon = json.indexOf(':', ki + pat.length)
        if (colon < 0) return null
        val q1 = json.indexOf('"', colon + 1)
        if (q1 < 0) return null
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\') { i += 2; continue }
            if (ch == '"') break
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** Сегмент "<username>":{...} в групповом конверте (учёт вложенных кавычек). */
    private fun extractGroupSegment(json: String, username: String): String? {
        val key = "\"$username\":"
        val ki = json.indexOf(key)
        if (ki < 0) return null
        val objStart = json.indexOf('{', ki + key.length)
        if (objStart < 0) return null
        var depth = 0
        var inStr = false
        var i = objStart
        while (i < json.length) {
            val ch = json[i]
            if (ch == '"' && (i == 0 || json[i - 1] != '\\')) inStr = !inStr
            if (!inStr) {
                if (ch == '{') depth++
                if (ch == '}') {
                    depth--
                    if (depth == 0) return json.substring(objStart, i + 1)
                }
            }
            i++
        }
        return null
    }

    fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    const val PREFIX_TEXT = "E2E1:"
    const val PREFIX_GROUP = "E2E1G:"
    const val PREFIX_FRAME = "E2E1F:"
}
