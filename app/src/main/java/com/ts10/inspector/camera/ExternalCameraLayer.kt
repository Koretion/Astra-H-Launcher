package com.ts10.inspector.camera

import androidx.annotation.StringRes
import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size as AndroidSize
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size as UvcSize
import com.ts10.inspector.ui.LauncherLayout

@Composable
internal fun ExternalCameraLayer(
    layout: LauncherLayout,
    requestPermission: Boolean = true,
    modifier: Modifier = Modifier
        .offset(y = layout.dp(162))
        .size(layout.dp(308), layout.dp(173.25)),
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = if (requestPermission) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionGranted = it
        }
    } else {
        null
    }

    LaunchedEffect(permissionGranted, requestPermission) {
        if (!permissionGranted && requestPermission) {
            permissionLauncher?.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (permissionGranted) {
            ExternalCameraPreview()
        } else {
            CameraMessage(stringResource(R.string.cam_permission_needed), layout)
        }
    }
}

@Composable
private fun ExternalCameraPreview() {
    val context = LocalContext.current
    val camera2Controller = remember { ExternalCameraController(context) }
    var useUvcFallback by remember { mutableStateOf(false) }

    if (camera2Controller.hasExternalCamera && !useUvcFallback) {
        Camera2ExternalCameraPreview(camera2Controller) {
            useUvcFallback = true
        }
    } else {
        UvcExternalCameraPreview()
    }
    DisposableEffect(camera2Controller) {
        onDispose(camera2Controller::close)
    }
}

@Composable
private fun Camera2ExternalCameraPreview(
    controller: ExternalCameraController,
    onCamera2Failed: () -> Unit,
) {
    AndroidView(
        factory = { textureView ->
            TextureView(textureView).also { view ->
                controller.attach(view, onCamera2Failed)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun UvcExternalCameraPreview() {
    val context = LocalContext.current
    val controller = remember { UvcCameraController(context.applicationContext) }
    val searchingMessage = stringResource(R.string.cam_searching)
    var statusMessage by remember { mutableStateOf<String?>(searchingMessage) }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).also { view ->
                controller.attach(view) { statusMessage = it }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    statusMessage?.let { message ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
            )
        }
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }
}

@Composable
private fun CameraMessage(message: String, layout: LauncherLayout) {
    Text(text = message, color = Color.White, fontSize = layout.sp(13))
}

private class ExternalCameraController(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING] ==
            CameraCharacteristics.LENS_FACING_EXTERNAL
    }
    private val previewSize = cameraId?.let(::choosePreviewSize)
    private val thread = HandlerThread("TS10-external-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var failed = false

    val hasExternalCamera: Boolean get() = cameraId != null

    fun attach(textureView: TextureView, onFailure: () -> Unit) {
        if (cameraId == null) return
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                open(texture, width, height, onFailure)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }
        }
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let {
                open(it, textureView.width, textureView.height, onFailure)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
        onFailure: () -> Unit,
    ) {
        val id = cameraId ?: return
        val configuredSize = previewSize ?: AndroidSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        texture.setDefaultBufferSize(configuredSize.width, configuredSize.height)
        surface = Surface(texture)
        runCatching {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, onFailure)
                }

                override fun onDisconnected(camera: CameraDevice) = fail(camera, onFailure)

                override fun onError(camera: CameraDevice, error: Int) = fail(camera, onFailure)
            }, handler)
        }.onFailure {
            closeCamera()
            switchToUvc(onFailure)
        }
    }

    private fun createSession(camera: CameraDevice, onFailure: () -> Unit) {
        val previewSurface = surface ?: return
        runCatching {
            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        runCatching {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                            }.build()
                            captureSession.setRepeatingRequest(request, null, handler)
                        }.onFailure { fail(camera, onFailure) }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        fail(camera, onFailure)
                    }
                },
                handler,
            )
        }.onFailure { fail(camera, onFailure) }
    }

    private fun choosePreviewSize(id: String): AndroidSize? {
        val sizes = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            .orEmpty()
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun fail(camera: CameraDevice, onFailure: () -> Unit) {
        camera.close()
        if (device === camera) device = null
        surface?.release()
        surface = null
        switchToUvc(onFailure)
    }

    private fun switchToUvc(onFailure: () -> Unit) {
        if (failed) return
        failed = true
        mainHandler.post(onFailure)
    }

    private fun closeCamera() {
        session?.close()
        session = null
        device?.close()
        device = null
        surface?.release()
        surface = null
    }

    fun close() {
        closeCamera()
        thread.quitSafely()
    }
}

private class UvcCameraController(context: Context) {
    private val appContext = context

    private fun t(@StringRes id: Int, vararg args: Any): String = AppLocale.string(appContext, id, *args)
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var helper: CameraHelper? = null
    private var previewView: SurfaceView? = null
    private var selectedDeviceId: Int? = null
    private var cameraOpened = false
    private var surfaceAdded = false
    private var closed = false
    private var attemptedFallbackOpen = false
    private var retryScheduled = false
    private var updateStatus: (String?) -> Unit = {}

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            addSurfaceIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            removeSurface()
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            attemptedFallbackOpen = false
            retryScheduled = false
            selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            postStatus(t(R.string.cam_opening))
            openSelectedDevice(device, preferCompatibility = false)
        }

        override fun onCameraOpen(device: UsbDevice) {
            cameraOpened = true
            runCatching {
                addSurfaceIfReady()
                helper?.startPreview()
            }.onSuccess {
                postStatus(null)
            }.onFailure {
                postOpenError()
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraOpened = false
            surfaceAdded = false
            if (!closed && retryOpenWithFallback(t(R.string.cam_closed_on_start))) return
            if (!closed) postStatus(t(R.string.cam_closed))
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            if (selectedDeviceId == device.deviceId) {
                selectedDeviceId = null
                cameraOpened = false
                surfaceAdded = false
                if (!closed) postStatus(t(R.string.cam_disconnected))
            }
        }

        override fun onCancel(device: UsbDevice) {
            postStatus(t(R.string.cam_usb_denied))
        }

        override fun onError(device: UsbDevice, error: CameraException) {
            if (!closed && error.code != CameraException.CAMERA_OPEN_ERROR_BUSY &&
                retryOpenWithFallback(t(R.string.cam_uvc_retry, error.code))
            ) {
                return
            }
            postStatus(t(R.string.cam_uvc_error, error.code, error.message ?: t(R.string.cam_unknown)))
        }
    }

    fun attach(view: SurfaceView, status: (String?) -> Unit) {
        previewView = view
        updateStatus = status
        view.holder.addCallback(surfaceCallback)

        val cameraHelper = CameraHelper()
        helper = cameraHelper
        cameraHelper.setStateCallback(stateCallback)

        runCatching { cameraHelper.deviceList.firstOrNull() }
            .onSuccess { device ->
                val selectedDevice = device ?: findDirectUsbCamera()
                if (selectedDevice != null) {
                    selectDevice(selectedDevice)
                }
            }
            .onFailure {
                val selectedDevice = findDirectUsbCamera()
                if (selectedDevice != null) {
                    selectDevice(selectedDevice)
                } else {
                    postStatus(t(R.string.cam_not_detected))
                }
            }
        view.postDelayed({
            if (!closed && selectedDeviceId == null) {
                updateStatus(usbDiagnosticMessage())
            }
        }, USB_DISCOVERY_TIMEOUT_MS)
    }

    private fun selectDevice(device: UsbDevice) {
        if (closed || selectedDeviceId == device.deviceId) return
        attemptedFallbackOpen = false
        retryScheduled = false
        selectedDeviceId = device.deviceId
        postStatus(t(R.string.cam_requesting_permission))
        runCatching { helper?.selectDevice(device) }
            .onFailure { postOpenError() }
    }

    private fun retryOpenWithFallback(reason: String): Boolean {
        if (attemptedFallbackOpen || retryScheduled) return false
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == selectedDeviceId } ?: return false
        attemptedFallbackOpen = true
        retryScheduled = true
        postStatus(t(R.string.cam_retry_compatible, reason))
        runCatching { helper?.closeCamera() }
        previewView?.postDelayed({
            retryScheduled = false
            if (closed) return@postDelayed
            cameraOpened = false
            surfaceAdded = false
            postStatus(t(R.string.cam_reopening_compatible))
            openSelectedDevice(device, preferCompatibility = true)
        }, USB_RETRY_DELAY_MS)
        return true
    }

    private fun openSelectedDevice(device: UsbDevice, preferCompatibility: Boolean) {
        if (closed || selectedDeviceId != device.deviceId) return
        runCatching {
            val selectedSize = choosePreviewSize(helper?.supportedSizeList.orEmpty(), preferCompatibility)
            if (selectedSize != null) {
                postStatus(t(R.string.cam_resolution, selectedSize.width, selectedSize.height))
                helper?.openCamera(selectedSize)
            } else {
                helper?.openCamera()
            }
        }.onFailure { postOpenError() }
    }

    private fun addSurfaceIfReady() {
        val view = previewView ?: return
        val surface = view.holder.surface
        if (!cameraOpened || surfaceAdded || !surface.isValid) return
        helper?.addSurface(view, false)
        surfaceAdded = true
    }

    private fun removeSurface() {
        val view = previewView ?: return
        if (!surfaceAdded) return
        runCatching { helper?.removeSurface(view) }
        surfaceAdded = false
    }

    private fun postStatus(message: String?) {
        previewView?.post {
            if (!closed) updateStatus(message)
        }
    }

    private fun postOpenError() {
        postStatus(t(R.string.cam_open_failed))
    }

    private fun choosePreviewSize(sizes: List<UvcSize>, preferCompatibility: Boolean = false): UvcSize? {
        if (sizes.isEmpty()) return null
        val exactSafe = sizes.firstOrNull { it.width == 640 && it.height == 480 }
        val exactWide = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
        if (preferCompatibility) return exactSafe ?: exactWide ?: sizes.minByOrNull { it.width * it.height }
        return sizes
            .sortedWith(
                compareByDescending<UvcSize> { it.width == 1280 && it.height == 720 }
                    .thenByDescending { it.width == 960 && it.height == 540 }
                    .thenByDescending { it.width == 640 && it.height == 480 }
                    .thenByDescending { it.type }
                    .thenByDescending { it.width * it.height },
            )
            .firstOrNull()
    }

    private fun findDirectUsbCamera(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(::looksLikeUsbCamera)

    private fun looksLikeUsbCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun usbDiagnosticMessage(): String {
        val device = usbManager.deviceList.values.firstOrNull()
            ?: return t(R.string.cam_not_detected)
        if (looksLikeUsbCamera(device)) {
            return t(R.string.cam_busy, device.vendorId.toString(16), device.productId.toString(16))
        }
        return t(R.string.cam_not_uvc, device.vendorId.toString(16), device.productId.toString(16))
    }

    fun close() {
        if (closed) return
        closed = true
        previewView?.holder?.removeCallback(surfaceCallback)
        removeSurface()
        runCatching { helper?.setStateCallback(null) }
        runCatching { helper?.release() }
        helper = null
        previewView = null
    }

    private companion object {
        const val USB_DISCOVERY_TIMEOUT_MS = 1_500L
        const val USB_RETRY_DELAY_MS = 600L
    }
}
