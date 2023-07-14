package net.odiak.medaka

class MinimedData(val sgs: List<SensorGlucoseData>, val lastSG: SensorGlucoseData?)

class SensorGlucoseData(
    val datetime: String,
    val kind: String,
    val relativeOffset: Int,
    val sensorState: String,
    val sg: Int,
    val timeChange: Boolean
)