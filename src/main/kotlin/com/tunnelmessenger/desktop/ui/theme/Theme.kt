package com.tunnelmessenger.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import com.tunnelmessenger.desktop.data.Repository
import java.io.File

/**
 * Фирменная палитра: тёмно-зелёный/мятный (щит и туннель), без синих,
 * индиго и фиолетового. Порт Android ui/theme/Theme.kt 1:1 (токены из
 * контракта 2.10).
 */
object Brand {
    val Teal900 = Color(0xFF0B3D33)
    val Teal800 = Color(0xFF12433A)
    val Teal600 = Color(0xFF177A63)
    val Teal500 = Color(0xFF1E9B7B)
    val Mint = Color(0xFF2BD9A8)
    val MintDark = Color(0xFF1FA983)
    val Paper = Color(0xFFF7F9F8)
    val Ink = Color(0xFF152420)
    val BubbleMine = Color(0xFFD7F3E8)
    val BubbleTheirs = Color(0xFFF0F2F1)
    val Warn = Color(0xFFB3591F)
}

// Прямые ссылки на фирменные цвета — как в Android (top-level val'ы Theme.kt).
val Teal900 = Brand.Teal900
val Teal800 = Brand.Teal800
val Teal600 = Brand.Teal600
val Teal500 = Brand.Teal500
val Mint = Brand.Mint
val MintDark = Brand.MintDark
val Paper = Brand.Paper
val Ink = Brand.Ink
val BubbleMine = Brand.BubbleMine
val BubbleTheirs = Brand.BubbleTheirs
val Warn = Brand.Warn

/**
 * СБОРКА 5: детерминированные шрифты + ЦВЕТНЫЕ ЭМОДЗИ.
 *
 * Проблема: у «системного дефолта» рендер зависит от машины — на Linux без
 * эмодзи-шрифта эмодзи показывались квадратами, на Windows — монохромными/
 * кривыми. Решение: вшиваем в поставку статические начертания Inter
 * (Regular/Medium/SemiBold/Bold, есть кириллица) и NotoColorEmoji (цветные
 * эмодзи, CBDT), собираем из них FontFamily-цепочку фолбэков: латиница/
 * кириллица берётся из Inter, эмодзи — из Noto, остальное — системный
 * fallback Skia. Один и тот же рендер на Windows и Linux.
 *
 * Поиск файлов (как у движков в AppDirs.resolveEngine):
 *  1) {compose.application.resources.dir}/fonts/  (jpackage: MSI/EXE/AppImage/DEB);
 *  2) fonts/ рядом с jar (портативный запуск java -jar);
 *  3) ресурсы classpath /fonts/… (fat-jar сборки 6 — шрифты ВНУТРИ jar,
 *     извлекаются в ~/.tunnelmessenger/cache/fonts один раз);
 *  4) если ничего не найдено — FontFamily.Default (старое поведение).
 */
object AppFonts {

    val family: FontFamily by lazy { buildFamily() }

    /** Файл эмодзи-шрифта (если найден) — для регистрации в Skia-фолбэке (сборка 8). */
    var emojiFontFile: File? = null
        private set

    private fun candidates(name: String): List<File> {
        val list = mutableListOf<File>()
        System.getProperty("compose.application.resources.dir")?.let { d ->
            // jpackage копирует содержимое appResourcesRootDir как есть:
            // и app-resources/fonts/, и app-resources/common/fonts/
            list.add(File(File(d, "fonts"), name))
            list.add(File(File(File(d, "common"), "fonts"), name))
        }
        runCatching {
            val jarFile = File(AppFonts::class.java.protectionDomain.codeSource.location.toURI())
            jarFile.parentFile?.let { p -> list.add(File(File(p, "fonts"), name)) }
        }
        return list
    }

    private fun loadFont(name: String, weight: FontWeight): Font? {
        candidates(name).firstOrNull { it.isFile }?.let { f ->
            runCatching { PlatformFont(f, weight) }.getOrNull()?.let {
                log("$name ← ${f.path}")
                return it
            }
        }
        return fromClasspath(name)?.let { f ->
            runCatching { PlatformFont(f, weight) }.getOrNull()?.let {
                log("$name ← classpath → ${f.path}")
                return it
            }
        }
        log("$name НЕ ЗАГРУЖЕН")
        return null
    }

    private fun loadAny(name: String): Font? {
        candidates(name).firstOrNull { it.isFile }?.let { f ->
            runCatching { PlatformFont(f) }.getOrNull()?.let {
                log("$name ← ${f.path}")
                if (name == "NotoColorEmoji.ttf") emojiFontFile = f
                return it
            }
        }
        return fromClasspath(name)?.let { f ->
            runCatching { PlatformFont(f) }.getOrNull()?.let {
                log("$name ← classpath → ${f.path}")
                if (name == "NotoColorEmoji.ttf") emojiFontFile = f
                return it
            }
        }
        log("$name НЕ ЗАГРУЖЕН — эмодзи может выглядеть квадратами/монохромом")
        return null
    }

    /** СБОРКА 7: диагностика в desktop.log (stderr) — видно, какой шрифт откуда взят. */
    private fun log(msg: String) {
        System.err.println("[fonts] $msg")
    }

    /**
     * СБОРКА 6: шрифты, упакованные внутрь fat-jar (fonts/ в корне jar).
     * Skia читает шрифт из файла, поэтому ресурс извлекается в кэш
     * (~/.tunnelmessenger/cache/fonts) один раз (проверка по размеру).
     */
    private fun fromClasspath(name: String): File? {
        val url = runCatching { AppFonts::class.java.classLoader.getResource("fonts/$name") }
            .getOrNull() ?: return null
        return runCatching {
            val dir = File(File(File(System.getProperty("user.home"), ".tunnelmessenger"), "cache"), "fonts")
            if (!dir.isDirectory) dir.mkdirs()
            val target = File(dir, name)
            val bytes = url.openStream().use { it.readBytes() }
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                val tmp = File(dir, "$name.part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            if (target.isFile) target else null
        }.getOrNull()
    }

    /**
     * СБОРКА 8 — ГАРАНТИЯ ЦВЕТНЫХ ЭМОДЗИ.
     *
     * КОРЕНЬ ПРОБЛЕМЫ (воспроизведён скриншотом в песочнице с ЗАМАСКИРОВАННЫМИ
     * системными шрифтами): FontFamily(Inter…, NotoColorEmoji) — это
     * FontListFontFamily, а Compose Desktop ИСКЛЮЧАЕТ такие семейства из
     * Skia-фолбэка (в байткоде FontCache.ensureRegistered: «Don't load
     * FontListFontFamily through ensureRegistered»). Шрифт эмодзи использовался
     * только для ПРЯМОГО рендера текста этим семейством, а при нехватке глифа
     * в Inter Skia шёл в СИСТЕМНЫЙ менеджер шрифтов: где установлен
     * Noto Color Emoji — эмодзи цветные; где нет — КВАДРАТЫ.
     *
     * ФИКС: регистрируем NotoColorEmoji напрямую в TypefaceFontProviderWithFallback
     * резолвера (это asset-И фолбэк-менеджер Skia-параграфа — он опрашивается
     * ДО системного). Внутренние поля доступны рефлексией; фиксированные версии
     * compose 1.7.3 / skiko 0.8.18 — при изменении API просто пишем в лог и
     * живём как раньше (не хуже). Дополнительно (Linux) копируем эмодзи-шрифт
     * в ~/.local/share/fonts — страховка для системных диалогов/попапов.
     *
     * СБОРКА 9 — ДОБИВАЕМ «БЕЛЫЕ» ЭМОДЗИ (☀⚡☎⚠♥☕ и все BMP-символы).
     * Вскрытие байткода skiko 0.8.18: TypefaceFontProviderWithFallback
     * конструируется нативно как «_nMakeAsFallbackProvider» — его опрашивают
     * ПОСЛЕ системных шрифтов. Поэтому на машинах, где системный моно-шрифт
     * (DejaVu/Segoe UI Symbol/Noto Emoji mono) покрывает BMP-символы, эти
     * эмодзи рисовались БЕЛЫМИ, хотя вшитый NotoColorEmoji их умеет (цветные
     * оставались только те, что системе неизвестны: 😀🚀🫨…).
     * ФИКС: наш менеджер шрифтов с эмодзи ставится в Skia-FontCollection как
     * DYNAMIC (FontCollection.setDynamicFontManager) — Skia опрашивает менеджеры
     * в порядке test → asset → dynamic → default(система): эмодзи теперь берутся
     * из вшитого шрифта ДО любых системных, а всё, что эмодзи-шрифт не покрывает,
     * по-прежнему уходит в систему (латиница/иероглифы/символы не ломаются).
     */
    fun registerEmojiFallback(resolver: FontFamily.Resolver) {
        // Гарантируем инициализацию семейства (by lazy): именно buildFamily
        // находит файлы и заполняет emojiFontFile — иначе регистрация из
        // TunnelMessengerTheme могла запуститься РАНЬШЕ первой загрузки шрифтов.
        runCatching { family }
        val file = emojiFontFile ?: run {
            log("эмодзи-файл не найден — фолбэк не регистрируется"); return
        }
        installToUserFontDirLinux(file)
        runCatching {
            // skiko 0.8.18 не имеет Typeface.makeFromFile — собираем из байтов:
            // Data.makeFromBytes → FontMgr.default.makeFromData
            val data = org.jetbrains.skia.Data.makeFromBytes(file.readBytes())
            val typeface = org.jetbrains.skia.FontMgr.default.makeFromData(data, 0)
            // Цепочка compose 1.7.3 (desktop):
            //   FontFamilyResolverImpl.platformFontLoader (SkiaFontLoader)
            //     .fontCache (FontCache).fontProvider (TypefaceFontProviderWithFallback)
            // В старых версиях fontCache жил прямо в резолвере — пробуем обе.
            val cache: Any = fieldOrNull(resolver, "platformFontLoader")
                ?.let { loader -> fieldOrNull(loader, "fontCache") }
                ?: fieldOrNull(resolver, "fontCache")
                ?: error("не найден FontCache в резолвере (${resolver.javaClass.name})")
            val provider = fieldOrNull(cache, "fontProvider")
                ?: error("не найден fontProvider в FontCache")
            provider.javaClass.methods
                .firstOrNull { it.name == "registerTypeface" && it.parameterCount == 2 }
                ?.invoke(provider, typeface, "Noto Color Emoji")
                ?: error("registerTypeface(Typeface, String) не найден")
            log("NotoColorEmoji зарегистрирован в Skia-фолбэке (TypefaceFontProviderWithFallback)")

            // СБОРКА 9: эмодзи-менеджер как DYNAMIC в FontCollection — ПЕРЕД системным.
            val collection = fieldOrNull(cache, "fonts") as? org.jetbrains.skia.paragraph.FontCollection
            if (collection != null) {
                val emojiMgr = org.jetbrains.skia.paragraph.TypefaceFontProvider()
                emojiMgr.registerTypeface(typeface, "Noto Color Emoji")
                collection.setDynamicFontManager(emojiMgr)
                log("NotoColorEmoji установлен как DYNAMIC-менеджер FontCollection (раньше системных шрифтов)")
            } else {
                log("FontCollection не найден — DYNAMIC-уровень пропущен (остаётся фолбэк-регистрация)")
            }
        }.onFailure {
            log("регистрация эмодзи в Skia-фолбэке НЕ удалась (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    /** Рефлексия: поле name в классе объекта (включая суперклассы) или null. */
    private fun fieldOrNull(obj: Any, name: String): Any? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            runCatching {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    /** Страховка для Linux: шрифт в пользовательском каталоге fontconfig. */
    private fun installToUserFontDirLinux(file: File) {
        if (System.getProperty("os.name", "").lowercase().contains("win")) return
        runCatching {
            val dir = File(File(System.getProperty("user.home"), ".local/share/fonts"), "tunnelmessenger")
            if (!dir.isDirectory) dir.mkdirs()
            val target = File(dir, "NotoColorEmoji.ttf")
            if (!target.isFile || target.length() != file.length()) {
                file.copyTo(target, overwrite = true)
            }
            Runtime.getRuntime().exec(arrayOf("fc-cache", "-f", dir.absolutePath))
            log("NotoColorEmoji скопирован в ~/.local/share/fonts/tunnelmessenger (страховка)")
        }
    }

    private fun buildFamily(): FontFamily {
        val fonts = mutableListOf<Font>()
        log("загрузка встроенных шрифтов (Inter + NotoColorEmoji)…")
        loadFont("Inter-Regular.otf", FontWeight.Normal)?.let { fonts.add(it) }
        loadFont("Inter-Medium.otf", FontWeight.Medium)?.let { fonts.add(it) }
        loadFont("Inter-SemiBold.otf", FontWeight.SemiBold)?.let { fonts.add(it) }
        loadFont("Inter-Bold.otf", FontWeight.Bold)?.let { fonts.add(it) }
        // Эмодзи — последним в цепочке: глиф не найден в Inter → берётся отсюда.
        loadAny("NotoColorEmoji.ttf")?.let { fonts.add(it) }
        return if (fonts.isEmpty()) {
            log("ни один встроенный шрифт не загружен — FontFamily.Default")
            FontFamily.Default
        } else FontFamily(fonts)
    }
}

/** Все стили Material3 с единым семейством (Inter → NotoColorEmoji → система). */
private fun buildTypography(): Typography {
    val f = AppFonts.family
    val b = Typography()
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = f),
        displayMedium = b.displayMedium.copy(fontFamily = f),
        displaySmall = b.displaySmall.copy(fontFamily = f),
        headlineLarge = b.headlineLarge.copy(fontFamily = f),
        headlineMedium = b.headlineMedium.copy(fontFamily = f),
        headlineSmall = b.headlineSmall.copy(fontFamily = f),
        titleLarge = b.titleLarge.copy(fontFamily = f),
        titleMedium = b.titleMedium.copy(fontFamily = f),
        titleSmall = b.titleSmall.copy(fontFamily = f),
        bodyLarge = b.bodyLarge.copy(fontFamily = f),
        bodyMedium = b.bodyMedium.copy(fontFamily = f),
        bodySmall = b.bodySmall.copy(fontFamily = f),
        labelLarge = b.labelLarge.copy(fontFamily = f),
        labelMedium = b.labelMedium.copy(fontFamily = f),
        labelSmall = b.labelSmall.copy(fontFamily = f),
    )
}

/**
 * v15: светлая тема — полный набор ролей (порт Android LightScheme).
 *
 * Акцент — тот же изумрудный Mint #2BD9A8, что и в тёмной (primary не менялся:
 * кнопки, свитчи, ползунки, точка онлайна, огибающая голосовых — одинаковые
 * в обеих темах). Весь текст — тёмный (Ink/Teal900): всё, что в тёмной теме
 * было светлым (#DDE7E2/#A9B8B1), здесь тёмно-зелёное/чёрное и не сливается
 * со светлым фоном. Собственные пузыри — мятного оттенка (BubbleMine) с
 * тёмным текстом; чужие — светло-серые (BubbleTheirs). Семья surfaceContainer*
 * задана явно в зелёных нейтралах (дефолты Material3 — с фиолетовым подтоном).
 */
private val LightScheme = lightColorScheme(
    primary = Mint,
    onPrimary = Teal900,
    primaryContainer = BubbleMine,
    onPrimaryContainer = Teal900,
    inversePrimary = Teal600,
    secondary = Teal600,
    onSecondary = Color.White,
    secondaryContainer = BubbleMine,
    onSecondaryContainer = Teal900,
    tertiary = MintDark,
    onTertiary = Color.White,
    tertiaryContainer = BubbleMine,
    onTertiaryContainer = Teal900,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = BubbleTheirs,
    onSurfaceVariant = Color(0xFF4A5A54),
    surfaceTint = MintDark,
    surfaceDim = Color(0xFFDDE4E0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = BubbleTheirs,
    surfaceContainer = Color(0xFFEBEEEC),
    surfaceContainerHigh = Color(0xFFE5E9E7),
    surfaceContainerHighest = Color(0xFFDFE4E2),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF6F7F78),
    outlineVariant = Color(0xFFD3DCD7),
    scrim = Color.Black,
)

/**
 * Тёмная тема — без изменений (как была до v15).
 * Все контейнеры (чипы, переключатели, меню) — зелёные: у Material3 дефолтный
 * secondaryContainer фиолетовый, поэтому задан явно.
 */
private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Teal900,
    primaryContainer = Teal800,
    onPrimaryContainer = Mint,
    inversePrimary = Teal600,
    secondary = MintDark,
    onSecondary = Teal900,
    secondaryContainer = Teal800,
    onSecondaryContainer = Mint,
    tertiary = Teal500,
    onTertiary = Color.White,
    tertiaryContainer = Teal900,
    onTertiaryContainer = Mint,
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFDDE7E2),
    surface = Color(0xFF14201B),
    onSurface = Color(0xFFDDE7E2),
    surfaceVariant = Color(0xFF1C2A24),
    onSurfaceVariant = Color(0xFFA9B8B1),
    surfaceTint = MintDark,
    // Контейнеры поверхностей (карточки, диалоги, меню, шиты, чипы):
    // дефолты Material3 имеют фиолетово-серый подтон (#211F26…) — на зелёной
    // теме это выглядит грязно, поэтому вся семья задана явно в зелёных тонах.
    surfaceDim = Color(0xFF0E1512),
    surfaceBright = Color(0xFF1E2B25),
    surfaceContainerLowest = Color(0xFF0A100E),
    surfaceContainerLow = Color(0xFF121A16),
    surfaceContainer = Color(0xFF161F1B),
    surfaceContainerHigh = Color(0xFF1B2621),
    surfaceContainerHighest = Color(0xFF202B26),
    inverseSurface = Color(0xFFDDE7E2),
    inverseOnSurface = Color(0xFF0E1512),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF73867D),
    outlineVariant = Color(0xFF2C3B34),
    scrim = Color.Black,
)

@Composable
fun TunnelMessengerTheme(content: @Composable () -> Unit) {
    // СБОРКА 8: гарантированные цветные эмодзи — регистрируем вшитый
    // NotoColorEmoji в Skia-фолбэке ДО первой композиции текста (remember
    // выполняется синхронно в композиции, раньше всего контента).
    val resolver = LocalFontFamilyResolver.current
    remember(resolver) {
        runCatching { AppFonts.registerEmojiFallback(resolver) }
        true
    }
    // v15: тема — по настройке (Авто/Тёмная/Светлая); Авто = по системе.
    // Акцентный цвет в обеих темах один — Mint #2BD9A8.
    MaterialTheme(
        colorScheme = if (isAppInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/**
 * v15: тёмная ли тема СЕЙЧАС. Режим берётся из настроек (Repository.themeMode:
 * "auto" по умолчанию / "dark" / "light"); в авто-режиме — системная тема
 * (см. SystemTheme). Смена режима в настройках применяется сразу.
 */
@Composable
fun isAppInDarkTheme(): Boolean {
    val mode by Repository.themeModeFlow.collectAsState()
    return when (mode) {
        "dark" -> true
        "light" -> false
        else -> SystemTheme.isSystemDark()
    }
}

/**
 * Акцентный ЦВЕТ ТЕКСТА/мелких подписей, где акцент стоит на фоне темы
 * (ссылки, имена в цитатах, статусные подписи). В тёмной теме — Mint (как
 * было), в светлой — Teal600: тот же акцентный зелёный, но тон темнее
 * (Mint на белом ~1.8:1 — сливается; Teal600 — ~5.3:1).
 * Кнопки/заливки/иконки-акценты по-прежнему colorScheme.primary (Mint).
 */
@Composable
fun accentTextColor(): Color = if (isAppInDarkTheme()) Mint else Teal600

/** СБОРКА 5: типографика с вшитым семейством (инициализируется один раз). */
private val AppTypography: Typography by lazy { buildTypography() }
