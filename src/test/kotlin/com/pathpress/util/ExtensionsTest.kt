package com.pathpress.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionsTest {

    @Test
    fun `toDoubleSafe converts Number to Double and null to 0_0`() {
        val intNum: Number? = 42
        val doubleNum: Number? = 3.14
        val nullNum: Number? = null

        assertEquals(42.0, intNum.toDoubleSafe())
        assertEquals(3.14, doubleNum.toDoubleSafe())
        assertEquals(0.0, nullNum.toDoubleSafe())
    }

    @Test
    fun `orEmptyList returns list if present and emptyList if null`() {
        val list: List<String>? = listOf("apple", "banana")
        val nullList: List<String>? = null

        assertEquals(listOf("apple", "banana"), list.orEmptyList())
        assertEquals(emptyList(), nullList.orEmptyList())
    }

    @Test
    fun `getOrDefault retrieves existing value or computes default`() {
        val map = mapOf("key1" to "val1", "key2" to "val2")

        assertEquals("val1", map.getOrDefault("key1") { "default" })
        assertEquals("default", map.getOrDefault("missingKey") { "default" })
    }

    @Test
    fun `isNotBlankSafe checks string safely for null and blank`() {
        val validStr: String? = "  text  "
        val emptyStr: String? = ""
        val blankStr: String? = "   "
        val nullStr: String? = null

        assertTrue(validStr.isNotBlankSafe())
        assertFalse(emptyStr.isNotBlankSafe())
        assertFalse(blankStr.isNotBlankSafe())
        assertFalse(nullStr.isNotBlankSafe())
    }
}
