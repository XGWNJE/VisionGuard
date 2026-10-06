package com.xgwnje.visionguard.detector.stream

import android.util.Log
import com.xgwnje.visionguard.detector.BuildConfig

/** Local timing for debuggable test builds; no accounts, tokens or image contents. */
object MediaDiagnostics {
    inline fun log(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i("VG_MediaPerf", message())
    }
}
