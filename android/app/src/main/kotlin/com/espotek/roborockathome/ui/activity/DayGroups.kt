package com.espotek.roborockathome.ui.activity

import com.espotek.roborockathome.core.model.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The entries of one calendar day, newest first. */
internal data class DayGroup(val date: LocalDate, val entries: List<HistoryEntry>)

/**
 * The page's day groups: [history], newest first as the server keeps it, by calendar day on the
 * clock of [zone], keeping the order of the days and of the entries in each.
 */
internal fun groupByDay(history: List<HistoryEntry>, zone: ZoneId): List<DayGroup> =
    history
        .groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() }
        .map { (date, entries) -> DayGroup(date, entries) }
