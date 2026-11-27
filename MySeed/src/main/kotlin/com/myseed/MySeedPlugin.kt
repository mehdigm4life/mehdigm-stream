package com.myseed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MySeedPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MySeed())
    }
}
