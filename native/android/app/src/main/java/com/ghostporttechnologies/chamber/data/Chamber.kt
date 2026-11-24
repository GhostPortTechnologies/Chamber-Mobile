package com.ghostporttechnologies.chamber.data

import android.content.Context

/**
 * One sign-in vault and one API client for the whole app: the screens, push and notification buttons all share
 * them, so they agree on who is signed in and reuse one connection pool.
 */
object Chamber {
    @Volatile private var vault: TokenVault? = null
    @Volatile private var api: ChamberApi? = null

    fun vault(context: Context): TokenVault =
        vault ?: synchronized(this) { vault ?: TokenVault(context.applicationContext).also { vault = it } }

    fun api(context: Context): ChamberApi =
        api ?: synchronized(this) { api ?: vault(context).let { v -> ChamberApi { v.read() } }.also { api = it } }
}
