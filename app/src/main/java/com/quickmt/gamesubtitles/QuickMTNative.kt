package com.quickmt.gamesubtitles

import android.util.LruCache
import java.io.File

object QuickMTNative {
    @Volatile
    private var librariesLoaded = false

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val cache = LruCache<String, String>(256)

    private val leadingDashRegex = Regex("""^[\-\u2013\u2014•·]+\s*""")
    private val colonSpeakerRegex = Regex("""^([A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ0-9'\- ]{1,24}):\s+(.+)$""")
    private val bracketSpeakerRegex = Regex("""^\[([A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ0-9'\- ]{1,24})\]\s*[:\-]?\s+(.+)$""")

    private val gameIdiomRewrites: List<Pair<Regex, String>> = listOf(
        Regex("""\bout cold\b""", RegexOption.IGNORE_CASE) to "completely unconscious",
        Regex("""\bwatch your six\b""", RegexOption.IGNORE_CASE) to "watch behind you",
        Regex("""\bgot your six\b""", RegexOption.IGNORE_CASE) to "watching your back",
        Regex("""\bhold your fire\b""", RegexOption.IGNORE_CASE) to "do not shoot",
        Regex("""\bcopy that\b""", RegexOption.IGNORE_CASE) to "understood",
        Regex("""\broger that\b""", RegexOption.IGNORE_CASE) to "understood",
        Regex("""\bfall back\b""", RegexOption.IGNORE_CASE) to "retreat",
        Regex("""\bstand down\b""", RegexOption.IGNORE_CASE) to "lower your weapons",
        Regex("""\bcut it out\b""", RegexOption.IGNORE_CASE) to "stop doing that",
        Regex("""\bknock it off\b""", RegexOption.IGNORE_CASE) to "stop doing that",
        Regex("""\bhang in there\b""", RegexOption.IGNORE_CASE) to "stay strong, hold on",
        Regex("""\bno sweat\b""", RegexOption.IGNORE_CASE) to "no problem at all",
        Regex("""\bpiece of cake\b""", RegexOption.IGNORE_CASE) to "very easy",
        Regex("""\blosing daylight\b""", RegexOption.IGNORE_CASE) to "running out of time before dark",
        Regex("""\bkeep your head down\b""", RegexOption.IGNORE_CASE) to "stay low and take cover",
        Regex("""\bon my mark\b""", RegexOption.IGNORE_CASE) to "on my signal",
        Regex("""\bhit the deck\b""", RegexOption.IGNORE_CASE) to "get down on the ground",
        Regex("""\bsit tight\b""", RegexOption.IGNORE_CASE) to "wait patiently right here",
        Regex("""\bhurry the fuck up\b""", RegexOption.IGNORE_CASE) to "hurry up right now"
    )

    private fun ensureLibraries() {
        if (!librariesLoaded) {
            synchronized(this) {
                if (!librariesLoaded) {
                    System.loadLibrary("ctranslate2")
                    System.loadLibrary("quickmt_jni")
                    librariesLoaded = true
                }
            }
        }
    }

    fun init(modelDir: File, threads: Int = 4): Boolean {
        ensureLibraries()
        synchronized(this) {
            cache.evictAll()
            val ok = nativeInit(modelDir.absolutePath, threads.coerceIn(1, 6))
            isInitialized = ok
            return ok
        }
    }

    fun normalizeOcrEnglish(raw: String): String {
        var s = raw.replace(Regex("""\s+"""), " ").trim()
        if (s.isEmpty()) return ""
        s = leadingDashRegex.replaceFirst(s, "").trim()
        s = s.replace(Regex("""\b[l|]('m|'ve|'ll|'d)\b"""), "I$1")
        s = s.replace(Regex("""([A-Za-z])-\s+([a-z])"""), "$1$2")

        // Convert ALL-CAPS subtitles to sentence case so NMT translates accurately
        val letters = s.filter { it.isLetter() }
        if (letters.length >= 6 && letters.count { it.isUpperCase() }.toFloat() / letters.length >= 0.85f) {
            var lowered = s.lowercase()
            lowered = Regex("""(^|[.!?]\s+)([a-z])""").replace(lowered) { m ->
                m.groupValues[1] + m.groupValues[2].uppercase()
            }
            lowered = Regex("""\bi('m|'ve|'ll|'d|\b)""").replace(lowered) { m ->
                "I" + m.groupValues[1]
            }
            s = lowered
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun extractSpeakerPrefix(text: String): Pair<String, String> {
        val clean = leadingDashRegex.replaceFirst(text.trim(), "").trim()
        if (clean.isEmpty()) return "" to ""
        for (regex in listOf(bracketSpeakerRegex, colonSpeakerRegex)) {
            val m = regex.matchEntire(clean) ?: continue
            val spk = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
            if (spk.split(" ").size <= 3 && !spk.equals("http", true) && !spk.equals("https", true) && body.length >= 2) {
                return spk to body
            }
        }
        return "" to clean
    }

    fun translate(text: String, beamSize: Int = 2, rewriteIdioms: Boolean = true): String {
        if (!isInitialized) return ""
        val clean = normalizeOcrEnglish(text)
        if (clean.isEmpty()) return ""

        val cacheKey = "$beamSize|$rewriteIdioms|$clean"
        cache.get(cacheKey)?.let { return it }

        var prepared = clean
        if (rewriteIdioms) {
            for ((pattern, replacement) in gameIdiomRewrites) {
                prepared = pattern.replace(prepared, replacement)
            }
        }

        val translated = nativeTranslate(prepared, beamSize).trim()
        if (translated.isNotEmpty()) {
            cache.put(cacheKey, translated)
        }
        return translated
    }

    fun release() {
        synchronized(this) {
            cache.evictAll()
            if (isInitialized) {
                nativeRelease()
                isInitialized = false
            }
        }
    }

    private external fun nativeInit(modelDir: String, numThreads: Int): Boolean
    private external fun nativeTranslate(text: String, beamSize: Int): String
    private external fun nativeRelease()
}
