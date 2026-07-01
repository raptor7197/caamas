package com.main.agent.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges the ReAct loop and the UI for dangerous tool confirmations.
 *
 * Loop calls [request] and suspends. UI calls [resolve] when user taps
 * Confirm or Deny. Loop resumes with true/false and retries or skips.
 */
class ConfirmationBroker {

    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Suspend until the user confirms (true) or denies (false), or until [timeoutMs]
     * elapses, in which case this returns false so the loop doesn't hang forever.
     */
    suspend fun request(timeoutMs: Long = 120_000L): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending = d
        val result = withTimeoutOrNull(timeoutMs) { d.await() }
        if (result == null) pending = null
        return result ?: false
    }

    /** Call from the UI thread when the user taps Confirm or Deny. */
    fun resolve(confirmed: Boolean) {
        pending?.complete(confirmed)
        pending = null
    }

    val hasPending: Boolean get() = pending != null
}
