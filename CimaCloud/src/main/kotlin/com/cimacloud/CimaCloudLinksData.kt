package com.cimacloud

/** Helpers for mapping CloudStream's episode/movie payload to the CimaCloud servers API. */
internal object CimaCloudLinksData {
    fun resolveServersApiPath(data: String): String? {
        val path = data.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val match = Regex(
            """(?:^|/)(movie|episode)/(\d+)(?:/servers)?$""",
            RegexOption.IGNORE_CASE,
        ).find(path) ?: return null

        return "${match.groupValues[1].lowercase()}/${match.groupValues[2]}/servers"
    }
}
