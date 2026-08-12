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
    val statusText: String = "분석 완료"
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private val scannedCards = mutableStateListOf<ScannedCardInfo>()
    private var isNfcAvailable by mutableStateOf(false)
    private var isNfcEnabled by mutableStateOf(false)

    companion object {
        var activeStep = 0 // 0: Read/Scan, 1: Write/Clone
        var targetWriteUid = ""
        var authKeyHex = "FFFFFFFFFFFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        isNfcAvailable = nfcAdapter != null
        isNfcEnabled = nfcAdapter?.isEnabled == true

        setContent {
            NfcIntuitiveAppTheme {
                NfcIntuitiveAppScreen(
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
            // Scan Mode active
            val (isCuid, statusMsg) = detectTagCuidSupport(tag)
            val cardInfo = ScannedCardInfo(
                timestamp = timeStr,
                uid = hexUid.ifEmpty { "N/A" },
                isCuidSupported = isCuid,
                statusText = statusMsg
            )

            triggerHapticFeedback(true)

            runOnUiThread {
                scannedCards.add(0, cardInfo)
                Toast.makeText(this, "✅ 카드가 정상 읽혔습니다!", Toast.LENGTH_SHORT).show()
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
                            return Pair(true, "복제 쓰기 가능한 카드입니다 (CUID) ✅")
                        } catch (e: Exception) {
                            return Pair(false, "원본 카드입니다 (복제 대상 공태그로 쓰기 권장) ℹ️")
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
                Toast.makeText(this, "카드가 인식되지 않았습니다. 카드를 휴대폰 뒷면에 밀착해 주세요.", Toast.LENGTH_LONG).show()
            }
            return
        }

        try {
            mifare.connect()
            mifare.timeout = 3000
            
            val defaultKey = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            val authA = mifare.authenticateSectorWithKeyA(0, defaultKey)
            val authB = if (!authA) mifare.authenticateSectorWithKeyB(0, defaultKey) else true
            
            if (!authA && !authB) {
                triggerHapticFeedback(false)
                runOnUiThread {
                    Toast.makeText(this, "카드의 보안 키가 맞지 않습니다.", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "🎉 카드 복제가 완료되었습니다!\n(새 UID: ${uidToWrite.chunked(2).joinToString(":")})", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "쓰기 실패!\n복제 전용(CUID/Gen2) 카드가 맞는지 확인해 주세요.", Toast.LENGTH_LONG).show()
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
fun NfcIntuitiveAppScreen(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    scannedCards: List<ScannedCardInfo>,
    onOpenNfcSettings: () -> Unit,
    onClearHistory: () -> Unit
) {
    var activeStep by remember { mutableIntStateOf(0) } // 0: 스캔, 1: 복제
    var targetUidInput by remember { mutableStateOf("") }

    LaunchedEffect(activeStep) {
        MainActivity.activeStep = activeStep
    }
    LaunchedEffect(targetUidInput) {
        MainActivity.targetWriteUid = targetUidInput
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary),
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
                            Text("NFC 복제기", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("1초 만에 쉬운 NFC 카드 스캔 & 복제", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ) {
            // NFC Readiness & Location Guide Header
            IntuitiveNfcHeader(
                isNfcAvailable = isNfcAvailable,
                isNfcEnabled = isNfcEnabled,
                onOpenNfcSettings = onOpenNfcSettings
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Step Navigation Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WizardStepButton(
                    stepNumber = "1",
                    title = "원본 카드 읽기",
                    isSelected = activeStep == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { activeStep = 0 }
                )
                WizardStepButton(
                    stepNumber = "2",
                    title = "새 카드로 복제",
                    isSelected = activeStep == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { activeStep = 1 }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when (activeStep) {
                    0 -> Step1ReadContent(
                        scannedCards = scannedCards,
                        onCopyToWriteStep = { uid ->
                            targetUidInput = uid
                            activeStep = 1
                        }
                    )
                    1 -> Step2WriteContent(
                        targetUidInput = targetUidInput,
                        onTargetUidChange = { targetUidInput = it },
                        recentScannedCards = scannedCards,
                        onBackToStep1 = { activeStep = 0 }
                    )
                }
            }
        }
    }
}

// Intuitive NFC Header with Location Guide
@Composable
fun IntuitiveNfcHeader(
    isNfcAvailable: Boolean,
    isNfcEnabled: Boolean,
    onOpenNfcSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isNfcEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color(0xFFFEF2F2)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isNfcEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isNfcEnabled) Color(0xFF10B981) else Color(0xFFEF4444),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (!isNfcAvailable) "NFC 미지원 스마트폰입니다" 
                           else if (!isNfcEnabled) "NFC가 꺼져 있습니다 📴" 
                           else "NFC 준비 완료! 🟢",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (!isNfcEnabled) "아래 [NFC 켜기] 버튼을 눌러 활성화해 주세요."
                           else "📱 카드를 휴대폰 뒷면 상단(카메라 주변)에 대주세요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isNfcEnabled && isNfcAvailable) {
                Button(
                    onClick = onOpenNfcSettings,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("NFC 켜기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun WizardStepButton(
    stepNumber: String,
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
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

// Step 1: Read Card Content
@Composable
fun Step1ReadContent(
    scannedCards: List<ScannedCardInfo>,
    onCopyToWriteStep: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val lastCard = scannedCards.firstOrNull()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (lastCard == null) {
                        PulsingTouchTarget(
                            title = "카드를 휴대폰 뒷면에 대세요",
                            subtitle = "원본 카드의 고유 번호(UID)를 읽어옵니다."
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("가장 최근 읽은 카드", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val formattedUid = lastCard.uid.chunked(2).joinToString(" : ")
                        Text(
                            text = formattedUid,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            color = if (lastCard.isCuidSupported) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = lastCard.statusText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (lastCard.isCuidSupported) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onCopyToWriteStep(lastCard.uid) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("👉 이 카드의 UID로 복제하기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (scannedCards.size > 1) {
            item {
                Text("이전 스캔 내역 (${scannedCards.size}건)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            items(scannedCards.drop(1), key = { it.id }) { card ->
                SimpleCardHistoryItem(card = card, onSelectToClone = { onCopyToWriteStep(card.uid) })
            }
        }
    }
}

// Step 2: Write/Clone Card Content
@Composable
fun Step2WriteContent(
    targetUidInput: String,
    onTargetUidChange: (String) -> Unit,
    recentScannedCards: List<ScannedCardInfo>,
    onBackToStep1: () -> Unit
) {
    val cleanUid = targetUidInput.replace(":", "").replace(" ", "").trim()
    val isValidHex = cleanUid.length == 8 && HexUtils.isValidHex(cleanUid)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isValidHex) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isValidHex) {
                        Text("복제 준비 완료! 🎯", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "복제할 UID: ${cleanUid.chunked(2).joinToString(" : ")}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        PulsingTouchTarget(
                            title = "새 복제용(CUID) 카드를 대세요",
                            subtitle = "휴대폰 뒷면에 대면 1초 만에 쓰기가 완료됩니다."
                        )
                    } else {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("복제할 카드의 UID를 먼저 선택해 주세요", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("1단계에서 원본 카드를 스캔하면 자동으로 세팅됩니다.", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(onClick = onBackToStep1) {
                            Text("1단계로 돌아가서 카드 읽기 ➔")
                        }
                    }
                }
            }
        }

        // Custom UID Input Accordion Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("직접 UID 지정 (선택 사항)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = targetUidInput,
                        onValueChange = onTargetUidChange,
                        label = { Text("대상 UID (8자리 16진수)") },
                        placeholder = { Text("예: AABBCCDD") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (recentScannedCards.isNotEmpty()) {
                            AssistChip(
                                onClick = { onTargetUidChange(recentScannedCards.first().uid) },
                                label = { Text("최근 스캔 UID 📋", fontSize = 11.sp) }
                            )
                        }
                        AssistChip(
                            onClick = {
                                val randomBytes = ByteArray(4)
                                Random.nextBytes(randomBytes)
                                onTargetUidChange(HexUtils.byteArrayToHexString(randomBytes))
                            },
                            label = { Text("랜덤 UID 생성 🎲", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PulsingTouchTarget(title: String, subtitle: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    )
                    .shadow(6.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Nfc, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
    }
}

@Composable
fun SimpleCardHistoryItem(card: ScannedCardInfo, onSelectToClone: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(card.uid.chunked(2).joinToString(" : "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(card.timestamp, fontSize = 10.sp, color = Color.Gray)
            }
            TextButton(
                onClick = onSelectToClone,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("복제 ➔", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Simple Theme Setup
@Composable
fun NfcIntuitiveAppTheme(
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
