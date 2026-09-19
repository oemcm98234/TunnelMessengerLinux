package com.tunnelmessenger.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.tunnelmessenger.desktop.call.CallManager
import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.ws.WsState
import com.tunnelmessenger.desktop.tunnel.TunnelHealth
import com.tunnelmessenger.desktop.tunnel.FreeTurnManager
import com.tunnelmessenger.desktop.tunnel.TunnelManager
import com.tunnelmessenger.desktop.tray.DesktopIntegrations
import com.tunnelmessenger.desktop.tray.bindWindowStateListeners
import com.tunnelmessenger.desktop.ui.screens.BlockedUsersScreen
import com.tunnelmessenger.desktop.ui.screens.CallOverlay
import com.tunnelmessenger.desktop.ui.screens.CaptchaDialog
import com.tunnelmessenger.desktop.ui.screens.ChatPane
import com.tunnelmessenger.desktop.ui.screens.ChatsScreen
import com.tunnelmessenger.desktop.ui.screens.ChatListPane
import com.tunnelmessenger.desktop.ui.screens.ContactsScreen
import com.tunnelmessenger.desktop.ui.screens.LoginScreen
import com.tunnelmessenger.desktop.ui.screens.NewChatScreen
import com.tunnelmessenger.desktop.ui.screens.SettingsScreen
import com.tunnelmessenger.desktop.ui.screens.TunnelScreen
import com.tunnelmessenger.desktop.ui.theme.Brand
import com.tunnelmessenger.desktop.ui.theme.TunnelMessengerTheme
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.Locale

/**
 * v15 (сборка 6): диагностика запуска — stderr + необработанные исключения
 * дублируются в ~/.tunnelmessenger/logs/desktop.log: если у пользователя
 * окно рисуется пустым/чёрным — по логу видно, где именно сломалось.
 */
private object DesktopLog {

    fun init() {
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            System.err.println("[FATAL] поток=${t.name}: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            prevHandler?.uncaughtException(t, e)
        }
        runCatching {
            val dir = File(File(System.getProperty("user.home"), ".tunnelmessenger"), "logs")
            if (!dir.isDirectory) dir.mkdirs()
            val f = File(dir, "desktop.log")
            // ротация: >2МиБ → в desktop.prev.log
            if (f.length() > 2_000_000L) {
                val old = File(dir, "desktop.prev.log")
                old.delete()
                f.renameTo(old)
            }
            val fout = FileOutputStream(f, true)
            val orig = System.err
            System.setErr(
                PrintStream(
                    object : OutputStream() {
                        override fun write(b: Int) {
                            orig.write(b); fout.write(b)
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            orig.write(b, off, len); fout.write(b, off, len)
                        }
                        override fun flush() {
                            orig.flush(); fout.flush()
                        }
                        override fun close() {
                            fout.flush()
                        }
                    },
                    true,
                    Charsets.UTF_8,
                ),
            )
            System.err.println(
                "=== TunnelMessenger Desktop запуск ${java.time.LocalDateTime.now()} | " +
                    "java ${System.getProperty("java.version")} | ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ===",
            )
        }
    }
}

/**
 * СБОРКА 6: режим рендера Skia. По умолчанию — SOFTWARE: на части машин
 * (GPU-драйверы, RDP, виртуалки) аппаратный GL/Direct3D не инициализируется
 * и окно остаётся пустым/чёрным при живом процессе; программный рендер
 * для мессенджера незаметен по производительности, зато работает везде.
 *  - -Dtm.render=gl — вернуть аппаратный рендер;
 *  - -Dskiko.renderApi=… — явный выбор пользователя имеет наивысший приоритет.
 */
private object RenderMode {

    fun apply() {
        if (System.getProperty("skiko.renderApi") != null) return
        val pref = System.getProperty("tm.render", "software").lowercase(Locale.ROOT)
        if (pref !in setOf("gl", "gpu", "metal", "direct3d")) {
            System.setProperty("skiko.renderApi", "SOFTWARE")
        }
        System.err.println("[render] skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "<по умолчанию (GPU)>"}")
    }
}

/**
 * v15 (сборка 5, десктоп): оболочка «окно + компактный верхний бар + правая
 * боковая панель». Рейл слева УБРАН — на ПК он дублировал окно:
 *
 *  - верхний бар 48dp (как на Android): глиф ▚▞, «Tunnel Messenger», статусы
 *    сервера и туннеля точками, справа — иконки «Туннель» (щит) и «Настройки»;
 *  - «Туннель»/«Настройки»/«Заблокированные» открываются БОКОВОЙ ПАНЕЛЬЮ от
 *    правого края (не шире половины окна): контент слева блюрится, клик мимо
 *    панели (или Esc) закрывает её;
 *  - «Контакты» — кнопкой FAB в списке чатов (как на Android), «Блокировки» —
 *    из настроек (как на Android), отдельной «кнопки блокировки» больше нет;
 *  - статусы внизу рейла (2 точки) убраны — они переехали в верхний бар.
 */

/**
 * СБОРКА 7: маршрутизатор Esc — единая обработка кнопки «назад» на УРОВНЕ
 * ОКНА. Раньше Esc ловился onPreviewKeyEvent на внутренних Box’ах — но он
 * срабатывает только вдоль цепочки фокуса: если фокус нигде не стоит
 * (клик по списку/кнопке без фокусируемого поля), событие до composables
 * не доходило, и из «Контактов»/«Нового чата» было НЕ ВЫЙТИ. Оконный
 * onKeyEvent вызывается для всех клавиатурных событий независимо от фокуса.
 */
private object EscRouter {
    @Volatile var handler: (() -> Boolean)? = null
}

/**
 * Основные страницы контента (всё остальное — панели/оверлеи).
 * СБОРКА 8: единственная главная страница — Чаты (мастер-детейл);
 * «Контакты» и «Новый чат» открываются БОКОВОЙ ПАНЕЛЬЮ СЛЕВА — так же,
 * как «Туннель»/«Настройки» справа (по фидбеку: верхний бар УБРАН,
 * кнопки-иконки туннель/настройки — слева от FAB «Контакты»/«Новый чат»).
 */
private enum class LeftPanel { Contacts, NewChat }

/** Что сейчас открыто в правой боковой панели (null — закрыта). */
private enum class SidePanel { Settings, Tunnel, Blocked }

fun main() {
    // Сборка 6: сначала лог и режим рендера — до любых сетевых/графических
    // подсистем; любой сбой запуска останется в ~/.tunnelmessenger/logs/desktop.log.
    DesktopLog.init()
    RenderMode.apply()
    // СБОРКА 9: одиночный экземпляр (автозапуск + ручной запуск = одна копия).
    if (!DesktopIntegrations.acquireSingleInstanceLock()) {
        System.err.println("[lock] Tunnel Messenger уже запущен — вторая копия закрывается")
        return
    }
    // СБОРКА 9: при завершении — гарантированно гасим дочерние процессы
    // туннеля (иначе движки осиротеют и продолжат работать без приложения).
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { TunnelManager.stopFreeTurnEngine(false) }
            runCatching { FreeTurnManager.stopCoreOnly() }
        },
    )
    // Ядро: БД/конфиг/секреты + старт WS, если аккаунт сохранён.
    Repository.init()
    // v11.3: health-тест туннеля (как в Amnezia) — база берётся у репозитория.
    TunnelHealth.start { Repository.baseForProbe() }

    application {
        val state = rememberWindowState(width = 1180.dp, height = 780.dp)
        // AWT-окно для onCloseRequest (лямбда без параметров — WindowScope
        // в неё не входит): заполняется на первой композиции контента.
        var awtWindow: java.awt.Window? = null
        Window(
            // СБОРКА 9: закрытие окна при включённом фоне СКРЫВАЕТ приложение
            // в трей (WebSocket живёт, уведомления приходят) — как foreground-
            // служба на Android. Выключить — в Настройках («Фон и уведомления»).
            // СБОРКА 9 (фикс): раньше ветка была пустой — окно «не закрывалось»,
            // но и не пряталось. Скрываем ТОЛЬКО когда иконка трея реально
            // добавлена (иначе в GNOME без AppIndicator окно можно потерять
            // навсегда: возвращать его некому).
            onCloseRequest = {
                if (Repository.backgroundEnabled && DesktopIntegrations.trayActive) {
                    runCatching { awtWindow?.isVisible = false }
                    // componentHidden поднимет windowHidden — уведомления включатся
                } else {
                    exitApplication()
                }
            },
            title = "Tunnel Messenger",
            state = state,
            icon = rememberAppIcon(),
            // СБОРКА 7: Esc («назад») на уровне окна — работает при любом фокусе.
            onKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    EscRouter.handler?.invoke() ?: false
                } else {
                    false
                }
            },
        ) {
            // v15: фон окна задаём ДО первой композиции — иначе при старте мелькает
            // чужой кадр; цвет — по активной теме (тёмный #0E1512 / светлый #F7F9F8).
            // Пересчитывается при смене режима темы в настройках (Flow ниже).
            val themeMode by Repository.themeModeFlow.collectAsState()
            window.background = when {
                themeMode == "dark" -> java.awt.Color(0x0E, 0x15, 0x12)
                themeMode == "light" -> java.awt.Color(0xF7, 0xF9, 0xF8)
                else -> {
                    // авто: по системной теме (Windows — реестр, Linux — gsettings)
                    if (com.tunnelmessenger.desktop.ui.theme.SystemTheme.isSystemDark())
                        java.awt.Color(0x0E, 0x15, 0x12)
                    else java.awt.Color(0xF7, 0xF9, 0xF8)
                }
            }
            window.minimumSize = Dimension(940, 600)
            awtWindow = window // для onCloseRequest (скрытие в трей, сборка 9)

            // СБОРКА 9: слушатели фокуса/сворачивания — для «уведомлять, когда
            // приложение в фоне» (как на Android).
            DisposableEffect(Unit) {
                bindWindowStateListeners(window)
                onDispose { }
            }

            TunnelMessengerTheme {
                AppRoot(
                    appWindow = window,
                    onExitApp = {
                        runCatching { TunnelManager.stopFreeTurnEngine(false) }
                        runCatching { FreeTurnManager.stopCoreOnly() }
                        exitApplication()
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ иконка окна

/**
 * СБОРКА 7: фирменная иконка приложения — тот же знак, что у Android-клиента
 * (ресурс ic_launcher_foreground.xml: 4 изумрудных квадранта ▚▞ на тёмном
 * фоне #0A0C0B). PNG генерируется из того же вектора и кладётся в
 * src/main/resources/icons/icon.png — в fat-jar и в jpackage-поставку
 * (см. icons/icon.ico + icons/icon.png 512 для MSI/EXE/AppImage/DEB).
 */
private class BitmapIconPainter(private val bmp: ImageBitmap) : Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(
        bmp.width.toFloat().coerceAtLeast(1f),
        bmp.height.toFloat().coerceAtLeast(1f),
    )

    override fun DrawScope.onDraw() {
        drawImage(
            bmp,
            dstSize = IntSize(
                intrinsicSize.width.toInt(),
                intrinsicSize.height.toInt(),
            ),
        )
    }
}

@Composable
private fun rememberAppIcon(): Painter? {
    return remember {
        runCatching {
            val bytes = DesktopLog::class.java.classLoader
                .getResourceAsStream("icons/icon.png")?.use { it.readBytes() }
                ?: return@runCatching null
            BitmapIconPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }.getOrNull()
    }
}

@Composable
private fun AppRoot(appWindow: java.awt.Window, onExitApp: () -> Unit) {
    val account by Repository.account.collectAsState()
    val backgroundEnabled by Repository.backgroundEnabledFlow.collectAsState()

    // Состояние оболочки ПК: открытый чат (master-detail), левая панель
    // (Контакты/Новый чат), правая панель (настройки/туннель/блокировки).
    var openChatId by remember { mutableStateOf<Long?>(null) }
    var sidePanel by remember { mutableStateOf<SidePanel?>(null) }
    var leftPanel by remember { mutableStateOf<LeftPanel?>(null) }

    // Автосинк при запуске (как Repository.refresh() в onStart у Android).
    // десктоп: setForeground/службы/уведомления — не требуется.
    LaunchedEffect(Unit) {
        Repository.refresh()
    }

    // СБОРКА 9: СИСТЕМНЫЙ ТРЕЙ — пока авторизован и включён «работать в фоне».
    // Клик по иконке — открыть окно из трея; в меню — «Открыть» и «Выход».
    DisposableEffect(account != null, backgroundEnabled) {
        if (account != null && backgroundEnabled) {
            DesktopIntegrations.ensureTray(
                onOpen = {
                    runCatching {
                        appWindow.isVisible = true
                        (appWindow as? java.awt.Frame)?.extendedState = java.awt.Frame.NORMAL
                        appWindow.toFront()
                    }
                },
                onQuit = onExitApp,
            )
        } else {
            DesktopIntegrations.removeTray()
        }
        onDispose { }
    }

    // Единый сброс навигации (после логина/логаута).
    fun resetNav() {
        openChatId = null
        sidePanel = null
        leftPanel = null
    }

    // СБОРКА 7: Esc («назад») обрабатывается на уровне окна (см. EscRouter);
    // здесь — актуальный приоритет: правая панель → левая панель → чат.
    DisposableEffect(Unit) {
        EscRouter.handler = {
            when {
                sidePanel != null -> { sidePanel = null; true }
                leftPanel != null -> { leftPanel = null; true }
                openChatId != null -> { openChatId = null; true }
                else -> false
            }
        }
        onDispose { EscRouter.handler = null }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (account == null) {
            // Логин — контент ограничен по ширине и центрирован. «Туннель» с
            // экрана входа открывается той же правой панелью (скрим).
            Box(Modifier.fillMaxSize()) {
                LoginScreen(
                        onLoggedIn = { resetNav() },
                        onOpenTunnel = { sidePanel = SidePanel.Tunnel },
                    )
                SidePanelHost(
                    panel = if (sidePanel == SidePanel.Tunnel) sidePanel else null,
                    onDismiss = { sidePanel = null },
                ) { p ->
                    when (p) {
                        SidePanel.Tunnel -> TunnelScreen(onBack = { sidePanel = null })
                        else -> {}
                    }
                }
                AppOverlays()
            }
        } else {
            // СБОРКА 8: верхний бар УБРАН (по фидбеку). Статус связи перенесён
            // в шапку панели списка чатов (ChatsScreen.ChatStatusCaption);
            // кнопки «Туннель»/«Настройки» — иконками СЛЕВА от FAB
            // «Контакты»/«Новый чат» (см. ChatListPane).
            Box(Modifier.fillMaxSize()) {
                ChatsSectionArea(
                    openChatId = openChatId,
                    onOpenChat = { openChatId = it },
                    onCloseChat = { openChatId = null },
                    onNewChat = { leftPanel = LeftPanel.NewChat },
                    onOpenContacts = { leftPanel = LeftPanel.Contacts },
                    onOpenTunnel = { sidePanel = SidePanel.Tunnel },
                    onOpenSettings = { sidePanel = SidePanel.Settings },
                )
                // ЛЕВЫЕ панели: «Контакты» и «Новый чат» — открываются так же,
                // как «Туннель»/«Настройки» (скрим + шторка), только слева.
                LeftPanelHost(
                    panel = leftPanel,
                    onDismiss = { leftPanel = null },
                ) { p ->
                    when (p) {
                        LeftPanel.Contacts -> ContactsScreen(
                            onOpenChat = { id -> openChatId = id; leftPanel = null },
                        )
                        LeftPanel.NewChat -> NewChatScreen(
                            onCreated = { id ->
                                leftPanel = null
                                openChatId = id
                            },
                        )
                    }
                }
                SidePanelHost(
                    panel = sidePanel,
                    onDismiss = { sidePanel = null },
                ) { p ->
                    when (p) {
                        SidePanel.Settings -> SettingsScreen(
                            onBack = { sidePanel = null },
                            onOpenBlocked = { sidePanel = SidePanel.Blocked },
                            onLoggedOut = { resetNav() },
                        )
                        SidePanel.Tunnel -> TunnelScreen(onBack = { sidePanel = null })
                        SidePanel.Blocked -> BlockedUsersScreen(
                            onBack = { sidePanel = SidePanel.Settings },
                        )
                    }
                }
                AppOverlays()
            }
        }
    }
}

/**
 * ЛЕВАЯ боковая панель (СБОРКА 8): полное зеркало [SidePanelHost] — «Контакты»
 * и «Новый чат» открываются шторкой от ЛЕВОГО края (по фидбеку: как правые
 * «Туннель»/«Настройки»). Скрим + скольжение слева; закрывается кликом мимо
 * или Esc. Без шапки с крестиком и без BackHeader.
 */
@Composable
private fun BoxScope.LeftPanelHost(
    panel: LeftPanel?,
    onDismiss: () -> Unit,
    content: @Composable (LeftPanel) -> Unit,
) {
    var lastPanel by remember { mutableStateOf(panel) }
    if (panel != null) lastPanel = panel

    AnimatedVisibility(
        visible = panel != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                },
        )
    }

    BoxWithConstraints(Modifier.matchParentSize()) {
        val panelWidth = (maxWidth * 0.5f).coerceIn(380.dp, 640.dp)
        AnimatedVisibility(
            visible = panel != null,
            // от ЛЕВОГО края: минус-смещение входа
            enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(200)) { -it } + fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight(),
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                lastPanel?.let { p ->
                    content(p)
                }
            }
        }
    }
}

/**
 * Правая боковая панель (СБОРКА 5): скрим на весь экран (клик мимо = закрыть)
 * + панель от правого края шириной РОВНО ПОЛОВИНА окна (380–640dp), со
 * скольжением справа.
 *
 * СБОРКА 7 (оптимизация «лагает при открытии/закрытии»):
 *  - УБРАН Modifier.blur(16.dp) на контенте ПОД панелью — в программном
 *    рендере (SOFTWARE — дефолт сборки 6) блюр целого окна пересчитывается
 *    на CPU КАЖДЫЙ КАДР (анимация скрима/шторки, мигание статусов, печать),
 *    что и давало сильные лаги. Вместо него — чуть более плотный скрим
 *    (быстрый однотонный прямоугольник);
 *  - тень панели 16dp заменена рамкой 1dp — тень Skia это тоже блюр,
 *    и она перерисовывалась на каждом кадре слайда;
 *  - сама анимация — placement-phase (offset), без ре-композиции контента.
 */
@Composable
private fun BoxScope.SidePanelHost(
    panel: SidePanel?,
    onDismiss: () -> Unit,
    content: @Composable (SidePanel) -> Unit,
) {
    // Последняя открытая панель — чтобы контент не мигал при закрытии (exit-анимация).
    var lastPanel by remember { mutableStateOf(panel) }
    if (panel != null) lastPanel = panel

    // Скрим: полупрозрачный, клик мимо панели закрывает. Однотонный слой —
    // дешёвый в любом рендере (альфа меняется только в draw-фазе).
    AnimatedVisibility(
        visible = panel != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                .pointerInput(onDismiss) {
                    detectTapGestures { onDismiss() }
                },
        )
    }

    // Панель: ровно половина ширины окна (не уже 380dp и не шире 640dp).
    BoxWithConstraints(Modifier.matchParentSize()) {
        val panelWidth = (maxWidth * 0.5f).coerceIn(380.dp, 640.dp)
        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
        ) {
            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                lastPanel?.let { p ->
                    content(p)
                }
            }
        }
    }
}

/**
 * Страница «Чаты»: master-detail при ширине ≥1000dp — панель списка 320dp слева
 * и [ChatPane] открытого чата справа; уже <1000dp — прежний «стек» (список,
 * а открытый чат — на всю ширину). Выбор чата — Repository.openChat (эффект
 * внутри ChatPane), состояние выбора живёт в AppRoot.
 *
 * СБОРКА 8: onOpenContacts/onNewChat теперь открывают ЛЕВУЮ панель,
 * onOpenTunnel/onOpenSettings — правую (кнопки — внизу панели списка,
 * см. ChatListPane).
 */
@Composable
private fun ChatsSectionArea(
    openChatId: Long?,
    onOpenChat: (Long) -> Unit,
    onCloseChat: () -> Unit,
    onNewChat: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenTunnel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1000.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ChatListPane(
                    selectedChatId = openChatId,
                    onChat = onOpenChat,
                    onNewChat = onNewChat,
                    onOpenContacts = onOpenContacts,
                    onOpenTunnel = onOpenTunnel,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                val openId = openChatId
                if (openId != null) {
                    // СБОРКА 5: в master-detail «✕ закрыть чат» не нужна —
                    // переключение кликом по списку / Esc (кнопка «выхода»
                    // в правом углу убрана по фидбеку).
                    ChatPane(
                        chatId = openId,
                        onClose = null,
                        onOpenChat = onOpenChat,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Пустое состояние: чат ещё не выбран
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // v15: знак в цвете акцента — в тёмной теме прежний
                            // #34d399, в светлой Teal600 (контраст на светлом фоне)
                            Text(
                                "▚▞",
                                color = if (com.tunnelmessenger.desktop.ui.theme.isAppInDarkTheme())
                                    Color(0xFF34D399)
                                else com.tunnelmessenger.desktop.ui.theme.Teal600,
                                fontSize = 30.sp,
                                letterSpacing = 4.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Выберите чат", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Список чатов — слева; кнопки — внизу панели",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            // Узкий режим: прежний мобильный «стек» — список или чат на всю ширину
            val openId = openChatId
            if (openId != null) {
                ChatPane(
                    chatId = openId,
                    onClose = onCloseChat,
                    onOpenChat = onOpenChat,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ChatsScreen(
                    onOpenChat = onOpenChat,
                    onNewChat = onNewChat,
                    onOpenContacts = onOpenContacts,
                    onOpenTunnel = onOpenTunnel,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

/**
 * Оверлеи поверх ВСЕЙ навигации: звонок (пока CallManager не IDLE/ENDED),
 * ненавязчивый статус ядра (Repository.notice — десктоп-замена Toast) и
 * ВСТРОЕННАЯ КАПЧА VK (СБОРКА 9: FreeTurnManager.captchaUrl — порт
 * Android CaptchaWebViewDialog; «Скрыть» — через FreeTurnManager.hide,
 * повторное открытие — из карточки FreeTurn в панели Туннеля).
 */
@Composable
private fun BoxScope.AppOverlays() {
    val call by CallManager.ui.collectAsState()
    if (call.state != CallManager.CallState.IDLE && call.state != CallManager.CallState.ENDED) {
        CallOverlay()
    }

    // СБОРКА 9: встроенная капча FreeTurn (JavaFX WebView → localhost:8765).
    val captchaUrl by FreeTurnManager.captchaUrl.collectAsState()
    val captchaHidden by FreeTurnManager.captchaHidden.collectAsState()
    captchaUrl?.takeIf { !captchaHidden }?.let { url ->
        CaptchaDialog(url = url, onDismiss = { FreeTurnManager.hideCaptchaDialog() })
    }

    // v13: статус ядра (смена ключа E2E у собеседника, удаление чужого аккаунта).
    // Показывается поверх всего, гаснет сам через 6с (снекбар без действий).
    val notice by Repository.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6000)
            Repository.clearNotice()
        }
    }
    notice?.let { text ->
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
