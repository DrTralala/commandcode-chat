package com.commandcode.chat.server

import kotlinx.serialization.Serializable

@Serializable
data class ModelDto(
    val id: String,
    val displayName: String,
    val apiFamily: String,
)

@Serializable
data class ModelCatalogueResponse(
    val schemaVersion: Int,
    val catalogueVersion: String,
    val generatedAt: Long,
    val models: List<ModelDto>,
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

@Serializable
data class RemainingQuota(
    val remaining: Double,
    val cap: Double,
)

@Serializable
data class UsedQuota(
    val used: Double,
    val cap: Double,
    val resetAt: Long,
)

@Serializable
data class QuotaResponse(
    val schemaVersion: Int = 1,
    val fetchedAt: Long,
    val planId: String,
    val limited: Boolean,
    val monthly: RemainingQuota,
    val fiveHour: UsedQuota,
    val weekly: UsedQuota,
    val purchasedCredits: Double,
    val freeCredits: Double,
)
