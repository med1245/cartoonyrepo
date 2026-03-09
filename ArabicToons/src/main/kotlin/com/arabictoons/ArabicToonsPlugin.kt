package com.arabictoons

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabicToonsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabicToons())
    }
}
