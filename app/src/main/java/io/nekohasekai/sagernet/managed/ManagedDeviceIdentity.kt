package io.nekohasekai.sagernet.managed

import android.annotation.SuppressLint
import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import java.math.BigInteger
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

object ManagedDeviceIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "cmss_managed_device"

    val isActivated: Boolean
        get() = DataStore.managedDeviceId.isNotBlank() && hasKey()

    fun ensureKey(): PublicIdentity {
        if (!hasKey()) generateKey()
        val certificate = keyStore().getCertificate(KEY_ALIAS)
            ?: error("设备密钥生成失败")
        return PublicIdentity(
            algorithm = if (certificate.publicKey.algorithm.equals("EC", true)) "ES256" else "RS256",
            publicKey = encode(certificate.publicKey.encoded)
        )
    }

    fun signManagedUrl(url: String): String {
        if (!isManagedSubscriptionUrl(url)) return url
        return signUrl(url)
    }

    fun signUrl(url: String): String {
        val deviceId = DataStore.managedDeviceId.takeIf { it.isNotBlank() }
            ?: error("设备尚未激活")
        val uri = URI(url)
        val path = uri.rawPath.ifBlank { "/" }
        val timestamp = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).let(::encode)
        val canonical = "GET\n$path\n$deviceId\n$timestamp\n$nonce"
        val publicIdentity = ensureKey()
        val signature = keyStore().getKey(KEY_ALIAS, null) as java.security.PrivateKey
        val signer = Signature.getInstance(
            if (publicIdentity.algorithm == "ES256") "SHA256withECDSA" else "SHA256withRSA"
        )
        signer.initSign(signature)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        val signed = signer.sign().let {
            if (publicIdentity.algorithm == "ES256") derToP1363(it) else it
        }
        val separator = if (url.contains('?')) '&' else '?'
        return buildString {
            append(url)
            append(separator)
            append("device_id=").append(urlEncode(deviceId))
            append("&ts=").append(timestamp)
            append("&nonce=").append(urlEncode(nonce))
            append("&alg=").append(publicIdentity.algorithm)
            append("&sig=").append(urlEncode(encode(signed)))
        }
    }

    fun clear() {
        DataStore.managedDeviceId = ""
        DataStore.managedLastVerifiedAt = 0L
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    fun isManagedSubscriptionUrl(url: String): Boolean {
        val base = BuildConfig.MANAGEMENT_API_URL.trimEnd('/')
        return base.isNotBlank() && url.startsWith("$base/api/v1/subscription")
    }

    private fun hasKey(): Boolean = runCatching { keyStore().containsAlias(KEY_ALIAS) }.getOrDefault(false)

    @SuppressLint("ObsoleteSdkInt")
    private fun generateKey() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }.generateKeyPair()
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val spec = KeyPairGeneratorSpec.Builder(SagerNet.application as Context)
                .setAlias(KEY_ALIAS)
                .setSubject(X500Principal("CN=CMSS-Box Device"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        if ((der[1].toInt() and 0x80) != 0) offset += der[1].toInt() and 0x7f
        require(der[offset++].toInt() == 0x02)
        val rLength = der[offset++].toInt() and 0xff
        val r = der.copyOfRange(offset, offset + rLength)
        offset += rLength
        require(der[offset++].toInt() == 0x02)
        val sLength = der[offset++].toInt() and 0xff
        val s = der.copyOfRange(offset, offset + sLength)
        return ByteArray(64).also {
            r.copyIntoFixed(it, 0)
            s.copyIntoFixed(it, 32)
        }
    }

    private fun ByteArray.copyIntoFixed(target: ByteArray, targetOffset: Int) {
        val sourceOffset = (size - 32).coerceAtLeast(0)
        val length = (size - sourceOffset).coerceAtMost(32)
        copyInto(target, targetOffset + 32 - length, sourceOffset, sourceOffset + length)
    }

    data class PublicIdentity(val algorithm: String, val publicKey: String)
}
