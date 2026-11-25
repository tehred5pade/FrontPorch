package io.tehred5pade.frontporch.data.innertube

import io.tehred5pade.frontporch.ui.models.ShortItem

/**
 * The subset of InnerTube calls the recommendation engine needs. Keeping this as an interface
 * lets the feed logic be tested with a deterministic source instead of live network calls.
 */
interface ShortsFeedSource {
    suspend fun fetchChannelShorts(
        channelId: String,
        channelName: String
    ): List<DatedShortItem>

    suspend fun fetchReelSequence(
        seedVideoId: String?,
        continuation: String?,
        visitorData: String?,
        preferredAudioLanguage: String
    ): Pair<List<ShortItem>, String?>

    suspend fun searchShortsByQuery(
        query: String,
        visitorData: String?,
        paramsOverride: String?,
        preferredAudioLanguage: String
    ): Result<List<ShortItem>>
}
