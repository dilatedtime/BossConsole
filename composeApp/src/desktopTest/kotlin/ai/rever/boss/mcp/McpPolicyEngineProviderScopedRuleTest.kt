package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies provider scoping for tool-level rules (#1360).
 *
 * Persisted tool rules are earned by the contributing provider:
 * 1. An operator granting "Always Allow" to a trusted plugin's tool does not confer standing
 *    ALLOW to an unrelated provider reusing that tool name.
 * 2. A DENY earned on one plugin does not blind an unrelated successor provider.
 * 3. Legacy configs without `ruleProviders` preserve backward compatibility (name-wide).
 * 4. Scoping is durable across serialization round-trips and restarts.
 */
class McpPolicyEngineProviderScopedRuleTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-provider-scoped-rule-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `scoped ALLOW grants access only to the earning provider`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // Grant persistent ALLOW for run_command under trusted-plugin
        assertTrue(
            engine.setToolPolicy(
                toolName = "run_command",
                action = McpPolicyAction.ALLOW,
                providerId = "trusted-plugin::terminal",
            ),
        )

        // Earning provider gets ALLOW
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("run_command", "trusted-plugin::terminal"),
        )
        // Same provider referenced without plugin namespace still matches if provider component matches
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("run_command", "terminal"),
        )

        // An unrelated plugin registering run_command does NOT inherit the ALLOW (preventing alias ride)
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("run_command", "malicious-plugin::terminal"),
        )
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("run_command", "other-plugin::exec"),
        )
        // Null providerId does not match a scoped rule
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("run_command", null),
        )
    }

    @Test
    fun `scoped DENY blocks earning provider without blinding successor`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // Deny run_command for untrusted-plugin
        assertTrue(
            engine.setToolPolicy(
                toolName = "run_command",
                action = McpPolicyAction.DENY,
                providerId = "untrusted-plugin::shell",
            ),
        )

        // Untrusted plugin tool is DENIED
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("run_command", "untrusted-plugin::shell"),
        )

        // A legitimate provider is not blinded by untrusted provider's DENY
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor("run_command", "legit-plugin::shell"),
        )
    }

    @Test
    fun `legacy unscoped rule answers name-wide across all providers`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // Unscoped write (legacy path with providerId = null)
        assertTrue(
            engine.setToolPolicy(
                toolName = "run_command",
                action = McpPolicyAction.ALLOW,
                providerId = null,
            ),
        )

        // Answers ALLOW for any provider
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("run_command", "plugin-a::tool"),
        )
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("run_command", "plugin-b::tool"),
        )
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("run_command", null),
        )
    }

    @Test
    fun `provider scoping is preserved across serialization and engine reload`() {
        val file = createTempPolicyFile()
        val engine1 = McpPolicyEngine(policyFile = file)

        assertTrue(
            engine1.setToolPolicy(
                toolName = "k8s_delete",
                action = McpPolicyAction.ALLOW,
                providerId = "cluster-admin::k8s",
            ),
        )

        // Reload fresh engine reading the same file
        val engine2 = McpPolicyEngine(policyFile = file)
        assertEquals(
            "cluster-admin::k8s",
            engine2.config.value.ruleProviders["k8s_delete"],
        )
        assertEquals(
            McpPolicyAction.ALLOW,
            engine2.policyFor("k8s_delete", "cluster-admin::k8s"),
        )
        assertEquals(
            McpPolicyAction.ASK,
            engine2.policyFor("k8s_delete", "rogue-plugin::k8s"),
        )
    }

    @Test
    fun `setToolPolicyIfAbsent persists provider scope`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        val outcome =
            engine.setToolPolicyIfAbsent(
                toolName = "helm_install",
                action = McpPolicyAction.ALLOW,
                expectedRevocation = engine.revocationVersion("helm_install", "ci::deployer"),
                providerId = "ci::deployer",
            )
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        assertEquals("ci::deployer", engine.config.value.ruleProviders["helm_install"])
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("helm_install", "ci::deployer"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_install", "other::deployer"))
    }

    @Test
    fun `setSectionPolicies associates providerId for all reviewed changes`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        val changes =
            listOf(
                McpSectionPolicyChange(
                    toolName = "docker_restart",
                    action = McpPolicyAction.ALLOW,
                    expectedRevocation = 0L,
                    expectedRule = null,
                    providerId = "sec-plugin::tools",
                ),
                McpSectionPolicyChange(
                    toolName = "docker_stop",
                    action = McpPolicyAction.DENY,
                    expectedRevocation = 0L,
                    expectedRule = null,
                    providerId = "sec-plugin::tools",
                ),
            )

        val outcome = engine.setSectionPolicies(changes)
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        assertEquals("sec-plugin::tools", engine.config.value.ruleProviders["docker_restart"])
        assertEquals("sec-plugin::tools", engine.config.value.ruleProviders["docker_stop"])
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("docker_restart", "sec-plugin::tools"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("docker_stop", "sec-plugin::tools"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("docker_restart", "other-plugin::tools"))
    }

    @Test
    fun `revokePersistedPolicy removes both rule and ruleProvider atomically`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        assertTrue(
            engine.setToolPolicy(
                toolName = "danger_op",
                action = McpPolicyAction.ALLOW,
                providerId = "admin::ops",
            ),
        )
        val config = engine.config.value
        assertTrue("danger_op" in config.rules)
        assertTrue("danger_op" in config.ruleProviders)

        assertTrue(engine.revokePersistedPolicy("danger_op"))
        val updatedConfig = engine.config.value
        assertFalse("danger_op" in updatedConfig.rules)
        assertFalse("danger_op" in updatedConfig.ruleProviders)
    }

    @Test
    fun `legacy config JSON without ruleProviders parses cleanly with empty ruleProviders`() {
        val file = createTempPolicyFile()
        val legacyJson =
            """
            {
                "rules": {
                    "run_command": "ALLOW"
                },
                "providerRules": {},
                "defaultMutatingAction": "ASK",
                "defaultReadOnlyAction": "ALLOW"
            }
            """.trimIndent()
        file.writeText(legacyJson)

        val engine = McpPolicyEngine(policyFile = file)
        val loadedConfig = engine.config.value
        assertTrue(loadedConfig.ruleProviders.isEmpty())
        // Legacy rule answers name-wide
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "any-plugin::sh"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "other-plugin::sh"))
    }

    @Test
    fun `McpMutatingToolCatalog resolveAction respects provider scoping`() {
        val config =
            McpToolPolicyConfig(
                rules = mapOf("custom_mutating" to McpPolicyAction.ALLOW),
                ruleProviders = mapOf("custom_mutating" to "plugin-a::worker"),
            )

        // Matching provider resolves to the rule action
        assertEquals(
            McpPolicyAction.ALLOW,
            McpMutatingToolCatalog.resolveAction(
                toolName = "custom_mutating",
                config = config,
                declaredReadOnly = false,
                providerId = "plugin-a::worker",
            ),
        )

        // Mismatched provider falls through to default mutating action (ASK)
        assertEquals(
            McpPolicyAction.ASK,
            McpMutatingToolCatalog.resolveAction(
                toolName = "custom_mutating",
                config = config,
                declaredReadOnly = false,
                providerId = "plugin-b::worker",
            ),
        )
    }
}
