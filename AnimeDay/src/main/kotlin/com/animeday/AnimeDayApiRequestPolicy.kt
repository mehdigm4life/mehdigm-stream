package com.animeday

import java.net.URI

/**
 * Auth headers observed in the official AnimeDay 3.8 HAR.
 * Public catalog/search/episode-list calls need no identity headers; detail calls use only
 * `firebase_id`; server-list calls use both `firebase_id` and `cloudflare-id`.
 */
internal data class AnimeDayApiAuthPolicy(
    val useFirebaseId: Boolean = false,
    val useCloudflareId: Boolean = false,
)

internal object AnimeDayApiRequestPolicy {
    private val none = AnimeDayApiAuthPolicy()
    private val firebaseOnly = AnimeDayApiAuthPolicy(useFirebaseId = true)
    private val serverList = AnimeDayApiAuthPolicy(useFirebaseId = true, useCloudflareId = true)
    private val apiPrefix = Regex("/v\\d+(?:\\.\\d+)?/api/", RegexOption.IGNORE_CASE)

    fun forRequest(method: String, pathOrUrl: String): AnimeDayApiAuthPolicy {
        if (!method.equals("GET", ignoreCase = true)) return none

        val route = routeOf(pathOrUrl)
        val parts = route.split('/').filter { it.isNotBlank() }
        if (parts.size == 3 && parts[0] in setOf("movie", "episode") &&
            parts[1].toLongOrNull() != null && parts[2] == "servers") {
            return serverList
        }

        if (parts.size == 2 && parts[0] in setOf("movie", "series", "episode") &&
            parts[1].toLongOrNull() != null) {
            return firebaseOnly
        }

        return none
    }

    internal fun routeOf(pathOrUrl: String): String {
        val withoutQuery = pathOrUrl.substringBefore('?').substringBefore('#').trim()
        val path = try {
            URI(withoutQuery).rawPath?.takeIf { it.isNotBlank() } ?: withoutQuery
        } catch (_: Throwable) {
            withoutQuery
        }
        return apiPrefix.replace(path, "").trim('/')
    }
}
