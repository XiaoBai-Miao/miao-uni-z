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
import android.widget.RadioButton
import android.widget.RadioGroup
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
        // 静态成员：确保在进程生命周期内永不消失
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
    private var logFileStream: FileOutputStream? = null

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

        initLogFile()
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
                    Log.d("Miao", "Re-attaching existing VirtualDisplay to new Surface")
                    try {
                        // 核心：利用反射将现有虚拟屏“挂载”到新的显示窗口，无需销毁屏幕
                        val setSurfaceMethod = VirtualDisplay::class.java.getMethod("setSurface", android.view.Surface::class.java)
                        setSurfaceMethod.invoke(virtualDisplay, h.surface)
                        
                        // 仅在当前没运行任何 App 时才显示欢迎提示，防止覆盖已有画面
                        if (currentRunningPackage == null) {
                            checkAndRunActiveMode()
                        }
                    } catch (e: Exception) {
                        Log.e("Miao", "setSurface failed, recreating...", e)
                        virtualDisplay?.release()
                        createVirtualDisplay(h)
                    }
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) { updateVirtualDisplaySize() }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                Log.d("Miao", "Surface destroyed, but VirtualDisplay stays alive in Companion")
            }
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
        val currentMode = prefs.getInt(KEY_MODE, MODE_DISPLAY_ONLY)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }

        container.addView(TextView(this).apply { text = "运行模式 (虚拟屏始终公开)"; textSize = 18f; setPadding(0, 0, 0, 20) })
        val radioGroup = RadioGroup(this)
        val rbDisplay = RadioButton(this).apply { text = "仅虚拟屏 (等待投屏)"; id = View.generateViewId() }
        val rbCarPlay = RadioButton(this).apply { text = "CarPlay专用 (主屏启动)"; id = View.generateViewId() }
        val rbEmbed = RadioButton(this).apply { text = "内嵌App (虚拟屏运行)"; id = View.generateViewId() }
        val rbMirror = RadioButton(this).apply { text = "镜像主屏幕 (物理克隆)"; id = View.generateViewId() }
        radioGroup.addView(rbDisplay); radioGroup.addView(rbCarPlay); radioGroup.addView(rbEmbed); radioGroup.addView(rbMirror)
        when(currentMode) {
            MODE_DISPLAY_ONLY -> rbDisplay.isChecked = true
            MODE_CARPAY -> rbCarPlay.isChecked = true
            MODE_EMBED_APP -> rbEmbed.isChecked = true
            MODE_MIRROR -> rbMirror.isChecked = true
        }
        container.addView(radioGroup)

        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        container.addView(TextView(this).apply { text = "布局设置"; textSize = 18f; setPadding(0, 0, 0, 20) })
        val cbEnableDrag = CheckBox(this).apply { text = "允许拖动改变分屏比例"; isChecked = prefs.getBoolean(KEY_ENABLE_DRAG, true) }
        container.addView(cbEnableDrag)
        container.addView(Button(this).apply {
            text = "重置为 16:9 比例"
            setOnClickListener {
                val totalWidth = findViewById<View>(R.id.main).width
                val totalHeight = findViewById<View>(R.id.main).height
                if (totalWidth > 0) {
                    val rightWidth = totalHeight * (16f / 9f)
                    val targetPercent = ((totalWidth - rightWidth) / totalWidth).coerceIn(0.1f, 0.8f)
                    val params = guideline.layoutParams as ConstraintLayout.LayoutParams
                    params.guidePercent = targetPercent
                    guideline.layoutParams = params
                    prefs.edit { putFloat(KEY_GUIDE_PERCENT, targetPercent) }
                    updateVirtualDisplaySize()
                }
            }
        })

        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        container.addView(TextView(this).apply { text = "调试选项"; textSize = 18f; setPadding(0, 0, 0, 20) })
        val cbShowOverlay = CheckBox(this).apply { text = "显示日志悬浮窗"; isChecked = prefs.getBoolean(KEY_SHOW_LOG_OVERLAY, false) }
        container.addView(cbShowOverlay)
        val etFilter = EditText(this).apply { hint = "日志过滤关键词"; setText(prefs.getString(KEY_LOG_FILTER_KEYWORD, "")) }
        container.addView(etFilter)

        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        val btnAction = Button(this).apply { text = "Select & Launch App"; visibility = if (currentMode == MODE_EMBED_APP) View.VISIBLE else View.GONE }
        radioGroup.setOnCheckedChangeListener { _, checkedId -> btnAction.visibility = if (checkedId == rbEmbed.id) View.VISIBLE else View.GONE }
        btnAction.setOnClickListener { showAppPicker() }
        container.addView(btnAction)

        container.addView(Button(this).apply {
            text = "STOP CURRENT TASK"
            setTextColor(Color.RED)
            setOnClickListener { stopCurrentApp() }
        })

        val tvId = TextView(this).apply {
            val id = virtualDisplay?.display?.displayId ?: -1
            text = "\nVirtual Display ID: $id"
            setTextIsSelectable(true)
        }
        container.addView(tvId)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save & Apply") { _, _ ->
                val newMode = when {
                    rbDisplay.isChecked -> MODE_DISPLAY_ONLY
                    rbCarPlay.isChecked -> MODE_CARPAY
                    rbMirror.isChecked -> MODE_MIRROR
                    else -> MODE_EMBED_APP
                }
                prefs.edit {
                    putInt(KEY_MODE, newMode)
                    putBoolean(KEY_SHOW_LOG_OVERLAY, cbShowOverlay.isChecked)
                    putString(KEY_LOG_FILTER_KEYWORD, etFilter.text.toString().trim())
                    putBoolean(KEY_ENABLE_DRAG, cbEnableDrag.isChecked)
                }

                divider.visibility = if (cbEnableDrag.isChecked) View.VISIBLE else View.GONE
                if (cbShowOverlay.isChecked) showLogOverlay() else hideLogOverlay()
                
                // 只有切换模式时，才强制释放并重建屏幕
                virtualDisplay?.release()
                virtualDisplay = null
                createVirtualDisplay(surfaceView.holder)

                if (newMode == MODE_CARPAY) launchCarplayOnMain()
                restartLogcatListener()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogOverlay() {
        if (floatingLogView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams().apply {
            width = 800; height = 500
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START; x = 100; y = 100
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#CC000000")) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.DKGRAY); setPadding(20, 10, 20, 10) }
        header.addView(TextView(this).apply { text = "LOGCAT"; setTextColor(Color.YELLOW); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        header.addView(TextView(this).apply { text = " ✕ "; setTextColor(Color.WHITE); setOnClickListener { hideLogOverlay() } })
        root.addView(header)
        header.setOnTouchListener(object : View.OnTouchListener {
            private var ix: Int = 0
            private var iy: Int = 0
            private var tx: Float = 0f
            private var ty: Float = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { 
                        ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY
                        v.performClick()
                        return true 
                    }
                    MotionEvent.ACTION_MOVE -> { 
                        params.x = ix + (e.rawX - tx).toInt()
                        params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(root, params)
                        return true 
                    }
                }
                return false
            }
        })
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 9f }
        root.addView(ScrollView(this).apply { addView(tv) })
        try { wm.addView(root, params); floatingLogView = root; tvFloatingLog = tv } catch (e: Exception) {}
    }

    private fun hideLogOverlay() {
        floatingLogView?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        floatingLogView = null; tvFloatingLog = null
    }

    private fun launchCarplayOnMain() {
        val intent = packageManager.getLaunchIntentForPackage("com.autochips.carplayapp")
        if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }
    }

    private fun showAppPicker() {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val adapter = object : ArrayAdapter<ResolveInfo>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent); val info = getItem(position)
                v.findViewById<TextView>(android.R.id.text1).text = info?.loadLabel(pm)
                v.findViewById<TextView>(android.R.id.text2).text = info?.activityInfo?.packageName; return v
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
            
            // 1. 获取 SurfaceView 在主物理屏上的绝对位置
            val location = IntArray(2)
            v.getLocationOnScreen(location)
            
            // 2. 计算相对于视图左上角的精确偏移
            val localX = event.rawX - location[0]
            val localY = event.rawY - location[1]
            
            // 3. 获取虚拟屏的真实分辨率（排除缩放干扰）
            val realSize = Point()
            @Suppress("DEPRECATION")
            vd.display.getRealSize(realSize)
            
            // 4. 计算坐标映射比例
            val mappedX = localX * (realSize.x.toFloat() / v.width)
            val mappedY = localY * (realSize.y.toFloat() / v.height)
            
            val te = MotionEvent.obtain(event)
            te.setLocation(mappedX, mappedY)
            
            try {
                if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
                
                // 5. 关键反射：将触摸事件绑定到虚拟显示器 ID
                val setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                setDisplayIdMethod.invoke(te, displayId)
                
                te.source = InputDevice.SOURCE_TOUCHSCREEN
                
                // 6. 注入事件 (异步模式防止阻塞 UI)
                val injectMethod = InputManager::class.java.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                injectMethod.invoke(inputManager, te, 0)
            } catch (e: Exception) {
                Log.e("Miao", "Touch forward fail: ${e.message}")
            } finally {
                te.recycle()
            }
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

    private fun initLogFile() {} // 移除后台写文件，防止延迟

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
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        if (mode == MODE_MIRROR) flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 16
        try { virtualDisplay = dm.createVirtualDisplay("HDMI 屏幕", w, hi, 160, h.surface, flags or (1 shl 10)) } catch (e: Exception) { virtualDisplay = dm.createVirtualDisplay("HDMI 屏幕", w, hi, 160, h.surface, flags) }
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
        // 核心变更：不再在销毁时释放虚拟屏，确保它在进程存活期间永远在线
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
