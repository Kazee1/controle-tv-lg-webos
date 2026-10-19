package com.example.tvremote.tv.repository

import com.example.tvremote.core.model.TvDevice
import com.example.tvremote.core.model.TvPowerState
import java.util.Locale

/** Protocol interpretation kept independent from Android and UI lifecycle. */
object ConnectionPolicy {
    fun sameDevice(saved: TvDevice, candidate: TvDevice): Boolean =
        !saved.deviceId.isNullOrBlank() && saved.deviceId.equals(candidate.deviceId, ignoreCase = true)

    fun powerState(value: String?, processing: String? = null): TvPowerState {
        // During shutdown LG can still report Active alongside Request Power Off.
        if (!processing.isNullOrBlank()) return TvPowerState.UNKNOWN
        return when (value?.lowercase(Locale.ROOT)) {
            "active", "on" -> TvPowerState.ON
            "active standby", "standby", "suspend", "power off" -> TvPowerState.STANDBY
            else -> TvPowerState.UNKNOWN
        }
    }

    fun offlineMessage(knownStandby: Boolean): String = if (knownStandby)
        "TV em standby" else "Sem conexão com a TV — desligada ou fora da rede"
}
