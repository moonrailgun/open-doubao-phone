package com.moonrailgun.opendoubao.assistant.model

import org.json.JSONObject

data class CompanionConfig(val intervalSeconds: Int = 20, val useDedicatedModel: Boolean = false,
    val endpoint: String = "", val model: String = "", val key: String = "") {
    fun modelConfig(general: ModelConfig): ModelConfig {
        require(intervalSeconds in 5..300) { "陪伴请求间隔请填写 5–300 秒" }
        return general.copy(jevKey = if (general.jevVision) general.jevKey else "",
            chatEndpoint = if (useDedicatedModel) endpoint else general.chatEndpoint,
            chatModel = if (useDedicatedModel) model else general.chatModel,
            chatKey = if (useDedicatedModel) key else general.chatKey).also { it.validate() }
    }

    fun json(): JSONObject = JSONObject().put("intervalSeconds", intervalSeconds).put("useDedicatedModel", useDedicatedModel)
        .put("endpoint", endpoint).put("model", model).put("key", key)

    companion object {
        fun fromJson(json: JSONObject?): CompanionConfig = if (json == null) CompanionConfig() else CompanionConfig(
            json.optInt("intervalSeconds", 20), json.optBoolean("useDedicatedModel", false),
            json.optString("endpoint"), json.optString("model"), json.optString("key"))
    }
}
