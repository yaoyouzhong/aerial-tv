package app.orchard.tv.update

/**
 * A pure model for one self-reinstall. Persist the entire state before issuing an installer
 * operation and restore it after process death. Side effects (commit, abandon, UI and timeouts)
 * must be performed by the caller; this model deliberately does not install anything.
 */
internal class SelfCompileAttempt(initial: State = State.Idle) {
    sealed class State {
        data object Idle : State()
        data class Starting(val auto: Boolean, val existingSessions: Set<Int>) : State()
        data class Waiting(val id: Int, val auto: Boolean, val confirming: Boolean = false) : State()
        data class Ambiguous(val auto: Boolean, val existingSessions: Set<Int>) : State()
        data class Failed(val reason: Failure) : State()
        data class Installed(val auto: Boolean) : State()
    }

    enum class Failure { Rejected, Cancelled, ConfirmationRequired, Interrupted, Detached, Other }
    enum class Answer { PendingConfirmation, Success, Cancelled, Rejected, Interrupted, Failure }

    var state: State = initial
        private set

    /** Automatic attempts are once per version. Explicit retries require a separate press. */
    fun start(auto: Boolean, alreadyTriedAutomatically: Boolean, existingSessions: Set<Int>): Boolean {
        if (state is State.Starting || state is State.Waiting || state is State.Ambiguous) return false
        if (auto && alreadyTriedAutomatically) return false
        state = State.Starting(auto, existingSessions)
        return true
    }

    fun created(id: Int) {
        val starting = state as? State.Starting ?: return
        state = State.Waiting(id, starting.auto)
    }

    fun startFailed(error: Exception) {
        if (state !is State.Starting) return
        state = State.Failed(if (error is SecurityException) Failure.Rejected else Failure.Other)
    }

    /** Opening, writing or committing a created session can fail as well. */
    fun submissionFailed(id: Int, error: Exception) {
        val waiting = state as? State.Waiting ?: return
        if (waiting.id != id) return
        state = State.Failed(if (error is SecurityException) Failure.Rejected else Failure.Other)
    }

    /** Forget local tracking only after an explicit user action; never abandon a session here. */
    fun detach() {
        if (state is State.Starting || state is State.Waiting || state is State.Ambiguous) {
            state = State.Failed(Failure.Detached)
        }
    }

    /**
     * Recover the gap between creating a session and saving its ID. The caller supplies only
     * sessions belonging to this app and persists the original list before creating a session.
     * Ambiguous candidates are never abandoned or retried automatically.
     */
    fun recover(currentSessions: Set<Int>) {
        val (auto, existingSessions) = when (val current = state) {
            is State.Starting -> current.auto to current.existingSessions
            is State.Ambiguous -> current.auto to current.existingSessions
            else -> return
        }
        val candidates = currentSessions - existingSessions
        state = when (candidates.size) {
            // An empty list cannot prove a createSession request has stopped in system_server.
            0 -> State.Ambiguous(auto, existingSessions)
            1 -> State.Waiting(candidates.single(), auto)
            else -> State.Ambiguous(auto, existingSessions)
        }
    }

    /** Ignore late callbacks from abandoned sessions or from a previous attempt. */
    fun answer(id: Int, answer: Answer) {
        val waiting = state as? State.Waiting ?: return
        if (waiting.id != id) return
        state = when (answer) {
            Answer.PendingConfirmation -> if (waiting.auto) {
                State.Failed(Failure.ConfirmationRequired)
            } else {
                waiting.copy(confirming = true)
            }
            Answer.Success -> State.Installed(waiting.auto)
            Answer.Cancelled -> State.Failed(Failure.Cancelled)
            Answer.Rejected -> State.Failed(Failure.Rejected)
            Answer.Interrupted -> State.Failed(Failure.Interrupted)
            Answer.Failure -> State.Failed(Failure.Other)
        }
    }
}
