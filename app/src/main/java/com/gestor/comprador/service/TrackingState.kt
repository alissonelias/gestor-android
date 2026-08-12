package com.gestor.comprador.service

import android.location.Location

/** Estado em memória do GPS compartilhado entre o service e a UI. */
object TrackingState {
    @Volatile var lastLocation: Location? = null
    @Volatile var lastSendAt: Long = 0L
    @Volatile var running: Boolean = false
    @Volatile var tripActive: Boolean = false
}
