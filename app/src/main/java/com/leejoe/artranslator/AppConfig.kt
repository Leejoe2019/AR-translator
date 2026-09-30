package com.leejoe.artranslator

data class AppConfig(
    val endpoint: String,
    val model: String,
    val targetLanguage: String
) {
    fun nativeChatEndpoint(): String {
        val value = endpoint.trim().trimEnd('/')
        return when {
            value.endsWith("/api/v1/chat") -> value
            value.endsWith("/v1/responses") ->
                value.removeSuffix("/v1/responses") + "/api/v1/chat"
            value.endsWith("/v1/chat/completions") ->
                value.removeSuffix("/v1/chat/completions") + "/api/v1/chat"
            else -> "$value/api/v1/chat"
        }
    }
}
