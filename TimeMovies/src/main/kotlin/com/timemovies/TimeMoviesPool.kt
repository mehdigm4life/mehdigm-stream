package com.timemovies

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in Pre-Activated Multi-Account Pool for TimeMovies v3+.
 *
 * Allows every CloudStream user to browse, search, watch, and download immediately out-of-the-box
 * without creating an account or entering an OTP, while automatically failing over across pool
 * accounts whenever a free account reaches its daily quota (`fq`/`dcr`).
 *
 * Pool sources (merged & deduplicated by `slotId`):
 * 1. Local custom bundles added in Settings (`TimeMoviesPrefs.localPoolBundlesRaw`)
 * 2. Live OTA pool (`TimeMovies/pool.enc` via `raw.githubusercontent.com` + `cdn.jsdelivr.net` mirror)
 * 3. Offline embedded bundles (`TimeMoviesEmbeddedPool.BUNDLES` baked into `classes.dex` at build time)
 */
object TimeMoviesPool {

    private val DEFAULT_REMOTE_POOL_URLS = listOf(
        "https://raw.githubusercontent.com/mehdigm4life/mehdigm-stream/main/TimeMovies/pool.enc",
        "https://cdn.jsdelivr.net/gh/mehdigm4life/mehdigm-stream@main/TimeMovies/pool.enc",
    )

    private const val REMOTE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
    private const val NEGATIVE_FETCH_COOLDOWN_MS = 5L * 60L * 1000L // 5 minutes when empty/unreachable
    private const val QUOTA_COOLDOWN_MS = 6L * 60L * 60L * 1000L // 6 hours media quota cooldown
    private const val AUTH_FAILURE_COOLDOWN_MS = 30L * 60L * 1000L // 30 minutes auth failure cooldown

    private val poolFetchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val fetchLock = Any()
    private val overlayLock = Any()
    private val roundRobinCursor = AtomicInteger(0)

    @Volatile
    private var lastNegativeFetchAttemptMs: Long = 0L

    data class PoolAccountBundle(
        val slotId: String,
        val label: String,
        val authToken: String,
        val deviceKeyId: String?,
        val keyPair: TimeMoviesCrypto.EcKeyMaterial,
        val virtualDevice: TimeMoviesPrefs.VirtualDeviceProfile,
        val profileId: String?,
        val profileName: String?,
        val profileMaturity: Int = 5,
    ) {
        fun toBundleJson(): String = JSONObject().apply {
            put("v", 1)
            put("slotId", slotId)
            put("label", label)
            put("tk", authToken)
            if (!deviceKeyId.isNullOrBlank()) put("dk", deviceKeyId)
            put("priv", keyPair.privateKeyPkcs8B64Url)
            put("pub", keyPair.publicKeySpkiB64Url)
            put("dev", JSONObject(virtualDevice.toStorageJson()))
            if (!profileId.isNullOrBlank()) put("pid", profileId)
            if (!profileName.isNullOrBlank()) put("pname", profileName)
            put("mat", profileMaturity)
        }.toString()

        fun seal(): String = TimeMoviesCrypto.sealPoolBundleJson(toBundleJson())

        companion object {
            fun unseal(bundleLine: String): PoolAccountBundle? = runCatching {
                val jsonStr = TimeMoviesCrypto.unsealPoolBundleJson(bundleLine) ?: return null
                fromBundleJson(jsonStr)
            }.getOrNull()

            fun fromBundleJson(jsonStr: String): PoolAccountBundle? = runCatching {
                val o = JSONObject(jsonStr)
                val tk = o.optString("tk", "").trim()
                if (tk.count { it == '.' } < 2) return null
                val priv = o.optString("priv", "").ifBlank { o.optString("sk", "") }.trim()
                val pub = o.optString("pub", "").ifBlank { o.optString("pk", "") }.trim()
                val kp = TimeMoviesCrypto.normalizeOrValidateKeyPair(priv, pub) ?: return null
                val vDev = o.optJSONObject("dev")?.let {
                    TimeMoviesPrefs.VirtualDeviceProfile.fromStorageJson(it.toString())
                } ?: TimeMoviesPrefs.buildSyntheticDevice()
                val computedSlotId = o.optString("slotId", "").ifBlank { o.optString("id", "") }.trim().ifEmpty {
                    val scope = TimeMoviesPrefs.computeTokenScope(tk, vDev.deviceId)
                    TimeMoviesCrypto.sha256Hex("$scope|${kp.publicKeySpkiB64Url}").take(16)
                }
                PoolAccountBundle(
                    slotId = computedSlotId,
                    label = o.optString("label", "Pool #${computedSlotId.take(4)}").ifBlank {
                        "Pool #${computedSlotId.take(4)}"
                    },
                    authToken = tk,
                    deviceKeyId = o.optString("dk", "").trim().ifEmpty { null },
                    keyPair = kp,
                    virtualDevice = vDev,
                    profileId = o.optString("pid", "").trim().ifEmpty { null },
                    profileName = o.optString("pname", "").trim().ifEmpty { null },
                    profileMaturity = o.optInt("mat", 5),
                )
            }.getOrNull()
        }
    }

    data class PoolSlotRuntimeState(
        val refreshedAuthToken: String? = null,
        val enrolledDeviceKeyId: String? = null,
        val resolvedProfileId: String? = null,
        val resolvedMaturity: Int? = null,
        val quotaExhaustedUntilMs: Long = 0L,
        val downloadQuotaExhaustedUntilMs: Long = 0L,
        val authFailedUntilMs: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            if (!refreshedAuthToken.isNullOrBlank()) put("tk", refreshedAuthToken)
            if (!enrolledDeviceKeyId.isNullOrBlank()) put("dk", enrolledDeviceKeyId)
            if (!resolvedProfileId.isNullOrBlank()) put("pid", resolvedProfileId)
            if (resolvedMaturity != null) put("mat", resolvedMaturity)
            if (quotaExhaustedUntilMs > 0L) put("qExp", quotaExhaustedUntilMs)
            if (downloadQuotaExhaustedUntilMs > 0L) put("dqExp", downloadQuotaExhaustedUntilMs)
            if (authFailedUntilMs > 0L) put("aExp", authFailedUntilMs)
        }

        companion object {
            fun fromJson(o: JSONObject): PoolSlotRuntimeState = PoolSlotRuntimeState(
                refreshedAuthToken = o.optString("tk", "").trim().ifEmpty { null },
                enrolledDeviceKeyId = o.optString("dk", "").trim().ifEmpty { null },
                resolvedProfileId = o.optString("pid", "").trim().ifEmpty { null },
                resolvedMaturity = if (o.has("mat")) o.optInt("mat", 5) else null,
                quotaExhaustedUntilMs = o.optLong("qExp", 0L),
                downloadQuotaExhaustedUntilMs = o.optLong("dqExp", 0L),
                authFailedUntilMs = o.optLong("aExp", 0L),
            )
        }
    }

    data class EffectivePoolSlot(
        val bundle: PoolAccountBundle,
        val runtime: PoolSlotRuntimeState,
    ) {
        val slotId: String get() = bundle.slotId
        val label: String get() = bundle.label
        val effectiveToken: String get() = runtime.refreshedAuthToken ?: bundle.authToken
        val effectiveDeviceKeyId: String? get() = runtime.enrolledDeviceKeyId ?: bundle.deviceKeyId
        val effectiveProfileId: String get() = (runtime.resolvedProfileId ?: bundle.profileId).orEmpty()
        val effectiveMaturity: String
            get() = if (effectiveProfileId.isNotEmpty()) {
                (runtime.resolvedMaturity ?: bundle.profileMaturity).toString()
            } else {
                ""
            }
        val userId: String?
            get() = extractJwtUserId(effectiveToken)

        fun isAuthHealthy(nowMs: Long): Boolean = runtime.authFailedUntilMs <= nowMs

        fun isMediaQuotaHealthy(nowMs: Long, action: String = "watch"): Boolean {
            if (!isAuthHealthy(nowMs)) return false
            return if (action == "download" || action == "season_download") {
                runtime.downloadQuotaExhaustedUntilMs <= nowMs
            } else {
                runtime.quotaExhaustedUntilMs <= nowMs
            }
        }
    }

    private fun parseBundleLines(rawText: String?): List<PoolAccountBundle> {
        if (rawText.isNullOrBlank()) return emptyList()
        return rawText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { PoolAccountBundle.unseal(it) }
            .toList()
    }

    private fun loadRuntimeOverlayMap(): MutableMap<String, PoolSlotRuntimeState> {
        val raw = TimeMoviesPrefs.poolRuntimeStateJson ?: return HashMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = HashMap<String, PoolSlotRuntimeState>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val child = obj.optJSONObject(k) ?: continue
                map[k] = PoolSlotRuntimeState.fromJson(child)
            }
            map
        }.getOrElse { HashMap() }
    }

    private fun saveRuntimeOverlayMap(map: Map<String, PoolSlotRuntimeState>) {
        if (map.isEmpty()) {
            TimeMoviesPrefs.poolRuntimeStateJson = null
            return
        }
        val root = JSONObject()
        for ((k, v) in map) {
            root.put(k, v.toJson())
        }
        TimeMoviesPrefs.poolRuntimeStateJson = root.toString()
    }

    fun updateSlotRuntime(slotId: String, transform: (PoolSlotRuntimeState) -> PoolSlotRuntimeState) {
        synchronized(overlayLock) {
            val map = loadRuntimeOverlayMap()
            val current = map[slotId] ?: PoolSlotRuntimeState()
            map[slotId] = transform(current)
            saveRuntimeOverlayMap(map)
        }
    }

    fun markSlotQuotaExhausted(
        slotId: String,
        action: String = "watch",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        updateSlotRuntime(slotId) { st ->
            if (action == "download" || action == "season_download") {
                st.copy(downloadQuotaExhaustedUntilMs = nowMs + QUOTA_COOLDOWN_MS)
            } else {
                st.copy(quotaExhaustedUntilMs = nowMs + QUOTA_COOLDOWN_MS)
            }
        }
        roundRobinCursor.incrementAndGet()
    }

    fun markUserIdQuotaExhausted(
        userId: String?,
        action: String = "watch",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (userId.isNullOrBlank()) return
        val slots = loadAllSlots(allowNetworkFetch = false)
        for (slot in slots) {
            if (slot.userId == userId) {
                markSlotQuotaExhausted(slot.slotId, action = action, nowMs = nowMs)
            }
        }
    }

    fun markSlotAuthFailed(slotId: String, nowMs: Long = System.currentTimeMillis()) {
        updateSlotRuntime(slotId) { it.copy(authFailedUntilMs = nowMs + AUTH_FAILURE_COOLDOWN_MS) }
        roundRobinCursor.incrementAndGet()
    }

    fun markSlotHealthy(slotId: String) {
        updateSlotRuntime(slotId) {
            if (it.authFailedUntilMs == 0L) it else it.copy(authFailedUntilMs = 0L)
        }
    }

    fun clearAllCooldowns() {
        synchronized(overlayLock) {
            val map = loadRuntimeOverlayMap()
            val cleaned = map.mapValues { (_, st) ->
                st.copy(
                    quotaExhaustedUntilMs = 0L,
                    downloadQuotaExhaustedUntilMs = 0L,
                    authFailedUntilMs = 0L,
                )
            }
            saveRuntimeOverlayMap(cleaned)
        }
    }

    fun extractJwtUserId(token: String?): String? {
        val claims = TimeMoviesPrefs.decodeJwtClaims(token) ?: return null
        return claims.optString("_id", "").trim().takeIf { it.isNotEmpty() }
    }

    fun extractJwtEmail(token: String?): String? {
        val claims = TimeMoviesPrefs.decodeJwtClaims(token) ?: return null
        return claims.optString("e", "").trim().lowercase().takeIf { it.isNotEmpty() }
    }

    private fun remotePoolUrls(): List<String> {
        return TimeMoviesPrefs.remotePoolUrlsOverride ?: DEFAULT_REMOTE_POOL_URLS
    }

    /**
     * Fetches `TimeMovies/pool.enc` from GitHub Raw / jsDelivr CDN and caches valid `tm3pool:v1:...` lines.
     */
    fun refreshRemotePool(force: Boolean = false): Int {
        synchronized(fetchLock) {
            val now = System.currentTimeMillis()
            val cachedRaw = TimeMoviesPrefs.remotePoolBundlesRaw
            val fetchedAt = TimeMoviesPrefs.remotePoolFetchedAtMs

            if (!force) {
                if (!cachedRaw.isNullOrBlank() && (now - fetchedAt) in 0 until REMOTE_CACHE_TTL_MS) {
                    return parseBundleLines(cachedRaw).size
                }
                if (cachedRaw.isNullOrBlank() && (now - lastNegativeFetchAttemptMs) in 0 until NEGATIVE_FETCH_COOLDOWN_MS) {
                    return 0
                }
            }

            for (url in remotePoolUrls()) {
                val fetchedLines = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", "CloudStream-TimeMovies/3.0")
                        .header("Cache-Control", "no-cache")
                        .build()
                    poolFetchClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.string()
                    }
                }.getOrNull()

                if (fetchedLines != null) {
                    val validLines = fetchedLines
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.startsWith(TimeMoviesCrypto.POOL_BUNDLE_PREFIX) && PoolAccountBundle.unseal(it) != null }
                        .toList()
                    if (validLines.isNotEmpty()) {
                        val joined = validLines.joinToString("\n")
                        TimeMoviesPrefs.remotePoolBundlesRaw = joined
                        TimeMoviesPrefs.remotePoolFetchedAtMs = now
                        lastNegativeFetchAttemptMs = 0L
                        return validLines.size
                    }
                }
            }

            lastNegativeFetchAttemptMs = now
            return parseBundleLines(cachedRaw).size
        }
    }

    /**
     * Collects all deduplicated `EffectivePoolSlot`s across Local + Remote + Embedded pool sources.
     */
    fun loadAllSlots(allowNetworkFetch: Boolean = true): List<EffectivePoolSlot> {
        val localBundles = parseBundleLines(TimeMoviesPrefs.localPoolBundlesRaw)
        val embeddedBundles = TimeMoviesEmbeddedPool.BUNDLES.mapNotNull { PoolAccountBundle.unseal(it) }
        var remoteBundles = parseBundleLines(TimeMoviesPrefs.remotePoolBundlesRaw)

        if (allowNetworkFetch) {
            val hasAnyImmediate = localBundles.isNotEmpty() || embeddedBundles.isNotEmpty() || remoteBundles.isNotEmpty()
            val cacheExpired = (System.currentTimeMillis() - TimeMoviesPrefs.remotePoolFetchedAtMs) >= REMOTE_CACHE_TTL_MS
            if (!hasAnyImmediate || cacheExpired) {
                refreshRemotePool(force = false)
                remoteBundles = parseBundleLines(TimeMoviesPrefs.remotePoolBundlesRaw)
            }
        }

        val mergedById = LinkedHashMap<String, PoolAccountBundle>()
        for (b in localBundles) mergedById.putIfAbsent(b.slotId, b)
        for (b in remoteBundles) mergedById.putIfAbsent(b.slotId, b)
        for (b in embeddedBundles) mergedById.putIfAbsent(b.slotId, b)

        if (mergedById.isEmpty()) return emptyList()

        val overlays = synchronized(overlayLock) { loadRuntimeOverlayMap() }
        return mergedById.values.map { bundle ->
            EffectivePoolSlot(
                bundle = bundle,
                runtime = overlays[bundle.slotId] ?: PoolSlotRuntimeState(),
            )
        }
    }

    fun hasAnySlot(allowNetworkFetch: Boolean = true): Boolean =
        loadAllSlots(allowNetworkFetch = allowNetworkFetch).isNotEmpty()

    fun countConfiguredSlots(allowNetworkFetch: Boolean = false): Int =
        loadAllSlots(allowNetworkFetch = allowNetworkFetch).size

    private fun rotateListFromCursor(slots: List<EffectivePoolSlot>): List<EffectivePoolSlot> {
        if (slots.size <= 1) return slots
        val start = (roundRobinCursor.get() and Int.MAX_VALUE) % slots.size
        if (start == 0) return slots
        return slots.subList(start, slots.size) + slots.subList(0, start)
    }

    /**
     * Candidate pool slots for catalog browsing (`Home`, `Search`, `WorkDetails`).
     * Note: Slots whose daily media quota (`fq`/`dcr`) is exhausted still work 100% for catalog browsing!
     */
    fun getCatalogCandidateSlots(nowMs: Long = System.currentTimeMillis()): List<EffectivePoolSlot> {
        var all = loadAllSlots(allowNetworkFetch = true)
        if (all.isEmpty()) return emptyList()

        val healthy = rotateListFromCursor(all.filter { it.isAuthHealthy(nowMs) })
        if (healthy.isNotEmpty()) return healthy

        // If every cached slot was marked auth-failed, check OTA once for newly published accounts
        refreshRemotePool(force = true)
        all = loadAllSlots(allowNetworkFetch = false)
        val freshHealthy = rotateListFromCursor(all.filter { it.isAuthHealthy(nowMs) })
        if (freshHealthy.isNotEmpty()) return freshHealthy

        // Last-resort fallback: try oldest-failed slot in case transient network/clock skew caused cooldown
        return all.sortedBy { it.runtime.authFailedUntilMs }.take(1)
    }

    /**
     * Candidate pool slots for `POST /api/v4.7/app/media/grant` (`watch` / `download`),
     * automatically skipping accounts whose daily free quota for that action is currently exhausted
     * or whose `_id` matches `excludeUserId` (e.g., personal session that already exhausted quota).
     */
    fun getMediaCandidateSlots(
        nowMs: Long = System.currentTimeMillis(),
        action: String = "watch",
        excludeUserId: String? = null,
    ): List<EffectivePoolSlot> {
        var all = loadAllSlots(allowNetworkFetch = true)
            .filter { excludeUserId.isNullOrBlank() || it.userId != excludeUserId }
        if (all.isEmpty()) return emptyList()

        val quotaHealthy = rotateListFromCursor(all.filter { it.isMediaQuotaHealthy(nowMs, action) })
        if (quotaHealthy.isNotEmpty()) return quotaHealthy

        // All known pool accounts hit quota — check OTA `pool.enc` once in case new accounts were added
        refreshRemotePool(force = true)
        all = loadAllSlots(allowNetworkFetch = false)
            .filter { excludeUserId.isNullOrBlank() || it.userId != excludeUserId }
        val freshQuotaHealthy = rotateListFromCursor(all.filter { it.isMediaQuotaHealthy(nowMs, action) })
        if (freshQuotaHealthy.isNotEmpty()) return freshQuotaHealthy

        // Fallback: try auth-healthy slot whose quota cooldown is closest to expiring (in case server UTC day rolled over)
        return all
            .filter { it.isAuthHealthy(nowMs) }
            .sortedBy {
                if (action == "download" || action == "season_download") {
                    it.runtime.downloadQuotaExhaustedUntilMs
                } else {
                    it.runtime.quotaExhaustedUntilMs
                }
            }
            .take(1)
    }

    /**
     * Automatically seals and archives the currently activated personal session into the device's
     * Local Unlimited Vault (`localPoolBundlesRaw`), deduplicating by user `_id` so signing out or
     * registering additional accounts never discards previously activated accounts.
     */
    fun autoArchiveCurrentPersonalSession(): Boolean = runCatching {
        val tk = TimeMoviesPrefs.authToken?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val claims = TimeMoviesPrefs.decodeJwtClaims(tk)
        if (claims != null && (
                (claims.has("ia") && !claims.optBoolean("ia", true)) ||
                    (claims.has("is_active") && !claims.optBoolean("is_active", true))
            )
        ) {
            return false
        }

        val vDev = TimeMoviesPrefs.getOrCreateVirtualDevice()
        val kp = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        val scope = TimeMoviesPrefs.computeTokenScope(tk, vDev.deviceId)
        val slotId = TimeMoviesCrypto.sha256Hex("$scope|${kp.publicKeySpkiB64Url}").take(16)
        val email = TimeMoviesPrefs.userEmail?.takeIf { it.isNotBlank() }
            ?: extractJwtEmail(tk)
        val label = email ?: "Vault #${slotId.take(4)}"
        val incomingUserId = extractJwtUserId(tk)

        val newBundle = PoolAccountBundle(
            slotId = slotId,
            label = label,
            authToken = tk,
            deviceKeyId = TimeMoviesPrefs.getDeviceKeyIdForScope(scope) ?: TimeMoviesPrefs.deviceKeyId,
            keyPair = kp,
            virtualDevice = vDev,
            profileId = TimeMoviesPrefs.profileId,
            profileName = TimeMoviesPrefs.profileName,
            profileMaturity = TimeMoviesPrefs.profileMaturity,
        )

        val existing = parseBundleLines(TimeMoviesPrefs.localPoolBundlesRaw)
        val merged = ArrayList<PoolAccountBundle>(existing.size + 1)
        merged.add(newBundle)
        for (old in existing) {
            if (old.slotId == newBundle.slotId) continue
            val oldUid = extractJwtUserId(old.authToken)
            if (!incomingUserId.isNullOrBlank() && oldUid == incomingUserId) continue
            merged.add(old)
        }
        TimeMoviesPrefs.localPoolBundlesRaw = merged.joinToString("\n") { it.seal() }
        true
    }.getOrDefault(false)

    /**
     * Generates a fresh Gmail dot/plus alias (`m.ehdi@gmail.com`, `me.hdi@gmail.com`, `@googlemail.com`,
     * `mehdi+tm2@gmail.com`) delivering OTP codes to the exact same Gmail inbox while remaining distinct
     * from all emails already stored in personal preferences or the local pool vault.
     */
    fun generateNextGmailAlias(explicitEmail: String? = null): String? {
        val rawSource = explicitEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: TimeMoviesPrefs.userEmail?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractJwtEmail(TimeMoviesPrefs.authToken)
            ?: parseBundleLines(TimeMoviesPrefs.localPoolBundlesRaw)
                .firstNotNullOfOrNull { b ->
                    b.label.takeIf { it.contains("@") } ?: extractJwtEmail(b.authToken)
                }
            ?: return null

        val lower = rawSource.lowercase()
        val atIdx = lower.indexOf('@')
        if (atIdx <= 0) return null
        val rawLocal = lower.substring(0, atIdx)
        val rawDomain = lower.substring(atIdx + 1)
        if (rawDomain != "gmail.com" && rawDomain != "googlemail.com") return null

        val cleanLocal = rawLocal.substringBefore('+').replace(".", "")
        if (cleanLocal.length < 2) return null

        val usedEmails = HashSet<String>()
        usedEmails.add(lower)
        TimeMoviesPrefs.userEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { usedEmails.add(it) }
        TimeMoviesPrefs.pendingOtpEmail?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { usedEmails.add(it) }
        for (b in parseBundleLines(TimeMoviesPrefs.localPoolBundlesRaw)) {
            if (b.label.contains("@")) usedEmails.add(b.label.trim().lowercase())
            extractJwtEmail(b.authToken)?.let { usedEmails.add(it) }
        }

        val gapCount = (cleanLocal.length - 1).coerceAtMost(12)
        val maxMask = (1 shl gapCount) - 1
        for (domain in listOf("gmail.com", "googlemail.com")) {
            for (mask in 1..maxMask) {
                // Skip adjacent dots (`..`) which Gmail & RFC 5321 forbid!
                if ((mask and (mask shl 1)) != 0) continue
                val sb = StringBuilder(cleanLocal.length + gapCount)
                for (i in cleanLocal.indices) {
                    sb.append(cleanLocal[i])
                    if (i < gapCount && ((mask shr i) and 1) == 1) {
                        sb.append('.')
                    }
                }
                val candidate = "$sb@$domain"
                if (candidate !in usedEmails) return candidate
            }
        }

        for (idx in 2..999) {
            val plusCandidate = "$cleanLocal+tm$idx@gmail.com"
            if (plusCandidate !in usedEmails) return plusCandidate
        }
        return null
    }

    /**
     * Exports the currently signed-in personal TimeMovies session as an AES-256-GCM sealed `tm3pool:v1:...`
     * bundle containing zero passwords — ready to paste into `TimeMovies/pool.enc` or local pool testing.
     */
    fun exportCurrentPersonalSessionBundle(customLabel: String? = null): Result<String> = runCatching {
        val tk = TimeMoviesPrefs.authToken?.takeIf { it.isNotBlank() }
            ?: error("لا يوجد حساب شخصي مفعّل حالياً لتصديره")
        val claims = TimeMoviesPrefs.decodeJwtClaims(tk)
        if (claims != null && claims.has("ia") && !claims.optBoolean("ia", true)) {
            error("الحساب لم يتم تفعيله برمز OTP بعد")
        }

        // Ensure device key and main USP profile are initialized before exporting so every user gets instant access
        val dk = TimeMoviesApi.ensureDeviceKeyEnrolled(tk)
        if (TimeMoviesPrefs.profileId.isNullOrBlank()) {
            TimeMoviesApi.syncAppDataAndSelectMainProfile()
        }

        val vDev = TimeMoviesPrefs.getOrCreateVirtualDevice()
        val kp = TimeMoviesPrefs.getOrCreateDeviceKeyPair()
        val scope = TimeMoviesPrefs.computeTokenScope(tk, vDev.deviceId)
        val slotId = TimeMoviesCrypto.sha256Hex("$scope|${kp.publicKeySpkiB64Url}").take(16)
        val label = customLabel?.trim()?.takeIf { it.isNotBlank() }
            ?: "Pool #${slotId.take(4)}"

        val bundle = PoolAccountBundle(
            slotId = slotId,
            label = label,
            authToken = tk,
            deviceKeyId = dk ?: TimeMoviesPrefs.getDeviceKeyIdForScope(scope),
            keyPair = kp,
            virtualDevice = vDev,
            profileId = TimeMoviesPrefs.profileId,
            profileName = TimeMoviesPrefs.profileName,
            profileMaturity = TimeMoviesPrefs.profileMaturity,
        )
        bundle.seal()
    }

    /**
     * Adds one or more `tm3pool:v1:...` lines to the local device pool store (deduplicated by `slotId`).
     * Returns the count of newly validated bundles now stored locally.
     */
    fun addLocalPoolBundles(rawInput: String): Int {
        val incoming = parseBundleLines(rawInput)
        if (incoming.isEmpty()) return 0

        val existing = parseBundleLines(TimeMoviesPrefs.localPoolBundlesRaw)
        val byId = LinkedHashMap<String, PoolAccountBundle>()
        for (b in incoming) byId[b.slotId] = b
        for (b in existing) byId.putIfAbsent(b.slotId, b)

        TimeMoviesPrefs.localPoolBundlesRaw = byId.values.joinToString("\n") { it.seal() }
        clearAllCooldowns()
        return byId.size
    }

    fun clearLocalPoolBundles() {
        TimeMoviesPrefs.localPoolBundlesRaw = null
    }

    internal fun resetForTests() {
        roundRobinCursor.set(0)
        lastNegativeFetchAttemptMs = 0L
    }
}
