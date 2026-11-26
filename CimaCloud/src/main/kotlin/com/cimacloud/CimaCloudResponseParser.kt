package com.cimacloud

import org.json.JSONArray
import org.json.JSONObject

/** Normalizes CimaCloud API envelopes and the varied server-list schemas returned by the API. */
internal object CimaCloudResponseParser {
    private const val MAX_UNWRAP_DEPTH = 6
    private const val MAX_VISITED_NODES = 512

    private val linkKeys = setOf(
        "link",
        "url",
        "embed",
        "embed_link",
        "embedLink",
        "embed_url",
        "embedUrl",
        "iframe",
        "iframe_link",
        "iframeLink",
        "iframe_url",
        "iframeUrl",
        "server_url",
        "serverUrl",
        "source",
        "src",
        "file",
        "play_url",
        "playUrl",
        "playback_url",
        "playbackUrl",
        "stream",
        "stream_url",
        "streamUrl",
        "video_url",
        "videoUrl",
    )

    private val containerKeys = setOf(
        "servers",
        "server",
        "items",
        "data",
        "results",
        "links",
        "sources",
        "embeds",
        "videos",
        "streams",
    )

    /**
     * Parses plain JSON, raw encrypted bodies, or a JSON envelope whose `data` field is encrypted.
     * The caller supplies the response decryptor so this parser remains easy to unit test.
     */
    fun decodeBody(rawBody: String, decrypt: (String) -> String?): JSONObject? {
        val body = rawBody.trim()
        if (body.isEmpty()) return null

        if (body.startsWith("{")) {
            val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (envelope.optBoolean("blocked", false)) return null

            if (envelope.optBoolean("encrypted", false)) {
                val ciphertext = envelope.optString("data").trim()
                if (ciphertext.isEmpty()) return null
                val plaintext = decrypt(ciphertext)?.trim() ?: return null
                return normalizeJson(plaintext)
            }

            return unwrapData(envelope)
        }

        if (body.startsWith("[")) {
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
            return JSONObject().put("items", array)
        }

        val plaintext = decrypt(body)?.trim() ?: return null
        return normalizeJson(plaintext)
    }

    /** Finds playable HTTP(S) URLs through common server-list containers and field aliases. */
    fun extractServerUrls(root: JSONObject): List<String> {
        val urls = LinkedHashSet<String>()
        var visited = 0

        fun visit(value: Any?, depth: Int, allowStringUrl: Boolean) {
            if (value == null || depth > MAX_UNWRAP_DEPTH || visited++ >= MAX_VISITED_NODES) return

            when (value) {
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), depth + 1, allowStringUrl = true)
                    }
                }

                is JSONObject -> {
                    for (key in linkKeys) {
                        when (val child = value.opt(key)) {
                            is String -> normalizeHttpUrl(child)?.let(urls::add)
                            is JSONObject, is JSONArray -> visit(child, depth + 1, allowStringUrl = true)
                        }
                    }

                    for (key in containerKeys) {
                        if (key in linkKeys) continue
                        when (val child = value.opt(key)) {
                            is JSONObject, is JSONArray -> visit(child, depth + 1, allowStringUrl = true)
                            is String -> {
                                val trimmed = child.trim()
                                when {
                                    trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                                        ?.let { visit(it, depth + 1, allowStringUrl = true) }
                                    trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                                        ?.let { visit(it, depth + 1, allowStringUrl = true) }
                                    else -> normalizeHttpUrl(trimmed)?.let(urls::add)
                                }
                            }
                        }
                    }
                }

                is String -> {
                    val trimmed = value.trim()
                    when {
                        trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
                            ?.let { visit(it, depth + 1, allowStringUrl = true) }
                        trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                            ?.let { visit(it, depth + 1, allowStringUrl = true) }
                        allowStringUrl -> normalizeHttpUrl(trimmed)?.let(urls::add)
                    }
                }
            }
        }

        visit(root, depth = 0, allowStringUrl = false)
        return urls.toList()
    }

    private fun normalizeJson(raw: String): JSONObject? {
        val body = raw.trim()
        if (body.startsWith("{")) {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            return unwrapData(root)
        }
        if (body.startsWith("[")) {
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
            return JSONObject().put("items", array)
        }
        return null
    }

    private fun unwrapData(root: JSONObject): JSONObject? {
        if (root.optBoolean("blocked", false)) return null

        // Match the API's envelope: unwrap one object-valued `data` field, but keep the outer
        // object when `data` is an array so sibling fields such as `sections` are not discarded.
        val data = root.optJSONObject("data") ?: return root
        if (data.optBoolean("blocked", false)) return null
        return data
    }

    private fun normalizeHttpUrl(raw: String): String? {
        var candidate = raw.trim().trim('"', '\'', '`').replace("&amp;", "&")
        if (candidate.startsWith("//")) candidate = "https:$candidate"
        if (candidate.startsWith("www.", ignoreCase = true)) candidate = "https://$candidate"

        val schemeEnd = candidate.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = candidate.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val authority = candidate.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isBlank() || authority.any(Char::isWhitespace)) return null

        return "$scheme${candidate.substring(schemeEnd)}"
    }
}
