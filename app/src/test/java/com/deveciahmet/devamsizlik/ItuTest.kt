package com.deveciahmet.devamsizlik

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** İTÜ açılan dersler sayfasının çözümlenmesi (gerçek sayfadan alınmış satırlarla). */
class ItuTest {
    private fun t(s: String) = LocalTime.parse(s)
    private val mak = ItuParser.sections(fixture("itu/MAK.html"))

    @Test
    fun decodesHtmlEntities() {
        assertEquals("Isı Geçişi", ItuParser.decode("Is&#x131; Ge&#xE7;i&#x15F;i"))
        assertEquals("Öğretim & Yöntem", ItuParser.decode("&#xD6;&#x11F;retim &amp; Y&#246;ntem"))
        assertEquals("&bilinmeyen;", ItuParser.decode("&bilinmeyen;"))
    }

    @Test
    fun parsesNamesAndMultiMeetingSections() {
        val heat = mak.single { it.code == "MAK 303" }
        assertEquals("14882", heat.crn)
        assertEquals("Isı Geçişi", heat.name)
        assertEquals(
            listOf(
                ItuMeeting(1, t("15:30"), t("17:29"), "MKB A363"),
                ItuMeeting(4, t("08:30"), t("10:29"), "MKB A363"),
            ),
            heat.meetings,
        )
        assertEquals("Akışkanlar Mekaniği II", mak.single { it.code == "MAK 335" }.name)
        assertEquals("MAK 304E", mak.single { it.crn == "14883" }.code)
        // İki şubesi var, saatler aynı, derslik ve hoca farklı
        val vib = mak.filter { it.code == "MAK 309" }
        assertEquals(2, vib.size)
        assertEquals(setOf("MKB A501", "MKB A363"), vib.map { it.meetings.single().place }.toSet())
    }

    @Test
    fun sectionsWithoutTimesHaveNoMeetings() {
        val project = mak.first { it.code == "MAK 4901" }
        assertTrue(project.meetings.isEmpty())
    }

    @Test
    fun roomWithoutNumberKeepsBuilding() {
        val tennis = ItuParser.sections(fixture("itu/BEB.html"))
        assertEquals(6, tennis.size)
        assertEquals("KORT", tennis.first { it.crn == "10071" }.meetings.single().place)
        assertEquals(ItuMeeting(5, t("10:30"), t("12:29"), "KORT"), tennis.first { it.crn == "10071" }.meetings.single())
    }

    @Test
    fun parsesBranchList() {
        val ids = ItuParser.branches(fixture("itu/branches_LS.json"))
        assertEquals(12, ids["MAK"])
        assertEquals(50, ids["ITB"])
        assertEquals(200, ids["BEB"])
    }

    @Test
    fun emptyPageGivesNoSections() {
        assertTrue(ItuParser.sections("<div>Ders bulunamadı</div>").isEmpty())
    }

    @Test
    fun sectionsSurviveCacheRoundTrip() {
        val back = ItuParser.sectionsFromJson(JSONArray(ItuParser.sectionsToJson(mak).toString()))
        assertEquals(mak, back)
    }

    @Test
    fun placeIsKnownOnlyWhenAllSectionsAgree() {
        assertEquals("MKB A501", placeFor(mak.filter { it.code == "MAK 335" }, 1, t("10:30")))
        assertNull(placeFor(mak.filter { it.code == "MAK 309" }, 2, t("10:30")))
        assertNull(placeFor(mak.filter { it.code == "MAK 335" }, 2, t("10:30")))
    }

    @Test
    fun parseRange() {
        assertEquals(t("08:30") to t("10:29"), ItuParser.parseRange("08:30/10:29"))
        assertNull(ItuParser.parseRange("-/-"))
        assertNull(ItuParser.parseRange(null))
    }
}
