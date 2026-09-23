package ai.rever.boss

import ai.rever.boss.components.auth.LoginScreen
import ai.rever.boss.components.misc.LoadingScreen
import ai.rever.boss.components.misc.OfflineScreen
import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.auth.MagicLinkErrorService
import ai.rever.boss.services.auth.PasskeyCallbackInbox
import ai.rever.boss.services.auth.PasskeySessionEventHandler
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.AuthCallback
import ai.rever.boss.utils.AuthCallbackParseResult
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.parseAuthCallback
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("BossAppWithAuth")

/**
 * Main app entry point with authentication
 *
 * @param windowId The ID of the window this app instance belongs to
 * @param isFirstWindow Whether this is the first window (for workspace loading)
 * @param panelRegistry The panel registry instance for this window
 */
@Composable
fun ComponentContext.BossAppWithAuth(
    windowId: String,
    isFirstWindow: Boolean = false,
    panelRegistry: ai.rever.boss.components.registery.PanelRegistry,
    onToggleMaximize: (() -> Unit)? = null,
) {
    val authState by AuthService.authState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Initialize authentication service
    LaunchedEffect(Unit) {
        AuthService.initialize()
    }

    // Consume OS deep links once at the root. Child screens receive only validated callbacks.
    val deepLink by DeepLinkHandler.deepLinkFlow.collectAsState()

    LaunchedEffect(deepLink) {
        deepLink?.let { uri ->
            // Never log the URI: email tokens and passkey session identifiers are credentials.
            logger.debug(LogCategory.AUTH, "Received deep link in app")
            WindowFocusManager.bringToFront()

            when (val parsed = parseAuthCallback(uri)) {
                is AuthCallbackParseResult.Accepted -> {
                    when (val callback = parsed.callback) {
                        is AuthCallback.PasskeyRegistration -> {
                            logger.info(LogCategory.AUTH, "Validated passkey registration callback")
                            PasskeyCallbackInbox.publish(callback)
                            PasskeySessionEventHandler.handleRegistrationCompleted(callback.sessionId)
                        }

                        is AuthCallback.PasskeyAuthentication -> {
                            logger.info(LogCategory.AUTH, "Validated passkey authentication callback")
                            PasskeyCallbackInbox.publish(callback)

                            // The cross-device service is already polling. Trigger its existing
                            // completion event immediately when this process tracks the session.
                            coroutineScope.launch {
                                if (PasskeySessionEventHandler.getSessionMetadata(callback.sessionId) != null) {
                                    PasskeySessionEventHandler.handleAuthenticationCompleted(callback.sessionId)
                                } else {
                                    logger.warn(LogCategory.AUTH, "No metadata found for validated passkey session")
                                }
                            }
                        }

                        is AuthCallback.EmailVerification -> {
                            logger.debug(
                                LogCategory.AUTH,
                                "Validated email verification callback",
                                mapOf("type" to callback.type),
                            )
                            coroutineScope.launch {
                                logger.info(LogCategory.AUTH, "Starting magic link authentication process")
                                AuthService.verifyEmail(callback.token, callback.type).fold(
                                    onSuccess = {
                                        logger.info(LogCategory.AUTH, "Magic link authentication successful")
                                        if (authState is AuthService.AuthState.NotAuthenticated) {
                                            AuthService.initialize()
                                        }
                                    },
                                    onFailure = { error ->
                                        logger.error(
                                            LogCategory.AUTH,
                                            "Magic link authentication failed",
                                            error = error,
                                        )
                                        MagicLinkErrorService.setError(
                                            error.message ?: "Magic link verification failed",
                                        )
                                    },
                                )
                            }
                        }
                    }
                    DeepLinkHandler.clearDeepLink()
                }

                is AuthCallbackParseResult.Rejected -> {
                    logger.warn(
                        LogCategory.AUTH,
                        "Rejected malformed authentication callback",
                        mapOf("route" to parsed.route.name, "reason" to parsed.reason.name),
                    )
                    DeepLinkHandler.clearDeepLink()
                }

                AuthCallbackParseResult.NotAuthCallback -> {
                    logger.debug(LogCategory.AUTH, "Routing non-auth deep link to DeepLinkHandler")
                    DeepLinkHandler.processDeepLink(uri)
                    DeepLinkHandler.clearDeepLink()
                }
            }
        }
    }

    // Debug auth state changes
    LaunchedEffect(authState) {
        logger.debug(LogCategory.AUTH, "AuthState changed", mapOf("state" to authState.toString()))
    }

    when (authState) {
        is AuthService.AuthState.Loading -> {
            // Show loading screen
            logger.debug(LogCategory.AUTH, "Showing loading screen")
            LoadingScreen()
        }

        is AuthService.AuthState.Offline -> {
            // Show offline screen with retry button
            logger.debug(LogCategory.AUTH, "Showing offline screen")
            OfflineScreen(
                onRetry = {
                    CoreAuthService.retryInitialization()
                },
            )
        }

        is AuthService.AuthState.NotAuthenticated,
        is AuthService.AuthState.Error,
        -> {
            // Show login screen (it will handle 2FA verification internally)
            // Use key() to prevent recreation when switching between these states
            key("login_screen") {
                LoginScreen(
                    onLoginSuccess = {
                        // This will be called after successful login (and 2FA if required)
                    },
                )
            }
        }

        is AuthService.AuthState.Authenticated -> {
            // Show main BOSS app - all auth methods provide inherent 2FA
            // Plugin wizard is shown inside BossApp where DynamicPluginManager is accessible
            BossApp(
                windowId = windowId,
                isFirstWindow = isFirstWindow,
                panelRegistry = panelRegistry,
                onToggleMaximize = onToggleMaximize,
            )
        }
    }
}
