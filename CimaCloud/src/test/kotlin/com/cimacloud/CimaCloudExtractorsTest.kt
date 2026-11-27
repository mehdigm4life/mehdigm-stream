package com.cimacloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CimaCloudExtractorsTest {
    @Test
    fun vidTubeUsesCapturedParentHeadersForPageAndHostHeadersForPlayback() {
        val context = requireNotNull(
            CimaCloudExtractors.serverRequestContext("https://down.vidtube.one/example.html"),
        )

        assertEquals("https://topcinemaa.co/", context.pageReferer)
        assertEquals("https://topcinemaa.co", context.pageOrigin)
        assertEquals("https://down.vidtube.one/", context.mediaReferer)
        assertEquals("https://down.vidtube.one", context.mediaOrigin)
        assertTrue(context.userAgent.contains("Chrome/120.0.0.0"))
    }

    @Test
    fun capturedSelfRefererHostsDoNotInheritCimaCloudOrRedirectOrigins() {
        val expected = mapOf(
            "https://stmruby.com/embed-example.html" to "https://stmruby.com/",
            "https://morencius.com/v/example" to "https://morencius.com/",
            "https://lplaygo.com/e/example" to "https://lplaygo.com/",
        )

        for ((url, referer) in expected) {
            val context = requireNotNull(CimaCloudExtractors.serverRequestContext(url)) {
                "Missing host-specific context for $url"
            }
            assertEquals(referer, context.pageReferer)
            assertEquals(referer, context.mediaReferer)
            assertNull(context.pageOrigin)
            assertNull(context.mediaOrigin)
        }
    }

    @Test
    fun lplaygoIsDispatchedToTheHtmlExtractor() {
        assertTrue(CimaCloudExtractors.isPackerOrDirectHtmlHost("lplaygo.com"))
        assertEquals("LPlayGo", CimaCloudExtractors.prettyProviderName("lplaygo.com"))
        assertFalse(CimaCloudExtractors.isPackerOrDirectHtmlHost("unrelated.example"))
    }

    @Test
    fun packedJavascriptCanExposeAnHlsSource() {
        val packed = """<script>eval(function(p,a,c,k,e,d){return p;}('0 1 2',36,3,'file|https://cdn.example/master.m3u8|sources'.split('|')))</script>"""
        val unpacked = CimaCloudExtractors.unpackAllPacker(packed).joinToString("\n")

        assertTrue(unpacked.contains("https://cdn.example/master.m3u8"))
    }
}
