package ai.rever.boss.crash

/**
 * Post-unload plugin classloader refusal detection for [CrashHandler.isIgnorable].
 *
 * When a dynamic plugin is unloaded (e.g., during hot-reload, disable, or account switch),
 * lingering background tasks (such as Ktor selector loops, coroutines, or third-party thread pools)
 * may attempt to resolve classes against the closed or closing classloader.
 * `PluginClassLoader.loadClassChildFirst` deliberately throws [ClassNotFoundException] with:
 * `"Plugin classloader for '<id>' is <state>; refusing to resolve '<class>' against the host classloader."`
 * to protect the host runtime from class-graph splicing.
 *
 * When JVM class resolution encounters this refusal, it may wrap or propagate it as
 * [ClassNotFoundException] or [NoClassDefFoundError]. Because this refusal is a deliberate,
 * safe containment boundary during teardown, it must be treated as ignorable so it does not
 * pop the fatal crash dialog or terminate the application.
 */
internal fun isUnloadedPluginClassLoaderRefusal(throwable: Throwable): Boolean {
    if (throwable !is ClassNotFoundException && throwable !is NoClassDefFoundError) return false
    val message = throwable.message.orEmpty()
    return message.startsWith("Plugin classloader for '") && message.contains("; refusing to resolve '")
}
