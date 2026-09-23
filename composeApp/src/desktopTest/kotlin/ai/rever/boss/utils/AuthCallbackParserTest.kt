package ai.rever.boss.utils

import ai.rever.boss.components.auth.screens.PasskeyCallbackKind
import ai.rever.boss.components.auth.screens.matchesPasskeyCallback
import ai.rever.boss.components.auth.screens.passkeyCallbackKindForBrowserUrl
import ai.rever.boss.services.auth.PasskeyCallbackInbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthCallbackParserTest {
    private val sessionId = "01234567-89ab-4def-8123-456789abcdef"
    private val token = "eyJhbGciOiJIUzI1NiJ9.payload_signature-token"

    @Test
    fun `accepts the callback shapes emitted by first-party producers`() {
        assertEquals(
            AuthCallback.EmailVerification(token, "signup"),
            accepted("boss://auth/verify?token=$token&type=signup"),
        )
        assertEquals(
            AuthCallback.EmailVerification(token, "recovery"),
            accepted("boss://auth/verify#access_token=$token&type=recovery"),
        )
        assertEquals(
            AuthCallback.PasskeyRegistration(sessionId),
            accepted("boss://passkey/registered?sessionId=$sessionId"),
        )
        assertEquals(
            AuthCallback.PasskeyAuthentication(sessionId),
            accepted("boss://passkey/authenticated?sessionId=$sessionId"),
        )
    }

    @Test
    fun `scheme and host follow URI case rules while callback paths stay exact`() {
        assertEquals(
            AuthCallback.EmailVerification(token, "magiclink"),
            accepted("BOSS://AUTH/verify?token=$token"),
        )
        assertRejected("boss://auth/Verify?token=$token")
        assertRejected("boss://auth/verify/?token=$token")
        assertRejected("boss://auth/verify/anything?token=$token")
    }

    @Test
    fun `text lookalikes never become callbacks`() {
        listOf(
            "boss://evil/auth/verify?token=$token",
            "boss://evil/path?next=boss://auth/verify?token=$token",
            "boss://passkeys/authenticated?sessionId=$sessionId",
            "boss://other?value=passkey/registered&sessionId=$sessionId",
            "https://auth/verify?token=$token",
            "boss:/auth/verify?token=$token",
        ).forEach(::assertNotAuth)
    }

    @Test
    fun `recognized authorities reject ports user info and ambiguous paths`() {
        listOf(
            "boss://auth:443/verify?token=$token",
            "boss://user@auth/verify?token=$token",
            "boss://passkey:443/registered?sessionId=$sessionId",
            "boss://user@passkey/authenticated?sessionId=$sessionId",
            "boss://auth?path=/verify&token=$token",
            "boss://passkey?path=/registered&sessionId=$sessionId",
        ).forEach(::assertRejected)
    }

    @Test
    fun `passkey callbacks require one query UUID and no fragment substitute`() {
        listOf(
            "boss://passkey/registered",
            "boss://passkey/registered?sessionId=not-a-uuid",
            "boss://passkey/registered?sessionId=$sessionId&sessionId=$sessionId",
            "boss://passkey/registered#sessionId=$sessionId",
            "boss://passkey/registered?other=sessionId%3D$sessionId",
            "boss://passkey/registered?sessionId=$sessionId&unexpected=value",
            "boss://passkey/authenticated?sessionId=$sessionId#sessionId=other",
        ).forEach(::assertRejected)
    }

    @Test
    fun `verification callbacks reject missing duplicated or smuggled credentials`() {
        listOf(
            "boss://auth/verify",
            "boss://auth/verify?token=",
            "boss://auth/verify?token=$token&token=other",
            "boss://auth/verify?token=$token#access_token=$token",
            "boss://auth/verify?token=abc%26type%3Drecovery&type=magiclink",
            "boss://auth/verify?token=abc%0Adef&type=magiclink",
            "boss://auth/verify?other=token%3D$token",
        ).forEach(::assertRejected)
    }

    @Test
    fun `verification fields are bounded and typed`() {
        listOf("signup", "magiclink", "recovery", "invite", "email", "email_change").forEach { type ->
            assertEquals(
                AuthCallback.EmailVerification(token, type),
                accepted("boss://auth/verify?token=$token&type=$type"),
            )
        }

        assertRejected("boss://auth/verify?token=$token&type=admin")
        assertRejected("boss://auth/verify?token=$token&type=SIGNUP")
        assertRejected("boss://auth/verify?token=${"a".repeat(4097)}&type=magiclink")
        assertRejected("boss://auth/verify?token=abc+def&type=magiclink")
        assertRejected("boss://auth/verify?token=abc/def&type=magiclink")
    }

    @Test
    fun `passkey browser completion is bound to operation and session`() {
        val registration = AuthCallback.PasskeyRegistration(sessionId)
        val authentication = AuthCallback.PasskeyAuthentication(sessionId)

        assertEquals(true, matchesPasskeyCallback(registration, PasskeyCallbackKind.REGISTRATION, sessionId))
        assertEquals(true, matchesPasskeyCallback(authentication, PasskeyCallbackKind.AUTHENTICATION, sessionId))
        assertEquals(false, matchesPasskeyCallback(registration, PasskeyCallbackKind.AUTHENTICATION, sessionId))
        assertEquals(false, matchesPasskeyCallback(authentication, PasskeyCallbackKind.REGISTRATION, sessionId))
        assertEquals(
            false,
            matchesPasskeyCallback(
                authentication,
                PasskeyCallbackKind.AUTHENTICATION,
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            ),
        )
        assertEquals(
            false,
            matchesPasskeyCallback(
                AuthCallback.EmailVerification(token, "magiclink"),
                PasskeyCallbackKind.AUTHENTICATION,
                sessionId,
            ),
        )
    }

    @Test
    fun `browser operation comes only from an exact first-party endpoint path`() {
        val origin = "https://api.risaboss.com"
        assertEquals(
            PasskeyCallbackKind.REGISTRATION,
            passkeyCallbackKindForBrowserUrl("$origin/functions/v1/passkey/register/mobile?sessionId=$sessionId"),
        )
        assertEquals(
            PasskeyCallbackKind.AUTHENTICATION,
            passkeyCallbackKindForBrowserUrl("$origin/functions/v1/passkey/auth/mobile?sessionId=$sessionId"),
        )
        assertEquals(null, passkeyCallbackKindForBrowserUrl("$origin/passkey/register/mobile?sessionId=$sessionId"))
        assertEquals(null, passkeyCallbackKindForBrowserUrl("$origin/functions/v1/passkey/register/mobile/extra"))
        assertEquals(null, passkeyCallbackKindForBrowserUrl("not a URL containing /functions/v1/passkey/auth/mobile"))
    }

    @Test
    fun `validated passkey inbox retains a callback without letting an old consumer clear a newer one`() {
        val first = AuthCallback.PasskeyRegistration(sessionId)
        val second = AuthCallback.PasskeyAuthentication("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        try {
            PasskeyCallbackInbox.publish(first)
            assertEquals(first, PasskeyCallbackInbox.callback.value)

            PasskeyCallbackInbox.publish(second)
            PasskeyCallbackInbox.consume(first)
            assertEquals(second, PasskeyCallbackInbox.callback.value)

            PasskeyCallbackInbox.consume(second)
            assertEquals(null, PasskeyCallbackInbox.callback.value)
        } finally {
            PasskeyCallbackInbox.callback.value?.let(PasskeyCallbackInbox::consume)
        }
    }

    private fun accepted(uri: String): AuthCallback =
        assertIs<AuthCallbackParseResult.Accepted>(
            value = parseAuthCallback(uri),
            message = uri,
        ).callback

    private fun assertRejected(uri: String) {
        assertIs<AuthCallbackParseResult.Rejected>(parseAuthCallback(uri), uri)
    }

    private fun assertNotAuth(uri: String) {
        assertIs<AuthCallbackParseResult.NotAuthCallback>(parseAuthCallback(uri), uri)
    }
}
