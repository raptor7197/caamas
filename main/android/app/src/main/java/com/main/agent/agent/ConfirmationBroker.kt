package com.main.agent.agent

import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges the ReAct loop and the UI for dangerous tool confirmations.
 *
 * Loop calls [request] and suspends. UI calls [resolve] when user taps
 * Confirm or Deny. Loop resumes with true/false and retries or skips.
 */
class ConfirmationBroker {

    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    /** Suspend until the user confirms (true) or denies (false). */
    suspend fun request(): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        return d.await()
    }

    /** Call from the UI thread when the user taps Confirm or Deny. */
    fun resolve(confirmed: Boolean) {
        pending?.complete(confirmed)
        pending = null
    }

    val hasPending: Boolean get() = pending != null
}
