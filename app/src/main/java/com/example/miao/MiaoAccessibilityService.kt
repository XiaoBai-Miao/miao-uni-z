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
     * 在指定 Display 上模拟手势（点击/滑动）
     * duration: 触摸时长，点击通常为 50ms
     */
    fun dispatchGestureRelay(x: Float, y: Float, displayId: Int, duration: Long = 50) {
        val path = Path()
        path.moveTo(x, y)
        // 增加微小位移以模拟更真实的点击，防止被系统过滤
        path.lineTo(x, y + 1)
        
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(displayId)
        }
        
        dispatchGesture(builder.build(), null, null)
    }
}
