package com.example.miao

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.jvm.Volatile
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvSpeed: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvPowerLeft: TextView
    private lateinit var powerContainerLeft: View
    private lateinit var tvTime: TextView
    private lateinit var tvSoc: TextView
    private lateinit var pbBattery: ProgressBar
    private lateinit var pbSpeedBar: ProgressBar
    private lateinit var pbPowerBar: ProgressBar
    private lateinit var guidelineLeft: Guideline
    private lateinit var guidelineRight: Guideline
    private lateinit var surfaceView: SurfaceView
    private lateinit var divider: View
    private lateinit var centerCard: FrameLayout
    private lateinit var centerContainer: FrameLayout
    private lateinit var rightSidePanel: View
    
    private lateinit var musicCard: View
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var tvMusicLyric: TextView
    private lateinit var tvMusicTime: TextView
    private lateinit var pbMusicProgress: ProgressBar
    private lateinit var ivMusicCover: ImageView

    private lateinit var dragHandleLeft: View
    private lateinit var dragHandleRight: View

    private val logBuffer = mutableListOf<String>()
    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var lastVoltage: Double = 0.0
    private var lastCurrent: Double = 0.0
    private var musicDuration: Long = 0
    private var lastCoverUrl: String? = null
    
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    // logcat 酷狗通路最近一次命中音乐行的时间(elapsedRealtime)，
    // 用于抑制 MediaSession 通路对酷狗显示的覆盖
    @Volatile private var lastLogcatMusicAt: Long = 0L

    // 兜底轮询：系统未回调 onActiveSessionsChanged 时仍能发现新播放源
    private val mediaPollRunnable = object : Runnable {
        override fun run() {
            updateActiveMediaSession()
            uiHandler.postDelayed(this, 3000)
        }
    }

    private var floatingBall: View? = null
    private var currentSettingsOverlay: View? = null
    private var tvFloatingLog: TextView? = null
    private var floatingLogView: View? = null

    private val timeRunnable = object : Runnable {
        override fun run() {
            tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (tvFloatingLog != null && logBuffer.isNotEmpty()) {
                synchronized(logBuffer) { tvFloatingLog?.text = logBuffer.joinToString("\n"); logBuffer.clear() }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let {
                val title = it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                val artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                val album = it.getString(MediaMetadata.METADATA_KEY_ALBUM)
                val duration = it.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val art = it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                uiHandler.post {
                    if (title != null) tvMusicTitle.text = title
                    if (artist != null) tvMusicArtist.text = artist
                    if (album != null) tvMusicLyric.text = album
                    musicDuration = duration
                    // 酷狗 logcat 通路活跃时不允许 MediaSession 通路清空封面/重置显示
                    val kugouAlive = SystemClock.elapsedRealtime() - lastLogcatMusicAt < 10_000
                    if (art != null) {
                        ivMusicCover.setImageBitmap(art)
                        lastCoverUrl = null
                    } else if (!kugouAlive) {
                        ivMusicCover.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                    updateMusicTime(0)
                }
            }
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { uiHandler.post { updateMusicTime(it.position) } }
        }
    }

    companion object {
        private var virtualDisplay: VirtualDisplay? = null
        private var hintPresentation: HintPresentation? = null
        private var currentRunningPackage: String? = null
        const val MODE_DISPLAY_ONLY = 0
        const val MODE_CARPAY = 1
        const val MODE_EMBED_APP = 2
        const val MODE_MIRROR = 3
        private const val PREFS_NAME = "MiaoSettings"
        private const val KEY_MODE = "run_mode"
        private const val KEY_UI_STYLE_DOUBLE = "ui_style_double_wing"
        private const val KEY_UI_STYLE_VIRTUAL = "ui_style_virtual"
        private const val KEY_LAST_APP = "last_app_pkg"
        private const val KEY_SHOW_LOG_OVERLAY = "show_log_overlay"
        private const val KEY_LOG_FILTER_KEYWORD = "log_filter_keyword"
        private const val KEY_ENABLE_DRAG = "enable_divider_drag"
        private const val KEY_GUIDE_PERCENT = "guide_percent"
        private const val KEY_LOCK_RATIO = "lock_ratio_16_9"
        private const val KEY_USE_ACCESSIBILITY = "use_accessibility_touch"
        private const val KEY_SHOW_MUSIC_CARD = "show_music_card"
        
        private const val DEFAULT_TAG = "Mointerservice"
        private const val POWER_TAG = "CmdSmdManager"
        private const val SOC_TAG = "CarCabinManager"
        private const val MUSIC_TAG = "AvrcpControllerService"
        private const val MUSIC_PLAY_TAG = "BtMusicPlayImpl"
        private const val MUSIC_KUGOU_TAG = "KuGouMusicInterfaceService"
        private const val MUSIC_KUGOU_IMPL_TAG = "KuGouPlayImpl"
        private const val MUSIC_WIDGET_TAG = "MusicWidgetView"
        private const val MUSIC_A2DP_TAG = "A2dpMediaBrowserService"
        // 供 NotificationListener 回调访问 MainActivity 实例（跨进程通知服务）
        @Volatile var instance: MainActivity? = null
    }

    private var processMain: Process? = null
    private var processSystem: Process? = null
    private val isListening = AtomicBoolean(false)

    // 虚拟屏专属布局是否激活（仅 1920×1080 主屏 + 用户选择虚拟屏模式）
    private var useVirtualLayout = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程级豁免 non-SDK (hidden) 接口限制，使车机(Android 9)上可通过反射调用
        // MotionEvent.setDisplayId / InputManager.injectInputEvent 等 @hide 方法
        bypassHiddenApi()
        enableEdgeToEdge()

        // 虚拟屏模式检测：仅当主屏为 1920×1080 时启用专属布局
        val vmPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isVirtualMode = vmPrefs.getBoolean(KEY_UI_STYLE_VIRTUAL, false)
        val isMainScreen1080p = isPrimaryScreen1080p()
        if (isVirtualMode && isMainScreen1080p) {
            useVirtualLayout = true
            setContentView(R.layout.activity_main_virtual)
        } else {
            setContentView(R.layout.activity_main)
        }
        val mainLayout = findViewById<ConstraintLayout>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvSpeed = findViewById(R.id.tv_speed)
        tvPower = findViewById(R.id.tv_power)
        tvPowerLeft = findViewById(R.id.tv_power_left)
        powerContainerLeft = findViewById(R.id.power_container_left)
        tvTime = findViewById(R.id.tv_time)
        tvSoc = findViewById(R.id.tv_soc)
        pbBattery = findViewById(R.id.pb_battery)
        pbSpeedBar = findViewById(R.id.pb_speed_bar)
        pbPowerBar = findViewById(R.id.pb_power_bar)
        musicCard = findViewById(R.id.music_card)
        tvMusicTitle = findViewById(R.id.tv_music_title)
        tvMusicArtist = findViewById(R.id.tv_music_artist)
        tvMusicLyric = findViewById(R.id.tv_music_lyric)
        tvMusicTime = findViewById(R.id.tv_music_time)
        pbMusicProgress = findViewById(R.id.pb_music_progress)
        ivMusicCover = findViewById(R.id.iv_music_cover)
        
        dragHandleLeft = findViewById(R.id.drag_handle_left)
        dragHandleRight = findViewById(R.id.drag_handle_right)
        
        guidelineLeft = findViewById(R.id.guideline_left)
        guidelineRight = findViewById(R.id.guideline_right)
        surfaceView = findViewById(R.id.right_panel)
        divider = findViewById(R.id.divider)
        centerCard = findViewById(R.id.center_card)
        centerContainer = findViewById(R.id.center_container)
        rightSidePanel = findViewById(R.id.right_side_panel)
        
        centerCard.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 30f)
            }
        }
        centerCard.clipToOutline = true

        findViewById<ImageButton>(R.id.btn_stop_app_main).setOnClickListener { stopCurrentApp() }
        applyUiStyle()
        if (!useVirtualLayout) {
            applyGuidelinePreference(mainLayout)
            setupResizing(mainLayout)
        }
        setupTouchForwarding()
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)) showLogOverlay()
        
        initFloatingBall()
        startLogcatListener()
        initGlobalVirtualDisplay()
        startUiUpdateTimer()
        uiHandler.post(timeRunnable)
        if (!useVirtualLayout) adjustForScreenRatio(mainLayout)
        instance = this
        initMediaSessionListener()
        // 启动 MediaSession 兜底轮询，确保酷狗等播放源开始播放后能显示封面
        uiHandler.post(mediaPollRunnable)
    }

    private fun getPrimaryContext(): Context {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return createDisplayContext(dm.getDisplay(0))
    }

    private fun getPrimaryWindowManager(): WindowManager {
        return getPrimaryContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun applyUiStyle() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
        val showMusic = prefs.getBoolean(KEY_SHOW_MUSIC_CARD, false)
        val canDrag = prefs.getBoolean(KEY_ENABLE_DRAG, true)

        // 虚拟屏专属布局: 不使用左右翼/拖动, 音乐卡片始终显示在底栏右侧
        if (useVirtualLayout) {
            musicCard.visibility = View.VISIBLE
            rightSidePanel.visibility = View.GONE
            powerContainerLeft.visibility = View.GONE
            dragHandleLeft.visibility = View.GONE
            dragHandleRight.visibility = View.GONE
            return
        }

        // 核心：无论何种模式，只要开启开关就显示音乐卡片
        musicCard.visibility = if (showMusic) View.VISIBLE else View.GONE

        if (isDouble) {
            rightSidePanel.visibility = View.VISIBLE
            powerContainerLeft.visibility = View.GONE
            dragHandleRight.visibility = if(canDrag) View.VISIBLE else View.GONE
            if (!prefs.getBoolean(KEY_LOCK_RATIO, false)) guidelineRight.setGuidelinePercent(0.78f)
        } else {
            rightSidePanel.visibility = View.GONE
            powerContainerLeft.visibility = View.VISIBLE
            dragHandleRight.visibility = View.GONE
            if (!prefs.getBoolean(KEY_LOCK_RATIO, false)) guidelineRight.setGuidelinePercent(1.0f)
        }
        dragHandleLeft.visibility = if(canDrag) View.VISIBLE else View.GONE
        adjustForScreenRatio(findViewById(R.id.main))
    }

    private fun adjustForScreenRatio(main: View) {
        main.post {
            val w = main.width.toFloat(); val h = main.height.toFloat()
            if (w > 0 && h > 0) {
                val curL = (guidelineLeft.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                val curR = (guidelineRight.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                val centerWidth = w * (curR - curL)
                val targetRatio = 16f / 9f
                val panelHeight = h - 84 // 72dp top_bar + margins
                if ((centerWidth / panelHeight) < targetRatio) {
                    val p = ((panelHeight - (centerWidth / targetRatio)) / 2).toInt().coerceAtLeast(0)
                    centerContainer.setPadding(0, p, 0, p)
                } else { centerContainer.setPadding(0, 0, 0, 0) }
                updateTextSizes(w * curL)
            }
        }
    }

    private fun updateTextSizes(leftWidth: Float) {
        val scale = (leftWidth / 300f).coerceIn(0.6f, 1.8f)
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f * scale)
        tvPowerLeft.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f * scale)
        tvSoc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
    }

    private fun apply16x9Now() {
        val main = findViewById<ConstraintLayout>(R.id.main)
        main.post {
            val w = main.width.toFloat(); val h = main.height.toFloat()
            if (w > 0 && h > 0) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
                val targetCenterW = (h - 84) * (16f / 9f)

                if (isDouble) {
                    val sideW = (w - targetCenterW) / 2.0
                    val leftP = (sideW / w).coerceIn(0.1, 0.4).toFloat()
                    guidelineLeft.setGuidelinePercent(leftP)
                    guidelineRight.setGuidelinePercent(1.0f - leftP)
                } else {
                    val leftP = (1.0 - (targetCenterW / w)).coerceIn(0.1, 0.5).toFloat()
                    guidelineLeft.setGuidelinePercent(leftP)
                    guidelineRight.setGuidelinePercent(1.0f)
                }
                adjustForScreenRatio(main)
                updateVirtualDisplaySize()
            }
        }
    }

    private fun applyGuidelinePreference(mainLayout: ConstraintLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isLocked = prefs.getBoolean(KEY_LOCK_RATIO, false)
        if (isLocked) apply16x9Now() else { 
            val p = prefs.getFloat(KEY_GUIDE_PERCENT, 0.22f)
            guidelineLeft.setGuidelinePercent(p)
            guidelineRight.setGuidelinePercent(if(prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)) 1.0f - p else 1.0f) 
        } 
    }

    private fun resetLayoutToNormal() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
        val savedPercent = prefs.getFloat(KEY_GUIDE_PERCENT, 0.22f)
        guidelineLeft.setGuidelinePercent(savedPercent)
        guidelineRight.setGuidelinePercent(if (isDouble) 1.0f - savedPercent else 1.0f)
        centerContainer.setPadding(0, 0, 0, 0)
        updateVirtualDisplaySize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshThemeColors()
    }

    private fun refreshThemeColors() {
        findViewById<View>(R.id.left_panel).setBackgroundResource(R.drawable.wing_background)
        rightSidePanel.setBackgroundResource(R.drawable.wing_background)
        centerCard.setBackgroundResource(R.drawable.wing_background)
        tvSpeed.setTextColor(getColor(R.color.speed_text_color))
        tvPower.setTextColor(getColor(R.color.accent_green))
        tvPowerLeft.setTextColor(getColor(R.color.accent_green))
        tvSoc.setTextColor(getColor(R.color.speed_text_color))
        tvTime.setTextColor(getColor(R.color.label_text_color))
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingBall() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (floatingBall != null) return
        val wm = getPrimaryWindowManager()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        
        val params = WindowManager.LayoutParams().apply {
            width = 80; height = 80; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START; x = 5; y = 400
        }
        val ball = FrameLayout(getPrimaryContext()).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_floating_ball)
            elevation = 12f
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_speedometer)
                layoutParams = FrameLayout.LayoutParams(dpToPx(42), dpToPx(42)).apply { gravity = Gravity.CENTER }
            }
            addView(icon)
        }
        ball.setOnTouchListener(object : View.OnTouchListener {
            private var ix: Int = 0; private var iy: Int = 0; private var tx: Float = 0f; private var ty: Float = 0f; private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; moved = false; ball.animate().scaleX(0.88f).scaleY(0.88f).setDuration(120).start(); return true }
                    MotionEvent.ACTION_MOVE -> {
                        var nx = ix + (e.rawX - tx).toInt()
                        if (nx > 100 && nx < screenWidth - 180) nx = if (nx < screenWidth / 2) 5 else screenWidth - 85
                        params.x = nx; params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(ball, params)
                        if (abs(e.rawX - tx) > 10 || abs(e.rawY - ty) > 10) moved = true; return true 
                    }
                    MotionEvent.ACTION_UP -> { 
                        params.x = if (params.x < screenWidth / 2) 5 else screenWidth - 85
                        wm.updateViewLayout(ball, params)
                        ball.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        if (!moved) { 
                            v.performClick()
                            if (currentSettingsOverlay != null) closeSettingsMenu() else showOverlaySettings()
                        }
                        return true 
                    }
                }
                return false
            }
        })
        try { wm.addView(ball, params); floatingBall = ball } catch (ex: Exception) {}
    }

    private fun closeSettingsMenu() {
        currentSettingsOverlay?.let { try { getPrimaryWindowManager().removeView(it) } catch (e: Exception) {} }
        currentSettingsOverlay = null
    }

    private fun showOverlaySettings() {
        if (currentSettingsOverlay != null) { closeSettingsMenu(); return }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var selectedMode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        var isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
        var isVirtual = prefs.getBoolean(KEY_UI_STYLE_VIRTUAL, false)
        // 双翼和虚拟屏互斥: 选了虚拟屏就不再双翼
        if (isVirtual) isDouble = false
        val showMusic = prefs.getBoolean(KEY_SHOW_MUSIC_CARD, false)
        val wm = getPrimaryWindowManager()
        val ctx = getPrimaryContext()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        val ballParams = (floatingBall?.layoutParams as? WindowManager.LayoutParams) ?: return

        val rootOverlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setOnClickListener { closeSettingsMenu() }
        }

        val overlayParams = WindowManager.LayoutParams().apply {
            width = 560; height = 760; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            dimAmount = 0.5f; format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START
            x = if (ballParams.x < metrics.widthPixels / 2) ballParams.x + 95 else ballParams.x - 570
            y = (ballParams.y - 120).coerceAtLeast(50)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            elevation = 16f
            setOnClickListener { }
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            isVerticalScrollBarEnabled = false
            addView(container)
        }
        rootOverlay.addView(scroll, FrameLayout.LayoutParams(560, -2))

        // 标题
        container.addView(TextView(ctx).apply {
            text = getString(R.string.settings_title)
            textSize = 18f; setTextColor(getColor(R.color.color_on_surface))
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(18))
        })

        // 样式切换（双翼/单翼/虚拟屏）
        val rowStyle = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dpToPx(14)) }
        val bDouble = makeRoundButton(ctx, getString(R.string.style_double))
        val bSingle = makeRoundButton(ctx, getString(R.string.style_single))
        val bVirtual = makeRoundButton(ctx, getString(R.string.style_virtual))
        fun upSty() {
            bDouble.isSelected = isDouble && !isVirtual
            bSingle.isSelected = !isDouble && !isVirtual
            bVirtual.isSelected = isVirtual
            applyButtonStyle(bDouble)
            applyButtonStyle(bSingle)
            applyButtonStyle(bVirtual)
        }
        bDouble.setOnClickListener { isDouble = true; isVirtual = false; upSty() }
        bSingle.setOnClickListener { isDouble = false; isVirtual = false; upSty() }
        bVirtual.setOnClickListener { isVirtual = true; isDouble = false; upSty() }
        upSty(); rowStyle.addView(bDouble); rowStyle.addView(bSingle); rowStyle.addView(bVirtual)
        container.addView(rowStyle)

        // 显示模式分组标题
        container.addView(TextView(ctx).apply {
            text = getString(R.string.mode_section)
            textSize = 13f; setTextColor(getColor(R.color.color_on_surface_variant))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(6), 0, dpToPx(8))
        })

        // 显示模式（2 列网格，选中态用各自模式色）
        val modeList = listOf(
            Triple(MODE_DISPLAY_ONLY, getString(R.string.mode_display), R.color.mode_display),
            Triple(MODE_CARPAY, getString(R.string.mode_carplay), R.color.mode_carplay),
            Triple(MODE_EMBED_APP, getString(R.string.mode_embed), R.color.mode_embed),
            Triple(MODE_MIRROR, getString(R.string.mode_mirror), R.color.mode_mirror)
        )
        val modeBtns = mutableListOf<Button>()
        val btnPick = makeRoundButton(ctx, getString(R.string.pick_app)).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dpToPx(52)).apply { setMargins(0, dpToPx(4), 0, dpToPx(12)) }
            setOnClickListener { showAppPickerOnPrimary() }
            visibility = if (selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE
        }
        applyNeutralButton(btnPick)

        val modesGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (i in modeList.indices step 2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) if (i + j < modeList.size) {
                val (m, l, cRes) = modeList[i + j]
                val b = makeRoundButton(ctx, l).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(56), 1f).apply { setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)) }
                    isSelected = (selectedMode == m)
                    applyModeButtonStyle(this, cRes)
                    setOnClickListener {
                        selectedMode = m
                        btnPick.visibility = if (selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE
                        modeBtns.forEachIndexed { idx, btn ->
                            btn.isSelected = (modeList[idx].first == selectedMode)
                            applyModeButtonStyle(btn, modeList[idx].third)
                        }
                    }
                }.also { modeBtns.add(it) }
                row.addView(b)
            }
            modesGrid.addView(row)
        }
        container.addView(modesGrid); container.addView(btnPick)

        // 选项卡片分组
        val optCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button_neutral)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dpToPx(14), 0, 0) }
        }
        val cbLock = CheckBox(ctx).apply { text = getString(R.string.lock_ratio); textSize = 13f; setTextColor(getColor(R.color.color_on_surface)); isChecked = prefs.getBoolean(KEY_LOCK_RATIO, false) }
        val cbDrag = CheckBox(ctx).apply { text = getString(R.string.allow_drag); textSize = 13f; setTextColor(getColor(R.color.color_on_surface)); isChecked = prefs.getBoolean(KEY_ENABLE_DRAG, true) }
        val rS = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpToPx(4), 0, 0) }; rS.addView(cbLock); rS.addView(cbDrag)
        optCard.addView(rS)
        val cbMusic = CheckBox(ctx).apply { text = getString(R.string.music_card); textSize = 13f; setTextColor(getColor(R.color.color_on_surface)); isChecked = showMusic }
        optCard.addView(cbMusic)
        container.addView(optCard)

        // 应用按钮
        val btnApply = Button(ctx).apply {
            text = getString(R.string.apply_settings); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.color_on_primary))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button_primary)
            layoutParams = LinearLayout.LayoutParams(-1, dpToPx(56)).apply { setMargins(0, dpToPx(20), 0, 0) }
            setOnClickListener {
                prefs.edit().putInt(KEY_MODE, selectedMode).putBoolean(KEY_UI_STYLE_DOUBLE, isDouble).putBoolean(KEY_UI_STYLE_VIRTUAL, isVirtual).putBoolean(KEY_LOCK_RATIO, cbLock.isChecked).putBoolean(KEY_ENABLE_DRAG, cbDrag.isChecked).putBoolean(KEY_SHOW_MUSIC_CARD, cbMusic.isChecked).apply()
                // 虚拟屏模式需要切换布局文件, 重建 Activity
                if (isVirtual != useVirtualLayout) {
                    closeSettingsMenu()
                    recreate()
                    return@setOnClickListener
                }
                applyUiStyle(); if (!useVirtualLayout) { if (cbLock.isChecked) apply16x9Now() else resetLayoutToNormal() }
                virtualDisplay?.release(); virtualDisplay = null; createVirtualDisplay(surfaceView.holder)
                if (selectedMode == MODE_CARPAY) launchCarplayOnMain()
                closeSettingsMenu()
            }
        }
        container.addView(btnApply)

        try {
            wm.addView(rootOverlay, overlayParams); currentSettingsOverlay = rootOverlay
            // 弹出缩放动画
            container.scaleX = 0.85f; container.scaleY = 0.85f; container.alpha = 0f
            container.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(OvershootInterpolator()).start()
        } catch (ex: Exception) {}
    }

    private fun makeRoundButton(ctx: Context, text: String): Button {
        return Button(ctx, null, 0, androidx.appcompat.R.style.Widget_AppCompat_Button).apply {
            this.text = text
            this.isAllCaps = false
            this.textSize = 13f
            this.gravity = Gravity.CENTER
            this.minimumHeight = 0
            this.minHeight = 0
            this.includeFontPadding = false
        }
    }

    private fun applyButtonStyle(btn: Button) {
        val c = btn.context
        if (btn.isSelected) {
            btn.background = ContextCompat.getDrawable(c, R.drawable.bg_button_primary)
            btn.setTextColor(ContextCompat.getColor(c, R.color.color_on_primary))
        } else {
            btn.background = ContextCompat.getDrawable(c, R.drawable.bg_button_neutral)
            btn.setTextColor(ContextCompat.getColor(c, R.color.color_on_surface_variant))
        }
    }

    private fun applyModeButtonStyle(btn: Button, colorRes: Int) {
        val c = btn.context
        if (btn.isSelected) {
            val color = ContextCompat.getColor(c, colorRes)
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(color)
            }
            btn.setTextColor(Color.WHITE)
        } else {
            btn.background = ContextCompat.getDrawable(c, R.drawable.bg_button_neutral)
            btn.setTextColor(ContextCompat.getColor(c, R.color.color_on_surface_variant))
        }
    }

    private fun applyNeutralButton(btn: Button) {
        val c = btn.context
        btn.background = ContextCompat.getDrawable(c, R.drawable.bg_button_neutral)
        btn.setTextColor(ContextCompat.getColor(c, R.color.color_on_surface_variant))
    }

    private fun showAppPickerOnPrimary() {
        val themed = ContextThemeWrapper(getPrimaryContext(), androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val adapter = object : ArrayAdapter<ResolveInfo>(themed, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val info = getItem(position)
                v.findViewById<TextView>(android.R.id.text1).apply { text = info?.loadLabel(pm); setTextColor(Color.BLACK); textSize = 14f }
                v.findViewById<TextView>(android.R.id.text2).apply { text = info?.activityInfo?.packageName; setTextColor(Color.GRAY); textSize = 11f }
                return v
            }
        }
        val dialog = AlertDialog.Builder(themed).setTitle(getString(R.string.mode_embed)).setAdapter(adapter) { _, i -> 
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(KEY_LAST_APP, list[i].activityInfo.packageName) }
            launchAppInVirtualDisplay(list[i].activityInfo.packageName) 
        }.setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); dialog.show()
    }

    private fun initGlobalVirtualDisplay() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (virtualDisplay == null) createVirtualDisplay(h)
                else { 
                    try { 
                        val m = VirtualDisplay::class.java.getMethod("setSurface", android.view.Surface::class.java)
                        m.invoke(virtualDisplay, h.surface)
                        if (currentRunningPackage == null) checkAndRunActiveMode() 
                    } catch (e: Exception) { 
                        virtualDisplay?.release()
                        createVirtualDisplay(h) 
                    } 
                } 
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) { updateVirtualDisplaySize() }
            override fun surfaceDestroyed(h: SurfaceHolder) {} 
        })
    }

    private fun checkAndRunActiveMode() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        hintPresentation?.dismiss()
        when (mode) { 
            MODE_CARPAY -> currentRunningPackage = "com.autochips.carplayapp"
            MODE_EMBED_APP -> { 
                val lastPkg = prefs.getString(KEY_LAST_APP, null)
                if (lastPkg != null) launchAppInVirtualDisplay(lastPkg) else showHintDelayed() 
            }
            MODE_MIRROR -> currentRunningPackage = "MIRROR_MODE"
            else -> { currentRunningPackage = null; showHintDelayed() } 
        }
    }

    private fun showHintDelayed() { 
        Handler(Looper.getMainLooper()).postDelayed({ 
            if (isFinishing || currentRunningPackage != null) return@postDelayed
            val vd = virtualDisplay?.display
            if (vd != null) { 
                if (hintPresentation != null) hintPresentation?.dismiss()
                hintPresentation = HintPresentation(this, vd)
                try { hintPresentation?.show() } catch (ex: Exception) {} 
            } 
        }, 1000) 
    }

    private fun showLogOverlay() {
        if (floatingLogView != null) return
        val wm = getPrimaryWindowManager()
        val params = WindowManager.LayoutParams().apply { 
            width = 800; height = 400; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 
        }
        val root = LinearLayout(getPrimaryContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            elevation = 12f
        }
        val header = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(root.context, R.drawable.bg_button_primary)
            setPadding(20, 10, 20, 10)
            setOnTouchListener { v, e ->
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { v.performClick(); true }
                    MotionEvent.ACTION_MOVE -> { params.x = (e.rawX - 400).toInt(); params.y = (e.rawY - 20).toInt(); wm.updateViewLayout(root, params); true }
                    else -> false
                }
            }
        }
        header.addView(TextView(root.context).apply { text = "[M]=Main [S]=System"; setTextColor(ContextCompat.getColor(root.context, R.color.color_on_primary)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) } )
        header.addView(TextView(root.context).apply { text = " ✕ "; setTextColor(ContextCompat.getColor(root.context, R.color.color_on_primary)); setTextSize(16f); setOnClickListener { hideLogOverlay() } } )
        root.addView(header)
        val tv = TextView(root.context).apply { setTextColor(Color.WHITE); textSize = 11f; setPadding(10, 5, 10, 5) }
        root.addView(tv)
        try { wm.addView(root, params); floatingLogView = root; tvFloatingLog = tv } catch (ex: Exception) {}
    }

    private fun hideLogOverlay() { 
        floatingLogView?.let { try { getPrimaryWindowManager().removeView(it) } catch (e: Exception) {} }
        floatingLogView = null; tvFloatingLog = null 
    }

    private fun launchCarplayOnMain() { 
        val intent = packageManager.getLaunchIntentForPackage("com.autochips.carplayapp")
        if (intent != null) { 
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opt = ActivityOptions.makeBasic()
            opt.launchDisplayId = 0
            try { startActivity(intent, opt.toBundle()) } catch (e: Exception) { startActivity(intent) } 
        } 
    }

    private fun launchAppInVirtualDisplay(pkg: String) { 
        val displayId = virtualDisplay?.display?.displayId ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        hintPresentation?.dismiss()
        currentRunningPackage = pkg
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val opt = ActivityOptions.makeBasic()
        opt.launchDisplayId = displayId
        try { startActivity(intent, opt.toBundle()) } catch (e: Exception) { showHintDelayed() } 
    }

    private fun stopCurrentApp() { 
        try { 
            if (currentRunningPackage != null && currentRunningPackage != "MIRROR_MODE") { 
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, currentRunningPackage) 
            }
            currentRunningPackage = null; showHintDelayed() 
        } catch (ex: Exception) { showHintDelayed() } 
    }

    /**
     * 检测主物理屏(Display 0)分辨率是否为 1920×1080。
     * 虚拟屏专属布局仅在 1920×1080 主屏上有效,其它分辨率回退到标准布局。
     */
    private fun isPrimaryScreen1080p(): Boolean {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(0) ?: return false
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") display.getMetrics(metrics)
            // 宽 1920 / 高 1080 (允许容差以应对 density 计算误差)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val match = (w == 1920 && h == 1080) || (w == 1080 && h == 1920)
            Log.i("Miao", "Primary screen: ${w}x${h}, is1080p=$match")
            match
        } catch (e: Exception) {
            Log.w("Miao", "isPrimaryScreen1080p failed: ${e.message}")
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    /**
     * 解除本进程对 non-SDK (hidden) 接口的访问限制。
     *
     * 车机多为 Android 9 (API 28)，MotionEvent.setDisplayId / InputManager.injectInputEvent
     * 均为 @hide 方法，默认被 non-SDK 策略拦截，导致反射 getMethod 抛 NoSuchMethodException、
     * 触摸事件无法定向到虚拟屏。通过 VMRuntime.setHiddenApiExemptions("") 将本进程全部豁免。
     *
     * 该豁免方法在 API 28 上属于 dark greylist，可经反射直接调用；
     * API 29+ 上若被限，则回退到 try 多次的不同签名，失败也不影响其它功能。
     */
    private fun bypassHiddenApi() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val vmRuntime = getRuntime.invoke(null)
            // 优先用数组形参版本（API 28+）
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java
            )
            setExemptions.isAccessible = true
            setExemptions.invoke(vmRuntime, arrayOf("L"))
            Log.i("Miao", "Miao v1.9.11 starting | Hidden API exemption applied (VMRuntime)")
        } catch (e: Exception) {
            Log.w("Miao", "bypassHiddenApi failed: ${e.message}")
        }
    }

    private fun setupTouchForwarding() {
        // 缓存反射结果，避免每次触摸都反射；同时采集一次诊断信息
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        var injectMethod: java.lang.reflect.Method? = null
        var setDisplayIdM: java.lang.reflect.Method? = null
        var setDeviceIdM: java.lang.reflect.Method? = null
        // 分开 try：Android 9 上 setDisplayId/setDeviceId 不存在，不能连累其它方法解析
        try { injectMethod = InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType) } catch (e: Exception) { Log.e("Miao", "reflect injectInputEvent failed: ${e.message}") }
        try { setDisplayIdM = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (e: Exception) { Log.e("Miao", "reflect setDisplayId failed (API<29 无此方法，属正常): ${e.message}") }
        try { setDeviceIdM = MotionEvent::class.java.getMethod("setDeviceId", Int::class.javaPrimitiveType) } catch (e: Exception) { Log.e("Miao", "reflect setDeviceId failed (API<29 无此方法，属正常): ${e.message}") }

        // 查找真实触摸屏 deviceId（用于诊断）
        var touchDeviceId = -1
        try {
            for (id in inputManager.inputDeviceIds) {
                val dev = inputManager.getInputDevice(id)
                if (dev != null && (dev.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0) { touchDeviceId = id; break }
            }
        } catch (e: Exception) {}

        var diagLogged = false
        // P (API 28) 系统级输入转发器: IInputForwarder binder 对象
        // 由 InputManager.createInputForwarder(displayId) 创建，服务端原生按 displayId 注入,
        // 绕开 API 28 Java 层完全没有 setDisplayId/mDisplayId 的平台缺失
        var inputForwarder: Any? = null
        var fwdMethod: java.lang.reflect.Method? = null
        var fwdDisplayId = -1
        surfaceView.setOnTouchListener { v, event ->
            val vd = virtualDisplay ?: return@setOnTouchListener false
            val display = vd.display ?: return@setOnTouchListener false
            val displayId = display.displayId

            if (!diagLogged) {
                diagLogged = true
                Log.i("Miao", "Touch setup: targetDisplayId=$displayId " +
                        "surfaceView=${v.width}x${v.height} display=${display.width}x${display.height} " +
                        "touchDeviceId=$touchDeviceId injectMethodAvail=${injectMethod != null}")
            }

            // 使用 SurfaceView 的实际像素尺寸做坐标映射，而不是 display.width
            // display.width 可能返回虚拟屏的逻辑宽度，与渲染分辨率不一致
            val targetW = if (surfaceView.width > 0) surfaceView.width else display.width
            val targetH = if (surfaceView.height > 0) surfaceView.height else display.height
            val viewW = v.width.toFloat().coerceAtLeast(1f)
            val viewH = v.height.toFloat().coerceAtLeast(1f)
            val scaleX = targetW.toFloat() / viewW
            val scaleY = targetH.toFloat() / viewH
            val mappedX = event.x * scaleX
            val mappedY = event.y * scaleY

            // 复制事件
            val te = MotionEvent.obtain(event)
            te.setLocation(mappedX, mappedY)
            try {
                if (event.action == MotionEvent.ACTION_DOWN) v.performClick()

                // 惰性创建 IInputForwarder（每个 displayId 一次，虚拟屏重建后自动换新）
                if (fwdDisplayId != displayId) {
                    fwdDisplayId = displayId
                    inputForwarder = null
                    fwdMethod = null
                    try {
                        val imClass = InputManager::class.java
                        val imInstance = imClass.getMethod("getInstance").invoke(null)
                        Log.i("Miao", "createInputForwarder: invoking on display $displayId")
                        inputForwarder = imClass
                            .getMethod("createInputForwarder", Int::class.javaPrimitiveType)
                            .invoke(imInstance, displayId)
                        fwdMethod = Class.forName("android.app.IInputForwarder")
                            .getMethod("forwardEvent", android.view.InputEvent::class.java)
                        Log.i("Miao", "IInputForwarder ready for display $displayId (fwd=$inputForwarder)")
                    } catch (e: Exception) {
                        Log.e("Miao", "createInputForwarder failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }

                // 设置 source 为触摸屏
                te.source = InputDevice.SOURCE_TOUCHSCREEN
                if (touchDeviceId != -1) setDeviceIdM?.invoke(te, touchDeviceId)

                var handled = false
                when {
                    // 主路径 (P/28): 系统级 forwarder，服务端原生按 displayId 注入
                    inputForwarder != null && fwdMethod != null -> {
                        handled = try {
                            fwdMethod!!.invoke(inputForwarder, te) as Boolean
                        } catch (e: Exception) {
                            Log.e("Miao", "forwardEvent exception: ${e.message}")
                            inputForwarder = null
                            false
                        }
                        if (!handled && event.action == MotionEvent.ACTION_DOWN)
                            Log.w("Miao", "forwardEvent returned false (display=$displayId)")
                    }
                    // 回退路径 (29+): setDisplayId 存在时才直接注入
                    setDisplayIdM != null && injectMethod != null -> {
                        setDisplayIdM!!.invoke(te, displayId)
                        handled = injectMethod!!.invoke(inputManager, te, 2) as Boolean
                        if (!handled) handled = injectMethod!!.invoke(inputManager, te, 0) as Boolean
                        if (!handled && event.action == MotionEvent.ACTION_DOWN)
                            Log.w("Miao", "Touch inject failed both modes: displayId=$displayId")
                    }
                    else -> {
                        // P 及以下若 forwarder 拿不到：绝不能无目标注入主屏（会在原车桌面产生幽灵触摸）
                        if (event.action == MotionEvent.ACTION_DOWN)
                            Log.e("Miao", "No display-targeted touch path available")
                    }
                }
            } catch (ex: Exception) {
                Log.e("Miao", "Touch inject exception: ${ex.message}")
            } finally {
                te.recycle()
            }
            true
        }
    }

    private fun startLogcatListener() { 
        if (isListening.getAndSet(true)) return
        val filter = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LOG_FILTER_KEYWORD, "") ?: ""
        thread(start = true, isDaemon = true) { runLogcatTask("-b main", "[M]", filter) }
        thread(start = true, isDaemon = true) { runLogcatTask("-b system", "[S]", filter) } 
    }

    private fun runLogcatTask(bufferArgs: String, prefix: String, filter: String) {
        while (isListening.get()) {
            var process: Process? = null
            try {
                // 每次循环重新读取 isOverlay，确保设置变更后生效
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val isOverlay = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)
                val cmd = when {
                    !isOverlay -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V $MUSIC_PLAY_TAG:V $MUSIC_KUGOU_TAG:V $MUSIC_KUGOU_IMPL_TAG:V $MUSIC_WIDGET_TAG:V $MUSIC_A2DP_TAG:V *:S"
                    filter.isEmpty() -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V $MUSIC_PLAY_TAG:V $MUSIC_KUGOU_TAG:V $MUSIC_KUGOU_IMPL_TAG:V $MUSIC_WIDGET_TAG:V $MUSIC_A2DP_TAG:V *:V"
                    else -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V $MUSIC_PLAY_TAG:V $MUSIC_KUGOU_TAG:V $MUSIC_KUGOU_IMPL_TAG:V $MUSIC_WIDGET_TAG:V $MUSIC_A2DP_TAG:V $filter:V *:S"
                }
                process = Runtime.getRuntime().exec(cmd)
                if (bufferArgs.contains("main")) processMain = process else processSystem = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = null
                while (isListening.get() && reader.readLine().also { line = it } != null) { 
                    line?.let { logLine ->
                        if (isOverlay) { synchronized(logBuffer) { logBuffer.add("$prefix $logLine"); if (logBuffer.size > 20) logBuffer.removeAt(0) } }
                        if (logLine.contains("speed Value")) { 
                            val speed = parseSpeed(logLine)
                            if (speed != null) uiHandler.post { 
                                tvSpeed.text = speed
                                val sVal = speed.toDoubleOrNull() ?: 0.0
                                pbSpeedBar.progress = sVal.toInt().coerceIn(0, 180)
                            } 
                        }
                        if (logLine.contains("0x2140f636") || logLine.contains("电量=")) { 
                            extractValue(logLine)?.let { soc -> uiHandler.post { tvSoc.text = String.format(Locale.US, "%.0f %%", soc); pbBattery.progress = soc.toInt() } } 
                        }
                        if (logLine.contains("prop=0x2160f502")) { 
                            val c = extractValue(logLine) ?: 0.0
                            lastCurrent = c; updatePowerDisplay() 
                        }
                        else if (logLine.contains("prop=0x2160f503")) { 
                            val v = extractValue(logLine)
                            if (v != null) { lastVoltage = v; updatePowerDisplay() } 
                        }
                        
                        // 综合音乐解析 (支持歌名、歌手、封面 URL、专辑/歌词)
                        if (logLine.contains("onTrackChanged TrackInfo") || logLine.contains("prev MM title") || logLine.contains("getMediaTitle: result:") || logLine.contains("isActiveTimeOut")) {
                            lastLogcatMusicAt = SystemClock.elapsedRealtime()
                            val title = Regex("(?:mTrackTitle|title|result|song):? ?=?(?:result:)? ?[\"']?([^,\n\\]\"']+)[\"']?").find(logLine)?.groupValues?.get(1)?.trim()
                            val artist = Regex("(?:mArtistName|artist|singer)=[\"']?([^,\n\\]\"']+)[\"']?").find(logLine)?.groupValues?.get(1)?.trim()
                            val album = Regex("(?:mAlbumTitle|album)=[\"']?([^,\n\\]\"']+)[\"']?").find(logLine)?.groupValues?.get(1)?.trim()
                            val cover = Regex("(?:albumImg|img|singerImg)=[\"']?([^,\n\\]\"']+)[\"']?").find(logLine)?.groupValues?.get(1)?.trim()
                            val len = Regex("(?:mTrackLen|track len|duration)[ =]([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            
                            uiHandler.post { 
                                if (title != null && title != "null" && title.isNotEmpty()) tvMusicTitle.text = title
                                if (artist != null && artist != "null" && artist.isNotEmpty()) tvMusicArtist.text = artist
                                if (album != null && album != "null" && album.isNotEmpty()) tvMusicLyric.text = album
                                if (len > 0) musicDuration = len
                                if (cover != null && cover.startsWith("http") && cover != lastCoverUrl) {
                                    lastCoverUrl = cover
                                    loadCoverFromUrl(cover)
                                }
                                updateMusicTime(0)
                            }
                        }
                        // 酷狗车机版自有轮询行(与桌面无关, miao 全屏时依然有数据):
                        // KuGouPlayImpl: tvPlayWidgetTitle getCurPlaySong: KGMusic{songName='..', singerName='..', albumImgMedium='http://..', duration=..}
                        if (logLine.contains("getCurPlaySong")) {
                            lastLogcatMusicAt = SystemClock.elapsedRealtime()
                            val kTitle = Regex("songName='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                            val kArtist = Regex("singerName='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                            val kAlbum = Regex("albumName='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                            val kCover = Regex("albumImgMedium='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                                ?: Regex("albumImgSmall='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                                ?: Regex("albumImg='([^']*)'").find(logLine)?.groupValues?.get(1)?.trim()
                            val kLen = Regex("duration=([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            uiHandler.post {
                                if (kTitle != null && kTitle != "null" && kTitle.isNotEmpty()) tvMusicTitle.text = kTitle
                                if (kArtist != null && kArtist != "null" && kArtist.isNotEmpty()) tvMusicArtist.text = kArtist
                                if (kAlbum != null && kAlbum != "null" && kAlbum.isNotEmpty()) tvMusicLyric.text = kAlbum
                                if (kLen > 0) musicDuration = kLen
                                if (kCover != null && kCover.startsWith("http") && kCover != lastCoverUrl) {
                                    lastCoverUrl = kCover
                                    loadCoverFromUrl(kCover)
                                }
                            }
                        }
                        if (logLine.contains("getPlayBackState state") || logLine.contains("position=")) {
                            val pos = Regex("(?:time|position=)([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            uiHandler.post { updateMusicTime(pos) }
                        }
                        // KuGouPlayImpl 进度行: "duration : 240532 , position : 150320"
                        if (logLine.contains("duration : ") && logLine.contains("position : ")) {
                            val pos = Regex("position : ([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            val dur = Regex("duration : ([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            uiHandler.post { if (dur > 0) musicDuration = dur; updateMusicTime(pos) }
                        }
                    }
                }
            } catch (ex: Exception) { SystemClock.sleep(5000) } finally { process?.destroy() }
        }
    }

    private fun loadCoverFromUrl(urlStr: String) {
        thread {
            try {
                val url = URL(urlStr)
                val bitmap = BitmapFactory.decodeStream(url.openStream())
                uiHandler.post { if (bitmap != null) ivMusicCover.setImageBitmap(bitmap) }
            } catch (ex: Exception) {
                Log.e("Miao", "Cover load failed: ${ex.message}")
            }
        }
    }

    private fun updateMusicTime(currentMs: Long) {
        if (musicDuration <= 0) return
        val progress = (currentMs * 100 / musicDuration).toInt().coerceIn(0, 100)
        pbMusicProgress.progress = progress
        val curStr = String.format(Locale.US, "%02d:%02d", currentMs / 60000, (currentMs % 60000) / 1000)
        val durStr = String.format(Locale.US, "%02d:%02d", musicDuration / 60000, (musicDuration % 60000) / 1000)
        tvMusicTime.text = "$curStr / $durStr"
    }

    private fun extractValue(line: String): Double? { return try { val pattern = "(?:data|value|电量|values)[\\s=]*([0-9.-]+)"; Regex(pattern).find(line)?.groupValues?.get(1)?.toDouble() } catch (ex: Exception) { null } }
    
    private fun updatePowerDisplay() { 
        val p = (lastVoltage * lastCurrent) / 1000.0
        uiHandler.post { 
            tvPower.text = String.format(Locale.US, "%.1f", p)
            tvPowerLeft.text = String.format(Locale.US, "%.1f", p)
            pbPowerBar.progress = (p + 50).toInt().coerceIn(0, 200) 
        } 
    }
    
    private fun parseSpeed(logLine: String): String? { return try { val match = Regex("speed Value\\(\\)\\s*=\\s*([0-9.]+)").find(logLine); match?.groupValues?.get(1)?.toDouble()?.let { String.format(Locale.US, "%.1f", it) } } catch (ex: Exception) { null } }
    
    private fun initMediaSessionListener() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            updateActiveMediaSession()
        } catch (ex: Exception) {
            Log.e("Miao", "MediaSession init failed: ${ex.message}")
        }
    }

    internal fun updateActiveMediaSession() {
        try {
            // 酷狗走 logcat 私有通路(KuGouMusicInterfaceService)，不发布 MediaSession。
            // 若 logcat 通路最近 10s 内有音乐数据，且无正在播放的标准会话，
            // 则 MediaSession 通路不要介入，避免把酷狗信息覆盖成别的 app(如网易云)导致歌名乱跳。
            val kugouAlive = SystemClock.elapsedRealtime() - lastLogcatMusicAt < 10_000

            // 首选: getActiveSessions(null) —— 平台签名系统应用持有 MEDIA_CONTENT_CONTROL
            // 签名权限即可直取全部媒体会话，无需用户手动开启通知使用权(车机上往往无处授权)
            val controllers = try {
                mediaSessionManager?.getActiveSessions(null)
            } catch (e: Exception) {
                // 回退: 通知监听组件路径(需用户在设置里授权)
                val expected = ComponentName(this, NotificationListener::class.java)
                try { mediaSessionManager?.getActiveSessions(expected) } catch (e2: Exception) { null }
            }
            if (!controllers.isNullOrEmpty()) {
                // 只认"正在播放"的会话；暂停的(如后台网易云残留会话)不能接管显示
                val newCtl = controllers.firstOrNull {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                }
                if (newCtl == null) {
                    // 无播放会话：若 logcat 酷狗通路活跃则什么都不做；
                    // 若也不活跃，解除绑定即可(保留已显示内容，避免闪烁)
                    if (!kugouAlive) {
                        activeMediaController?.unregisterCallback(mediaCallback)
                        activeMediaController = null
                    }
                    return
                }
                // 用 sessionToken 判等(每次 getActiveSessions 都返回新对象，引用比较必为 false
                // ——这是此前每 3s 重复绑定+强制刷新导致歌名乱跳的根因)
                if (newCtl.sessionToken != activeMediaController?.sessionToken) {
                    activeMediaController?.unregisterCallback(mediaCallback)
                    activeMediaController = newCtl
                    activeMediaController?.registerCallback(mediaCallback)
                    Log.i("Miao", "Active MediaSession bound: ${newCtl.packageName}")
                    mediaCallback.onMetadataChanged(activeMediaController?.metadata)
                }
                // 会话未变时不强制刷新 metadata(回调会自动触发)
            } else {
                Log.w("Miao", "No active MediaSession found")
            }
        } catch (ex: Exception) {
            Log.e("Miao", "updateActiveMediaSession failed: ${ex.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizing(mainLayout: ConstraintLayout) {
        val onTouch = View.OnTouchListener { v, event ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLE_DRAG, true)) return@OnTouchListener false
            val screenW = mainLayout.width.toFloat()
            val screenH = mainLayout.height.toFloat()
            if (screenW <= 0) return@OnTouchListener false

            if (event.action == MotionEvent.ACTION_MOVE) {
                val rawXPercent = event.rawX / screenW
                val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
                if (v.id == R.id.drag_handle_left) {
                    if (isDouble) {
                        // 双翼：左右翼对称，右栏宽度 = 左栏宽度，始终可见
                        // 注意：锁定 16:9 时实时拖动保持对称，精确比例由"应用16:9"按钮处理，
                        // 避免在拖动时无脑加上 targetCenterW/screenW 导致右栏被挤出屏幕而消失。
                        val leftP = rawXPercent.coerceIn(0.1f, 0.45f)
                        guidelineLeft.setGuidelinePercent(leftP)
                        guidelineRight.setGuidelinePercent(1.0f - leftP)
                    } else {
                        // 单翼：左栏可加宽，右栏已隐藏
                        val leftP = rawXPercent.coerceIn(0.1f, 0.8f)
                        guidelineLeft.setGuidelinePercent(leftP)
                        guidelineRight.setGuidelinePercent(1.0f)
                    }
                } else if (v.id == R.id.drag_handle_right && isDouble) {
                    // 仅双翼模式有右 handle：左右对称
                    val rightP = rawXPercent.coerceIn(0.55f, 0.9f)
                    guidelineRight.setGuidelinePercent(rightP)
                    guidelineLeft.setGuidelinePercent(1.0f - rightP)
                }
                // 拖动中绝不 resize 虚拟屏/写盘：
                // VirtualDisplay.resize() 会导致内嵌 app 反复重排+SurfaceFlinger 拒收 buffer，
                // 高频触发(每次 MOVE)直接把内嵌 app 卡死并踢出 task(车机日志已证实)。
                // 拖动中只更新布局，松手后再统一处理一次。
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // 持久化 + 一次性收尾：延迟 100ms 等布局 pass 完成后按最终尺寸 resize 虚拟屏
                prefs.edit().putFloat(KEY_GUIDE_PERCENT, (guidelineLeft.layoutParams as ConstraintLayout.LayoutParams).guidePercent).apply()
                mainLayout.postDelayed({
                    adjustForScreenRatio(mainLayout)
                    updateVirtualDisplaySize()
                }, 100)
            } else if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
            true
        }
        dragHandleLeft.setOnTouchListener(onTouch)
        dragHandleRight.setOnTouchListener(onTouch)
    }

    private fun startUiUpdateTimer() {
        uiHandler.postDelayed(uiUpdateRunnable, 500)
    }
    
    private fun updateVirtualDisplaySize() {
        // 虚拟屏专属布局: 固定 1920×720 分辨率, 不随 surfaceView 尺寸变化
        if (useVirtualLayout) return
        val w = surfaceView.width
        val h = surfaceView.height
        if (w > 0 && h > 0) virtualDisplay?.resize(w, h, 160) 
    }
    
    private fun createVirtualDisplay(h: SurfaceHolder) {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // 虚拟屏专属布局: 固定 1920×720 分辨率, 投放到 1920×720 物理副屏
        val w: Int
        val hi: Int
        if (useVirtualLayout) {
            w = 1920
            hi = 720
        } else {
            w = if (surfaceView.width > 0) surfaceView.width else 1280
            hi = if (surfaceView.height > 0) surfaceView.height else 720
        }
        // VIRTUAL_DISPLAY_FLAG_TRUSTED (1 << 10) — 允许向虚拟屏注入触摸事件
        // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY 已弃用，PUBLIC + TRUSTED + PRESENTATION 组合可让 App 接收输入
        @SuppressLint("WrongConstant") val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                (1 shl 10) // VIRTUAL_DISPLAY_FLAG_TRUSTED
        virtualDisplay = dm.createVirtualDisplay("HDMI 屏幕", w, hi, 160, h.surface, flags)
        // 尝试将虚拟屏内容投放到物理副屏(如果存在 1920×720 副屏)
        if (useVirtualLayout) {
            mirrorToSecondaryDisplay()
        }
        checkAndRunActiveMode()
    }

    /**
     * 将虚拟屏内容投放到物理副屏(如果存在)。
     * 车机双屏场景: 主屏 1920×1080 运行 miao, 副屏 1920×720 显示虚拟屏内容。
     * 通过 Presentation 在副屏上显示一个全屏 SurfaceView, 其 Surface
     * 同时作为虚拟屏的渲染目标, 从而实现"投放"。
     */
    private var secondaryPresentation: Presentation? = null
    private fun mirrorToSecondaryDisplay() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // 查找副物理屏(非 Display 0)
            val secondary: Display? = dm.displays.firstOrNull { it.displayId != 0 }
            if (secondary == null) {
                Log.i("Miao", "No secondary display found, virtual screen stays on primary only")
                return
            }
            Log.i("Miao", "Secondary display found: id=${secondary.displayId} ${secondary.width}x${secondary.height}")
            // 在副屏上创建 Presentation, 内含一个全屏 SurfaceView,
            // 将该 Surface 设置为虚拟屏的渲染目标
            secondaryPresentation?.dismiss()
            val pres = object : Presentation(this, secondary) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val sv = SurfaceView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                try {
                                    val m = VirtualDisplay::class.java
                                        .getMethod("setSurface", android.view.Surface::class.java)
                                    m.invoke(virtualDisplay, holder.surface)
                                    Log.i("Miao", "Virtual display surface mirrored to secondary")
                                } catch (e: Exception) {
                                    Log.e("Miao", "Mirror surface to secondary failed: ${e.message}")
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {}
                        })
                    }
                    val root = FrameLayout(context).apply {
                        setBackgroundColor(Color.BLACK)
                        addView(sv)
                    }
                    setContentView(root)
                }
            }
            pres.show()
            secondaryPresentation = pres
        } catch (e: Exception) {
            Log.e("Miao", "mirrorToSecondaryDisplay failed: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 停止 logcat 监听
        isListening.set(false)
        processMain?.destroy()
        processSystem?.destroy()

        // 移除所有 Handler 回调，防止内存泄漏
        uiHandler.removeCallbacks(timeRunnable)
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.removeCallbacks(mediaPollRunnable)
        instance = null

        // 注销 MediaSession 回调
        try { activeMediaController?.unregisterCallback(mediaCallback) } catch (e: Exception) {}
        activeMediaController = null

        // 释放 VirtualDisplay
        try { virtualDisplay?.release() } catch (e: Exception) {}
        virtualDisplay = null

        // 销毁副屏 Presentation
        try { secondaryPresentation?.dismiss() } catch (e: Exception) {}
        secondaryPresentation = null

        // 销毁 HintPresentation
        try { hintPresentation?.dismiss() } catch (e: Exception) {}
        hintPresentation = null

        // 移除所有悬浮窗
        closeSettingsMenu()
        hideLogOverlay()
        floatingBall?.let { ball ->
            try { getPrimaryWindowManager().removeView(ball) } catch (e: Exception) {}
        }
        floatingBall = null
    }

    class NotificationListener : android.service.notification.NotificationListenerService(),
        MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onListenerConnected() {
            // 通知监听服务连上后立刻尝试发现当前播放源
            instance?.updateActiveMediaSession()
            registerSessionListener()
        }
        override fun onListenerDisconnected() {
            unregisterSessionListener()
        }
        private fun registerSessionListener() {
            try {
                val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                mgr.addOnActiveSessionsChangedListener(this, ComponentName(this, NotificationListener::class.java))
                Log.i("Miao", "MediaSession listener registered")
            } catch (e: Exception) {
                Log.e("Miao", "registerSessionListener failed: ${e.message}")
            }
        }
        private fun unregisterSessionListener() {
            try {
                val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                mgr.removeOnActiveSessionsChangedListener(this)
            } catch (e: Exception) {}
        }
        override fun onActiveSessionsChanged(activeSessions: MutableList<MediaController>?) {
            // 系统媒体会话列表变化（如酷狗开始/切换播放）时重新发现封面
            instance?.updateActiveMediaSession()
        }
    }
    class HintPresentation(c: Context, private val targetDisplay: Display) : Presentation(c, targetDisplay) {
        private fun dpToPx(dp: Int): Int =
            (dp * context.resources.displayMetrics.density).toInt()
        override fun onCreate(b: Bundle?) {
            super.onCreate(b)
            val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpToPx(48), dpToPx(40), dpToPx(48), dpToPx(40))
                background = ContextCompat.getDrawable(context, R.drawable.bg_card)
                elevation = 20f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            }
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_speedometer)
                setColorFilter(ContextCompat.getColor(context, R.color.color_primary))
                layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64)).apply {
                    bottomMargin = dpToPx(16); gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val tv = TextView(context).apply {
                text = context.getString(R.string.hdmi_ready, targetDisplay.displayId)
                setTextColor(ContextCompat.getColor(context, R.color.color_on_surface))
                textSize = 22f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(icon); card.addView(tv)
            root.addView(card)
            setContentView(root)
        }
    }
}
