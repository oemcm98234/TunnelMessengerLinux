package com.tunnelmessenger.desktop.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunnelmessenger.desktop.tunnel.FreeTurnManager
import java.awt.Desktop
import java.net.URI
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import javax.swing.SwingUtilities

/**
 * СБОРКА 9: ВСТРОЕННАЯ КАПЧА VK ДЛЯ FREETURN (паритет с Android
 * CaptchaWebViewDialog).
 *
 * Как это работает (ядро free-turn-proxy, internal/.../captcha/manual):
 *  1) при капче ядро поднимает ЛОКАЛЬНЫЙ reverse-proxy на 127.0.0.1:8765,
 *     который отдаёт страницу капчи VK с переписанными URL и инжект-скриптом;
 *  2) страница решается (слайдер/PoW), а токен успеха уходит ОБРАТНО НА ЭТОТ
 *     локальный сервер (fetch → /local-captcha-result или перехват
 *     captchaNotRobot.check) — ядро ждёт его в keyCh и продолжает подключение;
 *  3) значит, страницу можно открыть ЛЮБЫМ браузером на той же машине —
 *     в том числе встроенным JavaFX WebView внутри нашего окна (Android-подход:
 *     WebView в том же процессе; на десктопе callback всё равно приходит на
 *     localhost того же хоста).
 *
 * Движок — JavaFX WebView (полноценный WebKit с JS): зависимости javafx-web
 * вшиты в fat-jar, нативы извлекаются шимом com.sun.glass.utils.NativeLibLoader.
 * Если JavaFX не поднялся (нет GTK3 на голом Linux и т.п.) — автоматический
 * фолбэк: системный браузер (callback всё равно придёт ядру — он на localhost).
 */
object CaptchaWebView {

    @Volatile private var fxStarted = false

    /** Признак «встроенный просмотр недоступен» — больше не пытаемся (фолбэк-браузер). */
    @Volatile
    var unavailable: Boolean = false

    /**
     * Preflight: поднять JavaFX-платформу ОДИН раз. Бросок = нативы/GTK не
     * завелись — вызывающий код уходит в системный браузер.
     */
    fun ensureStarted() {
        if (fxStarted) return
        synchronized(this) {
            if (fxStarted) return
            // Программный рендер Prism: капча лёгкая, зато работает без GPU
            // (на тех же машинах, где skiko сам переключился на SOFTWARE).
            if (System.getProperty("prism.order") == null) {
                System.setProperty("prism.order", "sw")
            }
            // Не убивать FX-платформу, когда окно капчи закрыто.
            Platform.setImplicitExit(false)
            Platform.startup { }
            fxStarted = true
            System.err.println("[captcha] JavaFX WebView инициализирован (prism.order=sw)")
        }
    }

    /** JFXPanel со страницей капчи. Вызывать на EDT (SwingPanel.factory так и делает). */
    fun createPanel(url: String): JFXPanel {
        ensureStarted()
        val panel = JFXPanel()
        Platform.runLater {
            try {
                val web = WebView()
                web.engine.load(url)
                panel.scene = Scene(web)
            } catch (t: Throwable) {
                System.err.println("[captcha] ошибка WebView: $t")
            }
        }
        return panel
    }

    /** Фолбэк: системный браузер (как было до сборки 9). */
    fun openInSystemBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
        }.onFailure {
            System.err.println("[captcha] не удалось открыть браузер: ${it.message}")
        }
    }
}

/**
 * Диалог капчи (порт Android CaptchaWebViewDialog). Показывается автоматически,
 * когда FreeTurnManager.captchaUrl != null; сам закрывается, когда ядро
 * сообщит об успехе («received success token» → captchaUrl = null).
 *
 * «Открыть в браузере» — ручной фолбэк (callback приходит ядру с того же
 * localhost — прохождение засчитается и без встроенного окна).
 */
@Composable
fun CaptchaDialog(url: String, onDismiss: () -> Unit) {
    // Preflight JavaFX: на композии делать нельзя (UnsatisfiedLinkError уронит окно) —
    // только в LaunchedEffect (фон), с фолбэком на системный браузер.
    LaunchedEffect(url) {
        val ok = runCatching { CaptchaWebView.ensureStarted() }.isSuccess
        if (!ok) {
            CaptchaWebView.unavailable = true
            FreeTurnManager.captchaFallbackToBrowser()
            onDismiss()
        }
    }
    if (CaptchaWebView.unavailable) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(860.dp).padding(8.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Капча VK", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Пройдите проверку прямо в этом окне — FreeTurn продолжит " +
                        "подключение автоматически. Кнопка «Открыть в браузере» — " +
                        "запасной вариант (результат всё равно придёт ядру).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // Встроенный WebKit. SwingPanel создаёт панель на EDT — как требует JFXPanel.
                androidx.compose.ui.awt.SwingPanel(
                    factory = { CaptchaWebView.createPanel(url) },
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    TextButton(onClick = {
                        CaptchaWebView.openInSystemBrowser(url)
                    }) { Text("Открыть в браузере") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Скрыть (решу позже)")
                    }
                }
            }
        }
    }
}
