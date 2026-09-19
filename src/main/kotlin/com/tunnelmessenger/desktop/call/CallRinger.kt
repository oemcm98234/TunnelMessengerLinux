package com.tunnelmessenger.desktop.call

import kotlin.math.PI
import kotlin.math.sin
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * v8: звуковые сигналы звонка (десктоп-порт на javax.sound.sampled).
 *
 *  - RING     — входящий звонок: двухтональная трель, цикл ~2 с;
 *  - RINGBACK — исходящий: классический гудок 425 Гц (1 с тон / 3 с пауза);
 *  - playBusy — короткий сигнал «занято» (0.35 с тон / 0.35 с пауза, ×4).
 *
 * Сигналы синтезируются на лету (SourceDataLine) и пишутся в выходной микшер
 * циклически. Никаких внешних файлов не требуется — мелодия устройства не
 * зависит. Вибрации на десктопе нет (нечем) — только звук.
 */
object CallRinger {

    enum class Mode { RING, RINGBACK }

    private const val SAMPLE_RATE = 22050

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var mode: Mode? = null

    /** Запустить циклический сигнал (RING или RINGBACK). Повторный вызов перезапускает. */
    fun start(m: Mode) {
        stop()
        mode = m
        thread = Thread {
            val buf = when (m) {
                Mode.RING -> ringPattern()
                Mode.RINGBACK -> ringbackPattern()
            }
            val bytes = pcm16ToBytes(buf)
            var l: SourceDataLine? = null
            try {
                l = AudioSystem.getSourceDataLine(format)
                l.open(format, bytes.size)
                line = l
                l.start()
                // блокирующая запись паттерна по кругу: сигнал крутится сам
                while (mode == m && line === l && !Thread.currentThread().isInterrupted) {
                    l.write(bytes, 0, bytes.size)
                }
                runCatching { l.drain() }
            } catch (ignored: Exception) {
                // аудиовыход недоступен — звонок продолжается без сигнала
            } finally {
                try { l?.stop() } catch (ignored: Exception) {}
                try { l?.close() } catch (ignored: Exception) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Остановить сигнал. */
    fun stop() {
        mode = null
        thread?.interrupt()
        thread = null
        val l = line
        line = null
        try { l?.stop() } catch (ignored: Exception) {}
        try { l?.close() } catch (ignored: Exception) {}
    }

    /** Разовый сигнал «занято» (после call_decline с reason=busy). */
    fun playBusy() {
        Thread {
            val buf = busyPattern()
            val bytes = pcm16ToBytes(buf)
            var l: SourceDataLine? = null
            try {
                l = AudioSystem.getSourceDataLine(format)
                l.open(format, bytes.size)
                l.start()
                l.write(bytes, 0, bytes.size)
                l.drain()
                Thread.sleep(300)
            } catch (ignored: Exception) {
            } finally {
                try { l?.stop() } catch (ignored: Exception) {}
                try { l?.close() } catch (ignored: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ паттерны

    private fun addTone(dst: MutableList<Short>, freq: Int, ms: Long, vol: Double = 0.35) {
        val n = (SAMPLE_RATE * ms / 1000).toInt()
        for (i in 0 until n) {
            val v = sin(2 * PI * freq * i / SAMPLE_RATE) * vol
            dst.add((v * Short.MAX_VALUE).toInt().toShort())
        }
    }

    private fun addSilence(dst: MutableList<Short>, ms: Long) {
        repeat((SAMPLE_RATE * ms / 1000).toInt()) { dst.add(0) }
    }

    /** Входящий: трель (2 × двойной тон) на 2 с, затем пауза 1 с. */
    private fun ringPattern(): ShortArray {
        val out = mutableListOf<Short>()
        addTone(out, 760, 240)
        addTone(out, 950, 160)
        addSilence(out, 120)
        addTone(out, 760, 240)
        addTone(out, 950, 160)
        addSilence(out, 880)
        return out.toShortArray()
    }

    /** Исходящий: гудок 425 Гц — 1 с тон / 3 с пауза (российский стандарт). */
    private fun ringbackPattern(): ShortArray {
        val out = mutableListOf<Short>()
        addTone(out, 425, 1000, vol = 0.28)
        addSilence(out, 3000)
        return out.toShortArray()
    }

    /** «Занято»: 425 Гц — 0.35 с тон / 0.35 с пауза, 4 повтора. */
    private fun busyPattern(): ShortArray {
        val out = mutableListOf<Short>()
        repeat(4) {
            addTone(out, 425, 350, vol = 0.28)
            addSilence(out, 350)
        }
        return out.toShortArray()
    }

    // ------------------------------------------------------------- утилиты

    /** PCM16 LE → байты (для SourceDataLine с little-endian форматом). */
    private fun pcm16ToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
