package io.tehred5pade.frontporch

import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.innertube.DatedShortItem
import io.tehred5pade.frontporch.data.innertube.ShortsFeedSource
import io.tehred5pade.frontporch.data.local.AffinityScoreEntity
import io.tehred5pade.frontporch.data.local.ChannelAffinityEntity
import io.tehred5pade.frontporch.data.local.RecommendationDao
import io.tehred5pade.frontporch.data.local.SeenShortEntity
import io.tehred5pade.frontporch.data.local.ShortFeedbackEntity
import io.tehred5pade.frontporch.data.local.TagAffinityEntity
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.recommendation.ShortsRecommendationEngine
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShortsFeedEngineTest {

    private class FakeRecommendationDao : RecommendationDao {
        val feedback = mutableMapOf<String, ShortFeedbackEntity>()
        val affinities = mutableMapOf<String, AffinityScoreEntity>()
        val legacyChannels = mutableMapOf<String, ChannelAffinityEntity>()
        val legacyTags = mutableMapOf<String, TagAffinityEntity>()
        val seen = mutableMapOf<String, SeenShortEntity>()

        override suspend fun getFeedback(videoId: String) = feedback[videoId]
        override suspend fun getAllFeedback() = feedback.values.toList()
        override suspend fun insertFeedback(feedback: ShortFeedbackEntity) {
            this.feedback[feedback.videoId] = feedback
        }
        override suspend fun deleteFeedback(videoId: String) {
            feedback.remove(videoId)
        }

        override suspend fun getAllChannelAffinities() = legacyChannels.values.toList()
        override suspend fun getChannelAffinity(channelId: String) = legacyChannels[channelId]
        override suspend fun upsertChannelAffinity(affinity: ChannelAffinityEntity) {
            legacyChannels[affinity.channelId] = affinity
        }

        override suspend fun getAllTagAffinities() = legacyTags.values.toList()
        override suspend fun getTagAffinity(tag: String) = legacyTags[tag]
        override suspend fun upsertTagAffinity(affinity: TagAffinityEntity) {
            legacyTags[affinity.tag] = affinity
        }

        override suspend fun getAllAffinities() = affinities.values.toList()
        override suspend fun getAffinity(key: String) = affinities[key]
        override suspend fun getAffinitiesByType(type: String) =
            affinities.values.filter { it.type == type }
        override suspend fun getMutedChannelKeys() =
            affinities.values.filter { it.type == "CHANNEL" && it.muted }.map { it.affinityKey }
        override suspend fun upsertAffinity(affinity: AffinityScoreEntity) {
            affinities[affinity.affinityKey] = affinity
        }

        override suspend fun getSeen(videoId: String) = seen[videoId]
        override suspend fun getAllSeenIds(): List<String> = seen.keys.toList()
        override suspend fun getAllSeen(): List<SeenShortEntity> = seen.values.toList()
        override suspend fun insertSeen(seen: SeenShortEntity) {
            this.seen[seen.videoId] = seen
        }
        override suspend fun pruneSeenOlderThan(cutoffTimestamp: Long) {
            seen.values.removeAll { it.timestamp < cutoffTimestamp }
        }
    }

    private class FakeSubscriptionDao : SubscriptionDao {
        var subscriptions: List<SubscriptionEntity> = emptyList()

        override fun observeAll() = throw UnsupportedOperationException()
        override fun getAllSubscriptions() = throw UnsupportedOperationException()
        override suspend fun getAllOnce() = subscriptions
        override suspend fun insertAll(channels: List<SubscriptionEntity>) {
            subscriptions = subscriptions + channels
        }
        override suspend fun insertSubscription(subscription: SubscriptionEntity) {
            subscriptions = subscriptions + subscription
        }
        override suspend fun updateThumbnail(channelId: String, thumbnailUrl: String) = Unit
        override suspend fun deleteSubscription(channelId: String) {
            subscriptions = subscriptions.filterNot { it.channelId == channelId }
        }
        override suspend fun clearAll() {
            subscriptions = emptyList()
        }
    }

    private class FakeShortsSource : ShortsFeedSource {
        val channelShorts = HashMap<String, List<ShortItem>>()
        val relatedBySeed = HashMap<String, List<ShortItem>>()
        val fetchedChannels = mutableListOf<String>()
        val searchedQueries = mutableListOf<String>()

        override suspend fun fetchChannelShorts(
            channelId: String,
            channelName: String
        ): List<DatedShortItem> {
            fetchedChannels.add(channelId)
            return channelShorts[channelId].orEmpty().map {
                DatedShortItem(it, "2026-01-01T00:00:00Z")
            }
        }

        override suspend fun fetchReelSequence(
            seedVideoId: String?,
            continuation: String?,
            visitorData: String?,
            preferredAudioLanguage: String
        ): Pair<List<ShortItem>, String?> = relatedBySeed[seedVideoId].orEmpty() to null

        override suspend fun searchShortsByQuery(
            query: String,
            visitorData: String?,
            paramsOverride: String?,
            preferredAudioLanguage: String
        ): Result<List<ShortItem>> {
            searchedQueries.add(query)
            return Result.success(emptyList())
        }
    }

    private lateinit var dao: FakeRecommendationDao
    private lateinit var subscriptionDao: FakeSubscriptionDao
    private lateinit var source: FakeShortsSource
    private lateinit var engine: ShortsRecommendationEngine

    @Before
    fun setUp() {
        dao = FakeRecommendationDao()
        subscriptionDao = FakeSubscriptionDao()
        source = FakeShortsSource()
        engine = ShortsRecommendationEngine(
            recommendationDao = dao,
            subscriptionDao = subscriptionDao,
            shortsService = source
        )
    }

    @Test
    fun coldStartSeedsSubscribedChannelsWithBaseline() = runBlocking {
        subscriptionDao.subscriptions = listOf(SubscriptionEntity("UC1", "Channel One"))

        engine.seedSubscriptions()

        val affinity = dao.getAffinity("channel:UC1")
        assertEquals(ShortsRecommendationEngine.SUBSCRIPTION_BASELINE, affinity?.score ?: 0.0, 0.001)
    }

    @Test
    fun likeAndDislikeUpdateChannelAndKeywordScores() = runBlocking {
        val short = ShortItem(
            id = "v1",
            title = "Amazing #woodworking build",
            channelId = "UC2",
            channelTitle = "Shop Channel"
        )

        engine.setLike(short)
        assertEquals(ShortsRecommendationEngine.CHANNEL_LIKE_DELTA, dao.getAffinity("channel:UC2")!!.score, 0.001)
        assertEquals(ShortsRecommendationEngine.KEYWORD_LIKE_DELTA, dao.getAffinity("tag:woodworking")!!.score, 0.001)

        engine.clearLike(short)
        assertEquals(0.0, dao.getAffinity("channel:UC2")!!.score, 0.001)
        assertEquals(0.0, dao.getAffinity("tag:woodworking")!!.score, 0.001)

        engine.setDislike(short.copy(id = "v2"))
        engine.setDislike(short.copy(id = "v3"))

        val channel = dao.getAffinity("channel:UC2")!!
        assertEquals(ShortsRecommendationEngine.CHANNEL_DISLIKE_DELTA * 2, channel.score, 0.001)
        assertEquals(ShortsRecommendationEngine.CHANNEL_MUTE_DISLIKES, channel.dislikeCount)
        assertTrue(channel.muted)
    }

    @Test
    fun mutedChannelIsFilteredFromRanking() = runBlocking {
        val bad = ShortItem(id = "bad1", title = "Bad clip", channelId = "UCBAD", channelTitle = "Bad")
        engine.setDislike(bad.copy(id = "bad2"))
        engine.setDislike(bad.copy(id = "bad3"))

        val good = ShortItem(id = "good1", title = "Good clip", channelId = "UCGOOD", channelTitle = "Good")
        val ranked = engine.rankUnshown(listOf(bad, good), UserSettings(), firstChannelId = null)

        assertEquals(listOf("good1"), ranked.map { it.id })
    }

    @Test
    fun rankingSkipsSeenItemsAndSpacesCreators() = runBlocking {
        val a1 = ShortItem(id = "a1", title = "A one", channelId = "UCA", channelTitle = "A")
        val a2 = ShortItem(id = "a2", title = "A two", channelId = "UCA", channelTitle = "A")
        val b1 = ShortItem(id = "b1", title = "B one", channelId = "UCB", channelTitle = "B")
        val seen = ShortItem(id = "seen", title = "Seen", channelId = "UCA", channelTitle = "A")
        dao.insertSeen(SeenShortEntity(seen.id, System.currentTimeMillis()))

        val ranked = engine.rankUnshown(listOf(a1, a2, b1, seen), UserSettings(), null)

        assertEquals(3, ranked.size)
        assertTrue(ranked.none { it.id == "seen" })
        for (i in 0 until ranked.lastIndex) {
            assertNotEquals(ranked[i].channelId, ranked[i + 1].channelId)
        }
    }

    @Test
    fun rankingCyclesThroughAllAvailableCreators() = runBlocking {
        val items = (1..3).flatMap { creator ->
            (1..4).map { index ->
                ShortItem(
                    id = "c${creator}_$index",
                    title = "Clip $creator $index",
                    channelId = "UC$creator",
                    channelTitle = "Creator $creator"
                )
            }
        }

        val ranked = engine.rankUnshown(items, UserSettings(), firstChannelId = null)

        assertEquals(12, ranked.size)
        // The first rotation must expose all three creators before repeating one.
        assertEquals(3, ranked.take(3).map { it.channelId }.distinct().size)
        for (i in 0 until ranked.lastIndex) {
            assertNotEquals(ranked[i].channelId, ranked[i + 1].channelId)
        }
    }

    @Test
    fun feedRotatesAwayFromSameTopicServedLast() = runBlocking {
        subscriptionDao.subscriptions = listOf(SubscriptionEntity("UCSUB", "Sub Channel"))
        source.channelShorts["UCSUB"] = listOf(
            ShortItem(
                id = "sub1",
                title = "Harry Potter and the Chamber",
                channelTitle = "Sub Channel",
                channelId = "UCSUB",
                isShortForm = true,
                durationSeconds = 20
            )
        )

        val firstFeed = engine.fetchNextShorts(
            existingIds = emptySet(),
            lastChannelId = null,
            count = 8,
            settings = UserSettings(),
            visitorData = null
        )
        assertEquals(listOf("sub1"), firstFeed.map { it.id })

        // A different-niche Short must not be served right after the Harry Potter Short, even
        // though the creators differ. The topic guard keeps the feed out of single-niche loops.
        val nextFeed = engine.rankUnshown(
            items = listOf(
                ShortItem(
                    id = "hp2",
                    title = "Harry Potter and the Knight",
                    channelTitle = "HP Other",
                    channelId = "UCHP2",
                    isShortForm = true,
                    durationSeconds = 20
                ),
                ShortItem(
                    id = "car1",
                    title = "Car flipping in the snow",
                    channelTitle = "Car Ch",
                    channelId = "UCCAR",
                    isShortForm = true,
                    durationSeconds = 20
                )
            ),
            settings = UserSettings(),
            firstChannelId = null
        )

        assertEquals(listOf("car1", "hp2"), nextFeed.map { it.id })
    }

    @Test
    fun discoversUnsubscribedChannelAndFetchesItsShortsTab() = runBlocking {
        subscriptionDao.subscriptions = listOf(SubscriptionEntity("UCSUB", "Subscribed Channel"))
        val subscribedShort = ShortItem(
            id = "sub1",
            title = "Fresh from subscription",
            channelId = "UCSUB",
            channelTitle = "Subscribed Channel",
            isShortForm = true
        )
        val relatedShort = ShortItem(
            id = "rel1",
            title = "Related creator clip",
            channelId = "UCDISC",
            channelTitle = "Discovered Channel",
            isShortForm = true
        )
        val discoveredShort = ShortItem(
            id = "disc1",
            title = "Discovered channel short",
            channelId = "UCDISC",
            channelTitle = "Discovered Channel",
            isShortForm = true
        )
        source.channelShorts["UCSUB"] = listOf(subscribedShort)
        source.channelShorts["UCDISC"] = listOf(discoveredShort)
        source.relatedBySeed["sub1"] = listOf(relatedShort)

        val feed = engine.fetchNextShorts(
            existingIds = emptySet(),
            lastChannelId = null,
            count = 10,
            settings = UserSettings(),
            visitorData = null
        )

        assertTrue(source.fetchedChannels.contains("UCDISC"))
        assertTrue(feed.any { it.id == "disc1" })
        assertTrue(feed.any { it.channelId == "UCDISC" })
        assertTrue(source.searchedQueries.isEmpty()) // no generic search needed
    }

    @Test
    fun discoveryWorksWithoutAnySubscriptions() = runBlocking {
        val seed = ShortItem(
            id = "seed1",
            title = "Liked clip",
            channelId = "UCSEED",
            channelTitle = "Seed Creator"
        )
        val related = ShortItem(
            id = "rel2",
            title = "Another creator clip",
            channelId = "UCONLY",
            channelTitle = "Unsubscribed Creator",
            isShortForm = true
        )
        val unsubscribedShort = ShortItem(
            id = "only1",
            title = "Unsubscribed channel short",
            channelId = "UCONLY",
            channelTitle = "Unsubscribed Creator",
            isShortForm = true
        )
        source.relatedBySeed["seed1"] = listOf(related)
        source.channelShorts["UCONLY"] = listOf(unsubscribedShort)

        engine.setLike(seed)
        engine.seedRelated(seed, UserSettings(), null)

        val feed = engine.fetchNextShorts(
            existingIds = emptySet(),
            lastChannelId = null,
            count = 10,
            settings = UserSettings(),
            visitorData = null
        )

        assertTrue(subscriptionDao.subscriptions.isEmpty())
        assertTrue(source.fetchedChannels.contains("UCONLY"))
        assertTrue(feed.any { it.id == "only1" })
    }
}
