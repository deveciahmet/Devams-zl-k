package com.deveciahmet.devamsizlik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/** Kayıtlar: tatiller, sınavlar, yeni dönem (arşiv), yedekleme ve İTÜ'den bilgi doldurma. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataTest {
    @get:Rule
    val clock = FixedClockRule("2026-11-04T12:00:00")

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val oct29 = LocalDate.of(2026, 10, 29)

    @Test
    fun holiday_marksEveryClassOfTheDay_andCanBeRemoved() {
        Repo.setStatus(ctx, "mak303-per|2026-10-29", Status.ATTENDED)
        Repo.setStatus(ctx, "mak335-pzt|2026-10-26", Status.ABSENT)
        Repo.addHolidays(ctx, listOf(oct29))

        val snap = Repo.snapshot(ctx)
        val thursday = snap.polls.filter { it.session.date == oct29 }
        assertEquals(listOf("MAK 303", "MAK 305", "MAK 317", "MAK 304E"), thursday.map { it.code })
        assertTrue(thursday.all { snap.status(it.key) == Status.CANCELLED })
        assertNull("o günün eski cevabı silinir", Repo.status(ctx, "mak303-per|2026-10-29"))
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak335-pzt|2026-10-26"))
        assertEquals(12, snap.stat("MAK 317")!!.total)

        Repo.removeHolidays(ctx, listOf(oct29))
        val after = Repo.snapshot(ctx)
        assertTrue(after.polls.filter { it.session.date == oct29 }.all { after.status(it.key) == null })
    }

    @Test
    fun exams_saveEditRemove() {
        val e = Exam(Repo.newExamId(), "MAK 335", "Vize", LocalDate.of(2026, 11, 17), LocalTime.of(13, 30), "MKB A501")
        Repo.saveExam(ctx, e)
        Repo.saveExam(ctx, Exam(Repo.newExamId(), "", "İngilizce yeterlik", LocalDate.of(2026, 11, 10)))
        assertEquals(2, Repo.exams(ctx).size)
        Repo.saveExam(ctx, e.copy(title = "Final"))
        assertEquals("Final", Repo.exams(ctx).single { it.id == e.id }.title)
        val snap = Repo.snapshot(ctx)
        assertEquals(listOf("İngilizce yeterlik", "MAK 335 · Final"), snap.upcomingExams.map { snap.examTitle(it) })
        Repo.removeExam(ctx, e.id)
        assertEquals(1, Repo.exams(ctx).size)
    }

    @Test
    fun newTerm_archivesEverything_startsClean_andCanBeUndone() {
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        Repo.saveCourse(ctx, "MAK 335", "MAK 335", CourseConfig(name = "Akışkanlar Mekaniği II"))
        Repo.saveExam(ctx, Exam("e1", "MAK 335", "Vize", LocalDate.of(2026, 11, 17)))
        Repo.addHolidays(ctx, listOf(oct29))
        Repo.setRequiredPercent(ctx, 80)
        Repo.setNotifySettings(ctx, NotifySettings(reminderMinutes = 15))

        val id = Archive.startNewTerm(ctx, "2026-2027 Güz Dönemi", LocalDate.of(2027, 2, 17))

        val fresh = Repo.snapshot(ctx)
        assertTrue(fresh.slots.isEmpty())
        assertTrue(fresh.exams.isEmpty())
        assertTrue(fresh.holidays.isEmpty())
        assertTrue(Repo.statusMap(ctx).isEmpty())
        assertTrue(Repo.courseConfigs(ctx).isEmpty())
        assertEquals(LocalDate.of(2027, 2, 15), Repo.semesterStart(ctx))
        assertEquals("2026-2027 Bahar Dönemi", Repo.termName(ctx))
        // Genel ayarlar korunur
        assertEquals(80, Repo.requiredPercent(ctx))
        assertEquals(15, Repo.notifySettings(ctx).reminderMinutes)

        val info = Archive.list(ctx).single()
        assertEquals("2026-2027 Güz Dönemi", info.name)
        assertEquals(9, info.courseCount)
        val old = Archive.snapshot(ctx, id)!!
        assertEquals("Akışkanlar Mekaniği II", old.title("MAK 335"))
        assertEquals(1, old.stat("MAK 335")!!.absent)
        assertEquals(1, old.exams.size)
        assertEquals(setOf(oct29), old.holidays)
        assertEquals(1, termTotals(old).absent)

        Archive.restore(ctx, id)
        assertEquals(11, Repo.slots(ctx).size)
        assertEquals(Status.ABSENT, Repo.status(ctx, "mak335-pzt|2026-09-28"))
        assertEquals(LocalDate.of(2026, 9, 28), Repo.semesterStart(ctx))
        assertEquals("2026-2027 Güz Dönemi", Repo.termName(ctx))
        assertTrue("boş yeni dönem tekrar arşivlenmez", Archive.list(ctx).isEmpty())
    }

    @Test
    fun restoringOldTerm_archivesTheCurrentOneInstead() {
        val id = Archive.startNewTerm(ctx, "Güz", LocalDate.of(2027, 2, 15))
        Repo.addSlot(ctx, Repo.newSlot("FIZ 102", 1, LocalTime.of(8, 30), LocalTime.of(10, 29)))
        Archive.restore(ctx, id)
        val list = Archive.list(ctx)
        assertEquals(1, list.size)
        assertEquals(listOf("FIZ 102"), Archive.snapshot(ctx, list.single().id)!!.courseCodes)
    }

    @Test
    fun backup_roundTrip() {
        // Geçmiş bir dönem + yeni dönemde program, yoklama, sınav, tatil ve ayarlar
        Repo.setStatus(ctx, "mak335-pzt|2026-09-28", Status.ABSENT)
        Archive.startNewTerm(ctx, "2026-2027 Güz Dönemi", LocalDate.of(2027, 2, 15))
        Repo.importSlots(
            ctx,
            listOf(
                ImportedSlot("MAK 303", "Isı Geçişi", 1, LocalTime.of(15, 30), LocalTime.of(17, 29), "MKB A363"),
                ImportedSlot("MAK 303", "Isı Geçişi", 4, LocalTime.of(8, 30), LocalTime.of(10, 29), "MKB A363"),
            ),
            replace = true,
        )
        val monday = Repo.slots(ctx).first { it.day == 1 }
        Repo.setStatus(ctx, "${monday.id}|2027-02-15", Status.ATTENDED)
        Repo.saveExam(ctx, Exam("e1", "MAK 303", "Vize", LocalDate.of(2027, 4, 6), LocalTime.of(13, 30)))
        Repo.addHolidays(ctx, listOf(LocalDate.of(2027, 4, 23)))
        Repo.setNotifySettings(ctx, NotifySettings(warnThreshold = 2, examReminder = false))
        Repo.setTermName(ctx, "2026-2027 Bahar Dönemi")
        val json = Backup.export(ctx)
        val before = Repo.snapshot(ctx)

        // Yeni telefon: hiçbir kayıt yok
        listOf(Repo.SETTINGS, Repo.ATTENDANCE, Archive.PREFS).forEach {
            ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val info = Backup.inspect(json)
        assertEquals("2026-2027 Bahar Dönemi", info.termName)
        assertEquals(1, info.courses)
        assertEquals(1, info.records)
        assertEquals(1, info.exams)
        assertEquals(1, info.archives)

        Backup.restore(ctx, json)
        val after = Repo.snapshot(ctx)
        assertEquals(before.slots, after.slots)
        assertEquals(before.configs, after.configs)
        assertEquals(before.statuses, after.statuses)
        assertEquals(before.exams, after.exams)
        assertEquals(before.holidays, after.holidays)
        assertEquals(before.notify, after.notify)
        assertEquals(before.semesterStart, after.semesterStart)
        assertEquals("2026-2027 Bahar Dönemi", after.termName)
        assertEquals("2026-2027 Güz Dönemi", Archive.list(ctx).single().name)
        assertEquals(1, Archive.snapshot(ctx, Archive.list(ctx).single().id)!!.stat("MAK 335")!!.absent)
        assertTrue(Repo.cursor(ctx) != null)
    }

    @Test
    fun backup_rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.inspect("bu bir yedek değil") }
        assertThrows(IllegalArgumentException::class.java) { Backup.inspect("{\"app\":\"baska\",\"data\":{}}") }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.inspect("{\"app\":\"devamsizlik\",\"format\":99,\"data\":{}}")
        }
    }

    @Test
    fun itu_fillsBlankNamesAndKnownRooms() {
        Repo.saveCourse(ctx, "MAK 309", "MAK 309", CourseConfig(name = "Titreşim"))
        // 9 dersten 8'i güncellenir: MAK 309'un adı zaten var, iki şubesi aynı saatte olduğu için dersliği de belli değil
        assertEquals(8, Itu.fillCourseInfo(ctx))
        val snap = Repo.snapshot(ctx)
        assertEquals("Akışkanlar Mekaniği II", snap.title("MAK 335"))
        assertEquals("Isı Geçişi", snap.title("MAK 303"))
        assertEquals("Psikoloji", snap.title("ITB 220"))
        assertEquals("Titreşim", snap.title("MAK 309")) // elle yazılan ad korunur
        val place = Repo.slots(ctx).associate { it.id to it.place }
        assertEquals("MKB A501", place["mak335-pzt"])
        assertEquals("MED B34", place["itb220-sal"]) // Salı 14:30 şubesi tek
        assertEquals("KORT", place["beb104-cum"])
        assertEquals("", place["mak309-sal"]) // aynı saatte iki şube, derslik belli değil
    }

    @Test
    fun itu_cachesAndWorksOfflineFromCache() {
        Itu.lookup(ctx, listOf("MAK 335"))
        assertEquals(2, clock.itu.requests) // branş listesi + MAK dersleri
        Itu.lookup(ctx, listOf("MAK 303"))
        assertEquals(2, clock.itu.requests)
        clock.itu.offline = true
        assertEquals("Isı Geçişi", Itu.lookup(ctx, listOf("MAK 303")).catalog["MAK 303"]!!.first().name)
        Itu.clearCache(ctx)
        assertThrows(IOException::class.java) { Itu.lookup(ctx, listOf("MAK 303")) }
    }

    @Test
    fun itu_autoFillRunsAtMostOncePerDay() {
        val now = System.currentTimeMillis()
        Itu.autoFillIfNeeded(ctx, now)
        assertEquals("Akışkanlar Mekaniği II", Repo.snapshot(ctx).title("MAK 335"))
        Repo.saveCourse(ctx, "MAK 335", "MAK 335", CourseConfig())
        val n = clock.itu.requests
        Itu.autoFillIfNeeded(ctx, now + 3600_000)
        assertEquals(n, clock.itu.requests)
        Itu.autoFillIfNeeded(ctx, now + 25 * 3600_000L)
        assertEquals("Akışkanlar Mekaniği II", Repo.snapshot(ctx).title("MAK 335"))
    }
}
