package com.example.nfcemulator

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.nfcemulator.util.HexUtils

data class ApduLogItem(
    val timestamp: String,
    val direction: String, // "IN" or "OUT"
    val hexData: String,
    val summary: String
)

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

        // Live APDU transaction log stream for UI display
        val apduLogs = androidx.compose.runtime.mutableStateListOf<ApduLogItem>()

        fun clearLogs() {
            apduLogs.clear()
        }

        private fun addLog(direction: String, hexData: String, summary: String) {
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            apduLogs.add(0, ApduLogItem(timeStr, direction, hexData, summary))
            if (apduLogs.size > 50) {
                apduLogs.removeAt(apduLogs.lastIndex)
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) {
            addLog("IN", "NULL", "빈 APDU 명령 수신 실패")
            return STATUS_FAILED
        }

        val hexCommand = HexUtils.byteArrayToHexString(commandApdu)
        Log.d(TAG, "Received APDU: $hexCommand")

        // Check if SELECT APDU command matches
        if (hexCommand.startsWith(SELECT_APDU_HEADER)) {
            val payloadBytes = emulationResponsePayload.toByteArray(Charsets.UTF_8)
            val response = payloadBytes + STATUS_SUCCESS
            val resHex = HexUtils.byteArrayToHexString(response)
            
            addLog("IN", hexCommand, "SELECT AID 요청 수신")
            addLog("OUT", resHex, "페이로드 응답 + [90 00]")
            Log.d(TAG, "Responding with payload: $emulationResponsePayload")
            return response
        }

        val resHex = HexUtils.byteArrayToHexString(STATUS_SUCCESS)
        addLog("IN", hexCommand, "기타 APDU 명령 수신")
        addLog("OUT", resHex, "기본 성공 응답 [90 00]")
        return STATUS_SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = if (reason == DEACTIVATION_LINK_LOSS) "링크 해제 (Link Loss)" else "다른 AID 선택됨"
        addLog("SYS", "-", "HCE 비활성화: $reasonStr")
        Log.d(TAG, "Deactivated with reason: $reason")
    }
}
