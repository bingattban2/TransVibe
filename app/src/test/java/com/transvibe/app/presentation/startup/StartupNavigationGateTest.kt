package com.transvibe.app.presentation.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupNavigationGateTest {

    @Test
    fun resourceCheckFinishesBeforeSplash_navigatesOnlyAfterSplash() {
        val gate = StartupNavigationGate()

        assertFalse(gate.onResourceInspectionCompleted())
        assertTrue(gate.onSplashCompleted())
    }

    @Test
    fun splashFinishesBeforeResourceCheck_navigatesOnlyAfterResourceCheck() {
        val gate = StartupNavigationGate()

        assertFalse(gate.onSplashCompleted())
        assertTrue(gate.onResourceInspectionCompleted())
    }

    @Test
    fun resourceCheckFailure_isStillACompletedInspectionForNavigation() {
        val gate = StartupNavigationGate()

        assertFalse(gate.onSplashCompleted())
        assertTrue(gate.onResourceInspectionCompleted())
    }

    @Test
    fun recomposition_preservesGateAndDoesNotRedispatchNavigation() {
        val gateRememberedAcrossRecomposition = StartupNavigationGate()

        assertFalse(gateRememberedAcrossRecomposition.onResourceInspectionCompleted())
        assertTrue(gateRememberedAcrossRecomposition.onSplashCompleted())
        assertFalse(gateRememberedAcrossRecomposition.onResourceInspectionCompleted())
        assertFalse(gateRememberedAcrossRecomposition.onSplashCompleted())
    }

    @Test
    fun duplicateStateEmissions_dispatchNavigationExactlyOnce() {
        val gate = StartupNavigationGate()

        assertFalse(gate.onResourceInspectionCompleted())
        assertFalse(gate.onResourceInspectionCompleted())
        assertTrue(gate.onSplashCompleted())
        assertFalse(gate.onSplashCompleted())
        assertFalse(gate.onResourceInspectionCompleted())
    }
}
