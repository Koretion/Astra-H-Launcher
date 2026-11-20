package com.ts10.inspector.settings

import com.ts10.inspector.ui.AppLocale
import com.ts10.inspector.R
import androidx.annotation.StringRes
import android.content.Context
import android.os.SystemClock
import com.ts10.inspector.dashboard.DEFAULT_NAVIGATION_PACKAGE
import com.ts10.inspector.diagnostics.TelemetryLog
import com.ts10.inspector.phone.AndroidBluetoothPhoneClient
import com.ts10.inspector.ui.AppLanguage
import com.ts10.inspector.ui.DashboardThemeMode
import com.ts10.inspector.vehicle.VehicleMileageTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DriverProfile(
    val id: String,
    val name: String,
    val themeMode: DashboardThemeMode,
    val vehicleColorMode: DashboardVehicleColorMode,
    val drivingZoomMode: DashboardDrivingZoomMode,
    val preferredPhoneAddress: String? = null,
    val preferredPhoneName: String? = null,
    val iconColor: DriverProfileColor = DriverProfileColor.BLUE,
    val tripHistoryLimit: Int = 25,
    val navigationPackage: String = DEFAULT_NAVIGATION_PACKAGE,
    val assistantPackage: String? = null,
    val mediaPackage: String? = null,
    val mediaPackages: List<String> = emptyList(),
    val favoritePackages: List<String> = emptyList(),
    val onlineWeatherEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.AUTO,
)

internal enum class DriverProfileColor(val argb: Long) {
    BLUE(0xFF3D82D7),
    GREEN(0xFF2E9D50),
    ORANGE(0xFFE39A28),
    RED(0xFFD54A4A),
    PURPLE(0xFF8A62C8),
    GRAY(0xFF7D858D),
}

internal data class DriverProfilesState(
    val profiles: List<DriverProfile>,
    val activeProfileId: String,
) {
    val activeProfile: DriverProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}

internal class DriverProfilePreferences private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readProfiles())
    val state: StateFlow<DriverProfilesState> = mutableState.asStateFlow()
    private var lastAccPromptElapsedMs = Long.MIN_VALUE
    @Volatile private var recognizedIgnitionKeyIndex: Int? = null

    val hasRecognizedIgnitionKey: Boolean
        get() = recognizedIgnitionKeyIndex != null

    fun selectProfile(profileId: String, dashboard: DashboardPreferences) {
        val currentState = mutableState.value
        val selected = currentState.profiles.firstOrNull { it.id == profileId } ?: return
        saveDashboardSettings(currentState.activeProfileId, dashboard.state.value)
        preferences.edit().putString(KEY_ACTIVE_PROFILE, selected.id).apply()
        mutableState.value = readProfiles()
        VehicleMileageTracker.onProfileChanged(selected.id, selected.name)
        dashboard.applyProfileSettings(selected)
        AndroidBluetoothPhoneClient.refresh()
    }

    /** Applies the non-secret key index published by SW-CAN 0x425 (01/02).
     * Returns true only when this key has just been recognized for the current ACC cycle.
     */
    @Synchronized
    fun selectProfileForIgnitionKey(keyIndex: Int, dashboard: DashboardPreferences): Boolean {
        val profileId = when (keyIndex) {
            1 -> "driver_1"
            2 -> "driver_2"
            else -> return false
        }
        if (recognizedIgnitionKeyIndex == keyIndex) return false
        recognizedIgnitionKeyIndex = keyIndex
        clearPendingAccSelector()
        if (mutableState.value.activeProfileId != profileId) selectProfile(profileId, dashboard)
        TelemetryLog.event("DRIVER_PROFILE", "ignitionKey=$keyIndex profile=$profileId")
        return true
    }

    fun clearRecognizedIgnitionKey() {
        recognizedIgnitionKeyIndex = null
    }

    fun setPreferredPhone(profileId: String, address: String?, name: String?) {
        if (profileId !in PROFILE_DEFINITIONS.map { it.id }) return
        preferences.edit()
            .putString(profileKey(profileId, "phone_address"), address)
            .putString(profileKey(profileId, "phone_name"), name)
            .apply()
        mutableState.value = readProfiles()
        if (profileId == mutableState.value.activeProfileId) AndroidBluetoothPhoneClient.refresh()
    }

    fun setName(profileId: String, name: String) {
        val definition = PROFILE_DEFINITIONS.firstOrNull { it.id == profileId } ?: return
        val normalized = name.trim().take(MAX_PROFILE_NAME_LENGTH)
            .ifBlank { definition.defaultName }
        preferences.edit().putString(profileKey(profileId, "name"), normalized).apply()
        mutableState.value = readProfiles()
        if (profileId == mutableState.value.activeProfileId) {
            VehicleMileageTracker.onProfileChanged(profileId, normalized)
        }
    }

    fun setIconColor(profileId: String, color: DriverProfileColor) {
        if (profileId !in PROFILE_DEFINITIONS.map { it.id }) return
        preferences.edit().putString(profileKey(profileId, "icon_color"), color.name).apply()
        mutableState.value = readProfiles()
    }

    fun setTripHistoryLimit(profileId: String, limit: Int) {
        if (profileId !in PROFILE_DEFINITIONS.map { it.id }) return
        preferences.edit().putInt(profileKey(profileId, "trip_history_limit"), if (limit >= 50) 50 else 25).apply()
        mutableState.value = readProfiles()
    }

    /** Keeps the active profile synchronized whenever Screen or Applications is changed. */
    fun onDashboardSettingsChanged(settings: DashboardSettings) {
        saveDashboardSettings(mutableState.value.activeProfileId, settings)
        mutableState.value = readProfiles(settings)
    }

    @Synchronized
    fun requestAccSelector(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (lastAccPromptElapsedMs != Long.MIN_VALUE && nowElapsedMs - lastAccPromptElapsedMs < ACC_PROMPT_DEBOUNCE_MS) {
            return false
        }
        lastAccPromptElapsedMs = nowElapsedMs
        preferences.edit().putBoolean(KEY_ACC_SELECTOR_PENDING, true).apply()
        return true
    }

    @Synchronized
    fun consumePendingAccSelector(): Boolean {
        if (!preferences.getBoolean(KEY_ACC_SELECTOR_PENDING, false)) return false
        preferences.edit().putBoolean(KEY_ACC_SELECTOR_PENDING, false).apply()
        return true
    }

    fun clearPendingAccSelector() {
        preferences.edit().putBoolean(KEY_ACC_SELECTOR_PENDING, false).apply()
    }

    private fun saveDashboardSettings(profileId: String, settings: DashboardSettings) {
        preferences.edit()
            .putString(profileKey(profileId, "theme"), settings.themeMode.name)
            .putString(profileKey(profileId, "language"), settings.language.name)
            .putString(profileKey(profileId, "vehicle_color"), settings.vehicleColorMode.name)
            .putString(profileKey(profileId, "zoom"), settings.drivingZoomMode.name)
            .putString(profileKey(profileId, "navigation"), settings.navigationPackage)
            .putString(profileKey(profileId, "assistant"), settings.assistantPackage.orEmpty())
            .putString(profileKey(profileId, "media"), settings.mediaPackage.orEmpty())
            .putString(profileKey(profileId, "media_apps"), settings.mediaPackages.joinToString(LIST_SEPARATOR))
            .putString(profileKey(profileId, "favorites"), settings.favoritePackages.joinToString(LIST_SEPARATOR))
            .putBoolean(profileKey(profileId, "online_weather"), settings.onlineWeatherEnabled)
            .apply()
    }

    private fun readProfiles(globalDefaults: DashboardSettings = DashboardPreferences.current(context)): DriverProfilesState {
        val profiles = PROFILE_DEFINITIONS.map { definition ->
            val id = definition.id
            DriverProfile(
                id = id,
                name = preferences.getString(profileKey(id, "name"), null)
                    ?.trim()
                    ?.take(MAX_PROFILE_NAME_LENGTH)
                    // El nombre por defecto guardado en español se muestra traducido.
                    ?.takeIf { it.isNotBlank() && it != definition.defaultName }
                    ?: AppLocale.localizedContext(context, globalDefaults.language).getString(definition.nameRes),
                themeMode = enumPreference(profileKey(id, "theme"), globalDefaults.themeMode),
                language = enumPreference(profileKey(id, "language"), globalDefaults.language),
                vehicleColorMode = enumPreference(profileKey(id, "vehicle_color"), globalDefaults.vehicleColorMode),
                drivingZoomMode = enumPreference(profileKey(id, "zoom"), globalDefaults.drivingZoomMode),
                preferredPhoneAddress = preferences.getString(profileKey(id, "phone_address"), null),
                preferredPhoneName = preferences.getString(profileKey(id, "phone_name"), null),
                iconColor = enumPreference(profileKey(id, "icon_color"), definition.defaultColor),
                tripHistoryLimit = preferences.getInt(profileKey(id, "trip_history_limit"), 25)
                    .let { if (it >= 50) 50 else 25 },
                navigationPackage = preferences.getString(profileKey(id, "navigation"), globalDefaults.navigationPackage)
                    ?: globalDefaults.navigationPackage,
                assistantPackage = nullableProfileString(id, "assistant", globalDefaults.assistantPackage),
                mediaPackage = nullableProfileString(id, "media", globalDefaults.mediaPackage),
                mediaPackages = profileList(id, "media_apps", globalDefaults.mediaPackages),
                favoritePackages = profileList(id, "favorites", globalDefaults.favoritePackages)
                    .take(DashboardPreferences.MAX_FAVORITES),
                onlineWeatherEnabled = preferences.getBoolean(
                    profileKey(id, "online_weather"),
                    globalDefaults.onlineWeatherEnabled,
                ),
            )
        }
        val active = preferences.getString(KEY_ACTIVE_PROFILE, PROFILE_DEFINITIONS.first().id)
            ?.takeIf { candidate -> profiles.any { it.id == candidate } }
            ?: profiles.first().id
        return DriverProfilesState(profiles, active)
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, default: T): T =
        preferences.getString(key, null)
            ?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
            ?: default

    private fun nullableProfileString(profileId: String, property: String, default: String?): String? {
        val key = profileKey(profileId, property)
        if (!preferences.contains(key)) return default
        return preferences.getString(key, null)?.takeIf(String::isNotBlank)
    }

    private fun profileList(profileId: String, property: String, default: List<String>): List<String> {
        val key = profileKey(profileId, property)
        if (!preferences.contains(key)) return default
        return preferences.getString(key, "").orEmpty()
            .split(LIST_SEPARATOR)
            .filter(String::isNotBlank)
            .distinct()
    }

    companion object {
        private const val FILE_NAME = "driver_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_ACC_SELECTOR_PENDING = "acc_selector_pending"
        private const val ACC_PROMPT_DEBOUNCE_MS = 15_000L
        private const val MAX_PROFILE_NAME_LENGTH = 24
        private const val LIST_SEPARATOR = "|"
        private data class ProfileDefinition(
            val id: String,
            val defaultName: String,
            val defaultColor: DriverProfileColor,
            @StringRes val nameRes: Int,
        )
        private val PROFILE_DEFINITIONS = listOf(
            ProfileDefinition("driver_1", "Conductor 1", DriverProfileColor.BLUE, R.string.profile_default_driver_1),
            ProfileDefinition("driver_2", "Conductor 2", DriverProfileColor.ORANGE, R.string.profile_default_driver_2),
            ProfileDefinition("guest", "Invitado", DriverProfileColor.GRAY, R.string.profile_default_guest),
        )

        @Volatile
        private var instance: DriverProfilePreferences? = null

        fun get(context: Context): DriverProfilePreferences = instance ?: synchronized(this) {
            instance ?: DriverProfilePreferences(context.applicationContext).also { instance = it }
        }

        private fun profileKey(profileId: String, property: String) = "profile_${profileId}_$property"
    }
}
