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
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.MediaMetadata
import android.media.session.MediaController
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    private lateinit var centerCard: FrameLayout
    private lateinit var centerContainer: FrameLayout
    private lateinit var rightSidePanel: View
    
    private lateinit var musicCard: View
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var tvMusicAlbum: TextView
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
    
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null

    // 实例变量，避免 static 内存泄漏
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
                    if (album != null) tvMusicAlbum.text = album
                    musicDuration = duration
                    if (art != null) ivMusicCover.setImageBitmap(art) else ivMusicCover.setImageResource(android.R.drawable.ic_menu_report_image)
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
        private const val MUSIC_A2DP_TAG = "A2dpMediaBrowserService"
    }

    private var processMain: Process? = null
    private var processSystem: Process? = null
    private val isListening = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
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
        tvMusicAlbum = findViewById(R.id.tv_music_album)
        tvMusicTime = findViewById(R.id.tv_music_time)
        pbMusicProgress = findViewById(R.id.pb_music_progress)
        ivMusicCover = findViewById(R.id.iv_music_cover)
        
        dragHandleLeft = findViewById(R.id.drag_handle_left)
        dragHandleRight = findViewById(R.id.drag_handle_right)
        
        guidelineLeft = findViewById(R.id.guideline_left)
        guidelineRight = findViewById(R.id.guideline_right)
        surfaceView = findViewById(R.id.right_panel)
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
        applyGuidelinePreference(mainLayout)
        setupResizing(mainLayout)
        setupTouchForwarding()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)) showLogOverlay()
        initFloatingBall()
        startLogcatListener()
        initGlobalVirtualDisplay()
        startUiUpdateTimer()
        uiHandler.post(timeRunnable)
        adjustForScreenRatio(mainLayout)
        initMediaSessionListener()
    }

    private fun initMediaSessionListener() {
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            updateActiveMediaSession()
        } catch (e: Exception) {
            Log.e("Miao", "MediaSession init failed: ${e.message}")
        }
    }

    private fun updateActiveMediaSession() {
        try {
            // Android 9+ 需要通知监听权限才能获取所有活跃 Session
            val controllers = mediaSessionManager?.getActiveSessions(ComponentName(this, NotificationListener::class.java))
            if (!controllers.isNullOrEmpty()) {
                activeMediaController?.unregisterCallback(mediaCallback)
                activeMediaController = controllers[0]
                activeMediaController?.registerCallback(mediaCallback)
                mediaCallback.onMetadataChanged(activeMediaController?.metadata)
            }
        } catch (e: Exception) {}
    }

    private fun applyUiStyle() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
        val showMusic = prefs.getBoolean(KEY_SHOW_MUSIC_CARD, false)
        val canDrag = prefs.getBoolean(KEY_ENABLE_DRAG, true)
        
        if (isDouble) {
            rightSidePanel.visibility = View.VISIBLE
            powerContainerLeft.visibility = View.GONE
            musicCard.visibility = View.GONE
            dragHandleRight.visibility = if(canDrag) View.VISIBLE else View.GONE
            if (!prefs.getBoolean(KEY_LOCK_RATIO, false)) guidelineRight.setGuidelinePercent(0.78f)
        } else {
            rightSidePanel.visibility = View.GONE
            powerContainerLeft.visibility = View.VISIBLE
            musicCard.visibility = if (showMusic) View.VISIBLE else View.GONE
            dragHandleRight.visibility = View.GONE
            if (!prefs.getBoolean(KEY_LOCK_RATIO, false)) guidelineRight.setGuidelinePercent(1.0f)
        }
        dragHandleLeft.visibility = if(canDrag) View.VISIBLE else View.GONE
        adjustForScreenRatio(findViewById(R.id.main))
    }

    private fun adjustForScreenRatio(main: View) {
        main.post {
            val w = main.width.toFloat()
            val h = main.height.toFloat()
            if (w > 0 && h > 0) {
                val curL = (guidelineLeft.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                val curR = (guidelineRight.layoutParams as ConstraintLayout.LayoutParams).guidePercent
                val centerWidth = w * (curR - curL)
                val targetRatio = 16f / 9f
                val panelHeight = h - 60 // 48dp top_bar + 12dp margins
                if ((centerWidth / panelHeight) < targetRatio) {
                    val p = ((panelHeight - (centerWidth / targetRatio)) / 2).toInt().coerceAtLeast(0)
                    centerContainer.setPadding(0, p, 0, p)
                } else {
                    centerContainer.setPadding(0, 0, 0, 0)
                }
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
            val w = main.width.toFloat()
            val h = main.height.toFloat()
            if (w > 0 && h > 0) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val isDouble = prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)
                val targetCenterW = (h - 60) * (16f / 9f)

                if (isDouble) {
                    val sideW = (w - targetCenterW) / 2.0
                    val leftP = (sideW / w).toFloat().coerceIn(0.1f, 0.4f)
                    guidelineLeft.setGuidelinePercent(leftP)
                    guidelineRight.setGuidelinePercent(1.0f - leftP)
                } else {
                    val leftP = (1.0f - (targetCenterW / w)).coerceIn(0.1f, 0.5f)
                    guidelineLeft.setGuidelinePercent(leftP)
                    guidelineRight.setGuidelinePercent(1.0f)
                }
                adjustForScreenRatio(main)
                updateVirtualDisplaySize()
            }
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

    private fun getPrimaryContext(): Context {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return createDisplayContext(dm.getDisplay(0))
    }

    private fun getPrimaryWindowManager(): WindowManager {
        return getPrimaryContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#80FFFFFF")); setStroke(2, Color.GRAY) }
            addView(TextView(context).apply { text = "M"; setTextColor(Color.BLACK); gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
        }
        ball.setOnTouchListener(object : View.OnTouchListener {
            private var ix: Int = 0; private var iy: Int = 0; private var tx: Float = 0f; private var ty: Float = 0f; private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> { 
                        var nx = ix + (e.rawX - tx).toInt()
                        if (nx > 100 && nx < screenWidth - 180) nx = if (nx < screenWidth / 2) 5 else screenWidth - 85
                        params.x = nx; params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(ball, params)
                        if (Math.abs(e.rawX - tx) > 10 || Math.abs(e.rawY - ty) > 10) moved = true; return true 
                    }
                    MotionEvent.ACTION_UP -> { 
                        params.x = if (params.x < screenWidth / 2) 5 else screenWidth - 85
                        wm.updateViewLayout(ball, params)
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
        try { wm.addView(ball, params); floatingBall = ball } catch (e: Exception) {}
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
        var showMusic = prefs.getBoolean(KEY_SHOW_MUSIC_CARD, false)
        val wm = getPrimaryWindowManager()
        val ctx = getPrimaryContext()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        val ballParams = floatingBall?.layoutParams as WindowManager.LayoutParams
        
        val rootOverlay = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setOnClickListener { closeSettingsMenu() }
        }

        val overlayParams = WindowManager.LayoutParams().apply {
            width = 550; height = 750; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            dimAmount = 0.4f; format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START
            x = if (ballParams.x < metrics.widthPixels / 2) ballParams.x + 90 else ballParams.x - 560
            y = (ballParams.y - 100).coerceAtLeast(50)
        }
        
        val container = LinearLayout(ctx).apply { 
            orientation = LinearLayout.VERTICAL; setPadding(25, 20, 25, 25)
            background = GradientDrawable().apply { setColor(getColor(R.color.panel_background)); cornerRadius = 30f; setStroke(2, Color.GRAY) }
            setOnClickListener { }
        }
        
        val scroll = ScrollView(ctx).apply { 
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            addView(container)
        }
        rootOverlay.addView(scroll, FrameLayout.LayoutParams(550, -2))

        container.addView(TextView(ctx).apply { text = getString(R.string.settings_title); textSize = 16f; setTextColor(getColor(R.color.speed_text_color)); gravity = Gravity.CENTER; setPadding(0,0,0,15) })
        
        val rowStyle = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 15) }
        val bDouble = Button(ctx).apply { text = getString(R.string.style_double); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply { setMargins(4,0,4,0) } }
        val bSingle = Button(ctx).apply { text = getString(R.string.style_single); textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply { setMargins(4,0,4,0) } }
        fun upSty() { 
            bDouble.setBackgroundColor(if(isDouble) Color.parseColor("#4CAF50") else Color.LTGRAY)
            bSingle.setBackgroundColor(if(!isDouble) Color.parseColor("#4CAF50") else Color.LTGRAY)
            bDouble.setTextColor(Color.WHITE); bSingle.setTextColor(Color.WHITE) 
        }
        bDouble.setOnClickListener { isDouble = true; upSty() }; bSingle.setOnClickListener { isDouble = false; upSty() }
        upSty(); rowStyle.addView(bDouble); rowStyle.addView(bSingle); container.addView(rowStyle)

        val modeList = listOf(Triple(MODE_DISPLAY_ONLY, getString(R.string.mode_display), Color.GRAY), Triple(MODE_CARPAY, getString(R.string.mode_carplay), Color.parseColor("#2196F3")), Triple(MODE_EMBED_APP, getString(R.string.mode_embed), Color.parseColor("#FF9800")), Triple(MODE_MIRROR, getString(R.string.mode_mirror), Color.parseColor("#9C27B0")))
        val modeBtns = mutableListOf<Button>()
        val btnPick = Button(ctx).apply { text = getString(R.string.pick_app); textSize = 11f; layoutParams = LinearLayout.LayoutParams(-1, 80).apply { setMargins(0,5,0,15) }; setOnClickListener { showAppPickerOnPrimary() }; visibility = if(selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE }

        val modesGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (i in modeList.indices step 2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) if (i + j < modeList.size) {
                val (m, l, c) = modeList[i + j]
                val b = Button(ctx).apply { text = l; textSize = 11f; setAllCaps(false); layoutParams = LinearLayout.LayoutParams(0, 85, 1f).apply { setMargins(4, 4, 4, 4) }
                    if (selectedMode == m) { setBackgroundColor(c); setTextColor(Color.WHITE) } else { setBackgroundResource(R.color.button_inactive_bg); setTextColor(getColor(R.color.button_inactive_text)) }
                    setOnClickListener { 
                        selectedMode = m; btnPick.visibility = if (selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE
                        modeBtns.forEach { btn -> 
                            val mIdx = modeBtns.indexOf(btn)
                            if (modeList[mIdx].first == selectedMode) { btn.setBackgroundColor(modeList[mIdx].third); btn.setTextColor(Color.WHITE) } 
                            else { btn.setBackgroundResource(R.color.button_inactive_bg); btn.setTextColor(getColor(R.color.button_inactive_text)) } 
                        }
                    }
                }.also { modeBtns.add(it) }
                row.addView(b)
            }
            modesGrid.addView(row)
        }
        container.addView(modesGrid); container.addView(btnPick)
        
        val cbLock = CheckBox(ctx).apply { text = getString(R.string.lock_ratio); textSize = 12f; setTextColor(getColor(R.color.speed_text_color)); isChecked = prefs.getBoolean(KEY_LOCK_RATIO, false) }
        val cbDrag = CheckBox(ctx).apply { text = getString(R.string.allow_drag); textSize = 12f; setTextColor(getColor(R.color.speed_text_color)); isChecked = prefs.getBoolean(KEY_ENABLE_DRAG, true) }
        val rS = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0) }; rS.addView(cbLock); rS.addView(cbDrag); container.addView(rS)
        
        val cbMusic = CheckBox(ctx).apply { text = getString(R.string.music_card); textSize = 12f; setTextColor(getColor(R.color.speed_text_color)); isChecked = showMusic }
        container.addView(cbMusic)

        val btnApply = Button(ctx).apply { text = getString(R.string.apply_settings); textSize = 13f; setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(-1, 100).apply { setMargins(0,20,0,0) }
            setOnClickListener { 
                prefs.edit().putInt(KEY_MODE, selectedMode).putBoolean(KEY_UI_STYLE_DOUBLE, isDouble).putBoolean(KEY_LOCK_RATIO, cbLock.isChecked).putBoolean(KEY_ENABLE_DRAG, cbDrag.isChecked).putBoolean(KEY_SHOW_MUSIC_CARD, cbMusic.isChecked).apply()
                applyUiStyle(); if (cbLock.isChecked) apply16x9Now() else resetLayoutToNormal()
                virtualDisplay?.release(); virtualDisplay = null; createVirtualDisplay(surfaceView.holder)
                if (selectedMode == MODE_CARPAY) launchCarplayOnMain()
                closeSettingsMenu() 
            } 
        }
        container.addView(btnApply)
        try { wm.addView(rootOverlay, overlayParams); currentSettingsOverlay = rootOverlay } catch (e: Exception) {}
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

    private fun showLogOverlay() {
        if (floatingLogView != null) return
        val wm = getPrimaryWindowManager()
        val params = WindowManager.LayoutParams().apply { 
            width = 800; height = 400; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 
        }
        val root = LinearLayout(getPrimaryContext()).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#CC000000")) }
        val header = LinearLayout(root.context).apply { 
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.DKGRAY); setPadding(20, 10, 20, 10)
            setOnTouchListener { v, e -> 
                when(e.action) { 
                    MotionEvent.ACTION_DOWN -> { v.performClick(); true }
                    MotionEvent.ACTION_MOVE -> { params.x = (e.rawX - 400).toInt(); params.y = (e.rawY - 20).toInt(); wm.updateViewLayout(root, params); true }
                    else -> false 
                } 
            }
        }
        header.addView(TextView(root.context).apply { text = "[M]=Main [S]=System"; setTextColor(Color.YELLOW); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) } )
        header.addView(TextView(root.context).apply { text = " ✕ "; setTextColor(Color.WHITE); setOnClickListener { hideLogOverlay() } } )
        root.addView(header)
        val tv = TextView(root.context).apply { setTextColor(Color.WHITE); textSize = 11f; setPadding(10, 5, 10, 5) }
        root.addView(tv)
        try { wm.addView(root, params); floatingLogView = root; tvFloatingLog = tv } catch (e: Exception) {}
    }

    private fun hideLogOverlay() { 
        floatingLogView?.let { getPrimaryWindowManager().removeView(it) }
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
        } catch (e: Exception) { showHintDelayed() } 
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchForwarding() {
        surfaceView.setOnTouchListener { v, event ->
            v.requestFocus()
            val vd = virtualDisplay ?: return@setOnTouchListener false
            val displayId = vd.display.displayId
            val mappedX = event.x * (vd.display.width.toFloat() / v.width)
            val mappedY = event.y * (vd.display.height.toFloat() / v.height)
            val te = MotionEvent.obtain(event); te.setLocation(mappedX, mappedY)
            try { 
                if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(te, displayId)
                te.source = InputDevice.SOURCE_TOUCHSCREEN
                val injectMethod = InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                injectMethod.invoke(getSystemService(Context.INPUT_SERVICE), te, 2) 
            } catch (e: Exception) { Log.e("Miao", "Touch Failed: ${e.message}") } finally { te.recycle() }
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isOverlay = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)
        while (isListening.get()) {
            var process: Process? = null
            try { 
                val cmd = when { 
                    !isOverlay -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V MUSIC_A2DP_TAG:V *:S"
                    filter.isEmpty() -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V MUSIC_A2DP_TAG:V *:V"
                    else -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $SOC_TAG:V $MUSIC_TAG:V MUSIC_A2DP_TAG:V $filter:V *:S" 
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
                            var c = extractValue(logLine) ?: 0.0
                            if (c < -100.0) c += 256.0
                            lastCurrent = c; updatePowerDisplay() 
                        }
                        else if (logLine.contains("prop=0x2160f503")) { 
                            val v = extractValue(logLine)
                            if (v != null) { lastVoltage = v; updatePowerDisplay() } 
                        }
                        if (logLine.contains("prev MM title") || logLine.contains("onTrackChanged TrackInfo")) {
                            val title = Regex("(?:mTrackTitle|title)=([^,\n\\]]+)").find(logLine)?.groupValues?.get(1)?.trim()
                            val artist = Regex("(?:mArtistName|artist)=([^,\n\\]]+)").find(logLine)?.groupValues?.get(1)?.trim()
                            val album = Regex("(?:mAlbumTitle|album)=([^,\n\\]]+)").find(logLine)?.groupValues?.get(1)?.trim()
                            val len = Regex("(?:mTrackLen|track len)[ =]([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            uiHandler.post { 
                                if (title != null && title != "null") tvMusicTitle.text = title
                                if (artist != null && artist != "null") tvMusicArtist.text = artist
                                if (album != null && album != "null") tvMusicAlbum.text = album
                                if (len > 0) musicDuration = len
                                updateMusicTime(0)
                            }
                        }
                        if (logLine.contains("getPlayBackState state") || logLine.contains("position=")) { 
                            val pos = Regex("(?:time|position=)([0-9]+)").find(logLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            uiHandler.post { updateMusicTime(pos) } 
                        }
                    }
                }
            } catch (e: Exception) { SystemClock.sleep(5000) } finally { process?.destroy() }
        }
    }

    private fun updateMusicTime(currentMs: Long) {
        if (musicDuration <= 0) return
        val progress = (currentMs * 100 / musicDuration).toInt().coerceIn(0, 100)
        pbMusicProgress.progress = progress
        val curStr = String.format(Locale.US, "%02d:%02d", currentMs / 60000, (currentMs % 60000) / 1000)
        val durStr = String.format(Locale.US, "%02d:%02d", musicDuration / 60000, (musicDuration % 60000) / 1000)
        tvMusicTime.text = getString(R.string.app_name).replace("miao", "$curStr / $durStr") // 借用 app_name 占位或直接拼接
        tvMusicTime.text = "$curStr / $durStr"
    }

    private fun extractValue(line: String): Double? { return try { val pattern = "(?:data|value|电量|values)[\\s=]*([0-9.-]+)"; Regex(pattern).find(line)?.groupValues?.get(1)?.toDouble() } catch (e: Exception) { null } }
    private fun updatePowerDisplay() { 
        val p = (lastVoltage * lastCurrent) / 1000.0
        uiHandler.post { 
            tvPower.text = String.format(Locale.US, "%.1f", p)
            tvPowerLeft.text = String.format(Locale.US, "%.1f", p)
            pbPowerBar.progress = (p + 50).toInt().coerceIn(0, 200) 
        } 
    }
    private fun parseSpeed(logLine: String): String? { return try { val match = Regex("speed Value\\(\\)\\s*=\\s*([0-9.]+)").find(logLine); match?.groupValues?.get(1)?.toDouble()?.let { String.format(Locale.US, "%.1f", it) } } catch (e: Exception) { null } }
    
    private fun applyGuidelinePreference(mainLayout: ConstraintLayout) { 
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isLocked = prefs.getBoolean(KEY_LOCK_RATIO, false)
        if (isLocked) apply16x9Now() else { 
            val p = prefs.getFloat(KEY_GUIDE_PERCENT, 0.22f)
            guidelineLeft.setGuidelinePercent(p)
            guidelineRight.setGuidelinePercent(if(prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)) 1.0f - p else 1.0f) 
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
                if (v.id == R.id.drag_handle_left) {
                    val leftP = rawXPercent.coerceIn(0.1f, 0.45f)
                    guidelineLeft.setGuidelinePercent(leftP)
                    if (prefs.getBoolean(KEY_LOCK_RATIO, false)) {
                        val targetCenterW = (screenH - 60) * (16f/9f)
                        guidelineRight.setGuidelinePercent((leftP + (targetCenterW / screenW)).coerceAtMost(1.0f))
                    } else if (prefs.getBoolean(KEY_UI_STYLE_DOUBLE, true)) {
                        guidelineRight.setGuidelinePercent(1.0f - leftP)
                    }
                } else if (v.id == R.id.drag_handle_right) {
                    val rightP = rawXPercent.coerceIn(0.55f, 0.95f)
                    guidelineRight.setGuidelinePercent(rightP)
                    if (prefs.getBoolean(KEY_LOCK_RATIO, false)) {
                        val targetCenterW = (screenH - 60) * (16f/9f)
                        guidelineLeft.setGuidelinePercent((rightP - (targetCenterW / screenW)).coerceAtLeast(0.05f))
                    }
                }
                prefs.edit().putFloat(KEY_GUIDE_PERCENT, (guidelineLeft.layoutParams as ConstraintLayout.LayoutParams).guidePercent).apply()
                adjustForScreenRatio(mainLayout); updateVirtualDisplaySize()
            } else if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
            true
        }
        dragHandleLeft.setOnTouchListener(onTouch)
        dragHandleRight.setOnTouchListener(onTouch)
    }

    private fun startUiUpdateTimer() { 
        uiHandler.postDelayed(object : Runnable { 
            override fun run() { 
                if (tvFloatingLog != null && logBuffer.isNotEmpty()) { 
                    synchronized(logBuffer) { tvFloatingLog?.text = logBuffer.joinToString("\n"); logBuffer.clear() } 
                }; uiHandler.postDelayed(this, 500) 
            } 
        }, 500) 
    }
    
    private fun updateVirtualDisplaySize() { 
        val w = surfaceView.width
        val h = surfaceView.height
        if (w > 0 && h > 0) virtualDisplay?.resize(w, h, 160) 
    }
    
    private fun createVirtualDisplay(h: SurfaceHolder) { 
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val w = if (surfaceView.width > 0) surfaceView.width else 1280
        val hi = if (surfaceView.height > 0) surfaceView.height else 720
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_MODE, MODE_DISPLAY_ONLY) == MODE_MIRROR) {
            flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        }
        try { 
            virtualDisplay = dm.createVirtualDisplay("HDMI 屏幕", w, hi, 160, h.surface, flags) 
        } catch (e: Exception) { 
            virtualDisplay = dm.createVirtualDisplay("HDMI 屏幕", w, hi, 160, h.surface, flags) 
        }
        checkAndRunActiveMode() 
    }
    
    private fun showHintDelayed() { 
        Handler(Looper.getMainLooper()).postDelayed({ 
            if (isFinishing || currentRunningPackage != null) return@postDelayed
            val vd = virtualDisplay?.display
            if (vd != null) { 
                if (hintPresentation != null) hintPresentation?.dismiss()
                hintPresentation = HintPresentation(this, vd)
                try { hintPresentation?.show() } catch (e: Exception) {} 
            } 
        }, 1000) 
    }
    
    override fun onDestroy() { 
        super.onDestroy()
        isListening.set(false)
        processMain?.destroy()
        processSystem?.destroy() 
    }

    class NotificationListener : android.service.notification.NotificationListenerService()
    class HintPresentation(c: Context, private val targetDisplay: Display) : Presentation(c, targetDisplay) {
        override fun onCreate(b: Bundle?) { 
            super.onCreate(b)
            val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundResource(R.color.hint_bg_color) }
            l.addView(TextView(context).apply { 
                text = context.getString(R.string.hdmi_ready, targetDisplay.displayId)
                setTextColor(context.getColor(R.color.hint_text_color)); textSize = 24f; gravity = Gravity.CENTER 
            })
            setContentView(l) 
        }
    }
}
