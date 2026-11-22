package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.espotek.roborockathome.ui.common.Runner
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.Section
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Rah

/**
 * What is typed or shown on these pages that would let someone in: passwords, the Roborock code, a
 * tunnel token and a token just made. A view model keeps it through rotation, as saved state keeps
 * the rest of what is typed, but it is never written into saved state, so it never outlives the
 * process. Settings empties it whenever a page opens or closes.
 */
internal class Secrets : ViewModel() {
    var code by mutableStateOf("")
    var tunnelToken by mutableStateOf("")
    var personPassword by mutableStateOf("")
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var newToken: NewToken? by mutableStateOf(null)

    fun clear() {
        code = ""
        tunnelToken = ""
        personPassword = ""
        currentPassword = ""
        newPassword = ""
        newToken = null
    }
}

/** A token just made. Its [secret] is shown this once. */
internal class NewToken(val id: String, val name: String, val secret: String)

/**
 * What stops a form: [problems] found before sending, shown at their fields, and the server's
 * [refusal], shown above the form's button, or on the password page at the current password.
 */
@Stable
internal class FormErrors {
    var problems: Map<Field, String> by mutableStateOf(emptyMap())
        private set
    var refusal: String? by mutableStateOf(null)

    /** Shows what [found] says and answers whether the form can be sent, which it can when nothing was found. */
    fun check(found: Map<Field, String>): Boolean {
        problems = found
        refusal = null
        return found.isEmpty()
    }

    /** After [field] was changed: what was said about it, and the refusal, were about what it said before. */
    fun edited(field: Field) {
        problems = problems - field
        refusal = null
    }
}

/**
 * A list the server keeps outside the live state, such as who can sign in. A page asks for it when
 * it opens and again after a change; it is never saved, so after rotation it is asked for again.
 */
@Stable
internal class Fetched<T>(private val runner: Runner, private val fetch: suspend () -> T) {
    /** Null until the first answer. */
    var value: T? by mutableStateOf(null)
        private set

    fun load() = runner.launch { value = fetch() }
}

@Composable
internal fun <T> rememberFetched(fetch: suspend () -> T): Fetched<T> {
    val runner = rememberRunner()
    return remember(runner) { Fetched(runner, fetch) }
}

/** A page Settings opens: [title] with Back, and [content] scrolling under it, its blocks 16 dp apart. */
@Composable
internal fun SubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenFrame(topBar = { TopBar(title, onBack = onBack, actions = actions) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Rah.space.s8, bottom = Rah.space.s32),
            verticalArrangement = Arrangement.spacedBy(Rah.space.s16),
            content = content,
        )
    }
}

/** A page's opening line, or a note under a control, in the gutter. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Rah.type.supporting, color = Rah.colors.textSupporting, modifier = modifier.gutter())
}

/** A statement in body text, in the gutter. */
@Composable
internal fun Statement(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Rah.type.body, color = Rah.colors.text, modifier = modifier.gutter())
}

/**
 * A part of a page under its heading, a little further from what is above it than blocks are from
 * each other. Its blocks carry the gutter themselves, as on the rest of the page.
 */
@Composable
internal fun PageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Section(title, Modifier.padding(top = Rah.space.s8), headingModifier = Modifier.gutter(), content = content)
}

internal fun Modifier.gutter(): Modifier = padding(horizontal = Rah.space.gutter)
