package com.ts10.inspector.dashboard

/**
 * Vehicle scene shown in the panel: the OpenGL 3D car (default) or the 2D road scene.
 *
 * Values stored by older versions (OFFSCREEN_TEST, AUTOMATIC and the removed pre-rendered 3D
 * mode) all map to THREE_D, which is now the OpenGL engine.
 */
enum class VehicleSceneMode {
    TWO_D,
    THREE_D;

    companion object {
        internal fun fromStored(value: String?): VehicleSceneMode =
            if (value == TWO_D.name) TWO_D else THREE_D
    }
}
