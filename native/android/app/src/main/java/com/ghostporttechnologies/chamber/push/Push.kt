package com.ghostporttechnologies.chamber.push

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ghostporttechnologies.chamber.data.Chamber
import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.ChamberException
import com.ghostporttechnologies.chamber.data.ChamberJson
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * How this phone hears that something is waiting: Google's Firebase (T-0349) or, without Google, a UnifiedPush
 * distributor such as ntfy (T-0388). Either way the push is only a wake-up; the app then asks Chamber itself.
 * One of the two is active at a time, so nothing arrives twice.
 */
object Push {

    private const val LOG = "ChamberPush"
    private const val RETRY_MS = 30_000L
    private const val REFRESH_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val waking = Mutex()
    @Volatile private var googleSent: String? = null // token + session last accepted; memory only
    @Volatile private var unifiedSent: String? = null // address + keys + session last accepted; memory only
    @Volatile private var lastTry = 0L
    @Volatile private var unifiedStart = 0L
    @Volatile private var pickerShown = false // Android's "which push app?" dialog, at most once per run

    /** What Settings shows about alerts on this phone. */
    data class Status(
        val provider: Provider? = null,
        val google: Boolean = false,
        /** The installed distributor app's name, e.g. "ntfy"; null when there is none. */
        val distributor: String? = null,
        /** Android lets Chamber show notifications. */
        val allowed: Boolean = true,
        /** How Chamber names this phone's route once registered, e.g. "UnifiedPush (ntfy.sh)". */
        val via: String = "",
        val problem: String? = null,
        /** After a 403: the push servers the workspace allows. */
        val hosts: List<String> = emptyList(),
        val testing: Boolean = false,
        val tested: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** A build with a Firebase config, on a phone with Google Play services. */
    private fun google(context: Context): Boolean =
        FirebaseApp.getApps(context).isNotEmpty() &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun distributorName(context: Context): String? {
        val pkg = UnifiedPush.getDistributors(context).firstOrNull { it != context.packageName } ?: return null
        return runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse { if (it is PackageManager.NameNotFoundException) null else pkg }
    }

    private fun chosen(context: Context): Provider? =
        prefs(context).getString("chosen", null)?.let { name -> Provider.entries.firstOrNull { it.name == name } }

    fun provider(context: Context): Provider? =
        PushRules.provider(google(context), distributorName(context) != null, chosen(context))

    /** Re-reads what the phone has; Settings calls it through [start] on every resume. */
    fun refresh(context: Context) {
        val name = distributorName(context)
        val google = google(context)
        _status.update {
            it.copy(
                provider = PushRules.provider(google, name != null, chosen(context)),
                google = google,
                distributor = name,
                allowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
        }
    }

    /** Safe to call often. Does nothing before sign-in. */
    fun start(activity: Activity) {
        val context = activity.applicationContext
        refresh(context)
        if (Chamber.vault(context).read() == null) return
        Notifier.createChannels(activity) // the permission to show them is asked once by MainActivity, with driving detection
        startProvider(activity, picked = false)
    }

    private fun startProvider(activity: Activity, picked: Boolean) {
        val context = activity.applicationContext
        when (provider(context)) {
            Provider.Google -> { stopUnified(context); startGoogle(context) }
            Provider.UnifiedPush -> { stopGoogle(context); startUnified(activity, picked) }
            null -> stopGoogle(context)
        }
    }

    /** Settings: the person picks Google or UnifiedPush (offered only when the phone has both). */
    fun choose(activity: Activity, provider: Provider) {
        prefs(activity).edit().putString("chosen", provider.name).apply()
        _status.update { it.copy(problem = null, hosts = emptyList(), via = "", tested = null) }
        refresh(activity.applicationContext)
        if (Chamber.vault(activity).read() != null) startProvider(activity, picked = true)
    }

    // ── Google (Firebase) ───────────────────────────────────────────────────────────────────────

    private fun startGoogle(context: Context) {
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true // off in the manifest, so no token exists before the first sign-in
        messaging.token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null // .result throws on a failed task
            if (token != null) register(context, token) else Log.w(LOG, "no push token yet")
        }
    }

    /** Firebase's token for this phone, from [startGoogle] or Firebase itself. */
    fun register(context: Context, token: String) {
        if (provider(context) != Provider.Google) return // UnifiedPush is in use, so Google stays quiet
        val vault = Chamber.vault(context)
        scope.launch {
            lock.withLock {
                val session = vault.read() ?: return@launch
                val key = token + session
                val now = System.currentTimeMillis()
                if (key == googleSent || now - lastTry < RETRY_MS) return@launch
                lastTry = now
                try {
                    val reply = ChamberApi { session }.registerPush(token, deviceName())
                    googleSent = key
                    saveId(context, "googleId", reply.id)
                    _status.update { it.copy(via = reply.via.ifBlank { "Google (Firebase)" }, problem = null, hosts = emptyList()) }
                } catch (e: ChamberException) {
                    // 503: the server has no Firebase key yet. Next time the app opens, it tries again.
                    Log.w(LOG, "device not registered: HTTP ${e.status}") // never the token or the session
                    _status.update { it.copy(problem = e.message) }
                }
            }
        }
    }

    /** Google off: no token, and Chamber forgets this phone's Google entry. */
    private fun stopGoogle(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val messaging = FirebaseMessaging.getInstance()
        if (messaging.isAutoInitEnabled) {
            messaging.isAutoInitEnabled = false
            messaging.deleteToken()
        }
        googleSent = null
        removeEntry(context, "googleId")
    }

    // ── UnifiedPush (T-0388) ────────────────────────────────────────────────────────────────────

    private fun startUnified(activity: Activity, picked: Boolean = false) {
        val context = activity.applicationContext
        val now = System.currentTimeMillis()
        if (!picked && unifiedSent != null && now - unifiedStart < REFRESH_MS) return // set up a moment ago
        if (UnifiedPush.getAckDistributor(context) == null &&
            UnifiedPush.resolveDefaultDistributor(context) is ResolvedDistributor.ToSelect
        ) {
            // Several push apps and none picked: Android asks. Once per run, or when the person picks in Settings,
            // so backing out of the dialog doesn't bring it straight back.
            if (pickerShown && !picked) return
            pickerShown = true
        }
        unifiedStart = now
        scope.launch {
            val key = vapidKey(context)
            if (key == null) {
                _status.update { it.copy(problem = "Can't reach Chamber to set up alerts. They're set up next time you open the app online.") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                // Uses the distributor picked before; the first time, the only one installed, or Android asks which.
                UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                    if (!ok) {
                        _status.update { it.copy(problem = "No push app was picked. Install ntfy from F-Droid, then open Chamber again.") }
                        return@tryUseCurrentOrDefaultDistributor
                    }
                    // Registering again on every start is what the library advises: it repairs a lost registration.
                    runCatching { UnifiedPush.register(context, messageForDistributor = "Chamber", vapid = key) }
                        .onFailure { e ->
                            Log.w(LOG, "UnifiedPush register failed: ${e.javaClass.simpleName}")
                            _status.update { it.copy(problem = "Your push app couldn't set up alerts. Try again later.") }
                        }
                }
            }
        }
    }

    /** Chamber's VAPID public key: fresh when online, else the one fetched before. */
    private suspend fun vapidKey(context: Context): String? {
        val fresh = runCatching { Chamber.api(context).pushInfo().publicKey }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fresh != null) prefs(context).edit().putString("vapid", fresh).apply()
        return fresh ?: prefs(context).getString("vapid", null)
    }

    /** The distributor gave this phone an address and Web Push keys: tell Chamber. */
    fun unifiedEndpoint(context: Context, url: String, p256dh: String?, auth: String?) {
        if (p256dh == null || auth == null) {
            // Chamber only sends encrypted wake-ups, so an address without keys is no use.
            _status.update { it.copy(problem = "Your push app didn't give Chamber the keys it needs. Update the push app.") }
            return
        }
        prefs(context).edit().putString("endpoint", url).apply()
        scope.launch {
            lock.withLock {
                val session = Chamber.vault(context).read() ?: return@launch
                val key = url + p256dh + auth + session
                if (key == unifiedSent) return@launch
                try {
                    val reply = ChamberApi { session }.registerUnifiedPush(url, p256dh, auth, deviceName())
                    unifiedSent = key
                    saveId(context, "unifiedId", reply.id)
                    _status.update { it.copy(via = reply.via.ifBlank { "UnifiedPush" }, problem = null, hosts = emptyList()) }
                } catch (e: ChamberException) {
                    Log.w(LOG, "UnifiedPush address not registered: HTTP ${e.status}") // never the address or keys
                    _status.update { it.copy(problem = e.message, hosts = e.allowed) }
                }
            }
        }
    }

    fun unifiedFailed(context: Context, reason: FailedReason) {
        val app = _status.value.distributor ?: "your push app"
        val problem = when (reason) {
            FailedReason.NETWORK -> "$app couldn't reach its server. Alerts are set up once you're online."
            FailedReason.ACTION_REQUIRED -> "Open $app: it needs you to do something before Chamber's alerts can work."
            FailedReason.VAPID_REQUIRED -> "$app needs Chamber's key, and Chamber couldn't be reached. Open Chamber again online."
            else -> "$app couldn't set up alerts. Open Chamber again to retry."
        }
        _status.update { it.copy(problem = problem) }
        refresh(context)
    }

    /** The distributor dropped Chamber (uninstalled, or the person removed it there). */
    fun unifiedGone(context: Context) {
        unifiedSent = null
        prefs(context).edit().remove("endpoint").apply()
        removeEntry(context, "unifiedId")
        _status.update { it.copy(via = "", problem = "Your push app stopped Chamber's alerts. Open Chamber again to set them up.") }
        refresh(context)
    }

    private fun stopUnified(context: Context) {
        if (prefs(context).getString("endpoint", null) == null && UnifiedPush.getAckDistributor(context) == null) return
        runCatching { UnifiedPush.unregister(context) }
        unifiedSent = null
        prefs(context).edit().remove("endpoint").apply()
        removeEntry(context, "unifiedId")
    }

    /** Settings > Send a test notification, for UnifiedPush; it shows the way any test does (Notifier.showTest). */
    fun sendTest(context: Context) {
        val app = context.applicationContext
        val endpoint = prefs(app).getString("endpoint", null)
        _status.update { it.copy(testing = true, tested = null) }
        scope.launch {
            val said = if (endpoint == null) "This phone isn't set up for alerts yet. Open Chamber again online." else try {
                val reply = Chamber.api(app).sendPushTest(endpoint)
                if (reply.pushService == "gone") unifiedSent = null // Chamber dropped the address; the next start registers again
                PushRules.testResult(reply.pushService, reply.why, reply.via)
            } catch (e: ChamberException) {
                e.message
            } catch (e: Exception) {
                "Something went wrong. Try again."
            }
            _status.update { it.copy(testing = false, tested = said) }
        }
    }

    /** A UnifiedPush wake-up: ask Chamber what is waiting. One at a time; [done] runs when it's over. */
    fun wake(context: Context, done: () -> Unit = {}) {
        scope.launch {
            val power = context.getSystemService(PowerManager::class.java)
            val awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chamber:wake").apply { acquire(30_000L) }
            try {
                waking.withLock { withTimeoutOrNull(25_000L) { Notifier.onPush(context) } }
            } catch (e: Exception) {
                Log.w(LOG, "wake-up check failed: ${e.javaClass.simpleName}")
            } finally {
                if (awake.isHeld) awake.release()
                done()
            }
        }
    }

    // ── Both ────────────────────────────────────────────────────────────────────────────────────

    private fun deviceName() = ChamberApi.APP_HEADER.removePrefix("android; ").ifBlank { "Android phone" }

    private fun saveId(context: Context, name: String, id: JsonElement?) {
        prefs(context).edit().apply { if (id == null) remove(name) else putString(name, id.toString()) }.apply()
    }

    /** Asks Chamber to forget an entry this phone made, if there is one. */
    private fun removeEntry(context: Context, name: String) {
        val saved = prefs(context).getString(name, null) ?: return
        prefs(context).edit().remove(name).apply()
        val id = runCatching { ChamberJson.parseToJsonElement(saved) }.getOrNull() ?: return
        scope.launch { runCatching { Chamber.api(context).removePush(id) } } // gone already or offline: Chamber drops dead entries itself
    }

    /**
     * Signing out: nothing more arrives for the old session, and anything Chamber showed leaves the screen. Chamber
     * removes this session's entries itself when it signs out. Call before the session is cleared.
     */
    fun signedOut(context: Context) {
        googleSent = null
        unifiedSent = null
        lastTry = 0
        Notifier.clearAll(context)
        runCatching { UnifiedPush.unregister(context) }
        prefs(context).edit().remove("endpoint").remove("googleId").remove("unifiedId").apply()
        _status.update { Status() }
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().apply {
            isAutoInitEnabled = false
            deleteToken()
        }
    }
}
