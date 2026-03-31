package com.cleanwatch

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CleanWatchPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CleanWatchProvider())
    }
}
