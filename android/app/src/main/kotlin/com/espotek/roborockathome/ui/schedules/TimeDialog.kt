package com.espotek.roborockathome.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.espotek.roborockathome.ui.common.DialogFrame
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.SecondaryButton
import com.espotek.roborockathome.ui.theme.Rah
import java.time.LocalTime

/**
 * Android's Material time picker for a schedule's [time] (`HH:MM`), in 12 or 24 hours as the phone
 * is set, with the server's [zone] under the title since that is the clock the time is read on.
 * Picking "Set time" gives the new `HH:MM` to [onPick]; Cancel, Back and a tap outside call [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TimeDialog(time: String, zone: String, use24Hour: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val start = LocalTime.parse(time)
    val state = rememberTimePickerState(start.hour, start.minute, use24Hour)
    // 16 dp from the screen's edges rather than the kit's 24: the picker needs 280 dp.
    DialogFrame(onDismiss, edge = Rah.space.s16) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Rah.space.s24),
            verticalArrangement = Arrangement.spacedBy(Rah.space.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("Time", style = Rah.type.status, color = Rah.colors.text, modifier = Modifier.semantics { heading() })
                Text("In $zone", style = Rah.type.supporting, color = Rah.colors.textSupporting)
            }
            TimePicker(state)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Rah.space.s8, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Rah.space.s8),
            ) {
                SecondaryButton("Cancel", onDismiss)
                // ISO's HH:MM whatever the phone's language, as the server wants it.
                PrimaryButton("Set time", onClick = { onPick(LocalTime.of(state.hour, state.minute).toString()) })
            }
        }
    }
}
