@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID

class BTab(val id: String, val web: WebView, val openerId: String?) {
    var title by mutableStateOf("New tab")
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)
    var icon by mutableStateOf<Bitmap?>(null)
}

/** Tabs live here (process-level), not in composition: switching sections or rotating never destroys a page. */
object Browser {
    const val HOME = "https://www.google.com"
    lateinit var app: Context
    val tabs = mutableStateListOf<BTab>()
    var activeId by mutableStateOf<String?>(null)
    fun init(c: Context) { app = c.applicationContext }
    fun current() = tabs.firstOrNull { it.id == activeId }
    fun get(id: String?) = tabs.firstOrNull { it.id == id }

    fun newTab(url: String?, opener: String? = null, select: Boolean = true): BTab {
        val tab = BTab(UUID.randomUUID().toString(), WebView(app), opener)
        setup(tab); tabs.add(tab)
        if (select || activeId == null) activeId = tab.id
        if (url != null) tab.web.loadUrl(url)
        return tab
    }
    fun close(id: String) {
        val i = tabs.indexOfFirst { it.id == id }; if (i < 0) return
        val t = tabs.removeAt(i)
        (t.web.parent as? ViewGroup)?.removeView(t.web); t.web.stopLoading(); t.web.destroy()
        if (activeId == id) activeId = get(t.openerId)?.id ?: tabs.getOrNull(minOf(i, tabs.lastIndex))?.id    // back to the page that opened it
        if (tabs.isEmpty()) newTab(HOME)
    }
    fun save() = Store.sp.edit().putString("tabs", JSONArray(tabs.mapNotNull { it.url.takeIf { u -> u.startsWith("http") } }).toString())
        .putInt("tabsel", tabs.indexOfFirst { it.id == activeId }).apply()
    fun restore() {
        val a = runCatching { JSONArray(Store.sp.getString("tabs", "[]")) }.getOrDefault(JSONArray())
        val sel = Store.sp.getInt("tabsel", 0)
        for (i in 0 until a.length()) newTab(a.getString(i), select = i == sel)
        if (tabs.isEmpty()) newTab(HOME)
    }
    private fun sync(t: BTab) { t.canBack = t.web.canGoBack(); t.canFwd = t.web.canGoForward() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(tab: BTab) {
        val w = tab.web
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false           // popups without a user gesture are blocked
            loadWithOverviewMode = true; useWideViewPort = true; mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url?.toString().orEmpty()
                return !(u.startsWith("http://") || u.startsWith("https://"))        // swallow intent:/market: etc.; never leave the app
            }
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { if (u != null) tab.url = u; tab.icon = null; sync(tab) }
            override fun onPageFinished(v: WebView?, u: String?) { sync(tab) }
            override fun doUpdateVisitedHistory(v: WebView?, u: String?, isReload: Boolean) { if (u != null) tab.url = u; sync(tab) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) { tab.progress = p }
            override fun onReceivedTitle(v: WebView?, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
            override fun onReceivedIcon(v: WebView?, icon: Bitmap?) { tab.icon = icon }
            // Links/ads that open a window become a NEW tab; the original page is never replaced.
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, msg: Message?): Boolean {
                val m = msg ?: return false
                val transport = m.obj as? WebView.WebViewTransport ?: return false
                val nt = newTab(null, opener = tab.id, select = isUserGesture)       // gesture-less popups open in the background
                transport.webView = nt.web; m.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView?) { tabs.firstOrNull { it.web === window }?.let { close(it.id) } }
        }
        w.setDownloadListener { u, _, _, _, _ ->
            if (u.startsWith("http")) Inbox.url.value = u else Toast.makeText(app, "Unsupported link type", Toast.LENGTH_SHORT).show()
        }
    }
}

fun iconToB64(b: Bitmap?): String {
    if (b == null) return ""
    val s = Bitmap.createScaledBitmap(b, 64, 64, true)
    return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray().let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
fun b64ToBitmap(s: String): Bitmap? = if (s.isEmpty()) null else runCatching { Base64.decode(s, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

/** Clean square favicon container with a fallback glyph. */
@Composable fun Favicon(bmp: Bitmap?, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) else Text("🌐")
    }
}

@Composable fun BrowserScreen() {
    val ctx = LocalContext.current
    val tab = Browser.current()
    var addr by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBm by remember { mutableStateOf(false) }
    LaunchedEffect(tab?.id, tab?.url) { if (!focused) addr = tab?.url.orEmpty() }
    // Back: page history first, then close a popup tab back to its opener; otherwise normal navigation takes over.
    BackHandler(enabled = tab != null && (tab.canBack || Browser.get(tab.openerId) != null)) {
        if (tab!!.canBack) tab.web.goBack() else Browser.close(tab.id)
    }
    fun go() {
        val t = addr.trim(); if (t.isEmpty()) return
        val u = if ("://" in t) t else if ("." in t && " " !in t) "https://$t" else "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        Browser.current()?.web?.loadUrl(u)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = tab?.canBack == true, onClick = { tab?.web?.goBack() }, contentPadding = PaddingValues(6.dp), modifier = Modifier.width(36.dp)) { Text("‹") }
            TextButton(enabled = tab?.canFwd == true, onClick = { tab?.web?.goForward() }, contentPadding = PaddingValues(6.dp), modifier = Modifier.width(36.dp)) { Text("›") }
            TextButton(onClick = { if ((tab?.progress ?: 100) < 100) tab?.web?.stopLoading() else tab?.web?.reload() }, contentPadding = PaddingValues(6.dp), modifier = Modifier.width(36.dp)) {
                Text(if ((tab?.progress ?: 100) < 100) "✕" else "⟳") }
            OutlinedTextField(addr, { addr = it }, singleLine = true, modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                placeholder = { Text("Search or enter address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
            TextButton(onClick = { showTabs = true }, contentPadding = PaddingValues(6.dp)) { Text("▢ ${Browser.tabs.size}") }
            Box {
                TextButton(onClick = { menu = true }, contentPadding = PaddingValues(6.dp), modifier = Modifier.width(36.dp)) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("New tab") }, onClick = { menu = false; Browser.newTab(Browser.HOME) })
                    DropdownMenuItem(text = { Text("Add bookmark") }, onClick = {
                        menu = false
                        tab?.takeIf { it.url.startsWith("http") }?.let { Store.addBookmark(Bookmark(it.title, it.url, iconToB64(it.icon))); Toast.makeText(ctx, "Bookmark saved", Toast.LENGTH_SHORT).show() }
                    })
                    DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { menu = false; showBm = true })
                    DropdownMenuItem(text = { Text("Send this page's link to PS4") }, onClick = { menu = false; tab?.url?.takeIf { it.startsWith("http") }?.let { Inbox.url.value = it } })
                }
            }
        }
        if ((tab?.progress ?: 100) < 100) LinearProgressIndicator(progress = { (tab?.progress ?: 0) / 100f }, Modifier.fillMaxWidth())
        if (tab != null) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { FrameLayout(it) },
            update = { fl ->
                val wv = tab.web
                if (fl.childCount != 1 || fl.getChildAt(0) !== wv) {
                    fl.removeAllViews(); (wv.parent as? ViewGroup)?.removeView(wv)
                    fl.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }, onRelease = { it.removeAllViews() })
    }

    if (showTabs) AlertDialog(onDismissRequest = { showTabs = false }, title = { Text("Tabs (${Browser.tabs.size})") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Browser.tabs.toList().forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { Browser.activeId = t.id; showTabs = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(t.icon, 32)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(t.title, maxLines = 1, fontWeight = if (t.id == Browser.activeId) FontWeight.Bold else FontWeight.Normal)
                        Text(t.url, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { Browser.close(t.id) }) { Text("✕") }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { Browser.newTab(Browser.HOME); showTabs = false }) { Text("New tab") } },
        dismissButton = { TextButton(onClick = { showTabs = false }) { Text("Close") } })

    if (showBm) {
        var bms by remember { mutableStateOf(Store.bookmarks()) }
        AlertDialog(onDismissRequest = { showBm = false }, title = { Text("Bookmarks") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bms.isEmpty()) Text("No bookmarks yet. Open a site and use ⋮ → Add bookmark.")
                bms.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable { Browser.current()?.web?.loadUrl(b.url); showBm = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Favicon(b64ToBitmap(b.icon), 36)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(b.title.ifBlank { b.url }, maxLines = 1); Text(b.url, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { Store.deleteBookmark(b.url); bms = Store.bookmarks() }) { Text("✕") }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showBm = false }) { Text("Close") } })
    }
}
