package com.timemovies

import okhttp3.HttpUrl
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/**
 * Pure-Kotlin cryptographic & protocol helpers for TimeMovies v4.7 (`com.extremeqr.proapps` build 1092)
 * and Doom Video Player v1.6 (`com.doomvideo.players` build 1065).
 *
 * Zero native `.so` dependencies; works identically on Android API 21+ and JVM unit tests.
 */
object TimeMoviesCrypto {

    const val APP_BUILD = "1092"
    const val APP_ID = "60129f949ea4ea03d6598c06"
    const val APP_VERSION_ID = "6a3e1c72b864f954c0ef8d54"
    const val DEVICE_SIGNATURE_ALGORITHM = "ECDSA_SHA256_DER"

    const val DOOM_PLAYER_BUILD = "1065"
    const val DOOM_PLAYER_APP_ID = "6a5216d91e12eeadc194ab91"

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * Paths exempt from ECDSA device-key enrollment/signing (`u86.a` + enrollment endpoints).
     */
    private val EXEMPT_SEGMENTS: Set<String> = setOf(
        "d4f34a2a4f0249bbb3c92a7e45f3e1c2", // RefreshToken
        "f9e1a68e9d0c4f8ab4be63f0ed3aa1a4", // SocialSignIn
        "c205267f10be4a52a21ed4e7fa890263", // UserEmailSingIn
        "983fd0915e2841ec914088970dc6f059", // UserEmailSignUp
        "2b1ea8f4c8174afb90d75f29e38c1ab3",
        "5a0c0b8e7c3248f1a2d3e4f5b6c7d8e9",
        "c7f4a2e91b8d43a6ae05f7c2d9b16e30",
        "c9459fd0ac295d0f93237e370e6de40f",
        "528e9d0f958445e68b76f657a75ea1d4",
        "b05a119684b640ea8589ec4134dadefa", // AST (AppSettings)
        "ef91fe43a60d496598d95cf8e3c1f139",
        "2993cdecb5324ebc808f50f81c176d89",
        "0a3431eed2894035810bfcad800f7a4c",
        "c81d2e64a4d64d8faea7e1abf4cb8c13", // DeviceKeyChallenge
        "e4ad21d4b1fc41bca62d94bd972e93f6", // DeviceKeyEnroll
    )

    data class EcKeyMaterial(
        val privateKeyPkcs8B64Url: String,
        val publicKeySpkiB64Url: String,
    )

    data class SignedHeaders(
        val dk: String,
        val ds: String,
        val da: String = DEVICE_SIGNATURE_ALGORITHM,
    )

    data class DoomDeepLinkPayload(
        val srcOrUrl: String,
        val title: String?,
        val profileId: String?,
        val workId: String?,
        val episodeId: String?,
        val isEncryptedTicket: Boolean,
    )

    fun isEnrollmentEndpoint(encodedPath: String): Boolean {
        return encodedPath.endsWith("/c81d2e64a4d64d8faea7e1abf4cb8c13") ||
            encodedPath.endsWith("/e4ad21d4b1fc41bca62d94bd972e93f6")
    }

    fun shouldAttachDeviceSignature(url: HttpUrl, authToken: String): Boolean {
        if (authToken.isBlank()) return false
        val path = url.encodedPath
        if (!path.contains("/api/v4.7/")) return false
        if (isEnrollmentEndpoint(path)) return false
        return true
    }

    fun isExemptFromDeviceVerification(url: HttpUrl): Boolean {
        if (isEnrollmentEndpoint(url.encodedPath)) return true
        return url.pathSegments.any { it in EXEMPT_SEGMENTS }
    }

    fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return bytesToHex(bytes)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    /**
     * Pure-Kotlin Base64URL encoder without padding (`=`), compatible with Android API 21+ and JVM.
     */
    fun base64UrlEncodeNoPad(data: ByteArray): String = encodeBase64Internal(data, B64_URL, pad = false)

    fun base64Encode(data: ByteArray): String = encodeBase64Internal(data, B64_STD, pad = true)

    private fun encodeBase64Internal(data: ByteArray, alphabet: String, pad: Boolean): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val rem = data.size - i
            if (rem == 1) {
                sb.append(alphabet[b0 ushr 2])
                sb.append(alphabet[(b0 and 0x03) shl 4])
                if (pad) sb.append("==")
                break
            } else if (rem == 2) {
                val b1 = data[i + 1].toInt() and 0xFF
                sb.append(alphabet[b0 ushr 2])
                sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(alphabet[(b1 and 0x0F) shl 2])
                if (pad) sb.append('=')
                break
            } else {
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF
                sb.append(alphabet[b0 ushr 2])
                sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                sb.append(alphabet[b2 and 0x3F])
                i += 3
            }
        }
        return sb.toString()
    }

    /**
     * Pure-Kotlin Base64 / Base64URL decoder (handles missing padding, `-_`, `+/`, and whitespace).
     */
    fun base64DecodeFlexible(encoded: String): ByteArray {
        val cleaned = buildString(encoded.length) {
            for (c in encoded) {
                when (c) {
                    '-' -> append('+')
                    '_' -> append('/')
                    '=', ' ', '\n', '\r', '\t' -> Unit
                    else -> append(c)
                }
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)
        val lookup = IntArray(128) { -1 }
        for (idx in B64_STD.indices) {
            lookup[B64_STD[idx].code] = idx
        }
        val outLen = (cleaned.length * 3) / 4
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i < cleaned.length) {
            val rem = cleaned.length - i
            if (rem < 2) break
            val c0 = cleaned[i].code.let { if (it < 128) lookup[it] else -1 }
            val c1 = cleaned[i + 1].code.let { if (it < 128) lookup[it] else -1 }
            if (c0 < 0 || c1 < 0) throw IllegalArgumentException("Invalid base64 character")
            out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (rem >= 3 && outIdx < outLen) {
                val c2 = cleaned[i + 2].code.let { if (it < 128) lookup[it] else -1 }
                if (c2 < 0) throw IllegalArgumentException("Invalid base64 character")
                out[outIdx++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
                if (rem >= 4 && outIdx < outLen) {
                    val c3 = cleaned[i + 3].code.let { if (it < 128) lookup[it] else -1 }
                    if (c3 < 0) throw IllegalArgumentException("Invalid base64 character")
                    out[outIdx++] = (((c2 and 0x03) shl 6) or c3).toByte()
                }
            }
            i += 4
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    /**
     * Generates a NIST P-256 (`secp256r1`) ECDSA keypair for TimeMovies v4.7 device signing.
     */
    fun generateP256KeyPair(): EcKeyMaterial {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()
        return EcKeyMaterial(
            privateKeyPkcs8B64Url = base64UrlEncodeNoPad(kp.private.encoded),
            publicKeySpkiB64Url = base64UrlEncodeNoPad(kp.public.encoded),
        )
    }

    fun loadPrivateKey(pkcs8B64Url: String): PrivateKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(base64DecodeFlexible(pkcs8B64Url)))
    }

    fun loadPublicKey(spkiB64Url: String): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(base64DecodeFlexible(spkiB64Url)))
    }

    fun signEcdsaSha256DerB64Url(privateKeyPkcs8B64Url: String, messageBytes: ByteArray): String {
        val privateKey = loadPrivateKey(privateKeyPkcs8B64Url)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(messageBytes)
        return base64UrlEncodeNoPad(sig.sign())
    }

    fun verifyEcdsaSha256DerB64Url(
        publicKeySpkiB64Url: String,
        messageBytes: ByteArray,
        signatureB64Url: String,
    ): Boolean {
        val publicKey = loadPublicKey(publicKeySpkiB64Url)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(messageBytes)
        return sig.verify(base64DecodeFlexible(signatureB64Url))
    }

    /**
     * Builds the enrollment challenge signature payload:
     * `"$cid\n$ch\n$pk\nECDSA_SHA256_DER"`
     */
    fun buildEnrollmentChallengePayload(cid: String, challenge: String, publicKeySpkiB64Url: String): ByteArray {
        return "$cid\n$challenge\n$publicKeySpkiB64Url\n$DEVICE_SIGNATURE_ALGORITHM".toByteArray(Charsets.UTF_8)
    }

    /**
     * Builds the exact 13-line canonical request string verified by `V47DeviceSigningInterceptor`:
     *   1. METHOD (uppercase US)
     *   2. host (trimmed, lowercased US, stripped of `:port`)
     *   3. encodedPath (or `"/"` if empty)
     *   4. sorted non-empty `&`-split encodedQuery items joined by `"&"` (or `""`)
     *   5. appVersion (`"6a3e1c72b864f954c0ef8d54"`)
     *   6. appBuild (`"1092"`)
     *   7. authToken (`tk`)
     *   8. profileId (`usp._id` or `""`)
     *   9. maturity (`usp.maturity.toString()` if `profileId` is non-empty, else `""`)
     *  10. timestamp (`t`)
     *  11. nonce (`n`)
     *  12. sha256Hex(rawBody.ifEmpty { "{}" })
     *  13. deviceKeyId (`dk`)
     */
    fun buildCanonicalV47SigningPayload(
        method: String,
        url: HttpUrl,
        timestamp: String,
        nonce: String,
        rawBody: String?,
        authToken: String,
        profileId: String?,
        maturity: String?,
        deviceKeyId: String,
        appVersion: String = APP_VERSION_ID,
        appBuild: String = APP_BUILD,
    ): String {
        val upperMethod = method.uppercase(Locale.US)
        val lowerHost = url.host.trim().lowercase(Locale.US).substringBefore(':')
        val path = url.encodedPath.ifEmpty { "/" }
        val encodedQuery = url.encodedQuery
        val sortedQuery = if (!encodedQuery.isNullOrEmpty()) {
            encodedQuery
                .removePrefix("?")
                .split("&")
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString("&")
        } else {
            ""
        }
        val effectiveProfileId = profileId?.takeIf { it.isNotEmpty() }.orEmpty()
        val effectiveMaturity = if (effectiveProfileId.isNotEmpty()) {
            maturity.orEmpty()
        } else {
            ""
        }
        val normalizedBody = if (!rawBody.isNullOrEmpty()) rawBody else "{}"
        val bodyHashHex = sha256Hex(normalizedBody)

        return listOf(
            upperMethod,
            lowerHost,
            path,
            sortedQuery,
            appVersion,
            appBuild,
            authToken,
            effectiveProfileId,
            effectiveMaturity,
            timestamp,
            nonce,
            bodyHashHex,
            deviceKeyId,
        ).joinToString("\n")
    }

    fun signV47Request(
        privateKeyPkcs8B64Url: String,
        method: String,
        url: HttpUrl,
        timestamp: String,
        nonce: String,
        rawBody: String?,
        authToken: String,
        profileId: String?,
        maturity: String?,
        deviceKeyId: String,
    ): SignedHeaders {
        val canonical = buildCanonicalV47SigningPayload(
            method = method,
            url = url,
            timestamp = timestamp,
            nonce = nonce,
            rawBody = rawBody,
            authToken = authToken,
            profileId = profileId,
            maturity = maturity,
            deviceKeyId = deviceKeyId,
        )
        val ds = signEcdsaSha256DerB64Url(
            privateKeyPkcs8B64Url = privateKeyPkcs8B64Url,
            messageBytes = canonical.toByteArray(Charsets.UTF_8),
        )
        return SignedHeaders(dk = deviceKeyId, ds = ds, da = DEVICE_SIGNATURE_ALGORITHM)
    }

    /**
     * Decodes a Doom Video Player deep link (`timesnizz://doomplayer.com/<b64>` or `timesdizz://doomplayer.com/<b64>`),
     * matching `NavGraphKt.parseDeepPlayerArgs` & `DownloadNewDeep` in `com.doomvideo.players`.
     */
    fun parseDoomDeepLink(uriString: String): DoomDeepLinkPayload? {
        val trimmed = uriString.trim()
        val afterHost = trimmed
            .substringAfter("://doomplayer.com/", missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (afterHost.isEmpty()) return null

        // Format A: `timesnizz://doomplayer.com/<encodedSrc>/<title>` (2+ raw segments)
        if ('/' in afterHost) {
            val rawParts = afterHost.split('/')
            val first = safeUrlDecode(rawParts.getOrNull(0)).orEmpty()
            if (first.isNotBlank()) {
                val title = safeUrlDecode(rawParts.getOrNull(1))
                val isTicket = !first.startsWith("http://", ignoreCase = true) &&
                    !first.startsWith("https://", ignoreCase = true)
                return DoomDeepLinkPayload(
                    srcOrUrl = first,
                    title = title,
                    profileId = null,
                    workId = null,
                    episodeId = null,
                    isEncryptedTicket = isTicket,
                )
            }
        }

        // Format B: `timesnizz://doomplayer.com/<base64UrlPayload>` or `timesdizz://doomplayer.com/<base64UrlPayload>`
        val decoded = runCatching {
            String(base64DecodeFlexible(afterHost), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        // Query-style payload inside base64 (if any)
        if ((decoded.startsWith("src=") || decoded.contains("&src=") || decoded.startsWith("url=")) && '/' !in decoded.substringBefore('=')) {
            val map = decoded.removePrefix("?").split('&').mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null else kv.substring(0, idx) to safeUrlDecode(kv.substring(idx + 1)).orEmpty()
            }.toMap()
            val src = map["src"]?.ifBlank { null } ?: map["url"]?.ifBlank { null } ?: return null
            val ieFlag = map["ie"]?.lowercase(Locale.US)
            val isTicket = when (ieFlag) {
                "true", "1" -> true
                "false", "0" -> false
                else -> !src.startsWith("http://", ignoreCase = true) && !src.startsWith("https://", ignoreCase = true)
            }
            return DoomDeepLinkPayload(
                srcOrUrl = src,
                title = map["title"],
                profileId = map["pid"] ?: map["p"],
                workId = map["wk"] ?: map["workId"],
                episodeId = map["ep"]?.takeUnless { it == "@" },
                isEncryptedTicket = isTicket,
            )
        }

        val parts = decoded.split('/', limit = 7)
        val first = safeUrlDecode(parts.getOrNull(0))?.trim().orEmpty()
        if (first.isEmpty()) return null
        val second = safeUrlDecode(parts.getOrNull(1))?.takeIf { it.isNotBlank() }
        val profileId = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "@" }
        val workId = parts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "@" }
        val episodeId = parts.getOrNull(4)?.takeIf { it.isNotBlank() && it != "@" }
        val isTicket = !first.startsWith("http://", ignoreCase = true) &&
            !first.startsWith("https://", ignoreCase = true)

        return DoomDeepLinkPayload(
            srcOrUrl = first,
            title = second,
            profileId = profileId,
            workId = workId,
            episodeId = episodeId,
            isEncryptedTicket = isTicket,
        )
    }

    private fun safeUrlDecode(value: String?): String? {
        if (value == null) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
