package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogListener
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalPluginRepositoryDecodePrivacyTest {
    @TempDir
    lateinit var temporary: File

    private fun pluginDirectory(): File = File(temporary, "plugins").apply { mkdirs() }

    private fun writeCorruptedJar(
        directory: File,
        jarName: String,
        manifestText: String,
    ): File {
        val jar = File(directory, jarName)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(PluginManifestConstants.MANIFEST_PATH))
            out.write(manifestText.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar
    }

    @Test
    fun `scanning corrupted plugin JAR redacts decode failure and omits secrets from log`() =
        runTest {
            val dir = pluginDirectory()
            val secretCanary = "secret-jar-embedded-token-776655"
            val corruptManifest = """{"pluginId":"com.example.leaked","secret":"$secretCanary","displayName":"""
            val jar = writeCorruptedJar(dir, "corrupt.jar", corruptManifest)

            val captured = mutableListOf<LogEntry>()
            val listener = LogListener { captured += it }
            BossLogger.addListener(listener)

            try {
                val repo = LocalPluginRepository(dir)
                val pluginsResult = repo.listPlugins()
                assertTrue(pluginsResult.isSuccess, "listPlugins must succeed even with corrupted JARs")
                val plugins = pluginsResult.getOrThrow()
                assertEquals(0, plugins.size, "Corrupted plugin JAR must be safely skipped")

                val jarPath = repo.getJarPath("com.example.leaked")
                assertNull(jarPath, "Unreadable JAR ID must not match")
            } finally {
                BossLogger.removeListener(listener)
            }

            val repoLogs = captured.filter { it.component == "LocalPluginRepository" }
            assertTrue(repoLogs.isNotEmpty(), "Repository should log failure when encountering corrupted JAR")

            for (log in repoLogs) {
                assertFalse(
                    log.message.contains(secretCanary),
                    "Log message must not contain secret canary: ${log.message}",
                )
                val dataStr = log.data.toString()
                assertFalse(
                    dataStr.contains(secretCanary),
                    "Log data must not contain secret canary: $dataStr",
                )
                log.error?.let { err ->
                    assertFalse(
                        err.message.orEmpty().contains(secretCanary),
                        "Log error must not leak secret canary: ${err.message}",
                    )
                }
            }
        }
}
