package com.ts10.inspector.dashboard

import androidx.compose.ui.graphics.Color

internal val Weather = Color(0xFFF4A900)
internal const val RoadSceneTop = 99f
internal const val VehicleSceneTop = 134f
internal const val VehicleSceneWidth = 308f
private const val VehicleSceneAspectRatio = 1.5f
internal const val VehicleSceneHeight = VehicleSceneWidth / VehicleSceneAspectRatio
internal const val RoadSceneHeight = 244f
internal const val VehicleArtworkCanvasWidth = 1023f
internal const val VehicleArtworkCanvasHeight = 548f
internal const val VehicleArtworkWidth = 150f
internal const val VehicleArtworkBottomMargin = 14f
// The visible body/closed-door composite is centred at x=503.5 inside its 1023 px source canvas.
internal const val VehicleArtworkCenterCorrectionX = 8f
internal const val VehicleArtworkRightNudgeX = 10f
internal const val VehicleStoppedArtworkScale = 1.6f
internal const val VehicleStoppedRoadScale = 1.75f
internal const val VehicleRoadCenterOffsetFraction = 0f
internal const val VehicleStoppedArtworkLowering = 12f
internal const val VehicleStoppedZoomDelayMs = 2_000L
internal const val VehicleSceneAnimationDurationMs = 650
internal const val IndicatorPulseHoldMs = 2_000L
internal const val TrafficLightDisplayHoldMs = 6_000L
internal const val TrafficLightDirectMinScore = 0.50f
internal const val TrafficLightDirectMinColorConfidence = 0.60f
internal const val TrafficLightIndicatorLeft = 280f
internal const val VehicleBodyOffsetX = 263f
internal const val VehicleLeftDoorOffsetX = 0f
internal const val VehicleLeftDoorOffsetY = 44f
internal const val VehicleRightDoorOffsetX = 643f
internal const val VehicleRightDoorOffsetY = 43f
internal const val VehicleFrontLightsOffsetX = 55f
internal const val VehicleFrontLightsOffsetY = -291f
internal const val VehicleRearLightsOffsetX = 255f
internal const val VehicleRearLightsOffsetY = 343f
internal const val VehicleBodyImageWidth = 483f
internal const val VehicleBodyImageHeight = 548f
internal const val VehicleGroundShadowWidthFactor = 1.28f
internal const val VehicleGroundShadowHeightFactor = 0.34f
internal const val VehicleGroundShadowOffsetY = 0.75f
internal const val HorizonTop = 99f
internal const val HorizonWidth = 230f
internal const val HorizonHeight = 10f
internal const val HorizonLeft = (VehicleSceneWidth - HorizonWidth) / 2f

internal const val ReferenceScale = VehicleSceneWidth / 970f
internal const val ClimateTop = 343f
internal const val MediaTop = 379f
