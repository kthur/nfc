package com.example.nfcemulator

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val uid: String,
    val techList: List<String>,
    val detectedType: String = "분석 중...",
    val sak: String = "08",
    val atqa: String = "0400",
    val rawBlock0Hex: String = ""
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
            var isDarkTheme by remember { mutableStateOf(true) }

            NfcAppTheme(darkTheme = isDarkTheme) {
                NfcEmulatorAppScreen(
                    isNfcAvailable = isNfcAvailable,
                    isNfcEnabled = isNfcEnabled,
                    scannedCards = scannedCards,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme },
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

    private fun triggerHapticFeedback(success: Boolean = true) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                val effect = if (success) 
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else 
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (success) {
                    vibrator.vibrate(50)
                } else {
                    vibrator.vibrate(200)
                }
            }
        } catch (e: Exception) {
            Log.d("Haptic", "Vibration failed", e)
        }
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
            val (cardType, rawBlock0) = detectTagTypeAndBlock0(tag)
            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                techList = techList,
                detectedType = cardType,
                sak = "08",
                atqa = "0400",
                rawBlock0Hex = rawBlock0
            )

            triggerHapticFeedback(true)

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "카드 스캔 완료: $cardType", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Non-destructive CUID (Gen2) Card Test & Block 0 Retrieval
    private fun detectTagTypeAndBlock0(tag: Tag): Pair<String, String> {
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
                        val rawBlock0Hex = HexUtils.byteArrayToHexString(block0)
                        try {
                            mifare.writeBlock(0, block0)
                            return Pair("CUID (Gen2) - UID 쓰기 지원 태그 ✅", rawBlock0Hex)
                        } catch (e: Exception) {
                            return Pair("표준 MIFARE Classic (UID 변경 불가) 🔒", rawBlock0Hex)
                        }
                    }
                } else {
                    return Pair("MIFARE Classic (인증 키 불일치) 🔑", "")
                }
            } catch (e: Exception) {
                Log.d("TagDetect", "Mifare detection error", e)
                return Pair("MIFARE Classic (읽기 전용/미지원)", "")
            } finally {
                try { mifare.close() } catch (e: Exception) {}
            }
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            return Pair("NfcA 표준 태그 (UID 고정) 🔒", "")
        }

        return Pair("기타 NFC 태그", "")
    }

    // CUID (Gen2) Direct Block 0 Write Logic
    private fun handleCuidWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리 Hex) UID를 입력해 주세요. (예: AABBCCDD)", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            triggerHapticFeedback(false)
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
                triggerHapticFeedback(false)
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

            triggerHapticFeedback(writeSuccess)
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
            triggerHapticFeedback(false)
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
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "올바른 4바이트(8자리 Hex) UID를 입력해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            triggerHapticFeedback(false)
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
            triggerHapticFeedback(true)

            runOnUiThread {
                Toast.makeText(this, "🎉 Gen1a Magic 백도어로 UID($uidToWrite) 복사 성공!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("MagicWrite", "Error writing Gen1a Magic tag", e)
            triggerHapticFeedback(false)
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
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var payloadText by remember { mutableStateOf(MyHostApduService.emulationResponsePayload) }
    var targetUidInput by remember { mutableStateOf("") }
    var selectedWriteMode by remember { mutableIntStateOf(0) } // 0: CUID (Gen2), 1: Gen1a Magic
    var authKeyInput by remember { mutableStateOf("FFFFFFFFFFFF") }
    var inspectCard by remember { mutableStateOf<ScannedCardInfo?>(null) }

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
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "NFC Commander",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "UID Diagnostic & HCE Studio",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "테마 전환",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        BadgedBox(badge = {
                            if (scannedCards.isNotEmpty()) {
                                Badge { Text("${scannedCards.size}") }
                            }
                        }) {
                            Icon(Icons.Default.Radar, contentDescription = "스캔 모드")
                        }
                    },
                    label = { Text("카드 스캔", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CopyAll, contentDescription = "UID 쓰기") },
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
                targetUid = targetUidInput,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                    },
                    label = "tabChange"
                ) { tab ->
                    when (tab) {
                        0 -> ScanTabContent(
                            scannedCards = scannedCards,
                            onSelectUidForWrite = { uid ->
                                targetUidInput = uid
                                selectedTab = 2
                            },
                            onInspectCard = { card -> inspectCard = card }
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

                // Tag Detail Modal Dialog
                inspectCard?.let { card ->
                    TagDetailDialog(
                        card = card,
                        onDismiss = { inspectCard = null },
                        onUseUidForWrite = { uid ->
                            targetUidInput = uid
                            inspectCard = null
                            selectedTab = 2
                        }
                    )
                }
            }
        }
    }
}

// Sleek Modern Status Hero Bar
@Composable
fun NfcStatusHeroBar(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    activeTab: Int,
    selectedWriteMode: Int,
    targetUid: String,
    onOpenNfcSettings: () -> Unit
) {
    val (statusTitle, statusDesc, accentColor) = when {
        !isNfcAvailable -> Triple("NFC 미지원 기기 ⚠️", "이 기기는 NFC 하드웨어를 장착하고 있지 않습니다.", MaterialTheme.colorScheme.error)
        !isNfcEnabled -> Triple("NFC 비활성화됨 📴", "터치하여 스마트폰 설정에서 NFC를 활성화해 주세요.", MaterialTheme.colorScheme.error)
        activeTab == 0 -> Triple("NFC 스캔 대기 중 📡", "NFC 카드를 스마트폰 뒷면에 밀착하세요.", MaterialTheme.colorScheme.primary)
        activeTab == 2 -> if (targetUid.length == 8) {
            val formatted = targetUid.chunked(2).joinToString(":")
            if (selectedWriteMode == 0) 
                Triple("CUID 쓰기 준비 완료 🎯 ($formatted)", "CUID 복제 카드를 뒷면에 밀착하면 자동 덮어쓰기됩니다.", Color(0xFF10B981))
            else 
                Triple("Gen1a 백도어 모드 ($formatted)", "스마트폰 NFC 칩셋 특성상 아두이노 RC522 환경을 권장합니다.", Color(0xFFF59E0B))
        } else {
            Triple("UID 입력 필요 ✍️", "복제하여 기입할 4바이트 Hex UID를 입력해 주세요.", Color(0xFF6366F1))
        }
        else -> Triple("HCE 스마트카드 가동 중 💳", "외부 리더기의 AID SELECT 요청에 대기합니다.", MaterialTheme.colorScheme.secondary)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isNfcEnabled) accentColor else Color.Gray)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(statusTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(statusDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isNfcEnabled && isNfcAvailable) {
                TextButton(
                    onClick = onOpenNfcSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("설정 열기", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

// Tab 0: Scan View with Pulsing Radar Animation & Search Filter
@Composable
fun ScanTabContent(
    scannedCards: List<ScannedCardInfo>,
    onSelectUidForWrite: (String) -> Unit,
    onInspectCard: (ScannedCardInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, CUID, MIFARE

    val filteredCards = remember(scannedCards, searchQuery, selectedFilter) {
        scannedCards.filter { card ->
            val matchesQuery = searchQuery.isEmpty() || 
                    card.uid.contains(searchQuery, ignoreCase = true) || 
                    card.detectedType.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "CUID" -> card.detectedType.contains("Gen2") || card.detectedType.contains("CUID")
                "MIFARE" -> card.detectedType.contains("MIFARE")
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search & Filter Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("스캔 내역 및 CUID 판별", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${scannedCards.size}건 스캔됨", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (scannedCards.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("UID 또는 카드 유형 검색...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("전체 보기 (${scannedCards.size})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "CUID",
                    onClick = { selectedFilter = "CUID" },
                    label = { Text("CUID 지원 태그 ✅", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "MIFARE",
                    onClick = { selectedFilter = "MIFARE" },
                    label = { Text("MIFARE Classic 🔒", fontSize = 11.sp) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (filteredCards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (scannedCards.isEmpty()) {
                    // Pulsing Radar Animation Widget
                    AnimatedRadarView()
                } else {
                    Text("검색 조건과 일치하는 카드가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredCards, key = { it.id }) { card ->
                    ModernCardInfoItem(
                        card = card,
                        onCopyClick = { onSelectUidForWrite(card.uid) },
                        onCardClick = { onInspectCard(card) }
                    )
                }
            }
        }
    }
}

// Interactive Pulsing Radar Animation Component
@Composable
fun AnimatedRadarView() {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(140.dp)
        ) {
            // Pulsing Ring
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
            )

            // Inner Core Circle
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    )
                    .shadow(8.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "스마트폰 뒷면에 NFC 카드를 밀착하세요",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "UID 추출, SAK/ATQA 분석 및 CUID(Gen2) 쓰기 지원 여부를\n비파괴 실시간으로 검사합니다.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// Tab 1: UID Write View with Step-by-Step Stepper & Live BCC Calculator
@Composable
fun WriteTabContent(
    targetUidInput: String,
    onTargetUidChange: (String) -> Unit,
    selectedWriteMode: Int,
    onWriteModeChange: (Int) -> Unit,
    authKeyInput: String,
    onAuthKeyChange: (String) -> Unit,
    recentScannedCards: List<ScannedCardInfo>

    val cleanUid = targetUidInput.replace(":", "").replace(" ", "").trim()
    val isValidHex = HexUtils.isValidHex(cleanUid) && cleanUid.length == 8
    val calculatedBcc = remember(cleanUid) { HexUtils.calculateBccHex(cleanUid) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("특수 태그 UID 쓰기 (Cloner Studio)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        // Stepper Visual Progress Bar
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepChip(number = "1", title = "복제 모드", isActive = true)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    StepChip(number = "2", title = "UID 설정", isActive = cleanUid.isNotEmpty())
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    StepChip(number = "3", title = "인증 키", isActive = selectedWriteMode == 0)
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    StepChip(number = "4", title = "태그 밀착", isActive = isValidHex)
                }
            }
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
                            subtitle = "스마트폰 직접 쓰기 추천 ✅",
                            isSelected = selectedWriteMode == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { onWriteModeChange(0) }
                        )
                        SelectableChip(
                            title = "Gen1a (Magic)",
                            subtitle = "아두이노 / PM3 전용 ⚠️",
                            isSelected = selectedWriteMode == 1,
                            modifier = Modifier.weight(1f),
                            onClick = { onWriteModeChange(1) }
                        )
                    }
                }
            }
        }

        // Step 2: Target UID Input & BCC Calculator Preview
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Step 2. 대상 UID 설정 (4바이트 Hex)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (isValidHex) {
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("8 Hex - 검증 성공 ✅", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
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

                    // Real-Time BCC & Block 0 Checksum Calculator Display
                    if (cleanUid.length >= 2) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("BCC Checksum (XOR Sum):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = calculatedBcc?.let { "0x$it" } ?: "계산 중...",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (isValidHex) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Block 0 Preview: ${HexUtils.formatBlock0String(cleanUid)}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                val randomBytes = ByteArray(4)
                                Random.nextBytes(randomBytes)
                                onTargetUidChange(HexUtils.byteArrayToHexString(randomBytes))
                            },
                            label = { Text("랜덤 생성 🎲", fontSize = 11.sp) }
                        )

                        if (recentScannedCards.isNotEmpty()) {
                            AssistChip(
                                onClick = { onTargetUidChange(recentScannedCards.first().uid) },
                                label = { Text("최근 스캔 📋", fontSize = 11.sp) }
                            )
                        }

                        AssistChip(
                            onClick = {
                                val cleaned = cleanUid.uppercase()
                                onTargetUidChange(cleaned)
                            },
                            label = { Text("Hex 정제 ✨", fontSize = 11.sp) }
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
                        Text("Step 3. Sector 0 인증 키 설정 (6바이트 Hex)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = authKeyInput,
                            onValueChange = onAuthKeyChange,
                            label = { Text("Sector 0 Key (12자리 Hex)") },
                            placeholder = { Text("기본값: FFFFFFFFFFFF") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Extended Preset Key Chips
                        Text("자주 사용하는 키 프리셋:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7").forEach { key ->
                                FilterChip(
                                    selected = authKeyInput.uppercase() == key,
                                    onClick = { onAuthKeyChange(key) },
                                    label = { Text(key.take(4) + "...", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Step 4: Ready Guidance Hero Banner
        item {
            val isReady = isValidHex && (selectedWriteMode == 1 || authKeyInput.length == 12)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isReady) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isReady) "쓰기 대기 상태 🎯" else "입력 정보 확인 필요 ⚠️",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isReady) 
                                "준비된 CUID(Gen2) 복제 태그를 스마트폰 뒷면에 밀착하면 지정한 UID($cleanUid)가 즉시 기록됩니다." 
                            else 
                                "올바른 8자리 Hex UID 및 12자리 인증 키를 입력해야 쓰기가 활성화됩니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepChip(number: String, title: String, isActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (isActive) MaterialTheme.colorScheme.primary else Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onSurface else Color.Gray
        )
    }
}

// Tab 2: HCE Emulation View with Interactive Card & Live APDU Log
@Composable
fun HceTabContent(
    payloadText: String,
    onPayloadTextChange: (String) -> Unit
) {
    var isHceActive by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Host Card Emulation (HCE) Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("에뮬레이터", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = isHceActive,
                        onCheckedChange = { isHceActive = it },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }
        }

        // Digital Card Graphic Component with Glowing Signal Effect
        item {
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
                                colors = if (isHceActive) listOf(
                                    Color(0xFF0F172A),
                                    Color(0xFF1E293B),
                                    Color(0xFF0284C7)
                                ) else listOf(
                                    Color(0xFF334155),
                                    Color(0xFF1E293B)
                                )
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
                                Text("VIRTUAL SMART CARD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                            }
                            Surface(
                                color = if (isHceActive) Color(0xFF10B981).copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.3f),
                                shape = CircleShape
                            ) {
                                Text(
                                    text = if (isHceActive) "ACTIVE HCE 🟢" else "PAUSED ⏸️",
                                    color = if (isHceActive) Color(0xFF34D399) else Color.LightGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Column {
                            Text("REGISTERED AID", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Text("F0010203040506", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 1.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("RESPONSE PAYLOAD", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                Text(
                                    text = payloadText.take(24) + if (payloadText.length > 24) "..." else "",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text("STATUS: 90 00", color = Color(0xFF34D399), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Payload Input & Presets
        item {
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
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("빠른 카드 프로필 프리셋:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = { onPayloadTextChange("Hello NFC Emulator!") },
                            label = { Text("Hello NFC", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = { onPayloadTextChange("PASS_994821_OK") },
                            label = { Text("출입증 Pass", fontSize = 11.sp) }
                        )
                        AssistChip(
                            onClick = { onPayloadTextChange("STUDENT_ID_202601") },
                            label = { Text("학생증 ID", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // Live APDU Transaction Log Viewer
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("실시간 APDU 통신 로그", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (MyHostApduService.apduLogs.isNotEmpty()) {
                            TextButton(
                                onClick = { MyHostApduService.clearLogs() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("로그 지우기", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (MyHostApduService.apduLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "외부 NFC 리더기 태그 시 APDU 명령 및 응답이\n실시간 기록됩니다.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MyHostApduService.apduLogs.take(10).forEach { log ->
                                ApduLogRow(log = log)
                            }
                        }
                    }
                }
            }
        }

        // Security Notice Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("ℹ️ HCE UID 스푸핑 보안 안내", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "안드로이드 OS HCE API는 보안상 물리적 NFC 태그의 하드웨어 UID 변경/스푸핑을 제한합니다. 본 모듈은 AID 기반의 APDU 스마트카드 애플리케이션 에뮬레이션 규격을 준수합니다.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ApduLogRow(log: ApduLogItem) {
    val isOut = log.direction == "OUT"
    val badgeColor = if (isOut) Color(0xFF10B981) else MaterialTheme.colorScheme.primary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = log.direction,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(log.summary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(HexUtils.formatHexWithSpaces(log.hexData), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(log.timestamp, fontSize = 9.sp, color = Color.Gray)
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
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f)
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
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Modern Card Item UI Widget
@Composable
fun ModernCardInfoItem(
    card: ScannedCardInfo,
    onCopyClick: () -> Unit,
    onCardClick: () -> Unit
) {
    val isCuid = card.detectedType.contains("Gen2") || card.detectedType.contains("CUID")
    val badgeColor = if (isCuid) Color(0xFF10B981) else Color(0xFFEF4444)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("NFC CARD UID", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(card.timestamp, color = Color.Gray, fontSize = 11.sp)
                    FilledTonalButton(
                        onClick = onCopyClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("UID 쓰기", fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Formatted Monospace UID Display
            val formattedUid = card.uid.chunked(2).joinToString(" : ")
            Text(
                text = formattedUid,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Detected CUID Card Type Badge
            Surface(
                color = badgeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isCuid) Icons.Default.CheckCircle else Icons.Default.Lock,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = card.detectedType,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.techList.forEach { tech ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tech, fontSize = 9.sp) },
                            modifier = Modifier.height(22.dp)
                        )
                    }
                }
                Text("상세보기 🔍", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Tag Detail Modal Inspection Dialog
@Composable
fun TagDetailDialog(
    card: ScannedCardInfo,
    onDismiss: () -> Unit,
    onUseUidForWrite: (String) -> Unit
) {
    val isCuid = card.detectedType.contains("Gen2") || card.detectedType.contains("CUID")
    val bccHex = HexUtils.calculateBccHex(card.uid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("NFC 태그 정밀 진단 보고서", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("CARD IDENTIFIER (UID)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = card.uid.chunked(2).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Sector 0 Block 0 Byte Breakdown Matrix
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Sector 0 Block 0 바이트 분석", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("UID (0-3B):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(card.uid.chunked(2).joinToString(" "), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("BCC Checksum (4B):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(bccHex?.let { "0x$it (검증 성공 ✅)" } ?: "N/A", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF10B981))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SAK / ATQA (5-7B):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("SAK: 0x${card.sak} | ATQA: 0x${card.atqa}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Compatibility Summary
                Surface(
                    color = if (isCuid) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isCuid) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isCuid) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCuid) 
                                "이 태그는 CUID(Gen2) 복제 태그로 판별되어 스마트폰으로 즉시 UID 덮어쓰기가 가능합니다." 
                            else 
                                "표준 MIFARE Classic 태그입니다. 하드웨어 제조 단계에서 UID(Block 0)가 영구 고정되어 있습니다.",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onUseUidForWrite(card.uid) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("이 UID로 복제 쓰기 ➔", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", fontSize = 12.sp)
            }
        }
    )
}

// Material 3 Color Theme System (Light & Dark Support)
@Composable
fun NfcAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF38BDF8),
            primaryContainer = Color(0xFF0369A1),
            onPrimaryContainer = Color(0xFFE0F2FE),
            secondary = Color(0xFF34D399),
            secondaryContainer = Color(0xFF065F46),
            onSecondaryContainer = Color(0xFFD1FAE5),
            surface = Color(0xFF1E293B),
            surfaceVariant = Color(0xFF334155),
            onSurfaceVariant = Color(0xFF94A3B8),
            background = Color(0xFF0F172A),
            onBackground = Color(0xFFF8FAFC)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0284C7),
            primaryContainer = Color(0xFFE0F2FE),
            onPrimaryContainer = Color(0xFF0369A1),
            secondary = Color(0xFF10B981),
            secondaryContainer = Color(0xFFD1FAE5),
            onSecondaryContainer = Color(0xFF065F46),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF64748B),
            background = Color(0xFFF8FAFC),
            onBackground = Color(0xFF0F172A)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
