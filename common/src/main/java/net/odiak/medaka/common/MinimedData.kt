package net.odiak.medaka.common

import java.time.LocalDateTime

class MinimedData(val sgs: List<SensorGlucoseData>, val lastSG: SensorGlucoseData?) {
    val lastSGString: String
        get() = lastSG?.sg?.toString() ?: "??"

    val lastSGDateTime: LocalDateTime?
        get() = lastSG?.datetime?.parseISODateTime()

    val lastSGDiffString: String
        get() {
            if (sgs.size < 2) return ""
            return (sgs[sgs.size - 1].sg - sgs[sgs.size - 2].sg).signed()
        }
}

class SensorGlucoseData(
    val datetime: String,
    val kind: String,
    val relativeOffset: Int,
    val sensorState: String,
    val sg: Int,
    val timeChange: Boolean
)