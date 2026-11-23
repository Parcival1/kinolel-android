package org.jellyfin.mobile.kinolel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * KinoLel (Lel 23.11.2026): Benachrichtigung vor der Löschung eines Wunsches – Datum und Frist wie Mail und Fenster
 * (CT 103 /dokumentation/kinolel-player/AUFTRAG-ANDROID-LOESCHHINWEIS.md), Liste aus `kinolel-bald`.
 */
class LoeschHinweisTest : FunSpec({
    test("Datum deutsch und ungarisch") {
        LoeschHinweis.datum("2026-12-01", hu = false) shouldBe "01.12.2026"
        LoeschHinweis.datum("2026-12-01", hu = true) shouldBe "2026. december 1."
        LoeschHinweis.datum("2027-01-15", hu = true) shouldBe "2027. január 15."
    }

    test("Frist in Wochen oder Tagen, ungarisch mit Artikel") {
        LoeschHinweis.frist(35, hu = false) shouldBe "5 Wochen"
        LoeschHinweis.frist(14, hu = false) shouldBe "2 Wochen"
        LoeschHinweis.frist(10, hu = false) shouldBe "10 Tagen"
        LoeschHinweis.frist(35, hu = true) shouldBe "az 5 hetes"
        LoeschHinweis.frist(14, hu = true) shouldBe "a 2 hetes"
        LoeschHinweis.frist(10, hu = true) shouldBe "a 10 napos"
        LoeschHinweis.frist(7, hu = true) shouldBe "az 1 hetes"
        LoeschHinweis.frist(51, hu = true) shouldBe "az 51 napos"
    }

    test("Liste aus kinolel-bald lesen") {
        val liste = LoeschHinweis.lesen(
            """[{"id":"3E78A2D9-0000-0000-0000-00000000ABCD","titel":"How I Met Your Mother (2005)","tag":"2026-12-01","hu":false,"tage":35},""" +
                """{"id":"0519d8f6060b5ed860806e10cb33fbc2","titel":"A Bosszúállók","tag":"2026-11-30","hu":true},""" +
                """{"id":"kaputt","titel":"x","tag":"2026-12-01"},{"titel":"ohne id","tag":"2026-12-01"},7]""",
        )
        liste shouldBe listOf(
            LoeschHinweis.Eintrag("3e78a2d900000000000000000000abcd", "How I Met Your Mother (2005)", "2026-12-01", false, 35),
            LoeschHinweis.Eintrag("0519d8f6060b5ed860806e10cb33fbc2", "A Bosszúállók", "2026-11-30", true, 14),
        )
        liste.first().schluessel shouldBe "3e78a2d900000000000000000000abcd:2026-12-01"
        LoeschHinweis.lesen("[]") shouldBe emptyList()
        LoeschHinweis.lesen(null) shouldBe emptyList()
        LoeschHinweis.lesen("kein JSON") shouldBe emptyList()
    }
})
