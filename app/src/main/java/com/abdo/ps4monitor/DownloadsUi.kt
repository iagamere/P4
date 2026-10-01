@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable fun DownloadsScreen(nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val active = all.filter { it.state.active }
    val done = all.filter { it.state == DlState.COMPLETED }
    val failed = all.filter { it.state == DlState.FAILED || it.state == DlState.NOT_STARTED || it.state == DlState.STOPPED }
    val shown = when (tab) { 0 -> active; 1 -> done; else -> failed }.sortedByDescending { it.createdAt }
    // No "Paused" tab: ezRemote exposes no confirmed pause API, so pausing would be fake.
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { Inbox.url.value = "" }) { Text("+ URL") }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Active ${active.size}", "Completed ${done.size}", "Failed ${failed.size}").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        if (shown.isEmpty()) Text("Nothing here.", Modifier.padding(top = 16.dp))
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }) }
        }
    }
}

@Composable fun DownloadDetail(id: String, nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    val untracked by DownloadMonitor.untracked.collectAsState()
    val d = all.firstOrNull { it.id == id }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader("Download", nav)
        if (d == null) { Text("This download was removed."); return@Column }
        val ps4 = Ps4Repo.get(d.ps4Id)
        Text(d.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${ps4?.name ?: "Removed PS4"}  •  attempt ${d.attempt}", style = MaterialTheme.typography.bodySmall)
        Card { Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(d.state.label.uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            (d.errorMessage ?: d.note).takeIf { it.isNotBlank() }?.let { Text(it) }
        } }
        Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val exp = d.expectedSize?.takeIf { it > 0 }
            Text("Size on PS4: " + Fmt.bytes(d.currentSize))
            if (exp != null) {
                Text("Total: ${Fmt.bytes(exp)}  (${d.expectedSource.ifBlank { "known" }})")
                d.frac?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().height(10.dp)) }
                Text("Progress: ${d.pct}%")
            } else {
                Text("Total: unknown — no percentage or ETA is shown")
                if (d.state.active) {
                    var st by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(st, { st = it }, label = { Text("Set size, e.g. 47.5 GB") }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = { val b = Fmt.parseSize(st); if (b > 0) DownloadMonitor.setExpected(d.id, b) }) { Text("Set") }
                    }
                }
            }
            Text("ETA: " + if (d.etaSec >= 0) Fmt.dur(d.etaSec) else "--")
        } }
        if (d.state.active || d.peakSpeed > 0) Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Speed (from file growth): ${Fmt.mbs(d.speed)}  •  ${Fmt.mbit(d.speed)}")
            Text("Average (30 s): ${Fmt.mbs(d.avgSpeed)}   Peak: ${Fmt.mbs(d.peakSpeed)}")
            if (d.speeds.size > 1) Chart(d.speeds.map { it / 1048576f }, Modifier.fillMaxWidth().height(120.dp))
        } }
        if (d.tempPath == null && d.state.active) {
            val dir = DownloadMonitor.norm(d.dest)
            val cands = untracked[d.ps4Id].orEmpty().filter { it.first == dir }
            if (cands.isNotEmpty()) Card { Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Match the PS4 file yourself", fontWeight = FontWeight.Bold)
                Text("These .tmp files exist but could not be matched with certainty. Only choose one if you know it is this download.", style = MaterialTheme.typography.bodySmall)
                cands.forEach { (_, e) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.name, maxLines = 1); Text(Fmt.bytes(e.size), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { DownloadMonitor.assignFile(d.id, e.name) }) { Text("This one") }
                } }
            } }
        }
        Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Destination: ${d.dest}", style = MaterialTheme.typography.bodySmall)
            d.tempPath?.let { Text("Temporary file: $it", style = MaterialTheme.typography.bodySmall) }
            d.finalPath?.let { Text("Final file: $it", style = MaterialTheme.typography.bodySmall) }
            if (d.sourceUrl.isNotBlank()) Text("Link: ${d.sourceUrl.take(120)}", style = MaterialTheme.typography.bodySmall)
            Text("Sent: ${Fmt.dt(d.submittedAt)}   Started: ${Fmt.dt(d.startedAt)}   Finished: ${Fmt.dt(d.completedAt)}", style = MaterialTheme.typography.bodySmall)
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.state.active) Button(onClick = { DownloadMonitor.stop(d.id) }) { Text("Stop monitoring") }
            if (!d.superseded && d.sourceUrl.isNotBlank() && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED, DlState.STOPPED))
                Button(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Text("Retry") }
            if (!d.superseded && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED, DlState.STOPPED))
                OutlinedButton(onClick = { DownloadMonitor.resume(d.id) }) { Text("Resume monitoring") }
            if (!d.state.active) OutlinedButton(onClick = { DownloadMonitor.remove(d.id); nav.popBackStack() }) { Text("Remove") }
        }
        if (d.state.active) Text("“Stop monitoring” only stops this app from watching; it does not cancel the download on the PS4. " +
            "Retry sends a NEW request and is never done automatically.", style = MaterialTheme.typography.bodySmall)
    }
}
