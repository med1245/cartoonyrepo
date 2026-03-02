package com.conanaraby

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ConanArabyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ConanAraby())
    }
}
