package com.mixcoara.app

import org.json.JSONObject

internal fun parseProgressErrorValue(progress: JSONObject): String? {
    if (!progress.has("error") || progress.isNull("error")) return null
    val raw = progress.opt("error")
    if (raw == null || raw == JSONObject.NULL) return null
    val text = raw.toString().trim()
    return text.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
}
