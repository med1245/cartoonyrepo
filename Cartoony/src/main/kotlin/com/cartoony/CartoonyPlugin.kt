package com.cartoony

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CartoonyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cartoony())
    }
}

