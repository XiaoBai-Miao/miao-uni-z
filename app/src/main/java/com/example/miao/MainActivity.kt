package com.example.miao

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvSpeed: TextView
    private lateinit var tvLogStatus: TextView
    private lateinit var guideline: Guideline
    private lateinit var surfaceView: SurfaceView
    private lateinit var divider: View

    private var floatingLogView: View? = null
    private var tvFloatingLog: TextView? = null

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
        
        private const val DEFAULT_TAG = "Mointerservice"
    }

    private var currentLogcatProcess: Process? = null
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
        guideline = findViewById(R.id.guideline)
        surfaceView = findViewById(R.id.right_panel)
        divider = findViewById(R.id.divider)
        val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
        val btnStopAppMain = findViewById<ImageButton>(R.id.btn_stop_app_main)

        val leftPanel = findViewById<ViewGroup>(R.id.left_panel)
        tvLogStatus = TextView(this).apply {
            text = getString(R.string.status_standby)
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        leftPanel.addView(tvLogStatus)

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnStopAppMain.setOnClickListener { stopCurrentApp() }

        applyGuidelinePreference(mainLayout)
        setupResizing(mainLayout)
        setupTouchForwarding()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false)) showLogOverlay()

        startLogcatListener()
        initGlobalVirtualDisplay()
    }

    private fun initGlobalVirtualDisplay() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (virtualDisplay == null) {
                    createVirtualDisplay(h)
                } else {
                    try {
                        val setSurfaceMethod = VirtualDisplay::class.java.getMethod("setSurface", android.view.Surface::class.java)
                        setSurfaceMethod.invoke(virtualDisplay, h.surface)
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
                if (lastPkg != null) launchAppInVirtualDisplay(lastPkg)
                else showHintDelayed()
            }
            MODE_MIRROR -> currentRunningPackage = "MIRROR_MODE"
            else -> {
                currentRunningPackage = null
                showHintDelayed()
            }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var selectedMode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20) // 压缩内边距
        }

        container.addView(TextView(this).apply { 
            text = "运行模式选择"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        })

        // 宫格选择，高度减半
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(0, 10, 0, 0)
        }

        val modes = listOf(
            Triple(MODE_DISPLAY_ONLY, "仅虚拟屏", Color.parseColor("#9E9E9E")),
            Triple(MODE_CARPAY, "CarPlay专用", Color.parseColor("#4CAF50")),
            Triple(MODE_EMBED_APP, "内嵌应用", Color.parseColor("#2196F3")),
            Triple(MODE_MIRROR, "屏幕镜像", Color.parseColor("#FF9800"))
        )

        val modeButtons = mutableListOf<Button>()
        modes.forEachIndexed { index, (mode, label, color) ->
            val btn = Button(this).apply {
                text = label
                textSize = 14f // 稍微缩小字体
                setAllCaps(false)
                // 高度从 240 改为 120
                layoutParams = LinearLayout.LayoutParams(0, 120, 1f).apply {
                    setMargins(8, 0, 8, 0)
                }
                
                if (selectedMode == mode) {
                    setBackgroundColor(color)
                    setTextColor(Color.WHITE)
                } else {
                    setBackgroundColor(Color.parseColor("#DDDDDD"))
                    setTextColor(Color.parseColor("#444444"))
                }

                setOnClickListener {
                    selectedMode = mode
                    modeButtons.forEachIndexed { i, b ->
                        if (modes[i].first == selectedMode) {
                            b.setBackgroundColor(modes[i].third)
                            b.setTextColor(Color.WHITE)
                        } else {
                            b.setBackgroundColor(Color.parseColor("#DDDDDD"))
                            b.setTextColor(Color.parseColor("#444444"))
                        }
                    }
                }
            }
            modeButtons.add(btn)
            if (index < 2) row1.addView(btn) else row2.addView(btn)
        }
        
        container.addView(row1)
        container.addView(row2)

        // 紧凑布局设置
        val optionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        
        val cbEnableDrag = CheckBox(this).apply { 
            text = "允许拖动"
            textSize = 14f
            isChecked = prefs.getBoolean(KEY_ENABLE_DRAG, true) 
        }
        val cbShowOverlay = CheckBox(this).apply { 
            text = "日志浮窗"
            textSize = 14f
            isChecked = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false) 
        }
        optionsLayout.addView(cbEnableDrag)
        optionsLayout.addView(cbShowOverlay)
        container.addView(optionsLayout)

        val etFilter = EditText(this).apply { 
            hint = "过滤关键字"
            textSize = 14f
            setText(prefs.getString(KEY_LOG_FILTER_KEYWORD, ""))
        }
        container.addView(etFilter)

        val btnPickApp = Button(this).apply {
            text = "▶ 选择应用"
            textSize = 14f
            setOnClickListener { showAppPicker() }
            visibility = if (selectedMode == MODE_EMBED_APP) View.VISIBLE else View.GONE
        }
        container.addView(btnPickApp)

        // 取消 ScrollView 包装，使页面更紧凑
        AlertDialog.Builder(this)
            .setTitle("Miao Settings")
            .setView(container) 
            .setPositiveButton("应用", { _, _ ->
                prefs.edit {
                    putInt(KEY_MODE, selectedMode)
                    putBoolean(KEY_SHOW_LOG_OVERLAY, cbShowOverlay.isChecked)
                    putString(KEY_LOG_FILTER_KEYWORD, etFilter.text.toString().trim())
                    putBoolean(KEY_ENABLE_DRAG, cbEnableDrag.isChecked)
                }
                divider.visibility = if (cbEnableDrag.isChecked) View.VISIBLE else View.GONE
                if (cbShowOverlay.isChecked) showLogOverlay() else hideLogOverlay()
                virtualDisplay?.release(); virtualDisplay = null
                createVirtualDisplay(surfaceView.holder)
                if (selectedMode == MODE_CARPAY) launchCarplayOnMain()
                restartLogcatListener()
            })
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLogOverlay() {
        if (floatingLogView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams().apply {
            width = 800; height = 500; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT; gravity = Gravity.TOP or Gravity.START; x = 100; y = 100
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#CC000000")) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.DKGRAY); setPadding(20, 10, 20, 10) }
        header.addView(TextView(this).apply { text = "LOGCAT"; setTextColor(Color.YELLOW); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        header.addView(TextView(this).apply { text = " ✕ "; setTextColor(Color.WHITE); setOnClickListener { hideLogOverlay() } })
        root.addView(header)
        header.setOnTouchListener { v, e ->
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { v.performClick(); true }
                MotionEvent.ACTION_MOVE -> { params.x = (e.rawX - 400).toInt(); params.y = (e.rawY - 20).toInt(); wm.updateViewLayout(root, params); true }
                else -> false
            }
        }
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 9f }
        root.addView(ScrollView(this).apply { addView(tv) })
        try { wm.addView(root, params); floatingLogView = root; tvFloatingLog = tv } catch (e: Exception) { Log.e("Miao", "Overlay fail", e) }
    }

    private fun hideLogOverlay() {
        floatingLogView?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        floatingLogView = null; tvFloatingLog = null
    }

    private fun launchCarplayOnMain() {
        val intent = packageManager.getLaunchIntentForPackage("com.autochips.carplayapp")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic()
            // 锁定在主物理屏幕启动
            options.launchDisplayId = 0 
            try { startActivity(intent, options.toBundle()) } catch (e: Exception) { startActivity(intent) }
        }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val adapter = object : ArrayAdapter<ResolveInfo>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent); val info = getItem(position)
                v.findViewById<TextView>(android.R.id.text1).text = info?.loadLabel(pm); v.findViewById<TextView>(android.R.id.text2).text = info?.activityInfo?.packageName; return v
            }
        }
        AlertDialog.Builder(this).setTitle("Select App").setAdapter(adapter) { _, i ->
            val pkg = list[i].activityInfo.packageName
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putString(KEY_LAST_APP, pkg) }
            launchAppInVirtualDisplay(pkg)
        }.show()
    }

    private fun launchAppInVirtualDisplay(pkg: String) {
        val displayId = virtualDisplay?.display?.displayId ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        hintPresentation?.dismiss(); currentRunningPackage = pkg
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val opt = ActivityOptions.makeBasic(); opt.launchDisplayId = displayId
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

    private fun setupTouchForwarding() {
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        surfaceView.setOnTouchListener { v, event ->
            val vd = virtualDisplay ?: return@setOnTouchListener false
            val displayId = vd.display.displayId
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val mappedX = (event.rawX - loc[0]) * (vd.display.width.toFloat() / v.width)
            val mappedY = (event.rawY - loc[1]) * (vd.display.height.toFloat() / v.height)
            val te = MotionEvent.obtain(event); te.setLocation(mappedX, mappedY)
            try {
                if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(te, displayId)
                te.source = InputDevice.SOURCE_TOUCHSCREEN
                InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType).invoke(inputManager, te, 0)
            } catch (e: Exception) {} finally { te.recycle() }
            true
        }
    }

    private fun restartLogcatListener() { isListening.set(false); currentLogcatProcess?.destroy(); startLogcatListener() }

    private fun startLogcatListener() {
        if (isListening.getAndSet(true)) return
        val handler = Handler(Looper.getMainLooper())
        val filter = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LOG_FILTER_KEYWORD, "") ?: ""
        thread(start = true, isDaemon = true) {
            try {
                val cmd = "logcat -b main -b system -v raw"
                currentLogcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(currentLogcatProcess?.inputStream))
                var line: String? = null; var lastUi = 0L
                while (isListening.get() && reader.readLine().also { line = it } != null) {
                    line?.let { logLine ->
                        if (tvFloatingLog != null && (filter.isEmpty() || logLine.contains(filter, true))) {
                            handler.post { tvFloatingLog?.append("\n$logLine"); if ((tvFloatingLog?.text?.length ?: 0) > 5000) tvFloatingLog?.text = tvFloatingLog?.text?.substring(2000) }
                        }
                        if (logLine.contains("speed Value")) {
                            val speed = parseSpeed(logLine); val now = SystemClock.elapsedRealtime()
                            if (speed != null && (now - lastUi > 30)) {
                                handler.post { tvLogStatus.text = "● RECEIVING"; tvLogStatus.setTextColor(Color.GREEN); tvSpeed.text = speed }
                                lastUi = now
                            }
                        }
                    }
                }
            } catch (e: Exception) {} finally { isListening.set(false) }
        }
    }

    private fun parseSpeed(logLine: String): String? {
        return try {
            val match = Regex("speed Value\\(\\)\\s*=\\s*([0-9.]+)").find(logLine)
            match?.groupValues?.get(1)?.toDouble()?.let { String.format(Locale.US, "%.1f", it) }
        } catch (e: Exception) { null }
    }

    private fun applyGuidelinePreference(mainLayout: ConstraintLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        divider.visibility = if (prefs.getBoolean(KEY_ENABLE_DRAG, true)) View.VISIBLE else View.GONE
        val p = prefs.getFloat(KEY_GUIDE_PERCENT, -1f)
        if (p < 0) surfaceView.post {
            val target = ((mainLayout.width - (mainLayout.height * (16f / 9f))) / mainLayout.width).coerceIn(0.1f, 0.8f)
            val params = guideline.layoutParams as ConstraintLayout.LayoutParams; params.guidePercent = target
            guideline.layoutParams = params
        } else { val params = guideline.layoutParams as ConstraintLayout.LayoutParams; params.guidePercent = p; guideline.layoutParams = params }
    }

    private fun setupResizing(mainLayout: ConstraintLayout) {
        divider.setOnTouchListener { v, event ->
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLE_DRAG, true)) return@setOnTouchListener false
            if (event.action == MotionEvent.ACTION_MOVE) {
                val final = (event.rawX / mainLayout.width.toFloat()).coerceIn(0.1f, 0.9f)
                val params = guideline.layoutParams as ConstraintLayout.LayoutParams; params.guidePercent = final
                guideline.layoutParams = params; prefs.edit { putFloat(KEY_GUIDE_PERCENT, final) }; updateVirtualDisplaySize()
            } else if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
            true
        }
    }

    private fun updateVirtualDisplaySize() {
        val w = surfaceView.width; val h = surfaceView.height
        if (w > 0 && h > 0) virtualDisplay?.resize(w, h, 160)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun createVirtualDisplay(h: SurfaceHolder) {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val w = if (surfaceView.width > 0) surfaceView.width else 1280
        val hi = if (surfaceView.height > 0) surfaceView.height else 720
        val mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        
        // 核心变更：模式锁定显示器名称为 "carplay仪表"
        val displayName = if (mode == MODE_CARPAY) "carplay仪表" else "HDMI 屏幕"
        
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        if (mode == MODE_MIRROR) flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 16
        try { virtualDisplay = dm.createVirtualDisplay(displayName, w, hi, 160, h.surface, flags or (1 shl 10)) } catch (e: Exception) { virtualDisplay = dm.createVirtualDisplay(displayName, w, hi, 160, h.surface, flags) }
        checkAndRunActiveMode()
    }

    private fun showHintDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || currentRunningPackage != null) return@postDelayed
            val vd = virtualDisplay?.display
            if (vd != null) { if (hintPresentation != null) hintPresentation?.dismiss(); hintPresentation = HintPresentation(this, vd); try { hintPresentation?.show() } catch (e: Exception) {} }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentLogcatProcess?.destroy()
    }

    class HintPresentation(c: Context, private val targetDisplay: Display) : Presentation(c, targetDisplay) {
        override fun onCreate(b: Bundle?) {
            super.onCreate(b)
            val l = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE) }
            l.addView(TextView(context).apply { text = "HDMI 虚拟屏幕就绪\nID: ${targetDisplay.displayId}\n请在左侧设置模式"; setTextColor(Color.BLACK); textSize = 24f; gravity = Gravity.CENTER })
            setContentView(l)
        }
    }
}
