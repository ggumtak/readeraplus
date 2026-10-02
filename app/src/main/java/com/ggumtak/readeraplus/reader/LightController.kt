package com.ggumtak.readeraplus.reader

import android.app.Activity
import android.os.Handler
import com.ggumtak.readeraplus.settings.AppSettings

internal interface LightHost {                        // implemented by ReaderActivity (READER_A)
    val activity: Activity
    val handler: Handler
    val app: AppSettings                              // the live one
    val chromeVisible: Boolean
    fun saveApp(a: AppSettings)                       // ReaderActivity.saveApp
    fun setPageBrightnessSwipe(on: Boolean)           // page.brightnessSwipe = on
    fun showChrome()                                  // setChromeVisible(true)
}

internal class LightController(private val host: LightHost) {
    companion object { const val ASK_NONE=0; const val ASK_WINDOW=1; const val ASK_DEVICE=2 }
    fun attach(chrome: ReaderChrome) {} // R3 stub (owner: RU)
    fun onCreate() {} // R3 stub (owner: RU)
    fun afterFirstPage() {} // R3 stub (owner: RU)
    fun onAppSettingsApplied() { ReaderWindow.applyBrightness(host.activity, host.app.brightness) } // R3 stub (owner: RU)
    fun onResume() {} // R3 stub (owner: RU)
    fun onPause() {} // R3 stub (owner: RU)
    fun onDestroy(finishing: Boolean) {} // R3 stub (owner: RU)
    fun markOwnLaunch() {} // R3 stub (owner: RU)
    fun currentPos(): Float = host.app.brightness // R3 stub (owner: RU)
    fun onDrag(pos: Float, done: Boolean) {} // R3 stub (owner: RU)
    fun onAuto() {} // R3 stub (owner: RU)
    fun onSwipeSwitch(on: Boolean) {} // R3 stub (owner: RU)
    fun onAnswer(yes: Boolean) {} // R3 stub (owner: RU)
    fun onDeviceSwitch(on: Boolean) {} // R3 stub (owner: RU)
    fun onOpenPanel() {} // R3 stub (owner: RU)
    fun bind() {} // R3 stub (owner: RU)
    val swipeUsable: Boolean get() = false // R3 stub (owner: RU)
}
