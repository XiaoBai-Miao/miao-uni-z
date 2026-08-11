package com.example.miao

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class MiaoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MiaoAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    /**
     * 在虚拟屏对应的 Display 上模拟手势
     */
    fun dispatchGestureRelay(x: Float, y: Float, displayId: Int) {
        val path = Path()
        path.moveTo(x, y)
        // 模拟一个极小的滑动以激活车机屏幕响应
        path.lineTo(x, y + 1)
        
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        
        // 在 Android 9 上，即使不能 setDisplayId，也可以尝试通过默认分发
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(displayId)
        }
        
        dispatchGesture(builder.build(), null, null)
    }
}
