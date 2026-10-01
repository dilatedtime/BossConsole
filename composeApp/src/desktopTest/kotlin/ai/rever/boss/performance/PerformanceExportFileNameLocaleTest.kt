package ai.rever.boss.performance

import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [exportFileName] locale independence (#1068).
 *
 * Verifies that performance export file names remain stable, chronological, and purely ASCII
 * regardless of the host JVM default locale (such as Thai-Buddhist eras or Arabic-Indic numerals).
 */
class PerformanceExportFileNameLocaleTest {
    @Test
    fun `export file name uses ASCII digits and ISO chronology across diverse locales`() {
        val originalLocale = Locale.getDefault()
        val originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)

        // Fixed date: 2025-09-19 13:53:20 UTC
        val fixedDate = Date(1758290000000L)
        val expectedRegex = Regex("""^performance-export-\d{8}-\d{6}\.json$""")

        val testLocales =
            listOf(
                Locale.US,
                Locale.ROOT,
                Locale("th", "TH", "TH"),
                Locale("ar", "EG"),
                Locale("fa", "IR"),
                Locale.JAPAN,
            )

        try {
            for (locale in testLocales) {
                Locale.setDefault(locale)
                Locale.setDefault(Locale.Category.FORMAT, locale)

                val fileName = exportFileName(fixedDate)

                assertTrue(
                    expectedRegex.matches(fileName),
                    "file name '$fileName' under locale '$locale' must match ASCII yyyyMMdd-HHmmss pattern",
                )
                assertTrue(
                    fileName.all { it.code in 32..126 },
                    "file name '$fileName' under locale '$locale' must contain only ASCII characters",
                )
            }
        } finally {
            Locale.setDefault(originalLocale)
            Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        }
    }

    @Test
    fun `exportFileName timestamp overload matches Date overload`() {
        val fixedMillis = 1758290000000L
        val fromDate = exportFileName(Date(fixedMillis))
        val fromMillis = exportFileName(fixedMillis)

        assertEquals(fromDate, fromMillis)
    }
}
