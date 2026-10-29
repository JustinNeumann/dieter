package com.getdieter.core.identity

import com.getdieter.core.platform.AuthHttp
import com.getdieter.core.platform.HttpResponse
import com.getdieter.core.runtime.CoreException
import com.getdieter.core.storage.CoreStorage
import com.getdieter.core.testing.ManualClock
import com.getdieter.core.testing.MemorySecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class SignInTest {
    private val redirect = "dieter-test://oauth/callback"
    private val gateway = Gateway.parse("https://gateway.example", "Example")!!
    private val clock = ManualClock()
    private val secrets = MemorySecureStore()
    private val exchanges = mutableListOf<String>()
    private val http =
        object : AuthHttp {
            override suspend fun postJson(url: String, body: String): HttpResponse {
                exchanges += body
                return if ("\"real\"" in body) HttpResponse(200, """{"accessToken":"session"}""")
                else HttpResponse(400, "invalid")
            }
        }
    private val signIn =
        SignIn(
            CoreStorage(FakeFileSystem(), "/state".toPath()),
            http,
            Credentials(secrets),
            redirect,
            clock,
        )

    @Test
    fun aForgedCallbackDoesNotCancelTheRealAttempt() = runTest {
        signIn.begin(gateway)
        assertFailsWith<CoreException> { signIn.complete("$redirect?code=forged") }
        assertFailsWith<CoreException> { signIn.complete(redirect) }
        assertEquals(gateway.origin, signIn.complete("$redirect?code=real").origin)
        assertEquals("session", Credentials(secrets).token(gateway))
        assertFailsWith<CoreException>("a completed attempt is single-use") {
            signIn.complete("$redirect?code=real")
        }
        assertEquals(2, exchanges.size)
    }

    @Test
    fun aPerAttemptReturnAddressGoesToTheGatewayAndItsCallbackCompletes() = runTest {
        val web = SignIn(CoreStorage(FakeFileSystem(), "/state".toPath()), http, Credentials(secrets), redirect, clock, perAttempt = true)
        val page = "https://ha.example/app/addon_dieter/next/signin"
        assertFailsWith<CoreException>("the address is required") { web.begin(gateway) }
        assertFailsWith<CoreException> { web.begin(gateway, "$page?x=1") }
        assertFailsWith<CoreException> { web.begin(gateway, "javascript:alert(1)") }
        val url = web.begin(gateway, page)
        assertTrue("native_redirect_uri=${Urls.encode(page)}&" in url, url)
        assertTrue(web.isCallback("$page?code=real"))
        assertTrue(web.isCallback("$redirect?code=real"), "the platform hands callbacks over under its own address")
        assertFalse(web.isCallback("https://elsewhere.example/next/signin?code=real"))
        assertEquals(gateway.origin, web.complete("$page?code=real").origin)
        assertFalse(web.isCallback("$page?code=real"), "the attempt ended")
    }

    @Test
    fun withoutPerAttemptTheConfiguredAddressIsUsed() = runTest {
        val url = signIn.begin(gateway, "https://ha.example/next/signin")
        assertTrue("native_redirect_uri=${Urls.encode(redirect)}&" in url, url)
        assertFalse(signIn.isCallback("https://ha.example/next/signin?code=real"))
    }

    @Test
    fun anExpiredAttemptEndsWithoutAnExchange() = runTest {
        signIn.begin(gateway)
        clock.current += SignIn.EXPIRY + 1.minutes
        assertFailsWith<CoreException> { signIn.complete("$redirect?code=real") }
        clock.current -= SignIn.EXPIRY + 1.minutes
        assertFailsWith<CoreException>("expiry removed the attempt") {
            signIn.complete("$redirect?code=real")
        }
        assertEquals(0, exchanges.size)
        assertNull(Credentials(secrets).token(gateway))
    }
}
