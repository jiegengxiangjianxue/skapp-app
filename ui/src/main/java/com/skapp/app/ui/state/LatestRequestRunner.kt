package com.skapp.app.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Finish the active run before processing the latest request; never overlap archive writes. */
internal class LatestRequestRunner<K : Any>(scope: CoroutineScope, run: suspend (K) -> Unit) {
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var active: K? = null
    private var pending: K? = null
    private var stopped = false

    init {
        scope.launch {
            try {
                for (signal in wake) {
                    while (true) {
                        val next = synchronized(lock) {
                            pending?.also { pending = null; active = it }
                        } ?: break
                        try {
                            run(next)
                        } finally {
                            synchronized(lock) { active = null }
                        }
                    }
                }
            } finally {
                synchronized(lock) { stopped = true; pending = null; active = null }
                wake.close()
            }
        }
    }

    fun submit(key: K, force: Boolean = false) {
        synchronized(lock) {
            if (stopped || (!force && key == pending)) return
            if (!force && key == active) {
                pending = null
                return
            }
            pending = key
            wake.trySend(Unit)
        }
    }
}
