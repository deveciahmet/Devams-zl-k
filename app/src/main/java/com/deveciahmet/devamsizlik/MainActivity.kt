package com.deveciahmet.devamsizlik

import android.Manifest
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

sealed interface Screen {
    data object Home : Screen
    data object Fill : Screen
    data object Settings : Screen
    data object Import : Screen
    data class Course(val code: String) : Screen
    data class CourseEdit(val code: String) : Screen
}

/** Basit geri yığını. */
class Nav(private val stack: SnapshotStateList<Screen>) {
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replace(old: Screen, new: Screen) {
        for (i in stack.indices) if (stack[i] == old) stack[i] = new
    }
}

class MainActivity : ComponentActivity() {

    /** Veriler değiştikçe (bildirimden cevap verilse bile) arayüzü yenilemek için sayaç. */
    private val version = mutableIntStateOf(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        setContent {
            AppTheme {
                PermissionRequester()
                App(version.intValue)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Repo.attendance(this).registerOnSharedPreferenceChangeListener(listener)
        Repo.settings(this).registerOnSharedPreferenceChangeListener(listener)
        Scheduler.reschedule(this)
        version.intValue++
    }

    override fun onStop() {
        Repo.attendance(this).unregisterOnSharedPreferenceChangeListener(listener)
        Repo.settings(this).unregisterOnSharedPreferenceChangeListener(listener)
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
fun App(version: Int) {
    val ctx = LocalContext.current
    val snap = remember(version) { Repo.snapshot(ctx) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Nav(stack) }
    BackHandler(enabled = stack.size > 1) { nav.pop() }
    when (val s = nav.current) {
        Screen.Home -> HomeScreen(snap, nav)
        Screen.Fill -> SessionsScreen(snap, null, nav)
        Screen.Settings -> SettingsScreen(snap, nav)
        Screen.Import -> ImportScreen(nav)
        is Screen.Course -> SessionsScreen(snap, s.code, nav)
        is Screen.CourseEdit -> CourseEditScreen(snap, s.code, nav)
    }
}
