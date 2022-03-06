package com.dede.nativetools.netspeed.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.dede.nativetools.netspeed.NetSpeedPreferences
import com.dede.nativetools.util.later

/**
 * 保活无障碍服务
 */
class AliveAccessibilityService : AccessibilityService(), OnEventCallback {

    private val controller by later { NetSpeedServiceController(this) }

    override fun onCreate() {
        super.onCreate()
        if (NetSpeedPreferences.status) {
            controller.startService(true)
            controller.stopForeground()
        }
        controller.init(onStartCallback = this)
    }

    override fun invoke() {
        // 绑定服务
        controller.bindService()
        controller.stopForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        controller.release()
        if (NetSpeedPreferences.status) {
            // 重新改为前台服务模式运行
            controller.startService()
        }
        super.onDestroy()
    }
}