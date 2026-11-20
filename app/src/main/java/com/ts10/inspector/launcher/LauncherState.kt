package com.ts10.inspector.launcher

import androidx.compose.runtime.Immutable
import com.ts10.inspector.environment.CurrentWeather

@Immutable
data class LauncherState(
    val speedKmh: Int,
    val speedSource: String,
    val speedLimitKmh: Int?,
    val dateLabel: String,
    val timeLabel: String,
    val outdoorTemperature: String,
    val weatherCode: Int?,
    val weatherDetails: CurrentWeather?,
    val cabinTemperature: String,
    val climateMode: String,
    val climatePower: Boolean?,
    val climateCooling: Boolean?,
    val climateFanLevel: Int?,
    val climateAuto: Boolean?,
    val climateRecirculation: Boolean?,
    val climateFrontDefrost: Boolean?,
    val climateRearDefrost: Boolean?,
    val climateAirDirection: String,
    val mediaProgress: Float,
    val mediaPlaying: Boolean,
    val mediaTitle: String?,
    val mediaArtist: String?,
    val mediaSourceLabel: String?,
    val mediaPackage: String?,
    val leftBlinker: Boolean,
    val rightBlinker: Boolean,
    val headlights: Boolean,
    val dippedBeam: Boolean,
    val highBeam: Boolean,
    val positionLights: Boolean,
    val lowFuel: Boolean,
    val hazard: Boolean,
    val handbrake: Boolean,
    val coolantAlert: Boolean,
    val brakeAlert: Boolean,
    val frontLeftDoorOpen: Boolean,
    val frontRightDoorOpen: Boolean,
    val rearLeftDoorOpen: Boolean,
    val rearRightDoorOpen: Boolean,
    val trunkOpen: Boolean,
    val bonnetOpen: Boolean,
)

@Immutable
data class SpeedUiState(
    val speedKmh: Int,
    val speedSource: String,
    val speedLimitKmh: Int?,
)

@Immutable
data class WeatherUiState(
    val outdoorTemperature: String,
    val weatherCode: Int?,
    val weatherDetails: CurrentWeather?,
)

@Immutable
data class ClimateUiState(
    val cabinTemperature: String,
    val climateMode: String,
    val climatePower: Boolean?,
    val climateCooling: Boolean?,
    val climateFanLevel: Int?,
    val climateAuto: Boolean?,
    val climateRecirculation: Boolean?,
    val climateFrontDefrost: Boolean?,
    val climateRearDefrost: Boolean?,
    val climateAirDirection: String,
)

@Immutable
data class MediaUiState(
    val mediaProgress: Float,
    val mediaPlaying: Boolean,
    val mediaTitle: String?,
    val mediaArtist: String?,
    val mediaSourceLabel: String?,
    val mediaPackage: String?,
)

@Immutable
data class VehicleVisualUiState(
    val speedKmh: Int,
    val frontLeftDoorOpen: Boolean,
    val frontRightDoorOpen: Boolean,
    val rearLeftDoorOpen: Boolean,
    val rearRightDoorOpen: Boolean,
    val trunkOpen: Boolean,
    val bonnetOpen: Boolean,
)

internal fun LauncherState.speedUiState() = SpeedUiState(speedKmh, speedSource, speedLimitKmh)

internal fun LauncherState.weatherUiState() =
    WeatherUiState(outdoorTemperature, weatherCode, weatherDetails)

internal fun LauncherState.climateUiState() = ClimateUiState(
    cabinTemperature,
    climateMode,
    climatePower,
    climateCooling,
    climateFanLevel,
    climateAuto,
    climateRecirculation,
    climateFrontDefrost,
    climateRearDefrost,
    climateAirDirection,
)

internal fun LauncherState.mediaUiState() = MediaUiState(
    mediaProgress,
    mediaPlaying,
    mediaTitle,
    mediaArtist,
    mediaSourceLabel,
    mediaPackage,
)

internal fun LauncherState.vehicleVisualUiState() = VehicleVisualUiState(
    speedKmh,
    frontLeftDoorOpen,
    frontRightDoorOpen,
    rearLeftDoorOpen,
    rearRightDoorOpen,
    trunkOpen,
    bonnetOpen,
)

@Immutable
data class LauncherActions(
    val onWeather: () -> Unit = {},
    val onPhone: () -> Unit = {},
    val onVoiceAssistant: () -> Unit = {},
    val onNavigation: () -> Unit = {},
    val onApplications: () -> Unit = {},
    val onCamera: () -> Unit = {},
    val onOpenCameraFullScreen: () -> Unit = {},
    val onOpenMedia: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onSelectMediaSource: () -> Unit = {},
)

fun previewLauncherState() = LauncherState(
    speedKmh = 0,
    speedSource = "GPS",
    speedLimitKmh = null,
    dateLabel = "Miercoles 23",
    timeLabel = "11:26",
    outdoorTemperature = "--\u00b0",
    weatherCode = null,
    weatherDetails = null,
    cabinTemperature = "--\u00b0",
    climateMode = "NO DATA",
    climatePower = null,
    climateCooling = null,
    climateFanLevel = null,
    climateAuto = null,
    climateRecirculation = null,
    climateFrontDefrost = null,
    climateRearDefrost = null,
    climateAirDirection = "",
    mediaProgress = 0f,
    mediaPlaying = false,
    mediaTitle = null,
    mediaArtist = null,
    mediaSourceLabel = null,
    mediaPackage = null,
    leftBlinker = false,
    rightBlinker = false,
    headlights = false,
    dippedBeam = false,
    highBeam = false,
    positionLights = false,
    lowFuel = false,
    hazard = false,
    handbrake = false,
    coolantAlert = false,
    brakeAlert = false,
    frontLeftDoorOpen = false,
    frontRightDoorOpen = false,
    rearLeftDoorOpen = false,
    rearRightDoorOpen = false,
    trunkOpen = false,
    bonnetOpen = false,
)
