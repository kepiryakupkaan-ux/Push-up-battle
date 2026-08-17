package com.example.pushup

import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pushup.ui.theme.*
import kotlinx.coroutines.delay
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.random.Random

private const val BOT_MATCH_DURATION_SECONDS = 90
private const val BOT_PREP_DELAY_MS = 3000L

/** CallScreen'deki private renderer/track alanlarına dokunmadan, bot maçının kendi
 * kamera/analiz kaynaklarını tutan basit bir tutucu (Compose state değil, sıradan referans -
 * gereksiz recomposition'a yol açmasın diye). */
private class BotCallRefs {
    var renderer: SurfaceViewRenderer? = null
    var track: VideoTrack? = null
    var client: WebRtcClient? = null
    var analyzer: PoseAnalyzer? = null
}

private fun formatBotCountdown(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun BotAvatar(res: Int, size: androidx.compose.ui.unit.Dp, borderColor: Color, locked: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(BgSurfaceRaised)
            .border(2.dp, borderColor, CircleShape)
    ) {
        Image(
            painter = painterResource(id = res),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(CircleShape).alpha(if (locked) 0.35f else 1f),
            contentScale = ContentScale.Crop
        )
    }
}

/** Maç ekranının üstünde duran panel: aktif rakibin fotoğrafı + ismi + canlı kapışma barı.
 * Bar, o ana kadarki TOPLAM tekrar sayısı kadar parçaya bölünür; soldan sağa önce SEN
 * (mavi), ardından RAKİP (kırmızı) kadar parça doldurulur. Her tekrarda güncellenir. */
@Composable
private fun BotTopPanel(bot: BotOpponent, botIndex: Int, myReps: Int, opponentReps: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
            .padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(res = bot.avatarRes, size = 52.dp, borderColor = LoseRed)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "RAKİP ${botIndex + 1}/${BotRoster.bots.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Text(
                    bot.displayName.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Canlı kapışma barı: toplam = SEN + RAKİP tekrar sayısı kadar eşit parça.
        // İlk myReps parça mavi (SEN), kalan opponentReps parça kırmızı (RAKİP).
        val totalParts = (myReps + opponentReps).coerceAtLeast(1)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(BgSurfaceBorder)
        ) {
            repeat(totalParts) { i ->
                val isMine = i < myReps
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isMine) RivalBlue else LoseRed)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("SEN $myReps", style = MaterialTheme.typography.labelSmall, color = RivalBlue, fontWeight = FontWeight.Bold)
            Text("${bot.displayName.uppercase()} $opponentReps", style = MaterialTheme.typography.labelSmall, color = LoseRed, fontWeight = FontWeight.Bold)
        }
    }
}

/** Bot rakiplerin sırayla listelendiği, ilerlemenin gösterildiği seçim ekranı. */
@Composable
fun MainActivity.BotLadderScreen(
    unlockedIndex: Int,
    onSelectBot: (Int) -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(scrollable = true, topAligned = true) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ Geri",
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { playClickSound(); onBack() }
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("BOT 1v1", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sırayla hepsini yen, her rakip bir öncekinden daha zorlu",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
        Spacer(Modifier.height(22.dp))

        BotRoster.bots.forEachIndexed { i, bot ->
            val locked = i > unlockedIndex
            val beaten = i < unlockedIndex
            val active = i == unlockedIndex
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = BgSurfaceRaised,
                border = BorderStroke(1.dp, if (active) AccentOrange else BgSurfaceBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable(enabled = !locked) { playClickSound(); onSelectBot(i) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BotAvatar(
                        res = bot.avatarRes,
                        size = 56.dp,
                        borderColor = if (beaten) WinGreen else if (active) AccentOrange else BgSurfaceBorder,
                        locked = locked
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${i + 1}. ${bot.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (locked) TextFaint else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                beaten -> "Yenildi ✅"
                                locked -> "Kilitli 🔒"
                                else -> "Sırada ▶"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                beaten -> WinGreen
                                locked -> TextFaint
                                else -> AccentOrange
                            }
                        )
                    }
                    if (!locked) {
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = TextFaint)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SecondaryButton("ANA MENÜ", onClick = onBack)
        Spacer(Modifier.height(8.dp))
    }
}

/** Botla tek bir maçı oynatan ekran: kendi kameran + gerçek şınav sayımı, botun ise
 * zorluk seviyesine göre zamanla artan sahte tekrar üretimi. */
@Composable
fun MainActivity.BotCallScreen(
    bot: BotOpponent,
    botIndex: Int,
    eglBase: EglBase,
    onMatchEnded: (myReps: Int, opponentReps: Int) -> Unit
) {
    var myReps by remember { mutableStateOf(0) }
    var botReps by remember { mutableStateOf(0) }
    var repBump by remember { mutableStateOf(0) }
    var botBump by remember { mutableStateOf(0) }
    var showBodyHint by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableStateOf(BOT_MATCH_DURATION_SECONDS) }
    var matchStartMs by remember { mutableStateOf<Long?>(null) }
    var matchEndedHandled by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Kamera hazırlanıyor…") }

    val refs = remember { BotCallRefs() }

    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Maça 3 saniyelik bir hazırlık payı bırak, sonra hem sayaç hem bot başlar.
    LaunchedEffect(Unit) {
        delay(BOT_PREP_DELAY_MS)
        matchStartMs = System.currentTimeMillis()
        statusText = ""
    }

    LaunchedEffect(matchStartMs) {
        val startMs = matchStartMs ?: return@LaunchedEffect
        while (true) {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            val remaining = (BOT_MATCH_DURATION_SECONDS - elapsed).toInt()
            secondsRemaining = remaining.coerceAtLeast(0)
            if (remaining <= 0 && !matchEndedHandled) {
                matchEndedHandled = true
                onMatchEnded(myReps, botReps)
                break
            }
            delay(250)
        }
    }

    // Bot simülasyonu: bot.repIntervalMs'e göre (rastgele sapmayla) tekrar üretir.
    // Zorluk arttıkça (sonraki botlarda) interval küçülür -> bot daha hızlı sayar.
    LaunchedEffect(matchStartMs) {
        val startMs = matchStartMs ?: return@LaunchedEffect
        delay(bot.startDelayMs)
        while (!matchEndedHandled) {
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed >= BOT_MATCH_DURATION_SECONDS * 1000L) break
            val jitterRange = (bot.repIntervalMs * bot.jitter).toLong().coerceAtLeast(50L)
            val waitMs = (bot.repIntervalMs + Random.nextLong(-jitterRange, jitterRange + 1)).coerceAtLeast(250L)
            delay(waitMs)
            if (matchEndedHandled) break
            botReps++
            botBump++
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        // ÖNEMLİ: Kamera 640x480 (4:3) yakalanıyor, dikey ekranda bu 480x640 (en/boy 0.75)
        // olarak görünür. Kamera kutusunu doğrudan TAM EKRAN yapıp SCALE_ASPECT_FILL ile
        // doldurtursak, kutunun oranı (ör. 0.46) video oranından (0.75) çok farklı olduğu
        // için sistem kenarlardan ciddi miktarda kırpar - iskelet noktaları ise kırpılmamış
        // ham orana göre hesaplandığından ekranda sola/sağa kaymış görünür. Kamera kutusunu
        // videonun gerçek oranına (3:4) sabitleyip ortalayarak kırpmayı sıfıra indiriyoruz,
        // böylece iskelet gerçek görüntüyle birebir örtüşüyor. Üstte/altta ince boşluk kalması
        // bunun kabul edilebilir bedeli.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.Center)
        ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    val renderer = SurfaceViewRenderer(ctx)
                    val poseOverlay = PoseOverlayView(ctx)

                    val analyzer = PoseAnalyzer(
                        onRepCounted = { total -> runOnUiThread { myReps = total; repBump++ } },
                        onLandmarks = { points, postureOk ->
                            runOnUiThread { showBodyHint = false; poseOverlay.updatePose(points, postureOk) }
                        },
                        onNoBodyDetected = {
                            runOnUiThread { showBodyHint = true; poseOverlay.clear() }
                        }
                    )
                    refs.analyzer = analyzer

                    val client = WebRtcClient(
                        context = ctx,
                        eglBase = eglBase,
                        listener = object : WebRtcClient.Listener {
                            override fun onLocalIceCandidate(candidate: IceCandidate) {}
                            override fun onRemoteVideoTrack(track: VideoTrack) {}
                            override fun onRemoteAudioTrack(track: AudioTrack) {}
                            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {}
                            override fun onError(code: String, message: String) {
                                runOnUiThread { statusText = "[$code] $message" }
                            }
                        }
                    )
                    refs.client = client

                    val track = client.startLocalCapture(
                        renderer,
                        onFrontCameraMissing = {
                            runOnUiThread {
                                statusText = "Ön kamera bulunamadı. Bu uygulama sadece ön kamerayla çalışır."
                            }
                        }
                    )
                    if (track != null) {
                        track.addSink(analyzer)
                        refs.track = track
                        runOnUiThread { statusText = "Hazırlan…" }
                    }
                    refs.renderer = renderer

                    FrameLayout(ctx).apply {
                        addView(renderer)
                        addView(poseOverlay)
                    }
                } catch (e: Throwable) {
                    Log.e("PushUpBotCall", "Bot maç ekranı kurulamadı", e)
                    runOnUiThread {
                        statusText = "Maç ekranı kurulamadı: ${e.javaClass.simpleName}: ${e.message}"
                    }
                    android.widget.FrameLayout(ctx).apply {
                        addView(android.widget.TextView(ctx).apply {
                            text = "Maç ekranı kurulamadı:\n${e.javaClass.simpleName}: ${e.message}"
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(48, 48, 48, 48)
                        })
                    }
                }
            }
        )
        }

        DisposableEffect(Unit) {
            onDispose {
                refs.analyzer?.let { refs.track?.removeSink(it) }
                refs.renderer?.let { refs.track?.removeSink(it) }
                refs.renderer?.release()
                refs.analyzer?.close()
                refs.client?.close()
                refs.renderer = null
                refs.track = null
                refs.analyzer = null
                refs.client = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            BotTopPanel(bot = bot, botIndex = botIndex, myReps = myReps, opponentReps = botReps)
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val urgent = secondsRemaining in 1..10
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (urgent) LoseRed else Color(0xCC000000),
                border = BorderStroke(1.dp, if (urgent) LoseRed else BgSurfaceBorder)
            ) {
                Text(
                    text = if (matchStartMs == null) "Hazırlan…" else formatBotCountdown(secondsRemaining),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }

        Row(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            ScorePill(label = "SEN", value = myReps, accent = RivalBlue, bumpKey = repBump)
        }
        Row(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ScorePill(label = bot.displayName.take(8).uppercase(), value = botReps, accent = LoseRed, bumpKey = botBump)
        }

        if (showBodyHint) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xCC000000),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
            ) {
                Text(
                    "Kolun tamamen görünsün 👀",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0x99000000),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        ) {
            Text(
                text = statusText,
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Bot maçı bitince gösterilen sonuç ekranı: kazanınca sıradaki rakibe geçiş, kaybedince tekrar dene. */
@Composable
fun MainActivity.BotResultScreen(
    bot: BotOpponent,
    botIndex: Int,
    myReps: Int,
    opponentReps: Int,
    won: Boolean,
    onRetry: () -> Unit,
    onNextBot: () -> Unit,
    onBackToLadder: () -> Unit
) {
    val isLast = botIndex == BotRoster.bots.lastIndex
    ScreenScaffold {
        BotAvatar(res = bot.avatarRes, size = 84.dp, borderColor = if (won) WinGreen else LoseRed)
        Spacer(Modifier.height(16.dp))
        Text(
            if (won) "KAZANDIN 🎉" else "KAYBETTİN 😕",
            style = MaterialTheme.typography.headlineMedium,
            color = if (won) WinGreen else LoseRed,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(bot.displayName, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SEN", style = MaterialTheme.typography.labelSmall, color = RivalBlue)
                Text("$myReps", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bot.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, color = LoseRed)
                Text("$opponentReps", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(32.dp))

        if (won) {
            if (isLast) {
                Text("Tüm rakipleri yendin! 🏆", style = MaterialTheme.typography.titleMedium, color = MedalGold)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("RAKİP LİSTESİ", onClick = onBackToLadder)
            } else {
                PrimaryButton("SONRAKİ RAKİP: ${BotRoster.bots[botIndex + 1].displayName.uppercase()}", onClick = onNextBot)
                Spacer(Modifier.height(12.dp))
                SecondaryButton("RAKİP LİSTESİ", onClick = onBackToLadder)
            }
        } else {
            PrimaryButton("TEKRAR DENE", onClick = onRetry)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("RAKİP LİSTESİ", onClick = onBackToLadder)
        }
    }
}
