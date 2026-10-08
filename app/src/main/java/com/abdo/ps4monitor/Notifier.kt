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

/**
 * Two stable channels (never one per download): "active" (ongoing, silent, one notification per download id updated in place) and
 * "results" (completed / failed / not started; replaces the ongoing one; posted once, guarded by Download.terminalNotified).
 */
object Notifier {
    private lateinit var ctx: Context
    private val last = ConcurrentHashMap<Int, Pair<String, Long>>()

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel("active", tr("Active downloads", "التحميلات النشطة"), NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel("results", tr("Download results", "نتائج التحميل"), NotificationManager.IMPORTANCE_HIGH))
    }
    private fun pi() = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    /** The mandatory foreground-service notification (one, id 1). */
    fun summary(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, "active").setSmallIcon(R.drawable.ic_stat_ps4).setContentTitle("PS4 Monitor")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi()).build()
    fun updateSummary(c: Context, text: String) = post(1, summary(c, text), force = true)

    private fun psName(d: Download) = Ps4Repo.get(d.ps4Id)?.name.orEmpty()

    fun progress(d: Download) {
        if (!Store.settings().notif) return
        val exp = d.expectedSize?.takeIf { it > 0 }
        val sizeLine = if (exp != null) "${Fmt.gb(d.currentSize)} / ${Fmt.gb(exp)}" else Fmt.gb(d.currentSize)
        val detail = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(d.pct?.let { "$it%" }, Fmt.mbs(d.speed), if (d.etaSec >= 0) "ETA ${Fmt.dur(d.etaSec)}" else null).joinToString(" • ")
            DlState.STALLED -> tr("Download stalled", "التحميل متعثّر")
            DlState.SERVER_DOWN -> tr("ezRemote Server is not reachable", "خادم ezRemote غير متاح")
            DlState.QUEUED -> tr("Waiting for ezRemote Server…", "بانتظار خادم ezRemote…")
            DlState.VERIFYING -> tr("Checking downloaded file…", "جارٍ فحص الملف…")
            else -> ""
        }
        val showSize = d.currentSize > 0
        val text = listOfNotNull(sizeLine.takeIf { showSize }, detail.takeIf { it.isNotEmpty() }).joinToString("\n")
        // Only touch the notification when something meaningful changed (or at most every 3 s for speed jitter).
        val key = "${d.iconReady}|${d.pkgTitle}|${d.state}|${d.currentSize / (4L shl 20)}|${d.pct}|${d.etaSec / 30}|${(d.speed / 524288).toInt()}"
        val prev = last[d.notificationId]; val now = System.currentTimeMillis()
        if (prev != null && prev.first == key) return
        if (prev != null && now - prev.second < 3000 && prev.first.substringAfter("${d.pkgTitle}|").substringBefore('|') == d.state.name) return
        last[d.notificationId] = key to now
        val b = NotificationCompat.Builder(ctx, "active").setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(d.pkgTitle ?: d.displayName).setSubText(psName(d).ifEmpty { null }).setContentText(text.lines().lastOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifEmpty { d.state.label }))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
        if (d.iconReady) PkgStore.bitmap("d:${d.id}", "icon0.png", 128)?.let { b.setLargeIcon(it) }
        when {
            d.state == DlState.DOWNLOADING && d.pct != null -> b.setProgress(100, d.pct!!, false)
            d.state == DlState.DOWNLOADING || d.state == DlState.QUEUED -> b.setProgress(0, 0, true)
        }
        post(d.notificationId, b.build())
    }

    fun result(d: Download) {
        last.remove(d.notificationId)
        if (!Store.settings().notif) return
        val (title, body) = when (d.state) {
            DlState.COMPLETED -> tr("Download completed", "اكتمل التحميل") to "${Fmt.gb(d.currentSize)} • " + tr("verified", "تم التحقق")
            DlState.FAILED -> tr("Download failed", "فشل التحميل") to Tx.t(d.errorMessage ?: tr("Unknown reason", "سبب غير معروف"))
            else -> tr("Download has not started yet", "لم يبدأ التحميل بعد") to tr("ezRemote accepted the request but no download activity was detected.", "قبل ezRemote الطلب لكن لم يُكتشف أي نشاط تحميل.")
        }
        val n = NotificationCompat.Builder(ctx, "results").setSmallIcon(if (d.state == DlState.COMPLETED) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(d.pkgTitle ?: d.displayName).setContentText("$title\n$body".lines().first()).setSubText(psName(d).ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$body")).setAutoCancel(true).setContentIntent(pi()).also { b -> if (d.iconReady) PkgStore.bitmap("d:${d.id}", "icon0.png", 128)?.let { b.setLargeIcon(it) } }.build()
        post(d.notificationId, n)            // same id as the ongoing one: replaces it, never adds a second
    }

    fun cancel(id: Int) { last.remove(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    private fun post(id: Int, n: Notification, force: Boolean = false) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
