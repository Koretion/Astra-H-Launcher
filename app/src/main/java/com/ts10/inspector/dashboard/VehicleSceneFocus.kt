package com.ts10.inspector.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Part of the car the vehicle panel shows while a related settings section is open. */
internal enum class VehicleSceneFocus { NONE, ENGINE, CABIN, FUEL, TIRES }

/** Shared between the settings overlay and the vehicle panel, which live in different windows. */
internal object VehicleSceneFocusRequest {
    private val mutableFocus = MutableStateFlow(VehicleSceneFocus.NONE)
    val focus: StateFlow<VehicleSceneFocus> = mutableFocus.asStateFlow()

    fun set(focus: VehicleSceneFocus) {
        mutableFocus.value = focus
    }
}
