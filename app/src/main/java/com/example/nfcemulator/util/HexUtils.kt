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
        val clean = s.replace(":", "").replace(" ", "").trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // Calculate BCC check byte (XOR sum of 4-byte UID)
    fun calculateBcc(uid: ByteArray): Byte {
        var bcc = 0.toByte()
        for (i in 0..3) {
            bcc = (bcc.toInt() xor uid[i].toInt()).toByte()
        }
        return bcc
    }

    fun calculateBccHex(uidHex: String): String? {
        val clean = uidHex.replace(":", "").replace(" ", "").trim()
        if (clean.length != 8 || !isValidHex(clean)) return null
        return try {
            val bytes = hexStringToByteArray(clean)
            val bcc = calculateBcc(bytes)
            byteArrayToHexString(byteArrayOf(bcc))
        } catch (e: Exception) {
            null
        }
    }

    fun isValidHex(s: String): Boolean {
        val clean = s.replace(":", "").replace(" ", "").trim()
        return clean.isNotEmpty() && clean.all { it in "0123456789abcdefABCDEF" } && (clean.length % 2 == 0)
    }

    fun formatHexWithSpaces(s: String): String {
        val clean = s.replace(":", "").replace(" ", "").trim().uppercase()
        return clean.chunked(2).joinToString(" ")
    }

    // Build Mifare Classic Block 0 payload using 4-byte UID
    fun createBlock0(uidHex: String): ByteArray {
        val uidBytes = hexStringToByteArray(uidHex)
        require(uidBytes.size == 4) { "UID must be exactly 4 bytes (8 hex chars)" }
        
        val block0 = ByteArray(16)
        // 0-3: UID
        System.arraycopy(uidBytes, 0, block0, 0, 4)
        // 4: BCC (BCC = UID[0] ^ UID[1] ^ UID[2] ^ UID[3])
        block0[4] = calculateBcc(uidBytes)
        // 5: SAK (usually 0x08 for Mifare 1k)
        block0[5] = 0x08.toByte()
        // 6-7: ATQA (usually 0x04 0x00)
        block0[6] = 0x04.toByte()
        block0[7] = 0x00.toByte()
        
        // 8-15: Default manufacturer extra info
        val defaultExtra = byteArrayOf(0x1c.toByte(), 0x02.toByte(), 0x20.toByte(), 0x90.toByte(), 0x00.toByte(), 0x2d.toByte(), 0x00.toByte(), 0x10.toByte())
        System.arraycopy(defaultExtra, 0, block0, 8, 8)
        
        return block0
    }

    val DEFAULT_KEYS = listOf(
        "FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "B0B1B2B3B4B5",
        "D3F7D3F7D3F7", "4D3A99C351DD", "1A2B3C4D5E6F", "A0B1C2D3E4F5",
        "123456789ABC", "A1B2C3D4E5F6", "484558414354", "888888888888",
        "112233445566", "777777777777", "999999999999", "FF00FF00FF00",
        "00FF00FF00FF", "AA00BB00CC00", "00AA00BB00CC", "121212121212",
        "343434343434", "565656565656", "787878787878", "9A9A9A9A9A9A",
        "BCBCBCBCBCBC", "DEDEDEDEDEDE", "F0F0F0F0F0F0", "0F0F0F0F0F0F"
    ).map { hexStringToByteArray(it) }

    fun formatBlock0String(uidHex: String): String {
        return try {
            val bytes = createBlock0(uidHex)
            byteArrayToHexString(bytes).chunked(2).joinToString(" ")
        } catch (e: Exception) {
            "Invalid UID"
        }
    }
}
