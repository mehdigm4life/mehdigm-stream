package com.cimacloud

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaCloudPlugin : Plugin() {
    override fun load(context: Context) {
        CimaCloud.appContext = context.applicationContext ?: context
        registerMainAPI(CimaCloud())
        registerExtractorAPI(GooglePhotosExtractor())
    }
}
