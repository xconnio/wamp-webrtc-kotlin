package io.xconn.wampwebrtc

import android.content.Context
import io.xconn.xconn.Session
import java.util.concurrent.LinkedBlockingDeque

class WAMPSession(
    private val context: Context,
) {
    suspend fun connect(config: ClientConfig): Session {
        val queue = LinkedBlockingDeque<ByteArray>()
        val webRTCConnection = WebRTC(context, queue)
        val webRTCSession = webRTCConnection.connect(config)

        val peer = WebRTCPeer(webRTCSession.channel, config.serializer, queue)
        val baseSession = join(peer, config)

        return Session(baseSession)
    }
}
