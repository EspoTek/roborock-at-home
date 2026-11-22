package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.User
import com.espotek.roborockathome.ui.common.ConfirmDialog
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.IconOnlyButton
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PasswordField
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah

/** The name being typed for a new person. Their password is one of the [Secrets]. */
@Stable
internal class PersonDraft(name: String = "") {
    var name by mutableStateOf(name)

    companion object {
        val Saver = listSaver<PersonDraft, String>(save = { listOf(it.name) }, restore = { PersonDraft(it.single()) })
    }
}

/**
 * People: who can sign in, which one is you, removing anyone else, and adding a person. [users] are
 * null until fetched; [onChanged] fetches them again.
 */
@Composable
internal fun PeoplePage(
    users: List<User>?,
    api: RoborockApi,
    secrets: Secrets,
    words: TimeWords,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    draft: PersonDraft = rememberSaveable(saver = PersonDraft.Saver) { PersonDraft() },
) {
    SubPage(SettingsPage.People.title, onBack) {
        Note("Everyone here can sign in and has the same access.")
        if (users != null) {
            Column {
                for (user in users) key(user.name) { Person(user, api, words, onChanged) }
            }
        }
        AddPerson(draft, api, secrets, onChanged)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Person(user: User, api: RoborockApi, words: TimeWords, onRemoved: () -> Unit) {
    val runner = rememberRunner()
    var asking by rememberSaveable { mutableStateOf(false) }
    ListRow(
        detail = if (runner.busy) "Removing" else "Added ${words.dayMonth(user.created)}",
        title = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Rah.space.s8), verticalArrangement = Arrangement.spacedBy(Rah.space.s4)) {
                Text(user.name, style = Rah.type.body, color = Rah.colors.text, modifier = Modifier.align(Alignment.CenterVertically))
                if (user.you) YouBadge(Modifier.align(Alignment.CenterVertically))
            }
        },
        action = {
            if (!user.you) {
                IconOnlyButton(
                    Glyph.Trash,
                    contentDescription = "Remove ${user.name}",
                    onClick = { asking = true },
                    enabled = !runner.busy,
                    tint = Rah.colors.attention,
                )
            }
        },
    )
    if (asking) {
        ConfirmDialog(
            title = "Remove ${user.name}?",
            text = "They are signed out and can no longer sign in.",
            confirmLabel = "Remove",
            onConfirm = {
                asking = false
                runner.launch {
                    api.removeUser(user.name)
                    onRemoved()
                }
            },
            onDismiss = { asking = false },
        )
    }
}

@Composable
private fun YouBadge(modifier: Modifier = Modifier) {
    Text(
        "You",
        style = Rah.type.label,
        color = Rah.colors.accent,
        modifier = modifier
            .background(Rah.colors.accentSurface, Rah.shapes.chip)
            .padding(horizontal = Rah.space.s8),
    )
}

/**
 * An item of a list on these pages, people or tokens: [title], a [detail] line under it, and an
 * [action] at the end, such as Remove. TalkBack reads the title and detail together.
 */
@Composable
internal fun ListRow(detail: String, title: @Composable () -> Unit, action: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            // The action's 48 dp target ends 4 dp from the edge, so its 24 dp icon lines up with the gutter.
            .padding(start = Rah.space.gutter, end = Rah.space.s4),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).padding(vertical = Rah.space.s8).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            title()
            Text(detail, style = Rah.type.supporting, color = Rah.colors.textSupporting)
        }
        action()
    }
}

@Composable
private fun AddPerson(draft: PersonDraft, api: RoborockApi, secrets: Secrets, onAdded: () -> Unit) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val messages = LocalMessages.current
    val add = {
        if (errors.check(personProblems(draft.name, secrets.personPassword))) {
            runner.launch(onError = { errors.refusal = it }) {
                val name = draft.name.trim()
                api.addUser(name, secrets.personPassword)
                draft.name = ""
                secrets.personPassword = ""
                messages.success("$name can now sign in")
                onAdded()
            }
        }
    }
    PageSection("Add a person") {
        LabelledField(
            draft.name,
            onValueChange = {
                draft.name = it
                errors.edited(Field.PersonName)
            },
            label = "Email or name",
            modifier = Modifier.gutter(),
            error = errors.problems[Field.PersonName],
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        PasswordField(
            secrets.personPassword,
            onValueChange = {
                secrets.personPassword = it
                errors.edited(Field.PersonPassword)
            },
            label = "Password",
            modifier = Modifier.gutter(),
            supporting = "At least 8 characters",
            error = errors.problems[Field.PersonPassword],
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { add() }),
        )
        errors.refusal?.let { ErrorText(it, Modifier.gutter()) }
        PrimaryButton("Add person", onClick = add, modifier = Modifier.gutter(), busy = runner.busy)
    }
}
