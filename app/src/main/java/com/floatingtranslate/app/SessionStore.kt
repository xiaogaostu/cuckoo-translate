package com.floatingtranslate.app

import android.content.Context

/**
 * 翻译会话持久化（v1.5.0 新增）。
 *
 * 用户场景：面板收起只是隐藏视图，数据不能丢。再点球进来，要立刻看到
 * 上次的原文、译文和回复建议，不要重新联网翻译，更不要显示空白。
 *
 * 两层存储：Service 内存里的 lastSession（最快）+ SharedPreferences
 * （Service 被系统杀掉重建后也能恢复）。
 *
 * 更新规则：
 * - 翻译成功后保存整条会话（原文 + 译文 + 方向 + 建议 + 来源 + 意图）。
 * - 只改输入框文字（还没点翻译）时保存草稿：原文更新，旧译文/建议作废。
 * - 只有用户点面板里的“清空记录”，或复制了新的不同内容并点了翻译，
 *   旧会话才会被覆盖。
 */
object SessionStore {

    private const val K_ORIG = "session_original"
    private const val K_TRANS = "session_translated"
    private const val K_DIR = "session_direction"
    private const val K_LANG = "session_source_lang" // "en" / "zh"
    private const val K_SUGG = "session_suggestions"
    private const val K_SUGG_SRC = "session_sugg_source" // "llm" / "local"
    private const val K_INTENT = "session_intent"
    private const val K_TIME = "session_time"

    private const val REC_SEP = "\u0002"
    private const val FIELD_SEP = "\u0001"

    data class Session(
        val original: String,
        val translated: String,
        val direction: String,
        val sourceLang: String,
        val suggestions: List<ReplySuggester.Suggestion>,
        val suggSource: String,
        val intentLabel: String,
        val timeMillis: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FloatingBallService.PREFS, Context.MODE_PRIVATE)

    private fun clean(s: String): String =
        s.replace(REC_SEP, " ").replace(FIELD_SEP, " ")

    fun save(ctx: Context, session: Session) {
        val suggEncoded = session.suggestions.joinToString(REC_SEP) {
            listOf(
                it.tone.name,
                it.toneLabelZh,
                clean(it.textMain),
                clean(it.textSub)
            ).joinToString(FIELD_SEP)
        }
        prefs(ctx).edit()
            .putString(K_ORIG, session.original)
            .putString(K_TRANS, session.translated)
            .putString(K_DIR, session.direction)
            .putString(K_LANG, session.sourceLang)
            .putString(K_SUGG, suggEncoded)
            .putString(K_SUGG_SRC, session.suggSource)
            .putString(K_INTENT, session.intentLabel)
            .putLong(K_TIME, session.timeMillis)
            .apply()
    }

    /** 只保存输入框草稿：原文变了，旧译文和建议作废，但原文不丢。 */
    fun saveDraft(ctx: Context, original: String) {
        val old = load(ctx)
        if (old != null && old.original == original) return
        prefs(ctx).edit()
            .putString(K_ORIG, original)
            .putString(K_TRANS, "")
            .putString(K_SUGG, "")
            .putString(K_SUGG_SRC, "")
            .putString(K_INTENT, "")
            .putLong(K_TIME, System.currentTimeMillis())
            .apply()
    }

    fun load(ctx: Context): Session? {
        return try {
            val p = prefs(ctx)
            val original = p.getString(K_ORIG, null).orEmpty()
            if (original.isBlank()) return null
            val suggestions = p.getString(K_SUGG, null).orEmpty()
                .split(REC_SEP)
                .filter { it.isNotBlank() }
                .mapNotNull { rec ->
                    val f = rec.split(FIELD_SEP)
                    if (f.size < 4) return@mapNotNull null
                    val tone = runCatching { ReplySuggester.Tone.valueOf(f[0]) }
                        .getOrDefault(ReplySuggester.Tone.FRIENDLY)
                    ReplySuggester.Suggestion(tone, f[1], f[2], f[3])
                }
            Session(
                original = original,
                translated = p.getString(K_TRANS, null).orEmpty(),
                direction = p.getString(K_DIR, "auto") ?: "auto",
                sourceLang = p.getString(K_LANG, "en") ?: "en",
                suggestions = suggestions,
                suggSource = p.getString(K_SUGG_SRC, "local") ?: "local",
                intentLabel = p.getString(K_INTENT, "") ?: "",
                timeMillis = p.getLong(K_TIME, 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_ORIG).remove(K_TRANS).remove(K_DIR).remove(K_LANG)
            .remove(K_SUGG).remove(K_SUGG_SRC).remove(K_INTENT).remove(K_TIME)
            .apply()
    }
}
