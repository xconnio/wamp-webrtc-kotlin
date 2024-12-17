package io.xconn.wampwebrtc

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.LinkedBlockingDeque
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Offerer(
    context: Context,
    private val queue: LinkedBlockingDeque<ByteArray>,
    private val signalIceCandidate: (IceCandidate) -> Unit,
) {
    init {
        val options =
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    var peerConnection: PeerConnection? = null
    var dataChannel: DataChannel? = null
    val peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    private var assembler = MessageAssembler()
    private var onDataChannelOpen: (() -> Unit)? = null
    private var dataChannelTimeoutMillis: Long = 20000

    suspend fun createOffer(offerConfig: OfferConfig): SessionDescription? {
        val configuration = PeerConnection.RTCConfiguration(offerConfig.iceServers)

        peerConnection =
            peerConnectionFactory.createPeerConnection(
                configuration,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            signalIceCandidate(it)
                            peerConnection?.addIceCandidate(it)
                        }
                    }

                    override fun onDataChannel(channel: DataChannel?) {}

                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}

                    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}

                    override fun onIceConnectionReceivingChange(p0: Boolean) {}

                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

                    override fun onAddStream(p0: MediaStream?) {}

                    override fun onRemoveStream(p0: MediaStream?) {}

                    override fun onRenegotiationNeeded() {}

                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                },
            )

        // Create and set up the data channel
        val conf =
            DataChannel.Init().apply {
                id = offerConfig.id
                ordered = offerConfig.ordered
                protocol = offerConfig.protocol
            }
        dataChannel = peerConnection?.createDataChannel("wamp", conf)
        dataChannel?.registerObserver(
            object : DataChannel.Observer {
                override fun onStateChange() {
                    if (dataChannel?.state() == DataChannel.State.OPEN) {
                        onDataChannelOpen?.invoke()
                    }
                }

                override fun onBufferedAmountChange(p0: Long) {}

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    buffer?.data?.let {
                        val data = ByteArray(it.remaining())
                        it.get(data)

                        val message = assembler.feed(data)
                        if (message != null) {
                            queue.put(message)
                        }
                    }
                }
            },
        )

        return suspendCoroutine { continuation ->
            peerConnection?.createOffer(
                object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        peerConnection?.setLocalDescription(
                            object : SdpObserver {
                                override fun onCreateSuccess(description: SessionDescription?) {}

                                override fun onSetSuccess() {
                                    continuation.resume(description)
                                }

                                override fun onCreateFailure(p0: String?) {}

                                override fun onSetFailure(p0: String?) {}
                            },
                            description,
                        )
                    }

                    override fun onSetSuccess() {}

                    override fun onCreateFailure(p0: String?) {}

                    override fun onSetFailure(p0: String?) {}
                },
                MediaConstraints(),
            )
        }
    }

    suspend fun waitForDataChannelToOpen() {
        withTimeoutOrNull(dataChannelTimeoutMillis) {
            while (true) {
                if (dataChannel?.state() == DataChannel.State.OPEN) return@withTimeoutOrNull
                delay(100)
            }
        } ?: throw IllegalStateException("Data channel failed to open within $dataChannelTimeoutMillis milliseconds")
    }

    fun setRemoteDescription(sessionDescription: SessionDescription) {
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}

                override fun onSetSuccess() {}

                override fun onCreateFailure(p0: String?) {}

                override fun onSetFailure(p0: String?) {}
            },
            sessionDescription,
        )
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }
}
