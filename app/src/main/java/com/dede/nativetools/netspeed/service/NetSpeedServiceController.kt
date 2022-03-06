package com.dede.nativetools.netspeed.service

import android.content.*
import android.os.IBinder
import android.os.RemoteException
import com.dede.nativetools.netspeed.INetSpeedInterface
import com.dede.nativetools.netspeed.NetSpeedConfiguration
import com.dede.nativetools.netspeed.NetSpeedPreferences
import com.dede.nativetools.util.Intent
import com.dede.nativetools.util.IntentFilter
import com.dede.nativetools.util.startService
import com.dede.nativetools.util.toast

typealias OnEventCallback = () -> Unit

class NetSpeedServiceController(context: Context) : INetSpeedInterface.Default(),
    ServiceConnection {

    private val appContext = context.applicationContext
    private var register = false
    private var binder: INetSpeedInterface? = null

    var onCloseCallback: OnEventCallback? = null
    var onStartCallback: OnEventCallback? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action ?: return) {
                NetSpeedService.ACTION_START -> {
                    val onStartCallback = onStartCallback
                    onStartCallback?.invoke()
                }
                NetSpeedService.ACTION_CLOSE -> {
                    val onCloseCallback = onCloseCallback
                    unbindService()
                    NetSpeedPreferences.status = false
                    onCloseCallback?.invoke()
                }
            }
        }
    }

    fun init(onStartCallback: OnEventCallback? = null, onCloseCallback: OnEventCallback? = null) {
        this.onStartCallback = onStartCallback
        this.onCloseCallback = onCloseCallback
        registerReceiver()
    }

    private fun registerReceiver() {
        if (!register) {
            val intentFilter =
                IntentFilter(NetSpeedService.ACTION_CLOSE, NetSpeedService.ACTION_START)
            appContext.registerReceiver(eventReceiver, intentFilter)
        }
        register = true
    }

    private fun unregisterReceiver() {
        if (register) {
            appContext.unregisterReceiver(eventReceiver)
        }
        register = false
    }

    fun startService(bind: Boolean = false) {
        registerReceiver()
        val intent = NetSpeedService.createIntent(appContext)
        appContext.startService(intent, true)
        if (bind) {
            bindService()
        }
    }

    fun bindService() {
        registerReceiver()
        val intent = NetSpeedService.createIntent(appContext)
        appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun stopService() {
        val intent = Intent<NetSpeedService>(appContext)
        unbindService()
        appContext.stopService(intent)
        appContext.sendBroadcast(Intent(NetSpeedService.ACTION_CLOSE))
    }

    fun stopForeground() {
        appContext.sendBroadcast(Intent(NetSpeedService.ACTION_STOP_FOREGROUND))
    }

    fun unbindService() {
        if (binder == null) {
            return
        }
        appContext.unbindService(this)
        binder = null
    }

    fun release() {
        unbindService()
        unregisterReceiver()
        onStartCallback = null
        onCloseCallback = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = INetSpeedInterface.Stub.asInterface(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder = null
    }

    override fun updateConfiguration(configuration: NetSpeedConfiguration) {
        try {
            binder?.updateConfiguration(configuration)
        } catch (e: RemoteException) {
            appContext.toast("error")
        }
    }
}