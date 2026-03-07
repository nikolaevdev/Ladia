package ru.mishanikolaev.ladya.network.protocol

import org.json.JSONObject
import java.util.UUID

object PacketParser {

    fun parse(raw: String): PacketEnvelope {
        val json = JSONObject(raw)
        val payload = json.optJSONObject("payload") ?: extractPayload(json)
        return PacketEnvelope(
            packetId = json.optString("packetId").ifBlank { UUID.randomUUID().toString() },
            type = json.optString("type"),
            protocol = json.optString("protocol").ifBlank { ProtocolConstants.PROTOCOL_NAME },
            version = json.optInt("version", 1),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            senderPeerId = json.optString("senderPeerId").ifBlank { json.optString("peerId") },
            targetPeerId = json.optString("targetPeerId").ifBlank { null },
            ackId = json.optString("ackId").ifBlank { null },
            ttl = json.optInt("ttl", ProtocolConstants.DEFAULT_PACKET_TTL),
            payload = payload
        )
    }

    private fun extractPayload(root: JSONObject): JSONObject {
        val payload = JSONObject()
        val iterator = root.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in RESERVED_KEYS) {
                payload.put(key, root.opt(key))
            }
        }
        return payload
    }

    private val RESERVED_KEYS = setOf(
        "packetId",
        "type",
        "protocol",
        "version",
        "timestamp",
        "peerId",
        "senderPeerId",
        "targetPeerId",
        "ackId",
        "ttl",
        "payload"
    )
}
