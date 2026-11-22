package com.espotek.roborockathome.ui.signin

import androidx.compose.runtime.mutableStateOf
import com.espotek.roborockathome.core.api.ApiException
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.api.SessionStore
import com.espotek.roborockathome.fixtures.FakeServer
import com.espotek.roborockathome.fixtures.answer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// The answers are the demo server's own, recorded from it.
private const val WRONG = """{"error":{"code":"unauthenticated","message":"Wrong name or password."}}"""
private const val TOO_MANY = """{"error":{"code":"too_many_attempts","message":"Too many wrong passwords. Try again in 30 seconds."}}"""

class SignInTest {
    private val server = FakeServer()
    private var signedIn: Triple<String, String, RoborockApi>? = null

    private fun form(address: String = "192.168.1.50:8770/", name: String = " demo ", password: String = "a password") =
        SignInForm(mutableStateOf(address), mutableStateOf(name)).also { it.password = password }

    private fun signIn(form: SignInForm) = runBlocking {
        signIn(form, { RoborockApi(it, NoSession(), server.client) }, { url, name, api -> signedIn = Triple(url, name, api) })
    }

    @Test
    fun emptyFieldsAreSaidNextToEachWithoutAsking() {
        val form = form(address = " ", name = "", password = "")
        signIn(form)
        assertEquals("Enter the server's address.", form.addressError)
        assertEquals("Enter your email or name.", form.nameError)
        assertEquals("Enter your password.", form.passwordError)
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun somethingThatIsNotAnAddress() {
        val form = form(address = "192.168.1.50:port")
        signIn(form)
        assertEquals("Enter an address like 192.168.1.50:8770.", form.addressError)
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun nothingAnsweringIsSaidByTheAddressShownNormalised() {
        val form = form()
        signIn(form)
        assertEquals(UNREACHABLE, form.addressError)
        assertEquals("http://192.168.1.50:8770", form.address)
        assertNull(form.passwordError)
    }

    @Test
    fun anotherKindOfServerIsSaidByTheAddress() {
        server.on("POST /api/login") { it.answer(404, "<html>Not found</html>", type = "text/html") }
        val form = form()
        signIn(form)
        assertEquals(NOT_THIS_SERVER, form.addressError)
    }

    @Test
    fun aWrongPasswordIsSaidByTheCredentialsInTheAppsWords() {
        server.on("POST /api/login") { it.answer(401, WRONG) }
        val form = form()
        signIn(form)
        assertNull(form.addressError)
        assertEquals("That name and password do not match.", form.passwordError)
        assertEquals("a password", form.password)
        assertNull(signedIn)
    }

    @Test
    fun tooManyTriesSaysHowLongToWait() {
        server.on("POST /api/login") { it.answer(429, TOO_MANY, headers = arrayOf("Retry-After" to "30")) }
        val form = form()
        signIn(form)
        assertEquals("Signing in is paused for 30 seconds after too many wrong passwords.", form.passwordError)
        assertEquals("Signing in is paused for 1 second after too many wrong passwords.", paused(1))
        assertEquals("Signing in is paused for 89 seconds after too many wrong passwords.", paused(89))
        assertEquals("Signing in is paused for 2 minutes after too many wrong passwords.", paused(90))
        assertEquals("Signing in is paused for 5 minutes after too many wrong passwords.", paused(300))
    }

    @Test
    fun aRefusalItDoesNotExpectIsThrownForTheFormToShow() {
        server.on("POST /api/login") { it.answer(400, """{"error":{"code":"bad_request","message":"Name is 1-100 characters."}}""") }
        val refused = assertThrows(ApiException::class.java) { signIn(form()) }
        assertEquals("Name is 1-100 characters.", refused.message)
    }

    @Test
    fun signedInWithTheNormalisedAddressAndTheTrimmedNameAndThePasswordDropped() {
        server.on("POST /api/login") { it.answer(200, """{"ok":true}""") }
        val form = form()
        signIn(form)
        val (url, name, api) = checkNotNull(signedIn)
        assertEquals("http://192.168.1.50:8770", url)
        assertEquals("demo", name)
        assertEquals(url, api.baseUrl)
        assertEquals("", form.password)
        assertTrue(listOf(form.addressError, form.nameError, form.passwordError).all { it == null })
    }

    private class NoSession : SessionStore {
        override var cookie: String? = null
    }
}
