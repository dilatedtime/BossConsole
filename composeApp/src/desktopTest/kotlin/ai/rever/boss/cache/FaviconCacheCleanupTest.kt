package ai.rever.boss.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [FaviconCache.cleanupStaleEntries].
 *
 * Verifies age-based cache eviction (#1072) and prevents integer overflow in elapsed cutoff
 * calculations for large retention intervals (#1025).
 */
class FaviconCacheCleanupTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("boss-favicon-cleanup-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `stale files older than cutoff are deleted and fresh files are retained`() {
        val now = System.currentTimeMillis()
        val staleFile =
            File(tempDir, "stale_favicon.png").apply {
                writeText("stale-icon-data")
                setLastModified(now - (35L * 24 * 60 * 60 * 1000))
            }
        val freshFile =
            File(tempDir, "fresh_favicon.png").apply {
                writeText("fresh-icon-data")
                setLastModified(now - (5L * 24 * 60 * 60 * 1000))
            }

        val removed = FaviconCache.cleanupStaleEntries(daysOld = 30, dir = tempDir)

        assertEquals(1, removed, "should report exactly 1 deleted file")
        assertFalse(staleFile.exists(), "stale file older than 30 days must be deleted")
        assertTrue(freshFile.exists(), "fresh file newer than 30 days must be retained")
    }

    @Test
    fun `large daysOld retention value does not overflow integer arithmetic`() {
        val now = System.currentTimeMillis()
        // File created 100 days ago
        val file =
            File(tempDir, "hundred_days_old.png").apply {
                writeText("data")
                setLastModified(now - (100L * 24 * 60 * 60 * 1000))
            }

        // 25,000 days (~68 years) previously overflowed 32-bit Int (25000 * 86400 > Int.MAX_VALUE)
        val removed = FaviconCache.cleanupStaleEntries(daysOld = 25_000, dir = tempDir)

        assertEquals(0, removed, "file younger than 25,000 days must not be deleted")
        assertTrue(file.exists(), "file must survive when retention span exceeds file age without overflow")
    }

    @Test
    fun `cleanup on empty directory returns zero without errors`() {
        val removed = FaviconCache.cleanupStaleEntries(daysOld = 30, dir = tempDir)
        assertEquals(0, removed)
    }
}
