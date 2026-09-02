package com.commandcode.chat.data.service

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ModelCatalogueSnapshot(
    val schemaVersion: Int,
    val catalogueVersion: String,
    val generatedAt: Long,
    val models: List<ChatModel>,
)

interface ModelCatalogueSource {
    suspend fun loadLocal(): ModelCatalogueSnapshot
    suspend fun refresh(): ModelCatalogueSnapshot
}

data class RemainingQuota(val remaining: Double, val cap: Double?)

data class UsedQuota(val used: Double, val cap: Double, val resetAt: Instant)

data class QuotaSnapshot(
    val fetchedAt: Instant,
    val planId: String?,
    val limited: Boolean?,
    val monthly: RemainingQuota,
    val fiveHour: UsedQuota?,
    val weekly: UsedQuota?,
    val purchasedCredits: Double,
    val freeCredits: Double,
)

internal const val UNREPORTED_PLAN_ID: String = "unreported"

interface QuotaApi {
    suspend fun fetchQuota(apiKey: CharArray): QuotaSnapshot
}

interface QuotaSnapshotStore {
    fun loadQuota(): QuotaSnapshot?
    fun saveQuota(snapshot: QuotaSnapshot)
    fun clearQuota()
}

interface QuotaSource {
    suspend fun loadCached(): QuotaSnapshot?
    suspend fun refresh(apiKey: CharArray): QuotaSnapshot
    suspend fun clear()
}

internal object ModelCatalogueCodec {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_MODELS = 100
    const val MAX_CATALOGUE_BYTES = 131_072
    const val SUPPORTED_API_FAMILY = "openai-chat"

    fun validate(snapshot: ModelCatalogueSnapshot): ModelCatalogueSnapshot {
        require(snapshot.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported model catalogue schema"
        }
        require(snapshot.catalogueVersion.isNotBlank()) {
            "Catalogue version must not be blank"
        }
        require(snapshot.generatedAt > 0) {
            "Catalogue generatedAt must be positive"
        }
        require(snapshot.models.size <= MAX_MODELS) {
            "Model catalogue exceeds the maximum model count"
        }

        val ids = HashSet<String>(snapshot.models.size)
        snapshot.models.forEach { model ->
            require(model.apiId.isNotBlank()) { "Model id must not be blank" }
            require(model.displayName.isNotBlank()) { "Model displayName must not be blank" }
            require(model.apiFamily.wireValue == SUPPORTED_API_FAMILY) {
                "Unsupported model API family"
            }
            require(ids.add(model.apiId)) { "Duplicate model id" }
        }
        require(snapshot.models.singleOrNull { it.apiId == ChatModel.SOL.apiId } == ChatModel.SOL) {
            "Model catalogue must contain the canonical GPT-5.6 Sol entry"
        }
        return snapshot.copy(models = snapshot.models.toList())
    }

    fun decode(text: String): ModelCatalogueSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CATALOGUE_BYTES) {
            "Model catalogue exceeds the maximum size"
        }
        return try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue()
            require(root is JSONObject) { "Model catalogue must be a JSON object" }
            require(tokener.nextClean() == '\u0000') { "Unexpected trailing model catalogue data" }
            decodeObject(root)
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid model catalogue JSON", exception)
        }
    }

    fun encode(snapshot: ModelCatalogueSnapshot): String {
        val validated = validate(snapshot)
        val encoded = JSONObject()
            .put("schemaVersion", validated.schemaVersion)
            .put("catalogueVersion", validated.catalogueVersion)
            .put("generatedAt", validated.generatedAt)
            .put("models", JSONArray().apply {
                validated.models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.apiId)
                            .put("displayName", model.displayName)
                            .put("apiFamily", model.apiFamily.wireValue),
                    )
                }
            })
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CATALOGUE_BYTES) {
            "Model catalogue exceeds the maximum size"
        }
        return encoded
    }

    private fun decodeObject(root: JSONObject): ModelCatalogueSnapshot {
        requireKeys(root, setOf("schemaVersion", "catalogueVersion", "generatedAt", "models"))
        val modelsValue = root.get("models")
        require(modelsValue is JSONArray) { "Models must be an array" }
        require(modelsValue.length() <= MAX_MODELS) {
            "Model catalogue exceeds the maximum model count"
        }
        val models = List(modelsValue.length()) { index ->
            val modelObject = modelsValue.get(index)
            require(modelObject is JSONObject) { "Model must be an object" }
            requireKeys(modelObject, setOf("id", "displayName", "apiFamily"))
            val id = stringValue(modelObject, "id")
            val displayName = stringValue(modelObject, "displayName")
            val apiFamily = stringValue(modelObject, "apiFamily")
            require(apiFamily == SUPPORTED_API_FAMILY) { "Unsupported model API family" }
            ChatModel(id, displayName, ApiFamily.OPENAI_CHAT)
        }
        return validate(
            ModelCatalogueSnapshot(
                schemaVersion = intValue(root, "schemaVersion"),
                catalogueVersion = stringValue(root, "catalogueVersion"),
                generatedAt = longValue(root, "generatedAt"),
                models = models,
            ),
        )
    }

    private fun requireKeys(objectValue: JSONObject, expected: Set<String>) {
        require(objectValue.keys().asSequence().toSet() == expected) { "Unexpected model catalogue fields" }
    }

    private fun stringValue(objectValue: JSONObject, key: String): String {
        val value = objectValue.get(key)
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun intValue(objectValue: JSONObject, key: String): Int {
        val value = integerValue(objectValue, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key must be an integer" }
        return value.toInt()
    }

    private fun longValue(objectValue: JSONObject, key: String): Long = integerValue(objectValue, key)

    private fun integerValue(objectValue: JSONObject, key: String): Long {
        val value = objectValue.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        return value.toLong()
    }
}

internal object QuotaSnapshotCodec {
    const val SUPPORTED_SCHEMA_VERSION = 2
    const val MAX_QUOTA_BYTES = 65_536

    private const val LEGACY_SCHEMA_VERSION = 1

    fun validate(snapshot: QuotaSnapshot): QuotaSnapshot {
        require(epochMillis(snapshot.fetchedAt, "fetchedAt") > 0) {
            "fetchedAt must be positive"
        }
        snapshot.planId?.let { require(it.isNotBlank()) { "planId must not be blank" } }
        requireAmount(snapshot.monthly.remaining, "monthly.remaining")
        snapshot.monthly.cap?.let { requireCap(it, "monthly.cap") }
        val hasNoWindows = snapshot.limited == null && snapshot.fiveHour == null && snapshot.weekly == null
        val hasAllWindows = snapshot.limited != null && snapshot.fiveHour != null && snapshot.weekly != null
        require(hasNoWindows || hasAllWindows) {
            "Rolling quota fields must be all present or all absent"
        }
        snapshot.fiveHour?.let { validateWindow(it, "fiveHour") }
        snapshot.weekly?.let { validateWindow(it, "weekly") }
        requireAmount(snapshot.purchasedCredits, "purchasedCredits")
        requireAmount(snapshot.freeCredits, "freeCredits")
        return snapshot
    }

    fun decode(text: String): QuotaSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_QUOTA_BYTES) {
            "Quota snapshot exceeds the maximum size"
        }
        return try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue()
            require(root is JSONObject) { "Quota snapshot must be a JSON object" }
            require(tokener.nextClean() == '\u0000') { "Unexpected trailing quota data" }
            decodeObject(root)
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid quota JSON", exception)
        }
    }

    fun encode(snapshot: QuotaSnapshot): String {
        val validated = validate(snapshot)
        val windowLimits = if (validated.limited == null) {
            JSONObject.NULL
        } else {
            val fiveHour = checkNotNull(validated.fiveHour)
            val weekly = checkNotNull(validated.weekly)
            JSONObject()
                .put("limited", validated.limited)
                .put("fiveHour", encodeWindow(fiveHour, "fiveHour"))
                .put("weekly", encodeWindow(weekly, "weekly"))
        }
        val encoded = JSONObject()
            .put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
            .put("fetchedAt", epochMillis(validated.fetchedAt, "fetchedAt"))
            .put("planId", validated.planId ?: JSONObject.NULL)
            .put(
                "monthly",
                JSONObject()
                    .put("remaining", validated.monthly.remaining)
                    .put("cap", validated.monthly.cap ?: JSONObject.NULL),
            )
            .put("windowLimits", windowLimits)
            .put("purchasedCredits", validated.purchasedCredits)
            .put("freeCredits", validated.freeCredits)
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_QUOTA_BYTES) {
            "Quota snapshot exceeds the maximum size"
        }
        return encoded
    }

    private fun decodeObject(root: JSONObject): QuotaSnapshot = when (intValue(root, "schemaVersion")) {
        LEGACY_SCHEMA_VERSION -> decodeLegacyObject(root)
        SUPPORTED_SCHEMA_VERSION -> decodeCurrentObject(root)
        else -> throw IllegalArgumentException("Unsupported quota schema")
    }

    private fun decodeLegacyObject(root: JSONObject): QuotaSnapshot {
        requireKeys(
            root,
            setOf(
                "schemaVersion",
                "fetchedAt",
                "planId",
                "limited",
                "monthly",
                "fiveHour",
                "weekly",
                "purchasedCredits",
                "freeCredits",
            ),
        )
        val monthly = objectValue(root, "monthly")
        val fiveHour = objectValue(root, "fiveHour")
        val weekly = objectValue(root, "weekly")
        requireKeys(monthly, setOf("remaining", "cap"))
        requireKeys(fiveHour, setOf("used", "cap", "resetAt"))
        requireKeys(weekly, setOf("used", "cap", "resetAt"))

        val legacyPlanId = stringValue(root, "planId")
        return validate(
            QuotaSnapshot(
                fetchedAt = Instant.ofEpochMilli(epochMillis(root, "fetchedAt")),
                planId = legacyPlanId.takeUnless { it == UNREPORTED_PLAN_ID },
                limited = booleanValue(root, "limited"),
                monthly = RemainingQuota(
                    remaining = amountValue(monthly, "remaining"),
                    cap = capValue(monthly, "cap"),
                ),
                fiveHour = decodeWindow(fiveHour),
                weekly = decodeWindow(weekly),
                purchasedCredits = amountValue(root, "purchasedCredits"),
                freeCredits = amountValue(root, "freeCredits"),
            ),
        )
    }

    private fun decodeCurrentObject(root: JSONObject): QuotaSnapshot {
        requireKeys(
            root,
            setOf(
                "schemaVersion",
                "fetchedAt",
                "planId",
                "monthly",
                "windowLimits",
                "purchasedCredits",
                "freeCredits",
            ),
        )
        val monthly = objectValue(root, "monthly")
        requireKeys(monthly, setOf("remaining", "cap"))
        val windowsValue = root.get("windowLimits")
        val windows = if (windowsValue === JSONObject.NULL) {
            null
        } else {
            require(windowsValue is JSONObject) { "windowLimits must be an object or null" }
            requireKeys(windowsValue, setOf("limited", "fiveHour", "weekly"))
            windowsValue
        }

        return validate(
            QuotaSnapshot(
                fetchedAt = Instant.ofEpochMilli(epochMillis(root, "fetchedAt")),
                planId = nullableStringValue(root, "planId"),
                limited = windows?.let { booleanValue(it, "limited") },
                monthly = RemainingQuota(
                    remaining = amountValue(monthly, "remaining"),
                    cap = nullableCapValue(monthly, "cap"),
                ),
                fiveHour = windows?.let { decodeWindow(objectValue(it, "fiveHour")) },
                weekly = windows?.let { decodeWindow(objectValue(it, "weekly")) },
                purchasedCredits = amountValue(root, "purchasedCredits"),
                freeCredits = amountValue(root, "freeCredits"),
            ),
        )
    }

    private fun encodeWindow(window: UsedQuota, label: String): JSONObject = JSONObject()
        .put("used", window.used)
        .put("cap", window.cap)
        .put("resetAt", epochMillis(window.resetAt, "$label.resetAt"))

    private fun decodeWindow(window: JSONObject): UsedQuota {
        requireKeys(window, setOf("used", "cap", "resetAt"))
        return UsedQuota(
            used = amountValue(window, "used"),
            cap = capValue(window, "cap"),
            resetAt = Instant.ofEpochMilli(epochMillis(window, "resetAt")),
        )
    }

    private fun validateWindow(window: UsedQuota, label: String) {
        requireAmount(window.used, "$label.used")
        requireCap(window.cap, "$label.cap")
        require(epochMillis(window.resetAt, "$label.resetAt") > 0) {
            "$label.resetAt must be positive"
        }
    }

    private fun objectValue(objectValue: JSONObject, key: String): JSONObject {
        require(objectValue.get(key) is JSONObject) { "$key must be an object" }
        return objectValue.getJSONObject(key)
    }

    private fun requireKeys(objectValue: JSONObject, expected: Set<String>) {
        require(objectValue.keys().asSequence().toSet() == expected) { "Unexpected quota fields" }
    }

    private fun stringValue(objectValue: JSONObject, key: String): String {
        val value = objectValue.get(key)
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun nullableStringValue(objectValue: JSONObject, key: String): String? {
        val value = objectValue.get(key)
        if (value === JSONObject.NULL) return null
        require(value is String) { "$key must be a string or null" }
        return value
    }

    private fun booleanValue(objectValue: JSONObject, key: String): Boolean {
        val value = objectValue.get(key)
        require(value is Boolean) { "$key must be a boolean" }
        return value
    }

    private fun intValue(objectValue: JSONObject, key: String): Int {
        val value = objectValue.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        require(value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) { "$key must be an integer" }
        return value.toInt()
    }

    private fun epochMillis(objectValue: JSONObject, key: String): Long {
        val value = objectValue.get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        val millis = value.toLong()
        require(millis > 0) { "$key must be positive" }
        return millis
    }

    private fun epochMillis(instant: Instant, key: String): Long = try {
        instant.toEpochMilli().also { require(it > 0) { "$key must be positive" } }
    } catch (exception: ArithmeticException) {
        throw IllegalArgumentException("$key must be a valid millisecond timestamp", exception)
    }

    private fun amountValue(objectValue: JSONObject, key: String): Double =
        decimalValue(objectValue, key).toValidatedDouble(allowZero = true)

    private fun capValue(objectValue: JSONObject, key: String): Double =
        decimalValue(objectValue, key).toValidatedDouble(allowZero = false)

    private fun nullableCapValue(objectValue: JSONObject, key: String): Double? =
        if (objectValue.get(key) === JSONObject.NULL) null else capValue(objectValue, key)

    private fun decimalValue(objectValue: JSONObject, key: String): BigDecimal {
        val value = objectValue.get(key)
        require(value is Number) { "$key must be a number" }
        return try {
            BigDecimal(value.toString())
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("$key must be a number", exception)
        }
    }

    private fun requireAmount(value: Double, key: String) {
        require(value.isFinite() && value >= 0.0) { "$key must be non-negative and finite" }
    }

    private fun requireCap(value: Double, key: String) {
        require(value.isFinite() && value > 0.0) { "$key must be positive and finite" }
    }

    private fun BigDecimal.toValidatedDouble(allowZero: Boolean): Double {
        require(signum() >= 0 && (allowZero || signum() > 0)) {
            "Quota amount must not be negative and cap must be positive"
        }
        val value = toDouble()
        require(value.isFinite() && (allowZero || value > 0.0)) {
            "Quota amount must be finite and cap must be positive"
        }
        return value
    }
}
