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

    val localVideoSource: VideoSource
    val localAudioSource: AudioSource

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        localVideoSource = peerConnectionFactory.createVideoSource(false)
        localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
    }

    fun startLocalCapture(localRenderer: SurfaceViewRenderer): VideoTrack {
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)

        val cameraEnumerator = Camera2Enumerator(context)
        val frontCameraName = cameraEnumerator.deviceNames.firstOrNull {
            cameraEnumerator.isFrontFacing(it)
        } ?: cameraEnumerator.deviceNames.first()

        videoCapturer = cameraEnumerator.createCapturer(frontCameraName, null)

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

                override fun onAddStream(stream: MediaStream) {
                    stream.videoTracks.firstOrNull()?.let { listener.onRemoteVideoTrack(it) }
                    stream.audioTracks.firstOrNull()?.let { listener.onRemoteAudioTrack(it) }
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

        localVideoTrack?.let { peerConnection?.addTrack(it, listOf(LOCAL_STREAM_ID)) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf(LOCAL_STREAM_ID)) }
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

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                onSuccess(sdp)
            }
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                onSuccess(sdp)
            }
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun attachRemoteVideoTrack(track: VideoTrack, remoteRenderer: SurfaceViewRenderer) {
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setMirror(false)
        track.addSink(remoteRenderer)
    }

    fun close() {
        dataChannel?.close()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    companion object {
        private const val LOCAL_STREAM_ID = "pushup_local_stream"
    }
}
