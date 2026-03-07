package ru.mishanikolaev.ladya.network.protocol

import org.json.JSONObject

data class PacketEnvelope(
    val packetId: String,
    val type: String,
    val protocol: String,
    val version: Int,
    val timestamp: Long,
    val senderPeerId: String,
    val targetPeerId: String? = null,
    val ackId: String? = null,
    val ttl: Int = ProtocolConstants.DEFAULT_PACKET_TTL,
    val payload: JSONObject = JSONObject()
)
