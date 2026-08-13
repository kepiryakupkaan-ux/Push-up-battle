package com.example.pushup

import android.content.Context
import org.webrtc.*

/**
 * Thin wrapper around Google's WebRTC library.
 *
 * Responsibilities:
 *  - open the front camera and turn it into a local video track
 *  - create a PeerConnection to the other phone
 *  - hand SDP offer/answer and ICE candidates to whoever is doing signaling
 *    (see FirestoreSignalingClient) - this class does NOT know about Firebase.
 */
class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteStream(stream: MediaStream)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    }

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

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

    /** Starts the front camera and returns the local video track so the UI can render it. */
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

    /** Creates the PeerConnection. Call this once local capture has started. */
    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    listener.onLocalIceCandidate(candidate)
                }
                override fun onAddStream(stream: MediaStream) {
                    listener.onRemoteStream(stream)
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    listener.onConnectionStateChanged(newState)
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }
        )

        localVideoTrack?.let { peerConnection?.addTrack(it) }
        localAudioTrack?.let { peerConnection?.addTrack(it) }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                onSuccess(sdp)
            }
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
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

    fun attachRemoteRenderer(stream: MediaStream, remoteRenderer: SurfaceViewRenderer) {
        remoteRenderer.init(eglBase.eglBaseContext, null)
        stream.videoTracks.firstOrNull()?.addSink(remoteRenderer)
    }

    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
    }

    /** SDPObserver has 4 methods we usually don't care about; this avoids repeating empty overrides. */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
