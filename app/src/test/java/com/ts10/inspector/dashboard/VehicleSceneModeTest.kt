package com.ts10.inspector.dashboard

import com.ts10.inspector.settings.DashboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSceneModeTest {
    @Test
    fun `new installations use the validated compatible 3D scene`() {
        assertEquals(VehicleSceneMode.THREE_D, DashboardSettings().vehicleSceneMode)
        assertEquals(VehicleSceneMode.THREE_D, VehicleSceneMode.fromStored(null))
    }

    @Test
    fun `unknown stored values use the production default`() {
        assertEquals(VehicleSceneMode.THREE_D, VehicleSceneMode.fromStored("UNKNOWN"))
    }

    @Test
    fun `known development modes survive preference parsing`() {
        VehicleSceneMode.entries.forEach { mode ->
            assertEquals(mode, VehicleSceneMode.fromStored(mode.name))
        }
    }
}
