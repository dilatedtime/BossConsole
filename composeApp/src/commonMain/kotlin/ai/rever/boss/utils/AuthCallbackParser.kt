package ai.rever.boss.utils

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString

private const val MAX_CALLBACK_LENGTH = 8 * 1024
private const val MAX_TOKEN_LENGTH = 4096
private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
private val TOKEN = Regex("[A-Za-z0-9._~-]+")
private val VERIFICATION_TYPES = setOf("signup", "magiclink", "recovery", "invite", "email", "email_change")

internal sealed interface AuthCallback {
    data class EmailVerification(
        val token: String,
        val type: String,
    ) : AuthCallback

    data class PasskeyRegistration(
        val sessionId: String,
    ) : AuthCallback

    data class PasskeyAuthentication(
        val sessionId: String,
    ) : AuthCallback
}

internal sealed interface AuthCallbackParseResult {
    data object NotAuthCallback : AuthCallbackParseResult

    data class Accepted(
        val callback: AuthCallback,
    ) : AuthCallbackParseResult

    data class Rejected(
        val route: AuthCallbackRoute,
        val reason: AuthCallbackRejection,
    ) : AuthCallbackParseResult
}

internal enum class AuthCallbackRoute {
    EMAIL_VERIFICATION,
    PASSKEY_REGISTRATION,
    PASSKEY_AUTHENTICATION,
    UNKNOWN_AUTH_PATH,
    UNKNOWN_PASSKEY_PATH,
}

internal enum class AuthCallbackRejection {
    CALLBACK_TOO_LONG,
    INVALID_AUTHORITY,
    INVALID_PATH,
    INVALID_PARAMETERS,
    INVALID_TOKEN,
    INVALID_VERIFICATION_TYPE,
    INVALID_SESSION_ID,
}

/**
 * Parses only the authentication callbacks BOSS itself emits.
 *
 * `boss://` is an OS-registered protocol and can be launched by an arbitrary web page. Auth
 * callbacks therefore use an exact authority/path allow-list and validate their credentials as
 * data. Unknown authorities are not auth callbacks and remain available to the general deep-link
 * router; malformed links under the `auth` or `passkey` authorities are recognized and refused.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // Fail-closed grammar reads clearest as ordered guards.
internal fun parseAuthCallback(uri: String): AuthCallbackParseResult {
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd <= 0 || !uri.substring(0, schemeEnd).equals("boss", ignoreCase = true)) {
        return AuthCallbackParseResult.NotAuthCallback
    }

    val authorityStart = schemeEnd + 3
    val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: uri.length
    val authority = uri.substring(authorityStart, authorityEnd)
    val candidate = authority.substringAfterLast('@').substringBefore(':').lowercase()
    val expectedAuthority =
        candidate.takeIf { it == "auth" || it == "passkey" }
            ?: return AuthCallbackParseResult.NotAuthCallback
    val pathEnd = uri.indexOfAny(charArrayOf('?', '#'), authorityEnd).takeIf { it >= 0 } ?: uri.length
    val path = uri.substring(authorityEnd, pathEnd)
    val route = routeFor(expectedAuthority, path)

    if (uri.length > MAX_CALLBACK_LENGTH) {
        return AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.CALLBACK_TOO_LONG)
    }
    if (!authority.equals(expectedAuthority, ignoreCase = true)) {
        return AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_AUTHORITY)
    }
    if (!route.isKnown) {
        return AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_PATH)
    }

    val sections =
        parameterSections(uri, pathEnd)
            ?: return AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_PARAMETERS)
    return when (route) {
        AuthCallbackRoute.EMAIL_VERIFICATION -> parseEmailVerification(sections, route)

        AuthCallbackRoute.PASSKEY_REGISTRATION -> parsePasskey(sections, route, AuthCallback::PasskeyRegistration)

        AuthCallbackRoute.PASSKEY_AUTHENTICATION -> parsePasskey(sections, route, AuthCallback::PasskeyAuthentication)

        AuthCallbackRoute.UNKNOWN_AUTH_PATH,
        AuthCallbackRoute.UNKNOWN_PASSKEY_PATH,
        -> error("unknown routes are rejected before parameter parsing")
    }
}

private val AuthCallbackRoute.isKnown: Boolean
    get() = this != AuthCallbackRoute.UNKNOWN_AUTH_PATH && this != AuthCallbackRoute.UNKNOWN_PASSKEY_PATH

private fun routeFor(
    authority: String,
    path: String,
): AuthCallbackRoute =
    when (authority) {
        "auth" -> {
            if (path == "/verify") AuthCallbackRoute.EMAIL_VERIFICATION else AuthCallbackRoute.UNKNOWN_AUTH_PATH
        }

        "passkey" -> {
            when (path) {
                "/registered" -> AuthCallbackRoute.PASSKEY_REGISTRATION
                "/authenticated" -> AuthCallbackRoute.PASSKEY_AUTHENTICATION
                else -> AuthCallbackRoute.UNKNOWN_PASSKEY_PATH
            }
        }

        else -> {
            error("routeFor is only called for auth authorities")
        }
    }

private data class ParameterSections(
    val query: Parameters,
    val fragment: Parameters,
    val hasFragment: Boolean,
)

private fun parameterSections(
    uri: String,
    pathEnd: Int,
): ParameterSections? =
    runCatching {
        val queryStart = uri.indexOf('?', pathEnd)
        val fragmentStart = uri.indexOf('#', pathEnd)
        if (queryStart >= 0 && fragmentStart >= 0 && fragmentStart < queryStart) return null

        val rawQuery =
            if (queryStart >= 0) {
                uri.substring(queryStart + 1, fragmentStart.takeIf { it >= 0 } ?: uri.length)
            } else {
                ""
            }
        val rawFragment = if (fragmentStart >= 0) uri.substring(fragmentStart + 1) else ""
        ParameterSections(
            query = parseQueryString(rawQuery),
            fragment = parseQueryString(rawFragment),
            hasFragment = fragmentStart >= 0 && rawFragment.isNotEmpty(),
        )
    }.getOrNull()

private fun parseEmailVerification(
    sections: ParameterSections,
    route: AuthCallbackRoute,
): AuthCallbackParseResult {
    val queryTokens = sections.query.getAll("token").orEmpty()
    val fragmentTokens = sections.fragment.getAll("access_token").orEmpty()
    val tokenCount = queryTokens.size + fragmentTokens.size
    val token = (queryTokens + fragmentTokens).singleOrNull().orEmpty()
    val types = sections.query.getAll("type").orEmpty() + sections.fragment.getAll("type").orEmpty()
    val type = types.singleOrNull() ?: "magiclink"
    return when {
        tokenCount != 1 || types.size > 1 -> {
            AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_PARAMETERS)
        }

        token.isEmpty() || token.length > MAX_TOKEN_LENGTH || !TOKEN.matches(token) -> {
            AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_TOKEN)
        }

        type !in VERIFICATION_TYPES -> {
            AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_VERIFICATION_TYPE)
        }

        else -> {
            AuthCallbackParseResult.Accepted(AuthCallback.EmailVerification(token, type))
        }
    }
}

private fun parsePasskey(
    sections: ParameterSections,
    route: AuthCallbackRoute,
    callback: (String) -> AuthCallback,
): AuthCallbackParseResult {
    val sessionIds = sections.query.getAll("sessionId").orEmpty()
    return when {
        sections.hasFragment || sections.query.names() != setOf("sessionId") -> {
            AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_PARAMETERS)
        }

        sessionIds.size != 1 || !UUID.matches(sessionIds.single()) -> {
            AuthCallbackParseResult.Rejected(route, AuthCallbackRejection.INVALID_SESSION_ID)
        }

        else -> {
            AuthCallbackParseResult.Accepted(callback(sessionIds.single()))
        }
    }
}
