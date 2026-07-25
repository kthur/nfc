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
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfcemulator.util.HexUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

data class ScannedCardInfo(
    val timestamp: String,
    val uid: String,
    val techList: List<String>,
    val detectedType: String = "분석 중..."
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

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
                    onOpenNfcSettings = { openNfcSettings() },
                    onClearHistory = { scannedCards.clear() }
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
            // Scan Mode active: Perform CUID Gen2 non-destructive test
            val cardType = detectTagType(tag)
            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                techList = techList,
                detectedType = cardType
            )

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "카드 스캔 완료: $cardType", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Non-destructive CUID (Gen2) Card Test
    private fun detectTagType(tag: Tag): String {
        val mifare = MifareClassic.get(tag)
        if (mifare != null) {
            try {
                mifare.connect()
                mifare.timeout = 2000

                val defaultKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
                val isAuth = mifare.authenticateSectorWithKeyA(0, defaultKey) || 
                             mifare.authenticateSectorWithKeyB(0, defaultKey)

                if (isAuth) {
                    val block0 = mifare.readBlock(0)
                    if (block0 != null && block0.size == 16) {
                        // Non-destructive CUID test: write back original block 0 data
                        try {
                            mifare.writeBlock(0, block0)
                            return "CUID (Gen2) - UID 쓰기 지원 태그 ✅"
                        } catch (e: Exception) {
                            return "표준 MIFARE Classic (UID 변경 불가) 🔒"
                        }
                    }
                } else {
                    return "MIFARE Classic (인증 키 불일치) 🔑"
                }
            } catch (e: Exception) {
                Log.d("TagDetect", "Mifare detection error", e)
                return "MIFARE Classic (읽기 전용/미지원)"
            } finally {
                try { mifare.close() } catch (e: Exception) {}
            }
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            return "NfcA 표준 태그 (UID 고정) 🔒"
        }

        return "기타 NFC 태그"
    }

    // CUID (Gen2) Direct Block 0 Write Logic
    private fun handleCuidWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리 Hex) UID를 입력해 주세요. (예: AABBCCDD)", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            runOnUiThread {
                Toast.makeText(this, "이 카드는 Mifare Classic 규격이 아닙니다.", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            mifare.connect()
            mifare.timeout = 3000
            
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
            
            val authA = mifare.authenticateSectorWithKeyA(0, authKeyBytes)
            val authB = if (!authA) mifare.authenticateSectorWithKeyB(0, authKeyBytes) else true
            
            if (!authA && !authB) {
                runOnUiThread {
                    Toast.makeText(this, "섹터 0 인증 실패!\n카드의 키가 FFFFFFFFFFFF가 아니거나 접근 권한이 없습니다.", Toast.LENGTH_LONG).show()
                }
                return
            }

            val block0Data = HexUtils.createBlock0(uidToWrite)
            var writeSuccess = false

            try {
                mifare.writeBlock(0, block0Data)
                writeSuccess = true
                Log.d("CuidWrite", "writeBlock(0) succeeded!")
            } catch (e: Exception) {
                Log.w("CuidWrite", "writeBlock(0) failed, trying raw transceive bypass...", e)
            }

            if (!writeSuccess) {
                try {
                    val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
                    try {
                        mifare.transceive(writeHeader)
                    } catch (e: Exception) {
                        Log.d("CuidWrite", "transceive header ack exception: ${e.localizedMessage}")
                    }
                    mifare.transceive(block0Data)
                    writeSuccess = true
                    Log.d("CuidWrite", "raw transceive write succeeded!")
                } catch (e: Exception) {
                    Log.e("CuidWrite", "raw transceive failed", e)
                }
            }

            if (writeSuccess) {
                runOnUiThread {
                    Toast.makeText(this, "🎉 CUID 태그에 UID($uidToWrite) 복사 성공!", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "쓰기 실패!\n이 태그가 CUID(Gen2)가 아니거나, 표준 Block 0 쓰기를 차단하는 카드입니다.", Toast.LENGTH_LONG).show()
                }
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

    // Gen1a Magic Card Write
    private fun handleGen1aMagicWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리 Hex) UID를 입력해 주세요.", Toast.LENGTH_LONG).show()
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

            try {
                val unlockCmd1 = byteArrayOf(0x40.toByte())
                nfcA.transceive(unlockCmd1)
            } catch (e: Exception) {
                Log.d("MagicWrite", "Unlock 0x40 exception", e)
            }

            try {
                val unlockCmd2 = byteArrayOf(0x43.toByte())
                nfcA.transceive(unlockCmd2)
            } catch (e: Exception) {
                Log.d("MagicWrite", "Unlock 0x43 exception", e)
            }

            val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
            val block0Data = HexUtils.createBlock0(uidToWrite)
            val fullWriteCmd = writeHeader + block0Data
            
            nfcA.transceive(fullWriteCmd)

            runOnUiThread {
                Toast.makeText(this, "🎉 Gen1a Magic 백도어로 UID($uidToWrite) 복사 성공!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MagicWrite", "Error writing Gen1a Magic tag", e)
            runOnUiThread {
                Toast.makeText(this, "Gen1a 쓰기 실패!\n스마트폰 NFC 칩셋은 Gen1a 7-bit 백도어 프레임 송신을 지원하지 않습니다.\nArduino+RC522 환경에서 진행해야 합니다.", Toast.LENGTH_LONG).show()
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
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var payloadText by remember { mutableStateOf(MyHostApduService.emulationResponsePayload) }
    var targetUidInput by remember { mutableStateOf("") }
    var selectedWriteMode by remember { mutableIntStateOf(0) } // 0: CUID (Gen2), 1: Gen1a Magic
    var authKeyInput by remember { mutableStateOf("FFFFFFFFFFFF") }

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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("NFC Commander", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("UID Cloner & HCE Emulator", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0 && scannedCards.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "기록 삭제")
                        }
                    }
                    IconButton(onClick = onOpenNfcSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "NFC 설정")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Radar, contentDescription = "스캔 모드") },
                    label = { Text("카드 스캔", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CopyAll, contentDescription = "UID 복사") },
                    label = { Text("UID 쓰기", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CreditCard, contentDescription = "HCE 에뮬") },
                    label = { Text("HCE 에뮬", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Hero Status Bar
            NfcStatusHeroBar(
                isNfcAvailable = isNfcAvailable,
                isNfcEnabled = isNfcEnabled,
                activeTab = selectedTab,
                selectedWriteMode = selectedWriteMode,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (selectedTab) {
                    0 -> ScanTabContent(
                        scannedCards = scannedCards,
                        onSelectUidForWrite = { uid ->
                            targetUidInput = uid
                            selectedTab = 2
                        }
                    )
                    2 -> WriteTabContent(
                        targetUidInput = targetUidInput,
                        onTargetUidChange = { targetUidInput = it },
                        selectedWriteMode = selectedWriteMode,
                        onWriteModeChange = { selectedWriteMode = it },
                        authKeyInput = authKeyInput,
                        onAuthKeyChange = { authKeyInput = it },
                        recentScannedCards = scannedCards
                    )
                    1 -> HceTabContent(
                        payloadText = payloadText,
                        onPayloadTextChange = {
                            payloadText = it
                            MyHostApduService.emulationResponsePayload = it
                        }
                    )
                }
            }
        }
    }
}

// Sleek Status Hero Bar
@Composable
fun NfcStatusHeroBar(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    activeTab: Int,
    selectedWriteMode: Int,
    onOpenNfcSettings: () -> Unit
) {
    val (statusTitle, statusDesc, accentColor) = when {
        !isNfcAvailable -> Triple("NFC 미지원 기기", "이 기기는 NFC 하드웨어를 장착하고 있지 않습니다.", MaterialTheme.colorScheme.error)
        !isNfcEnabled -> Triple("NFC 비활성화됨", "터치하여 안드로이드 설정에서 NFC를 켜주세요.", MaterialTheme.colorScheme.tertiary)
        activeTab == 0 -> Triple("카드 감지 준비 완료", "NFC 카드를 스마트폰 뒷면에 밀착해 주세요.", MaterialTheme.colorScheme.primary)
        activeTab == 2 -> if (selectedWriteMode == 0) 
            Triple("CUID(Gen2) 쓰기 대기 중", "CUID 복제 카드를 뒷면에 밀착하면 자동 덮어쓰기됩니다.", Color(0xFF00A86B))
        else 
            Triple("Gen1a 백도어 모드 (아두이노 권장)", "7-bit 백도어 전송은 아두이노 RC522 환경을 권장합니다.", Color(0xFFE65100))
        else -> Triple("HCE 스마트카드 에뮬레이터 가동 중", "외부 NFC 리더기의 SELECT AID 요청에 대기합니다.", MaterialTheme.colorScheme.secondary)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isNfcEnabled) accentColor else Color.Gray)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(statusTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(statusDesc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isNfcEnabled && isNfcAvailable) {
                TextButton(onClick = onOpenNfcSettings) {
                    Text("설정 열기", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Tab 1: Scan View
@Composable
fun ScanTabContent(
    scannedCards: List<ScannedCardInfo>,
    onSelectUidForWrite: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("스캔 내역 및 CUID 판별", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${scannedCards.size}건 저장됨", fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (scannedCards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "스마트폰 뒷면에 NFC 카드를 태그하세요.",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "UID 추출 및 CUID(Gen2) 호환성을 비파괴 검사로 판단합니다.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(scannedCards) { card ->
                    ModernCardInfoItem(card = card, onCopyClick = { onSelectUidForWrite(card.uid) })
                }
            }
        }
    }
}

// Tab 2: UID Write View
@Composable
fun WriteTabContent(
    targetUidInput: String,
    onTargetUidChange: (String) -> Unit,
    selectedWriteMode: Int,
    onWriteModeChange: (Int) -> Unit,
    authKeyInput: String,
    onAuthKeyChange: (String) -> Unit,
    recentScannedCards: List<ScannedCardInfo>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("특수 태그 UID 쓰기 (복제 카드 기록)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        // Step 1: Mode Selector Filter
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Step 1. 복제 카드 유형 선택", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SelectableChip(
                            title = "CUID (Gen2)",
                            subtitle = "스마트폰 직접 쓰기 추천",
                            isSelected = selectedWriteMode == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { onWriteModeChange(0) }
                        )
                        SelectableChip(
                            title = "Gen1a (Magic)",
                            subtitle = "아두이노 / PM3 전용",
                            isSelected = selectedWriteMode == 1,
                            modifier = Modifier.weight(1f),
                            onClick = { onWriteModeChange(1) }
                        )
                    }
                }
            }
        }

        // Step 2: Target UID Input & Helper Controls
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Step 2. 대상 UID 설정 (4바이트 Hex)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = targetUidInput,
                        onValueChange = onTargetUidChange,
                        label = { Text("대상 UID (8자리 16진수)") },
                        placeholder = { Text("예: AABBCCDD") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (targetUidInput.isNotEmpty()) {
                                IconButton(onClick = { onTargetUidChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "지우기")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                val randomBytes = ByteArray(4)
                                Random.nextBytes(randomBytes)
                                onTargetUidChange(HexUtils.byteArrayToHexString(randomBytes))
                            },
                            label = { Text("랜덤 UID 생성 🎲") }
                        )

                        if (recentScannedCards.isNotEmpty()) {
                            AssistChip(
                                onClick = { onTargetUidChange(recentScannedCards.first().uid) },
                                label = { Text("최근 스캔 UID 📋") }
                            )
                        }

                        AssistChip(
                            onClick = {
                                val cleaned = targetUidInput.replace(":", "").replace(" ", "").uppercase()
                                onTargetUidChange(cleaned)
                            },
                            label = { Text("Hex 정제 ✨") }
                        )
                    }
                }
            }
        }

        // Step 3: Auth Key Setting (CUID Mode)
        if (selectedWriteMode == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Step 3. Sector 0 인증 키 설정", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = authKeyInput,
                            onValueChange = onAuthKeyChange,
                            label = { Text("6바이트 Key (12자리 Hex)") },
                            placeholder = { Text("기본값: FFFFFFFFFFFF") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Preset Key Chips
                        Text("빠른 키 입력:", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = authKeyInput == "FFFFFFFFFFFF",
                                onClick = { onAuthKeyChange("FFFFFFFFFFFF") },
                                label = { Text("FFFFFFFFFFFF (기본값)") }
                            )
                            FilterChip(
                                selected = authKeyInput == "000000000000",
                                onClick = { onAuthKeyChange("000000000000") },
                                label = { Text("000000000000") }
                            )
                        }
                    }
                }
            }
        }

        // Step 4: Guidance Footer Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedWriteMode == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (selectedWriteMode == 0) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (selectedWriteMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (selectedWriteMode == 0) "쓰기 준비 완료" else "Gen1a 백도어 주의사항",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (selectedWriteMode == 0) 
                                "준비된 CUID(Gen2) 복제 태그를 스마트폰 뒷면에 밀착하면 설정한 UID($targetUidInput)가 즉시 덮어쓰여집니다." 
                            else 
                                "Gen1a 7-bit 백도어는 안드로이드 OS NFC 스택 제약으로 아두이노+RC522 툴 환경을 사용해야 합니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// Tab 3: HCE Emulation View
@Composable
fun HceTabContent(
    payloadText: String,
    onPayloadTextChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Host Card Emulation (HCE) 에뮬레이터", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // Digital Card Graphic
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Nfc, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("VIRTUAL SMART CARD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Surface(
                            color = Color(0xFF00A86B).copy(alpha = 0.2f),
                            shape = CircleShape
                        ) {
                            Text(
                                "ACTIVE HCE",
                                color = Color(0xFF00FF87),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Column {
                        Text("REGISTERED AID", color = Color.Gray, fontSize = 10.sp)
                        Text("F0010203040506", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("PAYLOAD OVERVIEW", color = Color.Gray, fontSize = 10.sp)
                            Text(payloadText.take(20) + if (payloadText.length > 20) "..." else "", color = Color.LightGray, fontSize = 12.sp)
                        }
                        Text("STATUS: 90 00", color = Color(0xFF00FF87), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Payload Input Control
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("APDU 응답 페이로드 설정", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = payloadText,
                    onValueChange = onPayloadTextChange,
                    label = { Text("응답 페이로드 (String / Hex)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text("빠른 프리셋 선택:", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onPayloadTextChange("Hello NFC Emulator!") },
                        label = { Text("Hello NFC") }
                    )
                    AssistChip(
                        onClick = { onPayloadTextChange("STUDENT_ID_994821") },
                        label = { Text("학생증 ID") }
                    )
                }
            }
        }

        // Security Notice Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ℹ️ HCE UID 스푸핑 보안 안내", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "안드로이드 OS HCE API는 보안상 물리적 NFC 태그의 하드웨어 UID 변경/스푸핑을 제한합니다. 본 모듈은 AID 기반의 APDU 애플리케이션 에뮬레이션 규격을 준수합니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

// Selectable Segment Chip Widget
@Composable
fun SelectableChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 10.sp, color = Color.Gray)
        }
    }
}

// Modern Card Item UI Widget
@Composable
fun ModernCardInfoItem(card: ScannedCardInfo, onCopyClick: () -> Unit) {
    val isCuid = card.detectedType.contains("Gen2") || card.detectedType.contains("CUID")
    val badgeColor = if (isCuid) Color(0xFF00A86B) else Color(0xFFD32F2F)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("NFC CARD UID", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(card.timestamp, color = Color.Gray, fontSize = 12.sp)
                    FilledTonalButton(
                        onClick = onCopyClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("UID 쓰기", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Formatted UID Display (Monospace formatted with colons)
            val formattedUid = card.uid.chunked(2).joinToString(" : ")
            Text(
                text = formattedUid,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Detected CUID Card Type Badge
            Surface(
                color = badgeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isCuid) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = card.detectedType,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.techList.forEach { tech ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(tech, fontSize = 10.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

// Material 3 Color Theme Definition
@Composable
fun NfcAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0052CC),
            primaryContainer = Color(0xFFE6F0FF),
            onPrimaryContainer = Color(0xFF002966),
            secondary = Color(0xFF00A86B),
            secondaryContainer = Color(0xFFE6FFFA),
            onSecondaryContainer = Color(0xFF004D31),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF4F5F7),
            onSurfaceVariant = Color(0xFF42526E),
            background = Color(0xFFFAFBFC)
        ),
        content = content
    )
}
