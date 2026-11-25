package io.tehred5pade.frontporch.data.importexport

import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports the local subscription list in NewPipe's JSON backup format so it can be
 * re-imported by FrontPorch or NewPipe itself.
 */
object SubscriptionExporter {

    fun toNewPipeJson(subscriptions: List<SubscriptionEntity>): String {
        val subscriptionsArray = JSONArray()
        subscriptions
            .sortedBy { it.name.lowercase() }
            .forEach { subscription ->
                subscriptionsArray.put(
                    JSONObject()
                        .put("service_id", 0)
                        .put(
                            "url",
                            subscription.url.ifBlank {
                                "https://www.youtube.com/channel/${subscription.channelId}"
                            }
                        )
                        .put("name", subscription.name)
                )
            }

        return JSONObject()
            .put("app", "NewPipe")
            .put("version", "0.27.10")
            .put("subscriptions", subscriptionsArray)
            .toString(2)
    }
}
