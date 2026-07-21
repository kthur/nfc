package com.example.nfcemulator.util

object HexUtils {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun byteArrayToHexString(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return ""
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(HEX_CHARS[i shr 4])
            result.append(HEX_CHARS[i and 0x0F])
        }
        return result.toString()
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
