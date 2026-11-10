package com.floatingtranslate.app

import android.content.Context

/**
 * 翻译引擎设置：用户可以在设置页自己选择提供翻译引擎。
 *
 * - google_free：Google 免费端点，无需 Key，国内网络可能不可用，失败自动降级 MyMemory
 * - mymemory：MyMemory 免费接口，无需 Key，有每日配额
 * - deepl：DeepL API Free，需要用户自己的 DeepL-Auth-Key
 * - custom：用户自己的接口地址 + Key，POST JSON {text, source, target}
 *
 * 存在 SharedPreferences（FloatingBallService.PREFS），悬浮面板和主界面共用。
 */
object EngineSettings {

    const val KEY_ENGINE = "engine"
    const val KEY_DEEPL_KEY = "deepl_key"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_CUSTOM_KEY = "custom_key"
    // 智能回复（v1.5.0）：OpenAI 兼容大模型接口，不填就用本地规则
    const val KEY_LLM_URL = "llm_url"
    const val KEY_LLM_KEY = "llm_key"
    const val KEY_LLM_MODEL = "llm_model"

    const val ENGINE_GOOGLE_FREE = "google_free"
    const val ENGINE_MYMEMORY = "mymemory"
    const val ENGINE_DEEPL = "deepl"
    const val ENGINE_CUSTOM = "custom"

    data class Engine(val id: String, val label: String, val hint: String)

    val ENGINES = listOf(
        Engine(ENGINE_GOOGLE_FREE, "Google 免费", "无需 Key；国内网络可能不可用，失败自动降级 MyMemory"),
        Engine(ENGINE_MYMEMORY, "MyMemory 免费", "无需 Key；有每日配额，适合备用"),
        Engine(ENGINE_DEEPL, "DeepL", "需要你自己的 DeepL API Free Key，翻译质量最好"),
        Engine(ENGINE_CUSTOM, "自定义接口", "填你自己的接口地址和 Key，POST JSON")
    )

    fun labelOf(engineId: String): String =
        ENGINES.firstOrNull { it.id == engineId }?.label ?: "Google 免费"

    data class Config(
        val engineId: String = ENGINE_GOOGLE_FREE,
        val deeplKey: String = "",
        val customUrl: String = "",
        val customKey: String = "",
        // 智能回复：OpenAI 兼容接口地址（如 https://api.deepseek.com/v1）、Key、模型名
        val llmUrl: String = "",
        val llmKey: String = "",
        val llmModel: String = "deepseek-chat"
    )

    /** 是否配好了大模型：地址和 Key 都填了才走 LLM，否则用本地规则。 */
    fun hasLlm(config: Config): Boolean =
        config.llmUrl.isNotBlank() && config.llmKey.isNotBlank()

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(FloatingBallService.PREFS, Context.MODE_PRIVATE)
        return Config(
            engineId = p.getString(KEY_ENGINE, ENGINE_GOOGLE_FREE) ?: ENGINE_GOOGLE_FREE,
            deeplKey = p.getString(KEY_DEEPL_KEY, "") ?: "",
            customUrl = p.getString(KEY_CUSTOM_URL, "") ?: "",
            customKey = p.getString(KEY_CUSTOM_KEY, "") ?: "",
            llmUrl = p.getString(KEY_LLM_URL, "") ?: "",
            llmKey = p.getString(KEY_LLM_KEY, "") ?: "",
            llmModel = p.getString(KEY_LLM_MODEL, "deepseek-chat") ?: "deepseek-chat"
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(FloatingBallService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENGINE, config.engineId)
            .putString(KEY_DEEPL_KEY, config.deeplKey)
            .putString(KEY_CUSTOM_URL, config.customUrl)
            .putString(KEY_CUSTOM_KEY, config.customKey)
            .putString(KEY_LLM_URL, config.llmUrl)
            .putString(KEY_LLM_KEY, config.llmKey)
            .putString(KEY_LLM_MODEL, config.llmModel)
            .apply()
    }
}
