# PS4 Download Monitor 2.0

Build: open in Android Studio (Koala or newer, JDK 17), sync Gradle, Run / Build APK.
NOT yet compiled or tested by the author of this refactor (no Android SDK was available) — expect to fix a few compile errors on first sync.

Setup: Home -> Add PS4 (IP, ezRemote web port 8080, FTP port 2121 or 0 to disable) -> Test connection -> Save.
Send downloads: Browser -> find link (or ⋮ -> "Send this page's link to PS4") -> choose PS4 / destination -> Send.
Request accepted != download started != download completed; Downloads shows only what the PS4 filesystem proves.

Layout (package com.abdo.ps4monitor):
- Models.kt        Ps4, Download, DlState, Link/Reach/Ps4Status, FsEntry, SubmitResult
- Store.kt         settings, bookmarks, Ps4Repo, DownloadRepo (encrypted JSON persistence)
- EzRemote.kt      confirmed ezRemote calls only: /__local__/download_url, /__local__/list; Net.size (server size check)
- Ftp.kt           FTP helper (short-lived listing sessions)
- DownloadMonitor.kt  shared monitor: HTTP list + FTP fallback, file matching, state machine, speed/ETA, verification, recovery
- Notifier.kt / MonitorService.kt   one notification per download id; foreground service
- MainActivity.kt (nav) / Ui.kt / HomeUi.kt / DownloadsUi.kt / BrowserUi.kt / SettingsUi.kt
Old Engine.kt / Sender.kt (template "Learn" workflow) were removed.
