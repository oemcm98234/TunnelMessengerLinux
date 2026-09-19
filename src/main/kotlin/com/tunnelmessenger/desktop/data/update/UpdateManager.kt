package com.tunnelmessenger.desktop.data.update

import com.tunnelmessenger.desktop.data.api.Api
import com.tunnelmessenger.desktop.data.api.ApiError
import com.tunnelmessenger.desktop.data.local.AppConfig
import com.tunnelmessenger.desktop.data.local.AppDirs
import com.tunnelmessenger.desktop.data.model.UpdateCheckResp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

/**
 * Менеджер обновлений приложения — десктоп-порт.
 *
 * Запрашивает у сервера /api/updates/check, сравнивает с текущей версией
 * клиента (15), скачивает файл релиза в AppDirs.updatesDir и «устанавливает»:
 * после успешной проверки целостности открывает папку с файлом
 * (java.awt.Desktop) — автоматический запуск скачанного НЕ выполняется.
 *
 * Состояние обновления экспонируется через [state] (StateFlow) для UI.
 *
 * Сервер не требует авторизации для эндпоинтов обновлений — это позволяет
 * клиенту проверить обновление даже до входа в аккаунт (например, при
 * истёкшей сессии). Всё идёт через туннель (HttpRouter → SOCKS5).
 */
class UpdateManager(
    private val apiProvider: () -> Api?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Текущая версия клиента (version_code, как у Android v15). */
    val currentVersionCode: Int get() = 15
    /** Имя версии — единое с APP_VERSION («15», не «15.0»). */
    val currentVersionName: String get() = "15"

    /** Целевая платформа для эндпоинта обновлений. */
    private val platform: String =
        if (System.getProperty("os.name", "").lowercase().contains("win")) "windows" else "linux"

    /** Допустимое имя файла релиза (path-traversal защита, контракт 2.9).
     *
     *  СБОРКА 7: сервер раздаёт релизы по платформенному имени из Updates/
     *  (UPDATE_NAME_RE сервера знает Android|Linux|Windows):
     *    - Windows → TunnelMessengerWindows(vN).exe   (jpackage Exe/что зальёт админ);
     *    - Linux   → TunnelMessengerLinux(vN).appimage.
     *  Клиент принимает любое из этих имён (+ legacy Desktop/zip для совместимости). */
    private val FILENAME_REGEX = Regex(
        "^TunnelMessenger(?:Desktop|Windows|Linux)?\\(v\\d+\\)\\.(exe|msi|AppImage|deb|zip|jar)$",
        RegexOption.IGNORE_CASE,
    )

    /** Текущее состояние процесса проверки/загрузки обновления. */
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Последний известный результат проверки. null — проверка ещё не шла. */
    private val _lastCheck = MutableStateFlow<UpdateCheckResp?>(null)
    val lastCheck: StateFlow<UpdateCheckResp?> = _lastCheck.asStateFlow()

    /**
     * Проверить наличие обновления на сервере.
     * В случае успеха обновляет [lastCheck] и [state] (если update_available).
     * Возвращает результат проверки (или null при ошибке сети).
     *
     * [silent] = true (по умолчанию при авто-проверке при старте) — сетевые
     * ошибки НЕ записываются в lastError. При ручной проверке (кнопка в
     * Настройках) — silent=false, ошибки показываются.
     */
    suspend fun checkForUpdates(silent: Boolean = true): UpdateCheckResp? {
        val api = apiProvider() ?: fallbackApi() ?: run {
            if (!silent) _state.value = _state.value.copy(isChecking = false, lastError = "не задан адрес сервера")
            return null
        }
        return try {
            _state.value = _state.value.copy(isChecking = true, lastError = null)
            val resp = api.updatesCheck(currentVersionCode, platform)
            _lastCheck.value = resp
            if (resp.update_available) {
                _state.value = _state.value.copy(
                    isChecking = false,
                    updateAvailable = true,
                    latestVersion = resp.latest_version.toString(),
                    apkFilename = resp.filename,
                    apkSize = resp.size,
                    downloadUrl = resp.download_url,
                    // v13: чейнжлог релиза уже пришёл в ответе check — без повторного запроса
                    changelog = resp.changelog?.takeIf { it.isNotBlank() },
                    // v13 (аудит): SHA-256 релиза для проверки целостности после скачивания
                    apkSha256 = resp.sha256?.takeIf { it.isNotBlank() },
                )
            } else {
                _state.value = _state.value.copy(
                    isChecking = false,
                    updateAvailable = false,
                    latestVersion = resp.latest_version.toString(),
                )
            }
            resp
        } catch (e: ApiError) {
            // silent: не показываем сетевые ошибки при авто-проверке —
            // туннель может быть ещё не поднят, это не повод пугать пользователя.
            val msg = if (silent) null else "не удалось проверить: ${e.message} (возможно, туннель не поднят)"
            _state.value = _state.value.copy(isChecking = false, lastError = msg)
            null
        } catch (_: Exception) {
            val msg = if (silent) null else "не удалось проверить обновления (возможно, туннель не поднят)"
            _state.value = _state.value.copy(isChecking = false, lastError = msg)
            null
        }
    }

    /**
     * Временный Api для проверки обновлений без активной сессии.
     * Читает base_url из config.json (тот же ключ, что Repository).
     * Если base_url не задан — использует дефолтный сервер (10.10.10.1:80,
     * как на экране логина).
     */
    private fun fallbackApi(): Api? {
        return runCatching {
            val baseUrl = AppConfig.getString("base_url")?.takeIf { it.isNotBlank() }
                ?: "http://10.10.10.1:80" // дефолт, как в LoginScreen
            Api({ baseUrl }, { null })
        }.getOrNull()
    }

    /**
     * Скачать файл релиза в AppDirs.updatesDir/<имя с сервера> и открыть
     * папку с ним (контракт 2.9: НЕ запускать автоматически). Прогресс —
     * в [state]. Если файл уже скачан и прошёл проверку целостности —
     * скачивание пропускается.
     *
     * Требует, чтобы до этого была выполнена успешная checkForUpdates()
     * (известны имя/размер/SHA-256); иначе — Result.failure.
     */
    suspend fun downloadAndInstall(): Result<Unit> {
        val st = _state.value
        val filename = st.apkFilename
        if (filename.isNullOrBlank() || !st.updateAvailable) {
            return Result.failure(IllegalStateException("нет доступного обновления — сначала нажмите «Проверить»"))
        }
        val safeName = File(filename).name
        val nameOk = FILENAME_REGEX.matches(safeName)
        if (safeName.isBlank() || safeName.contains("..") || !nameOk) {
            return Result.failure(IllegalStateException("некорректное имя файла обновления"))
        }
        val expectedSize = st.apkSize.takeIf { it > 0 } ?: 0L
        return try {
            AppDirs.updatesDir.mkdirs()
            val outFile = File(AppDirs.updatesDir, safeName)
            // уже скачан и цел? — не качаем заново, сразу открываем папку
            if (outFile.exists() && outFile.length() > 0) {
                val cacheErr = integrityError(outFile, st.apkSha256, expectedSize)
                if (cacheErr == null) {
                    _state.value = _state.value.copy(
                        isDownloading = false, apkFile = outFile,
                        downloadedBytes = outFile.length(), totalBytes = outFile.length(),
                    )
                    launchExplorer(outFile)
                    return Result.success(Unit)
                }
                outFile.delete()
                _state.value = _state.value.copy(isDownloading = false, apkFile = null, lastError = cacheErr)
                return Result.failure(IllegalStateException(cacheErr))
            }
            val api = apiProvider() ?: fallbackApi()
                ?: return Result.failure(IllegalStateException("не задан адрес сервера — войдите в аккаунт или укажите сервер на экране входа"))
            _state.value = _state.value.copy(isDownloading = true, downloadedBytes = 0, totalBytes = expectedSize, lastError = null)
            if (outFile.exists()) outFile.delete()
            // сначала МНОГОПОТОЧНАЯ докачка — сегменты 4 МиБ
            // параллельными Range-запросами (как файлы сообщений): одна
            // TCP-сессия через движок туннеля даёт ~200-300 КиБ/с, 8-32
            // потока агрегируют пропускную способность. null — сервер без
            // Range/мелкий файл: откат на обычное последовательное скачивание.
            val tmpDir = File(AppDirs.dataDir, "tmp").apply { mkdirs() }
            val parallelOk = runCatching {
                api.downloadUpdateParallel(safeName, outFile, tmpDir) { got, total ->
                    _state.value = _state.value.copy(
                        downloadedBytes = got,
                        totalBytes = if (total > 0) total else expectedSize,
                    )
                }
            }.getOrNull()
            if (parallelOk == null) {
                outFile.outputStream().use { out ->
                    api.downloadUpdate(safeName, out) { got, total ->
                        _state.value = _state.value.copy(
                            downloadedBytes = got,
                            totalBytes = if (total > 0) total else expectedSize,
                        )
                    }
                }
            }
            // целостность скачанного файла — SHA-256 от сервера (если он его
            // прислал) и совпадение размера. При несовпадении файл удаляется.
            val err = integrityError(outFile, _state.value.apkSha256, _state.value.apkSize.takeIf { it > 0 } ?: expectedSize)
            if (err != null) {
                outFile.delete()
                _state.value = _state.value.copy(isDownloading = false, apkFile = null, lastError = err)
                return Result.failure(IllegalStateException(err))
            }
            _state.value = _state.value.copy(isDownloading = false, apkFile = outFile)
            launchExplorer(outFile)
            Result.success(Unit)
        } catch (e: ApiError) {
            _state.value = _state.value.copy(isDownloading = false, lastError = "ошибка ${e.code}: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isDownloading = false, lastError = e.message ?: "ошибка загрузки")
            Result.failure(e)
        }
    }

    /**
     * v13: текст чейнжлога обновления («Что нового?»). Порядок: (1) поле
     * changelog из ответа /api/updates/check, (2) прямое скачивание
     * /updates/<имя>.txt — файл рядом с релизом с тем же именем (публичный).
     * Успешный результат кэшируется в [state] — повторное открытие диалога
     * сеть не дёргает. null — чейнжлог недоступен.
     */
    suspend fun fetchChangelog(): String? {
        _state.value.changelog?.let { return it }
        val api = apiProvider() ?: fallbackApi() ?: run {
            _state.value = _state.value.copy(lastError = "не задан адрес сервера")
            return null
        }
        return try {
            // Имя файла с сервера → то же имя с .txt; только простое имя без пути.
            // СБОРКА 7: fallback — платформенное имя релиза, как его ждёт сервер
            // (TunnelMessengerWindows(vN).exe / TunnelMessengerLinux(vN).appimage).
            val safeBase = File(
                _state.value.apkFilename.orEmpty().ifBlank {
                    _state.value.latestVersion?.let { v ->
                        if (platform == "windows") "TunnelMessengerWindows(v$v).exe"
                        else "TunnelMessengerLinux(v$v).appimage"
                    } ?: ""
                },
            ).name
            if (safeBase.isBlank() || safeBase.contains("..") || !FILENAME_REGEX.matches(safeBase)) return null
            val txt = api.downloadUpdateText(safeBase.substringBeforeLast('.') + ".txt")
            if (txt.isNotBlank()) {
                _state.value = _state.value.copy(changelog = txt)
                txt
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Переустановить из кэша — без повторного скачивания (файл уже в
     * AppDirs.updatesDir). Если файла нет — запускает полный цикл
     * скачивания. Возвращает успех, если папка была открыта.
     */
    suspend fun reinstallFromCache(): Result<Unit> {
        val st = _state.value
        val f = st.apkFile
        if (f == null || !f.exists() || f.length() == 0L) {
            return downloadAndInstall()
        }
        // v13 (аудит): перед открытием — та же проверка целостности.
        val err = integrityError(f, st.apkSha256, st.apkSize.takeIf { it > 0 } ?: 0L)
        if (err != null) {
            f.delete()
            _state.value = st.copy(apkFile = null, installerLaunched = false, lastError = err)
            return Result.failure(IllegalStateException(err))
        }
        return if (launchExplorer(f)) Result.success(Unit)
        else Result.failure(IllegalStateException("не удалось открыть папку с файлом"))
    }

    /**
     * v13 (аудит): контрольная сумма SHA-256 файла (потоково, память константна).
     */
    private fun sha256Of(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * v13 (аудит): проверка целостности файла перед «установкой»:
     * (1) SHA-256, полученный от сервера в /api/updates/check;
     * (2) точный размер (если известен).
     * Возвращает null, если проверка пройдена или ожиданий нет (старый сервер),
     * иначе — текст ошибки.
     */
    private fun integrityError(f: File, expectedSha: String?, expectedSize: Long): String? {
        if (!expectedSha.isNullOrBlank()) {
            val actual = sha256Of(f)
            if (actual == null || !actual.equals(expectedSha, ignoreCase = true)) {
                return "контрольная сумма файла не совпала — файл отброшен (возможна подмена)"
            }
        }
        if (expectedSize > 0 && f.length() != expectedSize) {
            return "размер файла не совпал (${f.length()} из $expectedSize) — файл отброшен"
        }
        return null
    }

    /** Есть ли уже скачанный файл в кэше (можно «установить» без скачивания)? */
    fun hasCachedFile(): Boolean {
        val f = _state.value.apkFile ?: return false
        return f.exists() && f.length() > 0
    }

    /**
     * «Установка» на десктопе: открыть папку с файлом (java.awt.Desktop.open),
     * сам файл НЕ запускается. true — папка открыта.
     */
    private fun launchExplorer(f: File): Boolean {
        val opened = runCatching {
            if (!java.awt.Desktop.isDesktopSupported()) return@runCatching false
            java.awt.Desktop.getDesktop().open(f.parentFile)
            true
        }.getOrDefault(false)
        if (opened) {
            _state.value = _state.value.copy(installerLaunched = true)
        } else {
            _state.value = _state.value.copy(
                installerLaunched = false,
                lastError = "не удалось открыть папку — файл лежит в ${f.parent}",
            )
        }
        return opened
    }

    /** Сбросить состояние (например, после успешной «установки» или отмены). */
    fun reset() {
        _state.value = UpdateState()
    }

    /**
     * Сбросить состояние после очистки локального кэша (файл удалён).
     * Сохраняет lastCheck (чтобы пользователь видел, что обновление есть),
     * но обнуляет apkFile/installerLaunched.
     */
    fun resetAfterCacheClear() {
        _state.value = _state.value.copy(
            apkFile = null,
            installerLaunched = false,
            isDownloading = false,
            downloadedBytes = 0,
            totalBytes = 0,
        )
    }
}

/**
 * Состояние процесса обновления (контракт 2.9).
 * Один активный процесс: либо проверка, либо загрузка. «Установка» —
 * открытие папки с проверенным файлом.
 */
data class UpdateState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val apkFilename: String? = null,
    val apkSize: Long = 0,
    val downloadUrl: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val apkFile: File? = null,
    val installerLaunched: Boolean = false,
    val lastError: String? = null,
    /** v13: кэш текста чейнжлога релиза («Что нового?»). */
    val changelog: String? = null,
    /** v13 (аудит): ожидаемая SHA-256 файла релиза (hex, из /api/updates/check). */
    val apkSha256: String? = null,
) {
    /** Прогресс в процентах 0..100 (или -1, если размер неизвестен). */
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1
}
