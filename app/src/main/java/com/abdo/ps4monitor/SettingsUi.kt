@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val s0 = remember { Store.settings() }
    var iv by remember { mutableIntStateOf(s0.interval) }; var to by remember { mutableIntStateOf(s0.timeout) }
    var st by remember { mutableIntStateOf(s0.stuck) }; var ns by remember { mutableIntStateOf(s0.notStarted) }
    var custom by remember { mutableStateOf("") }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = { nav.navigate("settings/ps4s") }, Modifier.fillMaxWidth()) { Text("PS4 consoles") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("Monitoring")
        Text("Polling interval"); Chips(listOf(2, 3, 5, 10), iv, " s") { iv = it; Store.putInt("poll", it) }
        Text("Connection timeout"); Chips(listOf(5, 10, 20, 30), to, " s") { to = it; Store.putInt("timeout", it) }
        Text("Stalled after no growth for"); Chips(listOf(30, 60, 120), st, " s") { st = it; custom = ""; Store.putInt("stuck", it) }
        OutlinedTextField(custom, { custom = it; it.toIntOrNull()?.takeIf { v -> v >= 10 }?.let { v -> st = v; Store.putInt("stuck", v) } },
            label = { Text("Custom stalled threshold (seconds, min 10) — now $st s") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Report “not started” after"); Chips(listOf(60, 120, 180, 300), ns, " s") { ns = it; Store.putInt("notstarted", it) }
        Text("Changes apply on the next poll.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("Notifications")
        ToggleRow("Download notifications", "notif", true)
        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Light", "Dark").forEachIndexed { i, t -> FilterChip(selected = Store.theme.intValue == i, onClick = { Store.setTheme(i) }, label = { Text(t) }) }
        }
        if (!ignoring) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Battery optimization may pause background monitoring on some phones.")
            Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open Battery Optimization settings") }
        } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = { nav.navigate("settings/advanced") }, Modifier.fillMaxWidth()) { Text("Advanced (debug log, history)") }
        Text("PS4 Download Monitor 2.0", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun AdvancedScreen(nav: NavController) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader("Advanced", nav)
        ToggleRow("Auto-monitor growing .tmp files that this app did not start", "auto", false)
        Text("Only applies while at least one download is being monitored on that PS4. A .tmp file is adopted only after it is seen growing.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { nav.navigate("settings/log") }, Modifier.fillMaxWidth()) { Text("Debug log") }
        OutlinedButton(onClick = { nav.navigate("settings/history") }, Modifier.fillMaxWidth()) { Text("Download history") }
    }
}

@Composable fun LogScreen(nav: NavController) {
    val log by DownloadMonitor.log.collectAsState()
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackHeader("Debug log", nav); Spacer(Modifier.weight(1f))
            TextButton(onClick = { DownloadMonitor.clearLog() }) { Text("Clear") }
        }
        if (log.isEmpty()) Text("(empty)")
        LazyColumn { items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable fun HistoryScreen(nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    val fin = all.filter { !it.state.active }.sortedByDescending { it.updatedAt }
    val legacy = remember { Store.legacyHistory() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader("Download history", nav) }
        if (fin.isEmpty() && legacy.isEmpty()) item { Text("Nothing yet.") }
        items(fin, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("${d.state.label} • ${Fmt.bytes(d.currentSize)} • ${Ps4Repo.get(d.ps4Id)?.name ?: "Removed PS4"}", style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.dt(d.completedAt.takeIf { it > 0 } ?: d.updatedAt), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { DownloadMonitor.remove(d.id) }) { Text("Delete") }
            } }
        }
        if (legacy.isNotEmpty()) item { SectionTitle("Earlier versions (read-only)") }
        items(legacy) { o ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(o.optString("name"), fontWeight = FontWeight.Bold)
                Text("${o.optString("status")} • ${Fmt.bytes(o.optLong("size"))} • ${Fmt.dt(o.optLong("end"))}", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable fun Ps4ListScreen(nav: NavController) {
    val list by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader("PS4 consoles", nav)
        if (list.isEmpty()) Text("No PS4 added yet.")
        list.forEach { p ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p.id == activeId, onClick = { Ps4Repo.setActive(p.id) })
                Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Bold); Text("${p.host}  •  ${p.dest}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { nav.navigate("settings/ps4/${p.id}") }) { Text("Edit") }
            } }
        }
        Button(onClick = { nav.navigate("settings/ps4/new") }) { Text("Add PS4") }
    }
}

@Composable fun Ps4EditScreen(id: String, nav: NavController) {
    val existing = remember(id) { Ps4Repo.get(id) }
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "PS4") }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var http by remember { mutableStateOf((existing?.httpPort ?: 8080).toString()) }
    var ftp by remember { mutableStateOf((existing?.ftpPort ?: 2121).toString()) }
    var user by remember { mutableStateOf(existing?.ftpUser.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.ftpPass.orEmpty()) }
    var dest by remember { mutableStateOf(existing?.dest ?: "/data/pkg") }
    var mode by remember { mutableStateOf(existing?.mode ?: MonitorMode.AUTO) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun build() = Ps4(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "PS4" }, host = host.trim(),
        httpPort = http.toIntOrNull() ?: 8080, ftpPort = ftp.toIntOrNull() ?: 2121, ftpUser = user, ftpPass = pass, dest = DownloadMonitor.norm(dest), mode = mode)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackHeader(if (isNew) "Add PS4" else "Edit PS4", nav)
        OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Living Room)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("PS4 IP address / host") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(http, { http = it }, label = { Text("ezRemote web port (default 8080)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(ftp, { ftp = it }, label = { Text("FTP port (default 2121, 0 = off)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(user, { user = it }, label = { Text("FTP username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("FTP password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(dest, { dest = it }, label = { Text("Default destination") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Monitoring source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MonitorMode.AUTO to "Web + FTP fallback", MonitorMode.HTTP_ONLY to "Web only", MonitorMode.FTP_ONLY to "FTP only").forEach { (m, t) -> FilterChip(mode == m, { mode = m }, label = { Text(t) }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result.isNotEmpty()) Text(result, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = { busy = true; result = ""; scope.launch { result = DownloadMonitor.probe(build()); busy = false } }) { Text("Test connection") }
            Button(enabled = host.isNotBlank(), onClick = { Ps4Repo.save(build()); nav.popBackStack() }) { Text("Save") }
        }
        if (!isNew) {
            val inUse = DownloadRepo.all.value.any { it.ps4Id == existing!!.id && it.state.active }
            OutlinedButton(enabled = !inUse, onClick = { Ps4Repo.delete(existing!!.id); nav.popBackStack() }) { Text("Delete this PS4") }
            if (inUse) Text("Cannot delete while downloads are active on this PS4.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
