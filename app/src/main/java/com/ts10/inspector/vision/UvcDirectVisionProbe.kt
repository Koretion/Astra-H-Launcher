package com.ts10.inspector.vision

import com.ts10.inspector.ui.uiText
import com.ts10.inspector.ui.LocalizedException
import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.ts10.inspector.camera.GrabadoraHdCameraClient
import com.ts10.inspector.diagnostics.TelemetryLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Vision source that opens a UVC device directly, without Grabadora HD's Binder.
 *
 * Started by [LocalVisionPipeline] when the selected source is [LocalVisionSource.USB_UVC].
 * Most USB cameras permit a single owner, so the full-screen USB viewer borrows the device while
 * open. Stopping restores the secondary Grabadora HD Surface path.
 */
internal object UvcDirectVisionProbe {
    private const val SOURCE_NAME = "uvc-direct-rgbx"

    private val mutableState = MutableStateFlow(UvcDirectVisionState())
    val state: StateFlow<UvcDirectVisionState> = mutableState.asStateFlow()

    private var helper: CameraHelper? = null
    private var applicationContext: Context? = null
    private var selectedDeviceId: Int? = null
    private var firstFrameElapsed = 0L
    private var running = false

    private val frameCallback = IFrameCallback { buffer -> onFrame(buffer) }
    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            if (looksLikeVideoDevice(device) && selectedDeviceId == null) select(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (!running || selectedDeviceId != device.deviceId) return
            val cameraHelper = helper ?: return
            val sizes = runCatching { cameraHelper.supportedSizeList.orEmpty() }.getOrDefault(emptyList())
            val formats = runCatching { cameraHelper.supportedFormatList.orEmpty() }.getOrDefault(emptyList())
            val selectedSize = chooseDirectSize(sizes)
            mutableState.value = mutableState.value.copy(
                status = UiText.of(R.string.uvc_device_open),
                supportedSizes = sizes.joinToString { "${it.width}x${it.height}@${it.fps}" },
                supportedFormats = formats.joinToString(),
                selectedSize = selectedSize?.let { "${it.width}x${it.height}@${it.fps}" } ?: "automatico",
            )
            runCatching {
                if (selectedSize == null) cameraHelper.openCamera() else cameraHelper.openCamera(selectedSize)
            }.onFailure(::fail)
        }

        override fun onCameraOpen(device: UsbDevice) {
            if (!running || selectedDeviceId != device.deviceId) return
            val cameraHelper = helper ?: return
            runCatching {
                val size = cameraHelper.previewSize
                    ?: throw LocalizedException(UiText.of(R.string.uvc_no_preview_size))
                mutableState.value = mutableState.value.copy(
                    status = UiText.of(R.string.uvc_active),
                    width = size.width,
                    height = size.height,
                    selectedSize = "${size.width}x${size.height}@${size.fps}",
                )
                cameraHelper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGBX)
                cameraHelper.startPreview()
                TelemetryLog.event("UVC_DIRECT", "started size=${size.width}x${size.height}@${size.fps} pixel=RGBX")
            }.onFailure(::fail)
        }

        override fun onCameraClose(device: UsbDevice) {
            if (running) mutableState.value = mutableState.value.copy(status = UiText.of(R.string.uvc_stream_closed))
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            if (selectedDeviceId == device.deviceId) {
                selectedDeviceId = null
                mutableState.value = mutableState.value.copy(status = UiText.of(R.string.uvc_disconnected))
            }
        }

        override fun onCancel(device: UsbDevice) {
            fail(LocalizedException(UiText.of(R.string.uvc_permission_denied)))
        }

        override fun onError(device: UsbDevice, error: CameraException) {
            val detail = if (error.code == CameraException.CAMERA_OPEN_ERROR_BUSY) {
                UiText.of(R.string.uvc_busy)
            } else {
                UiText.of(R.string.uvc_error, error.code, error.message ?: "--")
            }
            fail(LocalizedException(detail))
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        applicationContext = context.applicationContext
        firstFrameElapsed = 0L
        selectedDeviceId = null
        GrabadoraHdCameraClient.pauseVisionProbeForDirectUsb()
        mutableState.value = UvcDirectVisionState(active = true, status = UiText.of(R.string.uvc_searching))
        TelemetryLog.event("UVC_DIRECT", "requested")

        val cameraHelper = CameraHelper()
        helper = cameraHelper
        runCatching {
            cameraHelper.setStateCallback(stateCallback)
            val usbManager = context.getSystemService(UsbManager::class.java)
            val device = cameraHelper.deviceList.firstOrNull(::looksLikeVideoDevice)
                ?: usbManager.deviceList.values.firstOrNull(::looksLikeVideoDevice)
                ?: throw LocalizedException(UiText.of(R.string.uvc_no_interface))
            select(device)
        }.onFailure(::fail)
    }

    fun stop(reason: UiText = UiText.of(R.string.uvc_stopped)) {
        if (!running && helper == null) return
        running = false
        TelemetryLog.event("UVC_DIRECT", "stopped reason=${applicationContext?.let { reason.english(it) } ?: reason}")
        runCatching { helper?.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGBX) }
        runCatching { helper?.stopPreview() }
        runCatching { helper?.closeCamera() }
        runCatching { helper?.setStateCallback(null) }
        runCatching { helper?.release() }
        helper = null
        selectedDeviceId = null
        mutableState.value = mutableState.value.copy(active = false, status = reason)
        GrabadoraHdCameraClient.resumeVisionProbeAfterDirectUsb()
    }

    private fun select(device: UsbDevice) {
        if (!running || selectedDeviceId == device.deviceId) return
        selectedDeviceId = device.deviceId
        mutableState.value = mutableState.value.copy(
            status = UiText.of(R.string.uvc_requesting),
            device = "VID:%04x PID:%04x id=%d".format(Locale.US, device.vendorId, device.productId, device.deviceId),
        )
        TelemetryLog.event("UVC_DIRECT", "select ${mutableState.value.device}")
        helper?.selectDevice(device)
    }

    private fun onFrame(buffer: ByteBuffer) {
        if (!running) return
        val current = mutableState.value
        if (current.width <= 0 || current.height <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (firstFrameElapsed == 0L) firstFrameElapsed = now
        val accepted = VisionFrameHub.offerRgbx(
            buffer = buffer,
            sourceWidth = current.width,
            sourceHeight = current.height,
            sourceName = SOURCE_NAME,
            capturedElapsed = now,
        )
        val frames = current.frames + 1L
        val elapsed = (now - firstFrameElapsed).coerceAtLeast(1L)
        mutableState.value = current.copy(
            status = if (accepted) UiText.of(R.string.uvc_active) else UiText.Raw(VisionFrameHub.state.value.status),
            frames = frames,
            fps = if (frames <= 1L) 0f else (frames - 1L) * 1_000f / elapsed,
            lastFrameElapsedRealtime = now,
            lastBufferBytes = buffer.remaining(),
        )
    }

    private fun fail(error: Throwable) {
        val message = error.uiText()
        TelemetryLog.event("UVC_DIRECT", "error=${error.javaClass.simpleName}:${applicationContext?.let { message.english(it) } ?: message}")
        mutableState.value = mutableState.value.copy(
            status = message,
            errors = mutableState.value.errors + 1,
        )
    }
}

internal data class UvcDirectVisionState(
    val active: Boolean = false,
    val status: UiText = UiText.of(R.string.uvc_inactive),
    val device: String = "--",
    val selectedSize: String = "--",
    val supportedSizes: String = "--",
    val supportedFormats: String = "--",
    val width: Int = 0,
    val height: Int = 0,
    val frames: Long = 0L,
    val fps: Float = 0f,
    val lastFrameElapsedRealtime: Long = 0L,
    val lastBufferBytes: Int = 0,
    val errors: Int = 0,
)

internal fun looksLikeVideoDevice(device: UsbDevice): Boolean {
    if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    return (0 until device.interfaceCount).any { index ->
        device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
    }
}

internal fun chooseDirectSize(sizes: List<Size>): Size? {
    if (sizes.isEmpty()) return null
    return sizes.sortedWith(
        compareByDescending<Size> { it.width == 1280 && it.height == 720 }
            .thenByDescending { it.width == 960 && it.height == 540 }
            .thenByDescending { it.width == 640 && it.height == 480 }
            .thenByDescending { it.fps }
            .thenBy { it.width * it.height },
    ).first()
}
