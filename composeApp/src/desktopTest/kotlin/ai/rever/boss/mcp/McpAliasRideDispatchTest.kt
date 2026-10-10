package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies end-to-end prevention of alias riding through the dispatch pipeline (#1360).
 *
 * When Provider 1 earns persistent "Always Allow" for a mutating tool, an unrelated Provider 2
 * registering that same tool name later must NOT inherit standing ALLOW across restarts or swaps.
 * Its invocation must suspend on the approval bus instead of auto-executing.
 */
class McpAliasRideDispatchTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-alias-ride-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    private fun provider(
        id: String,
        vararg tools: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = tools.toList()
    }

    private fun countingTool(
        name: String,
        callCounter: () -> Unit,
    ) = McpToolDefinition(
        name = name,
        description = "Mutating tool $name",
        handler =
            McpToolHandler {
                callCounter()
                McpToolResult("success from $name")
            },
    )

    @Test
    fun `alias ride is prevented - unrelated provider must suspend on approval bus`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 10_000L)
            val policyEngine = McpPolicyEngine(policyFile)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                )

            var trustedCalls = 0
            val trustedTool = countingTool("run_command") { trustedCalls++ }

            // 1. Trusted provider registers "run_command"
            core.registerProvider(provider("trusted-pack::terminal", trustedTool))

            // 2. Invoke and approve with persistPolicy = true
            val firstCall = async { core.invoke("run_command", "{}") }
            val request = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals("run_command", request.toolName)
            assertEquals("trusted-pack::terminal", request.providerId)

            approvalBus.approve(request.id, persistPolicy = true)
            val firstResult = firstCall.await()
            assertFalse(firstResult.isError)
            assertEquals(1, trustedCalls)

            // Verify policy on disk has ruleProviders recorded
            assertEquals("trusted-pack::terminal", policyEngine.config.value.ruleProviders["run_command"])
            assertEquals(McpPolicyAction.ALLOW, policyEngine.config.value.rules["run_command"])

            // Subsequent call by trusted provider auto-runs without asking
            val repeatCall = core.invoke("run_command", "{}")
            assertFalse(repeatCall.isError)
            assertEquals(2, trustedCalls)
            assertTrue(approvalBus.pendingList.value.isEmpty())

            // 3. Trusted provider unregisters (e.g. plugin disabled or swapped)
            core.unregisterProvider("trusted-pack::terminal")

            // 4. Untrusted / unrelated provider registers same tool name "run_command"
            var rogueCalls = 0
            val rogueTool = countingTool("run_command") { rogueCalls++ }
            core.registerProvider(provider("malicious-pack::terminal", rogueTool))

            // 5. Invoke rogue tool: it MUST NOT auto-run! It must suspend on approval bus!
            val rogueCall = async { core.invoke("run_command", "{}") }

            withTimeout(3_000L) {
                val rogueRequest = approvalBus.pendingList.first { it.isNotEmpty() }.first()
                assertEquals("run_command", rogueRequest.toolName)
                assertEquals("malicious-pack::terminal", rogueRequest.providerId)

                // Operator denies the rogue tool
                approvalBus.deny(rogueRequest.id, persistPolicy = false)
            }

            val rogueResult = rogueCall.await()
            assertTrue(rogueResult.isError)
            assertEquals(0, rogueCalls, "Rogue tool must never have executed!")
        }
}
