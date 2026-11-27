package com.cimacloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CimaCloudLinksDataTest {
    @Test
    fun episodeServerDataResolvesToEpisodeEndpoint() {
        val expected = "episode/94011/servers"
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("https://www.cima-cloud.com/episode/94011/servers"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/episode/94011/servers"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("episode/94011/servers"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/episode/94011"))
    }

    @Test
    fun movieServerDataResolvesToMovieEndpoint() {
        val expected = "movie/12981/servers"
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("https://www.cima-cloud.com/movie/12981/servers"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/movie/12981"))
    }

    @Test
    fun queryFragmentAndTrailingSlashDoNotChangeTheEndpoint() {
        val expected = "episode/94011/servers"
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/episode/94011/servers?autoplay=1"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/episode/94011/servers#player"))
        assertEquals(expected, CimaCloudLinksData.resolveServersApiPath("/episode/94011/servers/"))
    }

    @Test
    fun seriesPageIsNotMistakenForAnEpisodeOrMovieServerEndpoint() {
        assertNull(CimaCloudLinksData.resolveServersApiPath("https://www.cima-cloud.com/series/12981"))
        assertNull(CimaCloudLinksData.resolveServersApiPath("/serie/12981/servers"))
        assertNull(CimaCloudLinksData.resolveServersApiPath("https://www.cima-cloud.com/search?next=/episode/94011"))
    }

    @Test
    fun pathMustEndAfterIdOrServersSuffix() {
        assertNull(CimaCloudLinksData.resolveServersApiPath("/episode/94011/servers-extra"))
        assertNull(CimaCloudLinksData.resolveServersApiPath("/movie/12981/servers/other"))
    }
}
