package com.deveciahmet.devamsizlik

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

fun fixture(name: String): String =
    requireNotNull(FakeItuSource::class.java.classLoader!!.getResource(name)) { "fikstür yok: $name" }.readText()

/**
 * İTÜ'nün açılan dersler sayfası yerine test fikstürleri (gerçek sayfadan alınmış MAK, ITB, BEB, FIZ satırları;
 * öğretim üyesi adları değiştirilmiş). Ağa çıkmaz.
 */
class FakeItuSource(var term: String? = "2026-2027 Güz Dönemi") : ItuSource {
    var offline = false
    var requests = 0
    private val ids = ItuParser.branches(fixture("itu/branches_LS.json"))
    private val files = setOf("MAK", "ITB", "BEB", "FIZ")

    private fun check() {
        requests++
        if (offline) throw IOException("çevrimdışı")
    }

    override fun branches(level: String): Map<String, Int> {
        check()
        return if (level == "LS") ids else emptyMap()
    }

    override fun sections(level: String, branchId: Int): List<ItuSection> {
        check()
        val branch = ids.entries.firstOrNull { it.value == branchId }?.key ?: return emptyList()
        return if (branch in files) ItuParser.sections(fixture("itu/$branch.html")) else emptyList()
    }

    override fun activeTerm(level: String): String? {
        check()
        return term
    }
}

/**
 * Saati, uygulama açılmadan önce sabitler (varsayılan: 4 Kasım 2026 12:00, 6. hafta Çarşamba) ve İTÜ'ye çıkılmasını
 * engeller (sahte kaynak, açılışta otomatik doldurma kapalı).
 */
class FixedClockRule(private val time: String) : TestRule {
    val itu = FakeItuSource()

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val zone = ZoneId.systemDefault()
            AppClock.clock = Clock.fixed(LocalDateTime.parse(time).atZone(zone).toInstant(), zone)
            val oldSource = Itu.source
            Itu.source = itu
            Itu.autoFill = false
            try {
                base.evaluate()
            } finally {
                AppClock.clock = Clock.systemDefaultZone()
                Itu.source = oldSource
                Itu.autoFill = true
            }
        }
    }
}
