@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.launch

private fun go(nav: NavController, r: String) = nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }

@Composable fun HomeScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState()
    val activeId by Ps4Repo.activeId.collectAsState()
    val statuses by DownloadMonitor.status.collectAsState()
    val dls by DownloadRepo.all.collectAsState()
    val events by DownloadMonitor.events.collectAsState()
    val untracked by DownloadMonitor.untracked.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    val st = ps4?.let { statuses[it.id] } ?: Ps4Status()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var probeMsg by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    fun refresh() { val p = ps4 ?: return; probing = true; scope.launch { probeMsg = DownloadMonitor.probe(p); probing = false } }
    LaunchedEffect(ps4?.id) { if (ps4 != null) refresh() }      // one light check when Home opens / PS4 changes

    val mine = dls.filter { it.ps4Id == ps4?.id }
    val active = mine.filter { it.state.active }
    val recent = mine.filter { !it.state.active }.sortedByDescending { it.updatedAt }.take(4)
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ClipCard() }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ps4 == null) {
                    Text("No PS4 yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Add your PS4 (IP address) to send and monitor downloads.")
                    Button(onClick = { nav.navigate("settings/ps4/new") }) { Text("Add PS4") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ps4.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${ps4.host}  •  web ${ps4.httpPort}" + if (ps4.ftpPort > 0) "  •  FTP ${ps4.ftpPort}" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        if (ps4s.size > 1) Box {
                            TextButton(onClick = { pick = true }) { Text("Switch ▾") }
                            DropdownMenu(pick, { pick = false }) { ps4s.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { Ps4Repo.setActive(p.id); pick = false }) } }
                        }
                    }
                    Text(when (st.reach) {
                        Reach.REACHABLE -> "🟢 PS4 reachable"
                        Reach.UNREACHABLE -> "🔴 " + st.message.ifBlank { "Cannot reach the PS4" }
                        Reach.UNKNOWN -> "⚪ Not checked yet"
                    }, fontWeight = FontWeight.Bold)
                    Text("ezRemote web: ${st.http.label}   •   FTP: ${st.ftp.label}", style = MaterialTheme.typography.bodySmall)
                    if (probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (probeMsg.isNotBlank()) Text(probeMsg, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !probing, onClick = { refresh() }) { Text("Test / refresh") }
                        OutlinedButton(onClick = { nav.navigate("settings/ps4/${ps4.id}") }) { Text("Edit") }
                        OutlinedButton(onClick = { nav.navigate("settings/ps4/new") }) { Text("Add PS4") }
                    }
                }
            } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Inbox.url.value = "" }, Modifier.weight(1f)) { Text("Download URL") }
                OutlinedButton(onClick = { go(nav, "browser") }, Modifier.weight(1f)) { Text("Browser") }
                OutlinedButton(onClick = { go(nav, "downloads") }, Modifier.weight(1f)) { Text("Downloads") }
            }
        }
        item { SectionTitle("Active downloads: ${active.size}") }
        if (active.isEmpty()) item { Text("Nothing is being downloaded.", style = MaterialTheme.typography.bodySmall) }
        items(active.take(3), key = { it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }) }
        if (active.size > 3) item { TextButton(onClick = { go(nav, "downloads") }) { Text("View all ${active.size}") } }
        val loose = ps4?.let { untracked[it.id].orEmpty() }.orEmpty()
        if (loose.isNotEmpty()) {
            item { SectionTitle("Temporary files on the PS4") }
            item { Text("These .tmp files are not tracked by this app (for example started directly in ezRemote).", style = MaterialTheme.typography.bodySmall) }
            items(loose, key = { it.first + "/" + it.second.name }) { (dir, e) ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.name, maxLines = 1); Text(Fmt.bytes(e.size), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { ps4?.let { DownloadMonitor.adopt(it.id, dir, e) } }) { Text("Monitor") }
                } }
            }
        }
        if (recent.isNotEmpty()) item { SectionTitle("Recent downloads") }
        items(recent, key = { "r" + it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }) }
        if (events.isNotEmpty()) {
            item { SectionTitle("Recent events") }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                events.takeLast(6).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } } }
        }
    }
}
