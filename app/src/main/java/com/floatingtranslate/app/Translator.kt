package com.floatingtranslate.app

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 中英互译，翻译引擎由用户在设置页自选（见 EngineSettings）。
 *
 * - google_free：Google 免费端点 translate.googleapis.com/translate_a/single
 *   （client=gtx，无需 key；国内网络可能不可用，失败自动降级 MyMemory）
 * - mymemory：MyMemory API https://api.mymemory.translated.net/get（免费，无需 key，有配额）
 * - deepl：DeepL API Free，POST https://api-free.deepl.com/v2/translate，
 *   Header Authorization: DeepL-Auth-Key <用户自己的 KEY>
 * - custom：用户自己的接口，POST JSON {"text","source","target"}，可选 Bearer Key，
 *   接受返回字段 translatedText / translation / text / result，纯文本也接受。
 *
 * 所有引擎失败时抛错，调用方显示明确提示并建议换引擎；不伪造翻译结果。
 */
object Translator {

    enum class Lang { ZH, EN, AUTO }

    data class Result(
        val translated: String,
        val detectedSource: Lang,
        val source: String // "google" / "mymemory" / "deepl" / "custom"，便于排错
    )

    data class TestResult(val ok: Boolean, val message: String)

    private const val TIMEOUT_MS = 8000L

    /** 含中文字符即判为中文，否则判为英文。 */
    fun detect(text: String): Lang {
        return if (text.any { it in '\u4E00'..'\u9FFF' }) Lang.ZH else Lang.EN
    }

    /** 自动方向：中文 -> 英文，英文 -> 中文。 */
    fun autoTarget(text: String): Lang = if (detect(text) == Lang.ZH) Lang.EN else Lang.ZH

    /** 兼容旧调用：默认用 Google 免费引擎。 */
    suspend fun translateAuto(text: String): Result =
        translateAuto(text, EngineSettings.Config())

    suspend fun translateAuto(text: String, config: EngineSettings.Config): Result =
        withContext(Dispatchers.IO) {
            val src = detect(text)
            val target = if (src == Lang.ZH) Lang.EN else Lang.ZH
            translate(text, src, target, config)
        }

    /** 兼容旧调用：默认用 Google 免费引擎。 */
    suspend fun translate(text: String, source: Lang, target: Lang): Result =
        translate(text, source, target, EngineSettings.Config())

    suspend fun translate(
        text: String,
        source: Lang,
        target: Lang,
        config: EngineSettings.Config
    ): Result {
        if (text.isBlank()) return Result("", source, "none")
        val srcCode = when (source) {
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
            Lang.AUTO -> "auto"
        }
        val tgtCode = when (target) {
            Lang.ZH -> "zh-CN"
            Lang.EN -> "en"
            Lang.AUTO -> "en"
        }
        return when (config.engineId) {
            EngineSettings.ENGINE_DEEPL -> {
                if (config.deeplKey.isBlank()) {
                    throw IllegalStateException("DeepL 未填 Key：请在主界面翻译引擎里填写你的 DeepL API Key")
                }
                val out = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { translateViaDeepL(text, srcCode, tgtCode, config.deeplKey) }.getOrNull()
                } ?: throw IllegalStateException("DeepL 翻译失败：请检查 Key 和网络")
                Result(out, detect(text), "deepl")
            }
            EngineSettings.ENGINE_CUSTOM -> {
                if (config.customUrl.isBlank()) {
                    throw IllegalStateException("自定义接口未填地址：请在主界面翻译引擎里填写接口地址")
                }
                val out = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching {
                        translateViaCustom(text, srcCode, tgtCode, config.customUrl, config.customKey)
                    }.getOrNull()
                } ?: throw IllegalStateException("自定义接口调用失败：请检查地址、Key 和网络")
                Result(out, detect(text), "custom")
            }
            EngineSettings.ENGINE_MYMEMORY -> {
                val out = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { translateViaMyMemory(text, srcCode, tgtCode) }.getOrNull()
                } ?: throw IllegalStateException("MyMemory 翻译失败：请检查网络或换一个引擎")
                Result(out, detect(text), "mymemory")
            }
            else -> {
                // Google 免费：先试 Google，超时/失败再试 MyMemory。
                val google = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { translateViaGoogle(text, srcCode, tgtCode) }.getOrNull()
                }
                if (!google.isNullOrBlank()) {
                    return Result(google, detect(text), "google")
                }
                val myMemory = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { translateViaMyMemory(text, srcCode, tgtCode) }.getOrNull()
                }
                if (!myMemory.isNullOrBlank()) {
                    return Result(myMemory, detect(text), "mymemory")
                }
                throw IllegalStateException("翻译接口不可用：请检查网络，或在主界面切换翻译引擎")
            }
        }
    }

    /** 在设置页测试当前引擎：翻译一句 Hello，返回是否通过和一句话说明。 */
    suspend fun testEngine(config: EngineSettings.Config): TestResult {
        return try {
            val r = translate("Hello", Lang.EN, Lang.ZH, config)
            if (r.translated.isBlank()) {
                TestResult(false, "测试失败：接口返回空")
            } else {
                TestResult(true, "测试通过：Hello → ${r.translated}（${r.source}）")
            }
        } catch (e: Exception) {
            TestResult(false, "测试失败：${e.message}")
        }
    }

    // ---------- 各引擎实现 ----------

    /**
     * Google 免费端点返回形如：
     * [[["Hello","你好",null,null,1]],null,"zh-CN"]
     * 只做轻量解析：取每一段的第一个字符串拼接。
     */
    private fun translateViaGoogle(text: String, sl: String, tl: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val out = StringBuilder()
        val firstArrayEnd = body.indexOf("]],")
        val segment = if (firstArrayEnd > 0) body.substring(0, firstArrayEnd) else body
        val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val matches = regex.findAll(segment).map { it.groupValues[1] }.toList()
        // matches 成对出现：[译文, 原文, 译文, 原文, ...]，取偶数位。
        for (i in matches.indices step 2) {
            out.append(matches[i].replace("\\n", "\n"))
        }
        val result = out.toString().trim()
        if (result.isEmpty()) throw IllegalStateException("Google 免费端点返回空")
        return result
    }

    /** MyMemory：返回 JSON {"responseData":{"translatedText":"..."}}，轻量解析。 */
    private fun translateViaMyMemory(text: String, sl: String, tl: String): String {
        val pair = "$sl|$tl"
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=$pair")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val result = extractJsonString(body, "translatedText")
            ?: throw IllegalStateException("MyMemory 返回异常")
        if (result.isEmpty() || result.equals("QUERY LENGTH LIMIT EXCEEDED", ignoreCase = true)) {
            throw IllegalStateException("MyMemory 返回空或超限")
        }
        return result
    }

    /** DeepL API Free：POST 表单，返回 {"translations":[{"text":"..."}]}。 */
    private fun translateViaDeepL(text: String, sl: String, tl: String, key: String): String {
        val sourceLang = if (sl.startsWith("zh")) "ZH" else "EN"
        val targetLang = if (tl.startsWith("zh")) "ZH" else "EN"
        val body = "text=" + URLEncoder.encode(text, "UTF-8") +
            "&source_lang=$sourceLang&target_lang=$targetLang"
        val conn = (URL("https://api-free.deepl.com/v2/translate").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
        val code = conn.responseCode
        val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("DeepL 返回 $code：${respBody.take(120)}")
        }
        return extractJsonString(respBody, "text")
            ?: throw IllegalStateException("DeepL 返回异常")
    }

    /**
     * 自定义接口：POST JSON {"text","source","target"}，可选 Bearer Key。
     * 接受返回字段 translatedText / translation / text / result；纯文本响应也直接当译文。
     */
    private fun translateViaCustom(
        text: String,
        sl: String,
        tl: String,
        urlStr: String,
        key: String
    ): String {
        val jsonBody = "{\"text\":\"${escapeJson(text)}\",\"source\":\"$sl\",\"target\":\"$tl\"}"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(jsonBody) }
        val code = conn.responseCode
        val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("自定义接口返回 $code：${respBody.take(120)}")
        }
        val trimmed = respBody.trim()
        val fromField = extractJsonString(trimmed, "translatedText")
            ?: extractJsonString(trimmed, "translation")
            ?: extractJsonString(trimmed, "text")
            ?: extractJsonString(trimmed, "result")
        if (fromField != null && fromField.isNotBlank()) return fromField
        // 纯文本响应（不带 JSON）直接当译文
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        throw IllegalStateException("自定义接口返回无法解析：${trimmed.take(120)}")
    }

    /** 轻量取 JSON 字符串字段值（不引入 JSON 库）。 */
    private fun extractJsonString(body: String, key: String): String? {
        val needle = "\"$key\":\""
        val start = body.indexOf(needle)
        if (start < 0) return null
        val valueStart = start + needle.length
        val sb = StringBuilder()
        var i = valueStart
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = body.substring((i + 2).coerceAtMost(body.length), (i + 6).coerceAtMost(body.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(body[i + 1])
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
