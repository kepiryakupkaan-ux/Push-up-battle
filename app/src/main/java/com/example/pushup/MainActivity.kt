package com.example.pushup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as drawRotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
private const val RECONNECT_GRACE_MS = 10_000L

class MainActivity : ComponentActivity() {

    private lateinit var eglBase: EglBase
    private lateinit var sessionManager: SessionManager
    private val authClient = AuthClient()
    private val leaderboardClient = LeaderboardClient()
    private val friendsClient = FriendsClient()
    private val matchHistoryClient = MatchHistoryClient()
    private val matchInviteClient = MatchInviteClient()

    // ---------------- Ses efektleri (tık/onay sesleri) ----------------
    private var soundPool: SoundPool? = null
    private var clickSoundId: Int = 0
    private var confirmSoundId: Int = 0

    private fun initSounds() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        clickSoundId = soundPool?.load(this, R.raw.click_pop, 1) ?: 0
        confirmSoundId = soundPool?.load(this, R.raw.confirm_pop, 1) ?: 0
    }

    /** Küçük buton tıklama sesi ("tık/dop"). */
    fun playClickSound() {
        soundPool?.play(clickSoundId, 0.7f, 0.7f, 0, 0, 1f)
    }

    /** Ana aksiyonlar (Rastgele Rakip Bul, Kabul Et gibi) için biraz daha dolgun ses. */
    fun playConfirmSound() {
        soundPool?.play(confirmSoundId, 0.9f, 0.9f, 0, 0, 1f)
    }

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: FirestoreSignalingClient? = null
    private var matchmakingClient: MatchmakingClient? = null
    private var gameSyncClient: GameSyncClient? = null
    private var poseAnalyzer: PoseAnalyzer? = null

    // ÖNEMLİ: SurfaceViewRenderer'lar ve canlı track referansları, maç bitince/ekrandan
    // çıkılınca düzgünce release edilebilsin diye burada tutuluyor (bkz. CallScreen'in
    // DisposableEffect(Unit) onDispose bloğu). Bunlar olmadan ikinci bir maça
    // girildiğinde "MediaStreamTrack has been disposed" tarzı native çökmeler oluyordu -
    // eski renderer'lar hiç release edilmeden yeni bir WebRtcClient/PeerConnectionFactory
    // oluşturulmaya çalışılıyordu.
    private var callLocalRenderer: SurfaceViewRenderer? = null
    private var callRemoteRenderer: SurfaceViewRenderer? = null
    private var callLocalTrack: VideoTrack? = null
    private var callRemoteTrack: VideoTrack? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        eglBase = EglBase.create()
        sessionManager = SessionManager(this)
        initSounds()

        setContent {
            PushUpTheme {
                var hasPermissions by remember { mutableStateOf(hasCameraAndMicPermission()) }
                val online = rememberIsOnline()
                var screen by remember { mutableStateOf(Screen.SPLASH) }
                var session by remember { mutableStateOf<AuthSession?>(null) }
                var authMessage by remember { mutableStateOf<String?>(null) }
                var roomId by remember { mutableStateOf("") }
                var isCaller by remember { mutableStateOf(true) }
                var finalMyReps by remember { mutableStateOf(0) }
                var finalOpponentReps by remember { mutableStateOf(0) }
                var finalOpponentUsername by remember { mutableStateOf<String?>(null) }
                var finalOpponentDisplayName by remember { mutableStateOf<String?>(null) }
                var matchOutcome by remember { mutableStateOf<MatchResultOutcome?>(null) }
                var pendingInvite by remember { mutableStateOf<MatchInvite?>(null) }
                var myStats by remember { mutableStateOf<LeaderboardEntry?>(null) }

                LaunchedEffect(Unit) {
                    if (!hasPermissions) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                }

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

                // Bana gelen bekleyen maç davetlerini dinle (ana sayfada banner göstermek için).
                DisposableEffect(session?.username) {
                    val current = session
                    if (current == null) {
                        onDispose { }
                    } else {
                        val reg = matchInviteClient.listenForIncoming(current.username) { invite ->
                            runOnUiThread { pendingInvite = invite }
                        }
                        onDispose { reg.remove() }
                    }
                }

                // Kendi istatistiklerimi (elo/lig çerçevesi için) çek.
                LaunchedEffect(session?.username, screen) {
                    val current = session
                    if (current != null && (screen == Screen.HOME || screen == Screen.PROFILE)) {
                        leaderboardClient.fetchPlayerStats(current.username) { myStats = it }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = BgDeep) {
                    when {
                        !online -> NoInternetScreen(onExit = { finishAndRemoveTask() })

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
                            myStats = myStats,
                            pendingInvite = pendingInvite,
                            onFindMatch = { screen = Screen.MATCHMAKING },
                            onLeaderboard = { screen = Screen.LEADERBOARD },
                            onProfile = { screen = Screen.PROFILE },
                            onFriends = { screen = Screen.FRIENDS },
                            onPhotoChanged = { newPhoto ->
                                session = session!!.copy(photoBase64 = newPhoto)
                                sessionManager.save(session!!)
                            },
                            onAcceptInvite = { invite ->
                                matchInviteClient.respond(invite.id, true)
                                pendingInvite = null
                                roomId = invite.roomId
                                isCaller = false
                                screen = Screen.CALL
                            },
                            onDeclineInvite = { invite ->
                                matchInviteClient.respond(invite.id, false)
                                pendingInvite = null
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
                            photoBase64 = session!!.photoBase64,
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
                            onMatchOutcome = { outcome -> matchOutcome = outcome },
                            onMatchEnded = { myReps, oppReps, oppUsername, oppDisplayName ->
                                finalMyReps = myReps
                                finalOpponentReps = oppReps
                                finalOpponentUsername = oppUsername
                                finalOpponentDisplayName = oppDisplayName
                                screen = Screen.RESULT
                            },
                            onConnectionLost = {
                                matchOutcome = null
                                screen = Screen.HOME
                            }
                        )

                        screen == Screen.RESULT -> ResultScreen(
                            myReps = finalMyReps,
                            opponentReps = finalOpponentReps,
                            opponentUsername = finalOpponentUsername,
                            opponentDisplayName = finalOpponentDisplayName,
                            outcome = matchOutcome,
                            session = session,
                            pendingInvite = pendingInvite,
                            onAcceptInvite = { invite ->
                                matchInviteClient.respond(invite.id, true)
                                pendingInvite = null
                                matchOutcome = null
                                roomId = invite.roomId
                                isCaller = false
                                screen = Screen.CALL
                            },
                            onDeclineInvite = { invite ->
                                matchInviteClient.respond(invite.id, false)
                                pendingInvite = null
                            },
                            onRematchAccepted = { rid ->
                                roomId = rid
                                isCaller = true
                                matchOutcome = null
                                screen = Screen.CALL
                            },
                            onBackToHome = {
                                matchOutcome = null
                                screen = Screen.HOME
                            }
                        )

                        screen == Screen.LEADERBOARD -> LeaderboardScreen(onBack = { screen = Screen.HOME })

                        screen == Screen.PROFILE && session != null -> ProfileScreen(
                            session = session!!,
                            myStats = myStats,
                            onBack = { screen = Screen.HOME }
                        )

                        screen == Screen.FRIENDS && session != null -> FriendsScreen(
                            session = session!!,
                            onBack = { screen = Screen.HOME },
                            onInviteSent = { rid ->
                                roomId = rid
                                isCaller = true
                                screen = Screen.CALL
                            }
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

    override fun onDestroy() {
        super.onDestroy()
        soundPool?.release()
        soundPool = null
    }

    /**
     * TEŞHİS ARACI: gerçek bir çökme (uygulamanın aniden kapanması) olduğunda, Android
     * varsayılan olarak sadece logcat'e yazar - PC/adb olmadan bunu görmenin bir yolu yoktu.
     * Bu, herhangi bir yakalanmamış (uncaught) exception'ı, telefonun kendi dosya
     * yöneticisinden açılabilecek düz bir metin dosyasına ("Android/data/com.example.pushup/
     * files/son_cokme.txt") yazıyor - tam hatayı ve hangi satırda patladığını (stack trace)
     * içerir. Bir sonraki çökmede o dosyayı açıp içeriğini kopyalaman yeterli.
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(dir, "son_cokme.txt")
                file.writeText(
                    "Zaman: ${java.util.Date()}\n" +
                    "Thread: ${thread.name}\n\n" +
                    sw.toString()
                )
            } catch (_: Throwable) {
                // Loglama sırasında bir şey ters giderse bile orijinal çökme akışını bozma.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    enum class Screen { SPLASH, LOGIN, REGISTER, HOME, MATCHMAKING, CALL, RESULT, LEADERBOARD, PROFILE, FRIENDS }

    private fun authErrorText(e: Throwable): String = when (e.message) {
        "username_taken" -> "Bu kullanıcı adı zaten alınmış."
        "invalid_username" -> "Geçerli bir kullanıcı adı gir."
        "not_found" -> "Böyle bir kullanıcı bulunamadı."
        "wrong_password" -> "Şifre yanlış."
        else -> "Bir şeyler ters gitti, tekrar dene."
    }

    private fun leagueColor(elo: Long?): Color {
        val hex = EloUtils.leagueFor(elo ?: EloUtils.STARTING_ELO).colorHex
        return Color(android.graphics.Color.parseColor(hex))
    }

    // ---------------- Shared bits ----------------

    @Composable
    fun ScreenScaffold(
        scrollable: Boolean = false,
        backgroundRes: Int? = null,
        topAligned: Boolean = false,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (backgroundRes != null) {
                Image(
                    painter = painterResource(id = backgroundRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Dark scrim so text/buttons stay readable over the photo.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC0B0D12), Color(0xE60B0D12), Color(0xF20B0D12))
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(BgDeep, BgSurface)))
                )
            }
            val base = Modifier.fillMaxSize().padding(24.dp)
            Column(
                modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (topAligned) Arrangement.Top else Arrangement.Center,
                content = content
            )
        }
    }

    @Composable
    fun PrimaryButton(
        text: String,
        emoji: String? = null,
        iconRes: Int? = null,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        height: Dp = 58.dp
    ) {
        val haptics = LocalHapticFeedback.current
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.96f else 1f, animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f), label = "primaryBtnScale")

        Button(
            onClick = {
                playConfirmSound()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier.fillMaxWidth().height(height).scale(scale),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange, contentColor = Color.White)
        ) {
            if (iconRes != null) { IconImg(iconRes, 22.dp); Spacer(Modifier.width(10.dp)) }
            else if (emoji != null) { Text(emoji, fontSize = 18.sp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }

    @Composable
    fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val haptics = LocalHapticFeedback.current
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.96f else 1f, animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f), label = "secondaryBtnScale")

        OutlinedButton(
            onClick = {
                playClickSound()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            interactionSource = interactionSource,
            modifier = modifier.fillMaxWidth().height(56.dp).scale(scale),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BgSurfaceBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
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
    fun AvatarCircle(
        photoBase64: String?,
        size: Dp,
        placeholder: String,
        borderColor: Color,
        onClick: (() -> Unit)? = null
    ) {
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
                Image(
                    painter = painterResource(id = R.drawable.ic_default_avatar),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.7f).clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    @Composable
    fun ProfileQuickStat(label: String, value: String, modifier: Modifier = Modifier) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextFaint)
        }
    }

    // ---------------- Emoji yerine kullanılan gerçek görsel ikonlar ----------------
    // Bu ikonlar drawable/ klasöründeki PNG dosyalarından geliyor (emoji YOK).
    // Şu an yer tutucu (placeholder) görseller kullanılıyor; ChatGPT'de ürettiğin
    // PNG'leri gönderdiğinde aynı dosya adlarıyla değiştirip gerçek görselleri koyacağım.

    @Composable
    fun IconImg(res: Int, size: Dp, modifier: Modifier = Modifier) {
        Image(
            painter = painterResource(id = res),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    }

    /** Ana ekrandaki geniş, ikon rozetli gösterge paneli kartı (Skor Tablosu / Arkadaşlar). */
    @Composable
    fun DashboardTile(label: String, iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
        val haptics = LocalHapticFeedback.current
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.95f else 1f, animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f), label = "tileScale")

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BgSurfaceRaised,
            border = BorderStroke(1.dp, BgSurfaceBorder),
            modifier = modifier.scale(scale).clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    playClickSound()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(AccentOrangeDim),
                    contentAlignment = Alignment.Center
                ) { IconImg(iconRes, 28.dp) }
                Spacer(Modifier.height(10.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            }
        }
    }


    @Composable
    fun LeagueBadgeChip(elo: Long?) {
        val league = EloUtils.leagueFor(elo ?: EloUtils.STARTING_ELO)
        val color = leagueColor(elo)
        Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.18f), border = BorderStroke(1.dp, color)) {
            Text(
                "${league.displayName} · ${elo ?: EloUtils.STARTING_ELO} Elo",
                color = color,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }

    // ---------------- İnternet yok ----------------

    @Composable
    fun NoInternetScreen(onExit: () -> Unit) {
        LaunchedEffect(Unit) { AppError.log(AppError.NET_OFFLINE, "app foreground, no internet") }
        ScreenScaffold {
            Text("📡", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text("İnternet bağlantısı yok", style = MaterialTheme.typography.titleLarge, color = TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = LoseRedDim) {
                Text("[${AppError.NET_OFFLINE}]", color = LoseRed, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("Bu uygulama internet olmadan çalışamaz. Bağlantını kontrol edip tekrar dene.", style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("UYGULAMAYI KAPAT", onClick = onExit)
        }
    }

    // ---------------- Splash ----------------

    @Composable
    fun SplashScreen() {
        ScreenScaffold(backgroundRes = R.drawable.bg_gym_dark) {
            AppLogoMark(size = 88.dp)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = AccentOrange)
            Spacer(Modifier.height(16.dp))
            Text("Yükleniyor…", color = TextMuted)
        }
    }

    /** Uygulama logosu: turuncu dambıl ikonu, koyu yuvarlatılmış kare zemin üzerinde. */
    @Composable
    fun AppLogoMark(size: Dp) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 3.2f))
                .background(Brush.verticalGradient(listOf(BgSurfaceRaised, BgDeep)))
                .border(1.dp, BgSurfaceBorder, RoundedCornerShape(size / 3.2f)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = "Push-Up Battle logosu",
                modifier = Modifier.size(size * 0.66f)
            )
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

        ScreenScaffold(backgroundRes = R.drawable.bg_gym_dark) {
            AppLogoMark(size = 84.dp)
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
                value = username, onValueChange = { username = it }, label = { Text("Kullanıcı adı") },
                singleLine = true, shape = RoundedCornerShape(14.dp), colors = authFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Şifre") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp), colors = authFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = if (loading) "GİRİŞ YAPILIYOR…" else "GİRİŞ YAP",
                enabled = !loading,
                onClick = {
                    if (username.isBlank() || password.isBlank()) { error = "Kullanıcı adı ve şifre gerekli"; return@PrimaryButton }
                    loading = true; error = null
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

        ScreenScaffold(scrollable = true, backgroundRes = R.drawable.bg_gym_dark) {
            AppLogoMark(size = 60.dp)
            Spacer(Modifier.height(14.dp))
            Text("HESAP OLUŞTUR", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(20.dp))

            AvatarCircle(photoBase64 = photoBase64, size = 88.dp, placeholder = "➕📷", borderColor = BgSurfaceBorder, onClick = { pickPhoto.launch("image/*") })
            Spacer(Modifier.height(6.dp))
            Text("profil fotoğrafı (opsiyonel, dokunup seç)", style = MaterialTheme.typography.labelSmall, color = TextFaint, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            if (error != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = LoseRedDim, modifier = Modifier.fillMaxWidth()) {
                    Text(error!!, color = LoseRed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Kullanıcı adı") }, singleLine = true, shape = RoundedCornerShape(14.dp), colors = authFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Şifre") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), colors = authFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = confirm, onValueChange = { confirm = it }, label = { Text("Şifre (tekrar)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp), colors = authFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            PrimaryButton(
                text = if (loading) "OLUŞTURULUYOR…" else "HESAP OLUŞTUR",
                enabled = !loading,
                onClick = {
                    val uname = username.trim()
                    when {
                        !uname.matches(Regex("^[\\p{L}0-9_]{3,20}$")) -> error = "Kullanıcı adı 3-20 karakter olmalı, sadece harf/rakam/_ kullanılabilir"
                        password.length < 4 -> error = "Şifre en az 4 karakter olmalı"
                        password != confirm -> error = "Şifreler eşleşmiyor"
                        else -> {
                            loading = true; error = null
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
        val context = LocalContext.current
        ScreenScaffold {
            Text("📷", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text("Kamera ve mikrofon izni gerekiyor", style = MaterialTheme.typography.titleLarge, color = TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Rakibini görebilmen ve push-up'larının sayılabilmesi için gerekli", style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("TEKRAR DENE", onClick = onRetry)
            Spacer(Modifier.height(12.dp))
            SecondaryButton("AYARLARI AÇ", onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            })
        }
    }

    // ---------------- Home ----------------

    @Composable
    fun HomeScreen(
        session: AuthSession,
        myStats: LeaderboardEntry?,
        pendingInvite: MatchInvite?,
        onFindMatch: () -> Unit,
        onLeaderboard: () -> Unit,
        onProfile: () -> Unit,
        onFriends: () -> Unit,
        onPhotoChanged: (String) -> Unit,
        onAcceptInvite: (MatchInvite) -> Unit,
        onDeclineInvite: (MatchInvite) -> Unit,
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

        ScreenScaffold(scrollable = true, topAligned = true, backgroundRes = R.drawable.bg_home_dashboard) {
            // Marka başlığı — ana ekranın en üstüne nefes alanı ve kimlik katıyor.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppLogoMark(size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("PUSH-UP", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text("BATTLE", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                LeagueBadgeChip(myStats?.elo)
            }
            Spacer(Modifier.height(24.dp))

            // Big, unmistakable profile tab: whole card opens the profile screen.
            // Avatar keeps its own tap target for changing the photo.
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BgSurfaceRaised,
                border = BorderStroke(1.dp, BgSurfaceBorder),
                modifier = Modifier.fillMaxWidth().clickable(onClick = {
                    playClickSound()
                    onProfile()
                })
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarCircle(
                            photoBase64 = session.photoBase64, size = 68.dp, placeholder = "",
                            borderColor = leagueColor(myStats?.elo), onClick = { pickPhoto.launch("image/*") }
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hoş geldin", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            Text(session.displayName, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = TextFaint)
                    }
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = BgSurfaceBorder)
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ProfileQuickStat("Elo", "${myStats?.elo ?: EloUtils.STARTING_ELO}", Modifier.weight(1f))
                        VerticalDivider(color = BgSurfaceBorder, modifier = Modifier.height(28.dp))
                        ProfileQuickStat("Galibiyet", "${myStats?.wins ?: 0}", Modifier.weight(1f))
                        VerticalDivider(color = BgSurfaceBorder, modifier = Modifier.height(28.dp))
                        ProfileQuickStat("Seri", "${myStats?.currentStreak ?: 0}", Modifier.weight(1f))
                        VerticalDivider(color = BgSurfaceBorder, modifier = Modifier.height(28.dp))
                        ProfileQuickStat("Rekor", "${myStats?.bestReps ?: 0}", Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (pendingInvite != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = AccentOrangeDim,
                    border = BorderStroke(1.dp, AccentOrange),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(BgDeep.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) { IconImg(R.drawable.ic_icon_invite, 22.dp) }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "${pendingInvite.fromDisplayName} seni maça davet etti!",
                                color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { playConfirmSound(); onAcceptInvite(pendingInvite) },
                                colors = ButtonDefaults.buttonColors(containerColor = WinGreen),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text("Kabul Et") }
                            OutlinedButton(
                                onClick = { playClickSound(); onDeclineInvite(pendingInvite) },
                                border = BorderStroke(1.dp, BgSurfaceBorder),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text("Reddet", color = TextMuted) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, BgSurfaceBorder, RoundedCornerShape(22.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.bg_hero_pushup),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE60B0D12))))
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BgDeep.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    Text(
                        "1v1 CANLI DÜELLO",
                        style = MaterialTheme.typography.labelSmall, color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconImg(R.drawable.ic_icon_timer, 20.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("90 SANİYE", style = MaterialTheme.typography.titleMedium, color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("En fazla push-up yapan kazanır", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Kazanan +3 puan / +30 Elo · Berabere +2 puan · Kaybeden +0 puan",
                style = MaterialTheme.typography.bodySmall, color = TextFaint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(28.dp))

            PrimaryButton("RASTGELE RAKİP BUL", iconRes = R.drawable.ic_icon_battle, onClick = onFindMatch, height = 60.dp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardTile("Skor Tablosu", R.drawable.ic_icon_trophy, onClick = onLeaderboard, modifier = Modifier.weight(1f))
                DashboardTile("Arkadaşlar", R.drawable.ic_icon_friends, onClick = onFriends, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(36.dp))
            TextButton(onClick = onLogout) { Text("Çıkış yap", color = TextFaint, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------------- Matchmaking ----------------

    @Composable
    fun MatchmakingScreen(playerName: String, photoBase64: String?, onMatched: (String, Boolean) -> Unit, onCancel: () -> Unit) {
        var timedOut by remember { mutableStateOf(false) }
        var attempt by remember { mutableStateOf(0) }

        LaunchedEffect(attempt) {
            timedOut = false
            val client = MatchmakingClient(playerName)
            matchmakingClient = client
            client.findMatch(
                onMatched = { rid, caller -> onMatched(rid, caller) },
                onError = { err -> if (err == "timeout") timedOut = true }
            )
        }

        val transition = rememberInfiniteTransition(label = "pulse")
        val rotation by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rotation"
        )

        ScreenScaffold {
            if (!timedOut) {
                Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.fillMaxSize().rotate(rotation).border(4.dp, AccentOrange, CircleShape))
                    AvatarCircle(
                        photoBase64 = photoBase64,
                        size = 80.dp,
                        placeholder = "🙂",
                        borderColor = Color.Transparent
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("Rakip aranıyor…", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Hazır ol, birazdan başlıyor", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Spacer(Modifier.height(32.dp))
                SecondaryButton("İPTAL", onClick = onCancel)
            } else {
                Text("😕", fontSize = 44.sp)
                Spacer(Modifier.height(16.dp))
                Text("Şu an kimse yok", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Biraz sonra tekrar dene ya da arkadaşını davet et", style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("TEKRAR DENE", onClick = { attempt++ })
                Spacer(Modifier.height(12.dp))
                SecondaryButton("VAZGEÇ", onClick = onCancel)
            }
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
        onMatchOutcome: (MatchResultOutcome) -> Unit,
        onMatchEnded: (myReps: Int, opponentReps: Int, opponentUsername: String?, opponentDisplayName: String?) -> Unit,
        onConnectionLost: () -> Unit
    ) {
        var statusText by remember { mutableStateOf("Rakip bağlanıyor…") }
        var myReps by remember { mutableStateOf(0) }
        var opponentReps by remember { mutableStateOf(0) }
        var opponentUsername by remember { mutableStateOf<String?>(null) }
        var opponentDisplayName by remember { mutableStateOf<String?>(null) }
        var matchStartMs by remember { mutableStateOf<Long?>(null) }
        var secondsRemaining by remember { mutableStateOf(MATCH_DURATION_SECONDS) }
        var matchEndedHandled by remember { mutableStateOf(false) }
        var connected by remember { mutableStateOf(false) }
        var showBodyHint by remember { mutableStateOf(false) }
        var repBump by remember { mutableStateOf(0) }
        var connectionLostAtMs by remember { mutableStateOf<Long?>(null) }
        var lastErrorCode by remember { mutableStateOf<String?>(null) }

        val sync = remember { GameSyncClient(roomId) }

        // Maç ekranı açıkken ekran kararmasın (kamera/analiz durmasın diye).
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        DisposableEffect(Unit) {
            sync.listen { startMs, _ -> matchStartMs = startMs }
            onDispose { sync.stop() }
        }

        // Bağlantı 10 saniyeden uzun süre kopuk kalırsa maçı güvenli şekilde sonlandır
        // (puan/Elo işlenmez, sonsuza kadar donuk ekranda kalınmaz).
        LaunchedEffect(connectionLostAtMs) {
            val lostAt = connectionLostAtMs ?: return@LaunchedEffect
            delay(RECONNECT_GRACE_MS)
            if (connectionLostAtMs == lostAt && !matchEndedHandled) {
                matchEndedHandled = true
                onConnectionLost()
            }
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
                    leaderboardClient.recordMatchResult(session.username, session.displayName, myReps, won, draw, session.photoBase64) { outcome ->
                        runOnUiThread { onMatchOutcome(outcome) }
                    }
                    val finalOpponentUsername = opponentUsername
                    if (isCaller && finalOpponentUsername != null) {
                        matchHistoryClient.recordMatch(
                            session.username, session.displayName,
                            finalOpponentUsername, opponentDisplayName ?: "Rakip",
                            myReps, opponentReps
                        )
                    }
                    onMatchEnded(myReps, opponentReps, opponentUsername, opponentDisplayName)
                    break
                }
                delay(250)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(BgDeep)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                  try {
                    val remoteRenderer = SurfaceViewRenderer(ctx)
                    val localRenderer = SurfaceViewRenderer(ctx)
                    callLocalRenderer = localRenderer
                    callRemoteRenderer = remoteRenderer
                    val poseOverlay = PoseOverlayView(ctx)

                    val analyzer = PoseAnalyzer(
                        onRepCounted = { total ->
                            runOnUiThread { myReps = total; repBump++ }
                            webRtcClient?.sendDataChannelMessage("REPS:$total")
                        },
                        onLandmarks = { points, postureOk ->
                            runOnUiThread { showBodyHint = false; poseOverlay.updatePose(points, postureOk) }
                        },
                        onNoBodyDetected = {
                            runOnUiThread { showBodyHint = true; poseOverlay.clear() }
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
                                    callRemoteTrack = track
                                    webRtcClient?.attachRemoteVideoTrack(track, remoteRenderer)
                                    if (!connected) {
                                        connected = true
                                        connectionLostAtMs = null
                                        statusText = "Bağlandı ✅"
                                        if (isCaller) {
                                            val startAt = System.currentTimeMillis() + 3000
                                            sync.startMatch(startAt, MATCH_DURATION_SECONDS)
                                        }
                                    }
                                }
                            }
                            override fun onRemoteAudioTrack(track: AudioTrack) {
                                // Gelen ses otomatik olarak cihaz hoparlöründen çalar.
                            }
                            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                                runOnUiThread {
                                    when (state) {
                                        PeerConnection.PeerConnectionState.CONNECTED -> {
                                            connectionLostAtMs = null
                                            if (connected) statusText = "Bağlandı ✅"
                                        }
                                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                            if (connectionLostAtMs == null) connectionLostAtMs = System.currentTimeMillis()
                                            statusText = connectionStateText(state)
                                        }
                                        PeerConnection.PeerConnectionState.FAILED,
                                        PeerConnection.PeerConnectionState.CLOSED -> {
                                            if (connectionLostAtMs == null) connectionLostAtMs = System.currentTimeMillis()
                                            statusText = connectionStateText(state)
                                        }
                                        else -> if (!connected) statusText = connectionStateText(state)
                                    }
                                }
                            }
                            override fun onError(code: String, message: String) {
                                runOnUiThread {
                                    lastErrorCode = code
                                    statusText = "[$code] $message"
                                }
                            }
                            override fun onDataChannelOpen() {
                                webRtcClient?.sendDataChannelMessage("HELLO:${session.username}|${session.displayName}")
                            }
                            override fun onDataChannelMessage(message: String) {
                                when {
                                    message.startsWith("HELLO:") -> {
                                        val payload = message.removePrefix("HELLO:")
                                        val parts = payload.split("|")
                                        val uname = parts.getOrNull(0)
                                        val dname = parts.getOrNull(1) ?: uname
                                        runOnUiThread { opponentUsername = uname; opponentDisplayName = dname }
                                    }
                                    message.startsWith("REPS:") -> {
                                        val count = message.removePrefix("REPS:").toIntOrNull() ?: return
                                        runOnUiThread { opponentReps = count }
                                    }
                                }
                            }
                        }
                    )
                    webRtcClient = client
                    gameSyncClient = sync
                    signalingClient = FirestoreSignalingClient(roomId)

                    val localTrack = client.startLocalCapture(
                        localRenderer,
                        onFrontCameraMissing = {
                            runOnUiThread {
                                statusText = "Ön kamera bulunamadı. Bu uygulama sadece ön kamerayla çalışır."
                            }
                        }
                    )
                    if (localTrack != null) {
                        callLocalTrack = localTrack
                        localTrack.addSink(analyzer)
                        client.createPeerConnection(defaultIceServers())

                        if (isCaller) {
                            client.createDataChannel()
                            signalingClient?.listenForIceCandidates(fromCaller = false) { client.addRemoteIceCandidate(it) }
                            client.createOffer { offer ->
                                signalingClient?.sendOffer(offer)
                                signalingClient?.listenForAnswer { answer -> client.setRemoteDescription(answer) }
                            }
                        } else {
                            signalingClient?.listenForIceCandidates(fromCaller = true) { client.addRemoteIceCandidate(it) }
                            signalingClient?.listenForOffer { offer ->
                                client.setRemoteDescription(offer)
                                client.createAnswer { answer -> signalingClient?.sendAnswer(answer) }
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
                  } catch (e: Throwable) {
                    // BUG DÜZELTMESİ / TEŞHİS: yukarıdaki kurulumda (kamera, WebRTC, EGL,
                    // PeerConnectionFactory vb.) oluşan HERHANGİ bir exception eskiden
                    // doğrudan Compose'un AndroidView factory'sini patlatıp TÜM UYGULAMANIN
                    // anında çökmesine (force close) yol açıyordu - "eşleşme olunca direkt
                    // uygulamadan atıyor" şikayetiyle birebir örtüşen davranış budur. Artık
                    // yakalanıp hem statusText'e hem de installCrashLogger() ile aynı
                    // formatta bir log dosyasına ("call_setup_hatasi.txt") yazılıyor,
                    // kullanıcı en azından uygulamada kalıp ekranda hatayı görüyor.
                    Log.e("PushUpCallScreen", "CallScreen kurulumu patladı", e)
                    try {
                        val sw = java.io.StringWriter()
                        e.printStackTrace(java.io.PrintWriter(sw))
                        val dir = getExternalFilesDir(null) ?: filesDir
                        java.io.File(dir, "call_setup_hatasi.txt").writeText(sw.toString())
                    } catch (_: Throwable) {}
                    runOnUiThread {
                        statusText = "Maç ekranı kurulamadı: ${e.javaClass.simpleName}: ${e.message}"
                    }
                    android.widget.FrameLayout(ctx).apply {
                        addView(android.widget.TextView(ctx).apply {
                            text = "Maç ekranı kurulamadı:\n${e.javaClass.simpleName}: ${e.message}\n\n" +
                                "Bu hata cihazın dosyalarına 'call_setup_hatasi.txt' olarak kaydedildi."
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(48, 48, 48, 48)
                        })
                    }
                  }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    // ÖNEMLİ SIRALAMA: WebRTC Android'de resmi örneklerin (AppRTCMobile vb.)
                    // izlediği sıra budur - önce track'lerden sink'leri (renderer/analyzer)
                    // kaldır, sonra renderer'ları release et, EN SON native kaynakları
                    // (WebRtcClient.close() -> peer connection/source/factory) kapat.
                    // Eskiden renderer'lar HİÇ release edilmiyordu; bu yüzden ikinci bir
                    // maça (rövanş ya da yeni "rakip bul") girildiğinde eski renderer'ın
                    // GL thread'i hâlâ canlıyken yeni bir WebRtcClient/PeerConnectionFactory
                    // kurulmaya çalışılıyor, bu da "MediaStreamTrack has been disposed"
                    // tarzı native çökmelere yol açıyordu.
                    poseAnalyzer?.let { callLocalTrack?.removeSink(it) }
                    callLocalRenderer?.let { callLocalTrack?.removeSink(it) }
                    callRemoteRenderer?.let { callRemoteTrack?.removeSink(it) }
                    callLocalRenderer?.release()
                    callRemoteRenderer?.release()

                    poseAnalyzer?.close()
                    webRtcClient?.close()
                    signalingClient?.stopListening()

                    callLocalRenderer = null
                    callRemoteRenderer = null
                    callLocalTrack = null
                    callRemoteTrack = null
                    poseAnalyzer = null
                    webRtcClient = null
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            )

            Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val urgent = secondsRemaining in 1..10
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (urgent) LoseRed else Color(0xCC000000),
                    border = BorderStroke(1.dp, if (urgent) LoseRed else BgSurfaceBorder)
                ) {
                    Text(
                        text = if (matchStartMs == null) "Rakip bağlanıyor…" else formatCountdown(secondsRemaining),
                        color = Color.White, style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }

            Row(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                ScorePill(label = "SEN", value = myReps, accent = AccentOrange, bumpKey = repBump)
            }
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                ScorePill(label = opponentDisplayName?.take(8)?.uppercase() ?: "RAKİP", value = opponentReps, accent = RivalBlue, bumpKey = opponentReps)
            }

            if (showBodyHint) {
                Surface(
                    shape = RoundedCornerShape(10.dp), color = Color(0xCC000000),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 250.dp)
                ) {
                    Text("Kolun tamamen görünsün 👀", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            Surface(shape = RoundedCornerShape(10.dp), color = Color(0x99000000), modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(text = statusText, color = TextMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
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
            color = Color(0xCC000000), shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, accent),
            modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    fun ResultScreen(
        myReps: Int,
        opponentReps: Int,
        opponentUsername: String?,
        opponentDisplayName: String?,
        outcome: MatchResultOutcome?,
        session: AuthSession?,
        pendingInvite: MatchInvite?,
        onAcceptInvite: (MatchInvite) -> Unit,
        onDeclineInvite: (MatchInvite) -> Unit,
        onRematchAccepted: (String) -> Unit,
        onBackToHome: () -> Unit
    ) {
        val won = myReps > opponentReps
        val draw = myReps == opponentReps
        val emoji: String; val resultText: String; val accent: Color; val accentDim: Color
        when {
            won -> { emoji = "🏆"; resultText = "KAZANDIN!"; accent = WinGreen; accentDim = WinGreenDim }
            draw -> { emoji = "🤝"; resultText = "BERABERE"; accent = TextMuted; accentDim = BgSurfaceRaised }
            else -> { emoji = "😤"; resultText = "KAYBETTİN"; accent = LoseRed; accentDim = LoseRedDim }
        }

        var rematchState by remember { mutableStateOf("idle") }
        var rematchInviteId by remember { mutableStateOf<String?>(null) }
        var rematchRoomId by remember { mutableStateOf<String?>(null) }

        DisposableEffect(rematchInviteId) {
            val id = rematchInviteId
            if (id == null) {
                onDispose { }
            } else {
                val reg = matchInviteClient.listenForResponse(id) { status ->
                    runOnUiThread {
                        if (status == "accepted") {
                            rematchRoomId?.let { onRematchAccepted(it) }
                        } else if (status == "declined") {
                            rematchState = "declined"
                        }
                    }
                }
                onDispose { reg.remove() }
            }
        }

        val scale = remember { Animatable(0.4f) }
        LaunchedEffect(Unit) {
            scale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        val shakeOffset = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            if (!won && !draw) {
                delay(250)
                listOf(-18f, 16f, -12f, 8f, -4f, 0f).forEach { target -> shakeOffset.animateTo(target, animationSpec = tween(55)) }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            ScreenScaffold(scrollable = true, backgroundRes = R.drawable.bg_result_smoke) {
                // DÜZELTME: Rövanş daveti banner'ı eskiden SADECE ana sayfada gösteriliyordu.
                // İki oyuncu da maç bitince aynı anda "REVANŞ İSTE"ye basarsa, ikisi de
                // birbirinin davetini hiç GÖRMEDEN "rakibin cevabı bekleniyor" durumunda
                // sonsuza kadar kilitleniyordu (klasik çapraz davet kilitlenmesi). Artık bu
                // ekranda da banner gösteriliyor, böyle bir kilitlenme yaşanmıyor.
                if (pendingInvite != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BgSurfaceRaised,
                        border = BorderStroke(1.dp, AccentOrange),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("⚔️ ${pendingInvite.fromDisplayName} seni maça davet etti!", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onAcceptInvite(pendingInvite) },
                                    colors = ButtonDefaults.buttonColors(containerColor = WinGreen),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Kabul Et") }
                                OutlinedButton(
                                    onClick = { onDeclineInvite(pendingInvite) },
                                    border = BorderStroke(1.dp, BgSurfaceBorder),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Reddet", color = TextMuted) }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                Box(
                    modifier = Modifier.size(100.dp)
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value; translationX = shakeOffset.value }
                        .clip(CircleShape).background(accentDim),
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = 44.sp) }
                Spacer(Modifier.height(20.dp))
                Text(resultText, style = MaterialTheme.typography.headlineLarge, color = accent)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(label = "SEN", value = myReps, accent = AccentOrange, modifier = Modifier.weight(1f))
                    StatCard(label = opponentDisplayName ?: "RAKİP", value = opponentReps, accent = RivalBlue, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))

                if (outcome == null) {
                    Text("Puan hesaplanıyor…", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                } else {
                    Text(
                        "+${outcome.pointsEarned} puan" + if (outcome.pointsEarned == 0L) " (kaybetme puan kazandırmaz)" else "",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    val eloText = when {
                        outcome.eloDelta > 0 -> "+${outcome.eloDelta} Elo 📈"
                        outcome.eloDelta < 0 -> "${outcome.eloDelta} Elo 📉"
                        else -> "Elo değişmedi"
                    }
                    Text(eloText, style = MaterialTheme.typography.bodyMedium, color = leagueColor(outcome.newElo))

                    if (outcome.newLeague.ordinal > outcome.oldLeague.ordinal) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = leagueColor(outcome.newElo).copy(alpha = 0.15f), border = BorderStroke(1.dp, leagueColor(outcome.newElo))) {
                            Text(
                                "🎉 Ligin değişti: ${outcome.newLeague.displayName}'e yükseldin!",
                                color = leagueColor(outcome.newElo), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else if (outcome.newLeague.ordinal < outcome.oldLeague.ordinal) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = LoseRedDim, border = BorderStroke(1.dp, LoseRed)) {
                            Text(
                                "${outcome.oldLeague.displayName}'den ${outcome.newLeague.displayName}'e düştün",
                                color = LoseRed, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    outcome.newlyEarnedBadges.forEach { badge ->
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(12.dp), color = MedalGold.copy(alpha = 0.15f), border = BorderStroke(1.dp, MedalGold)) {
                            Text(
                                "${badge.emoji} Yeni rozet: ${badge.title}",
                                color = MedalGold, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                PrimaryButton("ANA SAYFAYA DÖN", onClick = onBackToHome)

                if (session != null && opponentUsername != null) {
                    Spacer(Modifier.height(12.dp))
                    when (rematchState) {
                        "idle" -> SecondaryButton("🔁 REVANŞ İSTE", onClick = {
                            rematchState = "sending"
                            matchInviteClient.sendInvite(session.username, session.displayName, opponentUsername) { inviteId, roomId ->
                                rematchInviteId = inviteId
                                rematchRoomId = roomId
                                rematchState = "waiting"
                            }
                        })
                        "sending", "waiting" -> Text("Rakibinin cevabı bekleniyor…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        "declined" -> Text("Rakip revanşı reddetti", color = LoseRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (won) ConfettiBurst(modifier = Modifier.fillMaxSize())
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
        LaunchedEffect(Unit) { progress.animateTo(1f, animationSpec = tween(1800, easing = LinearOutSlowInEasing)) }
        Canvas(modifier = modifier) {
            val t = progress.value
            val w = size.width; val h = size.height
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

    data class ConfettiParticle(val startX: Float, val velocityX: Float, val velocityY: Float, val color: Color, val rotationSpeed: Float, val particleSize: Float)
    private val confettiColors = listOf(WinGreen, AccentOrange, RivalBlue, MedalGold, Color.White)

    @Composable
    fun StatCard(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
        Surface(shape = RoundedCornerShape(16.dp), color = BgSurfaceRaised, border = BorderStroke(1.dp, BgSurfaceBorder), modifier = modifier) {
            Column(modifier = Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(value.toString(), style = MaterialTheme.typography.headlineLarge, color = accent)
            }
        }
    }

    // ---------------- Leaderboard ----------------

    @Composable
    fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) AccentOrange else BgSurfaceRaised,
            border = BorderStroke(1.dp, if (selected) AccentOrange else BgSurfaceBorder),
            modifier = modifier.clickable(onClick = onClick)
        ) {
            Text(
                text, color = if (selected) Color.White else TextMuted,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth()
            )
        }
    }

    @Composable
    fun LeaderboardScreen(onBack: () -> Unit) {
        var mode by remember { mutableStateOf(LeaderboardMode.TOTAL) }
        var entries by remember { mutableStateOf<List<LeaderboardEntry>?>(null) }

        LaunchedEffect(mode) {
            entries = null
            leaderboardClient.fetchTopPlayers(mode) { entries = it }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgDeep, BgSurface))).padding(20.dp)
        ) {
            Text("🏆 Skor Tablosu", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModeChip("Toplam", mode == LeaderboardMode.TOTAL, { mode = LeaderboardMode.TOTAL }, Modifier.weight(1f))
                ModeChip("Elo", mode == LeaderboardMode.ELO, { mode = LeaderboardMode.ELO }, Modifier.weight(1f))
                ModeChip("Bu Hafta", mode == LeaderboardMode.WEEKLY, { mode = LeaderboardMode.WEEKLY }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))

            when {
                entries == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentOrange)
                }
                entries!!.isEmpty() -> Text("Henüz kimse burada yok.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumnLeaderboard(entries!!, mode, Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton("GERİ", onClick = onBack)
        }
    }

    @Composable
    fun LazyColumnLeaderboard(entries: List<LeaderboardEntry>, mode: LeaderboardMode, modifier: Modifier = Modifier) {
        LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexedCompat(entries) { index, entry ->
                val rank = index + 1
                val medal = when (rank) { 1 -> MedalGold; 2 -> MedalSilver; 3 -> MedalBronze; else -> BgSurfaceBorder }
                val valueText = when (mode) {
                    LeaderboardMode.TOTAL -> "${entry.totalPoints} puan"
                    LeaderboardMode.ELO -> "${entry.elo} Elo"
                    LeaderboardMode.WEEKLY -> "${entry.weeklyPoints} puan"
                }
                Surface(
                    shape = RoundedCornerShape(14.dp), color = BgSurfaceRaised,
                    border = BorderStroke(1.dp, if (rank <= 3) medal else BgSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(28.dp).clip(CircleShape).background(if (rank <= 3) medal else BgSurfaceBorder),
                                contentAlignment = Alignment.Center
                            ) { Text("$rank", color = if (rank <= 3) BgDeep else TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                            Spacer(Modifier.width(10.dp))
                            AvatarCircle(photoBase64 = entry.photoBase64.ifBlank { null }, size = 32.dp, placeholder = "🙂", borderColor = leagueColor(entry.elo))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(entry.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(EloUtils.leagueFor(entry.elo).displayName, color = leagueColor(entry.elo), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(valueText, color = AccentOrange, style = MaterialTheme.typography.titleMedium)
                            Text("en iyi ${entry.bestReps}", color = TextFaint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    private fun <T> LazyListScope.itemsIndexedCompat(list: List<T>, itemContent: @Composable (Int, T) -> Unit) {
        items(list.size) { index -> itemContent(index, list[index]) }
    }

    // ---------------- Profile (İstatistik / Geçmiş / Rozetler) ----------------

    @Composable
    fun ProfileScreen(session: AuthSession, myStats: LeaderboardEntry?, onBack: () -> Unit) {
        var tab by remember { mutableStateOf(0) }
        var history by remember { mutableStateOf<List<MatchHistoryEntry>?>(null) }

        LaunchedEffect(tab) {
            if (tab == 1 && history == null) {
                matchHistoryClient.fetchHistory(session.username) { history = it }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgDeep, BgSurface))).padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(photoBase64 = session.photoBase64, size = 56.dp, placeholder = "📷", borderColor = leagueColor(myStats?.elo))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(session.displayName, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    LeagueBadgeChip(myStats?.elo)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModeChip("İstatistik", tab == 0, { tab = 0 }, Modifier.weight(1f))
                ModeChip("Geçmiş", tab == 1, { tab = 1 }, Modifier.weight(1f))
                ModeChip("Rozetler", tab == 2, { tab = 2 }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> StatsTabContent(myStats)
                    1 -> HistoryTabContent(history, session.username)
                    else -> BadgesTabContent(myStats?.badges ?: emptyMap())
                }
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton("GERİ", onClick = onBack)
        }
    }

    @Composable
    fun StatsTabContent(stats: LeaderboardEntry?) {
        if (stats == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentOrange) }
            return
        }
        val winRate = if (stats.matchesPlayed > 0) (stats.wins * 100 / stats.matchesPlayed) else 0
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("MAÇ", stats.matchesPlayed.toInt(), AccentOrange, Modifier.weight(1f))
                StatCard("KAZANMA %", winRate.toInt(), WinGreen, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("EN İYİ TEKRAR", stats.bestReps.toInt(), AccentOrange, Modifier.weight(1f))
                StatCard("EN İYİ SERİ", stats.bestStreak.toInt(), MedalGold, Modifier.weight(1f))
            }
            Surface(shape = RoundedCornerShape(16.dp), color = BgSurfaceRaised, border = BorderStroke(1.dp, BgSurfaceBorder), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${stats.wins}", color = WinGreen, style = MaterialTheme.typography.titleLarge); Text("Galibiyet", color = TextFaint, style = MaterialTheme.typography.labelSmall) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${stats.draws}", color = TextMuted, style = MaterialTheme.typography.titleLarge); Text("Berabere", color = TextFaint, style = MaterialTheme.typography.labelSmall) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("${stats.losses}", color = LoseRed, style = MaterialTheme.typography.titleLarge); Text("Mağlubiyet", color = TextFaint, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }

    @Composable
    fun HistoryTabContent(history: List<MatchHistoryEntry>?, myUsername: String) {
        if (history == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentOrange) }
            return
        }
        if (history.isEmpty()) {
            Text("Henüz maç geçmişin yok.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexedCompat(history) { _, entry ->
                val amICallerInThisMatch = entry.participants.getOrNull(0) == myUsername.trim().lowercase()
                val myRepsInMatch = if (amICallerInThisMatch) entry.repsA else entry.repsB
                val oppRepsInMatch = if (amICallerInThisMatch) entry.repsB else entry.repsA
                val oppName = if (amICallerInThisMatch) entry.playerB else entry.playerA
                val outcomeColor = when {
                    myRepsInMatch > oppRepsInMatch -> WinGreen
                    myRepsInMatch < oppRepsInMatch -> LoseRed
                    else -> TextMuted
                }
                Surface(shape = RoundedCornerShape(12.dp), color = BgSurfaceRaised, border = BorderStroke(1.dp, outcomeColor.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(oppName, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("$myRepsInMatch - $oppRepsInMatch", color = outcomeColor, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    @Composable
    fun BadgesTabContent(earnedBadges: Map<String, Long>) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexedCompat(BadgeDefinitions.ALL.chunked(2)) { _, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { badge ->
                        val earned = earnedBadges.containsKey(badge.id)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (earned) MedalGold.copy(alpha = 0.12f) else BgSurfaceRaised,
                            border = BorderStroke(1.dp, if (earned) MedalGold else BgSurfaceBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(badge.emoji, fontSize = 26.sp, modifier = Modifier.graphicsLayer { alpha = if (earned) 1f else 0.3f })
                                Spacer(Modifier.height(4.dp))
                                Text(badge.title, color = if (earned) TextPrimary else TextFaint, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                Text(badge.description, color = TextFaint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    // ---------------- Friends ----------------

    @Composable
    fun FriendsScreen(session: AuthSession, onBack: () -> Unit, onInviteSent: (String) -> Unit) {
        var friends by remember { mutableStateOf<List<FriendProfile>?>(null) }
        var incomingRequests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
        var addUsername by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }

        fun reload() { friendsClient.fetchFriendProfiles(session.username) { friends = it } }
        LaunchedEffect(Unit) { reload() }

        DisposableEffect(Unit) {
            val reg = friendsClient.listenForIncomingRequests(session.username) { incomingRequests = it }
            onDispose { reg.remove() }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgDeep, BgSurface))).padding(20.dp)
        ) {
            Text("👥 Arkadaşlar", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addUsername, onValueChange = { addUsername = it },
                    label = { Text("Kullanıcı adı") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), colors = authFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val target = addUsername.trim()
                        if (target.isBlank()) return@Button
                        friendsClient.sendFriendRequest(session.username, session.displayName, target) { outcome ->
                            message = when (outcome) {
                                FriendsClient.RequestOutcome.SENT -> "İstek gönderildi 📨"
                                FriendsClient.RequestOutcome.AUTO_ACCEPTED -> "O da sana istek göndermişti - artık arkadaşsınız ✅"
                                FriendsClient.RequestOutcome.ALREADY_FRIENDS -> "Zaten arkadaşsınız"
                                FriendsClient.RequestOutcome.ALREADY_PENDING -> "Bu kullanıcıya zaten istek gönderdin, yanıt bekleniyor"
                                FriendsClient.RequestOutcome.USER_NOT_FOUND -> "Böyle bir kullanıcı bulunamadı"
                                FriendsClient.RequestOutcome.SELF -> "Kendine istek gönderemezsin"
                                FriendsClient.RequestOutcome.ERROR -> "Bir şeyler ters gitti, tekrar dene"
                            }
                            addUsername = ""
                            if (outcome == FriendsClient.RequestOutcome.AUTO_ACCEPTED) reload()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                    modifier = Modifier.height(56.dp)
                ) { Text("İstek Gönder") }
            }
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                Text(message!!, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }

            if (incomingRequests.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Bekleyen istekler", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    incomingRequests.forEach { req ->
                        Surface(shape = RoundedCornerShape(14.dp), color = BgSurfaceRaised, border = BorderStroke(1.dp, AccentOrange), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(req.fromDisplayName, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { friendsClient.respondToFriendRequest(req, session.username, true) { reload() } },
                                    colors = ButtonDefaults.buttonColors(containerColor = WinGreen),
                                    modifier = Modifier.height(38.dp)
                                ) { Text("Kabul Et", style = MaterialTheme.typography.labelSmall) }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = { friendsClient.respondToFriendRequest(req, session.username, false) },
                                    border = BorderStroke(1.dp, LoseRed),
                                    modifier = Modifier.height(38.dp)
                                ) { Text("Reddet", color = LoseRed, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    friends == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentOrange) }
                    friends!!.isEmpty() -> Text("Henüz arkadaşın yok. Kullanıcı adıyla ekleyebilirsin.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexedCompat(friends!!) { _, friend ->
                            Surface(shape = RoundedCornerShape(14.dp), color = BgSurfaceRaised, border = BorderStroke(1.dp, BgSurfaceBorder), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AvatarCircle(photoBase64 = friend.entry.photoBase64.ifBlank { null }, size = 40.dp, placeholder = "🙂", borderColor = leagueColor(friend.entry.elo))
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(friend.entry.name.ifBlank { friend.username }, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                            Text("${friend.entry.elo} Elo", color = leagueColor(friend.entry.elo), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Row {
                                        Button(
                                            onClick = {
                                                matchInviteClient.sendInvite(session.username, session.displayName, friend.username) { _, roomId ->
                                                    onInviteSent(roomId)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                                            modifier = Modifier.height(38.dp)
                                        ) { Text("Davet Et", style = MaterialTheme.typography.labelSmall) }
                                        Spacer(Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = { friendsClient.removeFriend(session.username, friend.username) { reload() } },
                                            border = BorderStroke(1.dp, LoseRed),
                                            modifier = Modifier.height(38.dp)
                                        ) { Text("Sil", color = LoseRed, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SecondaryButton("GERİ", onClick = onBack)
        }
    }

    /**
     * v3: TURN sunucusu ExpressTURN'e taşındı (ücretsiz plan, ayda 1000 GB - şu ana kadar
     * kullanılan paylaşımlı openrelay.metered.ca'dan çok daha geniş bir kota).
     * openrelay satırı, ExpressTURN'e ulaşılamazsa diye yedek olarak bırakıldı.
     */
    private fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:free.expressturn.com:3478")
            .setUsername("000000002102104065")
            .setPassword("Bojx1Nf2qqS7ARqhsjbNPtIfHZo=")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )
}
