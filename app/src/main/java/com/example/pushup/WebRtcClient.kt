package com.example.pushup

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Thin wrapper around Google's WebRTC library.
 *
 * v3: DataChannel eklendi. Push-up tekrar sayıları artık Firestore üzerinden değil,
 * doğrudan bu DataChannel üzerinden karşı tarafa gönderiliyor - bu hem gecikmeyi
 * azaltıyor hem de Firestore günlük yazma kotasını neredeyse tamamen boşa çıkarıyor
 * (maç boyunca en sık yazılan veri buydu). Maç başlangıcı ve skor kaydı gibi "az sayıda,
 * garanti teslim edilmesi gereken" veriler hâlâ Firestore'da (GameSyncClient, LeaderboardClient).
 *
 * DataChannel'ı sadece davet eden (caller) taraf oluşturur; karşı taraf onu
 * `onDataChannel` callback'i ile alır - standart WebRTC deseni budur.
 */
class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onDataChannelOpen() {}
        fun onDataChannelMessage(message: String) {}
        /** code -> AppError.RTC_* sabitlerinden biri, message -> Türkçe kısa açıklama (AppError.message ile üretilir). */
        fun onError(code: String, message: String) {}
    }

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null
    private var localCandidateCount = 0
    private var remoteDescriptionSet = false
    private var closed = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    // Bir SurfaceViewRenderer'ı ikinci kez init() etmeye çalışmak çöküyor ("Already
    // initialized"). Bu bayrak, attachRemoteVideoTrack yanlışlıkla iki kez çağrılsa bile
    // (örn. revanş/yeniden bağlanma akışlarında) ikinci init() çağrısını engelliyor.
    private var remoteRendererInitialized = false

    val localVideoSource: VideoSource
    val localAudioSource: AudioSource

    init {
        // PeerConnectionFactory.initialize() işlem başına (process-wide) bir kez çağrılmalı -
        // resmi WebRTC dokümantasyonunun önerisi bu. Öncesinde her yeni maçta (yani her yeni
        // WebRtcClient örneğinde) tekrar tekrar çağrılıyordu; zararsız görünse de gereksiz ve
        // resmi kullanım dışıydı, artık sadece ilk sefer çalışıyor.
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        localVideoSource = peerConnectionFactory.createVideoSource(false)
        localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
    }

    /** Sistem SADECE ön kamerayla çalışır - arka kameraya asla düşmez (bkz. startLocalCapture). */
    val isMirrored: Boolean = true

    /**
     * Ön kamerayı başlatır. Bu uygulama tamamen ön kamera (selfie) üzerine kurulu -
     * rakibin seni push-up yaparken görmesi gerekiyor, arka kamera bu sistemde hiçbir işe
     * yaramaz. Cihazda ön kamera yoksa/algılanamıyorsa artık sessizce arka kameraya
     * DÜŞMÜYORUZ - onFrontCameraMissing çağrılıp maç ekranına net bir hata gösteriliyor.
     */
    fun startLocalCapture(localRenderer: SurfaceViewRenderer, onFrontCameraMissing: () -> Unit = {}): VideoTrack? {
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        // ÖNEMLİ: PoseOverlayView, iskelet noktalarını normalize (0..1) koordinatlarla
        // doğrudan bu renderer'ın kapladığı View'ın TAMAMINA (width/height) göre çiziyor.
        // Varsayılan scaling type (SCALE_ASPECT_BALANCED) videoyu en-boy oranını koruyarak
        // sığdırır - yani video, View'ın küçük PIP kutusunun (340x440) tamamını doldurmaz,
        // kenarlarda boşluk kalır. Bu durumda overlay'in "0..1 = kutunun tamamı" varsayımı
        // gerçek video ile örtüşmez ve iskelet kaymış/eğik görünür. SCALE_ASPECT_FILL ile
        // video kutunun tamamını (taşarak/kırparak) dolduruyor, böylece overlay koordinatları
        // gerçekten görünen video ile birebir hizalanıyor.
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        val cameraEnumerator = Camera2Enumerator(context)
        val frontCameraName = cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }
        if (frontCameraName == null) {
            Log.e("PushUpWebRTC", "Ön kamera bulunamadı. Algılanan kameralar: ${cameraEnumerator.deviceNames.joinToString()}")
            onFrontCameraMissing()
            return null
        }

        videoCapturer = cameraEnumerator.createCapturer(frontCameraName, object : CameraVideoCapturer.CameraEventsHandler {
            // ÖNEMLİ: eskiden createCapturer'a null geçiliyordu, yani kamera "başka bir
            // uygulama tarafından kullanılıyor", "kamera aniden kapandı" gibi native
            // hatalar HİÇBİR YERE bildirilmiyordu - ekran sessizce siyah kalıyordu.
            override fun onCameraError(errorDescription: String?) {
                Log.e("PushUpWebRTC", "Kamera hatası: $errorDescription")
                AppError.log(AppError.RTC_CAMERA_ERROR, errorDescription ?: "")
                listener.onError(AppError.RTC_CAMERA_ERROR, AppError.message(AppError.RTC_CAMERA_ERROR))
            }
            override fun onCameraDisconnected() {
                Log.e("PushUpWebRTC", "Kamera bağlantısı kesildi")
                AppError.log(AppError.RTC_CAMERA_ERROR, "kamera bağlantısı kesildi")
                listener.onError(AppError.RTC_CAMERA_ERROR, AppError.message(AppError.RTC_CAMERA_ERROR))
            }
            override fun onCameraFreezed(errorDescription: String?) {
                Log.e("PushUpWebRTC", "Kamera dondu: $errorDescription")
                AppError.log(AppError.RTC_CAMERA_ERROR, "kamera dondu: $errorDescription")
                listener.onError(AppError.RTC_CAMERA_ERROR, AppError.message(AppError.RTC_CAMERA_ERROR))
            }
            override fun onCameraOpening(cameraName: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        })

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        val videoTrack = peerConnectionFactory.createVideoTrack("local_video", localVideoSource)
        videoTrack.addSink(localRenderer)

        val audioTrack = peerConnectionFactory.createAudioTrack("local_audio", localAudioSource)

        localVideoTrack = videoTrack
        localAudioTrack = audioTrack
        return videoTrack
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    // candidate.sdp içinde "typ host/srflx/relay" geçer - "relay" hiç görünmüyorsa
                    // TURN sunucusuna hiç ulaşılamıyor demektir (en sık "bağlanmıyor" nedeni).
                    val type = Regex("typ (\\w+)").find(candidate.sdp)?.groupValues?.get(1) ?: "?"
                    localCandidateCount++
                    Log.d("PushUpWebRTC", "local ICE candidate type=$type sdpMid=${candidate.sdpMid}")
                    listener.onLocalIceCandidate(candidate)
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    when (val track = receiver?.track()) {
                        is VideoTrack -> listener.onRemoteVideoTrack(track)
                        is AudioTrack -> listener.onRemoteAudioTrack(track)
                        else -> {}
                    }
                }

                // DÜZELTME (ÇÖKME): onAddTrack yukarıda zaten her uzak video/ses track'i için
                // tetikleniyor (Unified Plan - modern WebRTC standardı). onAddStream ise eski
                // (Plan B) API'den kalma ve BAZI cihaz/sürümlerde onAddTrack ile birlikte
                // İKİSİ BİRDEN tetikleniyor - bu da aynı VideoTrack için
                // listener.onRemoteVideoTrack() iki kez çağrılmasına, oradan da
                // WebRtcClient.attachRemoteVideoTrack() içinde aynı SurfaceViewRenderer'ın iki
                // kez init() edilmesine yol açıyordu: "IllegalStateException: Already
                // initialized" - maç eşleşir eşleşmez uygulamanın anında çökmesinin
                // sebebi tam olarak buydu. Artık burada hiçbir şey yapılmıyor.
                override fun onAddStream(stream: MediaStream) {
                    // Bilerek boş bırakıldı - bkz. yukarıdaki yorum.
                }

                override fun onDataChannel(channel: DataChannel?) {
                    if (channel != null) {
                        dataChannel = channel
                        registerDataChannelObserver(channel)
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d("PushUpWebRTC", "peer connection state -> $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.FAILED -> {
                            AppError.log(AppError.RTC_PEER_FAILED, "peer connection FAILED")
                            listener.onError(AppError.RTC_PEER_FAILED, AppError.message(AppError.RTC_PEER_FAILED))
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            AppError.log(AppError.RTC_PEER_CLOSED, "peer connection CLOSED")
                            listener.onError(AppError.RTC_PEER_CLOSED, AppError.message(AppError.RTC_PEER_CLOSED))
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            AppError.log(AppError.RTC_PEER_DISCONNECTED, "peer connection DISCONNECTED")
                            listener.onError(AppError.RTC_PEER_DISCONNECTED, AppError.message(AppError.RTC_PEER_DISCONNECTED))
                        }
                        else -> {}
                    }
                    listener.onConnectionStateChanged(newState)
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                    Log.d("PushUpWebRTC", "signaling state -> $p0")
                }
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                    Log.d("PushUpWebRTC", "ICE connection state -> $p0")
                    when (p0) {
                        PeerConnection.IceConnectionState.FAILED -> {
                            AppError.log(AppError.RTC_ICE_FAILED, "ICE connection FAILED - muhtemelen NAT/TURN sorunu")
                            listener.onError(AppError.RTC_ICE_FAILED, AppError.message(AppError.RTC_ICE_FAILED))
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            AppError.log(AppError.RTC_ICE_DISCONNECTED, "ICE connection DISCONNECTED")
                            listener.onError(AppError.RTC_ICE_DISCONNECTED, AppError.message(AppError.RTC_ICE_DISCONNECTED))
                        }
                        else -> {}
                    }
                }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                    Log.d("PushUpWebRTC", "ICE gathering state -> $p0")
                    if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
                        Log.d("PushUpWebRTC", "ICE gathering complete, total local candidates=${localCandidateCount}")
                        if (localCandidateCount == 0) {
                            AppError.log(AppError.SIG_ICE_GATHER_FAILED, "hiç ICE candidate üretilemedi")
                            listener.onError(AppError.SIG_ICE_GATHER_FAILED, AppError.message(AppError.SIG_ICE_GATHER_FAILED))
                        }
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        // DÜZELTME: createPeerConnection() teorik olarak null dönebilir (örn. bozuk ICE
        // server config) - eskiden bu hiç kontrol edilmiyordu, peerConnection null kalırsa
        // sonraki tüm createOffer/createAnswer/addTrack çağrıları sessizce hiçbir şey
        // yapmıyordu (?.let / ?. ile no-op), maç sebepsizce donuyordu. Artık en azından
        // loglanıp kullanıcıya bildiriliyor.
        val pc = peerConnection
        if (pc == null) {
            Log.e("PushUpWebRTC", "createPeerConnection null döndü")
            AppError.log(AppError.RTC_PEER_FAILED, "createPeerConnection returned null")
            listener.onError(AppError.RTC_PEER_FAILED, AppError.message(AppError.RTC_PEER_FAILED))
            return
        }

        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        localAudioTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }
    }

    /** Sadece davet eden (caller) taraf çağırmalı - offer oluşturmadan ÖNCE. */
    fun createDataChannel() {
        val init = DataChannel.Init().apply { ordered = true }
        val channel = peerConnection?.createDataChannel("game", init) ?: return
        dataChannel = channel
        registerDataChannelObserver(channel)
    }

    private fun registerDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) listener.onDataChannelOpen()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onDataChannelMessage(String(bytes, Charsets.UTF_8))
            }
        })
    }

    fun sendDataChannelMessage(text: String) {
        val channel = dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)
        channel.send(buffer)
    }

    /**
     * DÜZELTME: eskiden setLocalDescription()'ın tamamlanmasını (onSetSuccess) HİÇ
     * beklemeden, createOffer başarılı olur olmaz SDP karşı tarafa gönderiliyordu
     * (onSuccess(sdp) senkron çağrılıyordu). setLocalDescription asenkron olduğu için,
     * eğer karşı taraf çok hızlı yanıt verirse (cevap/ICE candidate gönderirse) biz henüz
     * kendi local description'ımızı tam oturtmadan onu işlemeye çalışabiliyorduk - bu da
     * ara sıra "wrong state" tarzı SDP hatalarına yol açabiliyordu. Artık karşı tarafa
     * göndermeden önce local description'ın gerçekten set edildiğinden emin oluyoruz.
     */
    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { onSuccess(sdp) }
                }, sdp)
            }
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() { onSuccess(sdp) }
                }, sdp)
            }
        }, constraints)
    }

    /**
     * ÖNEMLİ: setRemoteDescription tamamlanmadan (onSetSuccess) önce gelen uzak ICE
     * candidate'ları addIceCandidate ile eklemeye çalışmak SESSİZCE başarısız olur -
     * native WebRTC tarafında henüz transport oluşturulmamış olur. Bu yüzden remote
     * description set edilene kadar gelen candidate'lar burada kuyruğa alınıyor ve
     * onSetSuccess tetiklenince hepsi birden uygulanıyor. Bu olmadan, maçların önemli
     * bir kısmı - kim daha önce candidate/offer gönderdiğine bağlı olarak - rastgele
     * şekilde hiç bağlanamıyordu ("rakip bağlanıyor" yazıp takılı kalıyordu).
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                val queued = synchronized(pendingRemoteCandidates) {
                    val copy = pendingRemoteCandidates.toList()
                    pendingRemoteCandidates.clear()
                    copy
                }
                queued.forEach { peerConnection?.addIceCandidate(it) }
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.add(candidate) }
        }
    }

    fun attachRemoteVideoTrack(track: VideoTrack, remoteRenderer: SurfaceViewRenderer) {
        if (!remoteRendererInitialized) {
            remoteRenderer.init(eglBase.eglBaseContext, null)
            remoteRenderer.setMirror(false)
            remoteRendererInitialized = true
        }
        track.addSink(remoteRenderer)
    }

    /**
     * DÜZELTME: eskiden burada PeerConnectionFactory, VideoSource ve AudioSource hiç
     * dispose edilmiyordu. Her maç (ve her rövanş) yeni bir WebRtcClient = yeni bir
     * PeerConnectionFactory yarattığı için, art arda oynanan maçlarda native encoder/
     * decoder thread'leri ve bellek sürekli sızıyordu - uzun bir oturumda (çok sayıda
     * maç/rövanş) uygulamanın yavaşlamasına hatta çökmesine yol açabilirdi.
     *
     * DÜZELTME 2: localVideoTrack/localAudioTrack (createVideoTrack/createAudioTrack ile
     * factory'den üretilen native nesneler) de hiç dispose edilmiyordu - kendi native
     * referansları var, sadece source'u dispose etmek onları serbest bırakmıyor. Ayrıca
     * close() birden fazla çağrılırsa (örn. bir kod yolu yanlışlıkla iki kez tetiklerse)
     * zaten dispose edilmiş native nesneleri tekrar dispose etmeye çalışmak çöküyordu
     * ("MediaSource has been disposed" tarzı IllegalStateException) - artık close()
     * idempotent, ikinci çağrı hiçbir şey yapmıyor.
     */
    fun close() {
        if (closed) return
        closed = true
        dataChannel?.close()
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.e("PushUpWebRTC", "stopCapture interrupted", e)
        }
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        localVideoSource.dispose()
        localAudioSource.dispose()
        peerConnectionFactory.dispose()
        remoteDescriptionSet = false
        synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.clear() }
    }

    private open inner class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        // DÜZELTME: bu ikisi eskiden tamamen boştu - createOffer/createAnswer/
        // setLocalDescription/setRemoteDescription başarısız olduğunda (örn. bozuk SDP,
        // yanlış signaling state) HİÇBİR ŞEY olmuyordu, maç sessizce donuyordu. Artık en
        // azından loglanıyor ve kullanıcıya bildiriliyor.
        override fun onCreateFailure(p0: String?) {
            Log.e("PushUpWebRTC", "SDP create failure: $p0")
            AppError.log(AppError.RTC_SDP_FAILED, "create: $p0")
            listener.onError(AppError.RTC_SDP_FAILED, AppError.message(AppError.RTC_SDP_FAILED))
        }
        override fun onSetFailure(p0: String?) {
            Log.e("PushUpWebRTC", "SDP set failure: $p0")
            AppError.log(AppError.RTC_SDP_FAILED, "set: $p0")
            listener.onError(AppError.RTC_SDP_FAILED, AppError.message(AppError.RTC_SDP_FAILED))
        }
    }

    companion object {
        private const val LOCAL_STREAM_ID = "pushup_local_stream"
        private var factoryInitialized = false
    }
}
