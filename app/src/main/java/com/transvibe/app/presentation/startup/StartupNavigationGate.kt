package com.transvibe.app.presentation.startup

/**
 * Coordinates startup completion without allowing either prerequisite to bypass the other.
 * The gate is idempotent: once navigation has been dispatched, duplicate events are ignored.
 */
class StartupNavigationGate {
    private var splashCompleted = false
    private var resourceInspectionCompleted = false
    private var navigationDispatched = false

    @Synchronized
    fun onSplashCompleted(): Boolean {
        splashCompleted = true
        return consumeNavigationIfReady()
    }

    @Synchronized
    fun onResourceInspectionCompleted(): Boolean {
        resourceInspectionCompleted = true
        return consumeNavigationIfReady()
    }

    @Synchronized
    private fun consumeNavigationIfReady(): Boolean {
        if (navigationDispatched || !splashCompleted || !resourceInspectionCompleted) return false
        navigationDispatched = true
        return true
    }
}
