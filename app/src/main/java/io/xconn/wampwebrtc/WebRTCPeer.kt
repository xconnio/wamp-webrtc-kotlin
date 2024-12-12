package io.xconn.wampwebrtc

import io.xconn.wampproto.messages.Message
import io.xconn.wampproto.serializers.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque

class WebRTCPeer(
    var channel: DataChannel?,
    private var serializer: Serializer,
    private var queue: LinkedBlockingDeque<ByteArray>,
) : Peer {
    private var assembler = MessageAssembler()

    override suspend fun send(data: ByteArray) {
        for (chunk in assembler.chunkMessage(data)) {
            channel?.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), false))
        }
    }

    override suspend fun sendMessage(message: Message) {
        val byteMessage = serializer.serialize(message)
        send(byteMessage as ByteArray)
    }

    override suspend fun receive(): Any =
        withContext(Dispatchers.IO) {
            queue.take()
        }

    override suspend fun receiveMessage(): Message = serializer.deserialize(receive())

    override fun close() {
        channel?.close()
    }
}
