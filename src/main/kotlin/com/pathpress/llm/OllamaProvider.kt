package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [HttpLlmProvider] implementation targeting local Ollama REST instances
 * (`http://localhost:11434/api/chat`).
 *
 * Requests structured JSON responses using `"format": "json"` mode without requiring an API key.
 * Automatically falls back to [NoOpFallbackProvider] on network failures, non-200 responses, or
 * parse errors.
 */
class OllamaProvider(
    private val endpoint: String,
    config: Config = Config.current,
    val modelName: String = config.defaultOllamaModel,
) : HttpLlmProvider(config) {
    override fun complete(prompt: String): String? {
        val requestBody =
            mapper.writeValueAsString(
                mapOf(
                    "model" to modelName,
                    "messages" to
                        listOf(
                            mapOf(
                                "role" to "system",
                                "content" to "You are a helpful travel planner that outputs JSON.",
                            ),
                            mapOf("role" to "user", "content" to prompt),
                        ),
                    "stream" to false,
                    "format" to "json",
                    "options" to mapOf("temperature" to 0.1),
                )
            )

        val uri = URI.create(endpoint)
        val request =
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.httpLlmConnectTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val root: Map<String, Any> = mapper.readValue(response.body())
            val message = root["message"] as? Map<*, *>
            val responseText = message?.get("content") as? String
            return if (!responseText.isNullOrBlank()) responseText else null
        } else {
            logger.warn(
                "Ollama API returned status code {}: {}",
                response.statusCode(),
                response.body().take(200),
            )
            return null
        }
    }
}
