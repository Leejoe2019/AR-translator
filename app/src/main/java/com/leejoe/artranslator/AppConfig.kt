package com.leejoe.artranslator

data class AppConfig(
    val endpoint: String,
    val model: String,
    val targetLanguage: String
) {
    fun normalizedEndpoint(): String {
        val value = endpoint.trim().trimEnd('/')
        return if (value.endsWith("/v1/responses")) value else "$value/v1/responses"
    }
}
