package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [HttpLlmProvider] implementation targeting Anthropic's Claude API (`/v1/messages`).
 *
 * Configures `x-api-key` and `anthropic-version: 2023-06-01` HTTP headers. Automatically falls back
 * to [NoOpFallbackProvider] on network failures, non-200 responses, or parse errors.
 */
class ClaudeProvider(
    apiKey: String,
    config: Config = Config.current,
    val modelName: String = config.defaultClaudeModel,
) : HttpLlmProvider(config) {
    private val apiKey: String = apiKey.validateApiKey(LlmProviderType.CLAUDE)

    override fun complete(prompt: String): String? {
        val requestBody =
            mapper.writeValueAsString(
                mapOf(
                    "model" to modelName,
                    "max_tokens" to 1024,
                    "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                )
            )

        val uri = URI.create("https://api.anthropic.com/v1/messages")
        val request =
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.httpLlmRequestTimeout)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val root: Map<String, Any> = mapper.readValue(response.body())
            val content = root["content"] as? List<*>
            val firstContent = content?.firstOrNull() as? Map<*, *>
            val text = firstContent?.get("text") as? String
            return if (!text.isNullOrBlank()) text else null
        } else {
            logger.warn(
                "Claude API returned status code {}: {}",
                response.statusCode(),
                response.body().take(200),
            )
            return null
        }
    }
}
