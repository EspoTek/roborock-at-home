package com.espotek.roborockathome.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.input.ImeAction
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.ui.common.LabelledField
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.PrimaryButton
import com.espotek.roborockathome.ui.common.RadioRow
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.theme.Rah

/** What is typed into the search and the zone picked, which Save sends. */
@Stable
internal class ZoneDraft(query: String, chosen: String?) {
    var query by mutableStateOf(query)
    var chosen by mutableStateOf(chosen)

    companion object {
        val Saver = listSaver<ZoneDraft, String?>(
            save = { listOf(it.query, it.chosen) },
            restore = { ZoneDraft(checkNotNull(it[0]), it[1]) },
        )
    }
}

/** Time zone: the zone schedules run in, and every zone Android knows to pick from, with a search. */
@Composable
internal fun TimeZonePage(
    app: AppInfo,
    api: RoborockApi,
    zones: List<String>,
    onBack: () -> Unit,
    draft: ZoneDraft = rememberSaveable(saver = ZoneDraft.Saver) { ZoneDraft("", app.tz) },
) {
    val runner = rememberRunner()
    val messages = LocalMessages.current
    val chosen = draft.chosen
    val save = {
        checkNotNull(chosen) { "Save is enabled once a zone is picked" }
        runner.launch {
            api.setTimeZone(chosen)
            messages.success("Time zone set to $chosen")
        }
    }
    ScreenFrame(
        topBar = {
            TopBar(SettingsPage.TimeZone.title, onBack = onBack) {
                PrimaryButton("Save", onClick = save, enabled = chosen != null && chosen != app.tz, busy = runner.busy)
            }
        },
    ) {
        val shown = remember(zones, draft.query) { filterZones(zones, draft.query) }
        LazyColumn(Modifier.fillMaxSize().selectableGroup()) {
            item {
                Column(
                    Modifier.gutter().padding(top = Rah.space.s8, bottom = Rah.space.s16),
                    verticalArrangement = Arrangement.spacedBy(Rah.space.s16),
                ) {
                    Column {
                        Text("Schedules run in this time zone.", style = Rah.type.supporting, color = Rah.colors.textSupporting)
                        Text(app.tz ?: "Not set", style = Rah.type.sectionHeading, color = Rah.colors.text)
                    }
                    LabelledField(
                        draft.query,
                        onValueChange = { draft.query = it },
                        label = "Find a time zone",
                        supporting = "For example, Melbourne or New York",
                        // Done puts the keyboard away, uncovering the list.
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    )
                }
            }
            items(shown, key = { it }) { zone ->
                RadioRow(
                    zone,
                    selected = zone == chosen,
                    onClick = { draft.chosen = zone },
                    summary = if (zone == app.tz) "Current time zone" else null,
                )
            }
            if (shown.isEmpty()) {
                item { Note("No time zone matches “${draft.query.trim()}”.") }
            }
        }
    }
}
