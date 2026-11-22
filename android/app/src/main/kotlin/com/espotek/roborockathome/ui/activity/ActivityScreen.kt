package com.espotek.roborockathome.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HistoryKind
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.EmptyState
import com.espotek.roborockathome.ui.common.LoadingLine
import com.espotek.roborockathome.ui.common.ScreenFrame
import com.espotek.roborockathome.ui.common.SectionHeading
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.TopBar
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.theme.Glyph
import com.espotek.roborockathome.ui.theme.GlyphIcon
import com.espotek.roborockathome.ui.theme.Rah
import com.espotek.roborockathome.ui.theme.tabular

/**
 * Activity: what every robot has done, newest first, in the page's day groups. Asks [store] for the
 * history the first time it is shown; the store keeps it current from then on. Times are the phone's.
 */
@Composable
fun ActivityScreen(home: Home, store: HomeStore) {
    val asked = home.history != null
    LaunchedEffect(Unit) { if (!asked) store.loadHistory() }
    ScreenFrame(topBar = { TopBar("Activity") }) {
        val history = home.history
        when {
            history == null -> LoadingLine(
                "Loading activity",
                Modifier.fillMaxWidth().padding(horizontal = Rah.space.gutter, vertical = Rah.space.s24),
            )
            history.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Rah.space.gutter)) {
                EmptyState(Glyph.Activity, "Nothing yet", "Cleans, docking and problems show up here as they happen.")
            }
            else -> Timeline(history, home.robots)
        }
    }
}

@Composable
private fun Timeline(history: List<HistoryEntry>, robots: List<Robot>) {
    val words = rememberTimeWords()
    val groups = remember(history, words.zone) { groupByDay(history, words.zone) }
    // As on the page, an entry names its robot only when there are several to tell apart. An entry of
    // a robot gone from the account names none.
    val names = if (robots.size > 1) robots.associate { it.id to it.name } else emptyMap()
    val timeWidth = timeColumnWidth(words)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // At large text a column of times would leave the sentences a narrow strip, so the time goes
        // above each sentence instead.
        val timeColumn = timeWidth.takeIf { it * 4 <= maxWidth - Rah.space.gutter * 2 }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Rah.space.gutter, end = Rah.space.gutter, bottom = Rah.space.s24)) {
            groups.forEachIndexed { i, group ->
                item(key = group.date.toString()) {
                    SectionHeading(
                        words.dayHeading(group.date),
                        Modifier.padding(top = if (i == 0) Rah.space.s8 else Rah.space.s24, bottom = Rah.space.s4),
                    )
                }
                items(group.entries) { entry -> Event(entry, names[entry.robot], words.time(entry.ts), timeColumn) }
            }
        }
    }
}

/**
 * The time, a mark for the kind, the server's sentence, and the robot under it; TalkBack reads it as
 * one. The time sits in a column [timeColumn] wide, or above the sentence when that is null.
 */
@Composable
private fun Event(entry: HistoryEntry, robot: String?, time: String, timeColumn: Dp?) {
    val c = Rah.colors
    val timeStyle = Rah.type.supporting.tabular()
    // The mark is centred on the first line, whatever the text size: the sentence's, or the time above it.
    val line = with(LocalDensity.current) { (if (timeColumn == null) timeStyle else Rah.type.body).lineHeight.toDp() }
    val markBox = maxOf(line, MARK)
    val lead = Modifier.padding(top = (markBox - line) / 2)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = Rah.space.s8),
        horizontalArrangement = Arrangement.spacedBy(Rah.space.s12),
    ) {
        if (timeColumn != null) {
            Text(
                time,
                style = timeStyle,
                color = c.textSupporting,
                textAlign = TextAlign.End,
                modifier = lead.width(timeColumn).alignByBaseline(),
            )
        }
        Mark(entry.kind, markBox)
        Column(if (timeColumn != null) lead.weight(1f).alignByBaseline() else lead.weight(1f)) {
            if (timeColumn == null) Text(time, style = timeStyle, color = c.textSupporting)
            Text(entry.text, style = Rah.type.body, color = c.text)
            if (robot != null) Text(robot, style = Rah.type.supporting, color = c.textSupporting)
        }
    }
}

/** The kind's mark, a tinted circle, centred in a box [height] tall. */
@Composable
private fun Mark(kind: HistoryKind, height: Dp) {
    val c = Rah.colors
    val (glyph, tone) = mark(kind)
    val (ink, back) = when (tone) {
        Tone.Good -> c.accent to c.accentSurface
        Tone.Trouble -> c.attention to c.attentionSurface
        Tone.Plain -> c.textSupporting to c.surfaceSecondary
    }
    Box(Modifier.size(MARK, height), contentAlignment = Alignment.Center) {
        Box(Modifier.size(MARK).background(back, CircleShape), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, contentDescription = null, tint = ink, size = 16.dp)
        }
    }
}

private val MARK = 28.dp

private enum class Tone { Good, Trouble, Plain }

/** The page's marks: what went to plan in the accent, trouble in the attention colour, the rest plain. */
private fun mark(kind: HistoryKind): Pair<Glyph, Tone> = when (kind) {
    HistoryKind.Started -> Glyph.Play to Tone.Good
    HistoryKind.Finished -> Glyph.Check to Tone.Good
    HistoryKind.Docked -> Glyph.Dock to Tone.Plain
    HistoryKind.Error -> Glyph.Alert to Tone.Trouble
    HistoryKind.Cleared -> Glyph.Check to Tone.Plain
    HistoryKind.Offline -> Glyph.Offline to Tone.Trouble
    HistoryKind.Online -> Glyph.Wifi to Tone.Plain
    HistoryKind.Schedule -> Glyph.Calendar to Tone.Good
    HistoryKind.Command -> Glyph.Robot to Tone.Plain
}

/** Wide enough for the widest time of day in the phone's format, so the times line up at their ends. */
@Composable
private fun timeColumnWidth(words: TimeWords): Dp {
    val measurer = rememberTextMeasurer()
    val style = Rah.type.supporting.tabular()
    val density = LocalDensity.current
    return remember(words, style, density) {
        val widest = listOf("10:00", "22:00").maxOf { measurer.measure(words.time(it), style).size.width }
        with(density) { widest.toDp() }
    }
}
