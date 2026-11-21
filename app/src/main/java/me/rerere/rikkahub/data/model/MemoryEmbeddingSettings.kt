package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryEmbeddingSettings(
    val enabled: Boolean = false,
    val endpoint: String = "https://api.openai.com/v1/embeddings",
    val apiKey: String = "",
    val model: String = "text-embedding-3-small",
    val semanticWeight: Double = 0.6,
) {
    override fun toString(): String =
        "MemoryEmbeddingSettings(enabled=$enabled, endpoint='$endpoint', apiKey='***', model='$model', semanticWeight=$semanticWeight)"
}
