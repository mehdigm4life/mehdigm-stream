package com.cimanow

import org.json.JSONObject
import org.jsoup.Jsoup
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
        if (!t.startsWith("{")) return null
        return runCatching { JSONObject(t) }.getOrNull()
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

    // ── 3. CSS Flexbox `<x-fx>` Layout Height Solver ─────────────────────────

    private data class FlexItem(val order: Int, val domIdx: Int, val outerW: Int, val outerH: Int)

    fun computeFlexHeight(fxHtml: String): Int {
        val fxStyle = Regex("""<x-fx\s+style='([^']+)'""").find(fxHtml)?.groupValues?.get(1) ?: return 0

        fun pxProp(style: String, prop: String): Int =
            Regex("""(?:^|;)\s*${Regex.escape(prop)}:(-?\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        fun pairPx(style: String, prop: String): Pair<Int, Int> {
            val m = Regex("""(?:^|;)\s*${Regex.escape(prop)}:(\d+)px\s+(\d+)px""").find(style)
                ?: return 0 to 0
            return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
        }

        val fxW = pxProp(fxStyle, "width")
        val (fxPadV, fxPadH) = pairPx(fxStyle, "padding")
        val colGap = pxProp(fxStyle, "column-gap")
        val rowGap = pxProp(fxStyle, "row-gap")
        val availW = fxW - 2 * fxPadH

        val items = ArrayList<FlexItem>()
        var domIdx = 0
        for (m in Regex("""<x-i\s+style='([^']+)'""").findAll(fxHtml)) {
            val st = m.groupValues[1]
            domIdx++
            if (st.contains("display:none")) continue
            val order = Regex("""order:(-?\d+)""").find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val borderBox = st.contains("box-sizing:border-box")
            val w = pxProp(st, "width")
            val h = pxProp(st, "height")
            val (padV, padH) = pairPx(st, "padding")
            val (marV, marH) = pairPx(st, "margin")
            val boxW = if (borderBox) w else w + 2 * padH
            val boxH = if (borderBox) h else h + 2 * padV
            items.add(FlexItem(order, domIdx, boxW + 2 * marH, boxH + 2 * marV))
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

        return 2 * fxPadV + rowHeights.sum() + max(0, rowHeights.size - 1) * rowGap
    }

    // ── 4. `/watching/?token=...` Full Pure-Kotlin Vault Decryptor ───────────

    fun decodeWatchingVault(rawHtml: String): DecodedWatchingPage? {
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

    fun decodeCnBootFrame(fragmentHtml: String): String? {
        if (fragmentHtml.isBlank()) return null
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
                return normalizeEmbedUrl(out.toString())
            }
        }

        val plainSrc = Regex("""<iframe\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fragmentHtml)?.groupValues?.get(1)
        return normalizeEmbedUrl(plainSrc)
    }

    fun normalizeEmbedUrl(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty() || t.equals("about:blank", true) || t.startsWith("javascript:", true)) return null
        return when {
            t.startsWith("//") -> "https:$t"
            t.startsWith("http://") || t.startsWith("https://") -> t
            else -> null
        }
    }
}
