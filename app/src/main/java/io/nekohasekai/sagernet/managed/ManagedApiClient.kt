package io.nekohasekai.sagernet.managed

import io.nekohasekai.sagernet.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ManagedApiClient {
    data class ManagedConfig(
        val templateName: String,
        val templateVersion: Int,
        val updateIntervalMinutes: Int,
        val subscriptionUrl: String
    )

    fun activate(inviteCode: String): String {
        val identity = ManagedDeviceIdentity.ensureKey()
        val body = JSONObject().apply {
            put("invite_code", inviteCode.trim())
            put("public_key", identity.publicKey)
            put("key_alg", identity.algorithm)
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val result = request("${baseUrl()}/api/v1/activate", "POST", body.toString())
        return result.getString("device_id")
    }

    fun fetchConfig(): ManagedConfig {
        val result = request(ManagedDeviceIdentity.signUrl("${baseUrl()}/api/v1/config"))
        return ManagedConfig(
            templateName = result.optString("template_name", "CMSS-Box"),
            templateVersion = result.optInt("template_version", 1),
            updateIntervalMinutes = result.optInt("update_interval_minutes", 10),
            subscriptionUrl = result.getString("subscription_url")
        )
    }

    private fun request(url: String, method: String = "GET", body: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw ManagedApiException(status, json.optString("code"), json.optString("error", "请求失败"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun baseUrl(): String = BuildConfig.MANAGEMENT_API_URL.trimEnd('/').ifBlank {
        error("MANAGEMENT_API_URL 未配置")
    }
}

class ManagedApiException(val status: Int, val code: String, message: String) : Exception(message) {
    val revoked: Boolean get() = code == "DEVICE_REVOKED" || code == "DEVICE_NOT_FOUND"
}
