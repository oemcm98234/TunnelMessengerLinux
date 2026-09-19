package com.tunnelmessenger.desktop.data.local

import com.tunnelmessenger.desktop.data.crypto.SecureStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Каталоги приложения (контракт 2.7).
 *
 * Единый корень данных — {user.home}/.tunnelmessenger (и в Windows, и в
 * Linux: File сам подставит разделитель). Данные БД, движки туннеля,
 * скачанные обновления и конфиг сессии живут здесь.
 */
object AppDirs {

    /** Корень данных: ~/.tunnelmessenger */
    val dataDir: File = File(System.getProperty("user.home"), ".tunnelmessenger")

    /** Движки туннеля (tunnel-core, freeturn-client). */
    val engineDir: File = File(dataDir, "engine")

    /** Скачанные обновления приложения. */
    val updatesDir: File = File(dataDir, "updates")

    /** Файл локальной БД (кэш истории). */
    val dbFile: File = File(dataDir, "tunnel_messenger.db")

    val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    @Volatile private var ensured = false

    /** Создать каталоги при старте; на POSIX-системах dataDir получает 700. */
    fun ensureDirs() {
        if (ensured) return
        for (d in listOf(dataDir, engineDir, updatesDir)) {
            if (!d.exists()) d.mkdirs()
        }
        if (!isWindows) {
            runCatching {
                Files.setPosixFilePermissions(
                    dataDir.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                )
            }
        }
        ensured = true
    }

    /** Бинарник AWG-движка (наследник librtcore.so). */
    fun engineBinary(): File =
        resolveEngine("tunnel-core", "tunnel-core-linux64", "tunnel-core-win64.exe")

    /**
     * Бинарник FreeTurn-клиента (UDP-релей TURN).
     * СБОРКА 4: официальный клиент samosvalishe/free-turn-proxy ВШИТ в
     * app-resources/common под именами freeturn-client-linux64 /
     * freeturn-client-win64.exe — извлекается автоматически, как tunnel-core.
     * СБОРКА 8: ядро пересобрано из GitHub HEAD (v3.4.0-13.8) — см. ENGINE_STAMP.
     */
    fun freeturnBinary(): File =
        resolveEngine("freeturn-client", "freeturn-client-linux64", "freeturn-client-win64.exe")

    /**
     * СБОРКА 8: штамп версии движков. При любом обновлении binaries в поставке
     * (пересборка ядра и т.п.) штамп повышается — тогда уже извлечённый ранее
     * движок в engineDir ЗАМЕНЯЕТСЯ свежим из поставки. Без этого у всех, кто
     * запускал прошлую сборку, навсегда оставался бы старый бинарник
     * (раньше extraction был «один раз и навсегда»). Свой binary пользователь
     * может подложить только удалив штамп-файл и binary.
     */
    private const val ENGINE_STAMP = "tm-13.8"

    /**
     * v13.1 (сборка 3): движки ВШИТЫ в поставку — ручная укладка в
     * ~/.tunnelmessenger/engine больше не требуется. Порядок поиска:
     * 1) движок уже в engineDir (пользовательский override/ранее извлечённый);
     * 2) ресурсы поставки jpackage (compose.application.resources.dir —
     *    app-resources/common, упаковываются в MSI/EXE/AppImage/DEB);
     * 3) каталог engines/ рядом с jar (портативный запуск java -jar);
     * найденный бинарник извлекается в engineDir и делается исполняемым.
     */
    private fun resolveEngine(baseName: String, linuxName: String, winName: String): File {
        ensureDirs()
        val target = File(engineDir, if (isWindows) "$baseName.exe" else baseName)
        val stampFile = File(engineDir, "$baseName.stamp")
        val bundledName = if (isWindows) winName else linuxName
        val candidates = mutableListOf<File>()
        System.getProperty("compose.application.resources.dir")?.let { d ->
            candidates.add(File(d, bundledName))
            candidates.add(File(File(d, "common"), bundledName))
        }
        runCatching {
            val jarFile = File(javaClass.protectionDomain.codeSource.location.toURI())
            jarFile.parentFile?.let { p ->
                candidates.add(File(File(p, "engines"), bundledName))
                candidates.add(File(File(p.parentFile, "engines"), bundledName))
            }
        }
        val src = candidates.firstOrNull { it.isFile }
        // СБОРКА 8: переизвлекаем, если движка нет ИЛИ штамп версии не совпадает
        // (обновление движка в поставке должно доезжать до машин пользователей).
        if (target.exists() && stampFile.isFile &&
            runCatching { stampFile.readText().trim() == ENGINE_STAMP }.getOrDefault(false)
        ) {
            return target
        }
        if (src == null) return target
        return runCatching {
            val tmp = File(engineDir, "$baseName.part")
            src.copyTo(tmp, overwrite = true)
            if (!isWindows) {
                Files.setPosixFilePermissions(
                    tmp.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                )
            }
            tmp.renameTo(target)
            stampFile.writeText(ENGINE_STAMP)
            target
        }.getOrDefault(target)
    }
}

/**
 * Конфиг сессии — dataDir/config.json (контракт 2.7).
 *
 * Здесь живут и несекретные флаги, и секреты (token, e2e_sk, e2e_pk,
 * конфиг туннеля с PrivateKey, блоб FreeTurn, pending_import) — секреты
 * записываются ТОЛЬКО через SecureStore.sealStr вызывающим кодом.
 * Потокобезопасно: единый lock + файл перезаписывается атомарно.
 */
object AppConfig {

    private val lock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Volatile private var loaded = false
    private var data: MutableMap<String, JsonElement> = LinkedHashMap()

    private fun file(): File = File(AppDirs.dataDir, "config.json")

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.exists()) return
        val obj = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return
        data = obj.toMutableMap()
    }

    private fun saveLocked() {
        val obj = JsonObject(data)
        val f = file()
        val tmp = File(f.parentFile, "config.json.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(JsonObject.serializer(), obj))
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // СБОРКА 7: даже при dir 700 сам файл с токенами/ключами — 600
            // (групповой/чужой доступ исключён; на Windows — ACL профиля).
            if (!AppDirs.isWindows) {
                runCatching {
                    Files.setPosixFilePermissions(
                        f.toPath(),
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                }
            }
        }
    }

    private fun prim(key: String): JsonPrimitive? =
        (data[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    fun getString(key: String): String? = synchronized(lock) {
        loadLocked(); prim(key)?.content
    }

    fun putString(key: String, value: String?): Unit = synchronized(lock) {
        loadLocked()
        if (value == null) data.remove(key) else data[key] = JsonPrimitive(value)
        saveLocked()
    }

    fun getBoolean(key: String, def: Boolean): Boolean = synchronized(lock) {
        loadLocked(); prim(key)?.booleanOrNull ?: def
    }

    fun putBoolean(key: String, value: Boolean): Unit = synchronized(lock) {
        loadLocked(); data[key] = JsonPrimitive(value); saveLocked()
    }

    fun getLong(key: String, def: Long): Long = synchronized(lock) {
        loadLocked(); prim(key)?.longOrNull ?: def
    }

    fun putLong(key: String, value: Long): Unit = synchronized(lock) {
        loadLocked(); data[key] = JsonPrimitive(value); saveLocked()
    }

    fun remove(key: String): Unit = synchronized(lock) {
        loadLocked(); data.remove(key); saveLocked()
    }

    /** Удобная запись секрета: sealStr тут же, в конфиг уходит только «enc1:…». */
    fun putSealed(key: String, plain: String?): Unit =
        putString(key, plain?.let { SecureStore.sealStr(it) })

    /** Чтение секрета (openStr; null/отсутствие → null). */
    fun getSealed(key: String): String? = SecureStore.openStr(getString(key))
}
