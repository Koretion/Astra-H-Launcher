package com.ts10.inspector.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ts10.inspector.dashboard.ApplicationsOverlay
import com.ts10.inspector.dashboard.DriverProfileOverlay
import com.ts10.inspector.dashboard.LocalDashboardVisible
import com.ts10.inspector.dashboard.MediaSourcesOverlay
import com.ts10.inspector.dashboard.VehiclePanel
import com.ts10.inspector.dashboard.VehicleTopIndicators
import com.ts10.inspector.dashboard.WeatherOverlay
import com.ts10.inspector.panel.ExclusiveLauncherSession
import com.ts10.inspector.panel.PanelApplicationSession
import com.ts10.inspector.panel.bringLauncherToForeground
import com.ts10.inspector.panel.launchConfiguredApplication
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.phone.BluetoothCallState
import com.ts10.inspector.phone.PhoneOverlay
import com.ts10.inspector.settings.DashboardSettingsOverlay
import com.ts10.inspector.ui.AstraDashboardTheme
import com.ts10.inspector.ui.DesignGrid
import com.ts10.inspector.ui.LauncherLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

class HybridOverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dashboardView: ComposeView? = null
    private var coolantIndicatorView: ComposeView? = null
    private var modalHostView: ComposeView? = null
    private val dashboardVisible = mutableStateOf(true)
    private val activeModal = mutableStateOf(OverlayModal.NONE)
    private var chromeMode = LauncherChromeMode.FULL
    private var windowRecoveryScheduled = false
    private var systemDialogsReceiverRegistered = false
    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON)
            Log.d(TAG, "System dialogs closed: reason=$reason")
            when (reason) {
                SYSTEM_DIALOG_REASON_RECENT_APPS -> {
                    chromeMode = reduceLauncherChrome(
                        LauncherChromeEvent.OPEN_RECENTS,
                    )
                    removeAllWindows()
                }
                SYSTEM_DIALOG_REASON_HOME_KEY -> {
                    if (ExclusiveLauncherSession.active) {
                        ExclusiveLauncherSession.complete(this@HybridOverlayService)
                        return
                    }
                    chromeMode = reduceLauncherChrome(
                        LauncherChromeEvent.SHOW_HOME,
                    )
                    showDashboard()
                    PanelApplicationSession.restoreForHome(this@HybridOverlayService)
                }
            }
        }
    }
    override fun onCreate() {
        savedStateController.performAttach()
        super.onCreate()
        savedStateController.performRestore(null)
        windowManager = getSystemService(WindowManager::class.java)
        // Reconstruct visibility from the logical panel interruption, not from an independent
        // overlay flag that can become stale. This keeps a restarted service below an active
        // full-screen task while allowing normal launcher restarts to rebuild their windows.
        chromeMode = if (PanelApplicationSession.isRestorePending(this)) {
            LauncherChromeMode.TOP_BAR
        } else {
            LauncherChromeMode.FULL
        }
        registerSystemDialogsReceiver()
        // El panel se dibuja primero con el estado de vista previa; los clientes (USB, BLE,
        // GPS, camaras) arrancan cuando ya hay algo en pantalla.
        lifecycleScope.launch {
            awaitFirstWindowDrawn()
            LauncherRuntime.start(this@HybridOverlayService)
        }
        observeIncomingCalls()
        runCatching {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        }.onFailure { error ->
            Log.e(TAG, "Unable to enter foreground mode", error)
        }
    }

    private fun observeIncomingCalls() {
        AndroidBluetoothPhoneClient.connect(this)
        lifecycleScope.launch {
            AndroidBluetoothPhoneClient.state
                .map { it.callState }
                .distinctUntilChanged()
                .collect { callState ->
                    if (callState == BluetoothCallState.RINGING) {
                        chromeMode = LauncherChromeMode.FULL
                        showDashboard()
                        showPhone()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        runCatching {
            when (intent?.action) {
                ACTION_SHOW_DASHBOARD -> {
                    chromeMode = reduceLauncherChrome(
                        LauncherChromeEvent.SHOW_HOME,
                    )
                    showDashboard()
                }
                ACTION_SHOW_APPLICATIONS -> showApplications()
                ACTION_SHOW_PHONE -> showPhone()
                ACTION_SHOW_SETTINGS -> showSettings()
                ACTION_SHOW_DRIVER_PROFILES -> showDriverProfiles()
                ACTION_SHOW_MEDIA_SOURCES -> showMediaSources()
                ACTION_SHOW_WEATHER -> showWeather()
                ACTION_TOGGLE_APPLICATIONS -> toggleApplications()
                ACTION_TOGGLE_PHONE -> togglePhone()
                ACTION_TOGGLE_MEDIA_SOURCES -> toggleMediaSources()
                ACTION_TOGGLE_SETTINGS -> toggleSettings()
                ACTION_TOGGLE_WEATHER -> toggleWeather()
                ACTION_HIDE_MODAL -> hideModal()
                ACTION_HIDE_DRIVER_PROFILES_IF_VISIBLE -> {
                    if (activeModal.value == OverlayModal.DRIVER_PROFILE) hideModal()
                }
                ACTION_SHOW_TOP_BAR_ONLY -> {
                    chromeMode = reduceLauncherChrome(
                        LauncherChromeEvent.OPEN_EXPANDED_APP,
                    )
                    hideModal()
                    modalHostView?.let(::removeWindow)
                    modalHostView = null
                    hideDashboard()
                    showCoolantIndicator()
                }
                ACTION_HIDE_FOR_EXCLUSIVE_SCREEN -> {
                    chromeMode = reduceLauncherChrome(
                        LauncherChromeEvent.OPEN_EXCLUSIVE_APP,
                    )
                    hideModal()
                    modalHostView?.let(::removeWindow)
                    modalHostView = null
                    coolantIndicatorView?.let(::removeWindow)
                    coolantIndicatorView = null
                    hideDashboard()
                }
                ACTION_STOP -> {
                    removeAllWindows()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ACTION_START, null -> showChromeForCurrentMode()
                else -> showChromeForCurrentMode()
            }
        }.onFailure { error ->
            Log.e(TAG, "Overlay command failed: ${intent?.action}", error)
            removeAllWindows()
            if (intent?.action != ACTION_STOP) {
                scheduleWindowRecovery()
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeAllWindows()
        if (systemDialogsReceiverRegistered) {
            runCatching { unregisterReceiver(systemDialogsReceiver) }
            systemDialogsReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerSystemDialogsReceiver() {
        if (systemDialogsReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            systemDialogsReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        systemDialogsReceiverRegistered = true
    }

    private fun showDashboard() {
        // HOME can be reasserted by the TS10 while the driver is using a modal (for example when
        // focus changes as the keyboard opens). Rebuilding the dashboard must not turn that
        // system event into an implicit dismissal: modals close only through their backdrop or X.
        // Paths which actually remove the dashboard call hideModal() themselves.
        if (chromeMode != LauncherChromeMode.FULL || !Settings.canDrawOverlays(this)) return
        dashboardView?.let { existing ->
            setDashboardWindowVisible(existing, true)
            showCoolantIndicator()
            return
        }
        runCatching {
            val layout = screenLayout()
            dashboardVisible.value = true
            val view = composeView {
                CompositionLocalProvider(LocalDashboardVisible provides dashboardVisible.value) {
                    val mediaActions = LauncherRuntime.actions(this)
                    VehiclePanel(
                        actions = mediaActions.copy(
                            onWeather = ::toggleWeather,
                            onPhone = ::togglePhone,
                            onVoiceAssistant = foregroundAction(mediaActions.onVoiceAssistant),
                            onNavigation = foregroundAction(mediaActions.onNavigation),
                            onApplications = ::toggleApplications,
                            // Full-screen camera launch must not reorder HOME first: that creates a
                            // competing delayed SHOW command which can put this overlay over the camera.
                            onCamera = mediaActions.onOpenCameraFullScreen,
                            onOpenCameraFullScreen = mediaActions.onOpenCameraFullScreen,
                            onOpenMedia = foregroundAction(mediaActions.onOpenMedia),
                            onRepeat = foregroundAction(mediaActions.onRepeat),
                            onPrevious = foregroundAction(mediaActions.onPrevious),
                            onStop = foregroundAction(mediaActions.onStop),
                            onPlayPause = foregroundAction(mediaActions.onPlayPause),
                            onNext = foregroundAction(mediaActions.onNext),
                            onMore = foregroundAction(mediaActions.onMore),
                            onShuffle = foregroundAction(mediaActions.onShuffle),
                            onSelectMediaSource = ::toggleMediaSources,
                        ),
                        layout = layout,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val density = resources.displayMetrics.density
            val displayBounds = displayBounds()
            val panelWidthPx = (DesignGrid.VehiclePanelWidth * layout.scale * density).roundToInt()
            windowManager.addView(
                view,
                overlayParams(
                    width = panelWidthPx,
                    height = displayBounds.height(),
                    touchable = true,
                    placeBelowIme = true,
                    // Anchor to the physical top: SystemUI clips the top edge instead of
                    // displacing the panel and cutting off its multimedia controls below.
                    coverSystemBars = true,
                ),
            )
            dashboardView = view
            markFirstWindowDrawnAfterNextFrame()
            showCoolantIndicator(layout, displayBounds, density)
            windowRecoveryScheduled = false
        }.onFailure { error ->
            Log.e(TAG, "Unable to create launcher windows", error)
            removeAllWindows()
            scheduleWindowRecovery()
        }
    }

    private fun scheduleWindowRecovery() {
        if (chromeMode == LauncherChromeMode.HIDDEN || windowRecoveryScheduled) return
        windowRecoveryScheduled = true
        mainHandler.postDelayed(
            {
                windowRecoveryScheduled = false
                showChromeForCurrentMode()
            },
            WINDOW_RECOVERY_DELAY_MS,
        )
    }

    private fun showChromeForCurrentMode() {
        when (chromeMode) {
            LauncherChromeMode.FULL -> showDashboard()
            LauncherChromeMode.TOP_BAR -> showCoolantIndicator()
            LauncherChromeMode.HIDDEN -> Unit
        }
    }

    /**
     * Oculta el panel sin destruirlo. Recrearlo al volver de una app a pantalla completa
     * obligaba a recomponer y decodificar de nuevo las imagenes del vehiculo, dejando la
     * pantalla vacia durante ese tiempo.
     */
    private fun hideDashboard() {
        dashboardView?.let { setDashboardWindowVisible(it, false) }
    }

    private fun setDashboardWindowVisible(view: View, visible: Boolean) {
        dashboardVisible.value = visible
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val flags = if (visible) params.flags and notTouchable.inv() else params.flags or notTouchable
        // Alpha 0: Android 12+ no deja pasar toques a la app de debajo si la ventana es opaca.
        val alpha = if (visible) 1f else 0f
        view.isEnabled = visible
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (params.flags == flags && params.alpha == alpha) return
        params.flags = flags
        params.alpha = alpha
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { error -> Log.w(TAG, "Unable to change dashboard visibility", error) }
    }

    private fun markFirstWindowDrawnAfterNextFrame() {
        if (firstWindowDrawn.value) return
        // El callback de Choreographer corre antes de dibujar; el post lo deja tras el frame.
        Choreographer.getInstance().postFrameCallback {
            mainHandler.post { firstWindowDrawn.value = true }
        }
    }

    private fun showCoolantIndicator() {
        if (chromeMode == LauncherChromeMode.HIDDEN || !Settings.canDrawOverlays(this)) return
        val layout = screenLayout()
        showCoolantIndicator(layout, displayBounds(), resources.displayMetrics.density)
    }

    private fun showCoolantIndicator(
        layout: LauncherLayout,
        displayBounds: Rect,
        density: Float,
    ) {
        if (coolantIndicatorView != null) return
        val indicatorWidthPx = (DesignGrid.CoolantIndicatorWidth * layout.scale * density).roundToInt()
        val indicatorHeightPx = (DesignGrid.VehicleTopIndicatorHeight * layout.scale * density).roundToInt()
        val view = composeView {
            VehicleTopIndicators(
                layout = layout,
                modifier = Modifier.fillMaxSize(),
            )
        }
        windowManager.addView(
            view,
            overlayParams(
                width = indicatorWidthPx,
                height = indicatorHeightPx,
                touchable = false,
                x = displayBounds.left + (displayBounds.width() - indicatorWidthPx) / 2,
                y = displayBounds.top,
                coverSystemBars = true,
            ),
        )
        coolantIndicatorView = view
        markFirstWindowDrawnAfterNextFrame()
    }

    private fun showApplications() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.APPLICATIONS)
    }

    private fun showWeather() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.WEATHER)
    }

    private fun showSettings() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.SETTINGS)
    }

    private fun showDriverProfiles() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.DRIVER_PROFILE)
    }

    private fun showMediaSources() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.MEDIA_SOURCES)
    }

    private fun showPhone() {
        if (!Settings.canDrawOverlays(this)) return
        showModal(OverlayModal.PHONE)
    }

    private fun showModal(modal: OverlayModal) {
        activeModal.value = modal
        if (modalHostView == null) {
            addModalHost()
        } else {
            setModalHostTouchable(true)
        }
    }

    private fun addModalHost() {
        val layout = screenLayout()
        val view = composeView {
            when (activeModal.value) {
                OverlayModal.NONE -> Unit
                OverlayModal.WEATHER -> {
                    val state by LauncherRuntime.weatherState.collectAsStateWithLifecycle()
                    WeatherOverlay(state = state, layout = layout, onDismiss = ::hideModal)
                }
                OverlayModal.APPLICATIONS -> ApplicationsOverlay(
                    layout = layout,
                    onDismiss = ::hideModal,
                    onLaunchApplication = { packageName ->
                        hideModal()
                        launchConfiguredApplication(this@HybridOverlayService, packageName)
                    },
                    onOpenSettings = ::showSettings,
                )
                OverlayModal.PHONE -> PhoneOverlay(layout = layout, onDismiss = ::hideModal)
                OverlayModal.SETTINGS -> DashboardSettingsOverlay(
                    layout = layout,
                    onDismiss = ::hideModal,
                )
                OverlayModal.DRIVER_PROFILE -> DriverProfileOverlay(
                    layout = layout,
                    onDismiss = ::hideModal,
                )
                OverlayModal.MEDIA_SOURCES -> MediaSourcesOverlay(
                    layout = layout,
                    onDismiss = ::hideModal,
                    onSelect = { packageName ->
                        hideModal()
                        bringLauncherToForeground(this@HybridOverlayService)
                        mainHandler.postDelayed(
                            {
                                LauncherRuntime.selectMediaSource(
                                    this@HybridOverlayService,
                                    packageName,
                                )
                            },
                            EXTERNAL_LAUNCH_DELAY_MS,
                        )
                    },
                )
            }
        }
        windowManager.addView(
            view,
            overlayParams(
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                touchable = true,
                focusable = true,
            ),
        )
        modalHostView = view
    }

    private fun hideModal() {
        activeModal.value = OverlayModal.NONE
        setModalHostTouchable(false)
    }

    private fun setModalHostTouchable(touchable: Boolean) {
        val view = modalHostView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val updatedFlags = if (touchable) {
            params.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params.flags == updatedFlags) return
        params.flags = updatedFlags
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { error -> Log.e(TAG, "Unable to update modal touch state", error) }
    }

    private fun toggleApplications() {
        toggleModal(OverlayModal.APPLICATIONS)
    }

    private fun toggleWeather() {
        toggleModal(OverlayModal.WEATHER)
    }

    private fun togglePhone() {
        toggleModal(OverlayModal.PHONE)
    }

    private fun toggleMediaSources() {
        toggleModal(OverlayModal.MEDIA_SOURCES)
    }

    private fun toggleSettings() {
        toggleModal(OverlayModal.SETTINGS)
    }

    private fun toggleModal(modal: OverlayModal) {
        if (activeModal.value == modal) hideModal() else showModal(modal)
    }

    private fun foregroundAction(action: () -> Unit): () -> Unit = {
        bringLauncherToForeground(this)
        action()
    }

    private fun removeAllWindows() {
        hideModal()
        modalHostView?.let(::removeWindow)
        modalHostView = null
        coolantIndicatorView?.let(::removeWindow)
        coolantIndicatorView = null
        dashboardView?.let(::removeWindow)
        dashboardView = null
    }

    private fun removeWindow(view: View) {
        // Disable input before detaching. Some TS10 builds defer or reject removeViewImmediate
        // while dispatching ACTION_UP; an old overlay must never remain as a touch target.
        view.isEnabled = false
        view.isClickable = false
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params != null && params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { error -> Log.w(TAG, "Unable to disable removed overlay input", error) }
        }
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { error -> Log.w(TAG, "Unable to remove overlay window", error) }
    }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@HybridOverlayService)
        setViewTreeSavedStateRegistryOwner(this@HybridOverlayService)
        setContent { AstraDashboardTheme { content() } }
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        touchable: Boolean,
        focusable: Boolean = false,
        placeBelowIme: Boolean = false,
        coverSystemBars: Boolean = false,
        x: Int = 0,
        y: Int = 0,
    ) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (coverSystemBars) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            } else {
                0
            }) or
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                // Keep the fixed launcher chrome behind the system IME so Maps text entry
                // can use the full keyboard width instead of being covered on the left.
                (if (placeBelowIme) WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM else 0) or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (focusable) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }

    private fun screenLayout(): LauncherLayout {
        val density = resources.displayMetrics.density
        // A service inherits the launcher's split-task metrics. Overlay windows span the
        // complete display, so their design scale must use the display's maximum bounds.
        val bounds = displayBounds()
        return LauncherLayout(
            scale = min(
                bounds.width() / density / DesignGrid.Width,
                bounds.height() / density / DesignGrid.Height,
            ),
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(com.ts10.inspector.R.string.hybrid_overlay_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.maximumWindowMetrics.bounds
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(com.ts10.inspector.R.mipmap.ic_launcher)
        .setContentTitle(getString(com.ts10.inspector.R.string.hybrid_overlay_active))
        .setOngoing(true)
        .addAction(
            0,
            getString(com.ts10.inspector.R.string.hybrid_overlay_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, HybridOverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private val firstWindowDrawn = MutableStateFlow(false)
        private const val FIRST_WINDOW_TIMEOUT_MS = 1_500L

        /** Espera a que el panel o la barra superior se hayan dibujado, con un tiempo maximo. */
        internal suspend fun awaitFirstWindowDrawn() {
            withTimeoutOrNull(FIRST_WINDOW_TIMEOUT_MS) { firstWindowDrawn.first { it } }
        }

        private const val ACTION_START = "com.ts10.inspector.overlay.START"
        private const val ACTION_SHOW_DASHBOARD = "com.ts10.inspector.overlay.SHOW_DASHBOARD"
        private const val ACTION_SHOW_APPLICATIONS = "com.ts10.inspector.overlay.SHOW_APPLICATIONS"
        private const val ACTION_SHOW_PHONE = "com.ts10.inspector.overlay.SHOW_PHONE"
        private const val ACTION_SHOW_SETTINGS = "com.ts10.inspector.overlay.SHOW_SETTINGS"
        private const val ACTION_SHOW_DRIVER_PROFILES = "com.ts10.inspector.overlay.SHOW_DRIVER_PROFILES"
        private const val ACTION_TOGGLE_SETTINGS = "com.ts10.inspector.overlay.TOGGLE_SETTINGS"
        private const val ACTION_SHOW_MEDIA_SOURCES = "com.ts10.inspector.overlay.SHOW_MEDIA_SOURCES"
        private const val ACTION_TOGGLE_APPLICATIONS = "com.ts10.inspector.overlay.TOGGLE_APPLICATIONS"
        private const val ACTION_TOGGLE_PHONE = "com.ts10.inspector.overlay.TOGGLE_PHONE"
        private const val ACTION_TOGGLE_MEDIA_SOURCES = "com.ts10.inspector.overlay.TOGGLE_MEDIA_SOURCES"
        private const val ACTION_HIDE_MODAL = "com.ts10.inspector.overlay.HIDE_MODAL"
        private const val ACTION_HIDE_DRIVER_PROFILES_IF_VISIBLE =
            "com.ts10.inspector.overlay.HIDE_DRIVER_PROFILES_IF_VISIBLE"
        private const val ACTION_SHOW_TOP_BAR_ONLY = "com.ts10.inspector.overlay.SHOW_TOP_BAR_ONLY"
        private const val ACTION_HIDE_FOR_EXCLUSIVE_SCREEN =
            "com.ts10.inspector.overlay.HIDE_FOR_EXCLUSIVE_SCREEN"
        private const val ACTION_STOP = "com.ts10.inspector.overlay.STOP"
        private const val ACTION_SHOW_WEATHER = "com.ts10.inspector.overlay.SHOW_WEATHER"
        private const val ACTION_TOGGLE_WEATHER = "com.ts10.inspector.overlay.TOGGLE_WEATHER"
        private const val ACTION_BACKCAR_START = "com.qf.action.BACKCAR_START"
        private const val ACTION_BACKCAR_STOP = "com.qf.action.BACKCAR_STOP"
        private const val NOTIFICATION_CHANNEL_ID = "hybrid_overlay"
        private const val NOTIFICATION_ID = 410
        private const val EXTERNAL_LAUNCH_DELAY_MS = 180L
        private const val WINDOW_RECOVERY_DELAY_MS = 350L
        private const val TAG = "TS10HybridOverlay"
        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps"
        private const val SYSTEM_DIALOG_REASON_HOME_KEY = "homekey"
        fun start(context: Context) = send(context, ACTION_START)
        fun showDashboard(context: Context) = send(context, ACTION_SHOW_DASHBOARD)
        fun showApplications(context: Context) = send(context, ACTION_SHOW_APPLICATIONS)
        fun showWeather(context: Context) = send(context, ACTION_SHOW_WEATHER)
        fun showPhone(context: Context) = send(context, ACTION_SHOW_PHONE)
        fun showSettings(context: Context) = send(context, ACTION_SHOW_SETTINGS)
        fun showDriverProfiles(context: Context) = send(context, ACTION_SHOW_DRIVER_PROFILES)
        fun hideDriverProfilesIfVisible(context: Context) =
            send(context, ACTION_HIDE_DRIVER_PROFILES_IF_VISIBLE)
        fun showMediaSources(context: Context) = send(context, ACTION_SHOW_MEDIA_SOURCES)
        fun toggleApplications(context: Context) = send(context, ACTION_TOGGLE_APPLICATIONS)
        fun toggleWeather(context: Context) = send(context, ACTION_TOGGLE_WEATHER)
        fun togglePhone(context: Context) = send(context, ACTION_TOGGLE_PHONE)
        fun toggleMediaSources(context: Context) = send(context, ACTION_TOGGLE_MEDIA_SOURCES)
        fun toggleSettings(context: Context) = send(context, ACTION_TOGGLE_SETTINGS)
        fun showTopBarOnly(context: Context) {
            PanelApplicationSession.markInterrupted(context)
            send(context, ACTION_SHOW_TOP_BAR_ONLY)
        }
        fun hideForExclusiveScreen(context: Context) {
            PanelApplicationSession.markInterrupted(context)
            send(context, ACTION_HIDE_FOR_EXCLUSIVE_SCREEN)
        }
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, HybridOverlayService::class.java).setAction(action)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action != ACTION_STOP) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { error ->
                Log.e(TAG, "Unable to send overlay command: $action", error)
            }
        }
    }

    private enum class OverlayModal {
        NONE,
        WEATHER,
        APPLICATIONS,
        PHONE,
        SETTINGS,
        DRIVER_PROFILE,
        MEDIA_SOURCES,
    }
}
