package com.floatingtranslate.app

/**
 * 智能回复建议（v1.5.0 重写）。
 *
 * v1.4.0 只有 5 种死板意图（感谢/道歉/提问/确认/通用），每种 3 条固定
 * 模板，不管对方说什么都回同一套，用户说“不够智能”。
 *
 * v1.5.0 改成意图 + 关键词实体驱动：
 * 1. 先识别意图，至少 12 种：问候、问时间、问价格、问地点、问选择、
 *    一般提问、感谢、道歉、确认、邀请、告别、陈述。
 * 2. 再从原文里抠实体（时间词/价格词/地点词），拼进回复里：
 *    对方问“你什么时候有空”，建议要带时间选项；问“多少钱”，
 *    建议要问预算或给报价方向；问“在哪”，建议要给地点。
 * 3. 每条建议口语化、自然，像真人回消息，不套“收到，谢谢你的消息”
 *    这种机器味。3 条分热情 / 简洁 / 正式三种语气。
 * 4. 语言逻辑不变：对方发什么语言，回复就用什么语言，每条下面附
 *    另一语言的翻译对照，复制出去对方能直接看懂。
 * 5. 如果用户在设置里填了自己的大模型 Key（见 LlmSuggester），
 *    面板会优先走 LLM 生成，失败再降级到这里的本地规则。
 */
object ReplySuggester {

    enum class Tone { FRIENDLY, FORMAL, SHORT }

    data class Suggestion(
        val tone: Tone,
        val toneLabelZh: String,
        /** 回复正文：与对方来信同语言，复制出去直接可用。 */
        val textMain: String,
        /** 对照翻译：另一语言的翻译，只给自己看。 */
        val textSub: String
    )

    enum class Intent(val labelZh: String) {
        GREETING("问候"),
        QUESTION_TIME("问时间"),
        QUESTION_PRICE("问价格"),
        QUESTION_LOCATION("问地点"),
        QUESTION_CHOICE("问选择"),
        QUESTION_GENERAL("提问"),
        THANKS("感谢"),
        APOLOGY("道歉"),
        CONFIRMATION("确认"),
        INVITATION("邀请"),
        FAREWELL("告别"),
        STATEMENT("陈述")
    }

    // ---------- 意图识别（中英文关键词都认） ----------

    fun detectIntent(originalText: String): Intent {
        val lower = originalText.lowercase()
        // vararg 只能用在函数声明上，lambda 字面量里不能写 vararg，
        // 所以这里用两个本地小函数，不用 lambda 变量。
        fun has(vararg keys: String): Boolean = keys.any { lower.contains(it) }
        fun hasZh(vararg keys: String): Boolean = keys.any { originalText.contains(it) }
        val isQuestion = originalText.contains("?") || originalText.contains("？") ||
            has("what", "how", "when", "where", "why", "which", "can you", "could you",
                "do you", "are you", "is there", "is it") ||
            hasZh("吗", "呢", "么", "能不能", "可不可以", "会不会", "有没有")

        return when {
            has("thank", "thanks", "thx") || hasZh("谢谢", "感谢", "多谢") -> Intent.THANKS
            has("sorry", "apolog", "my bad", "my fault") ||
                hasZh("对不起", "抱歉", "不好意思", "是我不对") -> Intent.APOLOGY
            has("good night", "goodnight", "bye", "goodbye", "see you", "talk later") ||
                hasZh("再见", "晚安", "回头聊", "先走了", "拜拜") -> Intent.FAREWELL
            has("hello", "hi ", "hey", "good morning", "good afternoon", "good evening",
                "how are you", "how's it going", "what's up") ||
                hasZh("你好", "早上好", "下午好", "晚上好", "在吗", "在么", "嗨", "最近怎么样") -> Intent.GREETING
            has("let's", "let us", "why don't we", "join me", "come with", "wanna join") ||
                hasZh("一起", "来不来", "要不要一起", "约吗", "组队") -> Intent.INVITATION
            has("ok", "sure", "sounds good", "no problem", "deal", "agreed", "perfect") ||
                hasZh("好的", "可以", "没问题", "行", "就这么定", "成交", "同意") -> Intent.CONFIRMATION
            isQuestion && (has("when", "what time", "which day", "schedule", "available", "free") ||
                hasZh("什么时候", "几点", "哪天", "有空", "有时间", "周末", "明天", "今天", "今晚", "下周")) ->
                Intent.QUESTION_TIME
            isQuestion && (has("how much", "price", "cost", "expensive", "cheap", "budget", "how many") ||
                hasZh("多少钱", "多少", "价格", "费用", "报价", "贵", "便宜", "预算")) ->
                Intent.QUESTION_PRICE
            isQuestion && (has("where", "location", "address", "which place") ||
                hasZh("在哪", "哪里", "地址", "怎么走", "哪个地方")) ->
                Intent.QUESTION_LOCATION
            isQuestion && (has("which", "or", "prefer", "better") ||
                hasZh("还是", "哪个好", "选哪个", "要不要", "喜欢哪个")) ->
                Intent.QUESTION_CHOICE
            isQuestion -> Intent.QUESTION_GENERAL
            else -> Intent.STATEMENT
        }
    }

    fun intentLabelZh(originalText: String): String = detectIntent(originalText).labelZh

    // ---------- 实体抽取：把原文里的时间词拼进回复 ----------

    /** 时间词对（中文, 英文），按原文出现顺序取第一个。 */
    private val TIME_TOKENS = listOf(
        "今天" to "today", "明天" to "tomorrow", "后天" to "the day after tomorrow",
        "今晚" to "tonight", "明晚" to "tomorrow night",
        "周末" to "this weekend", "周六" to "Saturday", "周日" to "Sunday",
        "下午" to "this afternoon", "晚上" to "tonight", "上午" to "this morning",
        "下周" to "next week", "下个月" to "next month"
    )

    /** 返回 (中文形式, 英文形式)，没提到时间就返回 null。 */
    private fun extractTimeToken(originalText: String): Pair<String, String>? {
        val lower = originalText.lowercase()
        for ((zh, en) in TIME_TOKENS) {
            if (originalText.contains(zh)) return zh to en
            if (lower.contains(en)) return zh to en
        }
        // “3点 / 3pm / 15:00”这类具体时间
        val m = Regex("(\\d{1,2})\\s*[:点]\\s*(\\d{0,2})\\s*([ap]m)?", RegexOption.IGNORE_CASE)
            .find(originalText)
        if (m != null) {
            val t = m.value.trim()
            return t to t
        }
        return null
    }

    // ---------- 建议生成 ----------

    /**
     * @param originalText 对方发来的原文
     * @param replyLang 回复语言 = 原文的语言（对方的语言）
     */
    fun suggest(originalText: String, replyLang: Translator.Lang): List<Suggestion> {
        val intent = detectIntent(originalText)
        val time = extractTimeToken(originalText)
        // 每组都是 (英文, 中文) 一对；主文按 replyLang 取，另一条当对照翻译。
        val picked: List<Triple<Tone, String, String>> = when (intent) {
            Intent.GREETING -> listOf(
                Triple(Tone.FRIENDLY, "Hey! Good to hear from you. What's up?",
                    "嗨！很高兴收到你的消息，最近怎么样？"),
                Triple(Tone.FORMAL, "Hello, nice to hear from you.",
                    "你好，很高兴收到你的消息。"),
                Triple(Tone.SHORT, "Hey, I'm here.", "嗨，我在。")
            )
            Intent.QUESTION_TIME -> {
                val (zhT, enT) = time ?: ("这个周末" to "this weekend")
                listOf(
                    Triple(Tone.FRIENDLY,
                        "How about $enT? I'm pretty free then — does that work for you?",
                        "$zhT 怎么样？我那时候比较有空，你看行吗？"),
                    Triple(Tone.FORMAL,
                        "I'm available $enT. Would that time suit you?",
                        "我 $zhT 有空，那个时间你方便吗？"),
                    Triple(Tone.SHORT,
                        "$enT works for me.",
                        "$zhT 可以。")
                )
            }
            Intent.QUESTION_PRICE -> listOf(
                Triple(Tone.FRIENDLY,
                    "It depends on what you need — what's your budget? I can find something that fits.",
                    "这要看你具体要什么，你预算大概多少？我可以帮你找合适的。"),
                Triple(Tone.FORMAL,
                    "Could you share your budget? I'll check the price for you.",
                    "方便说一下你的预算吗？我帮你查一下价格。"),
                Triple(Tone.SHORT,
                    "What's your budget?",
                    "你预算多少？")
            )
            Intent.QUESTION_LOCATION -> listOf(
                Triple(Tone.FRIENDLY,
                    "I'm near the city center — where are you? We can meet somewhere in the middle.",
                    "我在市中心附近，你在哪？我们可以约个中间的地方见。"),
                Triple(Tone.FORMAL,
                    "Could you share your location? I'll suggest a convenient place.",
                    "方便发一下你的位置吗？我帮你找个方便的地方。"),
                Triple(Tone.SHORT,
                    "Where are you now?",
                    "你现在在哪？")
            )
            Intent.QUESTION_CHOICE -> listOf(
                Triple(Tone.FRIENDLY,
                    "Either works for me — I slightly prefer the first one. What do you think?",
                    "两个我都可以，我稍微更喜欢第一个，你觉得呢？"),
                Triple(Tone.FORMAL,
                    "Both are fine with me. Please go with whichever you prefer.",
                    "两个都可以，你选喜欢的那个就行。"),
                Triple(Tone.SHORT,
                    "Either is fine, you pick.",
                    "都行，你定。")
            )
            Intent.QUESTION_GENERAL -> listOf(
                Triple(Tone.FRIENDLY,
                    "Good question — let me think for a second. I think that should work, want me to double-check?",
                    "好问题，让我想一下，我觉得应该是可以的，要我帮你确认一下吗？"),
                Triple(Tone.FORMAL,
                    "I'll check on this and get back to you shortly.",
                    "这个问题我确认一下，稍后回复你。"),
                Triple(Tone.SHORT,
                    "Let me check, one moment.",
                    "我看一下，稍等。")
            )
            Intent.THANKS -> listOf(
                Triple(Tone.FRIENDLY, "You're welcome, anytime! Happy to help.",
                    "不客气，随时找我！能帮上忙就好。"),
                Triple(Tone.FORMAL, "You're welcome. Glad I could help.",
                    "不客气，能帮上忙就好。"),
                Triple(Tone.SHORT, "Anytime!", "不客气！")
            )
            Intent.APOLOGY -> listOf(
                Triple(Tone.FRIENDLY, "No worries at all, it happens. Let's carry on.",
                    "没关系，别放在心上，这种事很正常，我们继续吧。"),
                Triple(Tone.FORMAL, "No problem at all, please don't worry about it.",
                    "没关系，请不用在意。"),
                Triple(Tone.SHORT, "It's fine, don't worry.", "没事的，别在意。")
            )
            Intent.CONFIRMATION -> listOf(
                Triple(Tone.FRIENDLY, "Sounds good, let's go with that!",
                    "好的，那就这么定了！"),
                Triple(Tone.FORMAL, "Confirmed. Noted, thanks!",
                    "好的，确认收到。"),
                Triple(Tone.SHORT, "Deal.", "好的，就这么定了。")
            )
            Intent.INVITATION -> listOf(
                Triple(Tone.FRIENDLY, "Sounds fun, count me in! When are we going?",
                    "听起来不错，算我一个！我们什么时候去？"),
                Triple(Tone.FORMAL, "I'd love to join. Please let me know the time and place.",
                    "我很愿意参加，麻烦告诉我时间和地点。"),
                Triple(Tone.SHORT, "I'm in!", "我去！")
            )
            Intent.FAREWELL -> listOf(
                Triple(Tone.FRIENDLY, "Talk soon! Take care.",
                    "回头聊，注意身体！"),
                Triple(Tone.FORMAL, "Goodbye for now, talk to you soon.",
                    "先这样，回头再聊。"),
                Triple(Tone.SHORT, "See you!", "再见！")
            )
            Intent.STATEMENT -> listOf(
                Triple(Tone.FRIENDLY, "Got it — that makes sense. Anything else you need from me?",
                    "明白了，你说的有道理，还有什么需要我帮忙的吗？"),
                Triple(Tone.FORMAL, "Noted, thank you for letting me know.",
                    "收到，谢谢你告诉我。"),
                Triple(Tone.SHORT, "Got it!", "收到！")
            )
        }
        val toneLabel = mapOf(
            Tone.FRIENDLY to "热情",
            Tone.FORMAL to "正式",
            Tone.SHORT to "简洁"
        )
        return picked.map { (tone, textEn, textZh) ->
            if (replyLang == Translator.Lang.EN) {
                Suggestion(tone, toneLabel[tone] ?: "", textEn, textZh)
            } else {
                Suggestion(tone, toneLabel[tone] ?: "", textZh, textEn)
            }
        }
    }
}
