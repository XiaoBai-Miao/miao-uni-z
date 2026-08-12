package com.example.miao

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Miao 触屏助手无障碍服务
 * 目前主要用于辅助权限获取，核心触摸分发已切回原生 InputManager
 */
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
}
