package io.xconn.wampwebrtc

import android.content.Context
import io.xconn.wampproto.serializers.CBORSerializer
import io.xconn.xconn.Client
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque

class WebRTC(
    private val context: Context,
    private val queue: LinkedBlockingDeque<ByteArray>,
) {
    suspend fun connect(config: ClientConfig): WebRTCSession {
        val client = Client(serializer = CBORSerializer())
        val session = client.connect(config.url, config.realm)

        val requestID = UUID.randomUUID().toString()
        val offerConfig =
            OfferConfig(
                config.subProtocol,
                config.iceServers,
                true,
                1,
                config.topicAnswererOnCandidate,
            )

        val candidates: MutableList<IceCandidate> = mutableListOf()
        val offerer =
            Offerer(
                context,
                queue,
                signalIceCandidate = { candidate ->
                    candidates.add(candidate)
                    GlobalScope.launch {
                        session.publish(
                            offerConfig.topicAnswererOnCandidate,
                            listOf(
                                requestID,
                                JSONObject(
                                    mapOf(
                                        "sdpMid" to candidate.sdpMid,
                                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                                        "candidate" to candidate.sdp,
                                    ),
                                ).toString(),
                            ),
                        )
                    }
                },
            )

        val offer = offerer.createOffer(offerConfig)
        Thread.sleep(200)

        val candidatesList =
            candidates.map { candidate ->
                mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp,
                )
            }

        val sdpData =
            mapOf(
                "description" to mapOf("type" to "offer", "sdp" to offer?.description),
                "candidates" to candidatesList,
            )
        val json = JSONObject(sdpData).toString()

        val res = session.call(config.procedureWebRTCOffer, listOf(requestID, json)).await()
        val jsonString = res.args?.get(0) as String

        val result = convertJsonToMap(jsonString)

        val remoteCandidates = result["candidates"] as JSONArray
        for (i in 0 until remoteCandidates.length()) {
            val candidateObject = remoteCandidates.getJSONObject(i)
            val candidate = candidateObject.getString("candidate")
            val sdpMid = candidateObject.optString("sdpMid")
            val sdpMLineIndex = candidateObject.optInt("sdpMLineIndex")

            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            offerer.addIceCandidate(iceCandidate)
        }

        val descriptionMap = result["description"] as JSONObject

        val sdpString = descriptionMap.getString("sdp")
        val sdpType = descriptionMap.getString("type")

        val remoteDescription =
            SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpType), sdpString)

        offerer.setRemoteDescription(remoteDescription)

        offerer.waitForDataChannelOpen()

        return WebRTCSession(offerer.peerConnection!!, offerer.dataChannel!!)
    }
}
