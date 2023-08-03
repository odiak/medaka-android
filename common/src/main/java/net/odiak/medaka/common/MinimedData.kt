package net.odiak.medaka.common

import java.time.LocalDateTime

class MinimedData(val sgs: List<SensorGlucoseData>, val lastSG: SensorGlucoseData?) {
    val lastSGString: String
        get() = lastSG?.let {
            when (it.sensorState) {
                SensorGlucoseData.SensorStates.OK -> "${it.sg}mg/dL"
                SensorGlucoseData.SensorStates.Above400MGDL -> "over 400mg/dL"
                else -> null
            }
        } ?: "??"

    val lastSGDateTime: LocalDateTime?
        get() = lastSG?.datetime?.parseISODateTime()

    val lastSGDiffString: String
        get() {
            if (sgs.size < 2) return ""
            return (sgs[sgs.size - 1].sg - sgs[sgs.size - 2].sg).signed()
        }
}

class SensorGlucoseData(
    val datetime: String?,
    val kind: String,
    val relativeOffset: Int?,
    val sensorState: String?,
    val sg: Int,
    val timeChange: Boolean?
) {
    object SensorStates {
        const val OK = "NO_ERROR_MESSAGE"
        const val Above400MGDL = "SG_ABOVE_400_MGDL"
    }
}