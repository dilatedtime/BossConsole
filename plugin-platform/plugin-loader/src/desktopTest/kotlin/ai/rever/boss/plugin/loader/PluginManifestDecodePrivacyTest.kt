package ai.rever.boss.plugin.loader

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginManifestDecodePrivacyTest {
    @Test
    fun `manifest parsing error withholds raw content and secret tokens from exception message`() {
        val secretCanary = "secret-plugin-auth-token-canary-554433"
        val tornJson = """{"pluginId":"com.example.leak","secret":"$secretCanary","displayName":"""

        val failure =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.parseManifest(tornJson, source = "test-plugin.jar")
            }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("decode failure"), "Exception message must report decode failure: $message")
        assertTrue(message.contains("test-plugin.jar"), "Exception message must identify the source: $message")
        assertFalse(message.contains(secretCanary), "Exception message must never leak secret token: $message")
    }
}
