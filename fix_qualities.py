with open('FaselHD/src/main/kotlin/com/faselhd/FaselHD.kt', 'r') as f:
    content = f.read()

# Fix 1: Line ~477 - this.quality in newExtractorLink needs .value (Int)
content = content.replace(
    'this.quality = Qualities.Unknown\n',
    'this.quality = Qualities.Unknown.value\n'
)

# Fix 2: Replace parseQuality to return Int instead of SearchQuality?
old_parse = '''    private fun parseQuality(q: String?): SearchQuality? {
        if (q.isNullOrBlank()) return Qualities.Unknown
        val digits = Regex("""(\\d{3,4})""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown
        return when {
            digits >= 2160 -> Qualities.P2160
            digits >= 1440 -> Qualities.P1440
            digits >= 1080 -> Qualities.P1080
            digits >= 720  -> Qualities.P720
            digits >= 480  -> Qualities.P480
            digits >= 360  -> Qualities.P360
            digits >= 240  -> Qualities.P240
            else           -> Qualities.Unknown
        }
    }'''

new_parse = '''    private fun parseQuality(q: String?): Int {
        if (q.isNullOrBlank()) return Qualities.Unknown.value
        val digits = Regex("""(\\d{3,4})""").find(q)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            digits >= 2160 -> Qualities.P2160.value
            digits >= 1440 -> Qualities.P1440.value
            digits >= 1080 -> Qualities.P1080.value
            digits >= 720  -> Qualities.P720.value
            digits >= 480  -> Qualities.P480.value
            digits >= 360  -> Qualities.P360.value
            digits >= 240  -> Qualities.P240.value
            else           -> Qualities.Unknown.value
        }
    }'''

content = content.replace(old_parse, new_parse)

with open('FaselHD/src/main/kotlin/com/faselhd/FaselHD.kt', 'w') as f:
    f.write(content)

# Verify
lines = content.split('\n')
print(f"Total lines: {len(lines)}")
for i, l in enumerate(lines):
    if 'this.quality' in l:
        print(f"QUALITY LINE {i+1}: {l}")
for i, l in enumerate(lines):
    if 'parseQuality' in l:
        print(f"PARSE LINE {i+1}: {l}")