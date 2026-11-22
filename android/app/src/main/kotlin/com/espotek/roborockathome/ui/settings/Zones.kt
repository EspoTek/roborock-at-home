package com.espotek.roborockathome.ui.settings

import android.icu.util.TimeZone

/**
 * The time zones Android knows, one per place ("Australia/Melbourne"), as the page's list of the
 * browser's zones is: no aliases such as "US/Pacific" and no "Etc/GMT+5".
 */
internal fun platformZones(): List<String> =
    TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null).sorted()

/**
 * The [zones] that match [query], in their order. Case does not matter, and spaces, slashes and
 * underscores are all the same, so "new york" finds America/New_York and "australia melb" finds
 * Australia/Melbourne.
 */
internal fun filterZones(zones: List<String>, query: String): List<String> {
    val wanted = searchable(query).trim()
    return zones.filter { wanted in searchable(it) }
}

private fun searchable(text: String): String = text.lowercase().replace(Regex("[\\s/_]+"), " ")
