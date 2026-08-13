package com.example.pushup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.pushup.ui.theme.*
import kotlinx.coroutines.delay
import org.webrtc.*

private const val MATCH_DURATION_SECONDS = 90

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: FirestoreSignalingClient? = null
    private var matchmakingClient: MatchmakingClient? = null
    private var gameSyncClient: GameSyncClient? = null
    private var poseAnalyzer: PoseAnalyzer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eglBase = EglBase.create()

        setContent {
            PushUpTheme {
                var hasPermissions by remember { mutableStateOf(hasCameraAndMicPermission()) }
                var screen by remember { mutableStateOf(Screen.NAME_ENTRY) }
                var playerName by remember { mutableStateOf("") }
                var roomId by remember { mutableStateOf("") }
                var isCaller by remember { mutableStateOf(true) }
                var finalMyReps by remember { mutableStateOf(0) }
                var finalOpponentReps by remember { mutableStateOf(0) }

                LaunchedEffect(Unit) {
                    if (!hasPermissions) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    when {
                        screen != Screen.NAME_ENTRY && !hasCameraAndMicPermission() -> PermissionScreen {
                            hasPermissions = hasCameraAndMicPermission()
                        }
                        screen == Screen.NAME_ENTRY -> NameEntryScreen(
                            initialName = playerName,
                            onContinue = { name ->
                                playerName = name
                                screen = Screen.HOME
                            }
                        )
                        screen == Screen.HOME -> HomeScreen(
                            playerName = playerName,
                            onFindMatch = { screen = Screen.MATCHMAKING },
                            onLeaderboard = { screen = Screen.LEADERBOARD }
                        )
                        screen == Screen.MATCHMAKING -> MatchmakingScreen(
                            playerName = playerName,
                            onMatched = { rid, caller ->
                                roomId = rid
                                isCaller = caller
                                screen = Screen.CALL
                            },
                            onCancel = {
                                matchmakingClient?.cancel()
                                screen = Screen.HOME
                            }
                        )
                        screen == Screen.CALL -> CallScreen(
                            roomId = roomId,
                            isCaller = isCaller,
                            playerName = playerName,
                            eglBase = eglBase,
                            onMatchEnded = { myReps, oppReps ->
                                finalMyReps = myReps
                                finalOpponentReps = oppReps
                                screen = Screen.RESULT
                            }
                        )
                        screen == Screen.RESULT -> ResultScreen(
                            myReps = finalMyReps,
                            opponentReps = finalOpponentReps,
                            onBackToHome = { screen = Screen.HOME }
                        )
                        screen == Screen.LEADERBOARD -> LeaderboardScreen(
                            onBack = { screen = Screen.HOME }
                        )
                    }
                }
            }
        }
    }

    private fun hasCameraAndMicPermission(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cam == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    enum class Screen { NAME_ENTRY, HOME, MATCHMAKING, CALL, RESULT, LEADERBOARD }

    // ---------------- Shared bits ----------------

    @Composable
    fun ScreenScaffold(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(BgDeep, BgSurface))
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }

    @Composable
    fun PrimaryButton(text: String, emoji: String? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = Color.White)
        ) {
            if (emoji != null) {
                Text(emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }

    @Composable
    fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BgSurfaceBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }

    // ---------------- Name entry ----------------

    @Composable
    fun NameEntryScreen(initialName: String, onContinue: (String) -> Unit) {
        var name by remember { mutableStateOf(initialName) }
        ScreenScaffold {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AccentOrange),
                contentAlignment = Alignment.Center
            ) { Text("💪", fontSize = 32.sp) }
            Spacer(Modifier.height(20.dp))
            Text("PUSH-UP", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Text("CHALLENGE", style = MaterialTheme.typography.headlineLarge, color = AccentOrange)
            Spacer(Modifier.height(8.dp))
            Text(
                "Rastgele biriyle eşleş, kim daha çok basar görelim",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Adın (skor tablosunda görünecek)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentOrange,
                    unfocusedBorderColor = BgSurfaceBorder,
                    focusedLabelColor = AccentOrange,
                    unfocusedLabelColor = TextMuted,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentOrange
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("DEVAM ET", onClick = { if (name.isNotBlank()) onContinue(name.trim()) })
        }
    }

    // ---------------- Permission ----------------

    @Composable
    fun PermissionScreen(onRetry: () -> Unit) {
        ScreenScaffold {
            Text("📷", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Kamera ve mikrofon izni gerekiyor",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Rakibini görebilmen ve push-up'larının sayılabilmesi için gerekli",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("TEKRAR DENE", onClick = onRetry)
        }
    }

    // ---------------- Home ----------------

    @Composable
    fun HomeScreen(playerName: String, onFindMatch: () -> Unit, onLeaderboard: () -> Unit) {
        ScreenScaffold {
            Text("Hoş geldin,", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            Text(playerName, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(28.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BgSurfaceRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, BgSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⏱️  90 SANİYE", style = MaterialTheme.typography.titleMedium, color = AccentOrange)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "En fazla push-up yapan kazanır",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Kazanan +3 · Berabere +2 · Kaybeden +1 puan",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextFaint,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton("🔥 RASTGELE RAKİP BUL", onClick = onFindMatch)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("🏆 SKOR TABLOSU", onClick = onLeaderboard)
        }
    }

    // ---------------- Matchmaking ----------------

    @Composable
    fun MatchmakingScreen(
        playerName: String,
        onMatched: (String, Boolean) -> Unit,
        onCancel: () -> Unit
    ) {
        LaunchedEffect(Unit) {
            val client = MatchmakingClient(playerName)
            matchmakingClient = client
            client.findMatch(
                onMatched = { roomId, isCaller -> onMatched(roomId, isCaller) },
                onError = { }
            )
        }

        val transition = rememberInfiniteTransition(label = "pulse")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "rotation"
        )

        ScreenScaffold {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .rotate(rotation)
                    .border(4.dp, AccentOrange, CircleShape)
            )
            Spacer(Modifier.height(24.dp))
            Text("Rakip aranıyor…", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("Hazır ol, birazdan başlıyor", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Spacer(Modifier.height(32.dp))
            SecondaryButton("İPTAL", onClick = onCancel)
        }
    }

    // ---------------- Call / live match ----------------

    @Composable
    fun CallScreen(
        roomId: String,
        isCaller: Boolean,
        playerName: String,
        eglBase: EglBase,
        onMatchEnded: (myReps: Int, opponentReps: Int) -> Unit
    ) {
        var statusText by remember { mutableStateOf("Bağlanıyor…") }
        var myReps by remember { mutableStateOf(0) }
        var opponentReps by remember { mutableStateOf(0) }
        var matchStartMs by remember { mutableStateOf<Long?>(null) }
        var secondsRemaining by remember { mutableStateOf(MATCH_DURATION_SECONDS) }
        var matchEndedHandled by remember { mutableStateOf(false) }
        var connected by remember { mutableStateOf(false) }

        val sync = remember { GameSyncClient(roomId) }

        DisposableEffect(Unit) {
            sync.listen { startMs, duration, callerReps, calleeReps ->
                matchStartMs = startMs
                opponentReps = if (isCaller) calleeReps else callerReps
            }
            onDispose { sync.stop() }
        }

        LaunchedEffect(matchStartMs) {
            val startMs = matchStartMs ?: return@LaunchedEffect
            while (true) {
                val elapsed = (System.currentTimeMillis() - startMs) / 1000
                val remaining = (MATCH_DURATION_SECONDS - elapsed).toInt()
                secondsRemaining = remaining.coerceAtLeast(0)
                if (remaining <= 0 && !matchEndedHandled) {
                    matchEndedHandled = true
                    val won = myReps > opponentReps
                    val draw = myReps == opponentReps
                    LeaderboardClient().recordMatchResult(playerName, myReps, won, draw)
                    onMatchEnded(myReps, opponentReps)
                    break
                }
                delay(250)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val remoteRenderer = SurfaceViewRenderer(ctx)
                    val localRenderer = SurfaceViewRenderer(ctx)

                    val analyzer = PoseAnalyzer(
                        onRepCounted = { total ->
                            myReps = total
                            gameSyncClient?.updateMyReps(isCaller, total)
                        }
                    )
                    poseAnalyzer = analyzer

                    val client = WebRtcClient(
                        context = ctx,
                        eglBase = eglBase,
                        listener = object : WebRtcClient.Listener {
                            override fun onLocalIceCandidate(candidate: IceCandidate) {
                                signalingClient?.sendIceCandidate(candidate, isCaller)
                            }
                            override fun onRemoteStream(stream: MediaStream) {
                                runOnUiThread {
                                    webRtcClient?.attachRemoteRenderer(stream, remoteRenderer)
                                    statusText = "Bağlandı"
                                    if (!connected) {
                                        connected = true
                                        if (isCaller) {
                                            val startAt = System.currentTimeMillis() + 3000
                                            sync.startMatch(startAt, MATCH_DURATION_SECONDS)
                                        }
                                    }
                                }
                            }
                            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                                runOnUiThread { statusText = state.toString() }
                            }
                        }
                    )
                    webRtcClient = client
                    gameSyncClient = sync
                    signalingClient = FirestoreSignalingClient(roomId)

                    val localTrack = client.startLocalCapture(localRenderer)
                    localTrack.addSink(analyzer)
                    client.createPeerConnection(defaultIceServers())

                    if (isCaller) {
                        signalingClient?.listenForIceCandidates(fromCaller = false) {
                            client.addRemoteIceCandidate(it)
                        }
                        client.createOffer { offer ->
                            signalingClient?.sendOffer(offer)
                            signalingClient?.listenForAnswer { answer ->
                                client.setRemoteDescription(answer)
                            }
                        }
                    } else {
                        signalingClient?.listenForIceCandidates(fromCaller = true) {
                            client.addRemoteIceCandidate(it)
                        }
                        signalingClient?.listenForOffer { offer ->
                            client.setRemoteDescription(offer)
                            client.createAnswer { answer ->
                                signalingClient?.sendAnswer(answer)
                            }
                        }
                    }

                    FrameLayout(ctx).apply {
                        addView(remoteRenderer)
                        addView(localRenderer, FrameLayout.LayoutParams(300, 400).apply {
                            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                            marginEnd = 24
                            bottomMargin = 220
                        })
                    }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    poseAnalyzer?.close()
                    webRtcClient?.close()
                    signalingClient?.stopListening()
                }
            }

            // Top gradient scrim so overlay text stays legible over video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            )

            // Countdown badge
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val urgent = secondsRemaining in 1..10
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (urgent) LoseRed else Color(0xCC000000),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (urgent) LoseRed else BgSurfaceBorder)
                ) {
                    Text(
                        text = if (matchStartMs == null) "Rakip bağlanıyor…" else formatCountdown(secondsRemaining),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            ) {
                ScorePill(label = "SEN", value = myReps, accent = AccentOrange)
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                ScorePill(label = "RAKİP", value = opponentReps, accent = RivalBlue)
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x99000000),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
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

    @Composable
    fun ScorePill(label: String, value: Int, accent: Color) {
        Surface(
            color = Color(0xCC000000),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label, color = accent, style = MaterialTheme.typography.labelSmall)
                Text(value.toString(), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }

    private fun formatCountdown(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    // ---------------- Result ----------------

    @Composable
    fun ResultScreen(myReps: Int, opponentReps: Int, onBackToHome: () -> Unit) {
        val won = myReps > opponentReps
        val draw = myReps == opponentReps
        val emoji: String
        val resultText: String
        val accent: Color
        val accentDim: Color
        when {
            won -> { emoji = "🏆"; resultText = "KAZANDIN!"; accent = WinGreen; accentDim = WinGreenDim }
            draw -> { emoji = "🤝"; resultText = "BERABERE"; accent = TextMuted; accentDim = BgSurfaceRaised }
            else -> { emoji = "😤"; resultText = "KAYBETTİN"; accent = LoseRed; accentDim = LoseRedDim }
        }

        ScreenScaffold {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(accentDim),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 44.sp) }
            Spacer(Modifier.height(20.dp))
            Text(
                resultText,
                style = MaterialTheme.typography.headlineLarge,
                color = accent
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(label = "SEN", value = myReps, accent = AccentOrange, modifier = Modifier.weight(1f))
                StatCard(label = "RAKİP", value = opponentReps, accent = RivalBlue, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton("ANA SAYFAYA DÖN", onClick = onBackToHome)
        }
    }

    @Composable
    fun StatCard(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BgSurfaceRaised,
            border = androidx.compose.foundation.BorderStroke(1.dp, BgSurfaceBorder),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(value.toString(), style = MaterialTheme.typography.headlineLarge, color = accent)
            }
        }
    }

    // ---------------- Leaderboard ----------------

    @Composable
    fun LeaderboardScreen(onBack: () -> Unit) {
        var entries by remember { mutableStateOf<List<LeaderboardEntry>?>(null) }

        LaunchedEffect(Unit) {
            LeaderboardClient().fetchTopPlayers { entries = it }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgDeep, BgSurface)))
                .padding(20.dp)
        ) {
            Text("🏆 Skor Tablosu", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(20.dp))

            when {
                entries == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentOrange)
                }
                entries!!.isEmpty() -> Text(
                    "Henüz kimse maç oynamadı.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumnLeaderboard(entries!!, Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton("GERİ", onClick = onBack)
        }
    }

    @Composable
    fun LazyColumnLeaderboard(entries: List<LeaderboardEntry>, modifier: Modifier = Modifier) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(entries) { index, entry ->
                val rank = index + 1
                val medal = when (rank) {
                    1 -> MedalGold
                    2 -> MedalSilver
                    3 -> MedalBronze
                    else -> BgSurfaceBorder
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BgSurfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (rank <= 3) medal else BgSurfaceBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (rank <= 3) medal else BgSurfaceBorder),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$rank",
                                    color = if (rank <= 3) BgDeep else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(entry.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${entry.totalPoints} puan",
                                color = AccentOrange,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "en iyi ${entry.bestReps}",
                                color = TextFaint,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }

    private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
        list: List<T>,
        itemContent: @Composable (Int, T) -> Unit
    ) {
        items(list.size) { index -> itemContent(index, list[index]) }
    }

    private fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )
}
