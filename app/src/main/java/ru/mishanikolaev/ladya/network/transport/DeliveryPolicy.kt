package ru.mishanikolaev.ladya.network.transport

import ru.mishanikolaev.ladya.network.protocol.ProtocolConstants

object DeliveryPolicy {
    const val MESSAGE_ACK_RETRY_LIMIT = ProtocolConstants.DEFAULT_MESSAGE_ACK_RETRY_LIMIT
    const val MESSAGE_ACK_DELAY_MS = ProtocolConstants.DEFAULT_MESSAGE_ACK_DELAY_MS
    const val FILE_RETRY_LIMIT = ProtocolConstants.DEFAULT_FILE_RETRY_LIMIT
}
