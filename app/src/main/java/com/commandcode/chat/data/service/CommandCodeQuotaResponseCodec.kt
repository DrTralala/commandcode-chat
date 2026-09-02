package com.commandcode.chat.data.service

import java.math.BigDecimal
import java.time.Instant
import org.json.JSONObject
import org.json.JSONTokener

internal object CommandCodeQuotaResponseCodec {
    fun decode(
        text: String,
        fetchedAt: Instant,
        subscriptionPlanId: String?,
    ): QuotaSnapshot = try {
        decodeObject(parseUniqueJsonObject(text, "Quota response"), fetchedAt, subscriptionPlanId)
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Invalid quota response JSON", exception)
    }

    private fun decodeObject(
        root: JSONObject,
        fetchedAt: Instant,
        subscriptionPlanId: String?,
    ): QuotaSnapshot {
        val credits = objectValue(root, "credits")
        val windowLimits = nullableObjectValue(root, "windowLimits")
        val fiveHour = windowLimits?.let { objectValue(it, "fiveHour") }
        val weekly = windowLimits?.let { objectValue(it, "weekly") }

        val snapshot = QuotaSnapshot(
            fetchedAt = fetchedAt,
            planId = subscriptionPlanId,
            limited = windowLimits?.let { booleanValue(it, "limited") },
            monthly = RemainingQuota(
                remaining = amountValue(credits, "monthlyCredits"),
                cap = commandCodePlan(subscriptionPlanId)?.monthlyCap,
            ),
            fiveHour = fiveHour?.let(::windowValue),
            weekly = weekly?.let(::windowValue),
            purchasedCredits = optionalAmountValue(credits, "purchasedCredits"),
            freeCredits = optionalAmountValue(credits, "freeCredits"),
        )
        return QuotaSnapshotCodec.validate(snapshot)
    }

    private fun objectValue(objectValue: JSONObject, key: String): JSONObject {
        val value = objectValue.get(key)
        require(value is JSONObject) { "$key must be an object" }
        return value
    }

    private fun nullableObjectValue(objectValue: JSONObject, key: String): JSONObject? {
        if (!objectValue.has(key)) return null
        val value = objectValue.get(key)
        if (value === JSONObject.NULL) return null
        require(value is JSONObject) { "$key must be an object or null" }
        return value
    }

    private fun windowValue(window: JSONObject): UsedQuota = UsedQuota(
        used = amountValue(window, "used"),
        cap = capValue(window, "cap"),
        resetAt = Instant.ofEpochMilli(epochMillis(window, "resetAt")),
    )

    private fun booleanValue(objectValue: JSONObject, key: String): Boolean {
        val value = objectValue.get(key)
        require(value is Boolean) { "$key must be a boolean" }
        return value
    }

    private fun epochMillis(objectValue: JSONObject, key: String): Long {
        val value = decimalValue(objectValue, key)
        val millis = try {
            value.longValueExact()
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("$key must be an integer millisecond timestamp", exception)
        }
        require(millis > 0) { "$key must be positive" }
        return millis
    }

    private fun amountValue(objectValue: JSONObject, key: String): Double =
        decimalValue(objectValue, key).toValidatedDouble(key, allowZero = true)

    private fun optionalAmountValue(objectValue: JSONObject, key: String): Double =
        if (objectValue.has(key)) amountValue(objectValue, key) else 0.0

    private fun capValue(objectValue: JSONObject, key: String): Double =
        decimalValue(objectValue, key).toValidatedDouble(key, allowZero = false)

    private fun decimalValue(objectValue: JSONObject, key: String): BigDecimal {
        val value = objectValue.get(key)
        require(value is Number) { "$key must be a number" }
        return try {
            BigDecimal(value.toString())
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("$key must be a number", exception)
        }
    }

    private fun BigDecimal.toValidatedDouble(key: String, allowZero: Boolean): Double {
        require(signum() >= 0 && (allowZero || signum() > 0)) {
            "$key must be non-negative and cap must be positive"
        }
        val converted = toDouble()
        require(converted.isFinite() && (allowZero || converted > 0.0)) {
            "$key must be finite and cap must remain positive after conversion"
        }
        return converted
    }

}

internal fun parseUniqueJsonObject(text: String, label: String): JSONObject {
    rejectDuplicateKeys(text)
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    require(value is JSONObject) { "$label must be a JSON object" }
    require(tokener.nextClean() == '\u0000') { "Unexpected trailing $label data" }
    return value
}

/** JSONObject keeps only the last value for a repeated name, so scan first. */
private fun rejectDuplicateKeys(text: String) {
    val tokener = JSONTokener(text)
    scanValue(tokener)
    require(tokener.nextClean() == '\u0000') { "Unexpected trailing response data" }
}

private fun scanValue(tokener: JSONTokener) {
    when (val first = tokener.nextClean()) {
        '{' -> scanObject(tokener)
        '[' -> scanArray(tokener)
        '"', '\'' -> tokener.nextString(first)
        else -> {
            tokener.back()
            tokener.nextValue()
        }
    }
}

private fun scanObject(tokener: JSONTokener) {
    val keys = mutableSetOf<String>()
    if (tokener.nextClean() == '}') return
    tokener.back()
    while (true) {
        val key = tokener.nextValue()
        require(key is String) { "Object field name must be a string" }
        require(keys.add(key)) { "Duplicate response field" }
        require(tokener.nextClean() == ':') { "Expected object field separator" }
        scanValue(tokener)
        when (tokener.nextClean()) {
            ',' -> Unit
            '}' -> return
            else -> throw IllegalArgumentException("Expected object field separator")
        }
    }
}

private fun scanArray(tokener: JSONTokener) {
    if (tokener.nextClean() == ']') return
    tokener.back()
    while (true) {
        scanValue(tokener)
        when (tokener.nextClean()) {
            ',' -> Unit
            ']' -> return
            else -> throw IllegalArgumentException("Expected array value separator")
        }
    }
}
