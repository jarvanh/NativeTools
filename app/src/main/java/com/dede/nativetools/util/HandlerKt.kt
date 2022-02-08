@file:JvmName("HandlerKt")

package com.dede.nativetools.util

import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.core.os.ExecutorCompat
import androidx.core.os.HandlerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus

val uiHandler by lazy { Handler(Looper.getMainLooper()) }

fun Handler.singlePost(r: Runnable, delayMillis: Long = 0) {
    if (HandlerCompat.hasCallbacks(this, r)) {
        this.removeCallbacks(r)
    }
    HandlerCompat.postDelayed(this, r, null, delayMillis)
}

val uiExecutor by lazy { ExecutorCompat.create(uiHandler) }

val exceptionHandler by lazy {
    CoroutineExceptionHandler { _, e ->
        e.printStackTrace()
    }
}

val mainScope by lazy { MainScope() + exceptionHandler }

typealias HandlerMessage = Message.() -> Unit

interface HandlerCallback : Handler.Callback {
    override fun handleMessage(msg: Message): Boolean {
        onHandleMessage(msg)
        return true
    }

    abstract fun onHandleMessage(msg: Message);
}

class LifecycleHandlerCallback(
    lifecycleOwner: LifecycleOwner,
    handlerMessage: HandlerMessage,
) : HandlerCallback, DefaultLifecycleObserver {

    private val holder = HandlerHolder(handlerMessage)

    private class HandlerHolder(handlerMessage: HandlerMessage) {
        var handlerMessage: HandlerMessage? = handlerMessage
            private set

        fun clear() {
            handlerMessage = null
        }
    }

    init {
        val lifecycle = lifecycleOwner.lifecycle
        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycle.addObserver(this)
        } else {
            holder.clear()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        holder.clear()
    }

    override fun onHandleMessage(msg: Message) {
        holder.handlerMessage?.invoke(msg)
    }
}
