package io.xconn.wampwebrtc

import io.xconn.wampproto.Joiner
import org.json.JSONObject

fun convertJsonToMap(jsonString: String): Map<String, Any> {
    val jsonObject = JSONObject(jsonString)
    val map = mutableMapOf<String, Any>()

    jsonObject.keys().forEach { key ->
        val value = jsonObject.get(key)
        map[key] = value
    }

    return map
}

suspend fun join(
    peer: Peer,
    config: ClientConfig,
): PeerBaseSession {
    val joiner = Joiner(config.realm, config.serializer, config.authenticator)
    val hello = joiner.sendHello()

    peer.send(hello as ByteArray)

    while (true) {
        val msg = peer.receive()
        val toSend = joiner.receive(msg)

        if (toSend == null) {
            val sessionDetails = joiner.getSessionDetails()
            val base = PeerBaseSession(peer, sessionDetails, config.serializer)
            return base
        }

        peer.send(toSend as ByteArray)
    }
}
