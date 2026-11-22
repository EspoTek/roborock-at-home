package com.espotek.roborockathome.ui.settings

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.Token
import com.espotek.roborockathome.core.model.TokenScope
import com.espotek.roborockathome.ui.common.BorderedCard
import com.espotek.roborockathome.ui.common.ConfirmDialog
import com.espotek.roborockathome.ui.common.CopyableValue
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.IconOnlyButton
import com.espotek.roborockathome.ui.common.IndexRow
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.SegmentedControl
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.copyText
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.Rah

/** The token being made: its name and what it may do. */
@Stable
internal class TokenDraft(name: String = "", scope: TokenScope = TokenScope.Control) {
    var name by mutableStateOf(name)
    var scope by mutableStateOf(scope)

    companion object {
        val Saver = listSaver<TokenDraft, Any>(
            save = { listOf(it.name, it.scope) },
            restore = { TokenDraft(it[0] as String, it[1] as TokenScope) },
        )
    }
}

/**
 * API tokens: the list with what each may do, when it was made and last used, the token just made
 * to copy, deleting one, making one, and where the API is described. [tokens] are null until
 * fetched; [onChanged] fetches them again.
 */
@Composable
internal fun TokensPage(
    tokens: List<Token>?,
    api: RoborockApi,
    secrets: Secrets,
    words: TimeWords,
    serverUrl: String,
    onChanged: () -> Unit,
    onBack: () -> Unit,
    draft: TokenDraft = rememberSaveable(saver = TokenDraft.Saver) { TokenDraft() },
) {
    val uris = LocalUriHandler.current
    val docs = remember(serverUrl) { Uri.parse(serverUrl).buildUpon().appendEncodedPath("api/docs").build().toString() }
    SubPage(SettingsPage.Tokens.title, onBack) {
        Note("For scripts, agents and home automation.")
        IndexRow("API documentation", docs, onClick = { uris.openUri(docs) })
        secrets.newToken?.let { NewTokenCard(it) }
        if (!tokens.isNullOrEmpty()) {
            Column {
                for (token in tokens) key(token.id) { TokenRow(token, api, secrets, words, onChanged) }
            }
        }
        CreateToken(draft, api, secrets, onChanged)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTokenCard(token: NewToken) {
    val context = LocalContext.current
    val messages = LocalMessages.current
    val view = remember { BringIntoViewRequester() }
    // It appears near the top, and the page is likely scrolled down to the form that made it.
    LaunchedEffect(token) { view.bringIntoView() }
    BorderedCard(Modifier.gutter().bringIntoViewRequester(view)) {
        Column(verticalArrangement = Arrangement.spacedBy(Rah.space.s12)) {
            CopyableValue("New token for ${token.name}", token.secret, onCopy = {
                context.copyText("API token", token.secret, secret = true)
                messages.success("Token copied")
            })
            Text("This token is shown only once, so copy it now.", style = Rah.type.label, color = Rah.colors.text)
        }
    }
}

@Composable
private fun TokenRow(token: Token, api: RoborockApi, secrets: Secrets, words: TimeWords, onDeleted: () -> Unit) {
    val runner = rememberRunner()
    var asking by rememberSaveable { mutableStateOf(false) }
    ListRow(
        detail = if (runner.busy) "Deleting" else tokenDetail(token, words),
        title = { Text(token.name, style = Rah.type.body, color = Rah.colors.text) },
        action = {
            IconOnlyButton(
                Glyph.Trash,
                contentDescription = "Delete ${token.name}",
                onClick = { asking = true },
                enabled = !runner.busy,
                tint = Rah.colors.attention,
            )
        },
    )
    if (asking) {
        ConfirmDialog(
            title = "Delete the token ${token.name}?",
            text = "Anything using it stops working straight away.",
            confirmLabel = "Delete",
            onConfirm = {
                asking = false
                runner.launch {
                    api.deleteToken(token.id)
                    if (secrets.newToken?.id == token.id) secrets.newToken = null
                    onDeleted()
                }
            },
            onDismiss = { asking = false },
        )
    }
}

private val SCOPES = mapOf(TokenScope.Read to "Read only", TokenScope.Control to "Read and control")

@Composable
private fun CreateToken(draft: TokenDraft, api: RoborockApi, secrets: Secrets, onCreated: () -> Unit) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val create = {
        if (errors.check(tokenProblems(draft.name))) {
            runner.launch(onError = { errors.refusal = it }) {
                val name = draft.name.trim()
                val made = api.createToken(name, draft.scope)
                secrets.newToken = NewToken(made.id, name, made.token)
                draft.name = ""
                onCreated()
            }
        }
    }
    PageSection("Create a token") {
        LabelledField(
            draft.name,
            onValueChange = {
                draft.name = it
                errors.edited(Field.TokenName)
            },
            label = "Name",
            modifier = Modifier.gutter(),
            supporting = "For example, Home Assistant",
            error = errors.problems[Field.TokenName],
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { create() }),
        )
        Column(Modifier.gutter(), verticalArrangement = Arrangement.spacedBy(Rah.space.s8)) {
            Text("What it can do", style = Rah.type.label, color = Rah.colors.textSupporting)
            SegmentedControl(
                options = SCOPES.keys.toList(),
                selected = draft.scope,
                onSelect = { draft.scope = it },
                label = SCOPES::getValue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        errors.refusal?.let { ErrorText(it, Modifier.gutter()) }
        PrimaryButton("Create token", onClick = create, modifier = Modifier.gutter(), busy = runner.busy)
    }
}
