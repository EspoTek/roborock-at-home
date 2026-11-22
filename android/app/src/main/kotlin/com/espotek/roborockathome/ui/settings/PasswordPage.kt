package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PasswordField
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.rememberRunner

/** Password: the person's own, changed with the current one; both are [Secrets]. */
@Composable
internal fun PasswordPage(api: RoborockApi, secrets: Secrets, onBack: () -> Unit) {
    val runner = rememberRunner()
    val errors = remember { FormErrors() }
    val messages = LocalMessages.current
    val change = {
        if (errors.check(passwordProblems(secrets.currentPassword, secrets.newPassword))) {
            runner.launch(onError = { errors.refusal = it }) {
                api.changePassword(secrets.currentPassword, secrets.newPassword)
                secrets.currentPassword = ""
                secrets.newPassword = ""
                messages.success("Your password is changed and your other devices are signed out.")
            }
        }
    }
    SubPage(SettingsPage.Password.title, onBack) {
        Note("Changing your password signs you out on your other devices.")
        PasswordField(
            secrets.currentPassword,
            onValueChange = {
                secrets.currentPassword = it
                errors.edited(Field.CurrentPassword)
            },
            label = "Current password",
            modifier = Modifier.gutter(),
            // With the new password checked before sending, what the server refuses is the current one.
            error = errors.problems[Field.CurrentPassword] ?: errors.refusal,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            secrets.newPassword,
            onValueChange = {
                secrets.newPassword = it
                errors.edited(Field.NewPassword)
            },
            label = "New password",
            modifier = Modifier.gutter(),
            supporting = "At least 8 characters",
            error = errors.problems[Field.NewPassword],
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { change() }),
        )
        PrimaryButton("Change password", onClick = change, modifier = Modifier.gutter(), busy = runner.busy)
    }
}
