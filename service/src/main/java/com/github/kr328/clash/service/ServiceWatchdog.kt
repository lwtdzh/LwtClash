package com.github.kr328.clash.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.log.Log

class ServiceWatchdog : Service() {
    private var targetService: ComponentName? = null

    override fun onBind(intent: Intent): IBinder {
        targetService = intent.getStringExtra(EXTRA_TARGET_SERVICE)
            ?.let(ComponentName::unflattenFromString)

        return Binder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val target = targetService
        if (target != null && StatusProvider.shouldStartClashOnBoot) {
            Log.w("Service process disconnected, restarting ${target.className}")
            startForegroundServiceCompat(Intent().setComponent(target))
        }

        return true
    }

    companion object {
        private const val EXTRA_TARGET_SERVICE = "targetService"

        fun intent(context: Context, target: Class<out Service>): Intent {
            return Intent(context, ServiceWatchdog::class.java).putExtra(
                EXTRA_TARGET_SERVICE,
                ComponentName(context, target).flattenToString()
            )
        }
    }
}

class ServiceWatchdogConnection(private val service: Service) : ServiceConnection {
    private var bound = false

    fun bind() {
        if (bound) return

        bound = service.bindService(
            ServiceWatchdog.intent(service, service.javaClass),
            this,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )
    }

    fun unbind() {
        if (!bound) return

        bound = false
        try {
            service.unbindService(this)
        } catch (_: IllegalArgumentException) {
            // The process may already have lost the binding.
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit

    override fun onServiceDisconnected(name: ComponentName?) = Unit
}
