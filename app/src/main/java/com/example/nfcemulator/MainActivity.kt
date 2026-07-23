package com.example.nfcemulator

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfcemulator.util.HexUtils
import java.text.SimpleDateFormat
import java.util.*

data class ScannedCardInfo(
    val timestamp: String,
    val uid: String,
    val techList: List<String>
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

    // Global states to share configuration with tag detection thread
    companion object {
        var activeTab = 0
        var targetWriteUid = ""
        var writeMode = 0 // 0: Standard CUID (Gen2), 1: Gen1a Magic Backdoor
        var authKeyHex = "FFFFFFFFFFFF" // 6-byte hex key for Sector 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcAvailable = nfcAdapter != null
        isNfcEnabled = nfcAdapter?.isEnabled == true

        setContent {
            NfcAppTheme {
                NfcEmulatorAppScreen(
                    isNfcAvailable = isNfcAvailable,
                    isNfcEnabled = isNfcEnabled,
                    scannedCards = scannedCards,
                    onOpenNfcSettings = { openNfcSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isNfcEnabled = nfcAdapter?.isEnabled == true
        if (isNfcAdapterActive()) {
            val options = Bundle()
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            nfcAdapter?.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V,
                options
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (isNfcAdapterActive()) {
            nfcAdapter?.disableReaderMode(this)
        }
    }

    private fun isNfcAdapterActive(): Boolean {
        return nfcAdapter != null && nfcAdapter!!.isEnabled
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        val tagIdBytes = tag.id
        val hexUid = HexUtils.byteArrayToHexString(tagIdBytes)
        val techList = tag.techList.map { it.substringAfterLast(".") }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (activeTab == 2) {
            // CUID / Magic Write Mode active
            if (writeMode == 1) {
                handleGen1aMagicWrite(tag)
            } else {
                handleCuidWrite(tag)
            }
        } else {
            // Scan Mode active
            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                techList = techList
            )

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "NFC 카드 스캔 완료: UID $hexUid", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Gen2 CUID Write using raw command injection via transceive to bypass Android API Block 0 write block filters.
    private fun handleCuidWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리) UID를 입력해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            runOnUiThread {
                Toast.makeText(this, "이 태그는 Mifare Classic (CUID) 규격이 아닙니다.", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            mifare.connect()
            mifare.timeout = 2000
            
            // Generate raw 6-byte key from target hex key (default: FFFFFFFFFFFF)
            val keyHexToUse = authKeyHex.replace(":", "").replace(" ", "").trim()
            val authKeyBytes = try {
                if (keyHexToUse.length == 12) {
                    HexUtils.hexStringToByteArray(keyHexToUse)
                } else {
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
                }
            } catch (e: Exception) {
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            }
            
            // Authenticate sector 0 explicitly
            val isAuth = mifare.authenticateSectorWithKeyA(0, authKeyBytes) || 
                         mifare.authenticateSectorWithKeyB(0, authKeyBytes)
            
            if (!isAuth) {
                runOnUiThread {
                    Toast.makeText(this, "섹터 0 인증에 실패했습니다 (인증 키 불일치).", Toast.LENGTH_LONG).show()
                }
                return
            }

            // Create Block 0 manufacturing block with required checksum (BCC)
            val block0Data = HexUtils.createBlock0(uidToWrite)
            
            // Bypassing MifareClassic.writeBlock(0, ...) security filter check via raw transceive
            // Standard MIFARE Classic WRITE command: Part 1 - Send 0xA0 (WRITE command) + 0x00 (Block 0 address)
            val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
            
            try {
                mifare.transceive(writeHeader)
            } catch (e: Exception) {
                // MifareClassic transceive might throw an exception when receiving the ACK/NAK response frame 
                // in standard Android API, but standard tags accept the payload. Let's proceed or log it.
                Log.d("CuidWrite", "Part 1 transceive response (often throws exception in Android): ${e.localizedMessage}")
            }

            // Part 2 - Transmit actual 16-byte Block 0 raw payload
            mifare.transceive(block0Data)

            runOnUiThread {
                Toast.makeText(this, "CUID 태그에 UID($uidToWrite) 복사 완료!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("CuidWrite", "Error writing CUID tag", e)
            runOnUiThread {
                Toast.makeText(this, "쓰기 오류: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } finally {
            try {
                mifare.close()
            } catch (e: Exception) {}
        }
    }

    // Gen1a Magic Card Write using backdoor unlock sequence via NfcA
    private fun handleGen1aMagicWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리) UID를 입력해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            runOnUiThread {
                Toast.makeText(this, "NfcA 규격을 획득할 수 없는 태그입니다.", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            nfcA.connect()
            nfcA.timeout = 2000

            // Magic Command 1: Wake up / Unlock (0x40)
            try {
                val unlockCmd1 = byteArrayOf(0x40.toByte())
                nfcA.transceive(unlockCmd1)
            } catch (e: Exception) {
                Log.d("MagicWrite", "Unlock command 1 exception (expected on some chips)", e)
            }

            // Magic Command 2: Writable Mode Unlock (0x43)
            try {
                val unlockCmd2 = byteArrayOf(0x43.toByte())
                nfcA.transceive(unlockCmd2)
            } catch (e: Exception) {
                Log.d("MagicWrite", "Unlock command 2 exception", e)
            }

            // Step 2: Write Block 0 Command (0xA0 followed by Block Number 0x00)
            val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
            val block0Data = HexUtils.createBlock0(uidToWrite)
            
            // Full command to write Block 0 via raw Mifare protocol
            val fullWriteCmd = writeHeader + block0Data
            nfcA.transceive(fullWriteCmd)

            runOnUiThread {
                Toast.makeText(this, "Gen1a Magic 백도어로 UID($uidToWrite) 복사 성공!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MagicWrite", "Error writing Gen1a Magic tag", e)
            runOnUiThread {
                Toast.makeText(this, "백도어 쓰기 오류: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } finally {
            try {
                nfcA.close()
            } catch (e: Exception) {}
        }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcEmulatorAppScreen(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    scannedCards: List<ScannedCardInfo>,
    onOpenNfcSettings: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var payloadText by remember { mutableStateOf(MyHostApduService.emulationResponsePayload) }
    var targetUidInput by remember { mutableStateOf("") }
    var selectedWriteMode by remember { mutableIntStateOf(0) } // 0: CUID (Gen2), 1: Gen1a Magic
    var authKeyInput by remember { mutableStateOf("FFFFFFFFFFFF") } // 12-char hex key for authentication

    // Sync state to companion object for background NFC thread
    LaunchedEffect(selectedTab) {
        MainActivity.activeTab = selectedTab
    }
    LaunchedEffect(targetUidInput) {
        MainActivity.targetWriteUid = targetUidInput
    }
    LaunchedEffect(selectedWriteMode) {
        MainActivity.writeMode = selectedWriteMode
    }
    LaunchedEffect(authKeyInput) {
        MainActivity.authKeyHex = authKeyInput
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC 스캐너 & 에뮬레이터", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Nfc, contentDescription = "스캔 모드") },
                    label = { Text("카드 스캔") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CreditCard, contentDescription = "HCE 에뮬레이션") },
                    label = { Text("HCE 에뮬") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CopyAll, contentDescription = "CUID 복사 모드") },
                    label = { Text("UID 쓰기") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Status Banner
            if (!isNfcAvailable) {
                StatusCard(
                    message = "이 기기는 NFC를 지원하지 않습니다.",
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            } else if (!isNfcEnabled) {
                StatusCardWithAction(
                    message = "NFC가 비활성화되어 있습니다. 설정에서 활성화해주세요.",
                    actionLabel = "NFC 설정",
                    onAction = onOpenNfcSettings
                )
            } else {
                val statusText = when(selectedTab) {
                    0 -> "NFC가 활성화되었습니다. 카드를 스마트폰 뒷면에 대주세요."
                    1 -> "AID 기반의 가상 스마트카드 에뮬레이션 동작 중..."
                    else -> if (selectedWriteMode == 0) "표준 CUID(Gen2) 쓰기 모드. 카드를 뒷면에 대주세요." else "Gen1a Magic 백도어 쓰기 모드. 카드를 뒷면에 대주세요."
                }
                StatusCard(
                    message = statusText,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedTab) {
                0 -> {
                    // Card Reader View
                    Text(
                        text = "스캔된 카드 목록 (최신순)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (scannedCards.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "NFC 카드를 스마트폰 뒷면에 태그하세요.",
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(scannedCards) { card ->
                                CardInfoItem(card) {
                                    // Click scanned card item to copy UID to writing target
                                    targetUidInput = card.uid
                                    selectedTab = 2 // Switch to write tab
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // HCE Emulation View
                    Text(
                        text = "Host Card Emulation (HCE) 에뮬레이터",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("등록된 AID:", fontWeight = FontWeight.Bold)
                            Text("F0010203040506 (Category: Other)", style = MaterialTheme.typography.bodyMedium)

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = payloadText,
                                onValueChange = {
                                    payloadText = it
                                    MyHostApduService.emulationResponsePayload = it
                                },
                                label = { Text("에뮬레이션 응답 페이로드") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "💡 안내: 외부 NFC 리더기가 SELECT AID(00A40400...) 명령을 전송하면 설정한 페이로드와 SUCCESS status(9000)를 응답합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "ℹ️ 안드로이드 HCE 보안 제약사항",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "안드로이드 OS 표준 HCE API는 보안상 실물 카드 UID 스푸핑을 제한하고 임의/고정 UID를 제공합니다. 본 서비스는 AID 기반 APDU 에뮬레이션 표준으로 구현되었습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                2 -> {
                    // CUID / Magic Writable Block 0 Write View
                    Text(
                        text = "특수 태그 UID 쓰기 (카드 복사)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "소유하고 계신 특수 복제 카드의 유형을 선택하고 기기 뒷면에 카드를 밀착해 주세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Writing Mode Radio Selection
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedWriteMode == 0,
                                        onClick = { selectedWriteMode = 0 }
                                    )
                                    Text("표준 CUID (Gen2)", fontSize = 14.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedWriteMode == 1,
                                        onClick = { selectedWriteMode = 1 }
                                    )
                                    Text("Magic (Gen1a 백도어)", fontSize = 14.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = targetUidInput,
                                onValueChange = { targetUidInput = it },
                                label = { Text("대상 UID (8자리 16진수 입력)") },
                                placeholder = { Text("예: AABBCCDD") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            if (selectedWriteMode == 0) {
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = authKeyInput,
                                    onValueChange = { authKeyInput = it },
                                    label = { Text("섹터 0 인증 키 (12자리 16진수 입력)") },
                                    placeholder = { Text("기본값: FFFFFFFFFFFF") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "⚠️ 주의:\n" +
                                        "1. 표준 CUID (Gen2) 방식은 입력한 인증 키를 통해 Sector 0 권한을 획득합니다. (기본값: FFFFFFFFFFFF)\n" +
                                        "2. Gen1a Magic 방식은 백도어 커맨드로 칩 잠금을 풀고 저수준(NfcA) 쓰기를 실행합니다. 보유하신 복제 태그 사양에 맞게 선택하세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.DarkGray,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(message: String, containerColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun StatusCardWithAction(message: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onAction, modifier = Modifier.padding(start = 8.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun CardInfoItem(card: ScannedCardInfo, onCopyClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NFC Card ID (UID)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(card.timestamp, color = Color.Gray, fontSize = 12.sp)
                    IconButton(
                        onClick = onCopyClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CopyAll,
                            contentDescription = "CUID 쓰기 모드로 복사",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = card.uid,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("지원 기술(Tech): ${card.techList.joinToString(", ")}", fontSize = 12.sp, color = Color.DarkGray)
        }
    }
}

@Composable
fun NfcAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1E88E5),
            primaryContainer = Color(0xFFE3F2FD),
            onPrimaryContainer = Color(0xFF0D47A1),
            secondaryContainer = Color(0xFFFFF3E0),
            onSecondaryContainer = Color(0xFFE65100),
            tertiaryContainer = Color(0xFFFFEBEE)
        ),
        content = content
    )
}
