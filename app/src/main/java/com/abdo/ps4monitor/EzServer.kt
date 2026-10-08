package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One download known to ezRemote Server. `path` is the FINAL file path; the data is written to "<path>.tmp". */
class SrvEntry(val path: String, val bytes: Long, val size: Long, val state: Int, val ts: Long) {
    val pending get() = state == 0
    val active get() = state == 1 || state == 2          // DOWNLOADING, RESUMED
    val failed get() = state == 3
    val done get() = state == 4
}

/**
 * ezRemote Server (ps4-ezremote-server): the component that really downloads. Listens on 0.0.0.0:6701.
 *   GET /get_download_state -> [{path, bytes_transfered, file_size, state, timestamp}]  state: 0 PENDING, 1 DOWNLOADING, 2 RESUMED, 3 FAILED, 4 SUCCESS
 *   GET /stop               -> terminates the whole server process (every download and install stops)
 * It downloads one file at a time, retries a failed one up to 5 times and has no per-download cancel/pause/remove.
 * `file_size` is what ezRemote Client measured (garbage when that failed), so it is range-checked.
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
    fun up(ps4: Ps4): Boolean = try { list(ps4, 3000); true } catch (e: Exception) { false }
    /** Terminates ezRemote Server. True when it no longer answers afterwards. */
    fun stop(ps4: Ps4): Boolean {
        try { get(ps4, "/stop", 3000) } catch (e: Exception) { }
        Thread.sleep(2000)
        return !up(ps4)
    }
    fun newest(l: List<SrvEntry>, path: String): SrvEntry? = l.filter { it.path == path }.maxByOrNull { it.ts }
    fun sane(size: Long) = size in (1L shl 16)..(1L shl 42)
}

/** One element of /data/ezremote-client/bg_download_history.json (credentials are never read). */
class BgEntry(val dest: String, val size: Long, val bytes: Long, val state: Int, val attempts: Int, val ts: Long) {
    /** FAILED with failed_attempts >= 5: ezRemote Server never retries it again. */
    val stopped get() = state == 3 && attempts >= 5
}

/**
 * bg_download_history.json is the persistent list of ezRemote Server. It is read ONCE when the server starts; the running server uses its
 * memory copy and rewrites the file only on some events, so edits only take effect after the server is stopped and started again.
 * At start-up an entry resumes when state==1 or (state==3 and failed_attempts<5), continuing from the .tmp size.
 * Edits here are text-level on the target element only (64-bit ids stay byte-identical), validated and read back.
 */
object BgHistory {
    const val PATH = "/data/ezremote-client/bg_download_history.json"
    private val cache = ConcurrentHashMap<String, Pair<Long, List<BgEntry>>>()
    class Edit(val ok: Boolean, val message: String)

    private fun readText(p: Ps4): String {
        val size = EzRemote.fileSize(p, PATH, 8000)
        if (size <= 0) throw EzError("bg_download_history.json was not found on the PS4.")
        if (size > (2L shl 20)) throw EzError("bg_download_history.json is unexpectedly large.")
        val t = String(EzRemote.readRange(p, PATH, 0, size.toInt(), 10_000), Charsets.UTF_8)
        val end = t.lastIndexOf(']')
        if (end < 0) throw EzError("bg_download_history.json is not a JSON list.")
        return t.substring(0, end + 1)
    }
    private fun parse(t: String): List<BgEntry> {
        val a = JSONArray(t)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            BgEntry(o.optString("dest_path"), o.optLong("file_size"), o.optLong("bytes_transfered"), o.optInt("state", -1), o.optInt("failed_attempts"), o.optLong("timestamp")) }
    }
    fun read(p: Ps4): List<BgEntry> = parse(readText(p))
    /** At most one read every 15 s per PS4; null when the file cannot be read. */
    fun cached(p: Ps4): List<BgEntry>? {
        val c = cache[p.id]; val now = System.currentTimeMillis()
        if (c != null && now - c.first < 15_000) return c.second
        val l = try { read(p) } catch (e: Exception) { null }
        if (l != null) cache[p.id] = now to l
        return l ?: c?.second
    }
    fun entry(l: List<BgEntry>?, dest: String): BgEntry? = l?.filter { it.dest == dest }?.maxByOrNull { it.ts }

    /** [start, end) of every top-level object of the JSON array (string-aware). */
    private fun spans(t: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(); var depth = 0; var inStr = false; var esc = false; var start = -1
        for (i in t.indices) {
            val c = t[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> { depth++; if (depth == 2) start = i }
                '}' -> { if (depth == 2 && start >= 0) { out += start to (i + 1); start = -1 }; depth-- }
                '[' -> depth++
                ']' -> depth--
            }
        }
        return out
    }
    private fun destOf(raw: String, s: Pair<Int, Int>): Pair<String, Long>? = try { JSONObject(raw.substring(s.first, s.second)).let { it.optString("dest_path") to it.optLong("timestamp") } } catch (e: Exception) { null }

    private fun write(p: Ps4, out: String, expectEntries: Int): Edit {
        val check = try { parse(out) } catch (e: Exception) { return Edit(false, "The edited file would not be valid JSON; nothing was written.") }
        if (check.size != expectEntries) return Edit(false, "The edit would change the number of entries unexpectedly; nothing was written.")
        val r = EzRemote.edit(p, PATH, out)
        cache.remove(p.id)
        return if (r.ok) Edit(true, "") else Edit(false, r.message)
    }

    /** state=3 and failed_attempts=[attempts] on the newest entry for [dest]. Only call while ezRemote Server is NOT running. */
    fun mark(p: Ps4, dest: String, attempts: Int): Edit = try {
        val raw = readText(p); val spans = spans(raw)
        var best = -1; var bestTs = Long.MIN_VALUE
        spans.forEachIndexed { i, s -> val d = destOf(raw, s); if (d != null && d.first == dest && d.second >= bestTs) { best = i; bestTs = d.second } }
        if (best < 0) Edit(false, "This download is not listed in bg_download_history.json.") else {
            val (a, b) = spans[best]
            val reA = Regex("(\"failed_attempts\"\\s*:\\s*)-?\\d+"); val reS = Regex("(\"state\"\\s*:\\s*)-?\\d+")
            var el = raw.substring(a, b)
            el = if (reA.containsMatchIn(el)) el.replace(reA) { it.groupValues[1] + attempts } else el.trimEnd().removeSuffix("}") + ",\"failed_attempts\":$attempts}"
            el = if (reS.containsMatchIn(el)) el.replace(reS) { it.groupValues[1] + "3" } else el.trimEnd().removeSuffix("}") + ",\"state\":3}"
            val w = write(p, raw.substring(0, a) + el + raw.substring(b), spans.size)
            if (!w.ok) w else {
                val back = entry(read(p), dest)
                if (back != null && back.attempts == attempts && back.state == 3) w else Edit(false, "The PS4 file did not keep the change.")
            }
        }
    } catch (e: Exception) { Edit(false, EzRemote.friendly(e)) }

    /** Removes every entry for [dest] (the server forgets the download at its next start). ok=true also when it was not listed. */
    fun remove(p: Ps4, dest: String): Edit = try {
        var raw = readText(p); val total = spans(raw).size; var removed = 0
        while (removed < 50) {
            val sp = spans(raw); val idx = sp.indexOfLast { destOf(raw, it)?.first == dest }
            if (idx < 0) break
            val (a, b) = sp[idx]; var s = a; var e = b
            var i = a - 1; while (i >= 0 && raw[i].isWhitespace()) i--
            if (i >= 0 && raw[i] == ',') s = i
            else { var j = b; while (j < raw.length && raw[j].isWhitespace()) j++; if (j < raw.length && raw[j] == ',') e = j + 1 }
            raw = raw.substring(0, s) + raw.substring(e); removed++
        }
        if (removed == 0) Edit(true, "") else {
            val w = write(p, raw, total - removed)
            if (!w.ok) w else if (entry(read(p), dest) == null) w else Edit(false, "The PS4 file still lists this download.")
        }
    } catch (e: Exception) { Edit(false, EzRemote.friendly(e)) }
}
