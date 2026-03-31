package com.drivem3u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DriveM3UPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DriveM3UProvider())
    }
}
