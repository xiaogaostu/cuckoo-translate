package com.floatingtranslate.app

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 大模型智能回复建议（v1.5.0 新增）。
 *
 * 用户在主界面“智能回复（可选）”里填自己的大模型 Key 后，面板优先
 * 走这里生成建议；任何一步失败（没填 Key、网络不通、接口报错、
 * 返回解析失败）都返回 null，调用方自动降级到本地规则
 * （ReplySuggester），并在面板上注明来源，绝不因为智能生成失败
 * 而让用户看不到建议。
 *
 * 接口要求：OpenAI 兼容的 /v1/chat/completions（DeepSeek、OpenAI、
 * 通义千问兼容版、任何自架兼容网关都能用）。DeepL 不走这里，
 * DeepL 是翻译引擎，不是聊天大模型。
 */
object LlmSuggester {

    private const val TIMEOUT_MS = 12000L

    /**
     * @param original 对方发来的原文
     * @param replyLang 回复语言 = 原文的语言
     * @return 3 条建议，失败返回 null（调用方降级本地规则）
     */
    suspend fun suggest(
        original: String,
        replyLang: Translator.Lang,
        config: EngineSettings.Config
    ): List<ReplySuggester.Suggestion>? {
        if (config.llmUrl.isBlank() || config.llmKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { callLlm(original, replyLang, config) }.getOrNull()
            }
        }
    }

    private fun callLlm(
        original: String,
        replyLang: Translator.Lang,
        config: EngineSettings.Config
    ): List<ReplySuggester.Suggestion>? {
        val replyLangName = if (replyLang == Translator.Lang.EN) "English" else "Chinese"
        val otherLangName = if (replyLang == Translator.Lang.EN) "Chinese" else "English"
        val model = config.llmModel.ifBlank { "deepseek-chat" }
        val prompt = "You are helping a user reply to a chat message. " +
            "The incoming message is in $replyLangName. " +
            "Write exactly 3 short, natural, colloquial replies in $replyLangName, " +
            "tones in order: 1 warm and friendly, 2 short and concise, 3 formal and polite. " +
            "Each reply must directly address what the message actually says " +
            "(answer its question, respond to its request, keep its topic). " +
            "For each reply also give its $otherLangName translation. " +
            "Output ONLY in this exact format, one reply per block:\n" +
            "1. <reply in $replyLangName>\n" +
            "<translation in $otherLangName>\n" +
            "2. <reply>\n<translation>\n3. <reply>\n<translation>"
        val endpoint = config.llmUrl.trim().trimEnd('/') +
            if (config.llmUrl.trim().endsWith("/chat/completions")) "" else "/chat/completions"
        val body = "{\"model\":\"${escapeJson(model)}\"," +
            "\"messages\":[" +
            "{\"role\":\"system\",\"content\":\"${escapeJson(prompt)}\"}," +
            "{\"role\":\"user\",\"content\":\"${escapeJson(original)}\"}]," +
            "\"temperature\":0.7,\"max_tokens\":600}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.llmKey}")
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
        val code = conn.responseCode
        val respBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return null
        val content = extractJsonString(respBody, "content") ?: return null
        return parseReplies(content, replyLang)
            ?: runCatching {
                // 大模型只给了回复没给翻译：用翻译引擎补翻译对照
                val replies = content.lines().map { it.trim() }
                    .filter { it.matches(Regex("^[123][.、)]\\s+.*")) }
                    .map { it.replaceFirst(Regex("^[123][.、)]\\s*"), "") }
                if (replies.size < 2) return null
                replies.take(3).map { r ->
                    val trans = runCatching {
                        kotlinx.coroutines.runBlocking {
                            Translator.translateAuto(r, config).translated
                        }
                    }.getOrNull().orEmpty()
                    r to trans
                }.let { pairs -> toSuggestions(pairs, replyLang) }
            }.getOrNull()
    }

    /** 解析 “1. 回复\n翻译\n2. 回复\n翻译\n3. …” 的格式。 */
    private fun parseReplies(
        content: String,
        replyLang: Translator.Lang
    ): List<ReplySuggester.Suggestion>? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pairs = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size && pairs.size < 3) {
            val m = Regex("^[123][.、)]\\s*(.*)").find(lines[i])
            if (m != null) {
                val reply = m.groupValues[1].trim()
                val trans = if (i + 1 < lines.size &&
                    !Regex("^[123][.、)]").containsMatchIn(lines[i + 1])
                ) {
                    lines[i + 1].trim().also { i++ }
                } else ""
                if (reply.isNotBlank()) pairs.add(reply to trans)
            }
            i++
        }
        if (pairs.size < 2) return null
        // 翻译缺失的，用翻译引擎补
        val filled = pairs.map { (r, t) ->
            if (t.isBlank()) {
                val trans = runCatching {
                    kotlinx.coroutines.runBlocking {
                        Translator.translateAuto(r, EngineSettings.Config()).translated
                    }
                }.getOrNull().orEmpty()
                r to trans
            } else r to t
        }
        return toSuggestions(filled, replyLang)
    }

    private fun toSuggestions(
        pairs: List<Pair<String, String>>,
        replyLang: Translator.Lang
    ): List<ReplySuggester.Suggestion> {
        val tones = listOf(
            ReplySuggester.Tone.FRIENDLY to "热情",
            ReplySuggester.Tone.SHORT to "简洁",
            ReplySuggester.Tone.FORMAL to "正式"
        )
        return pairs.take(3).mapIndexed { idx, (main, sub) ->
            val (tone, label) = tones.getOrElse(idx) { tones[0] }
            ReplySuggester.Suggestion(tone, label, main, sub)
        }
    }

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
                        val hex = body.substring(
                            (i + 2).coerceAtMost(body.length),
                            (i + 6).coerceAtMost(body.length)
                        )
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
