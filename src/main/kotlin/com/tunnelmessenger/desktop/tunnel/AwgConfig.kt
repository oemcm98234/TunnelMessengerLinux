package com.tunnelmessenger.desktop.tunnel

/**
 * Модель конфига AmneziaWG (формат .conf как в AmneziaVPN/WireGuard).
 * Парсер используется для валидации и показа параметров в UI; сам текст
 * конфига целиком передаётся движку tunnel-core (garble-обфусцированный
 * amneziawg-go v3), который понимает все группы ключей:
 *
 *  • WireGuard: PrivateKey, PublicKey, PresharedKey, Endpoint, AllowedIPs,
 *    PersistentKeepalive, ListenPort, FwMark…
 *  • AWG 1.5 (обфускация): Jc, Jmin, Jmax, S1, S2, H1–H4 (значения или диапазоны)
 *  • AWG 2.0: S3, S4, I1–I5 (obf-цепочки вида <b 0x…> <r 10> <t …>)
 *  • AWG 3.x: HeaderProtectionKey, ContentPaddingAddition, RekeyAfterTime,
 *    RekeyTimeout, RejectAfterTime, KeepaliveTimeout, MaxHandshakeAttempts,
 *    RandomTrailers, DisableCookies, AdvancedSecurity
 */
data class AwgConf(
    val addresses: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val mtu: Int? = null,
    val endpoint: String? = null,
    val allowedIps: List<String> = emptyList(),
    val peerCount: Int = 0,
    /** Все AWG-ключи обфускации (канонические имена → значения). */
    val obfuscation: Map<String, String> = emptyMap(),
) {
    fun summary(): String = buildString {
        addresses.takeIf { it.isNotEmpty() }?.let { append("Адрес: ").append(it.joinToString(", ")).append('\n') }
        dns.takeIf { it.isNotEmpty() }?.let { append("DNS: ").append(it.joinToString(", ")).append('\n') }
        mtu?.let { append("MTU: $it\n") }
        if (obfuscation.isNotEmpty()) {
            append("Обфускация AWG (")
            append(obfuscation.size).append(" ключ")
            append(if (obfuscation.size % 10 == 1 && obfuscation.size % 100 != 11) "" else "ей")
            append("):\n")
            for ((k, v) in obfuscation) {
                val shown = if (v.length > 28) v.take(28) + "…" else v
                append("  ").append(k).append(" = ").append(shown).append('\n')
            }
        }
        endpoint?.let { append("Endpoint: $it\n") }
        allowedIps.takeIf { it.isNotEmpty() }?.let { append("AllowedIPs: ").append(it.joinToString(", ")).append('\n') }
        append("Peer'ов: ").append(peerCount)
    }.trim()
}

class ConfigParseException(message: String) : Exception(message)

object AwgConfigParser {

    /** Канонические имена ключей обфускации (регистронезависимый разбор). */
    private val OBFUSCATION_KEYS = mapOf(
        "jc" to "Jc", "jmin" to "Jmin", "jmax" to "Jmax",
        "s1" to "S1", "s2" to "S2", "s3" to "S3", "s4" to "S4",
        "h1" to "H1", "h2" to "H2", "h3" to "H3", "h4" to "H4",
        "i1" to "I1", "i2" to "I2", "i3" to "I3", "i4" to "I4", "i5" to "I5",
        "headerprotectionkey" to "HeaderProtectionKey",
        "contentpaddingaddition" to "ContentPaddingAddition",
        "rekeyaftertime" to "RekeyAfterTime",
        "rekeytimeout" to "RekeyTimeout",
        "rejectaftertime" to "RejectAfterTime",
        "keepalivetimeout" to "KeepaliveTimeout",
        "maxhandshakeattempts" to "MaxHandshakeAttempts",
        "randomtrailers" to "RandomTrailers",
        "disablecookies" to "DisableCookies",
        "advancedsecurity" to "AdvancedSecurity",
    )

    fun parse(text: String): AwgConf {
        val ifaceLines = mutableListOf<String>()
        val peers = mutableListOf<MutableList<String>>()
        var section = ""
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                val name = line.substringBefore(']').substringAfter('[').trim().lowercase()
                when (name) {
                    "interface" -> { section = "iface"; ifaceLines.clear() }
                    "peer" -> { section = "peer"; peers.add(mutableListOf()) }
                    else -> section = ""
                }
                continue
            }
            if (section == "iface") ifaceLines.add(line)
            else if (section == "peer") peers.lastOrNull()?.add(line)
        }
        if (ifaceLines.none { it.lowercase().substringBefore('=').trim() == "privatekey" }) {
            throw ConfigParseException("в конфиге нет [Interface] PrivateKey")
        }
        if (peers.isEmpty()) throw ConfigParseException("в конфиге нет секции [Peer]")

        fun value(lines: List<String>, key: String): String? =
            lines.firstOrNull { it.lowercase().substringBefore('=').trim() == key }
                ?.substringAfter('=')?.trim()

        val obfuscation = sortedMapOf<String, String>()
        for (line in ifaceLines + peers.flatten()) {
            val key = line.substringBefore('=').trim().lowercase()
            val canonical = OBFUSCATION_KEYS[key] ?: continue
            val v = line.substringAfter('=').trim()
            if (v.isNotEmpty() && !obfuscation.containsKey(canonical)) obfuscation[canonical] = v
        }

        return AwgConf(
            addresses = value(ifaceLines, "address")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            dns = value(ifaceLines, "dns")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            mtu = value(ifaceLines, "mtu")?.toIntOrNull(),
            endpoint = value(peers.first(), "endpoint"),
            allowedIps = value(peers.first(), "allowedips")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            peerCount = peers.size,
            obfuscation = obfuscation,
        )
    }
}
