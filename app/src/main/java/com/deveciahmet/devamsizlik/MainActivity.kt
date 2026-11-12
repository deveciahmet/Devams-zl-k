package com.deveciahmet.devamsizlik

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

sealed interface Screen {
    // Alt çubuktaki sekmeler
    data object Home : Screen
    data object Exams : Screen
    data object Settings : Screen

    data object Fill : Screen
    data object Import : Screen
    data object PromptImport : Screen
    data object Holidays : Screen
    data object Archives : Screen
    data class ArchiveDetail(val id: String) : Screen

    /** [focus]: açılınca o derse (oturum anahtarı) kaydırılır. */
    data class Course(val code: String, val focus: String? = null) : Screen
    data class CourseEdit(val code: String) : Screen
}

/** Basit geri yığını. Kökte bir sekme durur; diğer ekranlar onun üstüne açılır. */
class Nav(private val stack: SnapshotStateList<Screen>) {
    val current: Screen get() = stack.last()
    val isRoot: Boolean get() = stack.size == 1

    /** Geri tuşu bir şey yapacak mı (kökte Dersler sekmesi değilse oraya döner). */
    val canGoBack: Boolean get() = stack.size > 1 || current != Screen.Home

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (current != Screen.Home) stack[0] = Screen.Home
    }

    fun tab(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun replace(old: Screen, new: Screen) {
        for (i in stack.indices) if (stack[i] == old) stack[i] = new
    }

    /** Bildirimden ya da widget'tan gelen istek. */
    fun open(req: OpenRequest) {
        when (req.target) {
            OpenRequest.COURSE -> {
                tab(Screen.Home)
                req.code?.let { push(Screen.Course(it, req.focus)) }
            }
            OpenRequest.FILL -> {
                tab(Screen.Home)
                push(Screen.Fill)
            }
            OpenRequest.EXAMS -> tab(Screen.Exams)
            OpenRequest.IMPORT -> {
                tab(Screen.Settings)
                push(Screen.Import)
            }
            else -> tab(Screen.Home)
        }
    }
}

class MainActivity : ComponentActivity() {

    /** Veriler değiştikçe (bildirimden cevap verilse bile) arayüzü yenilemek için sayaç. */
    private val version = mutableIntStateOf(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version.intValue++ }

    /** Bildirimden / widget'tan gelen "şu ekranı aç" isteği. */
    private val request = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        if (savedInstanceState == null) request.value = OpenRequest.from(intent)
        setContent {
            AppTheme {
                PermissionRequester()
                App(version.intValue, request.value) { request.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenRequest.from(intent)?.let { request.value = it }
    }

    override fun onStart() {
        super.onStart()
        Repo.attendance(this).registerOnSharedPreferenceChangeListener(listener)
        Repo.settings(this).registerOnSharedPreferenceChangeListener(listener)
        Archive.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        Scheduler.reschedule(this)
        version.intValue++
        if (Itu.autoFill) {
            // Adı boş dersler varsa İTÜ'nün açılan dersler listesinden sessizce doldur (günde en fazla bir kez)
            val app = applicationContext
            Thread { Itu.autoFillIfNeeded(app) }.start()
        }
    }

    override fun onStop() {
        Repo.attendance(this).unregisterOnSharedPreferenceChangeListener(listener)
        Repo.settings(this).unregisterOnSharedPreferenceChangeListener(listener)
        Archive.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }
}

@Composable
private fun PermissionRequester() {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Color(0xFF2E7D32))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(version: Int, request: OpenRequest? = null, onRequestHandled: () -> Unit = {}) {
    val ctx = LocalContext.current
    val snap = remember(version) { Repo.snapshot(ctx) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Nav(stack) }
    LaunchedEffect(request) {
        if (request != null) {
            nav.open(request)
            onRequestHandled()
        }
    }
    BackHandler(enabled = nav.canGoBack) { nav.pop() }
    when (val s = nav.current) {
        Screen.Home -> HomeScreen(snap, nav)
        Screen.Exams -> ExamsScreen(snap, nav)
        Screen.Settings -> SettingsScreen(snap, nav)
        Screen.Fill -> SessionsScreen(snap, null, nav)
        Screen.Import -> KeplerImportScreen(snap, nav)
        Screen.PromptImport -> PromptImportScreen(nav)
        Screen.Holidays -> HolidaysScreen(snap, nav)
        Screen.Archives -> ArchivesScreen(snap, nav)
        is Screen.ArchiveDetail -> ArchiveDetailScreen(snap, s.id, nav)
        is Screen.Course -> SessionsScreen(snap, s.code, nav, s.focus)
        is Screen.CourseEdit -> CourseEditScreen(snap, s.code, nav)
    }
}
