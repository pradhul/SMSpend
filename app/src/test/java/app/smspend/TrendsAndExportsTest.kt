package app.smspend

import app.smspend.data.GeminiCategorizer
import app.smspend.data.SpendReportRow
import app.smspend.data.TrendsBullet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsAndExportsTest {
    private val gemini = GeminiCategorizer()

    @Test
    fun parseTrendsBullets_mapsTextAndTones() {
        val raw = """
            [
              {"text":"You have spent ₹2,500 on Alcohol this month so far — ₹1,500 more than all of last month (₹1,000).","tone":"more"},
              {"text":"Your electricity bill was ₹100 less than last month.","tone":"less"},
              {"text":"Electricity bill (~₹1,200) expected by Mon, Aug 24 hasn't been paid yet.","tone":"warning"},
              {"text":"Amazon is most of your Shopping this month.","tone":"info"}
            ]
        """.trimIndent()
        val bullets = gemini.parseTrendsBullets(raw)
        assertEquals(4, bullets.size)
        assertEquals(TrendsBullet.TONE_MORE, bullets[0].tone)
        assertEquals(TrendsBullet.TONE_LESS, bullets[1].tone)
        assertEquals(TrendsBullet.TONE_WARNING, bullets[2].tone)
        assertEquals(TrendsBullet.TONE_INFO, bullets[3].tone)
        assertTrue(bullets[0].text.startsWith("You have spent"))
    }

    @Test
    fun parseTrendsBullets_normalizesUnknownTonesAndSkipsEmpty() {
        val raw = """
            [
              {"text":"Grocery is up.","tone":"UP"},
              {"text":"Fuel is down.","tone":"decrease"},
              {"text":"","tone":"more"},
              {"text":"Something odd.","tone":"celebration"}
            ]
        """.trimIndent()
        val bullets = gemini.parseTrendsBullets(raw)
        assertEquals(3, bullets.size)
        assertEquals(TrendsBullet.TONE_MORE, bullets[0].tone)
        assertEquals(TrendsBullet.TONE_LESS, bullets[1].tone)
        assertEquals(TrendsBullet.TONE_INFO, bullets[2].tone)
    }

    @Test
    fun parseTrendsBullets_acceptsFencedJsonAndStripsBulletMarkers() {
        val raw = """
            ```json
            [{"text":"- Food is up ₹500 vs last month.","tone":"more"}]
            ```
        """.trimIndent()
        val bullets = gemini.parseTrendsBullets(raw)
        assertEquals(1, bullets.size)
        assertEquals("Food is up ₹500 vs last month.", bullets[0].text)
    }

    @Test
    fun parseTrendsBullets_unparseableReturnsEmpty() {
        assertTrue(gemini.parseTrendsBullets("not json at all").isEmpty())
    }

    @Test
    fun parseDateLabels_keepsOnlyExpectedDatesWithLabels() {
        val raw = """
            [
              {"date":"2026-08-15","label":"Independence Day"},
              {"date":"2026-08-28","label":"Onam"},
              {"date":"2026-09-01","label":"Salary day"},
              {"date":"2026-08-03","label":""}
            ]
        """.trimIndent()
        val labels = gemini.parseDateLabels(
            raw,
            expectedDates = setOf("2026-08-03", "2026-08-15", "2026-08-28"),
        )
        assertEquals(2, labels.size)
        assertEquals("Independence Day", labels["2026-08-15"])
        assertEquals("Onam", labels["2026-08-28"])
        assertFalse(labels.containsKey("2026-09-01")) // not in the batch
        assertFalse(labels.containsKey("2026-08-03")) // blank label dropped
    }

    @Test
    fun spendReportRow_aboutTextCombinesCategoryAndTags() {
        val tagged = SpendReportRow(
            txId = 1,
            occurredAt = 0L,
            merchant = "BESCOM",
            categoryLabel = "Bills & utilities",
            tags = listOf("electricity", "home"),
            amountPaise = 120_000,
        )
        assertEquals("Bills & utilities · electricity, home", tagged.aboutText)

        val untagged = tagged.copy(tags = emptyList())
        assertEquals("Bills & utilities", untagged.aboutText)
    }

    @Test
    fun trendsBullet_normalizeToneVariants() {
        assertEquals(TrendsBullet.TONE_WARNING, TrendsBullet.normalizeTone(" Warn "))
        assertEquals(TrendsBullet.TONE_MORE, TrendsBullet.normalizeTone("increase"))
        assertEquals(TrendsBullet.TONE_LESS, TrendsBullet.normalizeTone("down"))
        assertEquals(TrendsBullet.TONE_INFO, TrendsBullet.normalizeTone("whatever"))
    }
}
