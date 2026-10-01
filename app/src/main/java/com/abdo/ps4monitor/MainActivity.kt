@file:OptIn(ExperimentalMaterial3Api::class)
package com.abdo.ps4monitor
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("""https?://\S+""").find(t)?.value?.let { Inbox.url.value = it }
        }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Browser.init(applicationContext)
        if (Browser.tabs.isEmpty()) Browser.restore()
        runCatching { DownloadMonitor.restore() }
        handleShare(intent)
        setContent { Root() }
    }
    override fun onStop() { super.onStop(); Browser.save() }
}

@Composable fun Root() {
    val dark = when (Store.theme.intValue) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val cs = if (dark) darkColorScheme(primary = Color(0xFFC6A8FF), secondary = Color(0xFF9F86D6))
             else lightColorScheme(primary = Color(0xFF6A3DE8), secondary = Color(0xFF8E6CF0))
    MaterialTheme(colorScheme = cs) { Surface(Modifier.fillMaxSize()) { AppNav() } }
}

private val TABS = listOf(Triple("home", "Home", "🏠"), Triple("browser", "Browser", "🌐"), Triple("downloads", "Downloads", "⬇️"), Triple("settings", "Settings", "⚙️"))

@Composable fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = TABS.any { it.first == route }
    val active by DownloadRepo.all.collectAsState()
    val activeCount = active.count { it.state.active }
    Scaffold(bottomBar = {
        if (topLevel) NavigationBar {
            TABS.forEach { (r, label, glyph) ->
                NavigationBarItem(selected = route == r, label = { Text(label) },
                    icon = { BadgedBox(badge = { if (r == "downloads" && activeCount > 0) Badge { Text("$activeCount") } }) { Text(glyph) } },
                    onClick = { nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("browser") { BrowserScreen() }
            composable("downloads") { DownloadsScreen(nav) }
            composable("downloads/{id}", listOf(navArgument("id") { type = NavType.StringType })) { DownloadDetail(it.arguments?.getString("id").orEmpty(), nav) }
            composable("settings") { SettingsScreen(nav) }
            composable("settings/ps4s") { Ps4ListScreen(nav) }
            composable("settings/ps4/{id}", listOf(navArgument("id") { type = NavType.StringType })) { Ps4EditScreen(it.arguments?.getString("id").orEmpty(), nav) }
            composable("settings/advanced") { AdvancedScreen(nav) }
            composable("settings/log") { LogScreen(nav) }
            composable("settings/history") { HistoryScreen(nav) }
        }
    }
    Inbox.url.value?.let { u ->
        SendDialog(initialUrl = u, onAddPs4 = { Inbox.url.value = null; nav.navigate("settings/ps4/new") },
            onSent = { nav.navigate("downloads") { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
            close = { Inbox.url.value = null })
    }
}
