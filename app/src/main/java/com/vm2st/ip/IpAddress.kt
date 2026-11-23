package com.vm2st.ip

/** Numeric IP literals only. Never resolves a hostname supplied in an HTTP response. */
class IpAddress private constructor(val value: String, private val bytes: ByteArray) {
    val isV6: Boolean get() = bytes.size == 16
    val isPublic: Boolean get() = isPublicBytes(bytes)
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is IpAddress && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun parse(text: String): IpAddress? {
            val value = text.trim().removePrefix("\uFEFF").trim()
            if (value.isEmpty() || value.length > 45 || value.any { it.code !in 33..126 }) return null
            if (':' !in value) {
                val bytes = parseV4(value) ?: return null
                return IpAddress(formatV4(bytes), bytes)
            }
            val bytes = parseV6(value) ?: return null
            // Canonicalize mapped IPv4 too, so it compares equal to a GeoIP IPv4 response.
            if (isMappedV4(bytes)) {
                val v4 = bytes.copyOfRange(12, 16)
                return IpAddress(formatV4(v4), v4)
            }
            return IpAddress(formatV6(bytes), bytes)
        }

        fun parsePublic(text: String): IpAddress? = parse(text)?.takeIf { it.isPublic }

        private fun parseV4(text: String): ByteArray? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val result = ByteArray(4)
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part[0] == '0')) return null
                val n = part.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                result[i] = n.toByte()
            }
            return result
        }

        private fun parseV6(text: String): ByteArray? {
            if (text.any { it !in "0123456789abcdefABCDEF:." } || ":::" in text) return null
            val compression = text.indexOf("::")
            if (compression >= 0 && text.indexOf("::", compression + 2) >= 0) return null
            fun words(side: String, allowV4: Boolean): List<Int>? {
                if (side.isEmpty()) return emptyList()
                val tokens = side.split(':')
                val output = mutableListOf<Int>()
                for ((index, token) in tokens.withIndex()) {
                    if ('.' in token) {
                        if (!allowV4 || index != tokens.lastIndex) return null
                        val v4 = parseV4(token) ?: return null
                        output += ((v4[0].toInt() and 255) shl 8) or (v4[1].toInt() and 255)
                        output += ((v4[2].toInt() and 255) shl 8) or (v4[3].toInt() and 255)
                    } else {
                        if (token.length !in 1..4) return null
                        output += token.toIntOrNull(16) ?: return null
                    }
                }
                return output
            }
            val result: List<Int>
            if (compression < 0) {
                result = words(text, true) ?: return null
                if (result.size != 8) return null
            } else {
                val left = words(text.substring(0, compression), false) ?: return null
                val right = words(text.substring(compression + 2), true) ?: return null
                val missing = 8 - left.size - right.size
                if (missing < 1) return null
                result = left + List(missing) { 0 } + right
            }
            return ByteArray(16) { i -> if (i % 2 == 0) (result[i / 2] ushr 8).toByte() else result[i / 2].toByte() }
        }

        private fun formatV4(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 255).toString() }

        private fun formatV6(bytes: ByteArray): String {
            val words = IntArray(8) { i -> ((bytes[i * 2].toInt() and 255) shl 8) or (bytes[i * 2 + 1].toInt() and 255) }
            var bestStart = -1
            var bestLength = 1
            var i = 0
            while (i < 8) {
                if (words[i] != 0) { i++; continue }
                val start = i
                while (i < 8 && words[i] == 0) i++
                if (i - start > bestLength) { bestStart = start; bestLength = i - start }
            }
            if (bestStart < 0) return words.joinToString(":") { it.toString(16) }
            val left = words.take(bestStart).joinToString(":") { it.toString(16) }
            val right = words.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
            return "$left::$right"
        }

        private fun isMappedV4(b: ByteArray): Boolean = b.size == 16 &&
            (0..9).all { b[it] == 0.toByte() } && b[10] == (-1).toByte() && b[11] == (-1).toByte()

        /** Conservative non-public/special-use filter; not a replacement for a network firewall. */
        fun isPublicBytes(raw: ByteArray, allowNat64: Boolean = false): Boolean {
            val b = if (isMappedV4(raw)) raw.copyOfRange(12, 16) else raw
            if (b.size == 4) {
                val a = b[0].toInt() and 255
                val c = b[1].toInt() and 255
                val d = b[2].toInt() and 255
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && c in 64..127) || (a == 169 && c == 254) ||
                    (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
                    (a == 192 && c == 0 && (d == 0 || d == 2)) ||
                    (a == 192 && c == 88 && d == 99) ||
                    (a == 198 && c in 18..19) || (a == 198 && c == 51 && d == 100) ||
                    (a == 203 && c == 0 && d == 113))
            }
            if (b.size != 16) return false
            if (allowNat64 && b[0] == 0.toByte() && b[1] == 0x64.toByte() &&
                b[2] == 0xff.toByte() && b[3] == 0x9b.toByte() && (4..11).all { b[it] == 0.toByte() }) {
                return isPublicBytes(b.copyOfRange(12, 16))
            }
            val a = b[0].toInt() and 255
            val c = b[1].toInt() and 255
            if (a !in 0x20..0x3f) return false // Global-unicast 2000::/3 only.
            if (a == 0x20 && c == 0x01 && (b[2].toInt() and 255) < 2) return false // 2001::/23
            if (a == 0x20 && c == 0x01 && b[2] == 0x0d.toByte() && b[3] == 0xb8.toByte()) return false
            if (a == 0x20 && c == 0x02) return false // Deprecated 6to4.
            if (a == 0x3f && c == 0xff && (b[2].toInt() and 255) < 0x10) return false // 3fff::/20 docs.
            return true
        }
    }
}
