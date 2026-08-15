package com.pathpress.llm

import com.fasterxml.jackson.module.kotlin.readValue
import com.pathpress.config.Config
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [HttpLlmProvider] implementation targeting Google's Gemini API
 * (`/v1beta/models/:generateContent`).
 *
 * Uses query parameter API key authentication and standard `contents[].parts[].text` payload
 * formatting.
 */
class GeminiProvider(
    apiKey: String,
    config: Config,
    val modelName: String = config.defaultGeminiModel,
) : HttpLlmProvider(config) {
    private val apiKey: String = apiKey.validateApiKey(LlmProviderType.GEMINI)

    override fun complete(prompt: String): String? {
        val requestBody =
            mapper.writeValueAsString(
                mapOf("contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))))
            )

        val uri =
            URI.create(
                "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            )
        val request =
            HttpRequest.newBuilder()
                .uri(uri)
                .timeout(config.httpLlmRequestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val root: Map<String, Any> = mapper.readValue(response.body())
            val candidates = root["candidates"] as? List<*>
            val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
            val content = firstCandidate?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String

            return if (!text.isNullOrBlank()) text else null
        } else {
            logger.warn(
                "Gemini API returned status code {}: {}",
                response.statusCode(),
                response.body().take(200),
            )
            return null
        }
    }
}
