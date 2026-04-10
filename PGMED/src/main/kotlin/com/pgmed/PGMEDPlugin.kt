package com.pgmed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PGMEDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PGMEDProvider())
    }
}
