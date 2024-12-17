package io.xconn.wampwebrtc

import android.content.Context
import io.xconn.xconn.Client
import io.xconn.xconn.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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
    private lateinit var offerer: Offerer
    private val couroutineScope = CoroutineScope(Dispatchers.Default + Job())

    suspend fun connect(config: ClientConfig): WebRTCSession {
        val client = Client(serializer = config.serializer)
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
        offerer =
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

        couroutineScope.launch {
            session.subscribe(config.topicOffererOnCandidate, ::candidateHandler).await()
        }

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

        val remoteDescription = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdpType), sdpString)

        offerer.setRemoteDescription(remoteDescription)

        offerer.waitForDataChannelToOpen()

        return WebRTCSession(offerer.peerConnection!!, offerer.dataChannel!!)
    }

    private fun candidateHandler(event: Event) {
        if (event.args == null || event.args!!.size < 2) {
            throw Exception("invalid arguments length")
        }

        val jsonString =
            event.args?.get(1) as? String
                ?: throw Exception("Invalid argument type: Second argument must be a JSON string")

        val result =
            try {
                convertJsonToMap(jsonString)
            } catch (e: Exception) {
                throw Exception("Invalid JSON: Unable to parse JSON string")
            }

        val candidate =
            result["candidate"] as? String
                ?: throw Exception("Invalid candidate: 'candidate' field is missing or not a string")

        val sdpMLineIndex =
            result["sdpMLineIndex"] as? Int
                ?: throw Exception("Invalid sdpMLineIndex: 'sdpMLineIndex' field is missing or not an integer")

        val sdpMid =
            result["sdpMid"] as? String
                ?: throw Exception("Invalid sdpMid: 'sdpMid' field is missing or not a string")

        try {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            offerer.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            throw Exception("Failed to add ICE candidate: ${e.message}")
        }
    }
}
