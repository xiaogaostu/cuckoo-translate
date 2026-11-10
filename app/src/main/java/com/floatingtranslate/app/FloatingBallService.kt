package com.floatingtranslate.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/**
 * 悬浮球前台 Service（v1.6.2：面板已迁出，只留悬浮球 + 通知）。
 *
 * v1.4.0 改动（按真机反馈）：
 * 1. 砍掉自动识别翻译：TranslationAccessibilityService 已删除，本 Service
 *    不再注册/监听任何无障碍广播，不读其他 App 的聊天内容。只做手动模式：
 *    用户复制一段话 -> 点悬浮球 -> 剪贴板自动填入 -> 点翻译 -> 复制译文。
 * 2. 通知栏补完整：NotificationChannel + 标题/内容 + “打开主界面” /
 *    “关闭悬浮球”两个动作；smallIcon 用白色镂空矢量图标
 *    （ic_notification），不再用彩色 launcher 图标，状态栏不再异常。
 * 3. 悬浮球改小：默认 40dp（原来 56dp），可调 32–56dp；简约白球保留。
 * 4. 面板精简：宽 ≤300dp、高 ≤ 屏幕 50%，只留输入框、翻译按钮、
 *    译文 + 复制按钮；回复建议默认折叠，点一下才展开。
 * 5. 输入法返回手势两步走：面板根布局拦截 KEYCODE_BACK，第一步只收
 *    键盘（输入框失焦、面板切回不聚焦），键盘已收起时第二步才收面板。
 * 6. 按钮/文本框统一美化：圆角 10dp、最小高 44dp、字号 14sp、主次分明。
 *
 * v1.5.0 改动（按真机反馈：返回手势关面板 + 内容丢失 + 建议不智能）：
 * 1. 返回手势根因重查：v1.4.0 只靠面板根布局 dispatchKeyEvent 拦截
 *    BACK，但悬浮窗平时 FLAG_NOT_FOCUSABLE 根本收不到按键；点输入框
 *    后输入法先吃 BACK，手维护的 keyboardOpen 又和真实输入法状态
 *    脱节，手势返回补发的第二个 BACK 就把面板关了。改成标准解法：
 *    输入框改用 BackAwareEditText 重写 onKeyPreIme，在输入法之前拦截
 *    BACK；键盘可见状态改由布局监听实测（可见显示区域高度差），
 *    不再手维护标志位；收键盘后延迟 150ms 再恢复 NOT_FOCUSABLE，
 *    让进行中的手势先结束；dispatchKeyEvent 只留作第二层兜底。
 * 2. 内容持久化：面板收起只是隐藏视图，数据不再销毁。会话（原文 +
 *    译文 + 方向 + 建议 + 来源 + 意图）存内存 + SharedPreferences，
 *    再点球进来立刻恢复上次内容，不重新联网；只有复制了新的不同
 *    内容或点“清空记录”才更新；没点翻译就收起的输入文字也当草稿存。
 * 3. 建议更智能：ReplySuggester 重写为 12 种意图 + 时间/价格/地点
 *    实体抽取，回复贴合原文、口语化；填了大模型 Key 后优先走
 *    LlmSuggester（OpenAI 兼容接口）智能生成，失败降级本地规则，
 *    面板上注明“智能生成”还是“本地规则”。
 *
 * v1.6.0 改动（技术真相 + 换架构，不再修 flag）：
 * 1. 悬浮框和输入法不是同一层：悬浮框是 TYPE_APPLICATION_OVERLAY
 *    窗口，输入法是系统输入法窗口，两个独立窗口。但 Android 的返回
 *    分发会把它们绑在一起：悬浮窗平时 FLAG_NOT_FOCUSABLE 根本收不到
 *    返回；点输入框后变可聚焦，输入法先吃掉返回；三星/华为手势返回
 *    还会在键盘收起后补发一个返回；onKeyPreIme 在真机手势返回上不可
 *    靠。所以 v1.4/v1.5 两版“两步返回”代码里看着对、真机上还是失效。
 *    这不是写错一行，是架构选错了：在悬浮窗里放可编辑输入框，本身
 *    就是 Android 上最难做对的形态，Google 翻译气泡、LoloTrans 都不
 *    在气泡里放输入框。
 * 2. 根治：面板里的 EditText 整个拿掉，面板永远 FLAG_NOT_FOCUSABLE，
 *    绝不抢焦点，永远不弹输入法。面板只做三件事：显示剪贴板自动填入
 *    的原文（只读 TextView）、翻译按钮、译文 + 复制按钮 + 折叠的回复
 *    建议。想改文字、想打字，点“去主界面输入”进 Cuckoo 主界面
 *    （正常 Activity，输入法和返回都是系统标准行为）。面板不存在
 *    “收键盘也收面板”的问题：返回手势回到聊天，点 × 或面板外收起。
 *    BackAwareEditText 已删除。SessionStore 持久化保留，面板重开恢复。
 * 3. 图标：布谷鸟改卡通风——圆滚滚青绿色鸟身、大眼睛、黄色小尖嘴、
 *    头顶一小撮毛、圆弧翅膀，鸟嘴前圆润对话气泡，气泡里白色字母 A。
 *
 * v1.6.1 改动（只改图标，未改功能）：
 * 启动图标按真布谷鸟重画（方案 3 栖枝版），悬浮球换成简笔画版小鸟。
 *
 * v1.6.2 改动（根治 v1.6.0 的 workaround，用户真机反馈）：
 * v1.6 拿掉输入框只是 workaround：面板里没法打字，只能回主界面输入，
 * 等于悬浮球白做了；剪贴板也读不到——Android 10+ 只允许获得焦点的
 * 应用读剪贴板，面板永远不聚焦、读剪贴板的代码还在 Service 里跑，
 * 系统直接返回空。不是用户没复制，是代码拿不到。
 * 根治：面板迁出悬浮窗，改成真正的半透明 Activity（TranslatePanelActivity，
 * 对话框式小卡片）。本 Service 只留悬浮球 + 前台通知，悬浮窗面板那套
 * 代码（PanelRootLayout / showPanel / hidePanel / restoreSessionOrClipboard /
 * translateAndShow / renderSuggestions 等）全部删除。点球用
 * FLAG_ACTIVITY_NEW_TASK 启动面板 Activity；单击开关面板，双击打开
 * 面板（Activity 在 onResume 里读剪贴板并自动翻译）。翻译、会话持久化、
 * 回复建议的逻辑都搬进了 TranslatePanelActivity，Service 不再碰翻译。
 * 悬浮球本身保持 FLAG_NOT_FOCUSABLE，绝不抢焦点；不加回无障碍权限，
 * 权限保持 7 个。
 */
class FloatingBallService : Service() {

    companion object {
        const val ACTION_START = "com.floatingtranslate.app.START_BALL"
        const val ACTION_STOP = "com.floatingtranslate.app.STOP_BALL"
        const val PREFS = "floating_translate_prefs"
        const val KEY_BALL_SIZE_DP = "ball_size_dp"
        const val KEY_BALL_ALPHA = "ball_alpha"
        const val KEY_DIRECTION = "direction" // auto / zh2en / en2zh
        const val KEY_SUGGESTIONS_EXPANDED = "suggestions_expanded"
        private const val CHANNEL_ID = "floating_translate_channel"
        private const val NOTIFICATION_ID = 1001

        const val COLOR_PRIMARY = "#111827"
        const val COLOR_BORDER = "#D1D5DB"
        const val COLOR_SUBTLE = "#6B7280"

        // 球尺寸：默认 40dp，可调 32–56dp
        const val BALL_SIZE_DEFAULT_DP = 40
        const val BALL_SIZE_MIN_DP = 32
        const val BALL_SIZE_MAX_DP = 56
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            showBall()
        } catch (t: Throwable) {
            runCatching { stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        removeBall()
    }

    // ---------- 通知（v1.4：完整通知栏） ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮翻译",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "悬浮翻译运行中，点悬浮球翻译剪贴板内容" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // 打开主界面：点通知正文 / “打开主界面”动作
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 关闭悬浮球：发 ACTION_STOP 给本 Service
        val stopIntent = Intent(this, FloatingBallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_ball_notification_title))
            .setContentText(getString(R.string.floating_ball_notification_text))
            // 白色镂空矢量图标：状态栏合规显示，绝不用彩色 launcher 图标
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .addAction(0, "打开主界面", openPending)
            .addAction(0, "关闭悬浮球", stopPending)
            .setOngoing(true)
            .build()
    }

    // ---------- 工具 ----------

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 悬浮球（默认 40dp 小白球） ----------

    private fun showBall() {
        if (ballView != null) return
        val sizeDp = runCatching { prefs().getInt(KEY_BALL_SIZE_DP, BALL_SIZE_DEFAULT_DP) }
            .getOrDefault(BALL_SIZE_DEFAULT_DP)
            .coerceIn(BALL_SIZE_MIN_DP, BALL_SIZE_MAX_DP)
        val alpha = runCatching { prefs().getFloat(KEY_BALL_ALPHA, 0.9f) }.getOrDefault(0.9f)
        val sizePx = dp(sizeDp)

        val ball = FrameLayout(this).apply {
            this.alpha = alpha
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor(COLOR_BORDER))
            }
        }
        val glyph = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_ball_bird)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                dp(26),
                dp(26),
                Gravity.CENTER
            )
        }
        ball.addView(glyph)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(300)
        }
        ball.setOnTouchListener(BallTouchListener(params))
        ballView = ball
        ballParams = params
        runCatching { windowManager.addView(ball, params) }
            .onFailure {
                Toast.makeText(this, "悬浮窗权限未开启，悬浮球无法显示，请先去开启", Toast.LENGTH_LONG).show()
                ballView = null
                ballParams = null
                return
            }
        scheduleIdleFade()
    }

    private fun removeBall() {
        mainHandler.removeCallbacksAndMessages(null)
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        ballParams = null
    }

    /** 闲置 5 秒后只变淡（不缩放），碰一下立刻恢复。 */
    private val idleFadeRunnable = Runnable {
        ballView?.animate()?.alpha(0.55f)?.setDuration(300)?.start()
    }

    private fun scheduleIdleFade() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        mainHandler.postDelayed(idleFadeRunnable, 5000)
    }

    private fun restoreBall() {
        mainHandler.removeCallbacks(idleFadeRunnable)
        ballView?.animate()?.alpha(prefs().getFloat(KEY_BALL_ALPHA, 0.9f))
            ?.setDuration(150)?.start()
        scheduleIdleFade()
    }

    /**
     * v1.6.2：点球不再打开悬浮窗面板，改为启动面板 Activity
     * （TranslatePanelActivity，对话框式小卡片）：
     * - 单击：面板开着就关掉，没开就打开。
     * - 双击：直接打开面板；面板在 onResume 里读剪贴板，有新内容自动填入并翻译。
     * Service 里不再读剪贴板、不再做翻译——Android 10+ 下 Service 读剪贴板
     * 会被系统返回空，读剪贴板只能由获得焦点的 Activity 来做。
     */
    private fun openPanel() {
        runCatching {
            val intent = Intent(this, TranslatePanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    private fun togglePanel() {
        if (TranslatePanelActivity.isOpen) {
            runCatching {
                val close = Intent(this, TranslatePanelActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(TranslatePanelActivity.EXTRA_CLOSE, true)
                }
                startActivity(close)
            }
        } else {
            openPanel()
        }
    }

    /** 拖动 / 单击 / 双击：移动 < 12dp 且 < 300ms 算点击；300ms 内两次点击算双击。 */
    private inner class BallTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var startX = 0
        private var startY = 0
        private var lastTapUpTime = 0L
        private var pendingSingleTap: Runnable? = null

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    restoreBall()
                    downX = event.rawX
                    downY = event.rawY
                    downTime = System.currentTimeMillis()
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(
                        (event.rawX - downX).toDouble(),
                        (event.rawY - downY).toDouble()
                    )
                    val duration = System.currentTimeMillis() - downTime
                    if (moved < dp(12) && duration < 300) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpTime < 350) {
                            pendingSingleTap?.let { mainHandler.removeCallbacks(it) }
                            pendingSingleTap = null
                            lastTapUpTime = 0L
                            openPanel()
                        } else {
                            lastTapUpTime = now
                            val r = Runnable { togglePanel() }
                            pendingSingleTap = r
                            mainHandler.postDelayed(r, 320)
                        }
                    } else {
                        // 拖到底部边缘：关闭悬浮球（LoloTrans / 行业惯例）
                        val screenH = resources.displayMetrics.heightPixels
                        if (params.y + v.height > screenH - dp(100)) {
                            runCatching {
                                prefs().edit().putBoolean("ball_running", false).apply()
                            }
                            Toast.makeText(
                                this@FloatingBallService,
                                "悬浮球已关闭，回主界面可重新打开",
                                Toast.LENGTH_SHORT
                            ).show()
                            removeBall()
                            stopSelf()
                        } else {
                            snapToEdge(v)
                        }
                    }
                    scheduleIdleFade()
                    return true
                }
            }
            return false
        }

        private fun snapToEdge(v: View) {
            val screenW = resources.displayMetrics.widthPixels
            val w = if (v.width > 0) v.width else dp(BALL_SIZE_DEFAULT_DP)
            val centerX = params.x + w / 2
            val targetX = if (centerX < screenW / 2) dp(8) else screenW - w - dp(8)
            ValueAnimator.ofInt(params.x, targetX).apply {
                duration = 180
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(v, params) }
                }
                start()
            }
        }
    }
}
