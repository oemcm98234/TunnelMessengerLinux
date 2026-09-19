package com.tunnelmessenger.desktop.call

import com.tunnelmessenger.desktop.data.Repository
import com.tunnelmessenger.desktop.data.crypto.E2eCrypto
import com.tunnelmessenger.desktop.data.model.Chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Звонки 1:1 (сигналинг + аудио через WS сервера), десктоп-порт.
 *
 * Сигналинг: call_invite / call_accept / call_decline / call_end;
 * пропущенный (собеседник офлайн) сервер отвечает call_missed.
 *
 * Аудио: PCM 16 кГц, моно, 16 бит, кадр 20 мс (640 Б). Каждый кадр —
 * {"i": seq, "d": base64(pcm)} в одном NaCl-боксе (E2E1F, ключ —
 * публичный ключ собеседника), уезжает WS-событием call_frame.
 * Сервер кадры только ретранслирует: содержимое ему недоступно.
 *
 * Плейбек — с джиттер-буфером ~100 мс (5 кадров до старта, до 25 в запасе);
 * при переполнении сбрасываются САМЫЕ СТАРЫЕ кадры, чтобы не отставать
 * от живого разговора. Входящий звонок сопровождается рингтоном (CallRinger),
 * исходящий — гудками, занятый собеседник — сигналом «занято».
 *
 * Замена медиа-API Android на javax.sound.sampled: захват — TargetDataLine,
 * воспроизведение — SourceDataLine (тот же PCM-поток и те же кадры 640 Б).
 * Разрешений на микрофон на JVM не требуется — проверка заменяется
 * попыткой открыть линию (ошибка → звонок продолжится в режиме «слушать»).
 */
object CallManager {

    enum class CallState { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }

    data class CallUi(
        val state: CallState = CallState.IDLE,
        val chatId: Long = 0,
        val peer: String = "",
        val callId: String? = null,
        val muted: Boolean = false,
        // на десктопе вывод всегда идёт в системное аудиоустройство; флаг
        // остаётся для кнопки «динамик» (как в Android, по умолчанию включён)
        val speaker: Boolean = true,
        val startedAtMs: Long = 0,
        val note: String? = null,
    )

    private const val SAMPLE_RATE = 16000
    private const val FRAME_MS = 20
    private const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * 2 // 640
    private const val RING_TIMEOUT_MS = 35_000L
    /* буфер 5 кадров (100 мс) до старта и до 25 кадров (500 мс) в запасе.
       Переполнение выталкивает старейший кадр, а не отказывает новому —
       разговор меньше «рвётся» при нестабильной сети. */
    private const val JITTER_START_FRAMES = 5
    private const val JITTER_MAX_FRAMES = 25

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var spkThread: Thread? = null
    @Volatile private var micLine: TargetDataLine? = null
    @Volatile private var spkLine: SourceDataLine? = null
    private val jitter = ArrayDeque<ByteArray>()
    private val jitterLock = Any()
    @Volatile private var seqOut = 0L
    /** v13 (аудит): последний принятый seq входящего кадра (защита от replay). */
    private var lastSeqIn = 0L
    @Volatile private var mutedFlag = false

    /** Единый PCM-формат: 16 кГц, моно, 16 бит, signed little-endian. */
    private val audioFormat = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)

    /**
     * v8.2: выделенный однопоточный исполнитель для входящих call_frame.
     * Кадры приходят через WsClient.hotSink МИМО общего SharedFlow — тот
     * при 50 событиях/с терял кадры (тишина/пропуски). Один поток = порядок
     * сохраняется, ничего не теряется, WS-ридер и общий коллектор событий
     * не блокируются расшифровкой.
     */
    private val frameExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "call-frames") }

    /** Подключается к WsClient.hotSink при старте WS. */
    val frameSink: (JsonObject) -> Unit = { ev ->
        try {
            frameExecutor.execute { onWsEvent(ev) }
        } catch (ignored: Exception) {
        }
    }

    // ---------------------------------------------------------------- API UI

    /**
     * Точка входа из UI (контракт 2.8). incoming=false — исходящий звонок
     * в личный чат (кнопка «Позвонить»); incoming=true — принять входящий
     * и сразу открыть аудио-тракт.
     */
    fun startAudio(chat: Chat, incoming: Boolean) {
        if (incoming) {
            accept()
        } else {
            startOutgoing(chat.id, chat.displayName)
        }
    }

    /** Позвонить в личный чат. */
    private fun startOutgoing(chatId: Long, peerDisplay: String) {
        val cur = _ui.value
        if (cur.state != CallState.IDLE && cur.state != CallState.ENDED) return
        val callId = UUID.randomUUID().toString()
        _ui.value = CallUi(CallState.OUTGOING, chatId, peerDisplay, callId, speaker = cur.speaker)
        Repository.sendCallEvent(buildJsonObject {
            put("t", "call_invite")
            put("chat_id", chatId)
            put("call_id", callId)
            put("kind", "audio")
        })
        // v8: гудки исходящего вызова, пока собеседник не ответил
        CallRinger.start(CallRinger.Mode.RINGBACK)
        // таймаут ответа: «не отвечает» и аккуратно гасим
        scope.launch {
            delay(RING_TIMEOUT_MS)
            val u = _ui.value
            if (u.state == CallState.OUTGOING && u.callId == callId) {
                Repository.sendCallEvent(buildJsonObject {
                    put("t", "call_end"); put("chat_id", u.chatId); put("call_id", u.callId)
                    put("reason", "timeout")
                })
                endInternal("не отвечает")
            }
        }
    }

    fun accept() {
        val u = _ui.value
        if (u.state != CallState.INCOMING) return
        Repository.sendCallEvent(buildJsonObject {
            put("t", "call_accept"); put("chat_id", u.chatId); put("call_id", u.callId)
        })
        CallRinger.stop()
        _ui.value = u.copy(state = CallState.ACTIVE, startedAtMs = System.currentTimeMillis(), note = null)
        startAudioEngine(u.chatId)
    }

    /** Отклонить входящий. */
    fun decline(reason: String = "declined") {
        val u = _ui.value
        if (u.state != CallState.INCOMING) return
        Repository.sendCallEvent(buildJsonObject {
            put("t", "call_decline"); put("chat_id", u.chatId); put("call_id", u.callId); put("reason", reason)
        })
        CallRinger.stop()
        _ui.value = u.copy(state = CallState.ENDED, note = "отклонено", speaker = u.speaker)
        scheduleIdle()
    }

    /** Положить трубку (исходящий/активный). */
    fun hangup() {
        val u = _ui.value
        if (u.state == CallState.IDLE || u.state == CallState.ENDED) return
        Repository.sendCallEvent(buildJsonObject {
            put("t", "call_end"); put("chat_id", u.chatId); put("call_id", u.callId)
        })
        endInternal("звонок завершён")
    }

    fun toggleMute() {
        val u = _ui.value
        mutedFlag = !u.muted
        _ui.value = u.copy(muted = !u.muted)
    }

    fun toggleSpeaker() {
        // на десктопе переключение не меняет устройство вывода — только флаг в UI
        val u = _ui.value
        _ui.value = u.copy(speaker = !u.speaker)
    }

    // ------------------------------------------------------------ события WS

    /** Диспетчер call_* событий из Repository. */
    fun onWsEvent(ev: JsonObject) {
        val t = ev["t"]?.toString()?.trim('"') ?: return
        val chatId = ev["chat_id"]?.toString()?.trim('"')?.toLongOrNull() ?: return
        val callId = ev["call_id"]?.toString()?.trim('"') ?: ""
        val from = ev["from"]?.toString()?.trim('"') ?: ""
        when (t) {
            "call_invite" -> {
                val cur = _ui.value
                if (cur.state != CallState.IDLE && cur.state != CallState.ENDED) {
                    // занят: сразу вежливо отклоняем
                    Repository.sendCallEvent(buildJsonObject {
                        put("t", "call_decline"); put("chat_id", chatId)
                        put("call_id", callId); put("reason", "busy")
                    })
                    return
                }
                val display = Repository.chatDisplayName(chatId, from)
                _ui.value = CallUi(
                    state = CallState.INCOMING, chatId = chatId, peer = display,
                    callId = callId, speaker = cur.speaker,
                )
                // v8: рингтон — работает и когда окно не в фокусе
                CallRinger.start(CallRinger.Mode.RING)
                scope.launch {
                    delay(RING_TIMEOUT_MS)
                    val u = _ui.value
                    if (u.state == CallState.INCOMING && u.callId == callId) {
                        CallRinger.stop()
                        _ui.value = u.copy(state = CallState.ENDED, note = "пропущенный звонок", speaker = u.speaker)
                        scheduleIdle()
                    }
                }
            }
            "call_accept" -> {
                val u = _ui.value
                if (u.state == CallState.OUTGOING && u.callId == callId) {
                    CallRinger.stop()          // v8: гудки больше не нужны
                    _ui.value = u.copy(state = CallState.ACTIVE, startedAtMs = System.currentTimeMillis(), note = null)
                    startAudioEngine(u.chatId)
                }
            }
            "call_decline" -> {
                val u = _ui.value
                if (u.callId == callId && (u.state == CallState.OUTGOING || u.state == CallState.ACTIVE)) {
                    val reason = ev["reason"]?.toString()?.trim('"') ?: ""
                    if (reason == "busy") {
                        endInternal("собеседник занят")
                        CallRinger.playBusy()          // v8: сигнал «занято»
                    } else {
                        endInternal("звонок отклонён")
                    }
                }
            }
            "call_end" -> {
                val u = _ui.value
                if (u.callId == callId && u.state != CallState.IDLE) {
                    // v13-c: сервер гасит звонок и на ДРУГИХ устройствах этого же
                    // пользователя (answered/declined/ended elsewhere) — показываем почему
                    endInternal(
                        when (ev["reason"]?.toString()?.trim('"')) {
                            "answered_elsewhere" -> "принято на другом устройстве"
                            "declined_elsewhere" -> "отклонено на другом устройстве"
                            "ended_elsewhere" -> "завершено на другом устройстве"
                            else -> "звонок завершён"
                        }
                    )
                }
            }
            "call_missed" -> {
                val u = _ui.value
                if (u.state == CallState.OUTGOING && u.callId == callId) {
                    endInternal("собеседник офлайн")
                }
            }
            "call_frame" -> {
                if (_ui.value.state != CallState.ACTIVE) return
                val data = ev["data"]?.toString()?.trim('"') ?: return
                val json = Repository.decryptCallFrame(from, data) ?: return
                // v13 (аудит): защита от replay — кадр с seq <= последнего принятого
                // отбрасываем (WS доставляет по порядку, повтор возможен только
                // при подмешивании релеем старых кадров). Кадры без seq не фильтруем.
                Regex("\"i\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()?.let { seq ->
                    if (seq > 0) {
                        synchronized(jitterLock) {
                            if (seq <= lastSeqIn) return
                            lastSeqIn = seq
                        }
                    }
                }
                val idx = json.indexOf("\"d\":\"")
                if (idx < 0) return
                val start = idx + 5
                val end = json.indexOf('"', start)
                if (end < 0) return
                val pcm = try {
                    Base64.getDecoder().decode(json.substring(start, end))
                } catch (_: Exception) {
                    return
                }
                synchronized(jitterLock) {
                    if (jitter.size >= JITTER_MAX_FRAMES) {
                        // v8: буфер переполнен — выбрасываем САМЫЙ СТАРЫЙ кадр
                        // (раньше отбрасывался новый, что ломало непрерывность)
                        jitter.removeFirst()
                    }
                    jitter.addLast(pcm)
                }
            }
        }
    }

    // --------------------------------------------------------------- аудио

    /** Открыть аудио-тракт (захват + воспроизведение) активного звонка. */
    private fun startAudioEngine(chatId: Long) {
        if (!running.compareAndSet(false, true)) return
        seqOut = 0
        lastSeqIn = 0L
        synchronized(jitterLock) { jitter.clear() }
        // заранее подтянуть ключ собеседника, чтобы первый кадр не ждал сеть
        scope.launch { Repository.callPeerPkSync(chatId) }

        // МИКРОФОН: читаем крупными кусками и ДОБИРАЕМ до ровного кадра 640 Б
        // (v8.2: частичные read() больше не порождают короткие кадры, из-за
        // которых у собеседника падал поток воспроизведения)
        micThread = Thread {
            var line: TargetDataLine? = null
            try {
                val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
                if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("микрофон недоступен")
                line = AudioSystem.getLine(info) as TargetDataLine
                micLine = line
                line.open(audioFormat, FRAME_BYTES * 20)
                line.start()
                val sk = Repository.myE2eSk()
                val carry = ByteArray(FRAME_BYTES)
                var carryLen = 0
                val buf = ByteArray(FRAME_BYTES * 4)
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val rec = micLine ?: break
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    if (mutedFlag) { carryLen = 0; continue }
                    var off = 0
                    while (off < n && running.get()) {
                        val toCopy = minOf(FRAME_BYTES - carryLen, n - off)
                        System.arraycopy(buf, off, carry, carryLen, toCopy)
                        carryLen += toCopy
                        off += toCopy
                        if (carryLen < FRAME_BYTES) continue
                        carryLen = 0
                        val peerPk = Repository.callPeerPkSync(chatId) ?: continue
                        val payload = """{"i":${seqOut++},"d":"${Base64.getEncoder().encodeToString(carry)}"}"""
                        val env = E2eCrypto.encryptFrame(payload, peerPk, sk) ?: continue
                        val ok = Repository.sendCallEvent(buildJsonObject {
                            put("t", "call_frame"); put("chat_id", chatId)
                            put("call_id", _ui.value.callId); put("seq", seqOut); put("data", env)
                        })
                        if (!ok) return@Thread
                    }
                }
            } catch (e: Exception) {
                println("[CallManager] микрофон: ${e.message} — звонок продолжится в режиме «слушать»")
            } finally {
                runCatching { line?.stop() }
                runCatching { line?.close() }
                micLine = null
            }
        }.also { it.start() }

        // ДИНАМИК: пишем ровно столько байт, сколько в кадре (v8.2: кадры бывают
        // 640 Б от Android, 1024 Б от веба, короткие от частичных read — раньше
        // жёсткий write(..., 640) на коротком кадре ломал поток). Пауза —
        // тишиной 20 мс при пустом джиттер-буфере.
        spkThread = Thread {
            var line: SourceDataLine? = null
            try {
                val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
                if (!AudioSystem.isLineSupported(info)) throw IllegalStateException("аудиовыход недоступен")
                line = AudioSystem.getLine(info) as SourceDataLine
                spkLine = line
                line.open(audioFormat, FRAME_BYTES * 10)
                line.start()
                val silence = ByteArray(FRAME_BYTES)
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val frame: ByteArray? = synchronized(jitterLock) {
                        if (jitter.size >= JITTER_START_FRAMES) jitter.removeFirst() else null
                    }
                    val data = frame ?: silence
                    var written = 0
                    while (written < data.size && running.get()) {
                        val w = try {
                            line.write(data, written, data.size - written)
                        } catch (e: Exception) {
                            println("[CallManager] воспроизведение: ${e.message}")
                            break
                        }
                        if (w <= 0) break
                        written += w
                    }
                }
            } catch (e: Exception) {
                println("[CallManager] динамик: ${e.message} — не роняем звонок")
            } finally {
                runCatching { line?.stop() }
                runCatching { line?.close() }
                spkLine = null
            }
        }.also { it.start() }
    }

    private fun endInternal(note: String) {
        val u = _ui.value
        CallRinger.stop()
        stopAll()
        _ui.value = u.copy(state = CallState.ENDED, note = note)
        scheduleIdle()
    }

    private fun stopAll() {
        running.set(false)
        micThread?.interrupt()
        spkThread?.interrupt()
        micThread = null
        spkThread = null
        // закрытие линий разблокирует read()/write() в аудио-потоках
        runCatching { micLine?.close() }
        runCatching { spkLine?.close() }
        synchronized(jitterLock) { jitter.clear() }
    }

    private fun scheduleIdle() {
        scope.launch {
            delay(1600)
            if (_ui.value.state == CallState.ENDED) _ui.value = CallUi()
        }
    }
}
