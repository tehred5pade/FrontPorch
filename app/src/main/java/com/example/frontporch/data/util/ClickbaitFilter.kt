package com.example.frontporch.data.util

object SlopAndClickbaitFilter {
    private val bannedPhrases = listOf(
        // Clickbait & engagement bait
        "wait for the end", "wait till the end", "watch till the end",
        "watch until the end", "you won't believe", "gone wrong",
        "99% fail", "part 2", "part 3", "#fyp", "#viral", "#trending",
        "sub for more",
        // AI slop & TTS farm markers
        "#ai", "#aigenerated", "#aiart", "#aivideo", "#sora", "#soraai",
        "#kling", "#klingai", "#midjourney", "#runwayml", "#veo", "#veo2",
        "#luma", "#aihistory", "#aicat", "#aidog", "#aianimation",
        "made with ai", "created with ai", "ai generated", "ai cover",
        "ai voice", "reddit stories", "minecraft parkour"
    )

    private val spamPunctuation = Regex("""[!?]{2,}""")
    private val botChannelPattern = Regex("""^@?user-[a-z0-9]{6,}$""", RegexOption.IGNORE_CASE)

    fun isSlopOrClickbait(title: String, channelName: String = ""): Boolean {
        val cleanTitle = title.trim()
        val lowerTitle = cleanTitle.lowercase()

        if (channelName.isNotBlank() && botChannelPattern.matches(channelName.trim())) {
            return true
        }

        if (cleanTitle.isEmpty() || cleanTitle == "YouTube Short") return false

        // Match standalone "#ai" or any banned phrase/hashtag
        if (Regex("""(^|\s)#ai(\b|$)""").containsMatchIn(lowerTitle)) return true
        if (bannedPhrases.any { lowerTitle.contains(it) }) return true
        if (spamPunctuation.containsMatchIn(cleanTitle)) return true

        val letters = cleanTitle.filter { it.isLetter() }
        if (letters.length >= 10 && letters.all { it.isUpperCase() }) return true

        val emojiCount = cleanTitle.codePoints().filter { cp ->
            cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF
        }.count()
        if (emojiCount >= 3) return true

        return false
    }
}

object ClickbaitFilter {
    fun isClickbait(title: String, channelName: String = ""): Boolean =
        SlopAndClickbaitFilter.isSlopOrClickbait(title, channelName)
}
