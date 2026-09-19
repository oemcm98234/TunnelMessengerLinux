package com.tunnelmessenger.desktop.data.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографическое хранилище чувствительных данных (десктоп-порт).
 *
 * МАСТЕР-КЛЮЧ — случайные 32 байта в файле {dataDir}/master.key
 * (на Linux права r--------; замена Android Keystore, которого на JVM нет).
 *
 *  • sealStr/openStr — небольшие секреты (токен сессии, e2e-приватный ключ,
 *    конфиг туннеля с PrivateKey, блоб FreeTurn). Формат:
 *    "enc1:" + base64(iv ‖ ciphertext) — СОВПАДАЕТ с Android, поэтому файл
 *    экспорта/миграции читается обоими клиентами. openStr возвращает вход
 *    как есть, если он без префикса — так старые (незашифрованные) значения
 *    читаются и прозрачно мигрируются при первой же перезаписи.
 *
 *  • DEK (data encryption key) — случайный 128-битный ключ для БЫСТРОГО
 *    шифрования полей локальной БД (тексты сообщений, превью, метаданные
 *    файлов). Сам DEK лежит в dataDir/security.json ТОЛЬКО в запечатанном
 *    мастер-ключом виде. Cipher на программном ключе — микросекунды.
 */
object SecureStore {

    private const val PREFIX = "enc1:"
    private const val TAG = "SecureStore"

    private val rng = SecureRandom()

    private var master: SecretKey? = null
    private var dek: SecretKey? = null

    @Volatile private var initialized = false

    /** Файл обёртки DEK — отдельный «prefs» (аналог SharedPreferences "security"). */
    private lateinit var securityFile: File

    private val secLock = Any()

    fun init(dataDir: File) {
        if (initialized) return
        securityFile = File(dataDir, "security.json")
        runCatching { master = loadOrCreateMasterKey(dataDir) }
            .onFailure { println("[$TAG] мастер-ключ недоступен: ${it.message}") }
        runCatching { dek = loadOrCreateDek() }
            .onFailure { println("[$TAG] DEK недоступен: ${it.message}") }
        initialized = true
    }

    // ------------------------------------------------------------ master key

    private fun loadOrCreateMasterKey(dataDir: File): SecretKey {
        val f = File(dataDir, "master.key")
        val raw: ByteArray = if (f.isFile && f.length() == 32L) {
            f.readBytes()
        } else {
            val b = ByteArray(32).also(rng::nextBytes)
            f.writeBytes(b)
            // на Linux — права r-------- (чтение только владельцем)
            if (!System.getProperty("os.name", "").lowercase().contains("win")) {
                runCatching { Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ)) }
            }
            b
        }
        require(raw.size == 32) { "master.key повреждён" }
        return SecretKeySpec(raw, "AES")
    }

    // ------------------------------------------------------- seal/open (str)

    /** Зашифровать строку мастер-ключом («enc1:base64(iv+ct)»).
     *  При недоступном мастер-ключе — логируем и сохраняем plaintext
     *  (как в Android v12 audit, только вместо logcat — stdout). */
    fun sealStr(plain: String): String {
        if (plain.isEmpty()) return plain
        val key = master ?: run {
            println("[$TAG] мастер-ключ недоступен — секрет сохраняется БЕЗ шифрования")
            return plain
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + ct)
        }.getOrElse {
            println("[$TAG] sealStr не удался (${it.message}) — значение сохранено plaintext")
            plain
        }
    }

    /**
     * Расшифровать. Значение без префикса "enc1:" — legacy-plaintext из старой
     * версии: возвращаем как есть (вызывающий код перезапишет его зашифрованным).
     * Битые/чужие данные → null (значение считается утраченным).
     */
    fun openStr(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        if (!sealed.startsWith(PREFIX)) return sealed
        val key = master ?: return null
        return runCatching {
            val blob = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Зашифровано ли значение (для миграции legacy-plaintext). */
    fun isSealed(s: String?): Boolean = s != null && s.startsWith(PREFIX)

    // ----------------------------------------------------- DEK для полей БД

    private val secJson = Json { ignoreUnknownKeys = true }
    private const val DEK_KEY = "dek_wrapped"

    private fun readSecurity(): MutableMap<String, String> {
        if (!securityFile.isFile) return LinkedHashMap()
        val obj = runCatching { secJson.parseToJsonElement(securityFile.readText()).jsonObject }.getOrNull()
            ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj) {
            (v as? JsonPrimitive)?.let { out[k] = it.content }
        }
        return out
    }

    private fun writeSecurity(map: Map<String, String>) {
        val obj: JsonObject = buildJsonObject {
            for ((k, v) in map) put(k, v)
        }
        securityFile.writeText(secJson.encodeToString(JsonObject.serializer(), obj))
        // СБОРКА 7: файл с DEK (обёрнутым мастер-ключом) — только владельцу.
        if (!System.getProperty("os.name", "").lowercase().contains("win")) {
            runCatching {
                Files.setPosixFilePermissions(
                    securityFile.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }
    }

    private fun loadOrCreateDek(): SecretKey {
        val wrapped = synchronized(secLock) { readSecurity()[DEK_KEY] }
        if (wrapped != null) {
            val raw = runCatching {
                openStr(wrapped)?.let { Base64.getDecoder().decode(it) }
            }.getOrNull()
            if (raw != null && raw.size == 16) {
                return SecretKeySpec(raw, "AES")
            }
            // DEK не расшифровался (повреждён security.json/мастер-ключ) — новый
        }
        val raw = ByteArray(16).also(rng::nextBytes)
        synchronized(secLock) {
            val map = readSecurity()
            map[DEK_KEY] = sealStr(Base64.getEncoder().encodeToString(raw))
            writeSecurity(map)
        }
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Быстрое шифрование поля БД: "enc1:" + base64(iv ‖ ct) на DEK.
     * Пустые значения пропускает.
     */
    fun sealField(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val key = dek ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + ct)
        }.getOrDefault(plain)
    }

    /** Расшифровка поля БД; без префикса — legacy-plaintext, возвращаем как есть. */
    fun openField(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return sealed
        if (!sealed.startsWith(PREFIX)) return sealed
        val key = dek ?: return sealed
        return runCatching {
            val blob = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
