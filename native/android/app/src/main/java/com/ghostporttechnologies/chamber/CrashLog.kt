package com.ghostporttechnologies.chamber

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

class ChamberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * When Chamber stops because of a bug, what failed is kept on the phone (app-private, never sent anywhere) and shown
 * the next time the app opens, so the person can copy it and pass it on. One report: a newer crash replaces it.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"
    private const val MAX = 8_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Keeper) return
        Thread.setDefaultUncaughtExceptionHandler(Keeper(app, previous))
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.ifBlank { null }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    fun describe(thread: String, e: Throwable, version: String = BuildConfig.VERSION_NAME): String =
        ("Chamber $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), thread $thread\n" +
            e.stackTraceToString()).take(MAX)

    private class Keeper(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, e: Throwable) {
            runCatching { File(app.filesDir, FILE).writeText(describe(thread.name, e)) }
            previous?.uncaughtException(thread, e) // Android's own handling: the app still closes
        }
    }
}
