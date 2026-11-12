@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.deveciahmet.devamsizlik

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fotoğraftan/metinden bulunanların İTÜ listesiyle eşleştirilmiş hali. */
class KeplerResult(val match: MatchResult, val words: Set<String>, val term: String?)

/** Bulunan kodları İTÜ'nün açılan dersler listesinde arar (ağ; ana iş parçacığında çağırma). */
fun resolveKepler(ctx: Context, f: Findings): KeplerResult {
    val lookup = Itu.lookup(ctx, f.codes.keys)
    return KeplerResult(ScheduleMatcher.match(f, lookup), f.words, Itu.activeTerm())
}

/**
 * Programı Kepler'den ekleme: Kepler'deki ders programının ekran görüntüsü telefonda okunur (OCR); bulunan ders
 * kodlarının adı, şubesi, günü, saati ve dersliği İTÜ'nün açılan dersler sayfasından alınır.
 */
@Composable
fun KeplerImportScreen(snap: Snapshot, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<KeplerResult?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var replace by rememberSaveable { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateMapOf<String, Int?>() }
    val included = remember { mutableStateMapOf<String, Boolean>() }
    val names = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    fun start(reading: String, read: suspend () -> Findings) {
        error = null
        result = null
        scope.launch {
            try {
                busy = reading
                val f = try {
                    read()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Fotoğraf okunamadı. Başka bir ekran görüntüsü dene. (${e.message ?: e.javaClass.simpleName})"
                    return@launch
                }
                if (f.codes.isEmpty()) {
                    error = "Ders kodu bulunamadı. Kepler → Ders Programı ekranının net bir ekran görüntüsünü seç " +
                        "ya da kodları elle yaz (ör. MAK 335)."
                    return@launch
                }
                busy = "İTÜ'nün açılan dersler listesinde aranıyor…"
                val r = try {
                    withContext(Dispatchers.IO) { resolveKepler(ctx, f) }
                } catch (e: IOException) {
                    error = "İTÜ'nün ders listesine ulaşılamadı. İnternet bağlantını kontrol edip tekrar dene."
                    return@launch
                }
                if (r.match.courses.isEmpty()) {
                    error = "Bulunan kodlar bu dönem açılan derslerde yok: " +
                        (r.match.notFound + r.match.noSchedule).ifEmpty { f.codes.keys.toList() }.joinToString(", ")
                    return@launch
                }
                chosen.clear(); included.clear(); names.clear(); expanded.clear()
                r.match.courses.forEach {
                    chosen[it.code] = it.chosen
                    included[it.code] = true
                    names[it.code] = it.name
                }
                result = r
            } finally {
                busy = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            start("Fotoğraf okunuyor…") {
                uris.map { KeplerReader.fromLines(Ocr.read(ctx, it)) }.fold(Findings.EMPTY) { a, b -> a + b }
            }
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val r = result
    val selected = r?.match?.courses?.filter { included[it.code] == true }.orEmpty()
    val missingChoice = selected.filter { chosen[it.code] == null }

    Scaffold(topBar = { BackBar("Programı Kepler'den ekle", nav::pop) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (r == null) {
                item(key = "intro") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Kepler → Ders Programı ekranının görüntüsünü seç", fontWeight = FontWeight.Bold)
                            Hint("Ders kodları ve CRN'ler fotoğraftan telefonda okunur; ders adları, günler, saatler ve " +
                                "derslikler İTÜ'nün açılan dersler listesinden bulunur. Program birkaç ekrana sığıyorsa " +
                                "hepsini birden seçebilirsin.")
                            Button(onClick = pick, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                                Text("Ekran görüntüsü seç")
                            }
                            TextButton(onClick = { typing = !typing }) { Text("Fotoğraf yerine ders kodlarını yaz") }
                            if (typing) {
                                OutlinedTextField(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3,
                                    label = { Text("Ders kodları ya da CRN'ler") },
                                    placeholder = { Text("MAK 335, MAK 303, ITB 220 …") },
                                )
                                Hint("Kepler'deki program sayfasını kopyalayıp buraya da yapıştırabilirsin.")
                                Button(enabled = typed.isNotBlank() && busy == null, onClick = {
                                    start("Kodlar okunuyor…") { KeplerReader.fromText(typed) }
                                }) { Text("Bul") }
                            }
                        }
                    }
                }
            }
            busy?.let { msg ->
                item(key = "busy") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(msg)
                    }
                }
            }
            error?.let { msg ->
                item(key = "error") {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) { Text(msg, Modifier.padding(16.dp)) }
                }
            }
            if (r != null) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Bulunan dersler (${r.match.courses.size})")
                        Hint((r.term?.let { "İTÜ $it açılan derslerine göre. " } ?: "") +
                            "Kontrol et: yanlış bulunan dersin işaretini kaldır, adına dokunup değiştirebilirsin.")
                    }
                }
                items(r.match.courses, key = { "m_${it.code}" }) { m ->
                    MatchCard(
                        m = m,
                        words = r.words,
                        name = names[m.code] ?: m.name,
                        included = included[m.code] == true,
                        chosen = chosen[m.code],
                        expanded = expanded[m.code] == true || chosen[m.code] == null,
                        onInclude = { included[m.code] = it },
                        onChoose = { chosen[m.code] = it; expanded[m.code] = false },
                        onExpand = { expanded[m.code] = true },
                        onRename = { renaming = m.code },
                    )
                }
                if (r.match.notFound.isNotEmpty() || r.match.noSchedule.isNotEmpty()) {
                    item(key = "warn") {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (r.match.notFound.isNotEmpty()) {
                                    Text("Bu dönem açılan derslerde bulunamadı (yanlış okunmuş olabilir): " +
                                        r.match.notFound.joinToString(", "))
                                }
                                if (r.match.noSchedule.isNotEmpty()) {
                                    Text("Haftalık ders saati olmadığı için eklenmedi: " + r.match.noSchedule.joinToString(", "))
                                }
                                Hint("Eksik bir ders varsa sonra Ayarlar → Dersler → Elle ders ekle ile ekleyebilirsin.")
                            }
                        }
                    }
                }
                item(key = "save") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snap.slots.isNotEmpty()) {
                            ChoiceRow(listOf(true to "Mevcut programın yerine koy", false to "Mevcut programa ekle"), replace) {
                                replace = it
                            }
                            Hint(
                                if (replace) "Eski program silinir. Aynı ders, gün ve saatteki derslerin yoklama geçmişi korunur."
                                else "Bu dersler mevcut programın yanına eklenir; aynı olanlar tekrar eklenmez."
                            )
                        }
                        if (missingChoice.isNotEmpty()) {
                            Hint("Kaydetmeden önce şubesini seç: " + missingChoice.joinToString(", ") { it.code }, Orange)
                        }
                        Button(
                            enabled = selected.isNotEmpty() && missingChoice.isEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val matches = selected.map { it.copy(chosen = chosen[it.code], name = names[it.code] ?: it.name) }
                                val entries = ScheduleMatcher.toSlots(matches, r.words)
                                val fresh = replace || snap.slots.isEmpty()
                                val n = Repo.importSlots(ctx, entries, replace = fresh, overwriteNames = true)
                                if (fresh) r.term?.let { Repo.setTermName(ctx, it) }
                                Scheduler.reschedule(ctx, resetCursor = true)
                                Toast.makeText(ctx, "${matches.size} ders ($n ders saati) programa kaydedildi.", Toast.LENGTH_LONG).show()
                                nav.pop()
                            },
                        ) { Text("Programa kaydet (${selected.size} ders)") }
                        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Başka ekran görüntüsü seç") }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    renaming?.let { code ->
        var text by remember(code) { mutableStateOf(names[code].orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(code) },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Ders adı") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    names[code] = text.trim()
                    renaming = null
                }) { Text("Tamam") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun MatchCard(
    m: CourseMatch,
    words: Set<String>,
    name: String,
    included: Boolean,
    chosen: Int?,
    expanded: Boolean,
    onInclude: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
    onExpand: () -> Unit,
    onRename: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = included, onCheckedChange = onInclude)
                Column(Modifier.weight(1f).clickable(onClick = onRename)) {
                    Text(name.ifBlank { m.code }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint(buildString {
                        append(m.code)
                        m.section?.let { append(" • CRN ${it.crn}") }
                        val ins = (m.section?.let { listOf(it) } ?: m.option?.sections.orEmpty()).map { it.instructor }.distinct()
                        if (ins.size == 1 && ins[0].isNotBlank()) append(" • ${ins[0]}")
                    })
                }
            }
            if (!included) return@Card
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val opt = chosen?.let { m.options.getOrNull(it) }
                if (opt != null && !expanded) {
                    val pm = m.copy(chosen = chosen)
                    opt.meetings.forEach { mt ->
                        val place = ScheduleMatcher.placeOf(pm, mt, words)
                        Text("${dayLongName(mt.day)} ${timeRange(mt.start, mt.end)}" + if (place.isNotBlank()) " • $place" else "")
                    }
                    when (m.via) {
                        MatchVia.CRN -> Hint("Fotoğraftaki CRN ile bulundu")
                        MatchVia.HINTS -> Hint("Fotoğraftaki gün ve saatten bulundu")
                        else -> {}
                    }
                    if (m.options.size > 1) {
                        TextButton(onClick = onExpand, contentPadding = PaddingValues(0.dp)) { Text("Başka şube seç") }
                    }
                } else {
                    Text(
                        if (chosen == null) "Hangi şubedesin? Fotoğraftan anlaşılamadı:" else "Şubeni seç:",
                        color = Orange, style = MaterialTheme.typography.bodyMedium,
                    )
                    m.options.forEachIndexed { i, o ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = chosen == i, onClick = { onChoose(i) }, role = Role.RadioButton)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == i, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(meetingsText(o.meetings))
                                Hint(o.sections.joinToString(" • ") { s -> "CRN ${s.crn}" + if (s.instructor.isNotBlank()) " ${s.instructor}" else "" })
                            }
                        }
                    }
                }
            }
        }
    }
}
