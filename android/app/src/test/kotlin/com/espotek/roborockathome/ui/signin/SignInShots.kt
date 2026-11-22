package com.espotek.roborockathome.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.api.SessionStore
import com.espotek.roborockathome.fixtures.FakeServer
import com.espotek.roborockathome.fixtures.answer
import com.espotek.roborockathome.shots.ShotSize
import com.espotek.roborockathome.shots.shot
import com.espotek.roborockathome.ui.theme.Rah
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sign-in form: build/shots/signin-*.png. Each error comes from signing in against a fake server. */
@RunWith(RobolectricTestRunner::class)
class SignInShots {
    @Test fun empty() = shot("signin") { Page(form()) }

    @Test fun emptyLargeText() = shot("signin", fontScale = 2f) { Page(form()) }

    // After signing out, or when the session has ended: the address and name are filled in.
    @Test fun remembered() = shot("signin-remembered") { Page(form("http://192.168.1.50:8770", "demo")) }

    @Test fun signingIn() = shot("signin-busy") { Page(form("http://192.168.1.50:8770", "demo", "a password"), busy = true) }

    @Test fun missing() = shotOf("signin-missing", tried(form()))

    @Test fun notAnAddress() = shotOf("signin-not-an-address", tried(form("192.168.1.50:port", "demo", "a password")))

    @Test fun unreachable() = shotOf("signin-unreachable", tried(filled()))

    @Test fun notThisServer() =
        shotOf("signin-not-this-server", tried(filled()) { it.answer(404, "<html>Not found</html>", type = "text/html") })

    @Test fun wrongPassword() = shotOf("signin-wrong-password", refusedPassword())

    @Test fun wrongPasswordLargeText() = shotOf("signin-wrong-password", refusedPassword(), fontScale = 2f)

    @Test fun tooManyTries() =
        shotOf("signin-too-many-tries", tried(filled()) { it.answer(429, TOO_MANY, headers = arrayOf("Retry-After" to "30")) })

    @Test fun landscape() = shotOf("signin-unreachable", tried(filled()), ShotSize.Landscape)

    // The keyboard is up and the password has the focus after a refusal: the password, its error and
    // the whole of Sign in stay in view above the keyboard.
    @Test fun withKeyboard() = withKeyboard(fontScale = 1f)

    @Test fun withKeyboardLargeText() = withKeyboard(fontScale = 2f)

    private fun withKeyboard(fontScale: Float) {
        val form = refusedPassword()
        shot("signin-keyboard", ShotSize.SmallPhone, fontScale, act = { onAllNodes(hasSetTextAction())[2].requestFocus() }) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Page(form) }
                KeyboardSpace()
            }
        }
    }

    private fun shotOf(name: String, form: SignInForm, size: ShotSize = ShotSize.Phone, fontScale: Float = 1f) =
        shot(name, size, fontScale) { Page(form) }

    @Composable
    private fun Page(form: SignInForm, busy: Boolean = false) = SignInPage(form, busy, onSignIn = {})

    private fun form(address: String = "", name: String = "", password: String = "") =
        SignInForm(mutableStateOf(address), mutableStateOf(name)).also { it.password = password }

    private fun filled() = form("192.168.1.50:8770", "demo", "a password")

    private fun refusedPassword() = tried(filled()) { it.answer(401, WRONG) }

    /** [form] after pressing Sign in, against a server that answers the login with [login], or with nothing. */
    private fun tried(form: SignInForm, login: ((Request) -> Response)? = null): SignInForm {
        val server = FakeServer()
        if (login != null) server.on("POST /api/login", login)
        runBlocking { signIn(form, { RoborockApi(it, NoSession(), server.client) }, { _, _, _ -> error("Signed in") }) }
        return form
    }

    /** Where the keyboard would be: about what Android's own takes on a 360 x 640 phone. */
    @Composable
    private fun KeyboardSpace() {
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(Rah.colors.surfaceSecondary),
            contentAlignment = Alignment.Center,
        ) {
            Text("Keyboard", style = Rah.type.supporting, color = Rah.colors.textQuiet)
        }
    }

    private class NoSession : SessionStore {
        override var cookie: String? = null
    }
}

// The demo server's own answers.
private const val WRONG = """{"error":{"code":"unauthenticated","message":"Wrong name or password."}}"""
private const val TOO_MANY = """{"error":{"code":"too_many_attempts","message":"Too many wrong passwords. Try again in 30 seconds."}}"""
