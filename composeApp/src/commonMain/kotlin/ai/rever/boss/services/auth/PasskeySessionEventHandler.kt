package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * PasskeySessionEventHandler - Handles passkey session completion events from deep links
 *
 * This service coordinates cross-device passkey flows by:
 * - Tracking active passkey sessions by sessionId
 * - Notifying listeners when passkey operations complete via deep links
 * - Triggering appropriate UI updates and authentication completion
 */
object PasskeySessionEventHandler {
    private val logger = BossLogger.forComponent("PasskeySessionEventHandler")

    /**
     * Passkey session event types
     */
    sealed class PasskeySessionEvent {
        data class RegistrationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()

        data class AuthenticationCompleted(
            val sessionId: String,
        ) : PasskeySessionEvent()
    }

    /**
     * Flow of passkey session events
     */
    private val _sessionEvents = MutableStateFlow<PasskeySessionEvent?>(null)

    /**
     * Public flow of passkey session events for observers.
     */
    val sessionEvents: StateFlow<PasskeySessionEvent?> = _sessionEvents.asStateFlow()

    /**
     * Map of active sessions being tracked
     * Key: sessionId, Value: session metadata
     */
    private val activeSessions = ConcurrentHashMap<String, SessionMetadata>()

    data class SessionMetadata(
        val sessionId: String,
        val email: String,
        val type: SessionType,
        val timestamp: Long = System.currentTimeMillis(),
    )

    enum class SessionType {
        REGISTRATION,
        AUTHENTICATION,
    }

    /**
     * Track an active passkey session.
     */
    fun registerSession(metadata: SessionMetadata) {
        activeSessions[metadata.sessionId] = metadata
        logger.debug(
            LogCategory.PASSKEY,
            "Registered active passkey session",
            mapOf("sessionId" to metadata.sessionId, "type" to metadata.type.name),
        )
    }

    /**
     * Remove an active passkey session.
     */
    fun removeSession(sessionId: String): SessionMetadata? =
        activeSessions.remove(sessionId)?.also {
            logger.debug(LogCategory.PASSKEY, "Removed passkey session", mapOf("sessionId" to sessionId))
        }

    /**
     * Handle passkey registration completion from deep link
     */
    fun handleRegistrationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Registration completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.RegistrationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of registration completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for registration completion")
        }
    }

    /**
     * Handle passkey authentication completion from deep link
     */
    fun handleAuthenticationCompleted(sessionId: String) {
        logger.info(LogCategory.PASSKEY, "Authentication completed for session")

        val metadata = activeSessions[sessionId]
        if (metadata != null) {
            _sessionEvents.value = PasskeySessionEvent.AuthenticationCompleted(sessionId)
            logger.debug(LogCategory.PASSKEY, "Notified listeners of authentication completion")
        } else {
            logger.warn(LogCategory.PASSKEY, "No active session found for authentication completion")
        }
    }

    /**
     * Get metadata for an active session
     */
    fun getSessionMetadata(sessionId: String): SessionMetadata? = activeSessions[sessionId]

    /**
     * Reset tracked sessions and event state for tests.
     */
    internal fun resetForTest() {
        activeSessions.clear()
        _sessionEvents.value = null
    }
}
