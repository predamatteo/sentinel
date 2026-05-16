package com.sentinel.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [AccessibilityHelper.isServiceEnabled]. The
 * helper parses `ENABLED_ACCESSIBILITY_SERVICES`, which Robolectric
 * lets us write programmatically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessibilityHelperTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun emptySecureSettingMeansDisabled() {
        Settings.Secure.putString(
            context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "",
        )
        assertFalse(AccessibilityHelper.isServiceEnabled(context()))
    }

    @Test
    fun matchingComponentIsDetectedAsEnabled() {
        val cn = ComponentName(context(), SentinelAccessibilityService::class.java)
        Settings.Secure.putString(
            context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            cn.flattenToString(),
        )
        assertTrue(AccessibilityHelper.isServiceEnabled(context()))
    }

    @Test
    fun unrelatedComponentDoesNotCount() {
        Settings.Secure.putString(
            context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.example.bogus/com.example.bogus.SomeService",
        )
        assertFalse(AccessibilityHelper.isServiceEnabled(context()))
    }

    @Test
    fun matchingComponentSurvivesCoExistingEntries() {
        val cn = ComponentName(context(), SentinelAccessibilityService::class.java)
        val combined = listOf(
            "com.example.bogus/com.example.bogus.SomeService",
            cn.flattenToString(),
            "com.another/com.another.AS",
        ).joinToString(":")
        Settings.Secure.putString(
            context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            combined,
        )
        assertTrue(AccessibilityHelper.isServiceEnabled(context()))
    }
}
