package ru.mishanikolaev.ladya.security

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object StegoTrustCapsuleManager {

    private val MAGIC = byteArrayOf(0x4C, 0x41, 0x44, 0x59) // LADY

    data class TrustCapsule(
        val version: Int = 1,
        val peerId: String,
        val publicKeyBase64: String,
        val fingerprint: String,
        val signatureBase64: String
    ) {
        fun toJson(): String = JSONObject()
            .put("v", version)
            .put("pid", peerId)
            .put("pk", publicKeyBase64)
            .put("fp", fingerprint)
            .put("sig", signatureBase64)
            .toString()

        companion object {
            fun fromJson(json: String): TrustCapsule {
                val obj = JSONObject(json)
                return TrustCapsule(
                    version = obj.optInt("v", 1),
                    peerId = obj.getString("pid"),
                    publicKeyBase64 = obj.getString("pk"),
                    fingerprint = obj.getString("fp"),
                    signatureBase64 = obj.getString("sig")
                )
            }
        }
    }

    fun embedCapsuleIntoBitmap(source: Bitmap, capsule: TrustCapsule): Bitmap {
        val payload = capsule.toJson().toByteArray(StandardCharsets.UTF_8)
        val framed = MAGIC + ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        val capacityBits = source.width * source.height * 3
        require(framed.size * 8 <= capacityBits) { "Bitmap too small for trust capsule" }

        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        var bitIndex = 0
        for (y in 0 until mutable.height) {
            for (x in 0 until mutable.width) {
                var pixel = mutable.getPixel(x, y)
                val a = (pixel ushr 24) and 0xFF
                var r = (pixel ushr 16) and 0xFF
                var g = (pixel ushr 8) and 0xFF
                var b = pixel and 0xFF
                if (bitIndex < framed.size * 8) r = (r and 0xFE) or getBit(framed, bitIndex++)
                if (bitIndex < framed.size * 8) g = (g and 0xFE) or getBit(framed, bitIndex++)
                if (bitIndex < framed.size * 8) b = (b and 0xFE) or getBit(framed, bitIndex++)
                pixel = (a shl 24) or (r shl 16) or (g shl 8) or b
                mutable.setPixel(x, y, pixel)
                if (bitIndex >= framed.size * 8) return mutable
            }
        }
        return mutable
    }

    fun extractCapsuleFromBitmap(bitmap: Bitmap): TrustCapsule? {
        val bits = ArrayList<Int>(bitmap.width * bitmap.height * 3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                bits += ((pixel ushr 16) and 0xFF) and 1
                bits += ((pixel ushr 8) and 0xFF) and 1
                bits += (pixel and 0xFF) and 1
            }
        }
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var value = 0
            for (bit in 0 until 8) {
                value = (value shl 1) or bits[i * 8 + bit]
            }
            bytes[i] = value.toByte()
        }
        if (bytes.size < 8) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val size = ByteBuffer.wrap(bytes, 4, 4).int
        if (size <= 0 || 8 + size > bytes.size) return null
        val json = bytes.copyOfRange(8, 8 + size).toString(StandardCharsets.UTF_8)
        return runCatching { TrustCapsule.fromJson(json) }.getOrNull()
    }

    fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun getBit(bytes: ByteArray, bitIndex: Int): Int {
        val byteIndex = bitIndex / 8
        val shift = 7 - (bitIndex % 8)
        return (bytes[byteIndex].toInt() ushr shift) and 1
    }
}
