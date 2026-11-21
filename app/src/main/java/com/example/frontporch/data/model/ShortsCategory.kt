package com.example.frontporch.data.model

enum class ShortsCategory(
    val id: String,
    val label: String,
    val seedQuery: String
) {
    FISHING("fishing", "Fishing", "fishing #shorts"),
    HUNTING_OUTDOORS("outdoors", "Hunting & Outdoors", "hunting outdoors #shorts"),
    AUTOMOTIVE("automotive", "Automotive & Cars", "cars automotive builds #shorts"),
    MECHANICS_DIY("mechanics", "Mechanics & Tools", "mechanic repair tools #shorts"),
    WOODWORKING("woodworking", "Woodworking", "woodworking shop #shorts"),
    HOMESTEADING("homesteading", "Homesteading & Farm", "homesteading tractor chickens #shorts"),
    COOKING_BBQ("bbq", "Cooking & BBQ", "bbq smoking cooking #shorts"),
    SCIENCE_TECH("tech", "Science & Tech", "science engineering tech #shorts"),
    AVIATION("aviation", "Aviation & Engines", "jet engine aviation #shorts"),
    SPORTS("sports", "Sports", "sports highlights #shorts"),
    GAMING("gaming", "Gaming", "gaming clips #shorts"),
    COMEDY("comedy", "Comedy", "funny comedy #shorts");

    companion object {
        fun fromId(id: String): ShortsCategory? = entries.find { it.id == id }
    }
}

data class ShortsCategoryItem(
    val id: String,
    val label: String,
    val seedQuery: String,
    val isCustom: Boolean = false
) {
    companion object {
        val presets = ShortsCategory.entries.map {
            ShortsCategoryItem(it.id, it.label, it.seedQuery, false)
        }
    }
}
