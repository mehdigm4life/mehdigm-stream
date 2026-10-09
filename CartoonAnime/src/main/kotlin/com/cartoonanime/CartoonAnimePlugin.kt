package com.cartoonanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CartoonAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CartoonAnime())
    }
}
