package com.espotek.roborockathome.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Where every screen reads now and the time zone from: the device's clock, or in a test one held
 * still, so that "Yesterday" or "3 min ago" come out the same every day.
 */
val LocalClock = staticCompositionLocalOf<Clock> { DeviceClock }

// The system's time in the zone the device is set to when it is read, not when the app started, so
// a change of zone shows from the next minute on.
private object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}

/**
 * [TimeWords] for now on [LocalClock], in the device's language and its 12 or 24 hour choice. Now
 * moves on at each turn of the minute, so "Today", "in 5 min" and "3 min ago" stay true while a
 * screen is left open, and the clock's zone is read again each time.
 */
@Composable
fun rememberTimeWords(): TimeWords {
    val now = rememberNow()
    val zone = LocalClock.current.zone
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val formats = remember(locale, use24Hour) { TimeFormats(locale, use24Hour) }
    return remember(now, zone, formats) { TimeWords(now, zone, formats) }
}

@Composable
private fun rememberNow(): Instant {
    val clock = LocalClock.current
    val now by produceState(clock.instant(), clock) {
        while (true) {
            delay(MINUTE - clock.millis() % MINUTE)
            value = clock.instant()
        }
    }
    return now
}

private const val MINUTE = 60_000L
