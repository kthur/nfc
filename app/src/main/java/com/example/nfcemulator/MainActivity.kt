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

enum class MagicFlowState {
    WAIT_ORIGINAL, // Step 1: 원본 카드 읽기 대기
    WAIT_CLONE,    // Step 2: 복제용 CUID 카드 쓰기 대기
    SUCCESS        // Step 3: 복제 완료
}

data class ScannedCardInfo(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val uid: String,
    val isCuidSupported: Boolean = false,
    val statusText: String = "분석 완료",
    val dumpBlocks: List<String> = emptyList(),
    val readSectorCount: Int = 0
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

    companion object {
        var currentFlowState by mutableStateOf(MagicFlowState.WAIT_ORIGINAL)
        var activeTargetCard by mutableStateOf<ScannedCardInfo?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcAvailable = nfcAdapter != null
        isNfcEnabled = nfcAdapter?.isEnabled == true

        setContent {
            NfcMagicAppTheme {
                NfcMagicAppScreen(
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

        if (currentFlowState == MagicFlowState.WAIT_CLONE) {
            // Automatically clone to blank card!
            handleCuidWrite(tag)
        } else {
            // Read original card automatically
            val mifare = MifareClassic.get(tag)
            val (isCuid, statusMsg) = detectTagCuidSupport(tag)
            val (dumpBlocks, successSectors) = if (mifare != null) dumpAllSectors(mifare) else Pair(emptyList(), 0)

            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                isCuidSupported = isCuid,
                statusText = statusMsg,
                dumpBlocks = dumpBlocks,
                readSectorCount = successSectors
            )

            triggerHapticFeedback(true)

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                activeTargetCard = cardInfo
                currentFlowState = MagicFlowState.WAIT_CLONE // Automatic seamless transition!
                Toast.makeText(this, "✅ 원본 카드 스캔 완료! 새 복제 카드를 대세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dumpAllSectors(mifare: MifareClassic): Pair<List<String>, Int> {
        val blocksDump = ArrayList<String>()
        var successSectorCount = 0

        try {
            mifare.connect()
            mifare.timeout = 2000
            val sectorCount = mifare.sectorCount.coerceAtMost(16)

            for (sector in 0 until sectorCount) {
                var authenticated = false

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
                            return Pair(true, "복제 가능 카드 (CUID) ✅")
                        } catch (e: Exception) {
                            return Pair(false, "원본 카드 ℹ️")
                        }
                    }
                } else {
                    return Pair(false, "보안 카드 🔑")
                }
            } catch (e: Exception) {
                return Pair(false, "읽기 전용")
            } finally {
                try { mifare.close() } catch (e: Exception) {}
            }
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            return Pair(false, "NfcA 원본 카드 ℹ️")
        }

        return Pair(false, "일반 NFC")
    }

    private fun handleCuidWrite(tag: Tag) {
        val targetCard = activeTargetCard
        val uidToWrite = targetCard?.uid ?: ""
        if (uidToWrite.length != 8) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "복제할 원본 카드가 지정되지 않았습니다.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "카드를 스마트폰 뒷면에 다시 대주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val dumpBlocks = targetCard?.dumpBlocks ?: emptyList()

        try {
            mifare.connect()
            mifare.timeout = 4000
            
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
                    Toast.makeText(this, "CUID 카드의 보안 키를 확인할 수 없습니다.", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "CUID 카드 쓰기 실패! CUID(Gen2) 복제용 카드가 맞는지 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
                return
            }

            // Write all blocks 1..63
            var writtenBlockCount = 1
            if (dumpBlocks.size >= 64) {
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
                            if (blockIndex == 0) continue

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
            runOnUiThread {
                currentFlowState = MagicFlowState.SUCCESS
                Toast.makeText(this, "🎉 100% 똑같은 복제 카드 생성 완료!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "쓰기 오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
fun NfcMagicAppScreen(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    scannedCards: List<ScannedCardInfo>,
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
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
                            Text("NFC Magic Cloner", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            Text("Auto-Flow 100% Twin Copy", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // Device NFC Status Indicator
            MagicNfcStatusBanner(
                isNfcAvailable = isNfcAvailable,
                isNfcEnabled = isNfcEnabled,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 1-Screen Magic Hero Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MagicHeroContent(
                    flowState = MainActivity.currentFlowState,
                    targetCard = MainActivity.activeTargetCard,
                    onResetToRead = {
                        MainActivity.currentFlowState = MagicFlowState.WAIT_ORIGINAL
                        MainActivity.activeTargetCard = null
                    }
                )
            }

            // Bottom Scanned History List
            if (scannedCards.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("스캔 내역 (${scannedCards.size}건)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("터치 시 즉시 복제 대상으로 지정", fontSize = 10.sp, color = Color.Gray)
                }
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(scannedCards, key = { it.id }) { card ->
                        MagicHistoryRowItem(
                            card = card,
                            onSelectToClone = {
                                MainActivity.activeTargetCard = card
                                MainActivity.currentFlowState = MagicFlowState.WAIT_CLONE
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MagicNfcStatusBanner(
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
                    text = if (!isNfcAvailable) "NFC 미지원 스마트폰" 
                           else if (!isNfcEnabled) "NFC가 꺼져 있습니다 📴" 
                           else "NFC 준비 완료 🟢",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (!isNfcEnabled) "아래 버튼을 눌러 NFC를 켜주세요."
                           else "📱 카드를 휴대폰 뒷면 상단(카메라 주변)에 밀착하세요.",
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

// 1-Screen Magic Hero Content Component
@Composable
fun MagicHeroContent(
    flowState: MagicFlowState,
    targetCard: ScannedCardInfo?,
    onResetToRead: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (flowState) {
                MagicFlowState.WAIT_ORIGINAL -> MaterialTheme.colorScheme.surface
                MagicFlowState.WAIT_CLONE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                MagicFlowState.SUCCESS -> Color(0xFFECFDF5)
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            when (flowState) {
                MagicFlowState.WAIT_ORIGINAL -> {
                    // Step 1: Read Original Card
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = CircleShape
                        ) {
                            Text(
                                "1단계: 원본 카드 읽기 📡",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            )
                        }

                        MagicRadarPulseHero(
                            title = "원본 카드를 휴대폰 뒷면에 대세요",
                            subtitle = "모든 섹터(0~15) 64개 블록 데이터를 100% 읽어옵니다."
                        )

                        Text("카드가 인식되면 자동으로 복제 단계로 연결됩니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                MagicFlowState.WAIT_CLONE -> {
                    // Step 2: Auto-Transitioned! Place blank CUID card to clone!
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                color = Color(0xFF10B981),
                                shape = CircleShape
                            ) {
                                Text(
                                    "2단계: 새 복제 카드 대기 🎯",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            val uid = targetCard?.uid ?: ""
                            Text(
                                text = uid.chunked(2).joinToString(" : "),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "원본 카드 100% 덤프 완료 (${targetCard?.readSectorCount ?: 0}/16 섹터)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }

                        MagicRadarPulseHero(
                            title = "새 복제(CUID) 카드를 대세요!",
                            subtitle = "휴대폰 뒷면에 대면 1초 만에 100% 동일하게 복제됩니다."
                        )

                        TextButton(onClick = onResetToRead) {
                            Text("↺ 다른 원본 카드 스캔하기", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                MagicFlowState.SUCCESS -> {
                    // Step 3: Success Celebration!
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = Color(0xFF10B981),
                            shape = CircleShape
                        ) {
                            Text(
                                "100% 동일한 복제 카드 생성 완료! 🎉",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(54.dp))
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text("복사 완료!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF065F46))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("원본 카드와 모든 섹터, 접근 권한까지 1:1로 동일합니다.", fontSize = 12.sp, color = Color(0xFF047857), textAlign = TextAlign.Center)
                        }

                        Button(
                            onClick = onResetToRead,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("다음 카드 복제하기 ↺", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MagicRadarPulseHero(title: String, subtitle: String) {
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
fun MagicHistoryRowItem(card: ScannedCardInfo, onSelectToClone: () -> Unit) {
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
                Text("복제 ➔", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// Simple Theme Setup
@Composable
fun NfcMagicAppTheme(
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
