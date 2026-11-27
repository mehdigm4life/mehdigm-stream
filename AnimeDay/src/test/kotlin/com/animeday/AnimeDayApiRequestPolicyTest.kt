package com.animeday

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeDayApiRequestPolicyTest {
    @Test
    fun publicCatalogAndSearchRoutesHaveNoAuthHeaders() {
        val none = AnimeDayApiAuthPolicy()
        for (route in listOf("home", "allcategories", "series?page=1", "movies?page=1", "search")) {
            assertEquals(route, none, AnimeDayApiRequestPolicy.forRequest("GET", route))
            assertEquals(route, none, AnimeDayApiRequestPolicy.forRequest("POST", route))
        }
    }

    @Test
    fun episodeListRouteIsPublicLikeOfficialHar() {
        assertEquals(
            AnimeDayApiAuthPolicy(),
            AnimeDayApiRequestPolicy.forRequest("GET", "series/12939/episodes"),
        )
    }

    @Test
    fun detailRoutesUseOnlyFirebaseId() {
        val expected = AnimeDayApiAuthPolicy(useFirebaseId = true)
        for (route in listOf("movie/27717", "series/12939", "episode/382757")) {
            assertEquals(route, expected, AnimeDayApiRequestPolicy.forRequest("GET", route))
        }
    }

    @Test
    fun serverRoutesUseFirebaseAndCloudflareIds() {
        val expected = AnimeDayApiAuthPolicy(useFirebaseId = true, useCloudflareId = true)
        assertEquals(expected, AnimeDayApiRequestPolicy.forRequest("GET", "episode/382757/servers"))
        assertEquals(expected, AnimeDayApiRequestPolicy.forRequest("GET", "movie/27717/servers"))
    }

    @Test
    fun unrelatedRoutesAndMalformedIdentifiersStayPublic() {
        val none = AnimeDayApiAuthPolicy()
        assertEquals(none, AnimeDayApiRequestPolicy.forRequest("GET", "movie/not-a-number"))
        assertEquals(none, AnimeDayApiRequestPolicy.forRequest("GET", "movie/27717/servers/extra"))
        assertEquals(
            none,
            AnimeDayApiRequestPolicy.forRequest("POST", "episode/382757/servers"),
        )
    }

    @Test
    fun absoluteApiUrlsAndQueryStringsResolveToTheSamePolicy() {
        val expected = AnimeDayApiAuthPolicy(useFirebaseId = true)
        assertEquals(
            expected,
            AnimeDayApiRequestPolicy.forRequest(
                "GET",
                "https://20042026.site/v3.8/api/series/12939?include=episodes",
            ),
        )
        assertEquals(
            AnimeDayApiAuthPolicy(useFirebaseId = true, useCloudflareId = true),
            AnimeDayApiRequestPolicy.forRequest(
                "GET",
                "https://20042026.site/v3.8/api/movie/27717/servers?quality=all",
            ),
        )
    }
}
