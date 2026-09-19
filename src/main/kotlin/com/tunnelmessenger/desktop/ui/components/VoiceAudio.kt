package com.tunnelmessenger.desktop.ui.components

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Запись голосового сообщения — порт Android data/audio/Voice.kt на
 * javax.sound.sampled. По контракту 2.8: PCM 16 кГц mono 16 бит, WAV,
 * огибающая wave 0..100 (~36 столбиков).
 *
 * Разрешение RECORD_AUDIO на десктопе не требуется (нет runtime-пермиссий).
 */
class VoiceRecorder {

    data class Result(val file: File, val durationSec: Int, val wave: List<Int>)

    private var line: TargetDataLine? = null
    private var outFile: File? = null
    private var startedAt: Long = 0
    private val amps = mutableListOf<Int>()
    private val pcm = ByteArrayOutputStream()
    @Volatile private var sampling = false
    @Volatile private var readerThread: Thread? = null

    val isRecording: Boolean get() = line != null

    fun start(): Boolean = try {
        // Голосовые — во временном каталоге (в Android был cacheDir/voice).
        val dir = File(System.getProperty("java.io.tmpdir"), "tunnelmessenger-voice").apply { mkdirs() }
        val f = File(dir, "voice-${System.currentTimeMillis()}.wav")
        val format = AudioFormat(16000f, 16, 1, true, false) // PCM 16 кГц mono 16 бит
        val l = AudioSystem.getTargetDataLine(format)
        l.open(format)
        l.start()
        line = l
        outFile = f
        startedAt = System.currentTimeMillis()
        synchronized(amps) { amps.clear() }
        pcm.reset()
        sampling = true
        readerThread = thread(isDaemon = true, name = "voice-recorder") {
            val buf = ByteArray(3200) // 100 мс PCM (32000 Б/с)
            while (sampling) {
                val n = try { l.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n > 0) {
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    // Амплитуда кусочка = максимум |s16le| в буфере
                    var max = 0
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        val a = abs(s)
                        if (a > max) max = a
                        i += 2
                    }
                    synchronized(amps) { if (amps.size < 400) amps.add(max) }
                }
            }
        }
        true
    } catch (_: Exception) {
        stopLine()
        false
    }

    /** Остановить и получить результат; отменить можно через cancel(). */
    fun stop(): Result? {
        val l = line ?: return null
        sampling = false
        try { readerThread?.join(500) } catch (ignored: InterruptedException) {}
        try { l.stop() } catch (ignored: Exception) {}
        try { l.close() } catch (ignored: Exception) {}
        line = null
        readerThread = null
        val f = outFile ?: return null
        val dur = ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(1)
        val wave = synchronized(amps) { buildWave(amps.toList()) }
        val data = synchronized(pcm) { pcm.toByteArray() }
        return if (writeWav(f, data)) Result(f, dur, wave) else null
    }

    /** Отменить запись и удалить файл. */
    fun cancel() {
        sampling = false
        stopLine()
        outFile?.delete()
        outFile = null
    }

    private fun stopLine() {
        try { line?.stop() } catch (ignored: Exception) {}
        try { line?.close() } catch (ignored: Exception) {}
        line = null
    }

    private fun buildWave(amps: List<Int>): List<Int> {
        // ~36 столбиков 0..100; каждый столбик — усреднённая амплитуда кусочка
        val n = 36
        if (amps.isEmpty()) return List(n) { 4 }
        val step = amps.size.coerceAtLeast(n) / n
        return (0 until n).map { i ->
            val from = i * step
            val to = minOf(amps.size, from + step)
            val slice = if (from >= amps.size) emptyList() else amps.subList(from, to)
            val avg = if (slice.isEmpty()) 0 else slice.sum() / slice.size
            // максимум |s16| 0..32767 → 4..100 для видимости даже тихой речи
            (4 + (avg * 96 / 32767)).coerceIn(4, 100)
        }
    }

    /** Записать WAV-файл: 44-байтный RIFF-заголовок + PCM-данные. */
    private fun writeWav(f: File, data: ByteArray): Boolean = try {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val totalLen = 36 + data.size
            raf.writeBytes("RIFF")
            raf.writeLe32(totalLen)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeLe32(16)                 // размер fmt-чанка
            raf.writeLe16(1)                  // PCM
            raf.writeLe16(1)                  // mono
            raf.writeLe32(16000)              // sample rate
            raf.writeLe32(32000)              // byte rate
            raf.writeLe16(2)                  // block align
            raf.writeLe16(16)                 // bits per sample
            raf.writeBytes("data")
            raf.writeLe32(data.size)
            raf.write(data)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun RandomAccessFile.writeLe16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeLe32(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }
}

/**
 * Одиночный плеер голосовых сообщений — порт Android VoicePlayer.
 * Один плеер на всё приложение: запуск нового голосового останавливает
 * предыдущее. Сбойный формат корректно завершает воспроизведение.
 */
object VoicePlayer {

    @Volatile private var playing = false
    @Volatile private var currentLine: SourceDataLine? = null

    val isPlaying: Boolean get() = playing

    fun play(file: File, onFinished: () -> Unit) {
        stop()
        playing = true
        thread(isDaemon = true, name = "voice-player") {
            var line: SourceDataLine? = null
            try {
                AudioSystem.getAudioInputStream(file).use { ais ->
                    val fmt = ais.format
                    val l = AudioSystem.getSourceDataLine(fmt)
                    line = l
                    currentLine = l
                    l.open(fmt)
                    l.start()
                    val buf = ByteArray(4096) // ~128 мс — быстрая реакция на stop()
                    while (playing) {
                        val n = ais.read(buf)
                        if (n < 0) break
                        l.write(buf, 0, n)
                    }
                    try { l.drain() } catch (ignored: Exception) {}
                }
            } catch (ignored: Exception) {
                // сбойный/неподдерживаемый формат — считаем воспроизведение оконченным
            } finally {
                try { line?.stop() } catch (ignored: Exception) {}
                try { line?.close() } catch (ignored: Exception) {}
                playing = false
                currentLine = null
                onFinished()
            }
        }
    }

    fun stop() {
        playing = false
        // flush сбрасывает буфер — l.write в цикле быстро выйдет по флагу playing
        try { currentLine?.flush() } catch (ignored: Exception) {}
    }
}
