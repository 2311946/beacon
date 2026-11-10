package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AssistantStatus(
    val aiText: String = "",
    val generatedAtMillis: Long = 0L,
    val manualText: String? = null,
) {
    val displayText: String
        get() = manualText?.takeIf { it.isNotBlank() } ?: aiText
}
