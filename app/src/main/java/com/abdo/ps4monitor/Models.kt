package com.abdo.ps4monitor

import java.util.UUID

enum class MonitorMode { AUTO, HTTP_ONLY, FTP_ONLY }

/** One PS4 console. ftpPort = 0 disables FTP for this console. */
data class Ps4(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val host: String,
    val httpPort: Int = 8080, val ftpPort: Int = 2121,
    val ftpUser: String = "", val ftpPass: String = "",
    val dest: String = "/data/pkg", val mode: MonitorMode = MonitorMode.AUTO
)

/** Separate concepts, never merged: request state / http / ftp / reachability / download state. */
enum class Link(val label: String) {
    UNKNOWN("Not checked"), AVAILABLE("Available"), UNAVAILABLE("Unavailable"),
    RECONNECTING("Reconnecting…"), STANDBY("Standby (fallback)"), DISABLED("Off")
}
enum class Reach { UNKNOWN, REACHABLE, UNREACHABLE }
data class Ps4Status(
    val http: Link = Link.UNKNOWN, val ftp: Link = Link.UNKNOWN, val reach: Reach = Reach.UNKNOWN,
    val lastOkAt: Long = 0, val message: String = ""
)

enum class DlState(val label: String, val active: Boolean) {
    SUBMITTING("Sending to PS4", true),
    QUEUED("Queued", true),
    WAITING_FOR_START("Waiting for PS4 download to start", true),
    STARTING("Starting", true),
    DOWNLOADING("Downloading", true),
    STALLED("Stalled", true),
    CONNECTION_LOST("PS4 monitoring connection lost", true),
    VERIFYING("Verifying", true),
    COMPLETED("Completed", false),
    FAILED("Failed", false),
    STOPPED("Monitoring stopped", false),
    NOT_STARTED("Download not started", false)
}

data class Download(
    val id: String, val ps4Id: String, val attempt: Int = 1, val retryOf: String? = null,
    val sourceUrl: String, val displayName: String, val dest: String,
    val tempPath: String? = null, val finalPath: String? = null,
    val expectedSize: Long? = null, val expectedSource: String = "",
    val currentSize: Long = 0, val speed: Double = 0.0, val avgSpeed: Double = 0.0, val peakSpeed: Double = 0.0,
    val etaSec: Long = -1,
    val state: DlState = DlState.SUBMITTING, val note: String = "",
    val createdAt: Long, val submittedAt: Long = 0, val startedAt: Long = 0, val completedAt: Long = 0,
    val lastSeenAt: Long = 0, val errorMessage: String? = null, val notificationId: Int,
    /** Files (name -> size) that existed in dest before submission; null = snapshot failed. */
    val baseline: Map<String, Long>? = null,
    val superseded: Boolean = false, val terminalNotified: Boolean = false,
    val updatedAt: Long = createdAt,
    val speeds: List<Float> = emptyList()          // runtime only, not persisted
) {
    val pct: Int? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize * 100 / it).toInt().coerceIn(0, 100) }
    val frac: Float? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class FsEntry(val name: String, val size: Long, val mtime: Long = 0, val isDir: Boolean = false)

sealed class SubmitResult {
    data class Accepted(val id: String, val note: String = "") : SubmitResult()
    data class Rejected(val message: String) : SubmitResult()
    data class Unreachable(val message: String) : SubmitResult()
    data class Duplicate(val message: String) : SubmitResult()
    data class Invalid(val message: String) : SubmitResult()
}
