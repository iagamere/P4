package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One element of /data/ezremote-client/bg_download_history.json (credentials are never read). */
class BgEntry(val id: Long, val dest: String, val url: String, val size: Long, val bytes: Long, val state: Int, val attempts: Int, val ts: Long) {
    /** ezRemote Server will not retry this one any more (FAILED with 5 attempts): it is "stopped". */
    val stopped get() = state == 3 && attempts >= 5
}

/**
 * bg_download_history.json is the persistent list of ezRemote Server (server/config.cpp). Facts from its source:
 *  - it is loaded ONCE, when the server starts; the running server works on its in-memory copy and rewrites the file only on some events
 *    (download started, finished, or failed_attempts reaching 5), so bytes_transfered in the file is a snapshot, not live progress;
 *  - at start-up an entry is resumed when state is DOWNLOADING(1), or FAILED(3) with failed_attempts < 5 (it continues from the .tmp size);
 *  - an entry with state 3 and failed_attempts 5 is never retried.
 */
object BgHistory {
    const val PATH = "/data/ezremote-client/bg_download_history.json"
    private val cache = ConcurrentHashMap<String, Pair<Long, List<BgEntry>>>()

    fun readText(p: Ps4): String? {
        val size = PkgInspector.sizeOf(p, PATH) ?: return null
        if (size <= 0 || size > (2L shl 20)) return null
        val t = String(PkgInspector.readBytes(p, PATH, 0, size.toInt()), Charsets.UTF_8)
        val end = t.lastIndexOf(']')
        return if (end >= 0) t.substring(0, end + 1) else null
    }

    private fun parse(t: String): List<BgEntry>? = try {
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            BgEntry(o.optLong("id"), o.optString("dest_path"), o.optString("url") + o.optString("src_path"), o.optLong("file_size"),
                o.optLong("bytes_transfered"), o.optInt("state", -1), o.optInt("failed_attempts"), o.optLong("timestamp")) }
    } catch (e: Exception) { null }

    fun read(p: Ps4): List<BgEntry>? = readText(p)?.let { parse(it) }

    /** Read at most every 15 s per PS4 (the monitor calls this every poll). */
    fun cached(p: Ps4): List<BgEntry>? {
        val c = cache[p.id]; val now = System.currentTimeMillis()
        if (c != null && now - c.first < 15_000) return c.second
        val l = try { read(p) } catch (e: Exception) { null }
        if (l != null) cache[p.id] = now to l
        return l ?: c?.second
    }
    fun forget(p: Ps4) { cache.remove(p.id) }
    fun entry(l: List<BgEntry>?, dest: String): BgEntry? = l?.filter { it.dest == dest }?.maxByOrNull { it.ts }

    /** [start, end) of every top-level object of the JSON array, string-aware. */
    fun spans(t: String): List<Pair<Int, Int>> {
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

    class Edit(val ok: Boolean, val message: String)

    /**
     * Sets state=3 (FAILED) and failed_attempts=[attempts] on the newest entry for [dest]. Only those two numbers change: the rest of the
     * file stays byte-identical (a re-serialised file could alter 64-bit ids). The result is read back and checked.
     * Must only be called while ezRemote Server is NOT running, otherwise its in-memory list would overwrite the edit.
     */
    fun mark(p: Ps4, dest: String, attempts: Int): Edit {
        if (!PkgInspector.httpOn(p)) return Edit(false, "Editing the history file needs the ezRemote web connection, which is off for this PS4.")
        val raw = readText(p) ?: return Edit(false, "Could not read bg_download_history.json on the PS4.")
        val spans = spans(raw)
        var best = -1; var bestTs = Long.MIN_VALUE
        spans.forEachIndexed { i, (a, b) ->
            val o = try { JSONObject(raw.substring(a, b)) } catch (e: Exception) { null }
            if (o != null && o.optString("dest_path") == dest && o.optLong("timestamp") >= bestTs) { best = i; bestTs = o.optLong("timestamp") }
        }
        if (best < 0) return Edit(false, "This download is not listed in bg_download_history.json.")
        val (a, b) = spans[best]
        var el = raw.substring(a, b)
        val reAttempts = Regex("(\"failed_attempts\"\\s*:\\s*)-?\\d+"); val reState = Regex("(\"state\"\\s*:\\s*)-?\\d+")
        el = if (reAttempts.containsMatchIn(el)) el.replace(reAttempts) { it.groupValues[1] + attempts } else el.trimEnd().removeSuffix("}") + ",\"failed_attempts\":$attempts}"
        el = if (reState.containsMatchIn(el)) el.replace(reState) { it.groupValues[1] + "3" } else el.trimEnd().removeSuffix("}") + ",\"state\":3}"
        val out = raw.substring(0, a) + el + raw.substring(b)
        val check = parse(out) ?: return Edit(false, "The edited file would not be valid JSON; nothing was written.")
        if (check.size != spans.size) return Edit(false, "The edit would change the number of entries; nothing was written.")
        val r = EzRemote.edit(p, PATH, out)
        if (!r.ok) return Edit(false, r.message)
        forget(p)
        val back = read(p)?.let { entry(it, dest) } ?: return Edit(false, "Written, but the file could not be read back to verify.")
        return if (back.attempts == attempts && back.state == 3) Edit(true, "") else Edit(false, "The PS4 file did not keep the change.")
    }
}
