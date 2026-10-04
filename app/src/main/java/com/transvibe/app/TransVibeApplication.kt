package com.transvibe.app

import android.app.Application
import com.transvibe.app.presentation.navigation.TransVibeViewModelFactory
import com.transvibe.app.service.SubtitleProcessingService

class TransVibeApplication : Application() {
    private lateinit var graph: AppGraph

    val viewModelFactory: TransVibeViewModelFactory
        get() = graph.viewModelFactory

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    internal fun inject(service: SubtitleProcessingService) {
        graph.inject(service)
    }
}
