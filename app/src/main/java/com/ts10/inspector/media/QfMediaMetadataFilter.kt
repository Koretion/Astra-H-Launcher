package com.ts10.inspector.media

/**
 * Keeps isolated, corrupted RDS programme-service updates from replacing the last stable name.
 *
 * The first name received for a tuning is shown immediately. Later changes on the same frequency
 * must be repeated because the TS10 occasionally emits a single damaged eight-character value
 * between otherwise identical station updates.
 */
internal class QfRadioStationNameFilter(
    private val confirmationsRequired: Int = 2,
) {
    private var tuningKey: String? = null
    private var stableName = ""
    private var candidateName = ""
    private var candidateCount = 0

    init {
        require(confirmationsRequired > 0)
    }

    fun update(band: Int, frequency: String, rawName: String): String {
        val frequencyKey = frequency.trim()
        val newTuningKey = "$band:$frequencyKey"
        val normalizedName = normalizeQfMetadata(rawName).orEmpty()

        if (newTuningKey != tuningKey) {
            tuningKey = newTuningKey
            stableName = normalizedName
            clearCandidate()
            return stableName
        }

        if (normalizedName.isBlank() || normalizedName == stableName) {
            clearCandidate()
            return stableName
        }

        if (stableName.isBlank()) {
            stableName = normalizedName
            clearCandidate()
            return stableName
        }

        if (normalizedName == candidateName) {
            candidateCount++
        } else {
            candidateName = normalizedName
            candidateCount = 1
        }

        if (candidateCount >= confirmationsRequired) {
            stableName = candidateName
            clearCandidate()
        }
        return stableName
    }

    private fun clearCandidate() {
        candidateName = ""
        candidateCount = 0
    }
}

internal fun normalizeQfMetadata(value: String?): String? {
    val normalized = value
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
    return normalized.takeUnless {
        it.isBlank() || it.equals(QF_METADATA_NOT_PROVIDED, ignoreCase = true)
    }
}

private const val QF_METADATA_NOT_PROVIDED = "Not Provided"
