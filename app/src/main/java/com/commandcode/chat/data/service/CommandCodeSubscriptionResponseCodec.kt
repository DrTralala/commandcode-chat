package com.commandcode.chat.data.service

import org.json.JSONObject

internal object CommandCodeSubscriptionResponseCodec {
    fun decodePlanId(text: String): String? = try {
        decodeObject(parseUniqueJsonObject(text, "Subscription response"))
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Invalid subscription response JSON", exception)
    }

    private fun decodeObject(root: JSONObject): String? {
        if (root.has("success")) {
            val success = root.get("success")
            require(success is Boolean) { "success must be a boolean" }
            if (!success) return null
        }
        if (!root.has("data") || root.get("data") === JSONObject.NULL) return null
        val data = root.get("data")
        require(data is JSONObject) { "data must be an object or null" }
        if (!data.has("planId") || data.get("planId") === JSONObject.NULL) return null
        val planId = data.get("planId")
        require(planId is String) { "planId must be a string or null" }
        require(planId.isNotBlank()) { "planId must not be blank" }
        return planId
    }
}
