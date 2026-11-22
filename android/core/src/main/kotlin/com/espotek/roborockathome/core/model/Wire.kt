package com.espotek.roborockathome.core.model

import kotlinx.serialization.json.Json

/**
 * How the server's JSON is read and written. A field a newer server adds is ignored; a documented
 * field that is missing, or null where the API never sends null, fails to parse.
 */
internal val WireJson = Json { ignoreUnknownKeys = true }
