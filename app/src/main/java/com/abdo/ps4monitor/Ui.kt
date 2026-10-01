@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Inbox { val url = mutableStateOf<String?>(null) }    // non-null => "Download to PS4" dialog is open

fun stateGlyph(s: DlState) = when (s) {
    DlState.COMPLETED -> "✅"; DlState.FAILED -> "⚠️"; DlState.NOT_STARTED -> "⏸️"; DlState.STOPPED -> "⏹️"
    DlState.STALLED -> "🟡"; DlState.CONNECTION_LOST -> "🔌"; DlState.VERIFYING -> "🔎"; DlState.DOWNLOADING -> "⬇️"
    else -> "🕒"
}

@Composable fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

/** Real state only: no percentage or ETA unless the total size is actually known. */
@Composable fun DownloadCard(d: Download, onOpen: () -> Unit, showRetry: Boolean = true) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val ps4 = Ps4Repo.get(d.ps4Id)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(stateGlyph(d.state), style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(d.displayName, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(ps4?.name ?: "Removed PS4", style = MaterialTheme.typography.bodySmall)
                val exp = d.expectedSize?.takeIf { it > 0 }
                if (d.currentSize > 0 || d.state == DlState.COMPLETED)
                    Text(if (exp != null) "${Fmt.bytes(d.currentSize)} / ${Fmt.bytes(exp)}" + (d.pct?.let { "  •  $it%" } ?: "") else Fmt.bytes(d.currentSize))
                val f = d.frac
                if (f != null && d.state.active) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                else if (d.state == DlState.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())    // total unknown: indeterminate, no fake %
                if (d.state == DlState.DOWNLOADING) Text(Fmt.mbs(d.speed) + if (d.etaSec >= 0) "  •  ETA ${Fmt.dur(d.etaSec)}" else "")
                Text(d.state.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                val extra = d.errorMessage ?: d.note
                if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall)
                if (d.superseded) Text("Replaced by a newer attempt", style = MaterialTheme.typography.bodySmall)
                if (showRetry && !d.superseded && d.sourceUrl.isNotBlank() && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED)) {
                    TextButton(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Text("Retry") }
                }
            }
        }
    }
}

fun resultText(r: SubmitResult) = when (r) {
    is SubmitResult.Accepted -> "Request accepted. Waiting for the PS4 to start the download…"
    is SubmitResult.Rejected -> r.message
    is SubmitResult.Unreachable -> r.message
    is SubmitResult.Duplicate -> r.message
    is SubmitResult.Invalid -> r.message
}

@Composable fun Chart(v: List<Float>, m: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(m) {
        if (v.size < 2) return@Canvas
        val mx = (v.max() * 1.2f).coerceAtLeast(0.1f); val dx = size.width / (v.size - 1)
        val path = Path()
        v.forEachIndexed { i, y -> val px = i * dx; val py = size.height * (1 - y / mx); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

@Composable fun Chips(opts: List<Int>, sel: Int, unit: String, on: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { FilterChip(selected = it == sel, onClick = { on(it) }, label = { Text("$it$unit") }) } }
}
@Composable fun ToggleRow(label: String, key: String, def: Boolean) {
    var v by remember { mutableStateOf(Store.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = { v = it; Store.sp.edit().putBoolean(key, it).apply() })
    }
}
@Composable fun BackHeader(title: String, nav: androidx.navigation.NavController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { nav.popBackStack() }) { Text("←") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable fun ClipCard() {
    val clip = LocalClipboardManager.current
    var link by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        delay(500)
        val m = Regex("""https?://\S+""").find(clip.getText()?.text.orEmpty())?.value
        if (m != null && m != Store.sp.getString("lastclip", "")) link = m
    }
    val l = link
    if (l != null) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("Link in clipboard:", fontWeight = FontWeight.Bold)
        Text(l.take(70) + if (l.length > 70) "…" else "", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { Store.sp.edit().putString("lastclip", l).apply(); Inbox.url.value = l; link = null }) { Text("Download to PS4") }
            TextButton(onClick = { Store.sp.edit().putString("lastclip", l).apply(); link = null }) { Text("Dismiss") }
        }
    } }
}

/** Direct ezRemote request: choose PS4 -> confirm destination -> send. No template/learn step exists any more. */
@Composable fun SendDialog(initialUrl: String = "", onAddPs4: () -> Unit, onSent: () -> Unit, close: () -> Unit) {
    val ps4s by Ps4Repo.list.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }
    var sel by remember { mutableStateOf(Ps4Repo.activeId.value ?: ps4s.firstOrNull()?.id) }
    val p = ps4s.firstOrNull { it.id == sel }
    var dest by remember(sel) { mutableStateOf(p?.dest ?: "/data/pkg") }
    var sizeTxt by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Download to PS4") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ps4s.isEmpty()) {
                Text("Add a PS4 first.")
                TextButton(onClick = onAddPs4) { Text("Add PS4") }
            } else {
                if (ps4s.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ps4s.forEach { FilterChip(sel == it.id, { sel = it.id }, label = { Text(it.name) }) } }
                else Text("PS4: ${ps4s[0].name}", fontWeight = FontWeight.Bold)
                OutlinedTextField(url, { url = it }, label = { Text("Link(s), one per line") }, minLines = 2, maxLines = 6)
                OutlinedTextField(dest, { dest = it }, label = { Text("Destination on the PS4") }, singleLine = true)
                OutlinedTextField(sizeTxt, { sizeTxt = it }, label = { Text("File size (optional), e.g. 47.5 GB") }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.Bold)
                Text("The PS4 downloads the file itself through ezRemote. This app only sends the request and then watches the PS4 to see what really happens. " +
                     "Leave the size empty if unknown; no percentage or ETA is shown without it.", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(enabled = !busy && p != null && Regex("""https?://""").containsMatchIn(url), onClick = {
            val target = p ?: return@TextButton
            busy = true; msg = ""
            scope.launch {
                val links = Regex("""https?://\S+""").findAll(url).map { it.value }.toList().distinct().take(10)
                val out = StringBuilder(); var ok = 0
                links.forEachIndexed { i, l ->
                    if (i > 0) delay(4000)                        // give ezRemote time between requests
                    val r = DownloadMonitor.submit(target, l, dest, if (links.size == 1) Fmt.parseSize(sizeTxt).takeIf { sizeTxt.isNotBlank() } ?: 0L else 0L)
                    if (r is SubmitResult.Accepted) ok++
                    out.append(if (links.size > 1) "${i + 1}/${links.size}: " else "").append(resultText(r)).append('\n'); msg = out.toString()
                }
                busy = false
                if (ok == links.size && ok > 0) { Toast.makeText(ctx, if (ok == 1) "Request accepted. Waiting for the PS4 to start…" else "$ok requests accepted.", Toast.LENGTH_LONG).show(); close(); onSent() }
            }
        }) { Text("Send") } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Close") } })
}
