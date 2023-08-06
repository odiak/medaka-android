package net.odiak.medaka.common

import com.squareup.moshi.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MinimedData(
    val sgs: List<SensorGlucose>,
    val lastSG: SensorGlucose?,
    val basal: Basal,
    @Json(name = "pumpBannerState")
    val pumpBannerStates: List<PumpBannerState> = emptyList(),
    val timeToNextCalibrationMinutes: Int? = null
) {
    val lastSGString: String
        get() = lastSG?.sgText ?: "??"

    val lastSGDateTime: LocalDateTime?
        get() = lastSG?.datetime?.parseISODateTime()

    val lastSGDiffString: String
        get() {
            if (sgs.size < 2) return ""
            return (sgs[sgs.size - 1].sg - sgs[sgs.size - 2].sg).signed()
        }
}

class SensorGlucose(
    val datetime: String?,
    val kind: String,
    val relativeOffset: Int?,
    val sensorState: String?,
    val sg: Int,
    val timeChange: Boolean?
) {
    companion object {
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    }

    object SensorStates {
        const val NO_ERROR_MESSAGE = "NO_ERROR_MESSAGE"
        const val Above400MGDL = "SG_ABOVE_400_MGDL"
        const val CALIBRATION_REQUIRED = "CALIBRATION_REQUIRED"
        const val NO_DATA_FROM_PUMP = "NO_DATA_FROM_PUMP"
        const val WAIT_TO_CALIBRATE = "WAIT_TO_CALIBRATE"
        const val DO_NOT_CALIBRATE = "DO_NOT_CALIBRATE"
        const val CALIBRATING = "CALIBRATING"
        const val WARM_UP = "WARM_UP"
        const val CHANGE_SENSOR = "CHANGE_SENSOR"
        const val NORMAL = "NORMAL"
        const val UNKNOWN = "UNKNOWN"
    }

    val sgText: String
        get() {
            return when (sensorState) {
                SensorStates.NO_ERROR_MESSAGE, SensorStates.NORMAL -> "${sg}mg/dL"
                SensorStates.Above400MGDL -> "over 400mg/dL"
                SensorStates.CALIBRATION_REQUIRED -> "calibration required"
                SensorStates.NO_DATA_FROM_PUMP -> "no data from pump"
                SensorStates.WAIT_TO_CALIBRATE -> "wait to calibrate"
                SensorStates.DO_NOT_CALIBRATE -> "do not calibrate"
                SensorStates.CALIBRATING -> "calibrating"
                SensorStates.WARM_UP -> "warming up"
                SensorStates.CHANGE_SENSOR -> "change sensor"
                SensorStates.UNKNOWN -> "unknown"
                else -> "$sensorState(${sg})"
            }
        }

    val timeText: String
        get() = datetime?.parseISODateTime()?.format(timeFormat) ?: "??"
}

class Basal(
    val activeBasalPattern: String,
    val basalRate: Double,
    val tempBasalRate: Double? = null
    // tempBasalType: String? = null,
    // tempBasalTimeRemaining: Int? = null
)

class PumpBannerState(val type: String, val timeRemaining: Int? = null)
