package com.espotek.roborockathome.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM tests of what each form checks before it sends anything.

class ValidationTest {
    @Test
    fun email() {
        assertEquals(emptyMap<Field, String>(), emailProblems(" you@example.com "))
        assertEquals(setOf(Field.Email), emailProblems("").keys)
        assertEquals(setOf(Field.Email), emailProblems("you.example.com").keys)
    }

    @Test
    fun code() {
        assertEquals(emptyMap<Field, String>(), codeProblems("123456"))
        assertEquals(mapOf(Field.Code to "Enter the code Roborock emailed you."), codeProblems("  "))
    }

    @Test
    fun tunnelToken() {
        assertEquals(emptyMap<Field, String>(), tunnelTokenProblems("eyJhIjoiMWUyIn0"))
        assertEquals(setOf(Field.TunnelToken), tunnelTokenProblems("").keys)
    }

    @Test
    fun personNeedsANameAndEightCharacters() {
        assertEquals(emptyMap<Field, String>(), personProblems("anna@example.com", "12345678"))
        assertEquals(
            mapOf(
                Field.PersonName to "Enter their email address or name.",
                Field.PersonPassword to "The password needs at least 8 characters.",
            ),
            personProblems("   ", "1234567"),
        )
        // The name is sent trimmed, so its length is the trimmed length.
        assertEquals(emptyMap<Field, String>(), personProblems(" " + "a".repeat(100) + " ", "12345678"))
        assertEquals(
            mapOf(Field.PersonName to "A name can be at most 100 characters."),
            personProblems("a".repeat(101), "12345678"),
        )
    }

    @Test
    fun tokenNeedsAName() {
        assertEquals(emptyMap<Field, String>(), tokenProblems("Home Assistant"))
        assertEquals(mapOf(Field.TokenName to "Give the token a name."), tokenProblems(""))
        assertEquals(setOf(Field.TokenName), tokenProblems("a".repeat(101)).keys)
    }

    @Test
    fun passwordChange() {
        assertEquals(emptyMap<Field, String>(), passwordProblems("old password", "new password"))
        assertEquals(
            mapOf(
                Field.CurrentPassword to "Enter your current password.",
                Field.NewPassword to "The new password needs at least 8 characters.",
            ),
            passwordProblems("", "short"),
        )
        // A password is sent as typed, spaces and all.
        assertEquals(emptyMap<Field, String>(), passwordProblems(" ", "        "))
    }
}

class ZonesTest {
    private val zones = listOf(
        "America/Argentina/Buenos_Aires",
        "America/New_York",
        "Australia/Melbourne",
        "Australia/Sydney",
        "Europe/London",
        "Pacific/Auckland",
    )

    @Test
    fun anEmptySearchKeepsEveryZoneInOrder() {
        assertEquals(zones, filterZones(zones, ""))
        assertEquals(zones, filterZones(zones, "   "))
    }

    @Test
    fun caseSpacesSlashesAndUnderscoresAreAlike() {
        assertEquals(listOf("America/New_York"), filterZones(zones, "new york"))
        assertEquals(listOf("America/New_York"), filterZones(zones, "NEW_YORK"))
        assertEquals(listOf("Australia/Melbourne"), filterZones(zones, "australia melb"))
        assertEquals(listOf("Australia/Melbourne"), filterZones(zones, "Australia/Melbourne"))
        assertEquals(listOf("America/Argentina/Buenos_Aires"), filterZones(zones, " buenos  aires "))
    }

    @Test
    fun aPartOfAnyWordMatches() {
        assertEquals(listOf("Australia/Melbourne", "Australia/Sydney"), filterZones(zones, "austral"))
        assertEquals(listOf("Pacific/Auckland"), filterZones(zones, "auck"))
        assertEquals(emptyList<String>(), filterZones(zones, "Atlantis"))
    }
}
