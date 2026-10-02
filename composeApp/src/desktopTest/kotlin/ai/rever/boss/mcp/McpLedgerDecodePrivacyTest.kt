package ai.rever.boss.mcp

import ai.rever.boss.cli.McpLedgerCli
import ai.rever.boss.cli.McpLedgerOutcome
import ai.rever.boss.cli.McpLedgerQuery
import ai.rever.boss.cli.McpLedgerSecrets
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpLedgerDecodePrivacyTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-ledger-decode-privacy")
                .toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `reading ledger with torn record withholds secret arguments from exception message`() {
        val file = createTempLedgerFile()
        val secretToken = "sk-ant-canary-vault-secret-998877"
        val tornLine = """{"id":"call-1","timestamp":1000,"sanitizedArgs":{"apiKey":"$secretToken"},"badJson": """
        file.writeText(tornLine + "\n")

        val ledger = McpOperationLedger(ledgerFile = file)
        val failure = assertFailsWith<McpLedgerReadException> {
            ledger.readEntries()
        }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("line 1"), "Exception message must report the line number: $message")
        assertTrue(message.contains(file.absolutePath), "Exception message must name the file: $message")
        assertTrue(message.contains("decodeFailure="), "Exception message must include structured decodeFailure: $message")
        assertFalse(message.contains(secretToken), "Exception message must never leak secret token: $message")
    }

    @Test
    fun `verify command reports failure without leaking secret from malformed record`() {
        val file = createTempLedgerFile()
        val secretToken = "ghp_super_secret_pat_112233"
        val tornLine = """{"id":"call-2","timestamp":2000,"sanitizedArgs":{"pat":"$secretToken"},"broken":"""
        file.writeText(tornLine + "\n")

        val outcome = McpLedgerCli.verify(fileOverride = file.absolutePath, json = false)
        val failed = assertIs<McpLedgerOutcome.Failed>(outcome)
        assertFalse(
            failed.message.contains(secretToken),
            "CLI verify output must not leak secret token: ${failed.message}",
        )
        assertTrue(failed.message.contains("line 1"), "CLI verify output should name the failing line")
    }

    @Test
    fun `search command reports failure without leaking secret from malformed record`() {
        val file = createTempLedgerFile()
        val secretToken = "vault-root-token-xyz-445566"
        val tornLine = """{"id":"call-3","sanitizedArgs":{"secret":"$secretToken"},"corrupt":"""
        file.writeText(tornLine + "\n")

        val outcome =
            McpLedgerCli.search(
                fileOverride = file.absolutePath,
                limit = 10,
                query = McpLedgerQuery(),
                json = false,
            )
        val failed = assertIs<McpLedgerOutcome.Failed>(outcome)
        assertFalse(
            failed.message.contains(secretToken),
            "CLI search output must not leak secret token: ${failed.message}",
        )
    }

    @Test
    fun `tail command reports failure without leaking secret from malformed record`() {
        val file = createTempLedgerFile()
        val secretToken = "aws-secret-access-key-canary-7788"
        val tornLine = """{"id":"call-4","sanitizedArgs":{"key":"$secretToken"},"corrupt":"""
        file.writeText(tornLine + "\n")

        val outcome =
            McpLedgerCli.tail(
                fileOverride = file.absolutePath,
                lines = 10,
                query = McpLedgerQuery(),
                json = false,
            )
        val failed = assertIs<McpLedgerOutcome.Failed>(outcome)
        assertFalse(
            failed.message.contains(secretToken),
            "CLI tail output must not leak secret token: ${failed.message}",
        )
    }

    @Test
    fun `secrets command reports failure without leaking secret from malformed record`() {
        val file = createTempLedgerFile()
        val secretToken = "database-password-canary-001122"
        val tornLine = """{"id":"call-5","sanitizedArgs":{"pwd":"$secretToken"},"corrupt":"""
        file.writeText(tornLine + "\n")

        val outcome =
            McpLedgerSecrets.secrets(
                fileOverride = file.absolutePath,
                query = McpLedgerQuery(),
                json = false,
            )
        val failed = assertIs<McpLedgerOutcome.Failed>(outcome)
        assertFalse(
            failed.message.contains(secretToken),
            "CLI secrets output must not leak secret token: ${failed.message}",
        )
    }
}
