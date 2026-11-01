package za.michael.stremioapp

import java.net.HttpURLConnection
import java.net.URL

/** Status 0 means the device couldn't be reached. */
data class HttpResult(val status: Int, val body: String)

object Http {
    fun request(device: Device, method: String, path: String, body: String?, timeoutMs: Int = 3000): HttpResult {
        return try {
            val conn = URL(device.url + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("X-Token", device.token)
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs + 3000
            if (!body.isNullOrEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            HttpResult(code, text)
        } catch (e: Exception) {
            HttpResult(0, e.message ?: "unreachable")
        }
    }
}
