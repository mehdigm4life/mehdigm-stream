package com.timemovies

import okhttp3.HttpUrl
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin cryptographic & protocol helpers for TimeMovies v4.7 (`com.extremeqr.proapps` build 1092)
 * and Doom Video Player v1.6 (`com.doomvideo.players` build 1065).
 *
 * v3 Hardening:
 * - Pins `AndroidOpenSSL` (Conscrypt/BoringSSL) on Android and `SunEC` on JVM, avoiding Android's
 *   deprecated/stripped `BC` and `AndroidKeyStoreBCWorkaround` providers.
 * - Normalizes every P-256 keypair into canonical 91-byte RFC 5480 `SubjectPublicKeyInfo` and
 *   canonical 138-byte RFC 5915 + PKCS#8 `PrivateKeyInfo` (with direct `ECPrivateKeySpec` /
 *   `ECPublicKeySpec` fallback so zero OEM ASN.1 parser bugs can ever occur).
 * - Self-tests every generated/loaded keypair (`SPKI == 91 bytes` + DER `0x30` signature + verify).
 * - Provides AES-256-GCM sealing (`tm3pool:v1:...`) for password-free pre-activated pool bundles.
 */
object TimeMoviesCrypto {

    const val API_BASE_URL = "https://api.apks.lol/api/v4.7/app/"
    const val MEDIA_V2_BASE_URL = "https://api.apks.lol/v2/media/"
    const val DOOM_BASE_URL = "https://api.apks.lol/api/player/v1.6/app/"

    const val APP_PACKAGE = "com.extremeqr.proapps"
    const val APP_VERSION_NAME = "1.0.6.5"
    const val APP_BUILD = "1092"
    const val APP_ID = "60129f949ea4ea03d6598c06"
    const val APP_VERSION_ID = "6a3e1c72b864f954c0ef8d54"
    const val APP_VERSION_TOKEN = APP_VERSION_ID
    const val DEVICE_SIGNATURE_ALGORITHM = "ECDSA_SHA256_DER"
    const val SIGNING_ALGORITHM = DEVICE_SIGNATURE_ALGORITHM

    const val DOOM_PLAYER_BUILD = "1065"
    const val DOOM_PLAYER_APP_ID = "6a5216d91e12eeadc194ab91"
    const val DOOM_BUILD = DOOM_PLAYER_BUILD
    const val DOOM_APP_ID = DOOM_PLAYER_APP_ID

    const val POOL_BUNDLE_PREFIX = "tm3pool:v1:"
    private const val POOL_BUNDLE_AAD = "TimeMoviesPoolV3"

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val HEX_CHARS = "0123456789abcdef"

    /**
     * Exact 27-byte ASN.1 DER header of a 91-byte `secp256r1` (`1.2.840.10045.3.1.7`) X.509 SPKI:
     * `30 59 30 13 06 07 2a 86 48 ce 3d 02 01 06 08 2a 86 48 ce 3d 03 01 07 03 42 00 04`
     */
    private val P256_SPKI_PREFIX: ByteArray = hexToBytes(
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
    )

    /**
     * Exact 36-byte ASN.1 DER prefix and 6-byte middle tag of a canonical 138-byte PKCS#8 P-256 key:
     * `30 81 87 02 01 00 30 13 06 07 2a8648ce3d0201 06 08 2a8648ce3d030107 04 6d 30 6b 02 01 01 04 20 <d:32> a1 44 03 42 00 04 <X:32> <Y:32>`
     */
    private val P256_PKCS8_PREFIX: ByteArray = hexToBytes(
        "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420"
    )
    private val P256_PKCS8_PUB_TAG: ByteArray = hexToBytes("a14403420004")

    /**
     * Standard FIPS 186-4 NIST P-256 (`secp256r1` / `prime256v1`) mathematical curve parameters.
     */
    val P256_PARAMETER_SPEC: ECParameterSpec by lazy {
        val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val a = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
        val b = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        val gx = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
        val gy = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
        val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val curve = EllipticCurve(ECFieldFp(p), a, b)
        ECParameterSpec(curve, ECPoint(gx, gy), n, 1)
    }

    /**
     * Paths exempt from ECDSA device-key enrollment/signing (`u86.a` + enrollment endpoints).
     */
    private val EXEMPT_SEGMENTS: Set<String> = setOf(
        "d4f34a2a4f0249bbb3c92a7e45f3e1c2", // RefreshToken
        "f9e1a68e9d0c4f8ab4be63f0ed3aa1a4", // SocialSignIn
        "c205267f10be4a52a21ed4e7fa890263", // UserEmailSingIn
        "983fd0915e2841ec914088970dc6f059", // UserEmailSignUp
        "2b1ea8f4c8174afb90d75f29e38c1ab3", // Resend / Forgot
        "5a0c0b8e7c3248f1a2d3e4f5b6c7d8e9", // UserUpdatePassword
        "c7f4a2e91b8d43a6ae05f7c2d9b16e30",
        "c9459fd0ac295d0f93237e370e6de40f", // AdDiagnosticRequest
        "528e9d0f958445e68b76f657a75ea1d4",
        "b05a119684b640ea8589ec4134dadefa", // AST (AppSettings)
        "ef91fe43a60d496598d95cf8e3c1f139", // List<VUD>
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
        val scheme: String = "timesnizz",
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
        // Check `u86.a` exempt endpoints too so we never needlessly block/sign exempt auth calls
        if (url.pathSegments.any { it in EXEMPT_SEGMENTS }) return false
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

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun sha256Bytes(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun sha256Hex(text: String): String {
        return bytesToHex(sha256Bytes(text.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Pure-Kotlin Base64URL encoder without padding (`=`), identical to `zy1.l(byte[])` (`Base64.NO_PADDING | NO_WRAP | URL_SAFE`).
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

    // ---- Cross-Platform Provider-Pinned Canonical NIST P-256 (`secp256r1`) ECDSA ----

    private fun orderedEcProviders(): List<Provider?> {
        val installed = Security.getProviders().orEmpty()
        val preferredNames = listOf("AndroidOpenSSL", "GmsCore_OpenSSL", "Conscrypt", "SunEC")
        val result = ArrayList<Provider?>(installed.size + 1)

        for (name in preferredNames) {
            installed.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { result.add(it) }
        }
        for (p in installed) {
            val n = p.name.orEmpty()
            if (n.contains("KeyStore", ignoreCase = true)) continue
            if (n.equals("BC", ignoreCase = true)) continue
            if (p !in result) result.add(p)
        }
        // `null` = JCA default lookup, then Android `BC` dead-last only if every native provider is absent
        result.add(null)
        installed.firstOrNull { it.name.equals("BC", ignoreCase = true) }?.let { result.add(it) }
        return result
    }

    private fun bigIntToFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
        }
    }

    fun buildCanonicalP256Spki(x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(91)
        System.arraycopy(P256_SPKI_PREFIX, 0, out, 0, 27)
        System.arraycopy(bigIntToFixed32(x), 0, out, 27, 32)
        System.arraycopy(bigIntToFixed32(y), 0, out, 59, 32)
        return out
    }

    fun buildCanonicalP256Pkcs8(d: BigInteger, x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(138)
        System.arraycopy(P256_PKCS8_PREFIX, 0, out, 0, 36)
        System.arraycopy(bigIntToFixed32(d), 0, out, 36, 32)
        System.arraycopy(P256_PKCS8_PUB_TAG, 0, out, 68, 6)
        System.arraycopy(bigIntToFixed32(x), 0, out, 74, 32)
        System.arraycopy(bigIntToFixed32(y), 0, out, 106, 32)
        return out
    }

    fun isCanonical91ByteP256Spki(spkiBytes: ByteArray): Boolean {
        if (spkiBytes.size != 91) return false
        for (i in P256_SPKI_PREFIX.indices) {
            if (spkiBytes[i] != P256_SPKI_PREFIX[i]) return false
        }
        return true
    }

    private fun isCanonical138ByteP256Pkcs8(pkcs8Bytes: ByteArray): Boolean {
        if (pkcs8Bytes.size != 138) return false
        for (i in P256_PKCS8_PREFIX.indices) {
            if (pkcs8Bytes[i] != P256_PKCS8_PREFIX[i]) return false
        }
        for (i in P256_PKCS8_PUB_TAG.indices) {
            if (pkcs8Bytes[68 + i] != P256_PKCS8_PUB_TAG[i]) return false
        }
        return true
    }

    /**
     * Generates a canonical, self-tested NIST P-256 (`secp256r1`) keypair guaranteed to emit an
     * exact 91-byte X.509 SPKI public key (`ro.java` / OpenSSL 3.0 compatible) and 138-byte PKCS#8 private key.
     */
    fun generateP256KeyPair(): EcKeyMaterial {
        var lastError: Throwable? = null
        for (provider in orderedEcProviders()) {
            val candidate = runCatching {
                val kpg = if (provider != null) {
                    KeyPairGenerator.getInstance("EC", provider)
                } else {
                    KeyPairGenerator.getInstance("EC")
                }
                val initialized = runCatching {
                    kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
                }.recoverCatching {
                    kpg.initialize(ECGenParameterSpec("prime256v1"), SecureRandom())
                }.recoverCatching {
                    kpg.initialize(P256_PARAMETER_SPEC, SecureRandom())
                }
                initialized.getOrThrow()

                val kp = kpg.generateKeyPair()
                val ecPub = kp.public as? ECPublicKey
                    ?: extractEcPublicKey(kp.public, provider)
                val ecPrivScalar = (kp.private as? ECPrivateKey)?.s
                    ?: extractEcPrivateScalar(kp.private, provider)

                val spki91 = buildCanonicalP256Spki(ecPub.w.affineX, ecPub.w.affineY)
                val pkcs8 = if (ecPrivScalar != null) {
                    buildCanonicalP256Pkcs8(ecPrivScalar, ecPub.w.affineX, ecPub.w.affineY)
                } else {
                    kp.private.encoded ?: error("Private key is not exportable")
                }

                val mat = EcKeyMaterial(
                    privateKeyPkcs8B64Url = base64UrlEncodeNoPad(pkcs8),
                    publicKeySpkiB64Url = base64UrlEncodeNoPad(spki91),
                )
                check(isValidP256KeyPair(mat)) { "Generated P-256 keypair failed self-test" }
                mat
            }.onFailure { lastError = it }.getOrNull()

            if (candidate != null) return candidate
        }
        throw IllegalStateException("Unable to generate P-256 ECDSA keypair on this device", lastError)
    }

    private fun extractEcPublicKey(pub: PublicKey, provider: Provider?): ECPublicKey {
        val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
        val spec = kf.getKeySpec(pub, ECPublicKeySpec::class.java)
        return kf.generatePublic(spec) as ECPublicKey
    }

    private fun extractEcPrivateScalar(priv: PrivateKey, provider: Provider?): BigInteger? = runCatching {
        val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
        kf.getKeySpec(priv, ECPrivateKeySpec::class.java).s
    }.getOrNull()

    /**
     * Validates and canonicalizes an existing stored keypair (`spki == 91 bytes` + sign/verify self-test).
     * Returns `null` if a legacy keypair is malformed so `TimeMoviesPrefs` can cleanly heal it.
     */
    fun normalizeOrValidateKeyPair(privB64Url: String, pubB64Url: String): EcKeyMaterial? = runCatching {
        val rawPub = base64DecodeFlexible(pubB64Url)
        val rawPriv = base64DecodeFlexible(privB64Url)
        if (rawPub.isEmpty() || rawPriv.isEmpty()) return null

        val pubKey = loadPublicKey(pubB64Url) as? ECPublicKey ?: return null
        val canonicalSpki = if (isCanonical91ByteP256Spki(rawPub)) {
            rawPub
        } else {
            buildCanonicalP256Spki(pubKey.w.affineX, pubKey.w.affineY)
        }

        val privKey = loadPrivateKey(privB64Url)
        val canonicalPkcs8 = if (isCanonical138ByteP256Pkcs8(rawPriv)) {
            rawPriv
        } else {
            val scalar = (privKey as? ECPrivateKey)?.s
            if (scalar != null) {
                buildCanonicalP256Pkcs8(scalar, pubKey.w.affineX, pubKey.w.affineY)
            } else {
                rawPriv
            }
        }

        val normalized = EcKeyMaterial(
            privateKeyPkcs8B64Url = base64UrlEncodeNoPad(canonicalPkcs8),
            publicKeySpkiB64Url = base64UrlEncodeNoPad(canonicalSpki),
        )
        if (isValidP256KeyPair(normalized)) normalized else null
    }.getOrNull()

    fun isValidP256KeyPair(keyPair: EcKeyMaterial): Boolean = runCatching {
        val spki = base64DecodeFlexible(keyPair.publicKeySpkiB64Url)
        if (!isCanonical91ByteP256Spki(spki)) return false
        val probe = "timemovies-v47-key-selftest".toByteArray(Charsets.UTF_8)
        val sigB64 = signEcdsaSha256DerB64Url(keyPair.privateKeyPkcs8B64Url, probe)
        val sigBytes = base64DecodeFlexible(sigB64)
        if (sigBytes.size < 64 || sigBytes[0] != 0x30.toByte()) return false
        verifyEcdsaSha256DerB64Url(keyPair.publicKeySpkiB64Url, probe, sigB64)
    }.getOrDefault(false)

    fun loadPrivateKey(pkcs8B64Url: String): PrivateKey {
        val der = base64DecodeFlexible(pkcs8B64Url)
        val directScalarSpec = if (isCanonical138ByteP256Pkcs8(der)) {
            val d = BigInteger(1, der.copyOfRange(36, 68))
            ECPrivateKeySpec(d, P256_PARAMETER_SPEC)
        } else {
            null
        }

        var lastError: Throwable? = null
        for (provider in orderedEcProviders()) {
            val loaded = runCatching {
                val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
                runCatching {
                    kf.generatePrivate(PKCS8EncodedKeySpec(der))
                }.recoverCatching {
                    val spec = directScalarSpec ?: throw it
                    kf.generatePrivate(spec)
                }.getOrThrow()
            }.onFailure { lastError = it }.getOrNull()
            if (loaded != null) return loaded
        }
        throw IllegalStateException("Failed to load P-256 private key", lastError)
    }

    fun loadPublicKey(spkiB64Url: String): PublicKey {
        val der = base64DecodeFlexible(spkiB64Url)
        val directPointSpec = if (isCanonical91ByteP256Spki(der)) {
            val x = BigInteger(1, der.copyOfRange(27, 59))
            val y = BigInteger(1, der.copyOfRange(59, 91))
            ECPublicKeySpec(ECPoint(x, y), P256_PARAMETER_SPEC)
        } else {
            null
        }

        var lastError: Throwable? = null
        for (provider in orderedEcProviders()) {
            val loaded = runCatching {
                val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
                runCatching {
                    kf.generatePublic(X509EncodedKeySpec(der))
                }.recoverCatching {
                    val spec = directPointSpec ?: throw it
                    kf.generatePublic(spec)
                }.getOrThrow()
            }.onFailure { lastError = it }.getOrNull()
            if (loaded != null) return loaded
        }
        throw IllegalStateException("Failed to load P-256 public key", lastError)
    }

    /**
     * Converts a 64-byte IEEE P1363 `(r || s)` signature into strict ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }`
     * if any exotic provider returned P1363 instead of DER.
     */
    private fun ensureAsn1DerEcdsaSignature(rawSig: ByteArray): ByteArray {
        if (rawSig.isNotEmpty() && rawSig[0] == 0x30.toByte()) {
            return rawSig
        }
        if (rawSig.size == 64) {
            val r = BigInteger(1, rawSig.copyOfRange(0, 32)).toByteArray()
            val s = BigInteger(1, rawSig.copyOfRange(32, 64)).toByteArray()
            val bodyLen = 2 + r.size + 2 + s.size
            val out = ByteArrayOutputStream(2 + bodyLen)
            out.write(0x30)
            out.write(bodyLen)
            out.write(0x02)
            out.write(r.size)
            out.write(r)
            out.write(0x02)
            out.write(s.size)
            out.write(s)
            return out.toByteArray()
        }
        return rawSig
    }

    fun signEcdsaSha256DerB64Url(privateKeyPkcs8B64Url: String, messageBytes: ByteArray): String {
        val der = base64DecodeFlexible(privateKeyPkcs8B64Url)
        val directScalarSpec = if (isCanonical138ByteP256Pkcs8(der)) {
            ECPrivateKeySpec(BigInteger(1, der.copyOfRange(36, 68)), P256_PARAMETER_SPEC)
        } else {
            null
        }

        var lastError: Throwable? = null
        // Always load PrivateKey and init Signature on the EXACT SAME Provider instance first!
        for (provider in orderedEcProviders()) {
            val signed = runCatching {
                val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
                val privKey = runCatching {
                    kf.generatePrivate(PKCS8EncodedKeySpec(der))
                }.recoverCatching {
                    val spec = directScalarSpec ?: throw it
                    kf.generatePrivate(spec)
                }.getOrThrow()

                val sig = if (provider != null) {
                    Signature.getInstance("SHA256withECDSA", provider)
                } else {
                    Signature.getInstance("SHA256withECDSA")
                }
                sig.initSign(privKey)
                sig.update(messageBytes)
                val derSig = ensureAsn1DerEcdsaSignature(sig.sign())
                check(derSig.isNotEmpty() && derSig[0] == 0x30.toByte()) { "Non-DER ECDSA signature" }
                base64UrlEncodeNoPad(derSig)
            }.onFailure { lastError = it }.getOrNull()

            if (signed != null) return signed
        }
        throw IllegalStateException("ECDSA P-256 signing failed across all JCA providers", lastError)
    }

    fun verifyEcdsaSha256DerB64Url(
        publicKeySpkiB64Url: String,
        messageBytes: ByteArray,
        signatureB64Url: String,
    ): Boolean {
        val sigBytes = ensureAsn1DerEcdsaSignature(base64DecodeFlexible(signatureB64Url))
        val derPub = base64DecodeFlexible(publicKeySpkiB64Url)
        val directPointSpec = if (isCanonical91ByteP256Spki(derPub)) {
            val x = BigInteger(1, derPub.copyOfRange(27, 59))
            val y = BigInteger(1, derPub.copyOfRange(59, 91))
            ECPublicKeySpec(ECPoint(x, y), P256_PARAMETER_SPEC)
        } else {
            null
        }

        for (provider in orderedEcProviders()) {
            val verified = runCatching {
                val kf = if (provider != null) KeyFactory.getInstance("EC", provider) else KeyFactory.getInstance("EC")
                val pubKey = runCatching {
                    kf.generatePublic(X509EncodedKeySpec(derPub))
                }.recoverCatching {
                    val spec = directPointSpec ?: throw it
                    kf.generatePublic(spec)
                }.getOrThrow()

                val sig = if (provider != null) {
                    Signature.getInstance("SHA256withECDSA", provider)
                } else {
                    Signature.getInstance("SHA256withECDSA")
                }
                sig.initVerify(pubKey)
                sig.update(messageBytes)
                sig.verify(sigBytes)
            }.getOrNull()

            if (verified != null) return verified
        }
        return false
    }

    // ---- AES-256-GCM Pool Session Bundle Sealing (`tm3pool:v1:...`) ----

    private fun derivePoolBundleAesKey(): SecretKeySpec {
        val seed = "$POOL_BUNDLE_AAD|$APP_ID|$APP_VERSION_ID|$APP_PACKAGE".toByteArray(Charsets.UTF_8)
        return SecretKeySpec(sha256Bytes(seed), "AES")
    }

    fun sealPoolBundleJson(jsonPayload: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derivePoolBundleAesKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(POOL_BUNDLE_AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(jsonPayload.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return POOL_BUNDLE_PREFIX + base64UrlEncodeNoPad(combined)
    }

    fun unsealPoolBundleJson(bundleLine: String): String? = runCatching {
        val trimmed = bundleLine.trim()
        if (!trimmed.startsWith(POOL_BUNDLE_PREFIX)) return null
        val raw = base64DecodeFlexible(trimmed.removePrefix(POOL_BUNDLE_PREFIX))
        if (raw.size <= 12 + 16) return null
        val iv = raw.copyOfRange(0, 12)
        val cipherText = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derivePoolBundleAesKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(POOL_BUNDLE_AAD.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Builds the enrollment challenge signature payload (`np0.N` in `1092-p1`):
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
        val detectedScheme = trimmed.substringBefore("://", "timesnizz").lowercase(Locale.US)
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
                    scheme = detectedScheme,
                )
            }
        }

        // Format B: `timesnizz://doomplayer.com/<base64UrlPayload>` or `timesdizz://doomplayer.com/<base64UrlPayload>`
        val decoded = runCatching {
            String(base64DecodeFlexible(afterHost), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        // Query-style payload inside base64 (`NavGraphKt.parseDeepPlayerQueryArgs`)
        if (
            (
                decoded.startsWith("src=") ||
                    decoded.contains("&src=") ||
                    decoded.startsWith("vidUrl=") ||
                    decoded.contains("&vidUrl=") ||
                    decoded.startsWith("url=") ||
                    decoded.contains("&url=")
                ) && '/' !in decoded.substringBefore('=')
        ) {
            val map = decoded.removePrefix("?").split('&').mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null else kv.substring(0, idx) to safeUrlDecode(kv.substring(idx + 1)).orEmpty()
            }.toMap()
            val src = map["src"]?.ifBlank { null }
                ?: map["vidUrl"]?.ifBlank { null }
                ?: map["url"]?.ifBlank { null }
                ?: return null
            val ieFlag = map["ie"]?.lowercase(Locale.US)
            val isTicket = when (ieFlag) {
                "true", "1" -> true
                "false", "0" -> false
                else -> !src.startsWith("http://", ignoreCase = true) && !src.startsWith("https://", ignoreCase = true)
            }
            return DoomDeepLinkPayload(
                srcOrUrl = src,
                title = map["title"],
                profileId = map["pid"] ?: map["profileId"] ?: map["p"],
                workId = map["wk"] ?: map["workId"] ?: map["w"],
                episodeId = (map["ep"] ?: map["episodeId"] ?: map["e"])?.takeUnless { it == "@" },
                isEncryptedTicket = isTicket,
                scheme = detectedScheme,
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
            scheme = detectedScheme,
        )
    }

    private fun safeUrlDecode(value: String?): String? {
        if (value == null) return null
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
