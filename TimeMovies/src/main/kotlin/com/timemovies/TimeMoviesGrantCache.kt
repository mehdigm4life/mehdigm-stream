package com.timemovies

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Persistent zero-quota cache for resolved `TimeMovies` (`DoomPlayer` / `sv*.egy.rocks`) Master HLS
 * playlists, direct progressive `.mp4` downloads, and external subtitle tracks.
 *
 * Why this eliminates redundant credit burn:
 * - `api.apks.lol`'s `/app/m?mt=...` proxy URL expires in ~210s, whereas the underlying `sv*.egy.rocks`
 *   Kaltura `nginx-vod-module` signed segment/variant URLs remain valid for 4–24 hours.
 * - By caching the absolutized `sv*.egy.rocks` Master M3U8 + direct MP4 URLs in `SharedPreferences`
 *   and verifying CDN liveness (`200`/`206`) before reuse, re-opening, resuming, switching qualities,
 *   or downloading an already-opened movie/episode costs **0 credits** even across app restarts.
 */
object TimeMoviesGrantCache {

    internal const val CACHE_TTL_MS = 10L * 60L * 60L * 1000L // 10 hours
    private const val MAX_ENTRIES = 64

    private val lock = Any()
    private val memoryMap = LinkedHashMap<String, CachedMediaEntry>(32, 0.75f, true)
    @Volatile
    private var diskLoaded = false

    private val probeClient: OkHttpClient by lazy {
        TimeMoviesApi.plainClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class CachedDirectVideo(
        val url: String,
        val label: String,
        val quality: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("u", url)
            .put("l", label)
            .put("q", quality)

        companion object {
            fun fromJson(o: JSONObject): CachedDirectVideo? {
                val u = o.optString("u", "").trim().takeIf { it.startsWith("http") } ?: return null
                return CachedDirectVideo(
                    url = u,
                    label = o.optString("l", "MP4"),
                    quality = o.optInt("q", 720),
                )
            }
        }
    }

    data class CachedSubtitle(
        val lang: String,
        val url: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lg", lang)
            .put("u", url)

        companion object {
            fun fromJson(o: JSONObject): CachedSubtitle? {
                val u = o.optString("u", "").trim().takeIf { it.startsWith("http") } ?: return null
                return CachedSubtitle(
                    lang = o.optString("lg", "Arabic").ifBlank { "Arabic" },
                    url = u,
                )
            }
        }
    }

    data class CachedMediaEntry(
        val key: String,
        val workKey: String,
        val savedAtMs: Long,
        val absolutizedMasterM3u8: String?,
        val probeUrl: String?,
        val directVideos: List<CachedDirectVideo>,
        val subtitles: List<CachedSubtitle>,
    ) {
        fun hasPlayableContent(): Boolean =
            !absolutizedMasterM3u8.isNullOrBlank() || directVideos.isNotEmpty()

        fun toJson(): JSONObject = JSONObject().apply {
            put("k", key)
            put("wk", workKey)
            put("ts", savedAtMs)
            if (!absolutizedMasterM3u8.isNullOrBlank()) put("m3u8", absolutizedMasterM3u8)
            if (!probeUrl.isNullOrBlank()) put("prb", probeUrl)
            if (directVideos.isNotEmpty()) {
                val arr = JSONArray()
                directVideos.forEach { arr.put(it.toJson()) }
                put("vids", arr)
            }
            if (subtitles.isNotEmpty()) {
                val arr = JSONArray()
                subtitles.forEach { arr.put(it.toJson()) }
                put("subs", arr)
            }
        }

        companion object {
            fun fromJson(o: JSONObject): CachedMediaEntry? {
                val k = o.optString("k", "").trim().takeIf { it.isNotEmpty() } ?: return null
                val wk = o.optString("wk", "").trim().ifEmpty { k }
                val ts = o.optLong("ts", 0L)
                if (ts <= 0L) return null
                val m3u8 = o.optString("m3u8", "").takeIf { it.contains("#EXTM3U") }
                val prb = o.optString("prb", "").trim().takeIf { it.startsWith("http") }

                val vids = ArrayList<CachedDirectVideo>()
                val vidsArr = o.optJSONArray("vids")
                if (vidsArr != null) {
                    for (i in 0 until vidsArr.length()) {
                        vidsArr.optJSONObject(i)?.let { CachedDirectVideo.fromJson(it) }?.let { vids.add(it) }
                    }
                }

                val subs = ArrayList<CachedSubtitle>()
                val subsArr = o.optJSONArray("subs")
                if (subsArr != null) {
                    for (i in 0 until subsArr.length()) {
                        subsArr.optJSONObject(i)?.let { CachedSubtitle.fromJson(it) }?.let { subs.add(it) }
                    }
                }

                val entry = CachedMediaEntry(
                    key = k,
                    workKey = wk,
                    savedAtMs = ts,
                    absolutizedMasterM3u8 = m3u8,
                    probeUrl = prb,
                    directVideos = vids,
                    subtitles = subs,
                )
                return entry.takeIf { it.hasPlayableContent() }
            }
        }
    }

    private fun buildKeys(payload: TimeMoviesModels.LinkPayload): Pair<String, String> {
        val workKey = "${payload.workId.trim()}|${payload.episodeId?.trim().orEmpty()}"
        val fullKey = "$workKey|${payload.fileCode.trim()}"
        return fullKey to workKey
    }

    private fun ensureDiskLoadedLocked(nowMs: Long) {
        if (diskLoaded) return
        diskLoaded = true
        val raw = TimeMoviesPrefs.mediaGrantCacheJson ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val entry = CachedMediaEntry.fromJson(obj) ?: continue
                if ((nowMs - entry.savedAtMs) in 0 until CACHE_TTL_MS) {
                    memoryMap[entry.key] = entry
                }
            }
        }
    }

    private fun persistLocked(nowMs: Long) {
        val iter = memoryMap.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            if ((nowMs - e.savedAtMs) !in 0 until CACHE_TTL_MS) {
                iter.remove()
            }
        }
        while (memoryMap.size > MAX_ENTRIES) {
            val eldestKey = memoryMap.keys.firstOrNull() ?: break
            memoryMap.remove(eldestKey)
        }
        if (memoryMap.isEmpty()) {
            TimeMoviesPrefs.mediaGrantCacheJson = null
            return
        }
        val arr = JSONArray()
        for (entry in memoryMap.values) {
            arr.put(entry.toJson())
        }
        TimeMoviesPrefs.mediaGrantCacheJson = arr.toString()
    }

    fun countCachedItems(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        ensureDiskLoadedLocked(nowMs)
        memoryMap.values.count { (nowMs - it.savedAtMs) in 0 until CACHE_TTL_MS }
    }

    fun clearAll() {
        synchronized(lock) {
            memoryMap.clear()
            diskLoaded = true
            TimeMoviesPrefs.mediaGrantCacheJson = null
        }
    }

    fun putResolved(
        payload: TimeMoviesModels.LinkPayload,
        absolutizedMasterM3u8: String? = null,
        probeUrl: String? = null,
        directVideos: List<CachedDirectVideo> = emptyList(),
        subtitles: List<CachedSubtitle> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val (fullKey, workKey) = buildKeys(payload)
        synchronized(lock) {
            ensureDiskLoadedLocked(nowMs)
            val prev = memoryMap[fullKey] ?: memoryMap.values.firstOrNull { it.workKey == workKey }

            val mergedM3u8 = absolutizedMasterM3u8?.takeIf { it.contains("#EXTM3U") }
                ?: prev?.absolutizedMasterM3u8
            val mergedProbe = probeUrl?.takeIf { it.startsWith("http") }
                ?: prev?.probeUrl
                ?: directVideos.firstOrNull()?.url

            val mergedVids = LinkedHashMap<String, CachedDirectVideo>()
            prev?.directVideos?.forEach { mergedVids[it.url] = it }
            directVideos.forEach { mergedVids[it.url] = it }

            val mergedSubs = LinkedHashMap<String, CachedSubtitle>()
            prev?.subtitles?.forEach { mergedSubs[it.url] = it }
            subtitles.forEach { mergedSubs[it.url] = it }

            val entry = CachedMediaEntry(
                key = fullKey,
                workKey = workKey,
                savedAtMs = prev?.savedAtMs ?: nowMs,
                absolutizedMasterM3u8 = mergedM3u8,
                probeUrl = mergedProbe,
                directVideos = mergedVids.values.toList(),
                subtitles = mergedSubs.values.toList(),
            )
            if (!entry.hasPlayableContent()) return
            memoryMap[fullKey] = entry
            persistLocked(nowMs)
        }
    }

    /**
     * Looks up a non-expired cached entry for `payload` and verifies CDN liveness (`200`/`206`)
     * so expired CDN URLs are automatically evicted and never handed to ExoPlayer.
     */
    fun getValidEntry(
        payload: TimeMoviesModels.LinkPayload,
        verifyNetworkLiveness: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): CachedMediaEntry? {
        val (fullKey, workKey) = buildKeys(payload)
        val candidate = synchronized(lock) {
            ensureDiskLoadedLocked(nowMs)
            val found = memoryMap[fullKey]
                ?: memoryMap.values.lastOrNull { it.workKey == workKey }
                ?: return null
            if ((nowMs - found.savedAtMs) !in 0 until CACHE_TTL_MS) {
                memoryMap.remove(found.key)
                persistLocked(nowMs)
                return null
            }
            found
        }

        if (!verifyNetworkLiveness) return candidate
        val checkUrl = candidate.probeUrl ?: candidate.directVideos.firstOrNull()?.url
        if (checkUrl != null && !isCdnUrlStillLive(checkUrl)) {
            synchronized(lock) {
                memoryMap.remove(candidate.key)
                persistLocked(nowMs)
            }
            return null
        }
        return candidate
    }

    private fun isCdnUrlStillLive(url: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "ExoPlayerLib/2.19.1 (Linux; Android 14) ExoPlayerImpl/2.19.1")
            .header("Range", "bytes=0-127")
            .build()
        probeClient.newCall(req).execute().use { resp ->
            resp.code in 200..299
        }
    }.getOrDefault(false)
}
