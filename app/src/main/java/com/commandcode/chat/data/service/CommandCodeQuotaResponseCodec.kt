package com.commandcode.chat.data.service

import java.math.BigDecimal
import java.time.Instant
import org.json.JSONObject
import org.json.JSONTokener

internal object CommandCodeQuotaResponseCodec {
    private val ROOT_KEYS = setOf("credits", "windowLimits")
    private val LEGACY_CREDIT_KEYS =
        setOf("planId", "monthlyCredits", "purchasedCredits", "freeCredits")
    private val CURRENT_CREDIT_KEYS =
        setOf("creditThreshold", "monthlyCredits", "purchasedCredits", "freeCredits")
    private val CREDIT_STATUS_KEYS = setOf("belowThreshold")
    private val WINDOW_LIMIT_KEYS = setOf("limited", "fiveHour", "weekly")
    private val WINDOW_LIMIT_STATUS_KEYS = setOf("exceeded")
    private val WINDOW_KEYS = setOf("used", "cap", "resetAt")
    private val WINDOW_STATUS_KEYS = setOf("exceeded")
    private const val MONTHLY_CAP = 70.0

    fun decode(text: String, fetchedAt: Instant): QuotaSnapshot = try {
        rejectDuplicateKeys(text)
        val tokener = JSONTokener(text)
        val root = tokener.nextValue()
        require(root is JSONObject) { "Quota response must be a JSON object" }
        require(tokener.nextClean() == '\u0000') { "Unexpected trailing quota response data" }
        decodeObject(root, fetchedAt)
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Invalid quota response JSON", exception)
    }

    private fun decodeObject(root: JSONObject, fetchedAt: Instant): QuotaSnapshot {
        requireKeys(root, ROOT_KEYS)
        val credits = objectValue(root, "credits")
        val windowLimits = objectValue(root, "windowLimits")
        val creditKeys = credits.keys().asSequence().toSet()
        val requiredCreditKeys = creditKeys - CREDIT_STATUS_KEYS
        val planId = when (requiredCreditKeys) {
            LEGACY_CREDIT_KEYS -> stringValue(credits, "planId")
            CURRENT_CREDIT_KEYS -> {
                amountValue(credits, "creditThreshold")
                UNREPORTED_PLAN_ID
            }
            else -> throw IllegalArgumentException("Unexpected quota response fields")
        }
        requireAllowedKeys(credits, requiredCreditKeys, CREDIT_STATUS_KEYS)
        validateOptionalBoolean(credits, "belowThreshold", allowNull = false)
        requireAllowedKeys(windowLimits, WINDOW_LIMIT_KEYS, WINDOW_LIMIT_STATUS_KEYS)
        val fiveHour = objectValue(windowLimits, "fiveHour")
        val weekly = objectValue(windowLimits, "weekly")
        requireAllowedKeys(fiveHour, WINDOW_KEYS, WINDOW_STATUS_KEYS)
        requireAllowedKeys(weekly, WINDOW_KEYS, WINDOW_STATUS_KEYS)
        validateOptionalBoolean(windowLimits, "exceeded", allowNull = true)
        validateOptionalBoolean(fiveHour, "exceeded", allowNull = false)
        validateOptionalBoolean(weekly, "exceeded", allowNull = false)

        val snapshot = QuotaSnapshot(
            fetchedAt = fetchedAt,
            planId = planId,
            limited = booleanValue(windowLimits, "limited"),
            monthly = RemainingQuota(
                remaining = amountValue(credits, "monthlyCredits"),
                cap = MONTHLY_CAP,
            ),
            fiveHour = UsedQuota(
                used = amountValue(fiveHour, "used"),
                cap = capValue(fiveHour, "cap"),
                resetAt = Instant.ofEpochMilli(epochMillis(fiveHour, "resetAt")),
            ),
            weekly = UsedQuota(
                used = amountValue(weekly, "used"),
                cap = capValue(weekly, "cap"),
                resetAt = Instant.ofEpochMilli(epochMillis(weekly, "resetAt")),
            ),
            purchasedCredits = amountValue(credits, "purchasedCredits"),
            freeCredits = amountValue(credits, "freeCredits"),
        )
        return QuotaSnapshotCodec.validate(snapshot)
    }

    private fun objectValue(objectValue: JSONObject, key: String): JSONObject {
        val value = objectValue.get(key)
        require(value is JSONObject) { "$key must be an object" }
        return value
    }

    private fun requireKeys(objectValue: JSONObject, expected: Set<String>) {
        require(objectValue.keys().asSequence().toSet() == expected) {
            "Unexpected quota response fields"
        }
    }

    private fun requireAllowedKeys(
        objectValue: JSONObject,
        required: Set<String>,
        optional: Set<String>,
    ) {
        val actual = objectValue.keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) {
            "Unexpected quota response fields"
        }
    }

    private fun validateOptionalBoolean(objectValue: JSONObject, key: String, allowNull: Boolean) {
        if (!objectValue.has(key)) return
        val value = objectValue.get(key)
        require(value is Boolean || (allowNull && value == JSONObject.NULL)) {
            "$key must be a boolean${if (allowNull) " or null" else ""}"
        }
    }

    private fun stringValue(objectValue: JSONObject, key: String): String {
        val value = objectValue.get(key)
        require(value is String) { "$key must be a string" }
        require(value.isNotBlank()) { "$key must not be blank" }
        return value
    }

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

    /**
     * JSONObject keeps only the last value for a repeated name. Scan the same
     * JSON grammar first so duplicate fields cannot bypass the exact-key checks.
     */
    private fun rejectDuplicateKeys(text: String) {
        val tokener = JSONTokener(text)
        scanValue(tokener)
        require(tokener.nextClean() == '\u0000') { "Unexpected trailing quota response data" }
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
            require(keys.add(key)) { "Duplicate quota response field" }
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
}
