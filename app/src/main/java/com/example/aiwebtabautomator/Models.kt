package com.example.aiwebtabautomator

import java.util.UUID

/** Coordinates are normalized to the visible WebView's width and height. */
data class CoordinateMapping(
    val host: String,
    val inputX: Float,
    val inputY: Float,
    val sendX: Float,
    val sendY: Float,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AutomationCommand(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val tabIndex: Int? = null,
    val host: String? = null,
    val callbackUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class AutomationResult(
    val requestId: String,
    val success: Boolean,
    val response: String = "",
    val error: String? = null,
    val tabIndex: Int? = null,
    val host: String? = null,
    val completedAt: Long = System.currentTimeMillis()
)

fun normalizeHost(rawUrlOrHost: String?): String? {
    if (rawUrlOrHost.isNullOrBlank()) return null
    val candidate = rawUrlOrHost.trim().lowercase()
    val host = if (candidate.contains("://")) {
        runCatching { android.net.Uri.parse(candidate).host }.getOrNull()
    } else {
        candidate.substringBefore('/').substringBefore(':')
    }
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}
