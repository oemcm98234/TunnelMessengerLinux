package com.tunnelmessenger.desktop.data.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Формат файлов TME1 (docs/E2E.md) — потоковый, память не зависит от размера:
 *
 *   байты 0..3   : ASCII "TME1"
 *   байты 4..35  : nonce_base — 32 случайных байта
 *   далее чанки  : AES-256-GCM(key,
 *                    nonce = SHA-256(nonce_base || le64(i))[:12],
 *                    aad   = le64(i),
 *                    открытый текст <= 65536 байт) + 16Б тег
 */
object Tme1 {

    const val CHUNK = 65536
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val HEADER = 4 + 32

    class Tme1Exception(message: String) : Exception(message)

    private fun le64(n: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((n shr (8 * i)) and 0xFF).toByte()
        return b
    }

    private fun chunkNonce(nonceBase: ByteArray, i: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(nonceBase)
        md.update(le64(i))
        return md.digest().copyOf(12)
    }

    private fun cipher(key: ByteArray, nonce: ByteArray, aad: ByteArray, encrypt: Boolean): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        c.updateAAD(aad)
        return c
    }

    fun encryptedSize(plainSize: Long): Long {
        val chunks = if (plainSize == 0L) 1L else (plainSize + CHUNK - 1) / CHUNK
        return HEADER + plainSize + 16L * chunks
    }

    /**
     * Потоковое шифрование: читает source, пишет TME1 в out.
     * Память константна (блок 64 КиБ).
     */
    fun encryptStream(source: InputStream, keyB64: String, out: OutputStream) {
        val key = E2eCrypto.unb64(keyB64) ?: throw Tme1Exception("ключ файла повреждён")
        val nonceBase = E2eCrypto.randomBytes(32)
        out.write(MAGIC)
        out.write(nonceBase)
        val buf = ByteArray(CHUNK)
        var i = 0L
        while (true) {
            val read = readFully(source, buf)
            if (read <= 0 && i > 0) break
            val c = cipher(key, chunkNonce(nonceBase, i), le64(i), encrypt = true)
            // doFinal по каждому чанку даёт шифротекст + 16Б тег
            val enc = if (read > 0) c.doFinal(buf, 0, read) else c.doFinal(byteArrayOf())
            out.write(enc)
            i++
            if (read <= 0) break
        }
        out.flush()
    }

    /**
     * Потоковое расшифрование TME1 из source → открытые байты в out.
     * plainSize — известный из E2E-меты размер, служит проверкой целостности.
     */
    fun decryptStream(source: InputStream, keyB64: String, plainSize: Long, out: OutputStream) {
        val key = E2eCrypto.unb64(keyB64) ?: throw Tme1Exception("ключ файла повреждён")
        val header = ByteArray(HEADER)
        if (readFully(source, header) != HEADER) throw Tme1Exception("файл оборван")
        if (!header.copyOf(4).contentEquals(MAGIC)) throw Tme1Exception("это не зашифрованный TME1-файл")
        val nonceBase = header.copyOfRange(4, HEADER)
        val buf = ByteArray(CHUNK + 16)
        var i = 0L
        var remaining = plainSize
        while (remaining > 0) {
            val need = minOf(CHUNK.toLong(), remaining).toInt() + 16
            val read = readFully(source, buf, need)
            if (read < need) throw Tme1Exception("файл оборван")
            val c = cipher(key, chunkNonce(nonceBase, i), le64(i), encrypt = false)
            val pt = c.doFinal(buf, 0, read)
            out.write(pt)
            remaining -= pt.size
            i++
        }
        if (remaining != 0L) throw Tme1Exception("размер не совпал")
        out.flush()
    }

    /** Зашифровать буфер целиком (P2P-файлы, v1 — в памяти). */
    fun encryptBytes(data: ByteArray, keyB64: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(encryptedSize(data.size.toLong()).toInt())
        encryptStream(java.io.ByteArrayInputStream(data), keyB64, out)
        return out.toByteArray()
    }

    /** Расшифровать буфер целиком (P2P-файлы). */
    fun decryptBytes(blob: ByteArray, keyB64: String, plainSize: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        decryptStream(java.io.ByteArrayInputStream(blob), keyB64, plainSize, out)
        return out.toByteArray()
    }

    private fun readFully(src: InputStream, buf: ByteArray): Int = readFully(src, buf, buf.size)

    private fun readFully(src: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = src.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
