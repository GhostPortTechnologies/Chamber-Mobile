package com.ghostporttechnologies.chamber.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.ChamberException
import com.ghostporttechnologies.chamber.data.Chamber
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Once someone has signed in: ask for permission to notify (once), then tell Chamber this phone's push token. */
object Push {

    private const val LOG = "ChamberPush"
    private const val RETRY_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var registered: String? = null // token + session last accepted; memory only
    @Volatile private var lastTry = 0L

    /** Builds without a Firebase config have push off. */
    fun available(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    /** Safe to call often. Does nothing in a build without Firebase or before sign-in. */
    fun start(activity: Activity) {
        if (!available(activity) || Chamber.vault(activity).read() == null) return
        Notifier.createChannels(activity)
        askOnce(activity)
        val app = activity.applicationContext
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true // off in the manifest, so no token exists before the first sign-in
        messaging.token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null // .result throws on a failed task
            if (token != null) register(app, token) else Log.w(LOG, "no push token yet")
        }
    }

    private fun askOnce(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences("push", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        ActivityCompat.requestPermissions(activity, arrayOf(permission), 1)
    }

    fun register(context: Context, token: String) {
        val vault = Chamber.vault(context)
        scope.launch {
            lock.withLock {
                val session = vault.read() ?: return@launch
                val key = token + session
                val now = System.currentTimeMillis()
                if (key == registered || now - lastTry < RETRY_MS) return@launch
                lastTry = now
                try {
                    ChamberApi { session }.registerPush(token, ChamberApi.APP_HEADER.removePrefix("android; ").ifBlank { "Android phone" })
                    registered = key
                } catch (e: ChamberException) {
                    // 503: the server has no Firebase key yet. Next time the app opens, it tries again.
                    Log.w(LOG, "device not registered: HTTP ${e.status}") // never the token or the session
                }
            }
        }
    }

    /**
     * Signing out: this phone's token is thrown away, so nothing more arrives for the old session, and anything
     * Chamber showed leaves the screen. Call before the session is cleared.
     */
    fun signedOut(context: Context) {
        registered = null
        lastTry = 0
        Notifier.clearAll(context)
        if (!available(context)) return
        FirebaseMessaging.getInstance().apply {
            isAutoInitEnabled = false
            deleteToken()
        }
    }
}
