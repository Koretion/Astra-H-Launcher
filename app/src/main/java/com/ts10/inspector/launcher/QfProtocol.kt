package com.ts10.inspector.launcher

/** Broadcasts and packet identifiers of the QF head unit shared by several runtime modules. */

internal const val ACTION_ACC_ON = "com.qf.action.ACC_ON"
internal const val ACTION_ACC_OFF = "com.qf.action.ACC_OFF"
internal const val ACTION_BACKCAR_START = "com.qf.action.BACKCAR_START"
internal const val ACTION_BACKCAR_STOP = "com.qf.action.BACKCAR_STOP"
internal const val ACTION_QF_DATA_SHARE = "com.qf.vehicle.action.DATA_SHARE"
internal const val ACTION_QF_SWA = "com.qf.vehicle.action.swa"
internal const val ACTION_QF_OUTSIDE_TEMPERATURE = "com.qf.vehicle.action.TEMP_STRING"
internal const val ACTION_QF_RADIO_UPDATE = "com.qf.radio.update_action"
internal const val ACTION_QF_BLUETOOTH_MUSIC_INFO = "com.qf.action.BT.MUSIC.INFO"
internal const val ACTION_QF_CAR_BACKLIGHT_CONTROL = "com.qf.action.car_backlight_control"
internal const val ACTION_QF_POPUP_ENABLE = "com.qf.vehicle.action.popup_enable"
internal const val ACTION_QF_AUX_VISIBILITY = "com.qf.vehicle.Action_AuxVisibility"
internal const val QF_PACKET_HEADER = 0x2e
internal const val QF_PACKET_CLIMATE = 1
internal const val QF_PACKET_CLIMATE_ALTERNATE = 11
internal const val QF_PACKET_CAR_BODY = 2
internal const val QF_PACKET_DOORS = 3
internal const val QF_PACKET_RADAR = 4
internal const val QF_PACKET_STEERING = 6
internal const val QF_PACKET_CAMERA = 7
internal const val QF_PACKET_TURN_LIGHT = 14
internal const val QF_DOOR_PAYLOAD_SIZE = 3
internal const val DIRECT_CAN_TIMEOUT_MS = 2_000L
internal const val CAR_BACKLIGHT_UNKNOWN = Int.MIN_VALUE
internal val RUNTIME_BROADCAST_ACTIONS = setOf(
    ACTION_ACC_ON,
    ACTION_ACC_OFF,
    ACTION_BACKCAR_START,
    ACTION_BACKCAR_STOP,
    ACTION_QF_DATA_SHARE,
    ACTION_QF_SWA,
    ACTION_QF_OUTSIDE_TEMPERATURE,
    ACTION_QF_RADIO_UPDATE,
    ACTION_QF_BLUETOOTH_MUSIC_INFO,
    ACTION_QF_CAR_BACKLIGHT_CONTROL,
)
