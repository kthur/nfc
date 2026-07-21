package com.example.nfcemulator

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.nfcemulator.util.HexUtils

class MyHostApduService : HostApduService() {

    companion object {
        private const val TAG = "MyHostApduService"

        // ISO 7816-4 SELECT APDU Header: 00 A4 04 00
        private const val SELECT_APDU_HEADER = "00A40400"
        
        // Status word: OK (90 00)
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        
        // Status word: Command not allowed / failed (6F 00)
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        // Custom Payload to respond when SELECT AID is received
        var emulationResponsePayload: String = "Hello from Android HCE Emulation!"
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED

        val hexCommand = HexUtils.byteArrayToHexString(commandApdu)
        Log.d(TAG, "Received APDU: $hexCommand")

        // Check if SELECT APDU command matches
        if (hexCommand.startsWith(SELECT_APDU_HEADER)) {
            val payloadBytes = emulationResponsePayload.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "Responding with payload: $emulationResponsePayload")
            return payloadBytes + STATUS_SUCCESS
        }

        // Return SUCCESS status for other commands
        return STATUS_SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated with reason: $reason")
    }
}
