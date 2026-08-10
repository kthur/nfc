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
    val techList: List<String>,
    val isCuidSupported: Boolean = false,
    val statusText: String = "분석 중..."
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

    companion object {
        var activeTab = 0
        var targetWriteUid = ""
        var authKeyHex = "FFFFFFFFFFFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcAvailable = nfcAdapter != null
        isNfcEnabled = nfcAdapter?.isEnabled == true

        setContent {
            NfcSimpleAppTheme {
                NfcSimpleAppScreen(
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
        val techList = tag.techList.map { it.substringAfterLast(".") }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (activeTab == 1) {
            // Write Mode active
            handleCuidWrite(tag)
        } else {
            // Scan Mode active
            val (isCuid, statusMsg) = detectTagCuidSupport(tag)
            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                techList = techList,
                isCuidSupported = isCuid,
                statusText = statusMsg
            )

            triggerHapticFeedback(true)

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "카드 스캔 완료: $hexUid", Toast.LENGTH_SHORT).show()
            }
        }
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
                            return Pair(true, "CUID (Gen2) - UID 쓰기 가능 ✅")
                        } catch (e: Exception) {
                            return Pair(false, "표준 MIFARE (UID 변경 불가) 🔒")
                        }
                    }
                } else {
                    return Pair(false, "MIFARE (인증 키 불일치) 🔑")
                }
            } catch (e: Exception) {
                return Pair(false, "MIFARE (읽기 전용)")
            } finally {
                try { mifare.close() } catch (e: Exception) {}
            }
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            return Pair(false, "NfcA 표준 태그 (UID 고정) 🔒")
        }

        return Pair(false, "기타 NFC 태그")
    }

    private fun handleCuidWrite(tag: Tag) {
        val uidToWrite = targetWriteUid.replace(":", "").replace(" ", "").trim()
        if (uidToWrite.length != 8) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "올바른 8자리 Hex UID를 입력해 주세요. (예: AABBCCDD)", Toast.LENGTH_LONG).show()
            }
            return
        }

        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "Mifare Classic 규격 태그를 뒷면에 밀착해 주세요.", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "섹터 0 인증 실패!\n카드의 키가 FFFFFFFFFFFF가 아닙니다.", Toast.LENGTH_LONG).show()
                }
                return
            }

            val block0Data = HexUtils.createBlock0(uidToWrite)
            var writeSuccess = false

            try {
                mifare.writeBlock(0, block0Data)
                writeSuccess = true
            } catch (e: Exception) {
                Log.w("CuidWrite", "writeBlock failed, trying raw transceive...", e)
            }

            if (!writeSuccess) {
                try {
                    val writeHeader = byteArrayOf(0xA0.toByte(), 0x00.toByte())
                    try { mifare.transceive(writeHeader) } catch (e: Exception) {}
                    mifare.transceive(block0Data)
                    writeSuccess = true
                } catch (e: Exception) {
                    Log.e("CuidWrite", "transceive failed", e)
                }
            }

            triggerHapticFeedback(writeSuccess)
            if (writeSuccess) {
                runOnUiThread {
                    Toast.makeText(this, "🎉 CUID 복제 성공! UID: $uidToWrite", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "쓰기 실패! 이 카드가 CUID(Gen2) 복제 태그인지 확인해 주세요.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            triggerHapticFeedback(false)
            runOnUiThread {
                Toast.makeText(this, "오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
fun NfcSimpleAppScreen(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    scannedCards: List<ScannedCardInfo>,
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var targetUidInput by remember { mutableStateOf("") }

    LaunchedEffect(selectedTab) {
        MainActivity.activeTab = selectedTab
    }
    LaunchedEffect(targetUidInput) {
        MainActivity.targetWriteUid = targetUidInput
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Nfc,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("NFC Cloner", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Simple Card Reader & Writer", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (selectedTab == 0 && scannedCards.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "삭제")
                        }
                    }
                    IconButton(onClick = onOpenNfcSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
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
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Radar, contentDescription = "스캔") },
                    label = { Text("카드 스캔", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CopyAll, contentDescription = "복제") },
                    label = { Text("CUID 복제 쓰기", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
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
            // Simplified NFC Status Bar
            SimpleNfcStatusBar(
                isNfcAvailable = isNfcAvailable,
                isNfcEnabled = isNfcEnabled,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when (selectedTab) {
                    0 -> SimpleScanTabContent(
                        scannedCards = scannedCards,
                        onCopyToWrite = { uid ->
                            targetUidInput = uid
                            selectedTab = 1
                        }
                    )
                    1 -> SimpleWriteTabContent(
                        targetUidInput = targetUidInput,
                        onTargetUidChange = { targetUidInput = it },
                        recentScannedCards = scannedCards
                    )
                }
            }
        }
    }
}

// Simple Device NFC Status Bar
@Composable
fun SimpleNfcStatusBar(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit
) {
    val (title, desc, color) = when {
        !isNfcAvailable -> Triple("NFC 미지원 기기 ❌", "이 스마트폰은 NFC를 지원하지 않습니다.", Color(0xFFEF4444))
        !isNfcEnabled -> Triple("NFC 비활성화됨 📴", "NFC를 켜야 스캔 및 복제가 가능합니다.", Color(0xFFF59E0B))
        else -> Triple("NFC 준비 완료 🟢", "NFC 카드를 스마트폰 뒷면에 태그하세요.", Color(0xFF10B981))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
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
                    .background(color)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isNfcEnabled && isNfcAvailable) {
                Button(
                    onClick = onOpenNfcSettings,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("NFC 켜기", fontSize = 11.sp)
                }
            }
        }
    }
}

// Simplified Scan Tab
@Composable
fun SimpleScanTabContent(
    scannedCards: List<ScannedCardInfo>,
    onCopyToWrite: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("스캔 내역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${scannedCards.size}개 카드가 스캔됨", fontSize = 12.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (scannedCards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                SimpleRadarPulse()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(scannedCards, key = { it.id }) { card ->
                    SimpleCardItem(card = card, onCopyClick = { onCopyToWrite(card.uid) })
                }
            }
        }
    }
}

@Composable
fun SimpleRadarPulse() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Radar, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("스마트폰 뒷면에 카드를 밀착하세요", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("UID 읽기 및 CUID 복제 가능 여부를 판별합니다.", fontSize = 12.sp, color = Color.Gray)
    }
}

// Simple Card Item
@Composable
fun SimpleCardItem(card: ScannedCardInfo, onCopyClick: () -> Unit) {
    val badgeColor = if (card.isCuidSupported) Color(0xFF10B981) else Color(0xFFEF4444)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CARD UID", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text(card.timestamp, fontSize = 11.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(4.dp))

            val formattedUid = card.uid.chunked(2).joinToString(" : ")
            Text(
                text = formattedUid,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = card.statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Button(
                    onClick = onCopyClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("이 UID 복제하기 ➔", fontSize = 11.sp)
                }
            }
        }
    }
}

// Simplified Write / Clone Tab
@Composable
fun SimpleWriteTabContent(
    targetUidInput: String,
    onTargetUidChange: (String) -> Unit,
    recentScannedCards: List<ScannedCardInfo>
) {
    val cleanUid = targetUidInput.replace(":", "").replace(" ", "").trim()
    val isValidHex = HexUtils.isValidHex(cleanUid) && cleanUid.length == 8

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("CUID 카드로 복제 쓰기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("복제할 4바이트 Hex UID 입력", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                            label = { Text("랜덤 UID 🎲", fontSize = 11.sp) }
                        )

                        if (recentScannedCards.isNotEmpty()) {
                            AssistChip(
                                onClick = { onTargetUidChange(recentScannedCards.first().uid) },
                                label = { Text("최근 스캔 UID 📋", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isValidHex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isValidHex) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isValidHex) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = if (isValidHex) "복제 카드를 대세요! 🎯" else "UID를 입력해 주세요",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isValidHex) 
                                "새 CUID(Gen2) 카드를 스마트폰 뒷면에 대면 지정한 UID(${cleanUid.chunked(2).joinToString(":")})가 즉시 덮어씌워집니다." 
                            else 
                                "올바른 8자리 Hex UID를 입력하면 복제 준비 상태가 됩니다.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// Simple Theme Setup
@Composable
fun NfcSimpleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF38BDF8),
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
