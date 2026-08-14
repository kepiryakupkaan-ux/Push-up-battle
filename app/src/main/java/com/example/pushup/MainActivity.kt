package com.example.pushup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.pushup.ui.theme.*
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import org.webrtc.*
import kotlin.random.Random

private const val MATCH_DURATION_SECONDS = 90

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private lateinit var sessionManager: SessionManager
    private val authClient = AuthClient()

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
        sessionManager = SessionManager(this)

        setContent {
            PushUpTheme {
                var hasPermissions by remember { mutableStateOf(hasCameraAndMicPermission()) }
                var screen by remember { mutableStateOf(Screen.SPLASH) }
                var session by remember { mutableStateOf<AuthSession?>(null) }
                var authMessage by remember { mutableStateOf<String?>(null) }
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

                // Açılışta kayıtlı oturum var mı diye bak; varsa sunucudan doğrula.
                LaunchedEffect(Unit) {
                    val saved = sessionManager.load()
                    if (saved == null) {
                        screen = Screen.LOGIN
                    } else {
                        authClient.validateSession(saved.username, saved.sessionToken) { ok, displayName, photo ->
                            if (ok) {
                                session = saved.copy(
                                    displayName = displayName ?: saved.displayName,
                                    photoBase64 = photo ?: saved.photoBase64
                                )
                                screen = Screen.HOME
                            } else {
                                sessionManager.clear()
                                authMessage = "Oturumun sona ermiş, tekrar giriş yap."
                                screen = Screen.LOGIN
                            }
                        }
                    }
                }

                // Hesabına başka bir cihazdan giriş yapılırsa anında dışarı at.
                DisposableEffect(session?.username, session?.sessionToken) {
                    val current = session
                    if (current == null) {
                        onDispose { }
                    } else {
                        val registration: ListenerRegistration = authClient.listenForKick(
                            current.username, current.sessionToken
                        ) {
                            runOnUiThread {
                                sessionManager.clear()
                                session = null
                                authMessage = "Hesabına başka bir cihazdan giriş yapıldı."
                                screen = Screen.LOGIN
                            }
                        }
                        onDispose { registration.remove() }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    when {
                        screen == Screen.SPLASH -> SplashScreen()

                        screen == Screen.LOGIN -> LoginScreen(
                            message = authMessage,
                            onLogin = { username, password, onError ->
                                authClient.login(username, password) { result ->
                                    result.onSuccess { s ->
                                        sessionManager.save(s)
                                        session = s
                                        authMessage = null
                                        screen = Screen.HOME
                                    }.onFailure { e -> onError(authErrorText(e)) }
                                }
                            },
                            onGoRegister = { authMessage = null; screen = Screen.REGISTER }
                        )

                        screen == Screen.REGISTER -> RegisterScreen(
                            onRegister = { username, password, photoBase64, onError ->
                                authClient.register(username, password, photoBase64) { result ->
                                    result.onSuccess { s ->
                                        sessionManager.save(s)
                                        session = s
                                        screen = Screen.HOME
                                    }.onFailure { e -> onError(authErrorText(e)) }
                                }
                            },
                            onGoLogin = { screen = Screen.LOGIN }
                        )

                        screen != Screen.LOGIN && screen != Screen.REGISTER && screen != Screen.SPLASH && !hasCameraAndMicPermission() ->
                            PermissionScreen { hasPermissions = hasCameraAndMicPermission() }

                        screen == Screen.HOME && session != null -> HomeScreen(
                            session = session!!,
                            onFindMatch = { screen = Screen.MATCHMAKING },
                            onLeaderboard = { screen = Screen.LEADERBOARD },
                            onPhotoChanged = { newPhoto ->
                                session = session!!.copy(photoBase64 = newPhoto)
                                sessionManager.save(session!!)
                            },
                            onLogout = {
                                val current = session
                                session = null
                                authMessage = null
                                screen = Screen.LOGIN
                                sessionManager.clear()
                                if (current != null) authClient.logout(current.username)
                            }
                        )

                        screen == Screen.MATCHMAKING && session != null -> MatchmakingScreen(
                            playerName = session!!.displayName,
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

                        screen == Screen.CALL && session != null -> CallScreen(
                            roomId = roomId,
                            isCaller = isCaller,
                            session = session!!,
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

                        else -> SplashScreen()
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

    enum class Screen { SPLASH, LOGIN, REGISTER, HOME, MATCHMAKING, CALL, RESULT, LEADERBOARD }

    private fun authErrorText(e: Throwable): String = when (e.message) {
        "username_taken" -> "Bu kullanıcı adı zaten alınmış."
        "invalid_username" -> "Geçerli bir kullanıcı adı gir."
        "not_found" -> "Böyle bir kullanıcı bulunamadı."
        "wrong_password" -> "Şifre yanlış."
        else -> "Bir şeyler ters gitti, tekrar dene."
    }

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
    fun PrimaryButton(text: String, emoji: String? = null, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
        Button(
            onClick = onClick,
            enabled = enabled,
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

    @Composable
    fun authFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentOrange,
        unfocusedBorderColor = BgSurfaceBorder,
        focusedLabelColor = AccentOrange,
        unfocusedLabelColor = TextMuted,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = AccentOrange
    )

    @Composable
    fun AvatarCircle(photoBase64: String?, size: androidx.compose.ui.unit.Dp, placeholder: String, borderColor: Color, onClick: (() -> Unit)? = null) {
        val bmp: Bitmap? = remember(photoBase64) { ImageUtils.base64ToBitmap(photoBase64) }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(BgSurfaceRaised)
                .border(2.dp, borderColor, CircleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(placeholder, fontSize = (size.value / 2.6f).sp)
            }
        }
    }

    // ---------------- Splash ----------------

    @Composable
    fun SplashScreen() {
        ScreenScaffold {
            CircularProgressIndicator(color = AccentOrange)
            Spacer(Modifier.height(16.dp))
            Text("Yükleniyor…", color = TextMuted)
        }
    }

    // ---------------- Login / Register ----------------

    @Composable
    fun LoginScreen(
        message: String?,
        onLogin: (username: String, password: String, onError: (String) -> Unit) -> Unit,
        onGoRegister: () -> Unit
    ) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(message) }

        ScreenScaffold {
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(AccentOrange),
                contentAlignment = Alignment.Center
            ) { Text("💪", fontSize = 32.sp) }
            Spacer(Modifier.height(20.dp))
            Text("PUSH-UP", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Text("CHALLENGE", style = MaterialTheme.typography.headlineLarge, color = AccentOrange)
            Spacer(Modifier.height(28.dp))

            if (error != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = LoseRedDim, modifier = Modifier.fillMaxWidth()) {
                    Text(error!!, color = LoseRed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Kullanıcı adı") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Şifre") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (loading) "GİRİŞ YAPILIYOR…" else "GİRİŞ YAP",
                enabled = !loading,
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        error = "Kullanıcı adı ve şifre gerekli"
                        return@PrimaryButton
                    }
                    loading = true
                    error = null
                    onLogin(username.trim(), password) { err -> loading = false; error = err }
                }
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton("HESABIN YOK MU? KAYIT OL", onClick = onGoRegister)
        }
    }

    @Composable
    fun RegisterScreen(
        onRegister: (username: String, password: String, photoBase64: String?, onError: (String) -> Unit) -> Unit,
        onGoLogin: () -> Unit
    ) {
        val context = LocalContext.current
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var photoBase64 by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) photoBase64 = ImageUtils.uriToProfileBase64(context, uri)
        }

        ScreenScaffold {
            Text("HESAP OLUŞTUR", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(20.dp))

            AvatarCircle(
                photoBase64 = photoBase64,
                size = 88.dp,
                placeholder = "➕📷",
                borderColor = BgSurfaceBorder,
                onClick = { pickPhoto.launch("image/*") }
            )
            Spacer(Modifier.height(6.dp))
            Text("profil fotoğrafı (opsiyonel, dokunup seç)", style = MaterialTheme.typography.labelSmall, color = TextFaint, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            if (error != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = LoseRedDim, modifier = Modifier.fillMaxWidth()) {
                    Text(error!!, color = LoseRed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Kullanıcı adı") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Şifre") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm, onValueChange = { confirm = it },
                label = { Text("Şifre (tekrar)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            PrimaryButton(
                text = if (loading) "OLUŞTURULUYOR…" else "HESAP OLUŞTUR",
                enabled = !loading,
                onClick = {
                    val uname = username.trim()
                    when {
                        !uname.matches(Regex("^[\\p{L}0-9_]{3,20}$")) ->
                            error = "Kullanıcı adı 3-20 karakter olmalı, sadece harf/rakam/_ kullanılabilir"
                        password.length < 4 -> error = "Şifre en az 4 karakter olmalı"
                        password != confirm -> error = "Şifreler eşleşmiyor"
                        else -> {
                            loading = true
                            error = null
                            onRegister(uname, password, photoBase64) { err -> loading = false; error = err }
                        }
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton("ZATEN HESABIN VAR MI? GİRİŞ YAP", onClick = onGoLogin)
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
    fun HomeScreen(
        session: AuthSession,
        onFindMatch: () -> Unit,
        onLeaderboard: () -> Unit,
        onPhotoChanged: (String) -> Unit,
        onLogout: () -> Unit
    ) {
        val context = LocalContext.current
        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val b64 = ImageUtils.uriToProfileBase64(context, uri)
                if (b64 != null) {
                    authClient.updatePhoto(session.username, b64)
                    onPhotoChanged(b64)
                }
            }
        }

        ScreenScaffold {
            AvatarCircle(
                photoBase64 = session.photoBase64,
                size = 88.dp,
                placeholder = "📷",
                borderColor = AccentOrange,
                onClick = { pickPhoto.launch("image/*") }
            )
            Spacer(Modifier.height(6.dp))
            Text("değiştirmek için dokun", style = MaterialTheme.typography.labelSmall, color = TextFaint)
            Spacer(Modifier.height(16.dp))
            Text("Hoş geldin,", style = MaterialTheme.typography.bodyLarge, color = TextMuted)
            Text(session.displayName, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
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
                        "Kazanan +3 · Berabere +2 · Kaybeden +0 puan",
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
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onLogout) {
                Text("Çıkış yap", color = TextFaint, style = MaterialTheme.typography.bodyMedium)
            }
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

    private fun connectionStateText(state: PeerConnection.PeerConnectionState?): String = when (state) {
        PeerConnection.PeerConnectionState.NEW -> "Hazırlanıyor…"
        PeerConnection.PeerConnectionState.CONNECTING -> "Bağlanıyor…"
        PeerConnection.PeerConnectionState.CONNECTED -> "Bağlandı ✅"
        PeerConnection.PeerConnectionState.DISCONNECTED -> "Bağlantı zayıf, toparlanmaya çalışıyor…"
        PeerConnection.PeerConnectionState.FAILED -> "Bağlantı kurulamadı ❌"
        PeerConnection.PeerConnectionState.CLOSED -> "Bağlantı kapandı"
        else -> "Bağlanıyor…"
    }

    @Composable
    fun CallScreen(
        roomId: String,
        isCaller: Boolean,
        session: AuthSession,
        eglBase: EglBase,
        onMatchEnded: (myReps: Int, opponentReps: Int) -> Unit
    ) {
        val playerName = session.displayName
        var statusText by remember { mutableStateOf("Rakip bağlanıyor…") }
        var myReps by remember { mutableStateOf(0) }
        var opponentReps by remember { mutableStateOf(0) }
        var matchStartMs by remember { mutableStateOf<Long?>(null) }
        var secondsRemaining by remember { mutableStateOf(MATCH_DURATION_SECONDS) }
        var matchEndedHandled by remember { mutableStateOf(false) }
        var connected by remember { mutableStateOf(false) }
        var showBodyHint by remember { mutableStateOf(false) }
        var repBump by remember { mutableStateOf(0) }

        val sync = remember { GameSyncClient(roomId) }

        DisposableEffect(Unit) {
            sync.listen { startMs, _, callerReps, calleeReps ->
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
                    LeaderboardClient().recordMatchResult(session.username, myReps, won, draw, session.photoBase64)
                    onMatchEnded(myReps, opponentReps)
                    break
                }
                delay(250)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
            var overlayView by remember { mutableStateOf<PoseOverlayView?>(null) }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val remoteRenderer = SurfaceViewRenderer(ctx)
                    val localRenderer = SurfaceViewRenderer(ctx)
                    val poseOverlay = PoseOverlayView(ctx)
                    overlayView = poseOverlay

                    val analyzer = PoseAnalyzer(
                        onRepCounted = { total ->
                            runOnUiThread {
                                myReps = total
                                repBump++
                            }
                            gameSyncClient?.updateMyReps(isCaller, total)
                        },
                        onLandmarks = { points, postureOk ->
                            runOnUiThread {
                                showBodyHint = false
                                poseOverlay.updatePose(points, postureOk)
                            }
                        },
                        onNoBodyDetected = {
                            runOnUiThread {
                                showBodyHint = true
                                poseOverlay.clear()
                            }
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
                            override fun onRemoteVideoTrack(track: VideoTrack) {
                                runOnUiThread {
                                    webRtcClient?.attachRemoteVideoTrack(track, remoteRenderer)
                                    if (!connected) {
                                        connected = true
                                        statusText = "Bağlandı ✅"
                                        if (isCaller) {
                                            val startAt = System.currentTimeMillis() + 3000
                                            sync.startMatch(startAt, MATCH_DURATION_SECONDS)
                                        }
                                    }
                                }
                            }
                            override fun onRemoteAudioTrack(track: AudioTrack) {
                                // Gelen ses otomatik olarak cihaz hoparlöründen çalar, ek işlem gerekmiyor.
                            }
                            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                                runOnUiThread {
                                    if (!connected || state != PeerConnection.PeerConnectionState.CONNECTED) {
                                        statusText = connectionStateText(state)
                                    }
                                }
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
                        val localSize = FrameLayout.LayoutParams(340, 440).apply {
                            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                            marginEnd = 20
                            bottomMargin = 200
                        }
                        addView(localRenderer, localSize)
                        addView(poseOverlay, FrameLayout.LayoutParams(localSize))
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
                ScorePill(label = "SEN", value = myReps, accent = AccentOrange, bumpKey = repBump)
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                ScorePill(label = "RAKİP", value = opponentReps, accent = RivalBlue, bumpKey = opponentReps)
            }

            if (showBodyHint) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 250.dp)
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
    fun ScorePill(label: String, value: Int, accent: Color, bumpKey: Int) {
        val scale = remember { Animatable(1f) }
        LaunchedEffect(bumpKey) {
            if (bumpKey > 0) {
                scale.animateTo(1.25f, animationSpec = tween(90))
                scale.animateTo(1f, animationSpec = tween(140))
            }
        }
        Surface(
            color = Color(0xCC000000),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent),
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
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
        val pointsText: String
        when {
            won -> { emoji = "🏆"; resultText = "KAZANDIN!"; accent = WinGreen; accentDim = WinGreenDim; pointsText = "+3 puan kazandın 🎉" }
            draw -> { emoji = "🤝"; resultText = "BERABERE"; accent = TextMuted; accentDim = BgSurfaceRaised; pointsText = "+2 puan kazandın" }
            else -> { emoji = "😤"; resultText = "KAYBETTİN"; accent = LoseRed; accentDim = LoseRedDim; pointsText = "Bu sefer puan yok - kaybetme puan kazandırmaz 💪" }
        }

        val scale = remember { Animatable(0.4f) }
        LaunchedEffect(Unit) {
            scale.animateTo(
                1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        val shakeOffset = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            if (!won && !draw) {
                delay(250)
                listOf(-18f, 16f, -12f, 8f, -4f, 0f).forEach { target ->
                    shakeOffset.animateTo(target, animationSpec = tween(55))
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            ScreenScaffold {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .graphicsLayer {
                            scaleX = scale.value; scaleY = scale.value
                            translationX = shakeOffset.value
                        }
                        .clip(CircleShape)
                        .background(accentDim),
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = 44.sp) }
                Spacer(Modifier.height(20.dp))
                Text(resultText, style = MaterialTheme.typography.headlineLarge, color = accent)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(label = "SEN", value = myReps, accent = AccentOrange, modifier = Modifier.weight(1f))
                    StatCard(label = "RAKİP", value = opponentReps, accent = RivalBlue, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                Text(pointsText, style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                PrimaryButton("ANA SAYFAYA DÖN", onClick = onBackToHome)
            }

            if (won) {
                ConfettiBurst(modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    fun ConfettiBurst(modifier: Modifier = Modifier) {
        val particles = remember {
            List(42) {
                ConfettiParticle(
                    startX = Random.nextFloat(),
                    velocityX = (Random.nextFloat() - 0.5f) * 1.6f,
                    velocityY = -(Random.nextFloat() * 1.1f + 0.5f),
                    color = confettiColors[Random.nextInt(confettiColors.size)],
                    rotationSpeed = (Random.nextFloat() - 0.5f) * 720f,
                    particleSize = Random.nextFloat() * 8f + 6f
                )
            }
        }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, animationSpec = tween(1800, easing = LinearOutSlowInEasing))
        }
        Canvas(modifier = modifier) {
            val t = progress.value
            val w = size.width
            val h = size.height
            particles.forEach { p ->
                val x = (p.startX * w) + p.velocityX * w * t
                val y = h * 0.12f + p.velocityY * h * 0.4f * t + h * 0.55f * t * t
                val alpha = (1f - t).coerceIn(0f, 1f)
                drawRotate(degrees = p.rotationSpeed * t, pivot = Offset(x, y)) {
                    drawRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(x - p.particleSize / 2, y - p.particleSize / 2),
                        size = androidx.compose.ui.geometry.Size(p.particleSize, p.particleSize * 1.6f)
                    )
                }
            }
        }
    }

    data class ConfettiParticle(
        val startX: Float,
        val velocityX: Float,
        val velocityY: Float,
        val color: Color,
        val rotationSpeed: Float,
        val particleSize: Float
    )

    private val confettiColors = listOf(WinGreen, AccentOrange, RivalBlue, MedalGold, Color.White)

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
                            Spacer(Modifier.width(10.dp))
                            AvatarCircle(
                                photoBase64 = entry.photoBase64.ifBlank { null },
                                size = 32.dp,
                                placeholder = "🙂",
                                borderColor = BgSurfaceBorder
                            )
                            Spacer(Modifier.width(10.dp))
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
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )
}
