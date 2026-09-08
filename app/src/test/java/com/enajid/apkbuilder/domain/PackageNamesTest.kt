package com.enajid.apkbuilder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {

    @Test
    fun `accepts valid reverse-domain identifiers`() {
        assertTrue(PackageNames.isValid("com.example.app"))
        assertTrue(PackageNames.isValid("a.b"))
        assertTrue(PackageNames.isValid("com.example.my_app2"))
        assertTrue(PackageNames.isValid("org.example.upperCase"))
    }

    @Test
    fun `rejects invalid identifiers`() {
        assertFalse(PackageNames.isValid("com"))
        assertFalse(PackageNames.isValid("com.example.2app"))
        assertFalse(PackageNames.isValid("com.example.my-app"))
        assertFalse(PackageNames.isValid("com.class"))
        assertFalse(PackageNames.isValid("com..app"))
        assertFalse(PackageNames.isValid(".com.app"))
        assertFalse(PackageNames.isValid("com."))
        assertFalse(PackageNames.isValid(""))
    }

    @Test
    fun `validation errors are helpful`() {
        assertNull(PackageNames.validationError("com.example.app"))
        assertEquals("Add at least two parts, like com.myapp", PackageNames.validationError("myapp"))
        assertEquals(
            "Each part must start with a letter",
            PackageNames.validationError("com.2fast")
        )
    }

    @Test
    fun `suggestions come from the app name`() {
        assertEquals("com.calculator", PackageNames.suggestFromAppName("Calculator"))
        assertEquals("com.mycalculator2", PackageNames.suggestFromAppName("My Calculator 2"))
        assertEquals("com.app2fa", PackageNames.suggestFromAppName("2FA"))
        assertEquals("com.myapp", PackageNames.suggestFromAppName(""))
        assertEquals("com.appclass", PackageNames.suggestFromAppName("Class"))
    }
}
