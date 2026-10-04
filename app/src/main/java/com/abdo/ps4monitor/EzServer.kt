package com.abdo.ps4monitor
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One download known to ezRemote Server (the background downloader). `path` is the FINAL file path; the data is written to "<path>.tmp". */
class SrvEntry(val path: String, val bytes: Long, val size: Long, val state: Int, val ts: Long) {
    val pending get() = state == 0
    val active get() = state == 1 || state == 2          // DOWNLOADING, RESUMED
    val failed get() = state == 3
    val done get() = state == 4
}

/**
 * ezRemote Server, source read from ps4-ezremote-server (server/http_server.cpp, config.h, clients/baseclient.cpp).
 * It listens on 0.0.0.0:6701 and is the component that really downloads:
 *   GET /get_download_state -> [{path, bytes_transfered, file_size, state, timestamp}]   state: 0 PENDING, 1 DOWNLOADING, 2 RESUMED, 3 FAILED, 4 SUCCESS
 *   GET /version, GET /stop (terminates the whole server process: every background download and install stops until it is relaunched).
 * It has NO per-download cancel / pause / remove. `file_size` is whatever ezRemote Client measured (may be garbage if that measurement failed),
 * so it is range-checked before use.
 */
object EzServer {
    const val PORT = 6701
    private fun get(ps4: Ps4, path: String, timeoutMs: Int): String {
        val c = URL("http://${ps4.host}:$PORT$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try {
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            if (code !in 200..299) throw EzError("ezRemote Server answered HTTP $code.")
            return text
        } finally { c.disconnect() }
    }
    fun list(ps4: Ps4, timeoutMs: Int): List<SrvEntry> {
        val a = JSONArray(get(ps4, "/get_download_state", timeoutMs))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            SrvEntry(o.optString("path"), o.optLong("bytes_transfered"), o.optLong("file_size"), o.optInt("state", -1), o.optLong("timestamp")) }
    }
    fun version(ps4: Ps4, timeoutMs: Int): String? = try { get(ps4, "/version", timeoutMs).trim().ifBlank { null } } catch (e: Exception) { null }
    /** Terminates ezRemote Server on the PS4. Returns true when it no longer answers afterwards. */
    fun stop(ps4: Ps4): Boolean {
        try { get(ps4, "/stop", 3000) } catch (e: Exception) { }
        Thread.sleep(2000)
        return try { list(ps4, 2000); false } catch (e: Exception) { true }
    }
    fun newest(l: List<SrvEntry>, path: String): SrvEntry? = l.filter { it.path == path }.maxByOrNull { it.ts }
    fun sane(size: Long) = size in (1L shl 16)..(1L shl 42)
}
