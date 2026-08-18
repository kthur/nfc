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
    val isCuidSupported: Boolean = false,
    val statusText: String = "분석 완료",
    val dumpBlocks: List<String> = emptyList(), // 64 blocks of 16-byte hex strings
    val readSectorCount: Int = 0
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

    companion object {
        var activeStep = 0 // 0: Read/Scan, 1: Write/Clone
        var targetWriteUid = ""
        var targetCardInfo: ScannedCardInfo? = null
        var isFullSectorCloneMode = true // true: Full Sector Dump Clone, false: UID Only
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcAvailable = nfcAdapter != null
        isNfcEnabled = nfcAdapter?.isEnabled == true

        setContent {
            NfcSleekAppTheme {
                NfcSleekAppScreen(
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
            Log.d("Haptic", "Vibration error", e)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        val tagIdBytes = tag.id
        val hexUid = HexUtils.byteArrayToHexString(tagIdBytes)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (activeStep == 1) {
            // Write Mode active
            handleCuidWrite(tag)
        } else {
            // Scan Mode active: Perform full 16-sector dump read
            val mifare = MifareClassic.get(tag)
            val (isCuid, statusMsg) = detectTagCuidSupport(tag)
            val (dumpBlocks, successSectors) = if (mifare != null) dumpAllSectors(mifare) else Pair(emptyList(), 0)

            val fullStatusMsg = if (successSectors > 0) {
                "$statusMsg | 덤프: $successSectors/16 섹터"
            } else {
                statusMsg
            }

            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                isCuidSupported = isCuid,
                statusText = fullStatusMsg,
                dumpBlocks = dumpBlocks,
                readSectorCount = successSectors
            )

            triggerHapticFeedback(true)

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "✅ 카드가 스캔되었습니다! ($successSectors/16 섹터 읽음)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Read full 16 sectors (64 blocks) from Mifare Classic card using dictionary keys
    private fun dumpAllSectors(mifare: MifareClassic): Pair<List<String>, Int> {
        val blocksDump = ArrayList<String>()
        var successSectorCount = 0

        try {
            mifare.connect()
            mifare.timeout = 2000
            val sectorCount = mifare.sectorCount.coerceAtMost(16)

            for (sector in 0 until sectorCount) {
                var authenticated = false
                var keyUsed: ByteArray? = null

                for (key in HexUtils.DEFAULT_KEYS) {
                    if (mifare.authenticateSectorWithKeyA(sector, key) || 
                        mifare.authenticateSectorWithKeyB(sector, key)) {
                        authenticated = true
                        break
                    }
                }

                if (authenticated) {
                    successSectorCount++
                    val firstBlock = mifare.sectorToBlock(sector)
                    val blockCountInSector = mifare.getBlockCountInSector(sector)

                    for (b in 0 until blockCountInSector) {
                        val blockIndex = firstBlock + b
                        try {
                            val blockBytes = mifare.readBlock(blockIndex)
                            if (blockBytes != null && blockBytes.size == 16) {
                                blocksDump.add(HexUtils.byteArrayToHexString(blockBytes))
                            } else {
                                blocksDump.add("00000000000000000000000000000000")
                            }
                        } catch (e: Exception) {
                            blocksDump.add("00000000000000000000000000000000")
                        }
                    }
                } else {
                    val blockCountInSector = mifare.getBlockCountInSector(sector)
                    for (b in 0 until blockCountInSector) {
                        blocksDump.add("--------------------------------")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DumpSectors", "Error dumping sectors", e)
        } finally {
            try { mifare.close() } catch (e: Exception) {}
        }

        return Pair(blocksDump, successSectorCount)
    }

    private fun detectTagCuidSupport(tag: Tag): Pair<Boolean, String> {
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
                        try {
                            mifare.writeBlock(0, block0)
                            return Pair(true, "복제 쓰기 가능 카드 (CUID) ✅")
                        } catch (e: Exception) {
                            return Pair(false, "원본 카드 (복제용 공태그로 쓰기 권장) ℹ️")
                        }
                    }
                } else {
                    return Pair(false, "비표준 키 카드 🔑")
                }
            } catch (e: Exception) {
                return Pair(false, "읽기 전용 카드")
            } finally {
                try { mifare.close() } catch (e: Exception) {}
            }
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            return Pair(false, "NfcA 원본 카드 (UID 고정) ℹ️")
        }

        return Pair(false, "일반 NFC 태그")
    }

    // Write Full Sector Dump (all blocks 0..63) or Block 0 UID to CUID (Gen2) card
    private fun handleCuidWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "올바른 8자리 UID를 선택하거나 입력해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "카드가 인식되지 않았습니다. CUID(Gen2) 카드를 밀착해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val targetCard = targetCardInfo
        val dumpBlocks = targetCard?.dumpBlocks ?: emptyList()
        val doFullSectorClone = isFullSectorCloneMode && dumpBlocks.size >= 64

        try {
            mifare.connect()
            mifare.timeout = 4000
            
            // Step 1: Write Block 0 (UID + BCC + Manufacturer)
            val block0Data = HexUtils.createBlock0(uidToWrite)
            var authSuccess = false

            for (key in HexUtils.DEFAULT_KEYS) {
                if (mifare.authenticateSectorWithKeyA(0, key) || mifare.authenticateSectorWithKeyB(0, key)) {
                    authSuccess = true
                    break
                }
            }
            
            if (!authSuccess) {
                triggerHapticFeedback(false)
                runOnUiThread {
                    Toast.makeText(this, "섹터 0 인증 실패! CUID 카드의 보안 키를 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
                }
                return
            }

            var block0Written = false
            try {
                mifare.writeBlock(0, block0Data)
                block0Written = true
            } catch (e: Exception) {
                try {
                    val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
                    try { mifare.transceive(writeHeader) } catch (ex: Exception) {}
                    mifare.transceive(block0Data)
                    block0Written = true
                } catch (ex: Exception) {
                    Log.e("CuidWrite", "Block 0 write failed", ex)
                }
            }

            if (!block0Written) {
                triggerHapticFeedback(false)
                runOnUiThread {
                    Toast.makeText(this, "CUID Block 0 (UID) 쓰기 실패! 카드가 CUID(Gen2) 규격인지 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
                return
            }

            var writtenBlockCount = 1
            // Step 2: Full Sector Data Clone (Write blocks 1..63 if full dump available)
            if (doFullSectorClone) {
                val sectorCount = mifare.sectorCount.coerceAtMost(16)
                for (sector in 0 until sectorCount) {
                    var secAuth = false
                    for (key in HexUtils.DEFAULT_KEYS) {
                        if (mifare.authenticateSectorWithKeyA(sector, key) || mifare.authenticateSectorWithKeyB(sector, key)) {
                            secAuth = true
                            break
                        }
                    }

                    if (secAuth) {
                        val firstBlock = mifare.sectorToBlock(sector)
                        val blockCount = mifare.getBlockCountInSector(sector)

                        for (b in 0 until blockCount) {
                            val blockIndex = firstBlock + b
                            if (blockIndex == 0) continue // Already written
                            val isTrailerBlock = (b == blockCount - 1)

                            // Skip trailer block to prevent locking sector unless data is valid
                            if (isTrailerBlock) continue

                            val hexData = dumpBlocks.getOrNull(blockIndex) ?: continue
                            if (hexData.length == 32 && !hexData.contains("-")) {
                                try {
                                    val blockBytes = HexUtils.hexStringToByteArray(hexData)
                                    mifare.writeBlock(blockIndex, blockBytes)
                                    writtenBlockCount++
                                } catch (e: Exception) {
                                    Log.w("CuidWrite", "Block $blockIndex write failed", e)
                                }
                            }
                        }
                    }
                }
            }

            triggerHapticFeedback(true)
            val finalCount = writtenBlockCount
            runOnUiThread {
                if (doFullSectorClone) {
                    Toast.makeText(this, "🎉 카드의 모든 데이터 (0~15 섹터, " + finalCount + "개 블록) 복제 완료!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "🎉 UID 복제 성공! (새 UID: " + uidToWrite + ")", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "쓰기 중 오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } finally {
            try { mifare.close() } catch (e: Exception) {}
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
fun NfcSleekAppScreen(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    scannedCards: List<ScannedCardInfo>,
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
    var activeStep by remember { mutableIntStateOf(0) } // 0: 스캔, 1: 복제
    var targetUidInput by remember { mutableStateOf("") }
    var isFullCloneSelected by remember { mutableStateOf(true) }

    LaunchedEffect(activeStep) {
        MainActivity.activeStep = activeStep
    }
    LaunchedEffect(targetUidInput) {
        MainActivity.targetWriteUid = targetUidInput
    }
    LaunchedEffect(isFullCloneSelected) {
        MainActivity.isFullSectorCloneMode = isFullCloneSelected
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            Color(0xFF0284C7)
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
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("NFC Cloner", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("Full Sector Data & UID Cloner", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (scannedCards.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "기록 삭제")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // Sleek Device NFC Status Banner
            SleekNfcStatusBanner(
                isNfcAvailable = isNfcAvailable,
                isNfcEnabled = isNfcEnabled,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Step Selector Switcher Segment
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SleekStepSegment(
                    stepNumber = "1",
                    title = "원본 카드 스캔",
                    isSelected = activeStep == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { activeStep = 0 }
                )
                SleekStepSegment(
                    stepNumber = "2",
                    title = "새 카드로 복제",
                    isSelected = activeStep == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { activeStep = 1 }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Full-Screen Body Container (weight(1f) fills available height cleanly!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeStep) {
                    0 -> SleekStep1ScanBody(
                        scannedCards = scannedCards,
                        onCopyToWriteStep = { card ->
                            targetUidInput = card.uid
                            MainActivity.targetCardInfo = card
                            activeStep = 1
                        }
                    )
                    1 -> SleekStep2WriteBody(
                        targetUidInput = targetUidInput,
                        onTargetUidChange = { targetUidInput = it },
                        isFullClone = isFullCloneSelected,
                        onToggleFullClone = { isFullCloneSelected = it },
                        recentScannedCards = scannedCards,
                        onBackToStep1 = { activeStep = 0 }
                    )
                }
            }
        }
    }
}

// Sleek Status Banner
@Composable
fun SleekNfcStatusBanner(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isNfcEnabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color(0xFFFEF2F2),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isNfcEnabled) Color(0xFF10B981) else Color(0xFFEF4444))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!isNfcAvailable) "NFC 미지원 기기입니다" 
                           else if (!isNfcEnabled) "NFC가 꺼져 있습니다 📴" 
                           else "NFC 준비 완료 🟢",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (!isNfcEnabled) "아래 버튼을 눌러 안드로이드 NFC를 활성화하세요."
                           else "📱 카드를 스마트폰 뒷면 상단(카메라 부근)에 대주세요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isNfcEnabled && isNfcAvailable) {
                Button(
                    onClick = onOpenNfcSettings,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("NFC 켜기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SleekStepSegment(
    stepNumber: String,
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.4f)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = bgColor,
        shadowElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(stepNumber, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

// Step 1: Scan Full Screen Body Layout with Full Sector Dump Status
@Composable
fun SleekStep1ScanBody(
    scannedCards: List<ScannedCardInfo>,
    onCopyToWriteStep: (ScannedCardInfo) -> Unit
) {
    val lastCard = scannedCards.firstOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (lastCard == null) {
                    SleekRadarPulseHero(
                        title = "카드를 스마트폰 뒷면에 밀착하세요",
                        subtitle = "모든 섹터(0~15) 데이터와 UID를 완벽하게 읽어옵니다."
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("스캔 성공! 전체 데이터 덤프 완료", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CARD UID", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            val formattedUid = lastCard.uid.chunked(2).joinToString(" : ")
                            Text(
                                text = formattedUid,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "16개 섹터 (64개 블록) 데이터 덤프 완료 (${lastCard.readSectorCount}/16 성공)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Button(
                            onClick = { onCopyToWriteStep(lastCard) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("👉 전체 데이터(모든 섹터) 복제 모드로 이동", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Bottom Scanned Cards History Section
        if (scannedCards.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("스캔 내역 (${scannedCards.size}건)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("터치 시 전체 복제 모드 전환", fontSize = 10.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(scannedCards, key = { it.id }) { card ->
                    SleekHistoryRowItem(card = card, onSelectToClone = { onCopyToWriteStep(card) })
                }
            }
        }
    }
}

// Step 2: Write Full Screen Body Layout with Full Clone Toggle
@Composable
fun SleekStep2WriteBody(
    targetUidInput: String,
    onTargetUidChange: (String) -> Unit,
    isFullClone: Boolean,
    onToggleFullClone: (Boolean) -> Unit,
    recentScannedCards: List<ScannedCardInfo>,
    onBackToStep1: () -> Unit
) {
    val cleanUid = targetUidInput.replace(":", "").replace(" ", "").trim()
    val isValidHex = cleanUid.length == 8 && HexUtils.isValidHex(cleanUid)
    val hasDump = MainActivity.targetCardInfo?.dumpBlocks?.isNotEmpty() == true

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isValidHex) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isValidHex) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) {
                                Text(
                                    if (isFullClone && hasDump) "전체 섹터 풀 데이터 복제 모드 🎯" else "UID 복제 모드 🎯",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = cleanUid.chunked(2).joinToString(" : "),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        SleekRadarPulseHero(
                            title = "새 복제용(CUID) 카드를 대세요",
                            subtitle = if (isFullClone && hasDump) 
                                "모든 섹터(0~15) 데이터와 UID를 완벽하게 덮어씁니다." 
                            else 
                                "카드를 대면 1초 만에 UID 덮어쓰기가 완료됩니다."
                        )

                        // Full Dump Clone Toggle Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("전체 섹터 데이터 복제", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("UID 외에 카드의 모든 데이터 블록을 복제합니다.", fontSize = 10.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = isFullClone,
                                onCheckedChange = onToggleFullClone,
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("복제할 카드의 UID를 지정해 주세요", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("1단계에서 원본 카드를 스캔하면 전체 데이터와 함께 자동 지정됩니다.", fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBackToStep1) {
                            Text("1단계 원본 카드 스캔하러 가기 ➔", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Manual UID Input Row Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("직접 UID 지정 (8자리 Hex)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    if (recentScannedCards.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val card = recentScannedCards.first()
                                onTargetUidChange(card.uid)
                                MainActivity.targetCardInfo = card
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("최근 스캔 카드 불러오기 📋", fontSize = 11.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = targetUidInput,
                    onValueChange = onTargetUidChange,
                    placeholder = { Text("예: AABBCCDD", fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
fun SleekRadarPulseHero(title: String, subtitle: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(70.dp)
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
                Icon(Icons.Default.Nfc, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

@Composable
fun SleekHistoryRowItem(card: ScannedCardInfo, onSelectToClone: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectToClone),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(card.uid.chunked(2).joinToString(" : "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("덤프: ${card.readSectorCount}/16 섹터", fontSize = 10.sp, color = Color.Gray)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.timestamp, fontSize = 10.sp, color = Color.Gray)
                Spacer(modifier = Modifier.width(8.dp))
                Text("전체 복제 ➔", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// Sleek Theme Setup
@Composable
fun NfcSleekAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF0284C7),
            primaryContainer = Color(0xFF0369A1),
            surface = Color(0xFF1E293B),
            surfaceVariant = Color(0xFF334155),
            background = Color(0xFF0F172A),
            onBackground = Color(0xFFF8FAFC)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0284C7),
            primaryContainer = Color(0xFFE0F2FE),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F5F9),
            background = Color(0xFFF8FAFC),
            onBackground = Color(0xFF0F172A)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
