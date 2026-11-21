package com.example.frontporch

import com.example.frontporch.data.importexport.NewPipeSubscriptionImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class NewPipeSubscriptionImporterTest {

    @Test
    fun parseFromJson_validSubscriptions_extractsChannels() {
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
                },
                {
                  "service_id": 1,
                  "url": "https://soundcloud.com/user-123456789",
                  "name": "Non-YouTube Service"
                }
              ]
            }
        """.trimIndent()

        val result = NewPipeSubscriptionImporter.parseFromJson(jsonText)

        assertEquals(2, result.size)
        assertEquals("UCv6JnTIupzcJI15akv7w8Aw", result[0].channelId)
        assertEquals("Veritasium", result[0].name)
        assertEquals("https://www.youtube.com/channel/UCv6JnTIupzcJI15akv7w8Aw", result[0].url)

        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", result[1].channelId)
        assertEquals("Kurzgesagt", result[1].name)
    }
}
