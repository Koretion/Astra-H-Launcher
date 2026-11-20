package com.ts10.inspector.vehicle.can

import com.ts10.inspector.ui.UiText
import com.ts10.inspector.R
import java.util.Locale

/**
 * Passive, bounded discovery aid for body warnings that do not yet have a validated decoder.
 *
 * It never assigns a vehicle fault from an unknown payload. Instead it retains repeated variants
 * on the low/mid-speed buses so an exported log can be compared with a deliberately labelled
 * physical event. High-rate measurements and already decoded controls are excluded to keep the
 * report useful.
 */
internal class CanFaultDiscoveryTracker(
    private val maximumSignatures: Int = 192,
    private val minimumOccurrences: Long = 3,
) {
    private data class FrameKey(val bus: Esp32CanBus, val identifier: Long)
    private data class SignatureKey(val frame: FrameKey, val payload: String)
    private data class SignatureStats(
        var occurrences: Long,
        val firstTimestampUs: Long,
        var lastTimestampUs: Long,
        var intervalTotalUs: Long = 0,
        var intervalSamples: Long = 0,
        var transitionsInto: Long = 0,
    )

    private val signatures = linkedMapOf<SignatureKey, SignatureStats>()
    private val lastPayloadByFrame = mutableMapOf<FrameKey, String>()

    fun accept(frame: Esp32CanFrame) {
        if (frame.rtr || frame.data.isEmpty() || !isDiscoveryFrame(frame)) return
        val frameKey = FrameKey(frame.bus, frame.identifier)
        val payload = frame.data.toHex()
        if (isNormalKnownSignature(frameKey, payload)) {
            lastPayloadByFrame[frameKey] = payload
            return
        }
        val previousPayload = lastPayloadByFrame.put(frameKey, payload)
        val signatureKey = SignatureKey(frameKey, payload)
        val current = signatures[signatureKey]
        if (current == null) {
            signatures[signatureKey] = SignatureStats(
                occurrences = 1,
                firstTimestampUs = frame.timestampUs,
                lastTimestampUs = frame.timestampUs,
                transitionsInto = if (previousPayload != null && previousPayload != payload) 1 else 0,
            )
        } else {
            val interval = frame.timestampUs - current.lastTimestampUs
            if (interval > 0) {
                current.intervalTotalUs += interval
                current.intervalSamples++
            }
            current.occurrences++
            current.lastTimestampUs = frame.timestampUs
            if (previousPayload != null && previousPayload != payload) current.transitionsInto++
        }
        trimIfNeeded()
    }

    fun snapshot(): List<CanFaultCandidate> = signatures.mapNotNull { (key, stats) ->
        val confirmed = confirmedMeaning(key)
        val averagePeriodMs = if (stats.intervalSamples > 0) {
            stats.intervalTotalUs.toDouble() / stats.intervalSamples / 1_000.0
        } else {
            null
        }
        val repeatedCandidate = stats.occurrences >= minimumOccurrences &&
            stats.transitionsInto > 0 &&
            averagePeriodMs?.let { it <= MAX_CANDIDATE_PERIOD_MS } == true
        if (confirmed == null && !repeatedCandidate) return@mapNotNull null
        CanFaultCandidate(
            bus = key.frame.bus,
            identifier = key.frame.identifier,
            payload = key.payload,
            occurrences = stats.occurrences,
            transitionsInto = stats.transitionsInto,
            averagePeriodMs = averagePeriodMs,
            meaning = confirmed ?: UiText.of(R.string.fault_unidentified),
            confirmed = confirmed != null,
        )
    }.sortedWith(
        compareByDescending<CanFaultCandidate> { it.confirmed }
            .thenByDescending { it.transitionsInto }
            .thenByDescending { it.occurrences },
    ).take(MAX_EXPORTED_CANDIDATES)

    fun reset() {
        signatures.clear()
        lastPayloadByFrame.clear()
    }

    private fun isDiscoveryFrame(frame: Esp32CanFrame): Boolean = when (frame.bus) {
        Esp32CanBus.SW_CAN -> frame.identifier !in SW_DYNAMIC_IDS || frame.identifier == TURN_FRAME_ID
        Esp32CanBus.MS_CAN -> frame.identifier !in MS_DYNAMIC_IDS
        else -> false
    }

    private fun isNormalKnownSignature(frame: FrameKey, payload: String): Boolean =
        frame.bus == Esp32CanBus.SW_CAN && frame.identifier == TURN_FRAME_ID &&
            payload in NORMAL_TURN_SIGNATURES

    private fun confirmedMeaning(key: SignatureKey): UiText? = when {
        key.frame.bus == Esp32CanBus.SW_CAN && key.frame.identifier == TURN_FRAME_ID &&
            key.payload == RIGHT_INDICATOR_LAMP_FAULT ->
            UiText.of(R.string.fault_rear_right_bulb)
        else -> null
    }

    private fun trimIfNeeded() {
        while (signatures.size > maximumSignatures) {
            val removable = signatures.entries
                .filterNot { confirmedMeaning(it.key) != null }
                .minByOrNull { it.value.lastTimestampUs }
                ?: return
            signatures.remove(removable.key)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }

    private companion object {
        const val TURN_FRAME_ID = 0x260L
        const val RIGHT_INDICATOR_LAMP_FAULT = "3A217C"
        const val MAX_CANDIDATE_PERIOD_MS = 5_000.0
        const val MAX_EXPORTED_CANDIDATES = 24

        val NORMAL_TURN_SIGNATURES = setOf("000000", "25437F", "3A437F", "1F437F")

        // Values already decoded or continuously changing; they produce noise, not warning clues.
        val SW_DYNAMIC_IDS = setOf(
            0x115L, 0x135L, 0x160L, 0x175L, 0x190L, 0x230L, 0x305L,
            0x350L, 0x360L, 0x370L, 0x375L, 0x425L, 0x440L, 0x445L, 0x500L, 0x530L,
        )
        val MS_DYNAMIC_IDS = setOf(0x180L, 0x188L, 0x201L, 0x206L, 0x208L, 0x450L, 0x4E8L,
            0x4ECL, 0x4EDL, 0x4EEL, 0x548L, 0x682L, 0x683L, 0x684L, 0x685L, 0x68CL,
            0x697L, 0x6C1L, 0x6C8L)
    }
}

internal data class CanFaultCandidate(
    val bus: Esp32CanBus,
    val identifier: Long,
    val payload: String,
    val occurrences: Long,
    val transitionsInto: Long,
    val averagePeriodMs: Double?,
    val meaning: UiText,
    val confirmed: Boolean,
) {
    val frameLabel: String
        get() = "%s 0x%03X#%s".format(Locale.US, bus.label, identifier, payload)
}
