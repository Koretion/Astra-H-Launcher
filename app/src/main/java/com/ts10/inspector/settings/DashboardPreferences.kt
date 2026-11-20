package com.ts10.inspector.settings

import com.ts10.inspector.R
import androidx.annotation.StringRes
import android.content.Context
import android.content.SharedPreferences
import com.ts10.inspector.dashboard.DEFAULT_NAVIGATION_PACKAGE
import com.ts10.inspector.dashboard.VehicleSceneMode
import com.ts10.inspector.ui.AppLanguage
import com.ts10.inspector.ui.DashboardThemeMode
import com.ts10.inspector.vision.LocalVisionPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DashboardSettings(
    val navigationPackage: String = DEFAULT_NAVIGATION_PACKAGE,
    val assistantPackage: String? = null,
    val mediaPackage: String? = null,
    val cameraMode: DashboardCameraMode = DashboardCameraMode.USB,
    val cameraPackage: String? = null,
    val mediaPackages: List<String> = emptyList(),
    val favoritePackages: List<String> = emptyList(),
    val panelPackages: List<String> = emptyList(),
    val manualOdometerKm: Double? = null,
    val onlineWeatherEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.AUTO,
    val fusedLocationEnabled: Boolean = false,
    val qfDoorPopupEnabled: Boolean = false,
    val qfClimatePopupEnabled: Boolean = false,
    val qfDataShareSteeringEnabled: Boolean = false,
    val themeMode: DashboardThemeMode = DashboardThemeMode.AUTOMATIC,
    val vehicleColorMode: DashboardVehicleColorMode = DashboardVehicleColorMode.WHITE,
    val vehiclePaint: Vehicle3dPaint = Vehicle3dPaint.WHITE,
    val drivingZoomMode: DashboardDrivingZoomMode = DashboardDrivingZoomMode.STANDARD_1X,
    val vehicleSceneMode: VehicleSceneMode = VehicleSceneMode.THREE_D,
    val localVisionMode: LocalVisionMode = LocalVisionMode.ACTIVE,
    val localVisionSource: LocalVisionSource = LocalVisionSource.DASHCAM,
    val localVisionCaptureMode: LocalVisionCaptureMode = LocalVisionCaptureMode.PRIVATE_GL,
    val localVisionObjectsEnabled: Boolean = false,
)

enum class DashboardDrivingZoomMode(
    val label: String,
    val sceneScale: Float,
) {
    CLOSER_0_5X("0.5x", 2f),
    STANDARD_1X("1x", 1f),
    FARTHER_2X("2x", 0.5f),

    /** 1x through 60 km/h, then the wider 2x view from 61 km/h. */
    AUTOMATIC_1X_2X("AUTO", 1f);

    fun sceneScaleForSpeed(speedKmh: Int): Float = when {
        this != AUTOMATIC_1X_2X -> sceneScale
        speedKmh >= AUTOMATIC_2X_MIN_SPEED_KMH -> FARTHER_2X.sceneScale
        else -> STANDARD_1X.sceneScale
    }

    private companion object {
        const val AUTOMATIC_2X_MIN_SPEED_KMH = 61
    }
}

internal fun DashboardDrivingZoomMode.sceneScaleForMotion(
    speedKmh: Int,
    lastMovingScale: Float,
): Float = if (this == DashboardDrivingZoomMode.AUTOMATIC_1X_2X && speedKmh <= 0) {
    lastMovingScale
} else {
    sceneScaleForSpeed(speedKmh)
}

enum class DashboardVehicleColorMode {
    WHITE,
    RED,
    BLACK,
    THEME,
}

/** Body colours of the 3D vehicle (approximate Astra H factory paints) plus the dashboard theme colour. */
enum class Vehicle3dPaint(@StringRes val label: Int, val argb: Int?) {
    WHITE(R.string.paint_white, 0xFFEDEEEC.toInt()),
    SILVER(R.string.paint_silver, 0xFFB9BDC1.toInt()),
    GREY(R.string.paint_grey, 0xFF55595F.toInt()),
    BLACK(R.string.paint_black, 0xFF141619.toInt()),
    RED(R.string.paint_red, 0xFFB3141C.toInt()),
    BLUE(R.string.paint_blue, 0xFF1D4FB0.toInt()),
    YELLOW(R.string.paint_yellow, 0xFFE8B10E.toInt()),
    THEME(R.string.paint_theme, null),
}

enum class DashboardCameraMode {
    USB,
    APPLICATION,
}

enum class LocalVisionMode(@StringRes val label: Int) {
    OFF(R.string.vision_mode_off),
    CAPTURE_ONLY(R.string.vision_mode_capture),
    ACTIVE(R.string.vision_mode_active);

    fun next(): LocalVisionMode = entries[(ordinal + 1) % entries.size]
}

enum class LocalVisionSource(@StringRes val label: Int) {
    DASHCAM(R.string.vision_source_dashcam),
    USB_UVC(R.string.vision_source_usb);

    fun next(): LocalVisionSource = entries[(ordinal + 1) % entries.size]
}

enum class LocalVisionCaptureMode(@StringRes val label: Int) {
    PRIVATE_GL(R.string.vision_capture_private),
    RGBA(R.string.vision_capture_rgba);

    fun next(): LocalVisionCaptureMode = entries[(ordinal + 1) % entries.size]
}

class DashboardPreferences private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also { stored ->
        // 0.4.43 promotes the validated compatible 3D scene from the laboratory to the real
        // dashboard. Migrate existing installs once; the driver can still select 2D afterwards.
        if (!stored.getBoolean(KEY_VEHICLE_3D_PROMOTED, false)) {
            stored.edit()
                .putString(KEY_VEHICLE_SCENE_MODE, VehicleSceneMode.THREE_D.name)
                .putBoolean(KEY_VEHICLE_3D_PROMOTED, true)
                .apply()
        }
    }
    private val mutableState = MutableStateFlow(read(preferences))
    val state: StateFlow<DashboardSettings> = mutableState.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        val updated = read(prefs)
        mutableState.value = updated
        DriverProfilePreferences.get(context).onDashboardSettingsChanged(updated)
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setNavigation(packageName: String) = edit(KEY_NAVIGATION, packageName)
    fun setAssistant(packageName: String) = edit(KEY_ASSISTANT, packageName)
    fun setMedia(packageName: String) = edit(KEY_MEDIA, packageName)
    fun setUsbCamera() {
        preferences.edit()
            .putString(KEY_CAMERA_MODE, DashboardCameraMode.USB.name)
            .remove(KEY_CAMERA_PACKAGE)
            .apply()
    }
    fun setCameraApplication(packageName: String) {
        preferences.edit()
            .putString(KEY_CAMERA_MODE, DashboardCameraMode.APPLICATION.name)
            .putString(KEY_CAMERA_PACKAGE, packageName)
            .apply()
    }

    fun toggleMediaPackage(packageName: String) {
        val current = mutableState.value.mediaPackages.toMutableList()
        val removed = current.remove(packageName)
        if (!removed) current += packageName
        preferences.edit().putString(KEY_MEDIA_PACKAGES, current.distinct().joinToString(SEPARATOR)).apply()
        if (removed && mutableState.value.mediaPackage == packageName) {
            preferences.edit().putString(KEY_MEDIA, current.firstOrNull()).apply()
        }
    }

    fun toggleFavorite(packageName: String) {
        val current = mutableState.value.favoritePackages.toMutableList()
        if (!current.remove(packageName)) {
            if (current.size >= MAX_FAVORITES) return
            current += packageName
        }
        preferences.edit().putString(KEY_FAVORITES, current.joinToString(SEPARATOR)).apply()
    }

    fun togglePanelPackage(packageName: String) {
        val current = mutableState.value.panelPackages.toMutableList()
        if (!current.remove(packageName)) current += packageName
        preferences.edit().putString(KEY_PANEL_PACKAGES, current.joinToString(SEPARATOR)).apply()
    }

    fun setOnlineWeatherEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ONLINE_WEATHER, enabled).apply()
    }

    fun setFusedLocationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FUSED_LOCATION, enabled).apply()
    }

    fun setManualOdometerKm(value: Double?) {
        val editor = preferences.edit()
        if (value != null && value.isFinite() && value in 0.0..MAX_ODOMETER_KM) {
            editor.putString(KEY_MANUAL_ODOMETER_KM, value.toString())
        } else {
            editor.remove(KEY_MANUAL_ODOMETER_KM)
        }
        editor.apply()
    }

    fun setQfDoorPopupEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_QF_DOOR_POPUP, enabled).apply()
    }

    fun setQfClimatePopupEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_QF_CLIMATE_POPUP, enabled).apply()
    }

    fun setQfDataShareSteeringEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_QF_DATA_SHARE_STEERING, enabled).apply()
    }

    fun setThemeMode(mode: DashboardThemeMode) = edit(KEY_THEME_MODE, mode.name)
    fun setLanguage(language: AppLanguage) = edit(KEY_LANGUAGE, language.name)
    fun setVehicleColorMode(mode: DashboardVehicleColorMode) = edit(KEY_VEHICLE_COLOR_MODE, mode.name)
    fun setDrivingZoomMode(mode: DashboardDrivingZoomMode) = edit(KEY_DRIVING_ZOOM_MODE, mode.name)
    fun setVehiclePaint(paint: Vehicle3dPaint) = edit(KEY_VEHICLE_PAINT, paint.name)

    /** Persisted opt-in switch for the compatible dashboard trial; 2D remains the safe default. */
    fun setVehicleSceneMode(mode: VehicleSceneMode) = edit(KEY_VEHICLE_SCENE_MODE, mode.name)

    internal fun applyProfileSettings(profile: DriverProfile) {
        preferences.edit()
            .putString(KEY_NAVIGATION, profile.navigationPackage)
            .putString(KEY_ASSISTANT, profile.assistantPackage)
            .putString(KEY_MEDIA, profile.mediaPackage)
            .putString(KEY_MEDIA_PACKAGES, profile.mediaPackages.joinToString(SEPARATOR))
            .putString(KEY_FAVORITES, profile.favoritePackages.joinToString(SEPARATOR))
            .putBoolean(KEY_ONLINE_WEATHER, profile.onlineWeatherEnabled)
            .putString(KEY_THEME_MODE, profile.themeMode.name)
            .putString(KEY_LANGUAGE, profile.language.name)
            .putString(KEY_VEHICLE_COLOR_MODE, profile.vehicleColorMode.name)
            .putString(KEY_DRIVING_ZOOM_MODE, profile.drivingZoomMode.name)
            .apply()
    }

    fun setLocalVisionMode(mode: LocalVisionMode) {
        edit(KEY_LOCAL_VISION_MODE, mode.name)
        LocalVisionPipeline.applyMode(mode)
    }

    fun setLocalVisionSource(source: LocalVisionSource) {
        edit(KEY_LOCAL_VISION_SOURCE, source.name)
        LocalVisionPipeline.applySource(source)
    }

    fun setLocalVisionCaptureMode(mode: LocalVisionCaptureMode) {
        edit(KEY_LOCAL_VISION_CAPTURE_MODE, mode.name)
        LocalVisionPipeline.applyCaptureMode(mode)
    }

    fun setLocalVisionObjectsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCAL_VISION_OBJECTS, enabled).apply()
        LocalVisionPipeline.applyObjectDetectionEnabled(enabled)
    }

    private fun edit(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    companion object {
        const val MAX_FAVORITES = 12
        private const val FILE_NAME = "dashboard_preferences"
        private const val KEY_NAVIGATION = "navigation_package"
        private const val KEY_ASSISTANT = "assistant_package"
        private const val KEY_MEDIA = "media_package"
        private const val KEY_CAMERA_MODE = "camera_mode"
        private const val KEY_CAMERA_PACKAGE = "camera_package"
        private const val KEY_MEDIA_PACKAGES = "media_packages"
        private const val KEY_FAVORITES = "favorite_packages"
        private const val KEY_PANEL_PACKAGES = "panel_packages"
        private const val KEY_MANUAL_ODOMETER_KM = "manual_odometer_km"
        private const val KEY_ONLINE_WEATHER = "online_weather"
        private const val KEY_FUSED_LOCATION = "fused_location_enabled"
        private const val KEY_QF_DOOR_POPUP = "qf_door_popup_enabled"
        private const val KEY_QF_CLIMATE_POPUP = "qf_climate_popup_enabled"
        private const val KEY_QF_DATA_SHARE_STEERING = "qf_data_share_steering_enabled"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VEHICLE_COLOR_MODE = "vehicle_color_mode"
        private const val KEY_DRIVING_ZOOM_MODE = "driving_zoom_mode"
        private const val KEY_VEHICLE_PAINT = "vehicle_3d_paint"
        private const val KEY_VEHICLE_SCENE_MODE = "vehicle_scene_mode"
        private const val KEY_VEHICLE_3D_PROMOTED = "vehicle_3d_promoted_0_4_43"
        private const val KEY_LOCAL_VISION_MODE = "local_vision_mode"
        private const val KEY_LOCAL_VISION_SOURCE = "local_vision_source"
        private const val KEY_LOCAL_VISION_CAPTURE_MODE = "local_vision_capture_mode"
        private const val KEY_LOCAL_VISION_OBJECTS = "local_vision_objects_enabled"
        private const val SEPARATOR = "|"
        internal const val MAX_ODOMETER_KM = 9_999_999.999

        @Volatile
        private var instance: DashboardPreferences? = null

        fun get(context: Context): DashboardPreferences = instance ?: synchronized(this) {
            instance ?: DashboardPreferences(context.applicationContext).also { instance = it }
        }

        fun current(context: Context): DashboardSettings = get(context).state.value

        private fun read(preferences: SharedPreferences) = DashboardSettings(
            navigationPackage = preferences.getString(KEY_NAVIGATION, DEFAULT_NAVIGATION_PACKAGE)
                ?: DEFAULT_NAVIGATION_PACKAGE,
            assistantPackage = preferences.getString(KEY_ASSISTANT, null),
            mediaPackage = preferences.getString(KEY_MEDIA, null),
            cameraMode = runCatching {
                DashboardCameraMode.valueOf(
                    preferences.getString(KEY_CAMERA_MODE, DashboardCameraMode.USB.name)
                        ?: DashboardCameraMode.USB.name,
                )
            }.getOrDefault(DashboardCameraMode.USB),
            cameraPackage = preferences.getString(KEY_CAMERA_PACKAGE, null),
            mediaPackages = preferences.getString(KEY_MEDIA_PACKAGES, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter(String::isNotBlank)
                .distinct(),
            favoritePackages = preferences.getString(KEY_FAVORITES, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_FAVORITES),
            panelPackages = preferences.getString(KEY_PANEL_PACKAGES, "")
                .orEmpty()
                .split(SEPARATOR)
                .filter(String::isNotBlank)
                .distinct(),
            manualOdometerKm = preferences.getString(KEY_MANUAL_ODOMETER_KM, null)
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..MAX_ODOMETER_KM },
            onlineWeatherEnabled = preferences.getBoolean(KEY_ONLINE_WEATHER, true),
            language = runCatching {
                AppLanguage.valueOf(preferences.getString(KEY_LANGUAGE, null) ?: AppLanguage.AUTO.name)
            }.getOrDefault(AppLanguage.AUTO),
            fusedLocationEnabled = preferences.getBoolean(KEY_FUSED_LOCATION, false),
            qfDoorPopupEnabled = preferences.getBoolean(KEY_QF_DOOR_POPUP, false),
            qfClimatePopupEnabled = preferences.getBoolean(KEY_QF_CLIMATE_POPUP, false),
            qfDataShareSteeringEnabled = preferences.getBoolean(KEY_QF_DATA_SHARE_STEERING, false),
            themeMode = runCatching {
                DashboardThemeMode.valueOf(
                    preferences.getString(KEY_THEME_MODE, DashboardThemeMode.AUTOMATIC.name)
                        ?: DashboardThemeMode.AUTOMATIC.name,
                )
            }.getOrDefault(DashboardThemeMode.AUTOMATIC),
            vehicleColorMode = runCatching {
                DashboardVehicleColorMode.valueOf(
                    preferences.getString(KEY_VEHICLE_COLOR_MODE, DashboardVehicleColorMode.WHITE.name)
                        ?: DashboardVehicleColorMode.WHITE.name,
                )
            }.getOrDefault(DashboardVehicleColorMode.WHITE),
            vehiclePaint = runCatching {
                Vehicle3dPaint.valueOf(preferences.getString(KEY_VEHICLE_PAINT, null) ?: Vehicle3dPaint.WHITE.name)
            }.getOrDefault(Vehicle3dPaint.WHITE),
            drivingZoomMode = runCatching {
                DashboardDrivingZoomMode.valueOf(
                    preferences.getString(KEY_DRIVING_ZOOM_MODE, DashboardDrivingZoomMode.STANDARD_1X.name)
                        ?: DashboardDrivingZoomMode.STANDARD_1X.name,
                )
            }.getOrDefault(DashboardDrivingZoomMode.STANDARD_1X),
            vehicleSceneMode = VehicleSceneMode.fromStored(
                preferences.getString(KEY_VEHICLE_SCENE_MODE, null),
            ),
            localVisionMode = runCatching {
                LocalVisionMode.valueOf(
                    preferences.getString(KEY_LOCAL_VISION_MODE, LocalVisionMode.ACTIVE.name)
                        ?: LocalVisionMode.ACTIVE.name,
                )
            }.getOrDefault(LocalVisionMode.ACTIVE),
            localVisionSource = runCatching {
                LocalVisionSource.valueOf(
                    preferences.getString(KEY_LOCAL_VISION_SOURCE, LocalVisionSource.DASHCAM.name)
                        ?: LocalVisionSource.DASHCAM.name,
                )
            }.getOrDefault(LocalVisionSource.DASHCAM),
            localVisionCaptureMode = runCatching {
                LocalVisionCaptureMode.valueOf(
                    preferences.getString(KEY_LOCAL_VISION_CAPTURE_MODE, LocalVisionCaptureMode.PRIVATE_GL.name)
                        ?: LocalVisionCaptureMode.PRIVATE_GL.name,
                )
            }.getOrDefault(LocalVisionCaptureMode.PRIVATE_GL),
            localVisionObjectsEnabled = preferences.getBoolean(KEY_LOCAL_VISION_OBJECTS, false),
        )
    }
}
