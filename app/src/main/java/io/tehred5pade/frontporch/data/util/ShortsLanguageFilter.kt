package io.tehred5pade.frontporch.data.util

/**
 * Cheap, offline English gate for Shorts candidates.
 *
 * We do not ship a language-detection model, and YouTube's `hl` hint only controls the UI
 * language. Instead this rejects titles/bylines that are mostly written in a non-Latin script,
 * which is where the bulk of the non-English Shorts spam comes from. Latin-script languages
 * (Spanish, Portuguese, German, ...) cannot be separated reliably without a model, so they are
 * allowed through and left to the affinity engine to demote.
 */
object ShortsLanguageFilter {
    /** Only apply the gate when the user expects English (or left the choice on auto). */
    fun appliesTo(preferredAudioLanguage: String): Boolean =
        preferredAudioLanguage.isBlank() ||
            preferredAudioLanguage.equals("en", ignoreCase = true) ||
            preferredAudioLanguage.equals("auto", ignoreCase = true)

    fun isLikelyEnglish(title: String, channelName: String, preferredAudioLanguage: String): Boolean {
        if (!appliesTo(preferredAudioLanguage)) return true

        val sample = "$title $channelName"
        var latinLetters = 0
        var totalLetters = 0
        for (ch in sample) {
            if (!ch.isLetter()) continue
            totalLetters++
            // Basic Latin, Latin-1 supplement, Latin Extended-A/B, IPA and spacing modifiers.
            if (ch.code in 0x0041..0x024F || ch.code in 0x1E00..0x1EFF) {
                latinLetters++
            }
        }

        if (totalLetters == 0) return true
        return latinLetters.toFloat() / totalLetters >= 0.65f
    }
}
