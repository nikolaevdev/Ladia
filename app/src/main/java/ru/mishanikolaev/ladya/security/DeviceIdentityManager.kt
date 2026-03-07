package ru.mishanikolaev.ladya.security

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

object DeviceIdentityManager {

    private const val PREFS_NAME = "ladya_identity_prefs"
    private const val PREF_PRIVATE_KEY = "private_key_base64"
    private const val PREF_PUBLIC_KEY = "public_key_base64"
    private const val PREF_FINGERPRINT = "fingerprint"

    data class DeviceIdentity(
        val publicKeyBase64: String,
        val privateKeyBase64: String,
        val fullKeyId: String,
        val fingerprint: String
    )

    fun getOrCreateIdentity(context: Context): DeviceIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingPublic = prefs.getString(PREF_PUBLIC_KEY, null)
        val existingPrivate = prefs.getString(PREF_PRIVATE_KEY, null)
        val existingFingerprint = prefs.getString(PREF_FINGERPRINT, null)
        if (!existingPublic.isNullOrBlank() && !existingPrivate.isNullOrBlank()) {
            val fullKeyId = fullKeyIdForPublicKey(existingPublic)
            val fingerprint = existingFingerprint ?: shortFingerprintFromKeyId(fullKeyId)
            if (existingFingerprint.isNullOrBlank()) {
                prefs.edit().putString(PREF_FINGERPRINT, fingerprint).apply()
            }
            return DeviceIdentity(
                publicKeyBase64 = existingPublic,
                privateKeyBase64 = existingPrivate,
                fullKeyId = fullKeyId,
                fingerprint = fingerprint
            )
        }

        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        val keyPair = keyPairGenerator.generateKeyPair()
        val publicKeyBase64 = keyPair.public.encoded.toBase64()
        val privateKeyBase64 = keyPair.private.encoded.toBase64()
        val fullKeyId = fullKeyIdForPublicKey(publicKeyBase64)
        val fingerprint = shortFingerprintFromKeyId(fullKeyId)

        prefs.edit()
            .putString(PREF_PUBLIC_KEY, publicKeyBase64)
            .putString(PREF_PRIVATE_KEY, privateKeyBase64)
            .putString(PREF_FINGERPRINT, fingerprint)
            .apply()

        return DeviceIdentity(
            publicKeyBase64 = publicKeyBase64,
            privateKeyBase64 = privateKeyBase64,
            fullKeyId = fullKeyId,
            fingerprint = fingerprint
        )
    }

    fun signPayload(privateKeyBase64: String, payload: String): String {
        val privateKey = restorePrivateKey(privateKeyBase64)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payload.toByteArray(Charsets.UTF_8))
        return signature.sign().toBase64()
    }

    fun verifySignature(publicKeyBase64: String, payload: String, signatureBase64: String): Boolean {
        return runCatching {
            val publicKey = restorePublicKey(publicKeyBase64)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            signature.verify(signatureBase64.fromBase64())
        }.getOrDefault(false)
    }

    fun fullKeyIdForPublicKey(publicKeyBase64: String): String {
        val publicKeyBytes = publicKeyBase64.fromBase64()
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    fun fingerprintForPublicKey(publicKeyBase64: String): String =
        shortFingerprintFromKeyId(fullKeyIdForPublicKey(publicKeyBase64))

    fun shortFingerprintFromKeyId(keyId: String): String {
        val normalized = keyId.filterNot { it == ':' }.uppercase(Locale.US)
        return normalized.chunked(2).take(8).joinToString(":")
    }

    fun buildHelloPayloadToSign(
        protocolVersion: Int,
        senderPeerId: String,
        publicKeyBase64: String,
        helloNonce: String
    ): String = listOf(
        "HELLO",
        protocolVersion.toString(),
        publicKeyBase64,
        helloNonce
    ).joinToString("|")

    fun buildHelloAckPayloadToSign(
        protocolVersion: Int,
        senderPeerId: String,
        publicKeyBase64: String,
        ackNonce: String,
        echoHelloNonce: String
    ): String = listOf(
        "HELLO_ACK",
        protocolVersion.toString(),
        publicKeyBase64,
        ackNonce,
        echoHelloNonce
    ).joinToString("|")

    fun buildHelloConfirmPayloadToSign(
        protocolVersion: Int,
        senderPeerId: String,
        publicKeyBase64: String,
        echoAckNonce: String
    ): String = listOf(
        "HELLO_CONFIRM",
        protocolVersion.toString(),
        publicKeyBase64,
        echoAckNonce
    ).joinToString("|")

    fun buildProfileSignaturePayload(
        type: String,
        peerId: String,
        publicNick: String,
        avatarEmoji: String,
        publicKeyBase64: String,
        issuedAt: Long
    ): String {
        return listOf(
            type,
            publicNick,
            avatarEmoji,
            publicKeyBase64,
            issuedAt.toString()
        ).joinToString("|")
    }

    fun buildShortAuthString(localPublicKey: String?, remotePublicKey: String?): String? {
        if (localPublicKey.isNullOrBlank() || remotePublicKey.isNullOrBlank()) return null
        val ordered = listOf(
            fullKeyIdForPublicKey(localPublicKey),
            fullKeyIdForPublicKey(remotePublicKey)
        ).sorted()
        val material = "LADYA:SAS:DEVICE|${ordered[0]}|${ordered[1]}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val number = ((digest[0].toInt() and 0xFF) shl 16) or ((digest[1].toInt() and 0xFF) shl 8) or (digest[2].toInt() and 0xFF)
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    private fun restorePublicKey(publicKeyBase64: String): PublicKey {
        val bytes = publicKeyBase64.fromBase64()
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun restorePrivateKey(privateKeyBase64: String): PrivateKey {
        val bytes = privateKeyBase64.fromBase64()
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.DEFAULT)
}
