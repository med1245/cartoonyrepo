package com.shahid4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Shahid4uPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Shahid4uProvider())
    }
}
