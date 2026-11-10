package com.floatingtranslate.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 翻译小面板（v1.6.2，根治 v1.6.0 的 workaround）。
 *
 * v1.6.0 的问题（用户真机反馈，v1.6.1 已确认）：
 * 面板是 TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE 的悬浮窗，
 * v1.6 把 EditText 整个拿掉了——面板里没法打字，只能回主界面输入，
 * 等于悬浮球白做了。剪贴板也读不到：Android 10+ 只允许获得焦点的
 * 应用读剪贴板，面板永远不聚焦、读剪贴板的代码还在 Service 里跑，
 * 系统直接返回空。不是用户没复制，是代码拿不到。
 *
 * v1.4/v1.5 在悬浮窗里修输入法 + 返回手势两次都失败，根因是架构：
 * 在悬浮窗里放可编辑输入框本身就是 Android 上最难做对的形态。
 * v1.6 拿掉输入框只是 workaround，治标不治本。
 *
 * v1.6.2 根治（换架构，不再修悬浮窗 flag）：
 * 面板改成真正的半透明 Activity（对话框式小卡片），悬浮球仍是悬浮窗。
 * - Activity 是真正获得焦点的窗口：输入法、返回手势、剪贴板读取全部
 *   走系统标准行为，不再自己拦截 BACK、不再手维护键盘状态。
 *   键盘开着时返回先收键盘，再返回关闭面板回到聊天；onResume 时读
 *   剪贴板（此时有焦点，Android 10+ 允许），有新内容自动填入并翻译。
 * - 外观和原来一样：宽约 300dp、高不超半屏的白色小卡片，浮在聊天
 *   界面上方，不是全屏；背后微暗；点面板外面关闭；不进最近任务。
 * - 面板里恢复 EditText（可输入原文）+ 翻译按钮 + 译文 + 复制 +
 *   折叠的回复建议；点悬浮球用 FLAG_ACTIVITY_NEW_TASK 启动它。
 * - SessionStore 会话持久化保留（原文 + 译文 + 建议 + 草稿，重开恢复）；
 *   回复建议 12 意图 + LLM 保留；不加回无障碍权限，仍只做复制翻译，
 *   权限保持 7 个；纯原生 Views，零 Compose、零 androidx。
 *
 * 启动安全：onCreate 全包 try-catch (Throwable)，出错只显示一行说明
 * 绝不闪退；面板 Activity 不启动 Service、不读任何权限状态。
 */
class TranslatePanelActivity : Activity() {

    companion object {
        const val EXTRA_CLOSE = "close"
        /** 面板是否正开着：悬浮球单击用它决定是打开还是关闭面板。 */
        @Volatile
        var isOpen = false
    }

    private val ink = Color.parseColor("#111827")
    private val gray = Color.parseColor("#6B7280")
    private val line = Color.parseColor("#E5E7EB")
    private val cardBg = Color.parseColor("#FFFFFF")
    private val inputBg = Color.parseColor("#F9FAFB")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var inputEdit: EditText
    private lateinit var translatedTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var suggestionsContainer: LinearLayout
    private lateinit var suggestionsToggle: TextView

    private var suggestionsSource = "local" // "llm" 智能生成 / "local" 本地规则
    private var suggestionsIntent = ""
    private var lastClipboardSeen: String? = null
    private var lastSessionOriginal: String? = null
    private var keyboardAutoShown = false
    /** 程序填入文字（恢复会话 / 剪贴板自动填入）时不触发“用户改了原文”的逻辑。 */
    private var isProgrammaticText = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences(FloatingBallService.PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            isOpen = true
            if (intent?.getBooleanExtra(EXTRA_CLOSE, false) == true) {
                finish()
                return
            }
            buildUi()
            // 对话框式小卡片：宽 ≤300dp，高随内容、但不超半屏（见下面的高度上限）。
            val metrics = resources.displayMetrics
            val panelW = minOf(dp(300), metrics.widthPixels - dp(32))
            window.setLayout(panelW, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            window.attributes = window.attributes.apply { y = dp(120) }
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
            // 点面板外面关闭面板（标准对话框行为）。
            setFinishOnTouchOutside(true)
            // 内容超高时把窗口限到半屏，卡片内滚动。
            window.decorView.post {
                val maxH = (metrics.heightPixels * 0.5).toInt()
                if (window.decorView.height > maxH) {
                    window.setLayout(panelW, maxH)
                }
            }
            restoreSession()
        } catch (t: Throwable) {
            try {
                val tv = TextView(this).apply {
                    text = "翻译面板加载失败，但 App 没有崩溃。\n请回主界面用手动翻译，或卸载后重新安装 v1.6.2（不要覆盖安装）。\n错误：${t.javaClass.simpleName}: ${t.message}"
                    textSize = 16f
                    setTextColor(ink)
                    setPadding(48, 48, 48, 48)
                }
                setContentView(tv)
            } catch (t2: Throwable) {
                try { finish() } catch (_: Throwable) { }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_CLOSE, false) == true) {
            finish()
            return
        }
        refreshClipboard()
    }

    override fun onResume() {
        super.onResume()
        // Activity 回到前台就获得焦点：此时读剪贴板是 Android 10+ 允许的。
        // 键盘开着/收起不会触发 onResume，不会重复读。
        refreshClipboard()
        // 空面板（没会话、没剪贴板内容）时自动聚焦输入框、弹出键盘，
        // 用户点球进来就能直接打字，不用再点一下输入框。
        if (!keyboardAutoShown && ::inputEdit.isInitialized &&
            inputEdit.text.toString().isBlank()
        ) {
            keyboardAutoShown = true
            inputEdit.postDelayed({
                runCatching {
                    inputEdit.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        // 面板被收起/切走时把输入框文字当草稿存下来，回来不丢。
        runCatching {
            if (::inputEdit.isInitialized) {
                val text = inputEdit.text.toString().trim()
                if (text.isNotBlank()) SessionStore.saveDraft(applicationContext, text)
            }
        }
    }

    override fun onDestroy() {
        isOpen = false
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 统一样式（和主界面 / 旧面板一致） ----------

    private fun roundedBg(fill: Int, stroke: Int, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun primaryButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(ink)
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(44)
        minimumHeight = dp(44)
    }

    private fun secondaryButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ink)
        background = roundedBg(Color.WHITE, Color.parseColor("#D1D5DB"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minHeight = dp(44)
        minimumHeight = dp(44)
    }

    // ---------- 面板界面 ----------

    private fun buildUi() {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
        }

        // 标题栏：标题 + 明确的 × 收起按钮
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "即时翻译"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(ctx).apply {
            text = "×"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3F4F6"))
            }
            setOnClickListener { finish() }
        }
        header.addView(title)
        header.addView(closeBtn)

        // v1.6.2：原文恢复成可编辑输入框。Activity 是真正获得焦点的窗口，
        // 输入法和返回都是系统标准行为：键盘开着时返回先收键盘，再返回
        // 关闭面板回到聊天，不需要自己拦截 BACK、不需要手维护键盘状态。
        val originalLabel = TextView(ctx).apply {
            text = "原文（直接输入，或从剪贴板自动填入）"
            textSize = 12f
            setTextColor(gray)
            setPadding(0, dp(4), 0, dp(2))
        }
        inputEdit = EditText(ctx).apply {
            hint = "在这里输入要翻译的话…"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(gray)
            background = roundedBg(inputBg, Color.parseColor("#D1D5DB"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 2
            maxLines = 4
            isSingleLine = false
            isVerticalScrollBarEnabled = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isProgrammaticText) return
                    // 用户亲手改了原文：旧译文/建议作废，提示重新翻译；
                    // 文字本身当草稿存下来，面板关了回来也不丢。
                    val text = s?.toString().orEmpty().trim()
                    if (text.isNotEmpty()) {
                        runCatching { SessionStore.saveDraft(applicationContext, text) }
                    }
                    translatedTextView.text = "—"
                    suggestionsContainer.removeAllViews()
                    suggestionsSource = "local"
                    suggestionsIntent = ""
                    suggestionsToggle.text = "回复建议 · 点开"
                    statusTextView.text = "已修改原文，点“翻译”重新翻译"
                }
            })
        }

        // 按钮行：翻译（主）+ 重新读取剪贴板（次），等宽，不溢出
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val translateButton = primaryButton("翻译").apply {
            setOnClickListener {
                val text = inputEdit.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(ctx, "请先输入或复制一段话", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                translateAndShow(text)
            }
        }
        val rereadButton = secondaryButton("重新读取剪贴板").apply {
            setOnClickListener {
                val clipText = readClipboard()
                if (clipText.isNullOrBlank()) {
                    Toast.makeText(ctx, "剪贴板是空的，先去复制一段文字", Toast.LENGTH_SHORT).show()
                } else {
                    lastClipboardSeen = clipText
                    setInputText(clipText)
                    translateAndShow(clipText)
                }
            }
        }
        val btnLp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        buttonRow.addView(translateButton, btnLp().apply { marginEnd = dp(4) })
        buttonRow.addView(rereadButton, btnLp().apply { marginStart = dp(4) })

        // 译文区
        val translatedLabel = TextView(ctx).apply {
            text = "译文"
            textSize = 12f
            setTextColor(gray)
            setPadding(0, dp(8), 0, dp(2))
        }
        translatedTextView = TextView(ctx).apply {
            text = "—"
            textSize = 14f
            setTextColor(ink)
        }
        val copyTranslatedBtn = secondaryButton("复制译文").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { copyToClipboard(translatedTextView.text.toString()) }
        }

        // 回复建议：默认折叠，点一下才展开
        val suggHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setPadding(0, dp(6), 0, dp(6))
        }
        suggestionsToggle = TextView(ctx).apply {
            text = "回复建议 · 点开"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        suggHeader.addView(suggestionsToggle)
        suggHeader.setOnClickListener { toggleSuggestions() }
        suggestionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs().getBoolean(
                    FloatingBallService.KEY_SUGGESTIONS_EXPANDED, false
                )
            ) View.VISIBLE else View.GONE
        }

        // 底部一行小字状态 + 清空记录
        statusTextView = TextView(ctx).apply {
            text = "点悬浮球打开面板：直接打字，或复制一段话后自动填入；点“翻译”，再点“复制译文”粘贴回去"
            textSize = 11f
            setTextColor(gray)
            setPadding(0, dp(8), 0, 0)
        }
        val clearBtn = TextView(ctx).apply {
            text = "清空记录"
            textSize = 12f
            setTextColor(gray)
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { clearSession() }
        }
        val hintTv = TextView(ctx).apply {
            text = "键盘开着时按返回先收键盘，再按返回回到聊天；点面板外面也可关闭"
            textSize = 11f
            setTextColor(gray)
            setPadding(0, dp(4), 0, 0)
        }

        card.addView(header)
        card.addView(originalLabel)
        card.addView(inputEdit)
        card.addView(buttonRow)
        card.addView(translatedLabel)
        card.addView(translatedTextView)
        card.addView(copyTranslatedBtn)
        card.addView(suggHeader)
        card.addView(suggestionsContainer)
        card.addView(statusTextView)
        card.addView(clearBtn)
        card.addView(hintTv)

        val scroll = ScrollView(ctx).apply { addView(card) }
        setContentView(scroll)
    }

    private fun setInputText(text: String) {
        isProgrammaticText = true
        inputEdit.setText(text)
        inputEdit.setSelection(text.length)
        isProgrammaticText = false
    }

    // ---------- 剪贴板（Activity 有焦点时读，Android 10+ 允许） ----------

    private fun readClipboard(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() else null
    }.getOrNull()

    /**
     * 面板回到前台时读剪贴板：
     * - 是新的不同内容，且输入框为空、或输入框里的就是上次会话原文
     *   （用户没亲手打字）：自动填入并翻译，用户刚复制完点球进来就是这个流程。
     * - 用户已经亲手在输入框打了字：不覆盖他的字，只提示点“重新读取剪贴板”。
     * - 同一段内容只处理一次，不会在每次 onResume 重复翻译。
     */
    private fun refreshClipboard() {
        if (!::inputEdit.isInitialized) return
        val clipText = readClipboard()
        if (clipText.isNullOrBlank() || clipText == lastClipboardSeen) return
        lastClipboardSeen = clipText
        val current = inputEdit.text.toString().trim()
        val untouched = current.isEmpty() || current == lastSessionOriginal
        if (untouched) {
            setInputText(clipText)
            statusTextView.text = "已从剪贴板填入，正在翻译…"
            translateAndShow(clipText)
        } else {
            statusTextView.text = "剪贴板有新内容，点“重新读取剪贴板”填入"
        }
    }

    // ---------- 会话恢复 / 清空 ----------

    private fun restoreSession() {
        val saved = runCatching { SessionStore.load(applicationContext) }.getOrNull()
        if (saved == null) return
        lastSessionOriginal = saved.original
        setInputText(saved.original)
        suggestionsSource = saved.suggSource
        suggestionsIntent = saved.intentLabel
        if (saved.translated.isNotBlank()) {
            translatedTextView.text = saved.translated
            statusTextView.text = "已恢复上次翻译 · " + formatTime(saved.timeMillis)
            if (saved.suggestions.isNotEmpty()) {
                renderSuggestions(saved.suggestions, saved.suggSource, saved.intentLabel)
            }
        } else {
            statusTextView.text = "已恢复上次原文，正在重新翻译…"
            translateAndShow(saved.original)
        }
    }

    private fun formatTime(millis: Long): String = runCatching {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }.getOrDefault("")

    /** 清空记录：会话、输入框、译文、建议全部清掉。 */
    private fun clearSession() {
        runCatching { SessionStore.clear(applicationContext) }
        lastSessionOriginal = null
        lastClipboardSeen = null
        setInputText("")
        translatedTextView.text = "—"
        suggestionsContainer.removeAllViews()
        suggestionsSource = "local"
        suggestionsIntent = ""
        suggestionsToggle.text = "回复建议 · 点开"
        statusTextView.text = "已清空，输入一段话或复制后点“翻译”"
    }

    // ---------- 回复建议折叠 / 渲染 ----------

    /** 回复建议折叠/展开切换。标题带条数、来源和识别出的意图。 */
    private fun suggestionsMetaText(expanded: Boolean): String {
        val count = suggestionsContainer.childCount
        val src = if (suggestionsSource == "llm") "智能生成" else "本地规则"
        val intent = if (suggestionsIntent.isNotEmpty()) " · $suggestionsIntent" else ""
        return if (count == 0) {
            "回复建议 · 点开"
        } else {
            "回复建议（$count 条 · $src$intent）· " + if (expanded) "收起" else "点开"
        }
    }

    private fun toggleSuggestions() {
        val expanded = suggestionsContainer.visibility != View.VISIBLE
        suggestionsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        suggestionsToggle.text = suggestionsMetaText(expanded)
        runCatching {
            prefs().edit()
                .putBoolean(FloatingBallService.KEY_SUGGESTIONS_EXPANDED, expanded).apply()
        }
        if (suggestionsContainer.childCount == 0 && expanded) {
            suggestionsToggle.text = "回复建议 · 收起（翻译后显示）"
        }
    }

    /**
     * 回复建议渲染：对方发什么语言，回复就用什么语言；
     * 每条建议下面附另一语言的翻译对照。默认折叠，不占面板空间。
     * 标题注明来源（智能生成 / 本地规则）和识别出的意图。
     */
    private fun renderSuggestions(
        suggestions: List<ReplySuggester.Suggestion>,
        source: String,
        intentLabel: String
    ) {
        suggestionsContainer.removeAllViews()
        suggestionsSource = source
        suggestionsIntent = intentLabel
        val replyLangName = if (suggestions.firstOrNull()?.let {
                it.textMain.any { ch -> ch in '\u4E00'..'\u9FFF' }
            } == true) "中文" else "英文"
        for (s in suggestions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#F9FAFB"))
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val toneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tone = TextView(this).apply {
                text = "[${s.toneLabelZh} · $replyLangName]"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ink)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val copyBtn = Button(this).apply {
                text = "复制回复"
                textSize = 12f
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { copyToClipboard(s.textMain) }
            }
            val copyTransBtn = Button(this).apply {
                text = "复制翻译"
                textSize = 12f
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { copyToClipboard(s.textSub) }
            }
            toneRow.addView(tone)
            toneRow.addView(copyBtn)
            toneRow.addView(copyTransBtn)
            val main = TextView(this).apply {
                text = s.textMain
                textSize = 14f
                setTextColor(ink)
                setPadding(0, dp(4), 0, 0)
            }
            val sub = TextView(this).apply {
                text = "译：${s.textSub}"
                textSize = 12f
                setTextColor(gray)
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(toneRow)
            card.addView(main)
            card.addView(sub)
            suggestionsContainer.addView(card)
        }
        // 建议已生成，标题带条数、来源和意图；保持折叠状态不变，等用户点开
        val expanded = suggestionsContainer.visibility == View.VISIBLE
        suggestionsToggle.text = suggestionsMetaText(expanded)
    }

    // ---------- 翻译 + 回复建议 ----------

    private fun translateAndShow(original: String) {
        statusTextView.text = "翻译中…"
        scope.launch {
            try {
                val appCtx = applicationContext
                val config = EngineSettings.load(appCtx)
                val direction = prefs().getString(FloatingBallService.KEY_DIRECTION, "auto")
                val result = when (direction) {
                    "zh2en" -> Translator.translate(original, Translator.Lang.ZH, Translator.Lang.EN, config)
                    "en2zh" -> Translator.translate(original, Translator.Lang.EN, Translator.Lang.ZH, config)
                    else -> Translator.translateAuto(original, config)
                }
                // 建议生成：填了大模型 Key 就优先走 LLM，失败降级本地规则。
                val (sugg, src) = suggestWithFallback(original, result.detectedSource, config)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    translatedTextView.text = result.translated
                    statusTextView.text = "已翻译（${EngineSettings.labelOf(config.engineId)}）"
                    suggestionsSource = src
                    suggestionsIntent = ReplySuggester.intentLabelZh(original)
                    val session = SessionStore.Session(
                        original = original,
                        translated = result.translated,
                        direction = direction ?: "auto",
                        sourceLang = if (result.detectedSource == Translator.Lang.EN) "en" else "zh",
                        suggestions = sugg,
                        suggSource = src,
                        intentLabel = suggestionsIntent,
                        timeMillis = System.currentTimeMillis()
                    )
                    lastSessionOriginal = original
                    runCatching { SessionStore.save(appCtx, session) }
                    renderSuggestions(sugg, src, suggestionsIntent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    translatedTextView.text = "翻译失败"
                    statusTextView.text = "翻译失败：${e.message}，可在主界面切换翻译引擎后重试"
                }
            }
        }
    }

    /** 建议生成：填了大模型 Key 优先走 LLM，任何失败都降级本地规则。 */
    private suspend fun suggestWithFallback(
        original: String,
        sourceLang: Translator.Lang,
        config: EngineSettings.Config
    ): Pair<List<ReplySuggester.Suggestion>, String> {
        if (EngineSettings.hasLlm(config)) {
            val llm = runCatching { LlmSuggester.suggest(original, sourceLang, config) }.getOrNull()
            if (!llm.isNullOrEmpty()) return llm to "llm"
        }
        return ReplySuggester.suggest(original, sourceLang) to "local"
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
}
