package ai.rever.boss.services.auth

import ai.rever.boss.utils.AuthCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Retains the latest validated passkey callback until the matching browser screen consumes it.
 *
 * The root auth collector must clear the raw OS deep link immediately, while Compose child
 * collectors are not guaranteed to observe that transient value first. Keeping only the parsed,
 * non-secret callback makes delivery deterministic without retaining or rebroadcasting the URI.
 */
internal object PasskeyCallbackInbox {
    private val _callback = MutableStateFlow<AuthCallback?>(null)
    val callback: StateFlow<AuthCallback?> = _callback.asStateFlow()

    fun publish(callback: AuthCallback) {
        require(callback is AuthCallback.PasskeyRegistration || callback is AuthCallback.PasskeyAuthentication)
        _callback.value = callback
    }

    fun consume(callback: AuthCallback) {
        _callback.compareAndSet(callback, null)
    }
}
