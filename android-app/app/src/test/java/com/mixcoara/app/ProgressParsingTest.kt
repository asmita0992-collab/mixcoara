package com.mixcoara.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressParsingTest {
    @Test
    fun nullJsonValueIsIgnored() {
        val progress = JSONObject().put("error", JSONObject.NULL)
        assertNull(parseProgressErrorValue(progress))
    }

    @Test
    fun literalNullStringIsIgnored() {
        val progress = JSONObject().put("error", "null")
        assertNull(parseProgressErrorValue(progress))
    }

    @Test
    fun nonEmptyErrorIsReturned() {
        val progress = JSONObject().put("error", "falló lectura")
        assertEquals("falló lectura", parseProgressErrorValue(progress))
    }
}
