package com.cimanow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaNowPlugin : Plugin() {
    override fun load(context: Context) {
        CimaNowNetwork.init(context)
        registerMainAPI(CimaNow())
        registerExtractorAPI(CimaNowUpnShareExtractor())
        registerExtractorAPI(CimaNowByseExtractor())
        registerExtractorAPI(CimaNowSaveFilesExtractor())
    }
}
