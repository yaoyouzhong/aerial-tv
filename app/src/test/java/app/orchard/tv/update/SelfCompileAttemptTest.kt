package app.orchard.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCompileAttemptTest {
    @Test fun securityExceptionAllowsExplicitRetry() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(auto = false, alreadyTriedAutomatically = false, emptySet()))
        attempt.startFailed(SecurityException("blocked by installer"))
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.Rejected), attempt.state)
        assertTrue(attempt.start(auto = false, alreadyTriedAutomatically = true, emptySet()))
    }

    @Test fun cancellationAllowsRetryAndIgnoresTheOldSession() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(false, false, emptySet()))
        attempt.created(12)
        attempt.answer(12, SelfCompileAttempt.Answer.PendingConfirmation)
        attempt.answer(12, SelfCompileAttempt.Answer.Cancelled)
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.Cancelled), attempt.state)
        assertTrue(attempt.start(false, true, emptySet()))
        attempt.created(13)
        attempt.answer(12, SelfCompileAttempt.Answer.Success)
        assertEquals(SelfCompileAttempt.State.Waiting(13, false), attempt.state)
    }

    @Test fun exceptionAfterCreatingSessionAlsoAllowsRetry() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(false, false, emptySet()))
        attempt.created(12)
        attempt.submissionFailed(12, SecurityException("write refused"))
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.Rejected), attempt.state)
        assertTrue(attempt.start(false, true, emptySet()))
    }

    @Test fun duplicatePressAndAutomaticRetryAreRejected() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(true, false, emptySet()))
        assertFalse(attempt.start(false, true, emptySet()))
        attempt.created(7)
        assertFalse(attempt.start(false, true, emptySet()))
        attempt.answer(7, SelfCompileAttempt.Answer.Failure)
        assertFalse(attempt.start(true, true, emptySet()))
        assertTrue(attempt.start(false, true, emptySet()))
    }

    @Test fun interruptedCreationRecoversOnlyTheNewSession() {
        val attempt = SelfCompileAttempt(SelfCompileAttempt.State.Starting(false, setOf(1, 2)))
        attempt.recover(setOf(1, 2, 3))
        assertEquals(SelfCompileAttempt.State.Waiting(3, false), attempt.state)
        assertFalse(attempt.start(false, false, emptySet()))
    }

    @Test fun ambiguousRecoveryNeverStartsAnotherSession() {
        val attempt = SelfCompileAttempt(SelfCompileAttempt.State.Starting(false, setOf(1)))
        attempt.recover(setOf(1, 2, 3))
        assertEquals(SelfCompileAttempt.State.Ambiguous(false, setOf(1)), attempt.state)
        assertFalse(attempt.start(false, false, emptySet()))
        attempt.recover(setOf(1))
        assertEquals(SelfCompileAttempt.State.Ambiguous(false, setOf(1)), attempt.state)
        attempt.detach()
        assertTrue(attempt.start(false, false, emptySet()))
    }

    @Test fun autoConfirmationIsNotLaunchedOverAnotherApp() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(true, false, emptySet()))
        attempt.created(5)
        attempt.answer(5, SelfCompileAttempt.Answer.PendingConfirmation)
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.ConfirmationRequired), attempt.state)
        assertTrue(attempt.start(false, true, emptySet()))
    }

    @Test fun missingInterruptedSessionAllowsExplicitRetry() {
        val attempt = SelfCompileAttempt(SelfCompileAttempt.State.Starting(false, setOf(1)))
        attempt.recover(setOf(1))
        assertEquals(SelfCompileAttempt.State.Ambiguous(false, setOf(1)), attempt.state)
        attempt.detach()
        assertTrue(attempt.start(false, false, emptySet()))
    }

    @Test fun successfulReinstallDoesNotClaimCompilation() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(false, false, emptySet()))
        attempt.created(10)
        attempt.answer(10, SelfCompileAttempt.Answer.Success)
        assertEquals(SelfCompileAttempt.State.Installed(false), attempt.state)
    }

    @Test fun detachedAttemptIgnoresLateResults() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(false, false, emptySet()))
        attempt.created(11)
        attempt.detach()
        attempt.answer(11, SelfCompileAttempt.Answer.Success)
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.Detached), attempt.state)
    }

    @Test fun missingConfirmationIsReportedAsInterrupted() {
        val attempt = SelfCompileAttempt()
        assertTrue(attempt.start(false, false, emptySet()))
        attempt.created(11)
        attempt.answer(11, SelfCompileAttempt.Answer.PendingConfirmation)
        attempt.answer(11, SelfCompileAttempt.Answer.Interrupted)
        assertEquals(SelfCompileAttempt.State.Failed(SelfCompileAttempt.Failure.Interrupted), attempt.state)
    }
}
