package ru.mishanikolaev.ladya.network.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PacketFactory {

    fun create(
        type: String,
        senderPeerId: String,
        targetPeerId: String? = null,
        ackId: String? = null,
        ttl: Int = ProtocolConstants.DEFAULT_PACKET_TTL,
        payload: Map<String, Any?> = emptyMap()
    ): String {
        val payloadJson = JSONObject()
        payload.forEach { (key, value) -> payloadJson.put(key, value.normalizeForJson()) }
        return JSONObject().apply {
            put("packetId", UUID.randomUUID().toString())
            put("type", type)
            put("protocol", ProtocolConstants.PROTOCOL_NAME)
            put("version", ProtocolConstants.PROTOCOL_VERSION)
            put("timestamp", System.currentTimeMillis())
            put("peerId", senderPeerId)
            put("senderPeerId", senderPeerId)
            put("targetPeerId", targetPeerId)
            put("ackId", ackId)
            put("ttl", ttl)
            payload.forEach { (key, value) -> put(key, value.normalizeForJson()) }
            put("payload", payloadJson)
        }.toString()
    }

    private fun Any?.normalizeForJson(): Any? {
        return when (this) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply {
                this@normalizeForJson.entries.forEach { (k, v) ->
                    if (k != null) put(k.toString(), v.normalizeForJson())
                }
            }
            is Iterable<*> -> JSONArray().apply {
                this@normalizeForJson.forEach { item -> put(item.normalizeForJson()) }
            }
            is Array<*> -> JSONArray().apply {
                this@normalizeForJson.asList().forEach { item -> put(item.normalizeForJson()) }
            }
            else -> this
        }
    }
}
