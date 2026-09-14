package com.animezid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimezidPlugin : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(AnimezidStreamRubyExtractor())
        registerExtractorAPI(AnimezidStreamRubyComExtractor())

        registerExtractorAPI(AnimezidTurboViPlayExtractor())
        registerExtractorAPI(AnimezidTurboViPlayComExtractor())

        registerExtractorAPI(AnimezidRpmHostExtractor())
        registerExtractorAPI(AnimezidUpnShareExtractor())
        registerExtractorAPI(AnimezidStreamP2PExtractor())
        registerExtractorAPI(AnimezidEasyVidPlayExtractor())

        registerExtractorAPI(AnimezidUqloadExtractor())
        registerExtractorAPI(AnimezidUqloadToExtractor())

        registerExtractorAPI(AnimezidMegaMaxExtractor())
        registerExtractorAPI(AnimezidVidTubeExtractor())

        registerMainAPI(Animezid())
    }
}