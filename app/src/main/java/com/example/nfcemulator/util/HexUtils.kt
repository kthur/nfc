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
        
        // 8-15: Default manufacturer extra info (commonly 0x1c, 0x02, 0x20, 0x90, 0x00, 0x2d, 0x00, 0x10)
        val defaultExtra = byteArrayOf(0x1c.toByte(), 0x02.toByte(), 0x20.toByte(), 0x90.toByte(), 0x00.toByte(), 0x2d.toByte(), 0x00.toByte(), 0x10.toByte())
        System.arraycopy(defaultExtra, 0, block0, 8, 8)
        
        return block0
    }
}
