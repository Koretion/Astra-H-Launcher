package com.ts10.inspector.vehicle.can.profile

/**
 * Nombres de señal que entiende el launcher. Un perfil puede omitir cualquiera (el coche no la
 * tiene o no se ha encontrado); un nombre que no esté aquí no lo usa ningún decodificador.
 */
internal object CanSignals {
    const val ENGINE_RPM = "engine_rpm"
    const val STEERING_ANGLE_DEG = "steering_angle_deg"
    const val WHEEL_SPEED_FL_KMH = "wheel_speed_fl_kmh"
    const val WHEEL_SPEED_FR_KMH = "wheel_speed_fr_kmh"
    const val WHEEL_SPEED_RL_KMH = "wheel_speed_rl_kmh"
    const val WHEEL_SPEED_RR_KMH = "wheel_speed_rr_kmh"
    const val HANDBRAKE = "handbrake"
    const val TURN_STATE = "turn_state"
    const val EXTERIOR_LIGHTS = "exterior_lights"
    const val HIGH_BEAM = "high_beam"
    const val FUEL_SENDER_RAW = "fuel_sender_raw"
    const val VEHICLE_SPEED_KMH = "vehicle_speed_kmh"
    const val ODOMETER_KM = "odometer_km"
    const val COOLANT_C = "coolant_c"
    const val EXTERIOR_LIGHTS_ON = "exterior_lights_on"
    const val REVERSE = "reverse"
    const val MOTION_STATE_RAW = "motion_state_raw"
    const val CLUTCH = "clutch"
    const val PEDAL_SWITCH_RAW = "pedal_switch_raw"
    const val CENTRAL_LOCK_ACTION = "central_lock_action"
    const val WINDOW_ACTION = "window_action"
    const val HIGH_BEAM_REQUEST = "high_beam_request"
    const val WIPER_MODE = "wiper_mode"
    const val DRIVER_DOOR_OPEN = "driver_door_open"
    const val PASSENGER_DOOR_OPEN = "passenger_door_open"
    const val TRUNK_OPEN = "trunk_open"
    const val CENTRAL_LOCKED = "central_locked"
    const val TURN_LAMPS = "turn_lamps"
    const val CENTRAL_CONTROL_ACTION = "central_control_action"
    const val FRONT_FOG = "front_fog"
    const val REAR_FOG = "rear_fog"
    const val SERVICE_BRAKE = "service_brake"
    const val IGNITION_KEY_INDEX = "ignition_key_index"
    const val OUTDOOR_TEMPERATURE_C = "outdoor_temperature_c"
    const val BATTERY_VOLTAGE = "battery_voltage"

    const val CLIMATE_ISOTP_DECODER = "gm_climate_isotp"

    val all: Set<String> = setOf(
        ENGINE_RPM, STEERING_ANGLE_DEG, WHEEL_SPEED_FL_KMH, WHEEL_SPEED_FR_KMH, WHEEL_SPEED_RL_KMH,
        WHEEL_SPEED_RR_KMH, HANDBRAKE, TURN_STATE, EXTERIOR_LIGHTS, HIGH_BEAM, FUEL_SENDER_RAW,
        VEHICLE_SPEED_KMH, ODOMETER_KM, COOLANT_C, EXTERIOR_LIGHTS_ON, REVERSE, MOTION_STATE_RAW,
        CLUTCH, PEDAL_SWITCH_RAW, CENTRAL_LOCK_ACTION, WINDOW_ACTION, HIGH_BEAM_REQUEST, WIPER_MODE,
        DRIVER_DOOR_OPEN, PASSENGER_DOOR_OPEN, TRUNK_OPEN, CENTRAL_LOCKED, TURN_LAMPS,
        CENTRAL_CONTROL_ACTION, FRONT_FOG, REAR_FOG, SERVICE_BRAKE, IGNITION_KEY_INDEX,
        OUTDOOR_TEMPERATURE_C, BATTERY_VOLTAGE,
    )
}
