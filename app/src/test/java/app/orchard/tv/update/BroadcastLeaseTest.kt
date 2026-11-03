package app.orchard.tv.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BroadcastLeaseTest {
    @Test fun completionCancelsDeadlineAndFinishesOnce() {
        var deadline: Runnable? = null
        val finishes = AtomicInteger()
        var cancellations = 0
        val lease = BroadcastLease({ deadline = it; true }, { cancellations++ }, { finishes.incrementAndGet() })
        lease.start()
        assertEquals(0, finishes.get())
        lease.complete()
        deadline!!.run()
        lease.complete()
        assertEquals(1, finishes.get())
        assertEquals(1, cancellations)
    }

    @Test fun timeoutAndConcurrentCompletionFinishOnce() {
        var deadline: Runnable? = null
        val finishes = AtomicInteger()
        val lease = BroadcastLease({ deadline = it; true }, {}, { finishes.incrementAndGet() })
        lease.start()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit { gate.await(); deadline!!.run() }
            val b = pool.submit { gate.await(); lease.complete() }
            gate.countDown()
            a.get(2, TimeUnit.SECONDS)
            b.get(2, TimeUnit.SECONDS)
            assertEquals(1, finishes.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun schedulingFailureFinishes() {
        val finishes = AtomicInteger()
        BroadcastLease({ false }, {}, { finishes.incrementAndGet() }).start()
        try {
            BroadcastLease({ throw IllegalStateException("rejected") }, {}, { finishes.incrementAndGet() }).start()
        } catch (_: IllegalStateException) {
            // A rejected timer must not leak the PendingResult.
        }
        assertEquals(2, finishes.get())
    }

    @Test fun deadlineRunsWhileMainQueueIsBlocked() {
        deadlineRunsWithBlockedQueue("main")
    }

    @Test fun deadlineRunsWhileInstallerQueueIsBlocked() {
        deadlineRunsWithBlockedQueue("installer")
    }

    private fun deadlineRunsWithBlockedQueue(name: String) {
        val blocked = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "blocked-$name") }
        val clock = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val finishes = AtomicInteger()
        try {
            blocked.submit { entered.countDown(); release.await() }
            check(entered.await(2, TimeUnit.SECONDS))
            val lease = BroadcastDeadlines(clock, 30).lease {
                finishes.incrementAndGet()
                finished.countDown()
            }
            lease.start()
            check(finished.await(2, TimeUnit.SECONDS))
            lease.complete()
            assertEquals(1, finishes.get())
        } finally {
            release.countDown()
            blocked.shutdownNow()
            clock.shutdownNow()
        }
    }
}
