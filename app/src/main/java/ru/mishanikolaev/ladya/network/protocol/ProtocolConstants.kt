package ru.mishanikolaev.ladya.network.protocol

object ProtocolConstants {
    const val PROTOCOL_NAME = "Ladya"
    const val PROTOCOL_VERSION = 2
    const val DEFAULT_PACKET_TTL = 4
    const val DEFAULT_MESSAGE_ACK_RETRY_LIMIT = 3
    const val DEFAULT_MESSAGE_ACK_DELAY_MS = 3_000L
    const val DEFAULT_FILE_RETRY_LIMIT = 3
}

const val RELAY_GROUP_FILE = "RELAY_GROUP_FILE"
const val GROUP_FILE = "GROUP_FILE"
