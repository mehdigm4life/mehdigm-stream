package com.cimanow

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure-Kotlin cryptographic & deobfuscation suite for CimaNow:
 * 1. `rm.freex2line.online` gateway repeating-XOR vault unpacker + HMAC-SHA256 `get-link.php` signer.
 * 2. Polymorphic `/watching/?token=...` stage-2 uninterleaver, V8-compatible arithmetic evaluator,
 *    CSS Flexbox `<x-fx>` layout height solver, LCG key derivation, custom RC4 stream cipher,
 *    `_restoreTabs` attribute decryptor, and `#vault_script` `secureCoreToken` (`_getCoreToken`) decoder.
 * 3. `core.php?action=switch` FNV-1a base-36 request signer (`_s`) & `__cnBootFrame` (`data-cnk`/`data-cna`) decryptor.
 */
object CimaNowCrypto {

    data class ServerTab(
        val name: String,
        val dataId: String,
        val dataIndex: String,
    )

    data class DownloadEntry(
        val url: String,
        val label: String,
        val qualityHint: Int?,
    )

    data class DecodedWatchingPage(
        val html: String,
        val coreToken: String,
        val sigSalt: String,
        val initialEmbedUrl: String?,
        val serverTabs: List<ServerTab>,
        val downloads: List<DownloadEntry>,
    )

    class WatchingVaultHandle(
        val decoded: DecodedWatchingPage,
        private val urlSigner: ((String) -> String)? = null,
        private val frameDecoder: ((String) -> String?)? = null,
    ) {
        fun signCoreSwitchUrl(rawCoreUrl: String, tab: ServerTab): String {
            urlSigner?.let { signer ->
                val signed = runCatching { signer(rawCoreUrl) }.getOrNull()
                if (!signed.isNullOrBlank() && signed != rawCoreUrl) return signed
            }
            val sig = calcCoreSig(decoded.coreToken, tab.dataId, tab.dataIndex, decoded.sigSalt)
            val encSig = URLEncoder.encode(sig, "UTF-8")
            return "$rawCoreUrl&_s=$encSig"
        }

        fun decodeCoreIframe(fragmentHtml: String): String? {
            frameDecoder?.let { dec ->
                val res = runCatching { dec(fragmentHtml) }.getOrNull()
                if (!res.isNullOrBlank()) return res
            }
            return decodeCnBootFrame(fragmentHtml, decoded.coreToken)
        }
    }

    // ── Cross-Platform Base64 Helpers (Android API 21..35 + JVM JUnit) ──────

    fun b64Decode(input: String): ByteArray {
        val clean = input.trim()
        if (clean.isEmpty()) return ByteArray(0)
        runCatching {
            val out = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            if (out != null && out.isNotEmpty()) return out
        }
        return java.util.Base64.getMimeDecoder().decode(clean)
    }

    fun b64UrlDecode(input: String): ByteArray {
        val clean = input.trim()
        if (clean.isEmpty()) return ByteArray(0)
        val std = clean.replace('-', '+').replace('_', '/').let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }
        return b64Decode(std)
    }

    fun b64EncodeNoWrap(bytes: ByteArray): String {
        runCatching {
            val out = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            if (!out.isNullOrEmpty() || bytes.isEmpty()) return out
        }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    fun b64UrlNoPad(bytes: ByteArray): String =
        b64EncodeNoWrap(bytes).replace('+', '-').replace('/', '_').trimEnd('=')

    fun safeJsonObject(raw: String?): JSONObject? {
        val t = raw?.trim() ?: return null
        val braceIdx = t.indexOf('{')
        if (braceIdx < 0 || !t.endsWith("}")) return null
        val slice = if (braceIdx == 0) t else t.substring(braceIdx)
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    // ── 1. `rm.freex2line.online` Gateway Unpacker & HMAC-SHA256 Signer ─────

    fun solveGatewayPostBody(vaultHtml: String): String? {
        val arrMatch = Regex("""(?:window\[["'][^"']+["']\]|var\s+_0x[A-Za-z0-9_]+)\s*=\s*\[""").find(vaultHtml)
            ?: return null
        val openBracket = arrMatch.range.last
        if (openBracket < 0 || vaultHtml[openBracket] != '[') return null

        val maxSliceEnd = minOf(vaultHtml.length, openBracket + 24576)
        val closeBracket = vaultHtml.indexOf(']', openBracket + 1)
        val reachedArrayEnd = closeBracket in (openBracket + 1)..maxSliceEnd
        val sliceEnd = if (reachedArrayEnd) closeBracket else maxSliceEnd
        val headSlice = vaultHtml.substring(openBracket + 1, sliceEnd)

        val rawJoined = buildString {
            for (m in Regex("""["']([A-Za-z0-9+/=]+)["']""").findAll(headSlice)) {
                append(m.groupValues[1])
            }
        }
        val b64Joined = if (reachedArrayEnd) {
            rawJoined
        } else {
            // Align truncated prefix down to a 4-char Base64 quantum so JVM Base64 never rejects `len % 4 == 1`
            rawJoined.substring(0, (rawJoined.length / 4) * 4)
        }
        if (b64Joined.length < 32) return null

        val raw = runCatching { b64Decode(b64Joined) }.getOrNull() ?: return null
        val needle = "<script>".toByteArray(Charsets.UTF_8)
        if (raw.size < needle.size + 16) return null

        for (off in 0..8) {
            for (klen in 1..12) {
                val key = ByteArray(klen)
                for (j in 0 until minOf(klen, needle.size)) {
                    key[(off + j) % klen] = (raw[off + j].toInt() xor needle[j].toInt()).toByte()
                }
                var matchesNeedle = true
                for (j in needle.indices) {
                    if ((raw[off + j].toInt() xor key[(off + j) % klen].toInt()).toByte() != needle[j]) {
                        matchesNeedle = false
                        break
                    }
                }
                if (!matchesNeedle) continue

                val decodedBytes = ByteArray(raw.size) { idx ->
                    (raw[idx].toInt() xor key[idx % klen].toInt()).toByte()
                }
                val topDecoded = String(decodedBytes, Charsets.UTF_8)
                if (!topDecoded.contains("<script", true) || !topDecoded.contains("window.")) continue

                val scriptContent = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
                    .find(topDecoded)?.groupValues?.get(1) ?: topDecoded
                val mapBlock = Regex("""window\.map_[A-Za-z0-9_]+\s*=\s*\{([\s\S]*?)\}""")
                    .find(scriptContent)?.groupValues?.get(1) ?: continue

                fun mapProp(prop: String): String? =
                    Regex("""['"]?${Regex.escape(prop)}['"]?\s*:\s*['"]([^'"]+)['"]""").find(mapBlock)?.groupValues?.get(1)

                val chVar = mapProp("ch") ?: continue
                val riVar = mapProp("ri") ?: continue
                val keVar = mapProp("ke") ?: continue
                val seVar = mapProp("se") ?: continue

                fun lookupVal(varName: String): String? =
                    Regex("""['"]${Regex.escape(varName)}['"]\s*:\s*['"]([^'"]+)['"]""").find(scriptContent)?.groupValues?.get(1)

                val ch = lookupVal(chVar) ?: continue
                val ri = lookupVal(riVar) ?: continue
                val ke = lookupVal(keVar) ?: continue
                val se = lookupVal(seVar) ?: continue

                val keBytes = runCatching { b64Decode(ke) }.getOrNull() ?: continue
                val seBytes = se.toByteArray(Charsets.UTF_8)
                if (seBytes.isEmpty()) continue

                val secretBytes = ByteArray(keBytes.size) { idx ->
                    (keBytes[idx].toInt() xor seBytes[idx % seBytes.size].toInt()).toByte()
                }
                val fp = "TW96aWxsYS81LjQ4"
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
                val hmacB64 = b64EncodeNoWrap(mac.doFinal((ri + ch + fp).toByteArray(Charsets.UTF_8)))

                fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
                return "request_id=${enc(ri)}&hmac_token=${enc(hmacB64)}&ch=${enc(ch)}&fp=${enc(fp)}"
            }
        }
        return null
    }

    // ── 2. Polymorphic Stage-2 Arithmetic Evaluator (V8-Equivalent) ──────────

    fun uninterleave(raw: String, step: Int): String {
        if (step <= 0) return raw
        val sp = raw.indexOf('.')
        if (sp == -1) return raw
        val ev = raw.substring(0, sp).reversed()
        val od = raw.substring(sp + 1)
        val tot = ev.length + od.length
        val sb = StringBuilder(tot)
        var ei = 0
        var oi = 0
        for (i in 0 until tot) {
            if (i % step == 0) {
                if (ei < ev.length) sb.append(ev[ei++])
            } else {
                if (oi < od.length) sb.append(od[oi++])
            }
        }
        return sb.toString()
    }

    fun evalMathExpr(rawExpr: String): Long {
        val preprocessed = rawExpr
            .replace(Regex("""typeof\s+window\.decodeURIComponent\s*===\s*['"]function['"]\s*\?\s*1\s*:\s*0"""), "1")
            .replace(Regex("""\b_envOk\b"""), "1")
            .replace(Regex("""'([^']*)'\.indexOf\('([^']*)'\)""")) { m ->
                m.groupValues[1].indexOf(m.groupValues[2]).toString()
            }
            .replace(Regex(""""([^"]*)"\.indexOf\("([^"]*)"\)""")) { m ->
                m.groupValues[1].indexOf(m.groupValues[2]).toString()
            }
            .replace(Regex("""['"]([^'"]*)['"]\.length""")) { m ->
                m.groupValues[1].length.toString()
            }
            .replace(Regex("""['"]([^'"]+)['"]\.charCodeAt\((\d+)\)""")) { m ->
                val str = m.groupValues[1]
                val idx = m.groupValues[2].toIntOrNull() ?: 0
                (if (idx in str.indices) str[idx].code else 0).toString()
            }
            .replace(Regex("""parseInt\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*\)""")) { m ->
                val rad = (m.groupValues[2].toIntOrNull() ?: 10).coerceIn(2, 36)
                (m.groupValues[1].trim().toLongOrNull(rad) ?: 0L).toString()
            }

        val parser = MathExprParser(preprocessed)
        return parser.parse().toLong()
    }

    fun evalMathListSum(listExpr: String): Long {
        var depth = 0
        var start = 0
        var total = 0L
        for (i in listExpr.indices) {
            when (listExpr[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    val seg = listExpr.substring(start, i).trim()
                    if (seg.isNotEmpty()) total += evalMathExpr(seg)
                    start = i + 1
                }
            }
        }
        val tail = listExpr.substring(start).trim()
        if (tail.isNotEmpty()) total += evalMathExpr(tail)
        return total
    }

    private class MathExprParser(private val s: String) {
        private var pos = 0

        fun parse(): Double = parseBitOr()

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun parseBitOr(): Double {
            var v = parseBitXor()
            while (true) {
                skipWs()
                if (pos < s.length && s[pos] == '|' && (pos + 1 >= s.length || s[pos + 1] != '|')) {
                    pos++
                    val r = parseBitXor()
                    v = (v.toLong().toInt() or r.toLong().toInt()).toDouble()
                } else {
                    break
                }
            }
            return v
        }

        private fun parseBitXor(): Double {
            var v = parseBitAnd()
            while (true) {
                skipWs()
                if (pos < s.length && s[pos] == '^') {
                    pos++
                    val r = parseBitAnd()
                    v = (v.toLong().toInt() xor r.toLong().toInt()).toDouble()
                } else {
                    break
                }
            }
            return v
        }

        private fun parseBitAnd(): Double {
            var v = parseShift()
            while (true) {
                skipWs()
                if (pos < s.length && s[pos] == '&' && (pos + 1 >= s.length || s[pos + 1] != '&')) {
                    pos++
                    val r = parseShift()
                    v = (v.toLong().toInt() and r.toLong().toInt()).toDouble()
                } else {
                    break
                }
            }
            return v
        }

        private fun parseShift(): Double {
            var v = parseAdd()
            while (true) {
                skipWs()
                when {
                    s.startsWith(">>>", pos) -> {
                        pos += 3
                        val r = parseAdd().toLong().toInt() and 31
                        v = ((v.toLong().toInt() ushr r).toLong() and 0xFFFFFFFFL).toDouble()
                    }
                    s.startsWith("<<", pos) -> {
                        pos += 2
                        val r = parseAdd().toLong().toInt() and 31
                        v = (v.toLong().toInt() shl r).toDouble()
                    }
                    s.startsWith(">>", pos) -> {
                        pos += 2
                        val r = parseAdd().toLong().toInt() and 31
                        v = (v.toLong().toInt() shr r).toDouble()
                    }
                    else -> break
                }
            }
            return v
        }

        private fun parseAdd(): Double {
            var v = parseMul()
            while (true) {
                skipWs()
                if (pos < s.length && s[pos] == '+') {
                    pos++
                    v += parseMul()
                } else if (pos < s.length && s[pos] == '-') {
                    pos++
                    v -= parseMul()
                } else {
                    break
                }
            }
            return v
        }

        private fun parseMul(): Double {
            var v = parseUnary()
            while (true) {
                skipWs()
                if (pos < s.length && s[pos] == '*') {
                    pos++
                    v *= parseUnary()
                } else if (pos < s.length && s[pos] == '/') {
                    pos++
                    v /= parseUnary()
                } else if (pos < s.length && s[pos] == '%') {
                    pos++
                    v %= parseUnary()
                } else {
                    break
                }
            }
            return v
        }

        private fun parseUnary(): Double {
            skipWs()
            if (pos < s.length && s[pos] == '+') {
                pos++
                return parseUnary()
            }
            if (pos < s.length && s[pos] == '-') {
                pos++
                return -parseUnary()
            }
            if (pos < s.length && s[pos] == '~') {
                pos++
                return parseUnary().toLong().toInt().inv().toDouble()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = parseBitOr()
                skipWs()
                if (pos < s.length && s[pos] == ')') pos++
                return v
            }
            if (s.startsWith("Math.", pos)) {
                pos += 5
                val fnStart = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val fn = s.substring(fnStart, pos)
                skipWs()
                if (pos < s.length && s[pos] == '(') pos++
                val args = ArrayList<Double>(2)
                args.add(parseBitOr())
                while (true) {
                    skipWs()
                    if (pos < s.length && s[pos] == ',') {
                        pos++
                        args.add(parseBitOr())
                    } else {
                        break
                    }
                }
                skipWs()
                if (pos < s.length && s[pos] == ')') pos++
                return when (fn) {
                    "abs" -> abs(args[0])
                    "min" -> args.fold(Double.POSITIVE_INFINITY) { a, b -> min(a, b) }
                    "max" -> args.fold(Double.NEGATIVE_INFINITY) { a, b -> max(a, b) }
                    "floor" -> floor(args[0])
                    "ceil" -> ceil(args[0])
                    "round" -> args[0].roundToLong().toDouble()
                    else -> args[0]
                }
            }
            if (pos + 2 < s.length && s[pos] == '0' && (s[pos + 1] == 'x' || s[pos + 1] == 'X')) {
                pos += 2
                val hexStart = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] in 'a'..'f' || s[pos] in 'A'..'F')) pos++
                return s.substring(hexStart, pos).toLongOrNull(16)?.toDouble() ?: 0.0
            }
            if (pos + 2 < s.length && s[pos] == '0' && (s[pos + 1] == 'b' || s[pos + 1] == 'B')) {
                pos += 2
                val binStart = pos
                while (pos < s.length && (s[pos] == '0' || s[pos] == '1')) pos++
                return s.substring(binStart, pos).toLongOrNull(2)?.toDouble() ?: 0.0
            }
            if (pos + 2 < s.length && s[pos] == '0' && (s[pos + 1] == 'o' || s[pos + 1] == 'O')) {
                pos += 2
                val octStart = pos
                while (pos < s.length && s[pos] in '0'..'7') pos++
                return s.substring(octStart, pos).toLongOrNull(8)?.toDouble() ?: 0.0
            }
            val numStart = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            val token = s.substring(numStart, pos)
            return token.toDoubleOrNull() ?: 0.0
        }
    }

    // ── 3. Generalized CSS Flexbox Layout Height Solver (`<x-fx>`, `<x-grid>`, any tag) ──

    private data class FlexItem(val order: Int, val domIdx: Int, val outerW: Int, val outerH: Int)

    fun computeFlexHeight(fxHtml: String): Int {
        val containerMatch = Regex("""<[A-Za-z0-9-]+\s+style=['"]([^'"]*display\s*:\s*flex[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            .find(fxHtml)
            ?: Regex("""<x-fx\s+style=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(fxHtml)
            ?: return 0
        val fxStyle = containerMatch.groupValues[1]

        fun pxProp(style: String, prop: String): Int =
            Regex("""(?:^|;)\s*${Regex.escape(prop)}\s*:\s*(-?\d+)px""", RegexOption.IGNORE_CASE)
                .find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        fun boxShorthandPx(style: String, prop: String): Pair<Int, Int> {
            val m = Regex(
                """(?:^|;)\s*${Regex.escape(prop)}\s*:\s*(-?\d+)px(?:\s+(-?\d+)px)?(?:\s+(-?\d+)px)?(?:\s+(-?\d+)px)?""",
                RegexOption.IGNORE_CASE,
            ).find(style) ?: return 0 to 0
            val p1 = m.groupValues[1].toIntOrNull() ?: 0
            val p2 = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: p1
            val p3 = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: p1
            val p4 = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: p2
            return (p1 + p3) to (p2 + p4)
        }

        val fxW = pxProp(fxStyle, "width")
        val (fxPadTotalV, fxPadTotalH) = boxShorthandPx(fxStyle, "padding")
        val fxBorder = pxProp(fxStyle, "border")
        val fxBorderBox = fxStyle.contains("box-sizing:border-box", ignoreCase = true)
        val colGap = pxProp(fxStyle, "column-gap")
        val rowGap = pxProp(fxStyle, "row-gap")
        val availW = if (fxBorderBox) fxW - fxPadTotalH - 2 * fxBorder else fxW

        val items = ArrayList<FlexItem>()
        val childrenSlice = fxHtml.substring(containerMatch.range.last + 1)
        var domIdx = 0
        for (m in Regex("""<[A-Za-z0-9-]+\s+style=['"]([^'"]+)['"]""").findAll(childrenSlice)) {
            val st = m.groupValues[1]
            domIdx++
            if (st.contains("display:none", ignoreCase = true)) continue
            val order = Regex("""order\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
                .find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val borderBox = st.contains("box-sizing:border-box", ignoreCase = true)
            val w = pxProp(st, "width")
            val h = pxProp(st, "height")
            val (padTotalV, padTotalH) = boxShorthandPx(st, "padding")
            val (marTotalV, marTotalH) = boxShorthandPx(st, "margin")
            val bdr = pxProp(st, "border")
            val boxW = if (borderBox) w else w + padTotalH + 2 * bdr
            val boxH = if (borderBox) h else h + padTotalV + 2 * bdr
            items.add(FlexItem(order, domIdx, boxW + marTotalH, boxH + marTotalV))
        }

        items.sortWith(compareBy<FlexItem> { it.order }.thenBy { it.domIdx })

        val rowHeights = ArrayList<Int>()
        var curW = 0
        var curMaxH = 0
        var countInRow = 0
        for (it in items) {
            val needed = if (countInRow == 0) it.outerW else curW + colGap + it.outerW
            if (countInRow > 0 && needed > availW) {
                rowHeights.add(curMaxH)
                curW = it.outerW
                curMaxH = it.outerH
                countInRow = 1
            } else {
                curW = needed
                if (it.outerH > curMaxH) curMaxH = it.outerH
                countInRow++
            }
        }
        if (countInRow > 0) rowHeights.add(curMaxH)

        return fxPadTotalV + 2 * fxBorder + rowHeights.sum() + max(0, rowHeights.size - 1) * rowGap
    }

    // ── 4. Version-Agnostic Rhino `/watching/` Vault Sandbox (V12, V13, V14+) ─

    fun unlockWatchingVault(rawHtml: String): WatchingVaultHandle? {
        unlockWatchingVaultRhino(rawHtml)?.let { return it }
        val legacy = decodeWatchingVaultStaticV12(rawHtml) ?: return null
        return WatchingVaultHandle(legacy)
    }

    fun decodeWatchingVault(rawHtml: String): DecodedWatchingPage? {
        decodeWatchingVaultStaticV12(rawHtml)?.let { return it }
        return unlockWatchingVaultRhino(rawHtml)?.decoded
    }

    @Suppress("unused")
    class RhinoBootBridge(
        private val outerDoc: Document,
        private val bootEl: Element,
        private val captured: MutableList<String>,
    ) {
        fun getAttr(id: String, name: String): String {
            val target = if (id.isNotBlank()) outerDoc.getElementById(id) ?: bootEl else bootEl
            return target.attributes().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
        }

        fun b64(s: String): String = String(b64Decode(s), Charsets.UTF_8)

        fun capture(code: String) {
            if (code.length > 200) captured.add(code)
        }
    }

    @Suppress("unused")
    class RhinoWatchingBridge(private val outerDoc: Document) {
        var decryptedHtml: String = ""
        var decryptedDoc: Document? = null

        fun hasOuterEl(id: String): Boolean = outerDoc.getElementById(id) != null

        fun getOuterAttr(id: String, attr: String): String =
            outerDoc.getElementById(id)?.let { el ->
                el.attributes().firstOrNull { it.key.equals(attr, ignoreCase = true) }?.value
            } ?: ""

        fun b64ToLatin1(s: String): String =
            String(b64Decode(s), Charsets.ISO_8859_1)

        fun utf8FromLatin1(s: String): String =
            String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)

        fun flexHeight(html: String): Int = computeFlexHeight(html)

        fun onDocWrite(html: String) {
            decryptedHtml = html
            decryptedDoc = Jsoup.parse(html)
        }

        // Snapshot matched elements ONCE per querySelectorAll call so removeAttribute()
        // inside _restoreTabs() never shifts subsequent indices!
        fun qsaSnapshot(sel: String): Array<Element> =
            decryptedDoc?.select(sel)?.toTypedArray() ?: emptyArray()

        fun elGetAttr(el: Element, attr: String): String? =
            el.attributes().firstOrNull { it.key.equals(attr, ignoreCase = true) }?.value

        fun elSetAttr(el: Element, attr: String, value: String) {
            val realKey = el.attributes().firstOrNull { it.key.equals(attr, ignoreCase = true) }?.key ?: attr
            el.attr(realKey, value)
        }

        fun elRemoveAttr(el: Element, attr: String) {
            val realKey = el.attributes().firstOrNull { it.key.equals(attr, ignoreCase = true) }?.key ?: attr
            el.removeAttr(realKey)
        }

        fun elHasAttr(el: Element, attr: String): Boolean =
            el.attributes().any { it.key.equals(attr, ignoreCase = true) }
    }

    private fun unpackWatchingBootstrapRhino(outerDoc: Document): String? {
        val bootEl = outerDoc.select("script").firstOrNull { s ->
            s.data().length in 60..16_000 && s.attributes().any { a -> a.value.length > 500 }
        } ?: return null

        val bootId = bootEl.id()
        val captured = ArrayList<String>(2)
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            cx.isInterpretedMode = true
            val scope = cx.initStandardObjects()
            val bootBridge = RhinoBootBridge(outerDoc, bootEl, captured)
            ScriptableObject.putProperty(scope, "__bb", Context.javaToJS(bootBridge, scope))
            val bootShim = """
                var window = this, self = this, globalThis = this;
                function atob(s) { return String(__bb.b64(String(s))); }
                var document = {
                    currentScript: {
                        id: ${JSONObject.quote(bootId)},
                        getAttribute: function(n) { return String(__bb.getAttr(${JSONObject.quote(bootId)}, String(n))); },
                        parentNode: { removeChild: function() {} }
                    },
                    getElementById: function(id) {
                        var sid = String(id);
                        return {
                            id: sid,
                            getAttribute: function(n) { return String(__bb.getAttr(sid, String(n))); },
                            remove: function() {},
                            parentNode: { removeChild: function() {} }
                        };
                    },
                    createElement: function() {
                        return {
                            _t: '',
                            set text(v) { this._t = String(v); __bb.capture(this._t); },
                            set textContent(v) { this._t = String(v); __bb.capture(this._t); },
                            set innerHTML(v) { this._t = String(v); __bb.capture(this._t); }
                        };
                    },
                    head: { appendChild: function(el) { if (el && el._t) __bb.capture(el._t); } },
                    body: { appendChild: function(el) { if (el && el._t) __bb.capture(el._t); } },
                    documentElement: { appendChild: function(el) { if (el && el._t) __bb.capture(el._t); } }
                };
                Function = function() {
                    var body = arguments[arguments.length - 1];
                    __bb.capture(String(body));
                    return function() {};
                };
            """.trimIndent()
            cx.evaluateString(scope, bootShim, "boot_shim.js", 1, null)
            runCatching { cx.evaluateString(scope, bootEl.data(), "boot.js", 1, null) }
        } catch (_: Throwable) {
            return null
        } finally {
            Context.exit()
        }
        return captured.maxByOrNull { it.length }
    }

    private fun unlockWatchingVaultRhino(rawHtml: String): WatchingVaultHandle? {
        if (rawHtml.length < 512) return null
        val outerDoc = Jsoup.parse(rawHtml)
        val s2Code = unpackWatchingBootstrapRhino(outerDoc) ?: return null

        val bridge = RhinoWatchingBridge(outerDoc)
        val rhinoLock = Any()
        val scope: Scriptable

        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ECMASCRIPT
            cx.isInterpretedMode = true
            scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "__h", Context.javaToJS(bridge, scope))

            val s2Shim = """
                var window = this, self = this, globalThis = this;
                var __lastDecodedTk = '';
                function setTimeout() { return 1; }
                function setInterval() { return 1; }
                function clearTimeout() {}
                function clearInterval() {}
                var navigator = {
                    webdriver: false,
                    userAgent: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36'
                };
                function atob(s) { return String(__h.b64ToLatin1(String(s))); }
                function TextDecoder() {}
                TextDecoder.prototype.decode = function(u8) {
                    var CHUNK = 8192, parts = [];
                    for (var i = 0; i < u8.length; i += CHUNK) {
                        var end = Math.min(i + CHUNK, u8.length);
                        var sub = u8.subarray ? u8.subarray(i, end) : Array.prototype.slice.call(u8, i, end);
                        parts.push(String.fromCharCode.apply(null, sub));
                    }
                    return String(__h.utf8FromLatin1(parts.join('')));
                };
                window.TextDecoder = TextDecoder;
                function XMLHttpRequest() { this._url = ''; }
                XMLHttpRequest.prototype.open = function(m, u) { this._url = String(u); };
                XMLHttpRequest.prototype.send = function() {};
                window.XMLHttpRequest = XMLHttpRequest;

                function makeStyleObj() {
                    return {
                        _css: '',
                        set cssText(v) { this._css = String(v); },
                        get cssText() { return this._css; },
                        setProperty: function() {}
                    };
                }
                function makeFlexChild(h) {
                    return {
                        offsetHeight: h, clientHeight: h, scrollHeight: h, offsetWidth: 403,
                        style: makeStyleObj(),
                        getBoundingClientRect: function() { return { height: h, width: 403, top: 0, bottom: h, left: 0, right: 403 }; }
                    };
                }
                function makeMockEl(tag, id) {
                    return {
                        tagName: String(tag || 'DIV').toUpperCase(),
                        id: String(id || ''),
                        style: makeStyleObj(),
                        _ih: '',
                        _fh: 0,
                        parentNode: { removeChild: function() {}, insertBefore: function() {} },
                        appendChild: function(c) { return c; },
                        removeChild: function(c) { return c; },
                        insertBefore: function(c) { return c; },
                        set innerHTML(v) {
                            this._ih = String(v);
                            if (this._ih.indexOf('display:flex') !== -1 || this._ih.indexOf('<x-fx') !== -1) {
                                this._fh = Number(__h.flexHeight(this._ih));
                            }
                        },
                        get innerHTML() { return this._ih; },
                        get offsetHeight() { return this._fh; },
                        get clientHeight() { return this._fh; },
                        get scrollHeight() { return this._fh; },
                        get firstChild() { return makeFlexChild(this._fh); },
                        get firstElementChild() { return makeFlexChild(this._fh); },
                        querySelector: function() { return makeFlexChild(this._fh); },
                        getElementsByTagName: function() { return []; },
                        getBoundingClientRect: function() { return { height: this._fh, width: 403, top: 0, bottom: this._fh, left: 0, right: 403 }; },
                        getAttribute: function(n) { return String(__h.getOuterAttr(this.id, String(n))); },
                        setAttribute: function() {},
                        removeAttribute: function() {},
                        hasAttribute: function() { return false; }
                    };
                }
                window.getComputedStyle = function(el) {
                    var css = (el && el.style && el.style._css) ? el.style._css : '';
                    var res = { height: (el && el._fh ? el._fh : 0) + 'px' };
                    var re = /([a-z-]+)\s*:\s*([^;!]+)/gi, m;
                    while ((m = re.exec(css)) !== null) { res[m[1].trim()] = m[2].trim(); }
                    return res;
                };
                function wrapJsoupEl(jEl) {
                    return {
                        getAttribute: function(a) {
                            var v = __h.elGetAttr(jEl, String(a));
                            return v === null ? null : String(v);
                        },
                        setAttribute: function(a, v) { __h.elSetAttr(jEl, String(a), String(v)); },
                        removeAttribute: function(a) { __h.elRemoveAttr(jEl, String(a)); },
                        hasAttribute: function(a) { return Boolean(__h.elHasAttr(jEl, String(a))); },
                        set src(v) { __h.elSetAttr(jEl, 'src', String(v)); },
                        get src() { var v = __h.elGetAttr(jEl, 'src'); return v === null ? '' : String(v); },
                        set href(v) { __h.elSetAttr(jEl, 'href', String(v)); },
                        get href() { var v = __h.elGetAttr(jEl, 'href'); return v === null ? '' : String(v); }
                    };
                }
                function Document() {}
                Document.prototype.write = function(html) { __h.onDocWrite(String(html)); };
                var document = new Document();
                document.documentElement = makeMockEl('HTML', '');
                document.body = makeMockEl('BODY', '');
                document.addEventListener = function() {};
                document.createDocumentFragment = function() { return makeMockEl('FRAG', ''); };
                document.createElement = function(tag) { return makeMockEl(tag, ''); };
                document.getElementById = function(id) {
                    var sid = String(id);
                    if (__h.hasOuterEl(sid)) return makeMockEl('SCRIPT', sid);
                    return null;
                };
                document.querySelectorAll = function(sel) {
                    var jArr = __h.qsaSnapshot(String(sel));
                    var out = [];
                    for (var i = 0; i < jArr.length; i++) {
                        out.push(wrapJsoupEl(jArr[i]));
                    }
                    return out;
                };
            """.trimIndent()

            cx.evaluateString(scope, s2Shim, "s2_shim.js", 1, null)
            cx.evaluateString(scope, s2Code, "s2_vault.js", 1, null)

            val dDoc = bridge.decryptedDoc ?: return null

            // Snapshot window keys before running inline #vault_script so any newly registered
            // zero-arg token getter (e.g. _getCoreToken or any future renamed getter) is found automatically!
            cx.evaluateString(scope, "var __preKeys = {}; for (var __pk in window) { __preKeys[__pk] = true; }", "pre_keys.js", 1, null)
            for (sc in dDoc.select("script")) {
                val code = sc.data()
                if (sc.id() == "vault_script" || (code.length in 10..2500 && code.contains("window["))) {
                    runCatching { cx.evaluateString(scope, code, "vault_inline.js", 1, null) }
                }
            }
            val tokenExtractJs = """
                (function(){
                    if (typeof window._getCoreToken === 'function') {
                        var t = window._getCoreToken();
                        if (t) { window.tk = t; return String(t); }
                    }
                    if (window.tk) return String(window.tk);
                    for (var k in window) {
                        if (!__preKeys[k] && typeof window[k] === 'function' && window[k].length === 0) {
                            try {
                                var r = window[k]();
                                if (typeof r === 'string' && r.length >= 6) { window.tk = r; return r; }
                            } catch(e) {}
                        }
                    }
                    return '';
                })();
            """.trimIndent()
            val coreToken = cx.evaluateString(scope, tokenExtractJs, "extract_tk.js", 1, null)?.toString()?.trim().orEmpty()

            // Boot any encrypted initial iframe inside #watch
            for (ifEl in dDoc.select("#watch iframe, iframe")) {
                val curSrc = ifEl.attr("src").trim()
                if (curSrc.isEmpty() || curSrc.startsWith("javascript:", true)) {
                    ScriptableObject.putProperty(scope, "__initIfEl", Context.javaToJS(ifEl, scope))
                    runCatching {
                        cx.evaluateString(
                            scope,
                            """
                            (function(){
                                var w = wrapJsoupEl(__initIfEl);
                                for (var k in window) {
                                    if (k.indexOf('__cnBootFrame') === 0 && typeof window[k] === 'function') {
                                        try { window[k](w); } catch(e) {}
                                    }
                                }
                            })();
                            """.trimIndent(),
                            "boot_init_if.js",
                            1,
                            null,
                        )
                    }
                }
            }

            val initialEmbed = dDoc.select("#watch iframe, iframe")
                .firstNotNullOfOrNull { el ->
                    normalizeEmbedUrl(el.attr("src"))
                        ?: decodeCnBootFrame(el.outerHtml(), coreToken)
                }

            val serverTabs = ArrayList<ServerTab>()
            val seenTabs = HashSet<String>()
            for (li in dDoc.select("li[data-id][data-index]")) {
                val dataId = li.attr("data-id").trim()
                val dataIndex = li.attr("data-index").trim()
                val label = li.text().trim()
                // Skip canary honeypot tab (data-id == "00" or data-index == "00") and invalid entries
                if (dataId.isEmpty() || dataIndex.isEmpty() || dataId == "00" || dataIndex == "00" || dataIndex == "undefined") continue
                if (seenTabs.add("$dataId@$dataIndex")) {
                    serverTabs.add(ServerTab(label.ifBlank { "Server $dataIndex" }, dataId, dataIndex))
                }
            }

            val downloads = ArrayList<DownloadEntry>()
            val seenDl = HashSet<String>()
            for (a in dDoc.select("#download a[href], li[aria-label=download] a[href], li[aria-label=quality] a[href]")) {
                val cleaned = normalizeEmbedUrl(
                    a.attr("href")
                        .replace("web2.cnvids.com", "fr.cimanow.cc"),
                ) ?: continue
                if (!seenDl.add(cleaned)) continue
                val text = a.text().trim()
                val qHint = Regex("""(2160|1440|1080|720|480|360|240)p""", RegexOption.IGNORE_CASE)
                    .find("$text $cleaned")?.groupValues?.get(1)?.toIntOrNull()
                downloads.add(DownloadEntry(cleaned, text, qHint))
            }

            val decodedPage = DecodedWatchingPage(
                html = bridge.decryptedHtml,
                coreToken = coreToken,
                sigSalt = "cnV13Sig",
                initialEmbedUrl = initialEmbed,
                serverTabs = serverTabs,
                downloads = downloads,
            )

            val signerFn: (String) -> String = { rawUrl ->
                synchronized(rhinoLock) {
                    val scx = Context.enter()
                    try {
                        scx.languageVersion = Context.VERSION_ECMASCRIPT
                        scx.isInterpretedMode = true
                        ScriptableObject.putProperty(scope, "__rawCoreUrl", rawUrl)
                        scx.evaluateString(
                            scope,
                            "(function(){ var x = new XMLHttpRequest(); x.open('GET', String(__rawCoreUrl)); return String(x._url || __rawCoreUrl); })()",
                            "sign_core.js",
                            1,
                            null,
                        )?.toString() ?: rawUrl
                    } finally {
                        Context.exit()
                    }
                }
            }

            val frameDecoderFn: (String) -> String? = { fragHtml ->
                val fragDoc = Jsoup.parse(fragHtml)
                val ifEl = fragDoc.selectFirst("iframe")
                if (ifEl != null) {
                    synchronized(rhinoLock) {
                        val fcx = Context.enter()
                        try {
                            fcx.languageVersion = Context.VERSION_ECMASCRIPT
                            fcx.isInterpretedMode = true
                            ScriptableObject.putProperty(scope, "__fragIfEl", Context.javaToJS(ifEl, scope))
                            fcx.evaluateString(
                                scope,
                                """
                                (function(){
                                    var w = wrapJsoupEl(__fragIfEl);
                                    for (var k in window) {
                                        if (k.indexOf('__cnBootFrame') === 0 && typeof window[k] === 'function') {
                                            try { window[k](w); } catch(e) {}
                                        }
                                    }
                                })();
                                """.trimIndent(),
                                "decode_frame.js",
                                1,
                                null,
                            )
                        } finally {
                            Context.exit()
                        }
                    }
                    normalizeEmbedUrl(ifEl.attr("src")) ?: decodeCnBootFrame(fragHtml, coreToken)
                } else {
                    decodeCnBootFrame(fragHtml, coreToken)
                }
            }

            return WatchingVaultHandle(
                decoded = decodedPage,
                urlSigner = signerFn,
                frameDecoder = frameDecoderFn,
            )
        } catch (_: Throwable) {
            return null
        } finally {
            Context.exit()
        }
    }

    // ── 4b. Static V12 Pure-Kotlin Vault Decryptor (Fallback & Unit Fixture) ─

    private fun decodeWatchingVaultStaticV12(rawHtml: String): DecodedWatchingPage? {
        val outerDoc = Jsoup.parse(rawHtml)
        val pEl = outerDoc.selectFirst("script[id^=p_]") ?: return null
        val gEl = outerDoc.selectFirst("script[id^=g_]") ?: return null

        val rawP = pEl.attributes().firstOrNull { it.key.startsWith("data-", true) && it.value.length > 64 }?.value
            ?: return null
        val rawG = gEl.attributes().firstOrNull { it.key.startsWith("data-", true) && it.value.length > 64 }?.value
            ?: return null
        val gBootJs = gEl.data()

        val modG = Regex("""%\s*(\d+)""").find(gBootJs)?.groupValues?.get(1)?.toIntOrNull() ?: 6
        val s2 = runCatching { String(b64Decode(uninterleave(rawG, modG)), Charsets.UTF_8) }.getOrNull()
            ?: return null

        val sigSalt = Regex(""":\s*['"](:[^'"]+)['"]""").find(s2)?.groupValues?.get(1)?.removePrefix(":") ?: "cnSig12"
        val rfMul = Regex("""i\s*\*\s*(\d+)\s*\+\s*_tkM2""").find(s2)?.groupValues?.get(1)?.toIntOrNull() ?: 11

        val tValExpr = Regex("""_t\.style\.cssText\s*=\s*['"][^'"]*['"]\s*\+\s*String\(([\s\S]*?)\)\s*\+\s*['"]px!important""")
            .find(s2)?.groupValues?.get(1) ?: return null
        val tVal = evalMathExpr(tValExpr)

        val fxHtml = Regex("""_fx\.innerHTML\s*=\s*"([\s\S]*?)";\s*_host\.appendChild""")
            .find(s2)?.groupValues?.get(1)?.replace("\\'", "'") ?: return null
        val estFlex = computeFlexHeight(fxHtml)

        val listTailExpr = Regex("""_listA\s*=\s*\[_tVal,\s*_flexVal,\s*([\s\S]*?)\];\s*for""")
            .find(s2)?.groupValues?.get(1) ?: return null
        val listTailSum = evalMathListSum(listTailExpr)

        val initSB = evalMathExpr(Regex("""var\s+_sB\s*=\s*([\s\S]*?),\s*_maxR\s*=""").find(s2)?.groupValues?.get(1) ?: return null)
        val maxR = evalMathExpr(Regex(""",\s*_maxR\s*=\s*([\s\S]*?),\s*_cm\s*=""").find(s2)?.groupValues?.get(1) ?: return null)
        val cm = evalMathExpr(Regex(""",\s*_cm\s*=\s*([\s\S]*?),\s*_ca\s*=""").find(s2)?.groupValues?.get(1) ?: return null)
        val ca = evalMathExpr(Regex(""",\s*_ca\s*=\s*([\s\S]*?);\s*for\s*\(var\s+_st""").find(s2)?.groupValues?.get(1) ?: return null)

        val keyAffix = Regex("""var\s+[A-Za-z0-9_$]+\s*=\s*['"]([^'"]*)['"]\s*\+\s*String\(_kv\)\s*\+\s*['"]([^'"]*)['"]""")
            .find(s2)
        val keyPrefix = keyAffix?.groupValues?.get(1) ?: "f"
        val keySuffix = keyAffix?.groupValues?.get(2) ?: "p"

        val stpP = evalMathExpr(
            Regex("""_uninterleave\(_rawP,\s*([\s\S]*?)\);\s*var\s+_dFn""").find(s2)?.groupValues?.get(1) ?: return null,
        ).toInt()
        val decBytes = runCatching { b64Decode(uninterleave(rawP, stpP)) }.getOrNull() ?: return null
        if (decBytes.isEmpty()) return null

        val tw = evalMathExpr(Regex("""var\s+_tw\s*=\s*([\s\S]*?),\s*_sb\s*=""").find(s2)?.groupValues?.get(1) ?: return null).toInt()
        val sb = evalMathExpr(Regex(""",\s*_sb\s*=\s*([\s\S]*?),\s*_ks\s*=""").find(s2)?.groupValues?.get(1) ?: return null).toInt()
        val ks = evalMathExpr(Regex(""",\s*_ks\s*=\s*([\s\S]*?),\s*_jm\s*=""").find(s2)?.groupValues?.get(1) ?: return null).toInt()
        val jm = evalMathExpr(Regex(""",\s*_jm\s*=\s*([\s\S]*?);\s*var\s+_S\s*=""").find(s2)?.groupValues?.get(1) ?: return null).toInt()

        val subPrefix = evalMathExpr(
            Regex("""_x[0-9a-f]+\s*=\s*_x[0-9a-f]+\.substring\(([\s\S]*?)\);\s*try\s*\{\s*if\s*\(pEl""", RegexOption.IGNORE_CASE)
                .find(s2)?.groupValues?.get(1) ?: return null,
        ).toInt()

        fun deriveKvAndDecrypt(flexCand: Int, maxOutLen: Int): Pair<Long, ByteArray> {
            var sA = tVal + flexCand.toLong() + listTailSum
            var sB = initSB
            val rounds = maxR.toInt()
            for (st in 0 until rounds) {
                sA = (sA * cm + ca + ((st * 9) and 255).toLong()) % 1000000009L
                val xoredUnsigned = (sB.toInt() xor sA.toInt()).toLong() and 0xFFFFFFFFL
                sB = (xoredUnsigned + ((st * 11) and 511).toLong()) % 1000000009L
            }
            val kv = (((sA + sB).toInt() xor maxR.toInt()).toLong() and 0xFFFFFFFFL) % 1000000009L
            val key = "$keyPrefix$kv$keySuffix"

            val state = IntArray(256) { idx -> (idx * ks + sb) and 255 }
            var j = 0
            for (i in 0 until 256) {
                j = (j + state[i] + key[i % key.length].code + ((i * 5 + sb) and 255)) and 255
                val tmp = state[i]
                state[i] = state[j]
                state[j] = tmp
            }

            val n = minOf(maxOutLen, decBytes.size)
            val out = ByteArray(n)
            var ii = 0
            j = 0
            for (y in 0 until n) {
                ii = (ii + 1) and 255
                j = (j + state[ii]) and 255
                val tmp = state[ii]
                state[ii] = state[j]
                state[j] = tmp
                val cipherByte = decBytes[y].toInt() and 255
                val keystream = state[(state[ii] + state[j]) and 255] xor ((y * 5 + tw) and 255) xor ((j xor ii) and jm)
                out[y] = (cipherByte xor keystream).toByte()
            }
            return kv to out
        }

        val probeBytesLen = minOf(decBytes.size, maxOf(subPrefix * 4 + 256, 512))
        var winningKv: Long? = null
        var fullPlainBytes: ByteArray? = null

        val candidateDeltas = ArrayList<Int>(601).apply {
            add(0)
            for (d in 1..300) {
                add(d)
                add(-d)
            }
        }

        for (delta in candidateDeltas) {
            val (candKv, probeOut) = deriveKvAndDecrypt(estFlex + delta, probeBytesLen)
            val probeStr = String(probeOut, Charsets.UTF_8)
            if (probeStr.length <= subPrefix + 8) continue
            val head = probeStr.substring(subPrefix).trimStart().lowercase()
            if (head.startsWith("<!doctype") || head.startsWith("<html") ||
                head.startsWith("<script") || head.startsWith("<div")
            ) {
                val (finalKv, fullOut) = deriveKvAndDecrypt(estFlex + delta, decBytes.size)
                winningKv = finalKv
                fullPlainBytes = fullOut
                break
            }
        }

        val kv = winningKv ?: return null
        val plainBytes = fullPlainBytes ?: return null
        val fullStr = String(plainBytes, Charsets.UTF_8)
        if (fullStr.length <= subPrefix) return null
        val decryptedHtml = fullStr.substring(subPrefix)

        val tkM1 = (kv and 255L).toInt()
        val tkM2 = ((kv shr 8) and 255L).toInt()

        // Extract `secureCoreToken` (`window["_..."] = "d1.d2.d3..."` inside `#vault_script`)
        val wonDots = Regex("""window\["_[A-Za-z0-9]+"\]\s*=\s*"(\d+(?:\.\d+){20,})"""")
            .find(decryptedHtml)?.groupValues?.get(1).orEmpty()
        val coreToken = if (wonDots.isNotEmpty()) {
            val parts = wonDots.split('.')
            val sbTok = StringBuilder(parts.size)
            for (i in parts.indices) {
                val num = parts[i].toIntOrNull() ?: continue
                sbTok.append((num xor tkM1 xor ((i * rfMul + tkM2) and 255)).toChar())
            }
            sbTok.toString()
        } else {
            ""
        }

        val doc = Jsoup.parse(decryptedHtml)

        // Restore encrypted server tabs (`li[data-<tabAttr>]`)
        val tabAttr = Regex("""querySelectorAll\('li\[(data-[A-Za-z0-9]+)\]'\)""").find(s2)?.groupValues?.get(1)
        val tabBase = Regex("""parseInt\(bx\[hi\],\s*(\d+)\)""").find(s2)?.groupValues?.get(1)?.toIntOrNull() ?: 24
        val tabMul = Regex("""hi\s*\*\s*(\d+)\)\s*\+\s*k""").find(s2)?.groupValues?.get(1)?.toIntOrNull() ?: 9

        val serverTabs = ArrayList<ServerTab>()
        if (!tabAttr.isNullOrBlank()) {
            val tabEls = doc.select("li[$tabAttr]")
            for (k in tabEls.indices) {
                val el = tabEls[k]
                val encVal = el.attr(tabAttr)
                if (encVal.isBlank()) continue
                val bx = encVal.split('_')
                val decoded = StringBuilder(bx.size)
                for (hi in bx.indices) {
                    val v = bx[hi].toIntOrNull(tabBase) ?: continue
                    decoded.append((v xor tkM1 xor ((hi * tabMul + k + tkM2) and 255)).toChar())
                }
                val pair = decoded.toString()
                val dataId = pair.substringBefore('#', "").trim()
                val dataIndex = pair.substringAfter('#', "").trim()
                val label = el.text().trim()
                // Skip canary honeypot tab (`data-index="00"` / hidden zero-opacity trap)
                if (dataId.isNotEmpty() && dataIndex.isNotEmpty() && dataIndex != "00") {
                    serverTabs.add(ServerTab(label.ifBlank { "Server $dataIndex" }, dataId, dataIndex))
                }
            }
        }

        // Restore encrypted download links (`a[data-<dlAttr>]`)
        val dlAttr = Regex("""querySelectorAll\('a\[(data-[A-Za-z0-9]+)\]'\)""").find(s2)?.groupValues?.get(1)
        val dlBase = Regex("""parseInt\(hx\[j\],\s*(\d+)\)""").find(s2)?.groupValues?.get(1)?.toIntOrNull() ?: 16
        val dlMul = Regex("""j\s*\*\s*(\d+)\s*\+\s*_tkM2""").find(s2)?.groupValues?.get(1)?.toIntOrNull() ?: 5

        if (!dlAttr.isNullOrBlank()) {
            val dlEls = doc.select("a[$dlAttr]")
            for (el in dlEls) {
                val encVal = el.attr(dlAttr)
                if (encVal.isBlank()) continue
                val hx = encVal.split('-')
                val decodedUrl = StringBuilder(hx.size)
                for (j in hx.indices) {
                    val v = hx[j].toIntOrNull(dlBase) ?: continue
                    decodedUrl.append((v xor tkM1 xor ((j * dlMul + tkM2) and 255)).toChar())
                }
                el.attr("href", decodedUrl.toString())
                el.removeAttr(dlAttr)
            }
        }

        // Initial active embed inside `#watch`
        val watchHtml = doc.selectFirst("#watch")?.outerHtml().orEmpty()
        val initialEmbed = decodeCnBootFrame(watchHtml)

        // Collect all download links inside `#download`
        val downloads = ArrayList<DownloadEntry>()
        val seenDl = HashSet<String>()
        for (a in doc.select("#download a[href]")) {
            val rawHref = a.attr("href").trim()
                .removePrefix("https://href.li/?")
                .removePrefix("http://href.li/?")
                .replace("web2.cnvids.com", "fr.cimanow.cc")
                .trim()
            if (!rawHref.startsWith("http") || rawHref.startsWith("javascript:", true)) continue
            if (!seenDl.add(rawHref)) continue
            val text = a.text().trim()
            val qHint = Regex("""(2160|1440|1080|720|480|360|240)p""", RegexOption.IGNORE_CASE)
                .find("$text $rawHref")?.groupValues?.get(1)?.toIntOrNull()
            downloads.add(DownloadEntry(rawHref, text, qHint))
        }

        return DecodedWatchingPage(
            html = decryptedHtml,
            coreToken = coreToken,
            sigSalt = sigSalt,
            initialEmbedUrl = initialEmbed,
            serverTabs = serverTabs,
            downloads = downloads,
        )
    }

    // ── 5. `core.php?action=switch` Signer & `__cnBootFrame` Iframe Decoder ──

    fun calcCoreSig(token: String, id: String, index: String, salt: String = "cnSig12"): String {
        val sd = "$token:$id:$index:$salt"
        var h = 2166136261u
        for (ch in sd) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        return h.toULong().toString(36)
    }

    fun calcCoreSigV13(token: String, id: String, index: String, salt: String = "cnV13Sig"): String {
        val sd = "$index#$token#$id#$salt"
        var h = 2166136261u
        for (i in sd.indices) {
            val addend = (sd[i].code + ((i * 7) and 255)).toUInt()
            h = h xor addend
            h *= 16777619u
        }
        return h.toULong().toString(36)
    }

    fun decodeCnBootFrame(fragmentHtml: String, coreToken: String = ""): String? {
        if (fragmentHtml.isBlank()) return null

        // V13 (`data-cnb` + `data-cni` keyed by `coreToken + ':' + info`)
        val cnb = Regex("""data-cnb=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)
        val cni = Regex("""data-cni=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)
        if (!cnb.isNullOrBlank() && !cni.isNullOrBlank() && coreToken.isNotBlank()) {
            val ks = "$coreToken:$cni"
            val parts = cnb.split('_')
            val out = StringBuilder(parts.size)
            for (j in parts.indices) {
                val v = parts[j].toIntOrNull() ?: continue
                val kCh = ks[j % ks.length].code
                out.append((v xor kCh xor ((j * 19 + 11) and 255)).toChar())
            }
            normalizeEmbedUrl(out.toString())?.let { return it }
        }

        // V12 (`data-cnk` + `data-cna`)
        val cnk = Regex("""data-cnk=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)
        val cna = Regex("""data-cna=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)

        if (!cnk.isNullOrBlank() && !cna.isNullOrBlank() && cnk.length >= 2) {
            val keyChars = CharArray(cnk.length / 2) { idx ->
                (cnk.substring(idx * 2, idx * 2 + 2).toIntOrNull(16) ?: 0).toChar()
            }
            if (keyChars.isNotEmpty()) {
                val parts = cna.split('.')
                val out = StringBuilder(parts.size)
                for (j in parts.indices) {
                    val v = parts[j].toIntOrNull() ?: continue
                    val kCh = keyChars[j % keyChars.size].code
                    out.append((v xor kCh xor ((j * 13 + 7) and 255)).toChar())
                }
                normalizeEmbedUrl(out.toString())?.let { return it }
            }
        }

        val plainSrc = Regex("""<iframe\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)
        return normalizeEmbedUrl(plainSrc)
    }

    fun normalizeEmbedUrl(raw: String?): String? {
        val t = raw?.trim()
            ?.removePrefix("https://href.li/?")
            ?.removePrefix("http://href.li/?")
            ?.trim()
            ?: return null
        if (t.isEmpty() || t.startsWith("#") || t.equals("about:blank", true) || t.startsWith("javascript:", true)) {
            return null
        }
        return when {
            t.startsWith("//") -> "https:$t"
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> null
        }
    }
}
