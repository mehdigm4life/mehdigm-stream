package com.animhq

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimhqPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Animhq())
    }
}