package ru.mishanikolaev.ladya.network.protocol

import org.json.JSONObject

data class TransferManifest(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val chunkSize: Int,
    val totalChunks: Int,
    val checksum: String,
    val senderLabel: String? = null,
    val previewUriString: String? = null
) {
    fun toPayload(): Map<String, Any?> = mapOf(
        "transferId" to transferId,
        "fileName" to fileName,
        "fileSize" to fileSize,
        "mimeType" to mimeType,
        "chunkSize" to chunkSize,
        "totalChunks" to totalChunks,
        "checksum" to checksum,
        "senderLabel" to senderLabel,
        "previewUriString" to previewUriString
    )

    companion object {
        fun fromPayload(payload: JSONObject): TransferManifest {
            return TransferManifest(
                transferId = payload.optString("transferId"),
                fileName = payload.optString("fileName", "received.bin"),
                fileSize = payload.optLong("fileSize"),
                mimeType = payload.optString("mimeType", "application/octet-stream"),
                chunkSize = payload.optInt("chunkSize"),
                totalChunks = payload.optInt("totalChunks", 1),
                checksum = payload.optString("checksum"),
                senderLabel = payload.optString("senderLabel").ifBlank { null },
                previewUriString = payload.optString("previewUriString").ifBlank { null }
            )
        }
    }
}
