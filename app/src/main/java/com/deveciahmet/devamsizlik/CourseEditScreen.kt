@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Derse özel ayarlar: ad, kod, saatler, dönemdeki ders sayısı, devam şartı / gelmeme hakkı, yoklama sayısı. */
@Composable
fun CourseEditScreen(snap: Snapshot, code: String, nav: Nav) {
    val ctx = LocalContext.current
    val cfg0 = remember(code) { snap.config(code) }
    val courseSlots = remember(snap, code) {
        snap.slots.filter { it.code == code }.sortedWith(compareBy<Slot>({ it.day }, { it.start }))
    }
    val sessionCount = remember(snap, code) { snap.sessions.count { it.slot.code == code } }

    var codeText by rememberSaveable(code) { mutableStateOf(code) }
    var name by rememberSaveable(code) { mutableStateOf(cfg0.name) }
    var autoTotal by rememberSaveable(code) { mutableStateOf(cfg0.totalSessions == null) }
    var totalText by rememberSaveable(code) { mutableStateOf((cfg0.totalSessions ?: sessionCount).toString()) }
    var mode by rememberSaveable(code) { mutableStateOf(cfg0.mode) }
    var percentText by rememberSaveable(code) {
        mutableStateOf((if (cfg0.mode == LimitMode.PERCENT) cfg0.requiredPercent else snap.requiredPercent).toString())
    }
    var countText by rememberSaveable(code) { mutableStateOf(cfg0.allowedCount.toString()) }
    var polls by rememberSaveable(code) { mutableIntStateOf(cfg0.polls) }
    var fillNew by rememberSaveable(code) { mutableStateOf(true) }
    var editingSlot by remember { mutableStateOf<Slot?>(null) }
    var addingSlot by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val draft = CourseConfig(
        name = name.trim(),
        totalSessions = if (autoTotal) null else totalText.toIntOrNull()?.takeIf { it > 0 },
        mode = mode,
        requiredPercent = percentText.toIntOrNull()?.coerceIn(0, 100) ?: snap.requiredPercent,
        allowedCount = countText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        polls = polls,
    )
    val preview = remember(draft, snap) {
        val cfgs = mapOf(code to draft)
        computeStats(buildPolls(snap.sessions.filter { it.slot.code == code }, cfgs), snap::status, cfgs,
            snap.requiredPercent, snap.now).firstOrNull()
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun save() {
        val newCode = normalizeCode(codeText)
        when {
            newCode.isBlank() -> return toast("Ders kodu boş olamaz")
            !autoTotal && draft.totalSessions == null -> return toast("Dönemdeki ders sayısını gir")
            mode == LimitMode.PERCENT && percentText.toIntOrNull()?.takeIf { it in 0..100 } == null ->
                return toast("Devam şartı 0–100 arasında olmalı")
            mode == LimitMode.COUNT && countText.toIntOrNull() == null -> return toast("Gelmeme hakkını sayı olarak gir")
        }
        val saved = Repo.saveCourse(ctx, code, newCode, draft)
        if (fillNew && polls > cfg0.polls) Repo.fillNewPolls(ctx, saved, cfg0.polls)
        Scheduler.reschedule(ctx, resetCursor = true)
        toast("Kaydedildi")
        nav.pop()
        if (saved != code) nav.replace(Screen.Course(code), Screen.Course(saved))
    }

    Scaffold(topBar = { BackBar("Ders ayarları", nav::pop) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Ders")
            OutlinedTextField(codeText, { codeText = it }, label = { Text("Ders kodu") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Ders adı (isteğe bağlı)") },
                placeholder = { Text("örn. Akışkanlar Mekaniği II") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Hint("Ders adı uygulamada, bildirimlerde ve widget'ta kodun yerine görünür.")

            HorizontalDivider()
            SectionTitle("Ders saatleri")
            courseSlots.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { editingSlot = s }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${dayLongName(s.day)}  ${timeRange(s.start, s.end)}")
                        if (s.place.isNotBlank()) Hint(s.place)
                    }
                    Icon(Icons.Default.Edit, "Saati düzenle")
                }
            }
            TextButton(onClick = { addingSlot = true }) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Saat ekle")
            }

            HorizontalDivider()
            SectionTitle("Dönemde kaç kez yapılacak?")
            SwitchRow(
                "Programdan otomatik hesapla",
                autoTotal,
                "${snap.weeks} hafta × haftada ${courseSlots.size} = $sessionCount ders",
            ) { autoTotal = it }
            if (!autoTotal) {
                NumberField("Dönemdeki ders sayısı", totalText, Modifier.fillMaxWidth()) { totalText = it }
                Hint("Elle girdiğinde \"Ders yapılmadı\" işaretleri toplamdan ayrıca düşülmez.")
            }

            HorizontalDivider()
            SectionTitle("Devam şartı")
            ChoiceRow(
                listOf(
                    LimitMode.DEFAULT to "Varsayılan (%${snap.requiredPercent})",
                    LimitMode.PERCENT to "Yüzde",
                    LimitMode.COUNT to "Gelmeme hakkı",
                ),
                mode,
            ) { mode = it }
            when (mode) {
                LimitMode.DEFAULT -> Hint("Ayarlar'daki genel devam şartı kullanılır.")
                LimitMode.PERCENT -> {
                    NumberField("Devam şartı (%)", percentText, Modifier.fillMaxWidth()) { percentText = it }
                    Hint("Derslerin en az yüzde kaçına girmen gerekiyor? Örn. 70 yazarsan en fazla %30 devamsızlık yapabilirsin.")
                }
                LimitMode.COUNT -> {
                    NumberField(
                        if (polls > 1) "Kaç yoklamaya gelmeme hakkın var?" else "Kaç derse gelmeme hakkın var?",
                        countText, Modifier.fillMaxWidth(),
                    ) { countText = it }
                    Hint("Hoca \"en fazla 3 kez gelmeyebilirsiniz\" dediyse 3 yaz.")
                }
            }

            HorizontalDivider()
            SectionTitle("Her derste kaç yoklama alınıyor?")
            ChoiceRow((1..MAX_POLLS).map { it to "$it" }, polls) { polls = it }
            courseSlots.forEach { s ->
                val times = pollTimes(s.start, s.end, polls).joinToString(", ") { it.format(timeFmt) }
                Hint("${dayLongName(s.day)} ${timeRange(s.start, s.end)} → $times" +
                    (if (polls > 1) " saatlerinde sorulur" else " saatinde sorulur"))
            }
            if (polls > 1) {
                Hint("Her yoklama ayrı sorulur ve ayrı sayılır: 1. soru dersin başlamasından 1 saat sonra, son soru " +
                    "ders bitiminde gelir. Bir derse hiç girmezsen $polls devamsızlık yazılır.")
                if (polls > cfg0.polls) {
                    SwitchRow(
                        "Geçmiş derslerdeki yeni yoklamaları doldur",
                        fillNew,
                        "Geçmiş haftalarda 1. yoklamaya ne cevap verdiysen yeni yoklamalara da o yazılır",
                    ) { fillNew = it }
                }
            }

            HorizontalDivider()
            preview?.let { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Bu ayarlarla", fontWeight = FontWeight.Bold)
                        Text("Toplam:\u00A0${p.total}\u00A0${p.unit}  •  Devamsızlık\u00A0hakkı:\u00A0${p.allowed}")
                        Text("Şu anki devamsızlık:\u00A0${p.absent}  •  Kalan\u00A0hak:\u00A0${p.remaining}")
                    }
                }
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Dersi programdan kaldır", color = Red)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addingSlot) {
        SlotDialog("Ders saati ekle", SlotDraft(code = code), showName = false, showCode = false,
            onDismiss = { addingSlot = false }) { d ->
            Repo.addSlot(ctx, Repo.newSlot(code, d.day, d.start, d.end, d.place))
            Scheduler.reschedule(ctx, resetCursor = true)
            addingSlot = false
        }
    }
    editingSlot?.let { s ->
        SlotDialog(
            "Ders saatini düzenle",
            SlotDraft(s.code, "", s.day, s.start, s.end, s.place),
            showName = false,
            showCode = false,
            onDismiss = { editingSlot = null },
            onDelete = {
                Repo.removeSlot(ctx, s.id)
                Scheduler.reschedule(ctx, resetCursor = true)
                editingSlot = null
            },
        ) { d ->
            Repo.updateSlot(ctx, s, s.copy(day = d.day, start = d.start, end = d.end, place = d.place))
            Scheduler.reschedule(ctx, resetCursor = true)
            editingSlot = null
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Ders programdan kaldırılsın mı?",
            text = "${snap.title(code)} dersinin bütün saatleri programdan silinir ve artık soru gelmez.",
            confirm = "Kaldır",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            Repo.deleteCourse(ctx, code)
            Scheduler.reschedule(ctx, resetCursor = true)
            nav.tab(Screen.Settings)
        }
    }
}
