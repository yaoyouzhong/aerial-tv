package app.orchard.tv.update

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Finishes a broadcast exactly once, including when the worker misses its deadline. */
internal class BroadcastLease(
    private val schedule: (Runnable) -> Boolean,
    private val cancel: (Runnable) -> Unit,
    private val finish: () -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val timeout = Runnable { complete() }

    fun start() {
        try {
            if (!schedule(timeout)) complete()
        } catch (error: Exception) {
            complete()
            throw error
        }
    }

    fun complete() {
        if (finished.compareAndSet(false, true)) {
            try { cancel(timeout) } finally { finish() }
        }
    }
}

/** A dedicated deadline thread cannot be held up by the main or installer work queues. */
internal class BroadcastDeadlines(
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AerialBroadcastDeadline").apply { isDaemon = true }
    },
    private val timeoutMillis: Long = 8_000L,
) {
    fun lease(finish: () -> Unit): BroadcastLease {
        val future = AtomicReference<ScheduledFuture<*>?>()
        return BroadcastLease(
            schedule = { task ->
                future.set(scheduler.schedule(task, timeoutMillis, TimeUnit.MILLISECONDS))
                true
            },
            cancel = { future.get()?.cancel(false) },
            finish = finish,
        )
    }
}
