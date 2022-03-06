package com.dede.nativetools.netspeed.service


import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.os.HandlerCompat
import com.dede.nativetools.netspeed.INetSpeedInterface
import com.dede.nativetools.netspeed.NetSpeedConfiguration
import com.dede.nativetools.netspeed.NetSpeedPreferences
import com.dede.nativetools.netspeed.utils.NetSpeedCompute
import com.dede.nativetools.util.*
import kotlinx.coroutines.*
import kotlin.math.max


class NetSpeedService : Service(), Runnable {

    class NetSpeedBinder(private val service: NetSpeedService) : INetSpeedInterface.Stub() {

        private val coroutineScope = CoroutineScope(Dispatchers.Main + service.lifecycleJob)

        override fun updateConfiguration(configuration: NetSpeedConfiguration?) {
            if (configuration == null) return
            coroutineScope.launch {
                service.updateConfiguration(configuration)
            }
        }
    }

    companion object {
        private const val NOTIFY_ID = 10
        private const val DELAY_BLANK_NOTIFICATION_ICON = 3000L

        const val ACTION_CLOSE = "com.dede.nativetools.CLOSE"
        const val ACTION_START = "com.dede.nativetools.START"
        const val ACTION_STOP_FOREGROUND = "com.dede.nativetools.STOP_FOREGROUND"

        const val EXTRA_CONFIGURATION = "extra_configuration"

        fun createIntent(context: Context): Intent {
            val configuration = NetSpeedConfiguration().updateFrom(globalDataStore.load())
            return Intent<NetSpeedService>(context, EXTRA_CONFIGURATION to configuration)
        }

        fun launchForeground(context: Context) {
            if (NetSpeedPreferences.status) {
                context.startService(createIntent(context), true)
            }
        }

        fun toggle(context: Context) {
            val status = NetSpeedPreferences.status
            val intent = createIntent(context)
            if (status) {
                context.stopService(intent)
            } else {
                context.startService(intent, true)
            }
            NetSpeedPreferences.status = !status
        }
    }

    private val notificationManager: NotificationManager by systemService()
    private val powerManager: PowerManager by systemService()

    val lifecycleJob = Job()

    private val showBlankNotificationRunnable = this
    private var isForegroundMode = false

    override fun run() {
        // 需要隐藏通知
        configuration.needHideNotification = true
        update(configuration, netSpeedCompute.rxSpeed, netSpeedCompute.txSpeed)
    }

    private val netSpeedCompute = NetSpeedCompute { rxSpeed, txSpeed ->
        if (!powerManager.isInteractive) {
            // ACTION_SCREEN_OFF广播有一定的延迟，所以设备不可交互时不处理
            return@NetSpeedCompute
        }

        val speed = when (configuration.mode) {
            NetSpeedPreferences.MODE_ALL -> max(rxSpeed, txSpeed)
            NetSpeedPreferences.MODE_UP -> txSpeed
            else -> rxSpeed
        }
        if (speed < configuration.hideThreshold) {
            if (!HandlerCompat.hasCallbacks(uiHandler, showBlankNotificationRunnable)) {
                // 延迟3s再显示透明图标，防止通知图标频繁变动
                uiHandler.postDelayed(showBlankNotificationRunnable, DELAY_BLANK_NOTIFICATION_ICON)
            }
        } else {
            uiHandler.removeCallbacks(showBlankNotificationRunnable)
            configuration.needHideNotification = false
        }
        update(configuration, rxSpeed, txSpeed)
    }

    private val configuration = NetSpeedConfiguration()

    private fun update(
        configuration: NetSpeedConfiguration,
        rxSpeed: Long = 0L,
        txSpeed: Long = 0L
    ) {
        if (configuration.needHideNotification && !isForegroundMode) {
            notificationManager.cancel(NOTIFY_ID)
            return
        }
        val notify =
            NetSpeedNotificationHelper.createNotification(this, configuration, rxSpeed, txSpeed)
        notificationManager.notify(NOTIFY_ID, notify)
    }

    override fun onBind(intent: Intent): IBinder {
        return NetSpeedBinder(this)
    }

    override fun onCreate() {
        super.onCreate()
        val intentFilter = IntentFilter(
            Intent.ACTION_SCREEN_ON,// 打开屏幕
            Intent.ACTION_SCREEN_OFF,// 关闭屏幕
            ACTION_STOP_FOREGROUND,
            ACTION_CLOSE// 关闭
        )
        registerReceiver(innerReceiver, intentFilter)
        sendBroadcast(Intent(ACTION_START))// 发送服务开启广播

        resume()
    }

    private fun startForeground() {
        if (isForegroundMode) {
            return
        }
        Log.i("NetSpeedService", "startForeground: ")
        val notify = configuration.notification ?: NetSpeedNotificationHelper.createNotification(
            this,
            configuration
        )
        startForeground(NOTIFY_ID, notify)
        isForegroundMode = true
    }

    private fun stopForeground1(removeNotification: Boolean) {
        if (!isForegroundMode) {
            return
        }
        Log.i("NetSpeedService", "stopForeground1: " + removeNotification)
        isForegroundMode = false
        stopForeground(removeNotification)
    }

    /**
     * 恢复指示器
     */
    private fun resume() {
        netSpeedCompute.start()
    }

    /**
     * 暂停指示器
     */
    private fun pause() {
        netSpeedCompute.stop()
    }

    private fun updateConfiguration(configuration: NetSpeedConfiguration?) {
        if (configuration ?: return == this.configuration) {
            return
        }
        this.configuration.updateFrom(configuration)
            .also { netSpeedCompute.interval = it.interval }
        update(configuration, netSpeedCompute.rxSpeed, netSpeedCompute.txSpeed)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground()
        if (Logic.isAccessibilityEnable(this)) {
            stopForeground1(false)
        }
        val configuration = intent.extra<NetSpeedConfiguration>(EXTRA_CONFIGURATION)
        updateConfiguration(configuration)
        // https://developer.android.google.cn/guide/components/services#CreatingAService
        // https://developer.android.google.cn/reference/android/app/Service#START_REDELIVER_INTENT
        return START_REDELIVER_INTENT// 重建时再次传递Intent
    }

    override fun onDestroy() {
        lifecycleJob.cancel()
        netSpeedCompute.destroy()
        stopForeground1(true)
        notificationManager.cancel(NOTIFY_ID)
        unregisterReceiver(innerReceiver)
        super.onDestroy()
    }

    /**
     * 接收解锁、熄屏、亮屏广播
     */
    private val innerReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action ?: return) {
                ACTION_CLOSE -> {
                    stopSelf()
                }
                ACTION_STOP_FOREGROUND -> {
                    stopForeground1(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    track("网速服务广播亮屏恢复") {
                        resume()// 直接更新指示器
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    pause()// 关闭屏幕时显示，只保留服务保活
                }
            }
        }
    }
}
