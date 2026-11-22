package com.espotek.roborockathome.ui.signin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.PasswordField
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.shell.StandalonePage
import com.espotek.roborockathome.ui.theme.Rah
import kotlinx.coroutines.flow.first

/**
 * Signing in: the server's address, the person's email or name and their password. [connect] makes
 * a client for an address; [onSignedIn] gets the address as normalised, the name and the client
 * once the server has let them in.
 */
@Composable
fun SignInScreen(
    form: SignInForm,
    connect: (serverUrl: String) -> RoborockApi,
    onSignedIn: (serverUrl: String, name: String, api: RoborockApi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val runner = rememberRunner()
    SignInPage(
        form,
        busy = runner.busy,
        onSignIn = { runner.launch(onError = { form.passwordError = it }) { signIn(form, connect, onSignedIn) } },
        modifier,
    )
}

/**
 * The form itself, for [SignInScreen] and for pictures of it. While the keyboard is up for the
 * password, and whenever a field shows an error, the page scrolls so the password, its error and
 * the whole of Sign in are in view, 16 dp clear of the keyboard; the mark and the earlier fields may
 * scroll away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SignInPage(form: SignInForm, busy: Boolean, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val c = Rah.colors
    val action = remember { BringIntoViewRequester() }
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    var passwordFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density)
    val clearance = with(density) { Rah.space.s16.toPx() }
    suspend fun showAction() {
        val size = snapshotFlow { actionSize }.first { it != IntSize.Zero }
        action.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + clearance))
    }
    // Again at each step of the keyboard rising, so the last one is measured against where it stops.
    LaunchedEffect(passwordFocused, keyboard) { if (passwordFocused) showAction() }
    LaunchedEffect(form.addressError, form.nameError, form.passwordError) {
        if (form.addressError != null || form.nameError != null || form.passwordError != null) showAction()
    }
    StandalonePage(modifier) {
        AppMark()
        Spacer(Modifier.height(Rah.space.s16))
        Text(
            "Roborock at Home",
            style = Rah.type.pageTitle,
            color = c.text,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Rah.space.s24))
        LabelledField(
            value = form.address,
            onValueChange = {
                form.address = it
                form.addressError = null
            },
            label = "Server address",
            supporting = "For example, 192.168.1.50:8770",
            error = form.addressError,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(Rah.space.s16))
        LabelledField(
            value = form.name,
            onValueChange = {
                form.name = it
                form.nameError = null
            },
            label = "Email or name",
            error = form.nameError,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(Rah.space.s16))
        Column(Modifier.bringIntoViewRequester(action).onSizeChanged { actionSize = it }) {
            PasswordField(
                value = form.password,
                onValueChange = {
                    form.password = it
                    form.passwordError = null
                },
                label = "Password",
                modifier = Modifier.onFocusChanged { passwordFocused = it.isFocused },
                error = form.passwordError,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { onSignIn() }),
            )
            Spacer(Modifier.height(Rah.space.s24))
            PrimaryButton("Sign in", onSignIn, Modifier.fillMaxWidth(), busy = busy)
        }
    }
}

/** The web page's logo: the vacuum on a rounded square of the accent, drawn in the theme's colours. */
@Composable
private fun AppMark() {
    val c = Rah.colors
    Canvas(Modifier.size(44.dp)) {
        val unit = size.width / 64f
        drawRoundRect(c.accent, cornerRadius = CornerRadius(15 * unit))
        drawCircle(c.page, radius = 20 * unit, center = Offset(32 * unit, 32 * unit))
        drawCircle(c.accent, radius = 6.5f * unit, center = Offset(32 * unit, 25 * unit))
        drawLine(c.accent, Offset(23.5f * unit, 42.5f * unit), Offset(40.5f * unit, 42.5f * unit), 3.5f * unit, StrokeCap.Round)
    }
}
