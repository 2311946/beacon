package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.BuildConfig

/** Logs request bodies only in debug builds to avoid release-only serialization overhead. */
internal inline fun logRequestBody(tag: String, name: String, body: () -> String) {
    if (BuildConfig.DEBUG) Log.i(tag, "$name: ${body()}")
}
