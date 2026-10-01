package com.abdo.ps4monitor
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Three stable channels (never one per download):
 *   active  = "Active downloads"   (ongoing, silent, one notification per download id, updated in place)
 *   results = "Download results"   (completed / failed / not started; replaces the ongoing one; posted once, guarded by Download.terminalNotified)
 *   alerts  = "Connection / errors"
 */
object Notifier {
    private lateinit var ctx: Context
    private val last = ConcurrentHashMap<Int, Pair<String, Long>>()

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel("active", "Active downloads", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel("results", "Download results", NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel("alerts", "Connection / errors", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pi() = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    /** The mandatory foreground-service notification (one, id 1). */
    fun summary(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, "active").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("PS4 Monitor")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi()).build()
    fun updateSummary(c: Context, text: String) = post(1, summary(c, text), force = true)

    private fun psName(d: Download) = Ps4Repo.get(d.ps4Id)?.name.orEmpty()

    fun progress(d: Download) {
        if (!Store.settings().notif) return
        val exp = d.expectedSize?.takeIf { it > 0 }
        val sizeLine = if (exp != null) "${Fmt.gb(d.currentSize)} / ${Fmt.gb(exp)}" else Fmt.gb(d.currentSize)
        val detail = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(d.pct?.let { "$it%" }, Fmt.mbs(d.speed), if (d.etaSec >= 0) "ETA ${Fmt.dur(d.etaSec)}" else null).joinToString(" • ")
            DlState.STALLED -> "Download stalled"
            DlState.CONNECTION_LOST -> "PS4 monitoring connection interrupted. Reconnecting…"
            DlState.QUEUED, DlState.WAITING_FOR_START -> "Waiting for PS4 download to start…"
            DlState.STARTING -> "Starting…"
            DlState.VERIFYING -> "Checking downloaded file…"
            else -> ""
        }
        val showSize = d.currentSize > 0
        val text = listOfNotNull(sizeLine.takeIf { showSize }, detail.takeIf { it.isNotEmpty() }).joinToString("\n")
        // Only touch the notification when something meaningful changed (or at most every 3 s for speed jitter).
        val key = "${d.state}|${d.currentSize / (4L shl 20)}|${d.pct}|${d.etaSec / 30}|${(d.speed / 524288).toInt()}"
        val prev = last[d.notificationId]; val now = System.currentTimeMillis()
        if (prev != null && prev.first == key) return
        if (prev != null && now - prev.second < 3000 && prev.first.substringBefore('|') == d.state.name) return
        last[d.notificationId] = key to now
        val b = NotificationCompat.Builder(ctx, "active").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(d.displayName).setSubText(psName(d).ifEmpty { null }).setContentText(text.lines().lastOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifEmpty { d.state.label }))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
        when {
            d.state == DlState.DOWNLOADING && d.pct != null -> b.setProgress(100, d.pct!!, false)
            d.state == DlState.DOWNLOADING || d.state == DlState.STARTING || d.state == DlState.QUEUED || d.state == DlState.WAITING_FOR_START -> b.setProgress(0, 0, true)
        }
        post(d.notificationId, b.build())
    }

    fun result(d: Download) {
        last.remove(d.notificationId)
        if (!Store.settings().notif) return
        val (title, body) = when (d.state) {
            DlState.COMPLETED -> "Download completed" to "${Fmt.gb(d.currentSize)} • verified"
            DlState.FAILED -> "Download failed" to (d.errorMessage ?: "Unknown reason")
            else -> "Download has not started yet" to "ezRemote accepted the request but no download activity was detected."
        }
        val n = NotificationCompat.Builder(ctx, "results").setSmallIcon(if (d.state == DlState.COMPLETED) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(d.displayName).setContentText("$title\n$body".lines().first()).setSubText(psName(d).ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$body")).setAutoCancel(true).setContentIntent(pi()).build()
        post(d.notificationId, n)            // same id as the ongoing one: replaces it, never adds a second
    }

    fun connection(p: Ps4, lost: Boolean) {
        val id = 5000 + abs(p.id.hashCode() % 1000)
        if (!lost) { cancel(id); return }
        if (!Store.settings().notif) return
        post(id, NotificationCompat.Builder(ctx, "alerts").setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("${p.name}: monitoring connection lost").setContentText("Reconnecting… Downloads on the PS4 may still be running.")
            .setOnlyAlertOnce(true).setContentIntent(pi()).build())
    }

    fun cancel(id: Int) { last.remove(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    private fun post(id: Int, n: Notification, force: Boolean = false) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
