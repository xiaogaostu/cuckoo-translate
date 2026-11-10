package com.floatingtranslate.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking

/**
 * 主界面 v1.4.0：纯原生 Android Views，零 Compose、零 androidx。
 *
 * v1.4.0 改动（按真机反馈 + 成品参考：Google 翻译“点按翻译”、
 * LoloTrans、overlay-translator / playtranslate）：
 * 1. 砍掉自动识别翻译：不再要无障碍权限，不读其他 App 的聊天内容。
 *    只做手动模式：复制一段话 -> 点悬浮球 -> 剪贴板自动填入 -> 点翻译
 *    -> 复制译文。主界面删掉权限状态（无障碍部分）、受限设置引导、
 *    手动模式说明等冗余卡片。
 * 2. 精简：只留手动翻译、悬浮球（含悬浮窗权限）、翻译方向、翻译引擎、
 *    使用说明 5 组内容。
 * 3. 按钮/文本框统一美化：圆角 10dp、最小高 44dp、字号 14sp、主按钮
 *    深灰实底、次按钮白底灰边；同行按钮等宽分栏，窄屏不溢出；
 *    输入框圆角边框、合适高度、灰色 hint。
 * 4. 悬浮球尺寸改小：默认 40dp，可调 32–56dp。
 *
 * v1.5.0 改动：
 * 1. 名字改成 Cuckoo：主界面标题、通知标题同步改名，副标题保留
 *    “悬浮翻译”说明；启动图标重做为布谷鸟 + 翻译气泡的简约设计。
 * 2. 智能回复（可选）：新增大模型设置，填 OpenAI 兼容接口和 Key
 *    后面板优先走 LLM 生成建议，失败降级本地规则。
 * 3. 手动翻译卡片打开时恢复上次会话（原文 + 译文直接填回去）。
 * 4. 使用说明补充：面板收起后内容保留，返回手势先收键盘再收面板。
 *
 * v1.6.0 改动（换架构根治输入法问题 + 卡通图标）：
 * 1. 悬浮面板里的输入框整个拿掉：面板永远不聚焦、永远不弹输入法，
 *    不存在“收键盘也收面板”。想打字点面板里的“去主界面输入”，
 *    在主界面（正常 Activity）里打字翻译。
 * 2. 布谷鸟图标改卡通：圆滚滚青绿色鸟身、大眼睛、黄色小尖嘴、
 *    头顶一小撮毛、圆弧翅膀 + 圆润对话气泡（白色 A）。
 * 3. 使用说明同步改成新流程。
 *
 * v1.6.2 改动（根治 v1.6.0 的 workaround）：
 * 1. 面板从悬浮窗改成真正的半透明 Activity（TranslatePanelActivity，
 *    对话框式小卡片）：面板里恢复了输入框，点球进来就能直接打字；
 *    Activity 获得焦点，剪贴板在 onResume 里读得到（Android 10+ 允许），
 *    复制完点球自动填入并翻译；键盘和返回手势都是系统标准行为。
 * 2. 使用说明同步改成新流程。
 *
 * 启动安全（v1.3 结论保留）：onCreate 全包 try-catch (Throwable)，
 * 出错只显示一行说明绝不闪退；onCreate 只建界面，不启动 Service，
 * 不读任何权限状态；权限检查只在点按钮时做且全部 try-catch。
 */
class MainActivity : Activity() {

    private val ink = Color.parseColor("#111827")
    private val gray = Color.parseColor("#6B7280")
    private val line = Color.parseColor("#E5E7EB")
    private val cardBg = Color.parseColor("#FFFFFF")
    private val inputBg = Color.parseColor("#F9FAFB")

    private val uiHandler = Handler(Looper.getMainLooper())

    private fun prefs(): SharedPreferences? = runCatching {
        getSharedPreferences(FloatingBallService.PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            buildUi()
        } catch (t: Throwable) {
            try {
                val tv = TextView(this).apply {
                    text = "悬浮翻译\n\n主界面加载失败，但 App 没有崩溃。\n请卸载后重新安装 v1.6.2（不要覆盖安装）。\n错误：${t.javaClass.simpleName}: ${t.message}"
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

    // ---------- 统一样式 ----------

    private fun roundedBg(fill: Int, stroke: Int = line, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(ink)
    }

    private fun bodyText(text: String, size: Float = 13f, color: Int = gray): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        setBackgroundColor(line)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(cardBg)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
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

    private fun inputBox(hint: String, singleLine: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(gray)
            background = roundedBg(inputBg, Color.parseColor("#D1D5DB"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (!singleLine) minLines = 2
            this.isSingleLine = singleLine
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    /** 同行按钮等宽分栏：窄屏也不溢出、不重叠。 */
    private fun weightedRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            buttons.forEachIndexed { i, b ->
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (i > 0) lp.marginStart = dp(8)
                addView(b, lp)
            }
        }

    private fun toast(msg: String) =
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { toast("操作失败：${t.message}") }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) { toast("没有可复制的内容"); return }
        safe {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", text))
            toast("已复制")
        }
    }

    private fun readClipboard(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (cm.hasPrimaryClip()) cm.primaryClip?.getItemAt(0)?.text?.toString() else null
    }.getOrNull()

    private fun isOverlayGranted(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true
    }.getOrDefault(false)

    private fun isBallRunning(): Boolean =
        runCatching { prefs()?.getBoolean("ball_running", false) ?: false }.getOrDefault(false)

    private fun runTranslate(text: String, onResult: (String) -> Unit) {
        Thread {
            val out = try {
                val config = runCatching { EngineSettings.load(this) }
                    .getOrDefault(EngineSettings.Config())
                runBlocking { Translator.translateAuto(text, config) }.translated
            } catch (t: Throwable) {
                "翻译失败：${t.message}，可在下面切换翻译引擎后重试"
            }
            uiHandler.post { runCatching { onResult(out) } }
        }.start()
    }

    // ---------- 主界面 ----------

    private fun buildUi() {
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        col.addView(TextView(this).apply {
            text = "Cuckoo"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
        })
        col.addView(bodyText("悬浮翻译 · 复制一段话，点悬浮球，马上翻译。不需要无障碍权限，不读取其他 App 的内容。"))
        col.addView(divider())

        // ---------- 手动翻译 ----------
        run {
            val c = card()
            c.addView(sectionTitle("手动翻译"))
            val input = inputBox("粘贴要翻译的话…")
            c.addView(input)
            val result = bodyText("译文将显示在这里", 15f, ink)
            // v1.5.0：打开主界面就恢复上次会话，原文和译文直接填回去。
            runCatching { SessionStore.load(this) }.getOrNull()?.let { s ->
                input.setText(s.original)
                if (s.translated.isNotBlank()) result.text = s.translated
            }
            val translateBtn = primaryButton("翻译这段")
            translateBtn.setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("请先粘贴一段文本"); return@setOnClickListener }
                result.text = "翻译中…"
                runTranslate(text) { result.text = it }
            }
            val pasteBtn = secondaryButton("粘贴剪贴板")
            pasteBtn.setOnClickListener {
                val clip = readClipboard()
                if (clip.isNullOrBlank()) toast("剪贴板是空的，先去复制一段文字") else input.setText(clip)
            }
            c.addView(weightedRow(translateBtn, pasteBtn))
            c.addView(result)
            val copyBtn = secondaryButton("复制译文")
            copyBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            copyBtn.setOnClickListener { copyToClipboard(result.text.toString()) }
            c.addView(copyBtn)
            col.addView(c)
        }

        // ---------- 悬浮球（含悬浮窗权限） ----------
        run {
            val c = card()
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val title = sectionTitle("悬浮球")
            title.layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val sw = Switch(this).apply { isChecked = isBallRunning() }
            sw.setOnCheckedChangeListener { _, on ->
                safe {
                    if (on) {
                        if (!isOverlayGranted()) {
                            toast("请先开启悬浮窗权限")
                            sw.isChecked = false
                            return@safe
                        }
                        val intent = Intent(this, FloatingBallService::class.java).apply {
                            action = FloatingBallService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        prefs()?.edit()?.putBoolean("ball_running", true)?.apply()
                    } else {
                        val intent = Intent(this, FloatingBallService::class.java).apply {
                            action = FloatingBallService.ACTION_STOP
                        }
                        startService(intent)
                        prefs()?.edit()?.putBoolean("ball_running", false)?.apply()
                    }
                }
            }
            titleRow.addView(title)
            titleRow.addView(sw)
            c.addView(titleRow)
            c.addView(bodyText("40dp 小白球，可拖动、贴边吸附、拖到底部关闭，闲置 5 秒变淡；点一下展开小面板，双击翻译剪贴板。"))
            val overlayTv = bodyText("悬浮窗权限：未检测（点“刷新状态”查看）")
            c.addView(overlayTv)
            val overlayBtn = primaryButton("去开悬浮窗权限")
            overlayBtn.setOnClickListener {
                safe {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                }
            }
            val refreshBtn = secondaryButton("刷新状态")
            refreshBtn.setOnClickListener {
                overlayTv.text = "悬浮窗权限：" + if (isOverlayGranted()) "已开启" else "未开启"
            }
            c.addView(weightedRow(overlayBtn, refreshBtn))
            // 球大小：32–56dp，默认 40dp
            val size = runCatching {
                prefs()?.getInt(FloatingBallService.KEY_BALL_SIZE_DP, FloatingBallService.BALL_SIZE_DEFAULT_DP)
                    ?: FloatingBallService.BALL_SIZE_DEFAULT_DP
            }.getOrDefault(FloatingBallService.BALL_SIZE_DEFAULT_DP)
            val sizeTv = bodyText("悬浮球大小：$size dp（32–56）", 14f, ink)
            val sizeBar = SeekBar(this).apply {
                max = FloatingBallService.BALL_SIZE_MAX_DP - FloatingBallService.BALL_SIZE_MIN_DP
                progress = (size - FloatingBallService.BALL_SIZE_MIN_DP)
                    .coerceIn(0, max)
            }
            sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p0: Int, fromUser: Boolean) {
                    sizeTv.text = "悬浮球大小：${p0 + FloatingBallService.BALL_SIZE_MIN_DP} dp（32–56）"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    safe {
                        prefs()?.edit()?.putInt(FloatingBallService.KEY_BALL_SIZE_DP,
                            (s?.progress ?: 8) + FloatingBallService.BALL_SIZE_MIN_DP)?.apply()
                    }
                }
            })
            c.addView(sizeTv)
            c.addView(sizeBar)
            val alpha = runCatching { prefs()?.getFloat(FloatingBallService.KEY_BALL_ALPHA, 0.9f) ?: 0.9f }
                .getOrDefault(0.9f)
            val alphaTv = bodyText("悬浮球透明度：${(alpha * 100).toInt()}%", 14f, ink)
            val alphaBar = SeekBar(this).apply {
                max = 60
                progress = ((alpha * 100).toInt() - 40).coerceIn(0, 60)
            }
            alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p0: Int, fromUser: Boolean) {
                    alphaTv.text = "悬浮球透明度：${p0 + 40}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    safe {
                        prefs()?.edit()?.putFloat(FloatingBallService.KEY_BALL_ALPHA,
                            ((s?.progress ?: 50) + 40) / 100f)?.apply()
                    }
                }
            })
            c.addView(alphaTv)
            c.addView(alphaBar)
            c.addView(bodyText("改完大小 / 透明度后，关掉再打开悬浮球生效。"))
            col.addView(c)
        }

        // ---------- 翻译方向 ----------
        run {
            val c = card()
            c.addView(sectionTitle("翻译方向"))
            val saved = runCatching {
                prefs()?.getString(FloatingBallService.KEY_DIRECTION, "auto")
            }.getOrNull() ?: "auto"
            val group = RadioGroup(this)
            val options = listOf("自动检测" to "auto", "中文 → 英文" to "zh2en", "英文 → 中文" to "en2zh")
            for ((label, value) in options) {
                val rb = RadioButton(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(ink)
                    isChecked = saved == value
                    tag = value
                }
                group.addView(rb)
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val rb = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
                safe {
                    prefs()?.edit()
                        ?.putString(FloatingBallService.KEY_DIRECTION, rb.tag as String)?.apply()
                }
            }
            c.addView(group)
            col.addView(c)
        }

        // ---------- 翻译引擎（用户自选） ----------
        run {
            val c = card()
            c.addView(sectionTitle("翻译引擎（可自己选）"))
            val savedConfig = runCatching { EngineSettings.load(this) }
                .getOrDefault(EngineSettings.Config())
            var engineId = savedConfig.engineId
            val group = RadioGroup(this)
            for (engine in EngineSettings.ENGINES) {
                val rb = RadioButton(this).apply {
                    text = engine.label + " — " + engine.hint
                    textSize = 13f
                    setTextColor(ink)
                    isChecked = engineId == engine.id
                    tag = engine.id
                }
                group.addView(rb)
            }
            val deeplKey = inputBox("DeepL API Key", singleLine = true).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
                hint = "DeepL API Key（选 DeepL 时填）"
            }
            deeplKey.setText(savedConfig.deeplKey)
            val customUrl = inputBox("接口地址", singleLine = true).apply {
                hint = "自定义接口地址（选自定义接口时填）"
            }
            customUrl.setText(savedConfig.customUrl)
            val customKey = inputBox("接口 Key（可选）", singleLine = true).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            customKey.setText(savedConfig.customKey)
            fun refreshEngineFields() {
                deeplKey.visibility = if (engineId == EngineSettings.ENGINE_DEEPL) View.VISIBLE else View.GONE
                val customVisible = engineId == EngineSettings.ENGINE_CUSTOM
                customUrl.visibility = if (customVisible) View.VISIBLE else View.GONE
                customKey.visibility = if (customVisible) View.VISIBLE else View.GONE
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val rb = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
                engineId = rb.tag as String
                refreshEngineFields()
            }
            refreshEngineFields()
            c.addView(group)
            c.addView(deeplKey)
            c.addView(customUrl)
            c.addView(customKey)
            val savedTv = bodyText("")
            val testTv = bodyText("")
            val saveBtn = primaryButton("保存引擎设置")
            saveBtn.setOnClickListener {
                safe {
                    EngineSettings.save(this, EngineSettings.Config(
                        engineId,
                        deeplKey.text.toString(),
                        customUrl.text.toString(),
                        customKey.text.toString()
                    ))
                    savedTv.text = "已保存当前引擎设置"
                    testTv.text = ""
                }
            }
            val testBtn = secondaryButton("测试引擎")
            testBtn.setOnClickListener {
                testBtn.isEnabled = false
                testTv.text = "测试中…"
                Thread {
                    val config = EngineSettings.Config(
                        engineId,
                        deeplKey.text.toString(),
                        customUrl.text.toString(),
                        customKey.text.toString()
                    )
                    runCatching { EngineSettings.save(this, config) }
                    val r = try {
                        runBlocking { Translator.testEngine(config) }
                    } catch (t: Throwable) {
                        Translator.TestResult(false, "测试失败：${t.message}")
                    }
                    uiHandler.post {
                        runCatching {
                            testBtn.isEnabled = true
                            testTv.text = r.message
                        }
                    }
                }.start()
            }
            c.addView(weightedRow(saveBtn, testBtn))
            c.addView(savedTv)
            c.addView(testTv)
            col.addView(c)
        }

        // ---------- 智能回复（可选，大模型） ----------
        run {
            val c = card()
            c.addView(sectionTitle("智能回复（可选）"))
            c.addView(bodyText("填你自己的大模型接口和 Key，面板里的回复建议会优先走大模型智能生成；不填就用本地规则，失败时自动降级。DeepL 是翻译引擎，不走这里。"))
            val savedLlm = runCatching { EngineSettings.load(this) }
                .getOrDefault(EngineSettings.Config())
            val llmUrl = inputBox("大模型接口地址", singleLine = true).apply {
                hint = "如 https://api.deepseek.com/v1（OpenAI 兼容）"
            }
            llmUrl.setText(savedLlm.llmUrl)
            val llmKey = inputBox("大模型 Key", singleLine = true).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
                hint = "大模型 API Key（必填才启用）"
            }
            llmKey.setText(savedLlm.llmKey)
            val llmModel = inputBox("模型名", singleLine = true).apply {
                hint = "如 deepseek-chat / gpt-4o-mini"
            }
            llmModel.setText(savedLlm.llmModel)
            val llmSavedTv = bodyText("")
            val llmSaveBtn = primaryButton("保存智能回复设置")
            llmSaveBtn.setOnClickListener {
                safe {
                    val cur = runCatching { EngineSettings.load(this) }
                        .getOrDefault(EngineSettings.Config())
                    EngineSettings.save(this, cur.copy(
                        llmUrl = llmUrl.text.toString().trim(),
                        llmKey = llmKey.text.toString().trim(),
                        llmModel = llmModel.text.toString().trim().ifBlank { "deepseek-chat" }
                    ))
                    llmSavedTv.text = if (llmUrl.text.toString().isBlank() || llmKey.text.toString().isBlank())
                        "已保存：未填完整，继续用本地规则生成建议"
                    else
                        "已保存：回复建议将优先走大模型智能生成"
                }
            }
            c.addView(llmUrl)
            c.addView(llmKey)
            c.addView(llmModel)
            c.addView(llmSaveBtn)
            c.addView(llmSavedTv)
            col.addView(c)
        }

        // ---------- 使用说明 ----------
        run {
            val c = card()
            c.addView(sectionTitle("使用说明"))
            c.addView(bodyText(
                "1. 在任何聊天 App 里复制一段话。\n" +
                "2. 点屏幕上的悬浮球，打开翻译小面板：剪贴板内容自动填入并翻译；也可以直接在面板里打字输入，点“翻译”。\n" +
                "3. 译文下面点“复制译文”，粘贴回聊天框。\n" +
                "4. 双击悬浮球直接打开面板翻译剪贴板；拖到底部关闭悬浮球。\n" +
                "5. 面板里“回复建议”默认折叠，点一下展开，语言和对方一致，每条下面附另一语言的翻译；标题会注明是智能生成还是本地规则，以及识别出的意图。\n" +
                "6. 面板是半透明小卡片，浮在聊天界面上方：键盘开着时按返回先收键盘，再按返回回到聊天；点 × 或点面板外面也能关闭面板。面板关掉后内容会保留，再点球进来直接看到上次的译文和建议，点“清空记录”可清除。"
            ))
            c.addView(divider())
            c.addView(bodyText(
                "不需要无障碍权限，不读取其他 App 的内容。翻译依赖网络，免费端点在国内网络下可能不可用，失败时会自动降级 MyMemory；两个都不可用时会提示失败，不会假装翻译成功。电池优化可能杀掉前台 Service，悬浮球消失后回 App 重新打开总开关即可。"
            ))
            col.addView(c)
        }

        root.addView(col)
        setContentView(root)
    }
}
