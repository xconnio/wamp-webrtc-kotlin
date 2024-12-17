package io.xconn.wampwebrtc

import io.xconn.wampproto.SessionDetails
import io.xconn.wampproto.auth.ClientAuthenticator
import io.xconn.wampproto.messages.Message
import io.xconn.wampproto.serializers.Serializer
import io.xconn.xconn.IBaseSession
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer

interface Peer {
    suspend fun send(data: ByteArray)

    suspend fun sendMessage(message: Message)

    suspend fun receive(): Any

    suspend fun receiveMessage(): Message

    fun close()
}

class PeerBaseSession(
    private val peer: Peer,
    private val sessionDetails: SessionDetails,
    private val serializer: Serializer,
) : IBaseSession {
    override fun id(): Long = sessionDetails.sessionID

    override fun realm(): String = sessionDetails.realm

    override fun authid(): String = sessionDetails.authid

    override fun authrole(): String = sessionDetails.authrole

    override fun serializer(): Serializer = serializer

    override suspend fun send(data: Any) {
        peer.send(data as ByteArray)
    }

    override suspend fun receive(): Any = peer.receive()

    override suspend fun sendMessage(msg: Message) {
        peer.sendMessage(msg)
    }

    override suspend fun receiveMessage(): Message = peer.receiveMessage()

    override suspend fun close() = peer.close()
}

data class ClientConfig(
    val url: String,
    val realm: String,
    val procedureWebRTCOffer: String,
    val topicAnswererOnCandidate: String,
    val topicOffererOnCandidate: String,
    val serializer: Serializer,
    val subProtocol: String,
    val iceServers: List<IceServer>,
    val authenticator: ClientAuthenticator,
)

data class OfferConfig(
    val protocol: String,
    val iceServers: List<IceServer>,
    val ordered: Boolean,
    val id: Int,
    val topicAnswererOnCandidate: String,
)

data class WebRTCSession(
    val connection: PeerConnection,
    val channel: DataChannel,
)
