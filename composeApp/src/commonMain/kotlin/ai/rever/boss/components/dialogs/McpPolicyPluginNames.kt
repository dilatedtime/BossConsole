package ai.rever.boss.components.dialogs

import ai.rever.boss.components.home.LocalPluginStates
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
internal fun mcpPolicyPluginNames(): Map<String, String> {
    val flow = LocalPluginStates.current
    val states = if (flow == null) emptyMap() else flow.collectAsState().value
    return states.mapValues { it.value.manifest.displayName }
}

internal fun policySectionName(
    providerId: String,
    names: Map<String, String>,
): String {
    // Plugin-registered providers carry a "<pluginId>::<providerId>" scoped id; the map is
    // keyed by pluginId, so strip the namespace first or every plugin section falls into the
    // mangling branch (#926, #1633).
    val pluginId = providerId.substringBefore("::")
    val unscopedProviderId = providerId.substringAfter("::")
    val matched =
        names[pluginId]?.takeIf { it.isNotBlank() }
            ?: names[providerId]?.takeIf { it.isNotBlank() }
            ?: names[unscopedProviderId]?.takeIf { it.isNotBlank() }
    if (matched != null) return matched

    val rawToFormat =
        if (providerId.contains("::")) {
            unscopedProviderId.ifBlank { pluginId }
        } else {
            providerId
        }
    return rawToFormat
        .substringAfterLast('.')
        .replace('-', ' ')
        .replace('_', ' ')
        .replaceFirstChar { it.titlecase() }
}
