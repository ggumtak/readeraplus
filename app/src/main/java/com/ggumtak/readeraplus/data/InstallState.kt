package com.ggumtak.readeraplus.data

import android.content.Context

object InstallState {
    const val KEY_INSTALL_ID="installId"; const val KEY_INSTALLED_AT="installId.at"; const val KEY_OFFER="restoreOffer.state"
    fun ensure(context: Context) {} // R3 stub (owner: DA-C)
    fun verify(context: Context) {} // R3 stub (owner: DA-C)
    fun installId(context: Context): String = "" // R3 stub (owner: DA-C)
    fun id8(context: Context): String = "" // R3 stub (owner: DA-C)
    fun offerPending(context: Context): Boolean = false // R3 stub (owner: DA-C)
    fun settleOffer(context: Context) {} // R3 stub (owner: DA-C)
}
