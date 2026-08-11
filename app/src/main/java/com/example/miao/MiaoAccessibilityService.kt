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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * 在指定 Display 上分发点击
     */
    fun dispatchClick(x: Float, y: Float, displayId: Int) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(displayId)
        }
        
        dispatchGesture(builder.build(), null, null)
    }
}
