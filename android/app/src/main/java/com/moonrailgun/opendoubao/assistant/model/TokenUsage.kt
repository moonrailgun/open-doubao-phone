package com.moonrailgun.opendoubao.assistant.model

import org.json.JSONObject

data class TokenUsage(val input: Long, val output: Long, val cached: Long) {
    init { require(input >= 0 && output >= 0 && cached in 0..input) }

    companion object {
        /** Missing or malformed usage is unknown, never a free request. */
        fun parse(response: String): TokenUsage? = runCatching {
            val usage = JSONObject(response).getJSONObject("usage")
            fun count(json: JSONObject, field: String): Long {
                val value = json.get(field)
                require(value is Number)
                return value.toString().toBigDecimal().longValueExact().also { require(it >= 0) }
            }
            val chat = usage.has("prompt_tokens")
            val input = count(usage, if (chat) "prompt_tokens" else "input_tokens")
            val output = count(usage, if (chat) "completion_tokens" else "output_tokens")
            val detailsField = if (chat) "prompt_tokens_details" else "input_tokens_details"
            val details = if (usage.isNull(detailsField)) null else usage.getJSONObject(detailsField)
            val cached = if (details?.has("cached_tokens") == true) count(details, "cached_tokens") else 0L
            TokenUsage(input, output, cached)
        }.getOrNull()
    }
}
