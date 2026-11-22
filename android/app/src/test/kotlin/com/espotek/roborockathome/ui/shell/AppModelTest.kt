package com.espotek.roborockathome.ui.shell

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.espotek.roborockathome.data.Preferences
import com.espotek.roborockathome.fixtures.FakeServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// No activity is started, so the app is never visible and no store opens its stream.
@RunWith(RobolectricTestRunner::class)
class AppModelTest {
    private val prefs = Preferences(RuntimeEnvironment.getApplication())
    private val server = FakeServer()

    private val models = ViewModelStore()
    private var made = 0

    // Held in a store, so that clearing it unhooks each model from the app's lifecycle.
    private fun model(saved: SavedStateHandle = SavedStateHandle()): AppModel =
        ViewModelProvider.create(models, viewModelFactory { initializer { AppModel(prefs, saved, server.client) } })
            .get("model ${made++}", AppModel::class)

    // The models write Compose state with no composition running. Compose applies such writes from
    // the main looper; left queued when the test ends, they are lost with it, and every later
    // Compose test in this JVM then waits for them and never sees the app idle.
    @After
    fun clear() {
        models.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aFirstLaunchHasAnEmptyFormAndNoSession() {
        val model = model()
        assertNull(model.session)
        assertEquals("", model.form.address)
        assertEquals("", model.form.name)
    }

    @Test
    fun aKeptCookieResumesTheSessionAndAMissingOneFillsTheForm() {
        prefs.serverUrl = "http://192.168.1.50:8770"
        prefs.name = "demo"
        val signedOut = model()
        assertNull(signedOut.session)
        assertEquals("http://192.168.1.50:8770", signedOut.form.address)
        assertEquals("demo", signedOut.form.name)

        prefs.cookie = "s3cr3t"
        val resumed = checkNotNull(model().session)
        assertEquals("http://192.168.1.50:8770", resumed.serverUrl)
        assertEquals(resumed.serverUrl, resumed.store.api.baseUrl)
    }

    // What the system keeps for an activity it has killed, given back to the next process.
    @Test
    fun theFormsAddressAndNameOutliveTheProcessButNotThePassword() {
        val saved = SavedStateHandle()
        val before = model(saved)
        before.form.address = "10.0.2.2:8793"
        before.form.name = "someone"
        before.form.password = "a password"
        val after = model(SavedStateHandle.createHandle(saved.savedStateProvider().saveState(), null))
        assertEquals("10.0.2.2:8793", after.form.address)
        assertEquals("someone", after.form.name)
        assertEquals("", after.form.password)
    }

    @Test
    fun signingInRemembersTheAddressAndName() {
        val model = model()
        val api = model.connect("http://10.0.2.2:8793")
        model.signedIn("http://10.0.2.2:8793", "demo", api)
        assertSame(api, checkNotNull(model.session).store.api)
        assertEquals("http://10.0.2.2:8793", prefs.serverUrl)
        assertEquals("demo", prefs.name)
    }

    @Test
    fun signingOutForgetsTheCookieAndAsksTheServerNothing() {
        // The fake server answers nothing here, as a server that has moved would: signing out still works.
        val model = signedInModel()
        model.signOut(checkNotNull(model.session))
        assertEquals(emptyList<String>(), server.asked)
        assertNull(model.session)
        assertNull(prefs.cookie)
        assertEquals("http://192.168.1.50:8770", prefs.serverUrl)
    }

    private fun signedInModel(): AppModel {
        prefs.serverUrl = "http://192.168.1.50:8770"
        prefs.cookie = "s3cr3t"
        return model()
    }
}
