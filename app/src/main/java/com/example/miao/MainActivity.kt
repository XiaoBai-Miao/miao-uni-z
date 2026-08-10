package com.example.miao

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var tvTime: TextView
    private lateinit var tvLogStatus: TextView
    private lateinit var guideline: Guideline
    private lateinit var surfaceView: SurfaceView
    private lateinit var divider: View

    private val logBuffer = mutableListOf<String>()
    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var lastVoltage: Double = 0.0
    private var lastCurrent: Double = 0.0

    private val timeRunnable = object : Runnable {
        override fun run() {
            tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            uiHandler.postDelayed(this, 1000)
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
        private const val KEY_LAST_APP = "last_app_pkg"
        private const val KEY_SHOW_LOG_OVERLAY = "show_log_overlay"
        private const val KEY_LOG_FILTER_KEYWORD = "log_filter_keyword"
        private const val KEY_ENABLE_DRAG = "enable_divider_drag"
        private const val KEY_GUIDE_PERCENT = "guide_percent"
        private const val KEY_LOCK_RATIO = "lock_ratio_16_9"
        private const val DEFAULT_TAG = "Mointerservice"
        private const val POWER_TAG = "CmdSmdManager"

        @SuppressLint("StaticFieldLeak")
        private var floatingBall: View? = null
        @SuppressLint("StaticFieldLeak")
        private var tvFloatingLog: TextView? = null
        private var floatingLogView: View? = null
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
        tvTime = findViewById(R.id.tv_time)
        guideline = findViewById(R.id.guideline)
        surfaceView = findViewById(R.id.right_panel)
        divider = findViewById(R.id.divider)
        
        findViewById<ImageButton>(R.id.btn_settings).visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_stop_app_main).setOnClickListener { stopCurrentApp() }

        val leftPanel = findViewById<ViewGroup>(R.id.left_panel)
        tvLogStatus = TextView(this).apply {
            text = getString(R.string.status_standby)
            setTextColor(getColor(R.color.label_text_color))
            textSize = 10f
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        leftPanel.addView(tvLogStatus)

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
    }

    private fun getPrimaryContext(): Context {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val primaryDisplay = dm.getDisplay(0)
        return createDisplayContext(primaryDisplay)
    }

    private fun getPrimaryWindowManager(): WindowManager {
        return getPrimaryContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun initFloatingBall() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (floatingBall != null) return
        val wm = getPrimaryWindowManager()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        
        val params = WindowManager.LayoutParams().apply {
            width = 80; height = 80
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START; x = 5; y = 400
        }
        val ball = FrameLayout(getPrimaryContext()).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99FFFFFF")); setStroke(2, Color.GRAY) }
            addView(TextView(context).apply { text = "M"; setTextColor(Color.BLACK); gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
        }
        ball.setOnTouchListener(object : View.OnTouchListener {
            private var ix: Int = 0; private var iy: Int = 0; private var tx: Float = 0f; private var ty: Float = 0f; private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> { 
                        var nx = ix + (e.rawX - tx).toInt()
                        if (nx > 100 && nx < screenWidth - 180) { nx = if (nx < screenWidth / 2) 5 else screenWidth - 85 }
                        params.x = nx; params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(ball, params)
                        if (Math.abs(e.rawX - tx) > 15 || Math.abs(e.rawY - ty) > 15) moved = true; return true 
                    }
                    MotionEvent.ACTION_UP -> { 
                        params.x = if (params.x < screenWidth / 2) 5 else screenWidth - 85
                        wm.updateViewLayout(ball, params)
                        if (!moved) { v.performClick(); showOverlaySettings() }; return true 
                    }
                }
                return false
            }
        })
        try { wm.addView(ball, params); floatingBall = ball } catch (e: Exception) {}
    }

    private fun showOverlaySettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var selectedMode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        val wm = getPrimaryWindowManager()
        val ctx = getPrimaryContext()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        
        val ballParams = floatingBall?.layoutParams as WindowManager.LayoutParams
        val overlayParams = WindowManager.LayoutParams().apply { 
            width = 700; height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND
            dimAmount = 0.4f; format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START
            x = if (ballParams.x < metrics.widthPixels / 2) ballParams.x + 90 else ballParams.x - 710
            y = (ballParams.y - 100).coerceAtLeast(50)
        }
        
        val rootScroll = ScrollView(ctx).apply { layoutParams = ViewGroup.LayoutParams(700, -2); isFillViewport = true }
        val container = LinearLayout(ctx).apply { 
            orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 25)
            background = GradientDrawable().apply { setColor(getColor(R.color.panel_background)); cornerRadius = 30f; setStroke(2, Color.GRAY) }
        }
        rootScroll.addView(container)
        
        container.addView(TextView(ctx).apply { text = "Miao Cluster Settings"; textSize = 16f; setTextColor(getColor(R.color.speed_text_color)); gravity = Gravity.CENTER; setPadding(0,0,0,20) })
        val grid1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val grid2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0) }
        val modes = listOf(Triple(MODE_DISPLAY_ONLY, "仅显示", Color.GRAY), Triple(MODE_CARPAY, "CarPlay", Color.parseColor("#4CAF50")), Triple(MODE_EMBED_APP, "内嵌App", Color.parseColor("#2196F3")), Triple(MODE_MIRROR, "镜像屏", Color.parseColor("#FF9800")))
        val modeBtns = mutableListOf<Button>()
        
        val btnPick = Button(ctx).apply { text = "▶ 选择App"; textSize = 12f; setOnClickListener { showAppPickerOnPrimary() }; visibility = if(selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE }

        modes.forEachIndexed { i, (m, l, c) ->
            val b = Button(ctx).apply {
                text = l; textSize = 13f; setAllCaps(false); layoutParams = LinearLayout.LayoutParams(0, 90, 1f).apply { setMargins(5,0,5,0) }
                if (selectedMode == m) { setBackgroundColor(c); setTextColor(Color.WHITE) } else { setBackgroundResource(R.color.button_inactive_bg); setTextColor(getColor(R.color.button_inactive_text)) }
                setOnClickListener { 
                    selectedMode = m
                    btnPick.visibility = if (selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE
                    modeBtns.forEachIndexed { idx, targetBtn -> 
                        if (modes[idx].first == selectedMode) { targetBtn.setBackgroundColor(modes[idx].third); targetBtn.setTextColor(Color.WHITE) } 
                        else { targetBtn.setBackgroundResource(R.color.button_inactive_bg); targetBtn.setTextColor(getColor(R.color.button_inactive_text)) } 
                    }
                }
            }
            modeBtns.add(b); if (i < 2) grid1.addView(b) else grid2.addView(b)
        }
        container.addView(grid1); container.addView(grid2)

        val switchRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 0) }
        val cbLock = CheckBox(ctx).apply { text = "锁定16:9"; textSize = 13f; setTextColor(getColor(R.color.speed_text_color)); isChecked = prefs.getBoolean(KEY_LOCK_RATIO, false) }
        val cbDrag = CheckBox(ctx).apply { text = "允许拖动"; textSize = 13f; setTextColor(getColor(R.color.speed_text_color)); isChecked = prefs.getBoolean(KEY_ENABLE_DRAG, true); isEnabled = !cbLock.isChecked }
        
        cbLock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_LOCK_RATIO, isChecked).apply()
            cbDrag.isEnabled = !isChecked
            if (isChecked) { apply16x9Now(); divider.visibility = View.GONE }
            else { divider.visibility = if (prefs.getBoolean(KEY_ENABLE_DRAG, true)) View.VISIBLE else View.GONE }
        }
        cbDrag.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ENABLE_DRAG, isChecked).apply()
            if (!cbLock.isChecked) divider.visibility = if(isChecked) View.VISIBLE else View.GONE
        }
        switchRow.addView(cbLock); switchRow.addView(cbDrag); container.addView(switchRow)

        val logRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cbLog = CheckBox(ctx).apply { text = "日志浮窗"; textSize = 13f; setTextColor(getColor(R.color.speed_text_color)); isChecked = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false); setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(KEY_SHOW_LOG_OVERLAY, c).apply(); if(c) showLogOverlay() else hideLogOverlay() } }
        logRow.addView(cbLog); container.addView(logRow)

        val et = EditText(ctx).apply { hint = "过滤词"; textSize = 13f; setTextColor(getColor(R.color.speed_text_color)); setText(prefs.getString(KEY_LOG_FILTER_KEYWORD, "")); addTextChangedListener(object: android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {} override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString(KEY_LOG_FILTER_KEYWORD, s.toString().trim()).apply(); restartLogcatListener() } }) }
        container.addView(et); container.addView(btnPick)

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 25, 0, 0) }
        actions.addView(Button(ctx).apply { text = "取消"; textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, 90, 1f); setOnClickListener { wm.removeView(rootScroll) } })
        actions.addView(Button(ctx).apply { text = "应用重启"; textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, 90, 1f); setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE)
            setOnClickListener { prefs.edit().putInt(KEY_MODE, selectedMode).apply(); virtualDisplay?.release(); virtualDisplay = null; createVirtualDisplay(surfaceView.holder)
                if (selectedMode == MODE_CARPAY) launchCarplayOnMain(); wm.removeView(rootScroll) } })
        container.addView(actions); try { wm.addView(rootScroll, overlayParams) } catch (e: Exception) {}
    }

    private fun showAppPickerOnPrimary() {
        val primaryContext = getPrimaryContext()
        // 使用带有 Dialog 主题的包装 Context 解决非 Activity 弹窗崩溃
        val themedContext = ContextThemeWrapper(primaryContext, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        
        val adapter = object : ArrayAdapter<ResolveInfo>(themedContext, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val info = getItem(position)
                v.findViewById<TextView>(android.R.id.text1).apply { text = info?.loadLabel(pm); setTextColor(Color.BLACK); textSize = 14f }
                v.findViewById<TextView>(android.R.id.text2).apply { text = info?.activityInfo?.packageName; setTextColor(Color.GRAY); textSize = 11f }
                return v
            }
        }

        val dialog = AlertDialog.Builder(themedContext)
            .setTitle("选择应用")
            .setAdapter(adapter) { _, i -> 
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { 
                    putString(KEY_LAST_APP, list[i].activityInfo.packageName) 
                }
                launchAppInVirtualDisplay(list[i].activityInfo.packageName) 
            }
            .setNegativeButton("取消", null)
            .create()
            
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun apply16x9Now() {
        val main = findViewById<ConstraintLayout>(R.id.main)
        main.post {
            if (main.width > 0) {
                val h = if(main.height > 0) main.height else 720
                val target = ((main.width - (h * (16f / 9f))) / main.width).coerceIn(0.1f, 0.8f)
                val params = guideline.layoutParams as ConstraintLayout.LayoutParams
                params.guidePercent = target
                guideline.layoutParams = params
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(KEY_GUIDE_PERCENT, target).apply()
                surfaceView.post { updateVirtualDisplaySize() }
            }
        }
    }

    private fun initGlobalVirtualDisplay() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (virtualDisplay == null) createVirtualDisplay(h)
                else { try { val m = VirtualDisplay::class.java.getMethod("setSurface", android.view.Surface::class.java); m.invoke(virtualDisplay, h.surface)
                        if (currentRunningPackage == null) checkAndRunActiveMode() } catch (e: Exception) { virtualDisplay?.release(); createVirtualDisplay(h) } }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) { updateVirtualDisplaySize() }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun checkAndRunActiveMode() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); val mode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY); hintPresentation?.dismiss()
        when (mode) { MODE_CARPAY -> currentRunningPackage = "com.autochips.carplayapp"
            MODE_EMBED_APP -> { val lastPkg = prefs.getString(KEY_LAST_APP, null); if (lastPkg != null) launchAppInVirtualDisplay(lastPkg) else showHintDelayed() }
            MODE_MIRROR -> currentRunningPackage = "MIRROR_MODE"
            else -> { currentRunningPackage = null; showHintDelayed() }
        }
    }

    private fun showLogOverlay() {
        if (floatingLogView != null) return
        val wm = getPrimaryWindowManager()
        val params = WindowManager.LayoutParams().apply { width = 800; height = 400; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL; format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START; x = 100; y = 100 }
        val root = LinearLayout(getPrimaryContext()).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#CC000000")) }
        val header = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.DKGRAY); setPadding(20, 10, 20, 10) }
        header.addView(TextView(root.context).apply { text = "[M]=Main [S]=System"; setTextColor(Color.YELLOW); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        header.addView(TextView(root.context).apply { text = " ✕ "; setTextColor(Color.WHITE); setOnClickListener { hideLogOverlay() } })
        root.addView(header)
        header.setOnTouchListener { v, e -> when(e.action) { MotionEvent.ACTION_DOWN -> { v.performClick(); true }
            MotionEvent.ACTION_MOVE -> { params.x = (e.rawX - 400).toInt(); params.y = (e.rawY - 20).toInt(); wm.updateViewLayout(root, params); true }
            else -> false } }
        val tv = TextView(root.context).apply { setTextColor(Color.WHITE); textSize = 9f; setPadding(10, 5, 10, 5) }
        root.addView(tv); try { wm.addView(root, params); floatingLogView = root; tvFloatingLog = tv } catch (e: Exception) {}
    }

    private fun hideLogOverlay() { floatingLogView?.let { getPrimaryWindowManager().removeView(it) }; floatingLogView = null; tvFloatingLog = null }

    private fun launchCarplayOnMain() {
        val intent = packageManager.getLaunchIntentForPackage("com.autochips.carplayapp")
        if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); val opt = ActivityOptions.makeBasic(); opt.launchDisplayId = 0; try { startActivity(intent, opt.toBundle()) } catch (e: Exception) { startActivity(intent) } }
    }

    private fun launchAppInVirtualDisplay(pkg: String) {
        val displayId = virtualDisplay?.display?.displayId ?: return; val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        hintPresentation?.dismiss(); currentRunningPackage = pkg; intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT); val opt = ActivityOptions.makeBasic(); opt.launchDisplayId = displayId; try { startActivity(intent, opt.toBundle()) } catch (e: Exception) { showHintDelayed() }
    }

    private fun stopCurrentApp() { try { if (currentRunningPackage != null && currentRunningPackage != "MIRROR_MODE") { val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager; am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, currentRunningPackage) }; currentRunningPackage = null; showHintDelayed() } catch (e: Exception) { showHintDelayed() } }

    private fun setupTouchForwarding() {
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        surfaceView.setOnTouchListener { v, event ->
            val vd = virtualDisplay ?: return@setOnTouchListener false; val displayId = vd.display.displayId; val loc = IntArray(2); v.getLocationOnScreen(loc)
            val te = MotionEvent.obtain(event); te.setLocation((event.rawX - loc[0]) * (vd.display.width.toFloat() / v.width), (event.rawY - loc[1]) * (vd.display.height.toFloat() / v.height))
            try { if (event.action == MotionEvent.ACTION_DOWN) v.performClick(); MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(te, displayId); te.source = InputDevice.SOURCE_TOUCHSCREEN; InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType).invoke(inputManager, te, 0) } catch (e: Exception) {} finally { te.recycle() }
            true
        }
    }

    private fun restartLogcatListener() { isListening.set(false); processMain?.destroy(); processSystem?.destroy(); startLogcatListener() }

    private fun startLogcatListener() {
        if (isListening.getAndSet(true)) return
        val filter = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LOG_FILTER_KEYWORD, "") ?: ""
        thread(start = true, isDaemon = true) { runLogcatTask("-b main", "[M]", filter) }
        thread(start = true, isDaemon = true) { runLogcatTask("-b system", "[S]", filter) }
    }

    private fun runLogcatTask(bufferArgs: String, prefix: String, filter: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); val isOverlay = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)
        while (isListening.get()) {
            var process: Process? = null
            try { val cmd = when { !isOverlay -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V *:S"; filter.isEmpty() -> if (bufferArgs.contains("main")) "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V *:V" else "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V *:S"; else -> "logcat $bufferArgs -v raw $DEFAULT_TAG:V $POWER_TAG:V $filter:V *:S" }
                process = Runtime.getRuntime().exec(cmd); if (bufferArgs.contains("main")) processMain = process else processSystem = process
                val reader = BufferedReader(InputStreamReader(process.inputStream)); var line: String? = null
                while (isListening.get() && reader.readLine().also { line = it } != null) { line?.let { logLine ->
                        if (isOverlay) { synchronized(logBuffer) { logBuffer.add("$prefix $logLine"); if (logBuffer.size > 20) logBuffer.removeAt(0) } }
                        if (logLine.contains("speed Value")) {
                            val speed = parseSpeed(logLine)
                            if (speed != null) uiHandler.post { tvSpeed.text = speed }
                        }
                        if (logLine.contains("prop=0x2160f502")) {
                             val current = extractValue(logLine)
                             if (current != null) { lastCurrent = current; updatePowerDisplay() }
                        } else if (logLine.contains("prop=0x2160f503")) {
                             val voltage = extractValue(logLine)
                             if (voltage != null) { lastVoltage = voltage; updatePowerDisplay() }
                        }
                    }
                }
            } catch (e: Exception) { SystemClock.sleep(5000) } finally { process?.destroy() }
        }
    }

    private fun extractValue(line: String): Double? {
        return try { val pattern = "value=([0-9.-]+)"; Regex(pattern).find(line)?.groupValues?.get(1)?.toDouble() } catch (e: Exception) { null }
    }

    private fun updatePowerDisplay() {
        val powerKw = (lastVoltage * lastCurrent) / 1000.0
        uiHandler.post { tvPower.text = String.format(Locale.US, "%.1f", powerKw) }
    }

    private fun parseSpeed(logLine: String): String? {
        return try { val match = Regex("speed Value\\(\\)\\s*=\\s*([0-9.]+)").find(logLine)
            match?.groupValues?.get(1)?.toDouble()?.let { String.format(Locale.US, "%.1f", it) } } catch (e: Exception) { null }
    }

    private fun applyGuidelinePreference(mainLayout: ConstraintLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isLocked = prefs.getBoolean(KEY_LOCK_RATIO, false)
        if (isLocked) { apply16x9Now(); divider.visibility = View.GONE }
        else {
            divider.visibility = if (prefs.getBoolean(KEY_ENABLE_DRAG, true)) View.VISIBLE else View.GONE
            val p = prefs.getFloat(KEY_GUIDE_PERCENT, -1f)
            if (p < 0) apply16x9Now()
            else { val params = guideline.layoutParams as ConstraintLayout.LayoutParams; params.guidePercent = p; guideline.layoutParams = params }
        }
    }

    private fun setupResizing(mainLayout: ConstraintLayout) {
        divider.setOnTouchListener { v, event ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLE_DRAG, true) || prefs.getBoolean(KEY_LOCK_RATIO, false)) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_MOVE) { val final = (event.rawX / mainLayout.width.toFloat()).coerceIn(0.1f, 0.9f); val params = guideline.layoutParams as ConstraintLayout.LayoutParams; params.guidePercent = final
                guideline.layoutParams = params; prefs.edit { putFloat(KEY_GUIDE_PERCENT, final) }; updateVirtualDisplaySize() } 
            else if (event.action == MotionEvent.ACTION_DOWN) v.performClick(); true
        }
    }

    private fun startUiUpdateTimer() { uiHandler.postDelayed(object : Runnable { override fun run() { if (tvFloatingLog != null && logBuffer.isNotEmpty()) { synchronized(logBuffer) { tvFloatingLog?.text = logBuffer.joinToString("\n"); logBuffer.clear() } }; uiHandler.postDelayed(this, 500) } }, 500) }
    private fun updateVirtualDisplaySize() { val w = surfaceView.width; val h = surfaceView.height; if (w > 0 && h > 0) virtualDisplay?.resize(w, h, 160) }

    @android.annotation.SuppressLint("WrongConstant")
    private fun createVirtualDisplay(h: SurfaceHolder) {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager; val w = if (surfaceView.width > 0) surfaceView.width else 1280; val hi = if (surfaceView.height > 0) surfaceView.height else 720
        val displayName = "HDMI 屏幕"; val mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION; if (mode == MODE_MIRROR) flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 16
        try { virtualDisplay = dm.createVirtualDisplay(displayName, w, hi, 160, h.surface, flags or (1 shl 10)) } catch (e: Exception) { virtualDisplay = dm.createVirtualDisplay(displayName, w, hi, 160, h.surface, flags) }; checkAndRunActiveMode()
    }

    private fun showHintDelayed() { Handler(Looper.getMainLooper()).postDelayed({ if (isFinishing || currentRunningPackage != null) return@postDelayed; val vd = virtualDisplay?.display; if (vd != null) { if (hintPresentation != null) hintPresentation?.dismiss(); hintPresentation = HintPresentation(this, vd); try { hintPresentation?.show() } catch (e: Exception) {} } }, 1000) }

    override fun onDestroy() { super.onDestroy(); isListening.set(false); processMain?.destroy(); processSystem?.destroy() }

    class HintPresentation(c: Context, private val targetDisplay: Display) : Presentation(c, targetDisplay) {
        override fun onCreate(b: Bundle?) { super.onCreate(b); val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundResource(R.color.hint_bg_color) }; l.addView(TextView(context).apply { text = "HDMI 虚拟屏幕就绪\nID: ${targetDisplay.displayId}\n请在左侧设置模式"; setTextColor(context.getColor(R.color.hint_text_color)); textSize = 24f; gravity = Gravity.CENTER }); setContentView(l) }
    }
}
