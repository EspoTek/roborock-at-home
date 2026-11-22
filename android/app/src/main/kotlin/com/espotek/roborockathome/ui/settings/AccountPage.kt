package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.ui.common.ConfirmDialog
import com.espotek.roborockathome.ui.common.DangerButton
import com.espotek.roborockathome.ui.common.ErrorText
import com.espotek.roborockathome.ui.common.Hairline
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.QuietButton
import com.espotek.roborockathome.ui.common.WarningText
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Rah

/**
 * The steps of linking an account: the email address, then the code Roborock sent to it. A linked
 * account with a problem goes through them again once the person has asked to [relink].
 */
@Stable
internal class AccountDraft(email: String = "", codeSentTo: String? = null, relink: Boolean = false) {
    var email by mutableStateOf(email)

    /** Where Roborock sent a code, once it has: the page then asks for the code. */
    var codeSentTo by mutableStateOf(codeSentTo)

    /** Link account again has been pressed. */
    var relink by mutableStateOf(relink)

    companion object {
        val Saver = listSaver<AccountDraft, Any?>(
            save = { listOf(it.email, it.codeSentTo, it.relink) },
            restore = { AccountDraft(it[0] as String, it[1] as String?, it[2] as Boolean) },
        )
    }
}

/**
 * Roborock account: which account is linked, and when it has a problem the way to link it again,
 * which keeps its robots and schedules; or the steps to link one. The demo has none.
 */
@Composable
internal fun AccountPage(
    app: AppInfo,
    api: RoborockApi,
    secrets: Secrets,
    onBack: () -> Unit,
    draft: AccountDraft = rememberSaveable(saver = AccountDraft.Saver) { AccountDraft() },
) {
    SubPage(SettingsPage.Account.title, onBack) {
        if (app.demo) {
            Statement(
                "This is the demo. The four robots are simulated, so nothing done here reaches a real robot. " +
                    "Linking a Roborock account is not available in the demo.",
            )
        } else if (app.account.linked) {
            Linked(app, draft, secrets, api)
        } else {
            LinkSteps(draft, secrets, api)
        }
    }
}

@Composable
private fun Linked(app: AppInfo, draft: AccountDraft, secrets: Secrets, api: RoborockApi) {
    Column(Modifier.gutter().semantics(mergeDescendants = true) {}) {
        Text("Linked as", style = Rah.type.supporting, color = Rah.colors.textSupporting)
        Text(linkedEmail(app), style = Rah.type.sectionHeading, color = Rah.colors.text)
    }
    val problem = app.account.problem
    if (problem != null) {
        WarningText(problem, Modifier.gutter())
        // The server links the same account again in place; the address shown is masked, so it is typed.
        if (draft.relink) {
            LinkSteps(draft, secrets, api)
        } else {
            PrimaryButton("Link account again", onClick = { draft.relink = true }, modifier = Modifier.gutter())
        }
        // Unlinking takes the robots and schedules with it, so it stands apart from linking again.
        Hairline(Modifier.padding(top = Rah.space.s8))
    }
    val runner = rememberRunner()
    var asking by rememberSaveable { mutableStateOf(false) }
    DangerButton("Unlink account", onClick = { asking = true }, modifier = Modifier.gutter(), busy = runner.busy)
    if (asking) {
        ConfirmDialog(
            title = "Unlink the Roborock account?",
            text = "The robots, their maps and their schedules are removed from this app. You can link the account again later.",
            confirmLabel = "Unlink",
            onConfirm = {
                asking = false
                runner.launch { api.unlinkAccount() }
            },
            onDismiss = { asking = false },
        )
    }
}

@Composable
private fun LinkSteps(draft: AccountDraft, secrets: Secrets, api: RoborockApi) {
    val sentTo = draft.codeSentTo
    if (sentTo == null) EmailStep(draft, api) else CodeStep(sentTo, draft, secrets, api)
}

@Composable
private fun EmailStep(draft: AccountDraft, api: RoborockApi) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val send = {
        if (errors.check(emailProblems(draft.email))) {
            runner.launch(onError = { errors.refusal = it }) {
                val email = draft.email.trim()
                api.requestAccountCode(email)
                draft.codeSentTo = email
            }
        }
    }
    Note("Use the email address of your Roborock app account, and Roborock will email you a code.")
    LabelledField(
        draft.email,
        onValueChange = {
            draft.email = it
            errors.edited(Field.Email)
        },
        label = "Email",
        modifier = Modifier.gutter(),
        error = errors.problems[Field.Email],
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
    )
    errors.refusal?.let { ErrorText(it, Modifier.gutter()) }
    PrimaryButton("Send code", onClick = send, modifier = Modifier.gutter(), busy = runner.busy)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeStep(sentTo: String, draft: AccountDraft, secrets: Secrets, api: RoborockApi) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val messages = LocalMessages.current
    val link = {
        if (errors.check(codeProblems(secrets.code))) {
            runner.launch(onError = { errors.refusal = it }) {
                api.linkAccount(sentTo, secrets.code.trim())
                secrets.code = ""
                draft.email = ""
                draft.codeSentTo = null
                draft.relink = false
                messages.success("Your Roborock account is linked and the server is looking for its robots.")
            }
        }
    }
    Note("Enter the code Roborock sent to $sentTo.")
    LabelledField(
        secrets.code,
        onValueChange = {
            secrets.code = it
            errors.edited(Field.Code)
        },
        label = "Code",
        modifier = Modifier.gutter(),
        error = errors.problems[Field.Code],
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { link() }),
    )
    errors.refusal?.let { ErrorText(it, Modifier.gutter()) }
    FlowRow(
        Modifier.gutter(),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s8),
        verticalArrangement = Arrangement.spacedBy(Rah.space.s8),
    ) {
        PrimaryButton("Link account", onClick = link, busy = runner.busy)
        QuietButton(
            "Use a different email",
            onClick = {
                secrets.code = ""
                draft.codeSentTo = null
            },
            enabled = !runner.busy,
        )
    }
}
