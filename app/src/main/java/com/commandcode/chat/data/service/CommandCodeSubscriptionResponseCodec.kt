package com.commandcode.chat.data.service

import java.time.Instant
import org.json.JSONObject

internal data class SubscriptionMetadata(val planId: String?, val renewsAt: Instant?)

internal object CommandCodeSubscriptionResponseCodec {
    fun decode(text: String, now: Instant): SubscriptionMetadata = try {
        decodeObject(parseUniqueJsonObject(text, "Subscription response"), now)
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Invalid subscription response JSON", exception)
    }

    fun decodePlanId(text: String): String? = decode(text, Instant.MIN).planId

    private fun decodeObject(root: JSONObject, now: Instant): SubscriptionMetadata {
        if (root.has("success")) {
            val success = root.get("success")
            require(success is Boolean) { "success must be a boolean" }
            if (!success) return SubscriptionMetadata(planId = null, renewsAt = null)
        }
        if (!root.has("data") || root.get("data") === JSONObject.NULL) {
            return SubscriptionMetadata(planId = null, renewsAt = null)
        }
        val data = root.get("data")
        require(data is JSONObject) { "data must be an object or null" }
        val planId = if (!data.has("planId") || data.get("planId") === JSONObject.NULL) {
            null
        } else {
            val value = data.get("planId")
            require(value is String) { "planId must be a string or null" }
            require(value.isNotBlank()) { "planId must not be blank" }
            value
        }
        val renewsAt = if (data.opt("status") == "active" && data.opt("cancelAtPeriodEnd") == false) {
            (data.opt("currentPeriodEnd") as? String)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?.takeIf { renewal ->
                    renewal.isAfter(now) && runCatching { renewal.toEpochMilli() > 0 }.getOrDefault(false)
                }
        } else {
            null
        }
        return SubscriptionMetadata(planId = planId, renewsAt = renewsAt)
    }
}
