package com.zlatan.gamelens.translation

/**
 * Pre-translation English Idiom & Slang Normalizer.
 * Applied ONLY before Free Google Translate and Offline ML Kit to rewrite
 * notorious gaming and cinema idioms into clear, unambiguous English,
 * ensuring high-quality natural Arabic translation on the first pass.
 * Never applied to Custom AI LLMs (which handle idioms natively).
 */
object EnglishIdiomNormalizer {

    private val REPLACEMENTS = listOf(
        Regex("""(?i)\bcut the crap\b""") to "stop the nonsense",
        Regex("""(?i)\bget your ass (down|over|out) here\b""") to "come $1 here right now",
        Regex("""(?i)(,\s*)homes([.!?]|$)""") to "$1buddy$2",
        Regex("""(?i)\bno parties\b([.!?]|$)""") to "no parties allowed$1",
        Regex("""(?i)\bshit([!?,])""") to "damn$1",
        Regex("""(?i)\bwhoa,\s*got you\b""") to "easy, understood",
        Regex("""(?i)\b(be )?back in (five|ten|two|three)\b(?!\s+minutes)""") to "$1back in $2 minutes",
        Regex("""(?i)\blocal upstart\b""") to "local newcomer",
        Regex("""(?i)\bget off the streets\b""") to "stay hidden off the streets",
        Regex("""(?i)\bdisguise is blown\b""") to "disguise is compromised",
        Regex("""(?i)\bsitting ducks\b""") to "easy targets",
        Regex("""(?i)\bclosing in on target\b""") to "approaching the target",
        Regex("""(?i)\bshit where (he|she|you|they) eats?\b""") to "make trouble in $1's own backyard"
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var s = text
        for ((regex, replacement) in REPLACEMENTS) {
            s = regex.replace(s, replacement)
        }
        return s
    }
}
