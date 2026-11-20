package com.ts10.inspector.vehicle.qf

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

/** Read-only client for the callback API exported by the original QF CAN service. */
internal class QfVehicleClient(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onStatus: (UiText) -> Unit,
) : ServiceConnection {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var reconnectScheduled = false
    private var bindingInProgress = false

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    true
                }

                CALLBACK_SOURCE_DATA, CALLBACK_PACKED_DATA -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    data.createByteArray()?.let(onPacket)
                    reply?.writeNoException()
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    fun connect() {
        if (bindingInProgress) return
        bindingInProgress = true
        reconnectScheduled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        onStatus(UiText.of(R.string.qf_connecting))
        val intent = Intent(SERVICE_ACTION).setComponent(
            ComponentName(SERVICE_PACKAGE, SERVICE_CLASS),
        )
        runCatching {
            if (!applicationContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                bindingInProgress = false
                onStatus(UiText.of(R.string.qf_refused))
                scheduleReconnect(UiText.of(R.string.qf_refused))
            }
        }.onFailure { error ->
            bindingInProgress = false
            onStatus(UiText.of(R.string.qf_binder_error, error.javaClass.simpleName))
            Log.w(TAG, "QF vehicle service is not accessible", error)
            scheduleReconnect(UiText.of(R.string.qf_binder_error, error.javaClass.simpleName))
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        bindingInProgress = false
        remote = service
        onStatus(UiText.of(R.string.qf_connected))
        READ_ONLY_CALLBACKS.forEach(::registerCallback)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        bindingInProgress = false
        remote = null
        onStatus(UiText.of(R.string.qf_disconnected))
        scheduleReconnect(UiText.of(R.string.qf_disconnected))
    }

    override fun onNullBinding(name: ComponentName) {
        bindingInProgress = false
        remote = null
        onStatus(UiText.of(R.string.qf_null))
        scheduleReconnect(UiText.of(R.string.qf_null))
    }

    override fun onBindingDied(name: ComponentName) {
        bindingInProgress = false
        remote = null
        onStatus(UiText.of(R.string.qf_invalid))
        scheduleReconnect(UiText.of(R.string.qf_invalid))
    }

    private fun registerCallback(transaction: Int) {
        val binder = remote ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FEATURE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            binder.transact(transaction, data, reply, 0)
            reply.readException()
            onStatus(UiText.of(R.string.qf_callback_registered, transaction))
            Log.i(TAG, "QF callback $transaction registered")
        } catch (error: Exception) {
            onStatus(UiText.of(R.string.qf_callback_failed, transaction, error.javaClass.simpleName))
            Log.w(TAG, "QF callback registration $transaction failed", error)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "QfVehicleClient"
        const val RECONNECT_DELAY_MS = 1_500L
        const val SERVICE_PACKAGE = "com.qf.vehicle"
        const val SERVICE_CLASS = "com.qf.vehicle.service.VehicleService"
        const val SERVICE_ACTION = "com.qf.vehicle.service.ACTION_CAN_SERVICE"
        const val FEATURE_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceFeature"
        const val CALLBACK_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceCallback"
        const val TRANSACTION_ADD_AC_CALLBACK = 3
        const val TRANSACTION_ADD_DOOR_CALLBACK = 5
        const val TRANSACTION_ADD_STEERING_CALLBACK = 7
        const val TRANSACTION_ADD_RADAR_CALLBACK = 9
        const val TRANSACTION_ADD_CAR_BODY_CALLBACK = 11
        const val TRANSACTION_ADD_CAMERA_CALLBACK = 17
        const val TRANSACTION_ADD_TURN_LIGHT_CALLBACK = 37
        const val CALLBACK_SOURCE_DATA = 1
        const val CALLBACK_PACKED_DATA = 2
        val READ_ONLY_CALLBACKS = listOf(
            TRANSACTION_ADD_AC_CALLBACK,
            TRANSACTION_ADD_DOOR_CALLBACK,
            TRANSACTION_ADD_STEERING_CALLBACK,
            TRANSACTION_ADD_RADAR_CALLBACK,
            TRANSACTION_ADD_CAR_BODY_CALLBACK,
            TRANSACTION_ADD_CAMERA_CALLBACK,
            TRANSACTION_ADD_TURN_LIGHT_CALLBACK,
        )
    }

    private fun scheduleReconnect(reason: UiText) {
        if (reconnectScheduled) return
        reconnectScheduled = true
        onStatus(UiText.of(R.string.qf_retrying, reason))
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        connect()
    }
}
