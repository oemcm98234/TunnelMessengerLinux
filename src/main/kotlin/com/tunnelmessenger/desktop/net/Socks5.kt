package com.tunnelmessenger.desktop.net

import com.tunnelmessenger.desktop.tunnel.ProxySpec
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * Собственный SOCKS5-клиент (RFC1928 + RFC1929) для трафика мессенджера.
 *
 * Почему НЕ java.net.Proxy + глобальный Authenticator: движок туннеля
 * кэширует учётные данные SOCKS по адресу прокси, поэтому после ротации
 * кредов / перезапуска движка соединения к локальному прокси уходили бы
 * со СТАРЫМИ кредами, и движок отклонял вход («отклонена попытка входа»).
 *
 * Здесь креды читаются из TunnelManager.currentProxy в момент КАЖДОГО
 * соединения — рассинхрон между приложением и движком невозможен в принципе.
 *
 * DNS: имя хоста передаётся движку неразрешённым (ATYP=domain), поэтому
 * резолвинг выполняется внутри туннеля (см. Socks5Dns).
 */
object Socks5 {

    private const val VER: Byte = 5
    private const val METHOD_USERPASS: Byte = 2
    private const val CMD_CONNECT: Byte = 1
    private const val ATYP_DOMAIN: Byte = 3

    /**
     * DNS-заглушка для OkHttpClient: возвращает «фейковый» адрес, сохраняющий
     * имя хоста. Реального соединения по нему не будет — сокет проходит SOCKS5-
     * хендшейк и передаёт имя движку как есть (DNS резолвится через туннель).
     */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(
                InetAddress.getByAddress(
                    hostname,
                    byteArrayOf(198.toByte(), 18.toByte(), 254.toByte(), 254.toByte()),
                ),
            )
    }

    internal fun connectThroughProxy(
        targetHost: String,
        targetPort: Int,
        spec: ProxySpec,
        socket: Socket,
        connectTimeoutMs: Int,
    ) {
        socket.connect(InetSocketAddress("127.0.0.1", spec.port), connectTimeoutMs)
        try {
            socket.soTimeout = connectTimeoutMs
            handshake(socket, targetHost, targetPort, spec)
            // после хендшейка сокет живёт без таймаута чтения
            socket.soTimeout = 0
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun handshake(s: Socket, host: String, port: Int, spec: ProxySpec) {
        val ins = s.getInputStream()
        val outs = s.getOutputStream()

        // --- приветствие: предлагаем только user/pass (движок другого не даёт)
        outs.write(byteArrayOf(VER, 1, METHOD_USERPASS))
        outs.flush()
        val greeting = readExact(ins, 2)
        if (greeting[0] != VER) throw IOException("SOCKS5: неожиданная версия прокси")
        if (greeting[1] != METHOD_USERPASS) {
            throw IOException("SOCKS5: прокси не принял метод авторизации")
        }

        // --- авторизация RFC1929
        val u = spec.user.toByteArray(Charsets.ISO_8859_1)
        val p = spec.pass.toByteArray(Charsets.ISO_8859_1)
        if (u.isEmpty() || u.size > 255 || p.isEmpty() || p.size > 255) {
            throw IOException("SOCKS5: некорректная длина логина/пароля")
        }
        val auth = ByteArray(3 + u.size + p.size)
        auth[0] = 1
        auth[1] = u.size.toByte()
        u.copyInto(auth, 2)
        auth[2 + u.size] = p.size.toByte()
        p.copyInto(auth, 3 + u.size)
        outs.write(auth)
        outs.flush()
        val authReply = readExact(ins, 2)
        if (authReply[0] != 1.toByte() || authReply[1] != 0.toByte()) {
            throw IOException("SOCKS5: прокси отклонил авторизацию")
        }

        // --- CONNECT (ATYP=domain: имя уходит в туннель неразрешённым)
        val hb = host.toByteArray(Charsets.ISO_8859_1)
        if (hb.isEmpty() || hb.size > 255) throw IOException("SOCKS5: некорректное имя хоста")
        val req = ByteArray(7 + hb.size)
        req[0] = VER
        req[1] = CMD_CONNECT
        req[2] = 0
        req[3] = ATYP_DOMAIN
        req[4] = hb.size.toByte()
        hb.copyInto(req, 5)
        req[5 + hb.size] = ((port shr 8) and 0xFF).toByte()
        req[6 + hb.size] = (port and 0xFF).toByte()
        outs.write(req)
        outs.flush()

        val head = readExact(ins, 4)
        if (head[0] != VER) throw IOException("SOCKS5: неожиданный ответ прокси")
        if (head[1] != 0.toByte()) {
            val reason = when (head[1].toInt()) {
                1 -> "сбой прокси"
                2 -> "запрещено правилами"
                3 -> "сеть недоступна"
                4 -> "хост недоступен"
                5 -> "в соединении отказано"
                6 -> "истёк TTL"
                7 -> "команда не поддерживается"
                8 -> "тип адреса не поддерживается"
                else -> "код ${head[1]}"
            }
            throw IOException("SOCKS5: туннель отклонил соединение ($reason)")
        }
        when (head[3].toInt() and 0xFF) {
            1 -> readExact(ins, 4 + 2)
            4 -> readExact(ins, 16 + 2)
            3 -> {
                val l = readExact(ins, 1)[0].toInt() and 0xFF
                readExact(ins, l + 2)
            }
        }
    }

    private fun readExact(ins: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = ins.read(buf, off, n - off)
            if (r < 0) throw IOException("SOCKS5: прокси закрыл соединение")
            off += r
        }
        return buf
    }
}

/**
 * Фабрика сокетов для OkHttpClient: каждый созданный сокет при connect()
 * проходит SOCKS5-хендшейк через локальный прокси движка с АКТУАЛЬНЫМИ кредами
 * (читаются в момент соединения через specProvider).
 */
class Socks5SocketFactory(private val specProvider: () -> ProxySpec?) : SocketFactory() {

    override fun createSocket(): Socket = Socks5Socket(specProvider)

    // Обёртки для полного контракта SocketFactory: connect() у Socks5Socket
    // сам выполняет SOCKS5-хендшейк с актуальными кредами.
    override fun createSocket(host: String?, port: Int): Socket =
        Socks5Socket(specProvider).apply { connect(InetSocketAddress(host ?: "", port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int): Socket =
        Socks5Socket(specProvider).apply { connect(InetSocketAddress(address, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)
}

/**
 * Сокет, у которого connect(целевой_хост) выполняет SOCKS5-хендшейк через
 * локальный прокси. Все прочие методы делегируются внутреннему сокету.
 */
internal class Socks5Socket(private val specProvider: () -> ProxySpec?) : Socket() {

    private val delegate = Socket()

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val spec = specProvider() ?: throw IOException("туннель не подключён")
        val target = endpoint as? InetSocketAddress
            ?: throw IOException("SOCKS5: неподдерживаемый тип адреса")
        // DNS-заглушка (Socks5.dns) кладёт исходное имя в InetAddress.hostName
        val host = target.address?.hostName ?: target.hostString
        if (host.isNullOrBlank()) throw IOException("SOCKS5: пустое имя хоста")
        Socks5.connectThroughProxy(host, target.port, spec, delegate, timeout)
    }

    override fun bind(localAddr: SocketAddress?) = delegate.bind(localAddr)
    override fun close() = delegate.close()
    override fun getInputStream() = delegate.getInputStream()
    override fun getOutputStream() = delegate.getOutputStream()

    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) = delegate.setSendBufferSize(size)
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) = delegate.setReceiveBufferSize(size)
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) = delegate.setTrafficClass(tc)
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) = delegate.setReuseAddress(on)
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun setOOBInline(on: Boolean) = delegate.setOOBInline(on)
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

    override fun toString(): String = delegate.toString()
}
