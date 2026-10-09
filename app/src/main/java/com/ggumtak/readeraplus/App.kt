package com.ggumtak.readeraplus

import android.app.Application
import com.ggumtak.readeraplus.data.Library
import com.ggumtak.readeraplus.format.Documents
import com.ggumtak.readeraplus.render.FontManager
import com.ggumtak.readeraplus.reader.ResumeState
import com.ggumtak.readeraplus.settings.Settings

/** Keeps startup cheap: only in-memory registries are initialised here; no disk scans. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.init(this)
        ResumeState.init(this)
        if (BuildConfig.DEBUG) com.ggumtak.readeraplus.data.DebugSeed.register(this)
        Documents.cacheDir = cacheDir
        Library.init(this)
        FontManager.init(this)
    }
}
