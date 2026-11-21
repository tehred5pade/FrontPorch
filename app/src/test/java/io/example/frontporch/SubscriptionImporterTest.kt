package io.tehred5pade.frontporch

import io.tehred5pade.frontporch.data.importexport.SubscriptionImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionImporterTest {

    @Test
    fun parseNewPipeJson_extractsChannels() {
        val jsonText = """
            {
              "app_version": "0.27.0",
              "app_version_int": 100,
              "subscriptions": [
                {
                  "service_id": 0,
                  "url": "https://www.youtube.com/channel/UCv6JnTIupzcJI15akv7w8Aw",
                  "name": "Veritasium"
                },
                {
                  "service_id": 0,
                  "url": "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q",
                  "name": "Kurzgesagt"
                }
              ]
            }
        """.trimIndent()

        val result = SubscriptionImporter.parseSubscriptionsFile(jsonText)

        assertEquals(2, result.size)
        assertEquals("UCv6JnTIupzcJI15akv7w8Aw", result[0].channelId)
        assertEquals("Veritasium", result[0].title)

        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", result[1].channelId)
        assertEquals("Kurzgesagt", result[1].title)
    }

    @Test
    fun parseYouTubeTakeoutCsv_extractsChannels() {
        val csvText = """
            Channel Id,Channel Url,Channel Title
            UCv6JnTIupzcJI15akv7w8Aw,http://www.youtube.com/channel/UCv6JnTIupzcJI15akv7w8Aw,Veritasium
            UCsXVk37bltHxD1rDPwtNM8Q,http://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q,"Kurzgesagt, in a Nutshell"
        """.trimIndent()

        val result = SubscriptionImporter.parseSubscriptionsFile(csvText)

        assertEquals(2, result.size)
        assertEquals("UCv6JnTIupzcJI15akv7w8Aw", result[0].channelId)
        assertEquals("Veritasium", result[0].title)

        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", result[1].channelId)
        assertEquals("Kurzgesagt, in a Nutshell", result[1].title)
    }

    @Test
    fun parseYouTubeTakeoutCsv_withByteOrderMark_extractsChannels() {
        val csvText = "\uFEFFChannel Id,Channel Url,Channel Title\nUCv6JnTIupzcJI15akv7w8Aw,http://www.youtube.com/channel/UCv6JnTIupzcJI15akv7w8Aw,Veritasium"

        val result = SubscriptionImporter.parseSubscriptionsFile(csvText)

        assertEquals(1, result.size)
        assertEquals("UCv6JnTIupzcJI15akv7w8Aw", result[0].channelId)
        assertEquals("Veritasium", result[0].title)
    }
}
