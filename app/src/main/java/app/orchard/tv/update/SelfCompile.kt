package app.orchard.tv.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import app.orchard.tv.system.DiagLog
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reinstall the running APK with its dex metadata. Automatic attempts are limited to one per
 * version; a failed or cancelled attempt can only be retried by an explicit Control Center press.
 */
object SelfCompile {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "self_compile"
    private const val KEY_TRIED = "tried_version"
    private const val COMPILED_BYTES = 1L shl 20
    private const val AUTO_DELAY_MS = 20_000L
    private const val SILENT_SESSION_TIMEOUT_MS = 120_000L
    private const val CONFIRM_SESSION_TIMEOUT_MS = 8_000L
    private const val MISSING_SESSION_GRACE_MS = 8_000L
    private const val RETURN_GRACE_MS = 5_000L

    enum class Notice {
        None, Waiting, Rejected, Cancelled, ConfirmationRequired, Interrupted, RecoveryRequired,
        Detached, Failed, Installed, UpdateBusy,
    }

    var offered by mutableStateOf(false); private set
    var notice by mutableStateOf(Notice.None); private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AerialSelfCompile").apply { isDaemon = true }
    }
    private var submitWork: (Runnable) -> Unit = { io.execute(it) }
    private var workerActive = false
    private var pendingActions = 0
    private var recoveryQueued = false
    private var recoveryAgain = false
    private var recoveryRequired = false
    private var inFront = false
    private var resumed = false
    private var checkedThisRun = false
    private var autoTriedThisRun = false
    private var loaded = false
    private var app: Context? = null
    private var attempt = SelfCompileAttempt()
    private var token = ""
    private var attemptBoot = -1
    private var sessionCreatedHere = false
    private var recoveryGraceUntil = 0L
    private var timer: Runnable? = null
    private var pendingConfirmation: Intent? = null
    private var confirmationLaunched = false

    fun odexBytes(context: Context): Long? = runCatching {
        val dir = File(File(context.applicationInfo.sourceDir).parentFile, "oat")
        listOf("arm", "arm64", "x86", "x86_64").map { File(dir, "$it/base.odex") }
            .firstOrNull { it.exists() }?.length()
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun triedThisVersion(context: Context) = prefs(context).getLong(KEY_TRIED, 0) == BuildConfig.VERSION_CODE.toLong()
    private fun responseTimeout() = if (Build.VERSION.SDK_INT >= 31) SILENT_SESSION_TIMEOUT_MS else CONFIRM_SESSION_TIMEOUT_MS

    private fun bootCount(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1) else -1

    private fun worthIt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !Updates.canInstall(context)) return false
        val odex = odexBytes(context) ?: return false
        return odex < COMPILED_BYTES && Updates.ownDexMetadata(context) != null
    }

    private data class SavedAttempt(
        val state: SelfCompileAttempt.State, val token: String, val boot: Int, val attention: Boolean,
    )
    private fun snapshot() = SavedAttempt(attempt.state, token, attemptBoot, recoveryRequired)

    /** Save the captured state before making its next external installer call. */
    private fun save(context: Context, markAuto: Boolean = false, saved: SavedAttempt = snapshot()): Boolean {
        val state = saved.state
        val editor = prefs(context).edit()
            .putInt("attempt_version", BuildConfig.VERSION_CODE)
            .putString("attempt_token", saved.token)
            .putInt("attempt_boot", saved.boot)
            .putBoolean("attempt_attention", saved.attention)
            .remove("attempt_before").remove("attempt_id").remove("attempt_auto")
            .remove("attempt_confirming").remove("attempt_failure")
        when (state) {
            SelfCompileAttempt.State.Idle -> editor.putString("attempt_state", "idle")
            is SelfCompileAttempt.State.Starting -> editor.putString("attempt_state", "starting")
                .putBoolean("attempt_auto", state.auto)
                .putStringSet("attempt_before", state.existingSessions.map(Int::toString).toSet())
            is SelfCompileAttempt.State.Ambiguous -> editor.putString("attempt_state", "ambiguous")
                .putBoolean("attempt_auto", state.auto)
                .putStringSet("attempt_before", state.existingSessions.map(Int::toString).toSet())
            is SelfCompileAttempt.State.Waiting -> editor.putString("attempt_state", "waiting")
                .putBoolean("attempt_auto", state.auto).putInt("attempt_id", state.id)
                .putBoolean("attempt_confirming", state.confirming)
            is SelfCompileAttempt.State.Failed -> editor.putString("attempt_state", "failed")
                .putString("attempt_failure", state.reason.name)
            is SelfCompileAttempt.State.Installed -> editor.putString("attempt_state", "installed")
                .putBoolean("attempt_auto", state.auto)
        }
        if (markAuto) editor.putLong(KEY_TRIED, BuildConfig.VERSION_CODE.toLong())
        return editor.commit()
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        val p = prefs(context)
        if (p.getInt("attempt_version", 0) == BuildConfig.VERSION_CODE) {
            token = p.getString("attempt_token", "").orEmpty()
            attemptBoot = p.getInt("attempt_boot", -1)
            sessionCreatedHere = false
            recoveryRequired = p.getBoolean("attempt_attention", false)
            val auto = p.getBoolean("attempt_auto", false)
            val before = p.getStringSet("attempt_before", emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()
            val state = when (p.getString("attempt_state", "idle")) {
                "starting" -> SelfCompileAttempt.State.Starting(auto, before)
                "ambiguous" -> SelfCompileAttempt.State.Ambiguous(auto, before)
                "waiting" -> SelfCompileAttempt.State.Waiting(p.getInt("attempt_id", -1), auto, p.getBoolean("attempt_confirming", false))
                "failed" -> SelfCompileAttempt.State.Failed(
                    runCatching { SelfCompileAttempt.Failure.valueOf(p.getString("attempt_failure", "").orEmpty()) }
                        .getOrDefault(SelfCompileAttempt.Failure.Other),
                )
                "installed" -> SelfCompileAttempt.State.Installed(auto)
                else -> SelfCompileAttempt.State.Idle
            }
            attempt = SelfCompileAttempt(state)
            if (state is SelfCompileAttempt.State.Starting) {
                recoveryGraceUntil = SystemClock.elapsedRealtime() + MISSING_SESSION_GRACE_MS
            }
        }
        refresh()
        scheduleRecovery(context)
    }

    private fun busy() = workerActive || pendingActions > 0 || attempt.state is SelfCompileAttempt.State.Starting ||
        attempt.state is SelfCompileAttempt.State.Waiting || attempt.state is SelfCompileAttempt.State.Ambiguous

    private fun refresh(worth: Boolean? = null) {
        notice = if (workerActive || pendingActions > 0) Notice.Waiting else if (recoveryRequired) Notice.RecoveryRequired else when (val state = attempt.state) {
            is SelfCompileAttempt.State.Starting, is SelfCompileAttempt.State.Waiting,
            is SelfCompileAttempt.State.Ambiguous -> Notice.Waiting
            is SelfCompileAttempt.State.Failed -> when (state.reason) {
                SelfCompileAttempt.Failure.Rejected -> Notice.Rejected
                SelfCompileAttempt.Failure.Cancelled -> Notice.Cancelled
                SelfCompileAttempt.Failure.ConfirmationRequired -> Notice.ConfirmationRequired
                SelfCompileAttempt.Failure.Interrupted -> Notice.Interrupted
                SelfCompileAttempt.Failure.Detached -> Notice.Detached
                SelfCompileAttempt.Failure.Other -> Notice.Failed
            }
            is SelfCompileAttempt.State.Installed -> if (state.auto) Notice.None else Notice.Installed
            SelfCompileAttempt.State.Idle -> Notice.None
        }
        if (workerActive || attempt.state is SelfCompileAttempt.State.Starting ||
            attempt.state is SelfCompileAttempt.State.Waiting ||
            attempt.state is SelfCompileAttempt.State.Ambiguous ||
            attempt.state is SelfCompileAttempt.State.Failed
        ) offered = true
        else if (worth != null) {
            // Earlier releases recorded every failure as "tried". Keep an explicit route on
            // modern Android too when that old marker suppressed an uncompiled installation.
            offered = worth && (Build.VERSION.SDK_INT < 31 || app?.let(::triedThisVersion) == true)
        }
        else if (attempt.state !is SelfCompileAttempt.State.Idle) offered = false
    }

    fun init(context: Context) {
        synchronized(this) {
            if (checkedThisRun) return
            checkedThisRun = true
            load(context)
        }
        val worth = worthIt(context)
        if (worth) DiagLog.i("self compile: uncompiled (odex " + (odexBytes(context)?.div(1024)) + " KB)")
        synchronized(this) { refresh(worth) }
    }

    fun recheck(context: Context) {
        synchronized(this) {
            load(context)
            if (!offered || busy()) return
        }
        queueAction(context, Runnable {
            val odex = odexBytes(context) ?: return@Runnable
            if (odex < COMPILED_BYTES) return@Runnable
            val captured = synchronized(this) {
                if (!offered || workerActive || pendingActions != 1 ||
                    attempt.state is SelfCompileAttempt.State.Starting ||
                    attempt.state is SelfCompileAttempt.State.Waiting ||
                    attempt.state is SelfCompileAttempt.State.Ambiguous
                ) return@synchronized null
                offered = false
                notice = Notice.None
                attempt = SelfCompileAttempt()
                snapshot()
            } ?: return@Runnable
            save(context, saved = captured)
            DiagLog.i("self compile: compiled since (odex " + (odex / 1024) + " KB); no longer offered")
        })
    }

    @Synchronized
    fun diagnostics(context: Context): String {
        load(context)
        return "tried for this version=" + triedThisVersion(context) + ", offered=" + offered + ", state=" + attempt.state
    }

    @Synchronized
    fun onHomeStarted(context: Context) {
        load(context)
        inFront = true
        main.removeCallbacks(auto)
        scheduleRecovery(context)
    }

    /** Confirmation can be translucent: use pause/resume, not only start/stop. */
    @Synchronized
    fun onHomeResumed(context: Context) {
        load(context)
        resumed = true
        val confirmation = pendingConfirmation
        if (confirmation != null) {
            presentPending(context)
        } else {
            val waiting = attempt.state as? SelfCompileAttempt.State.Waiting
            // The confirmation Intent is not durable. After process death (or a return without
            // a result), do not pretend it can be reopened; offer an explicit retry instead.
            if (waiting?.confirming == true && !recoveryRequired && pendingActions == 0) {
                armConfirmationUnknown(context, waiting.id, RETURN_GRACE_MS)
            }
        }
        recheck(context)
    }

    @Synchronized
    fun onHomePaused() {
        resumed = false
        confirmationLaunched = false
        if ((attempt.state as? SelfCompileAttempt.State.Waiting)?.confirming == true && pendingConfirmation == null) {
            cancelTimer()
        }
    }

    @Synchronized
    fun onHomeStopped() {
        inFront = false
        if (Build.VERSION.SDK_INT < 31) return
        main.removeCallbacks(auto)
        main.postDelayed(auto, AUTO_DELAY_MS)
    }

    private val auto = Runnable {
        val context = app ?: return@Runnable
        synchronized(this) {
            if (inFront || autoTriedThisRun || triedThisVersion(context) || busy()) return@synchronized
            run(context, auto = true)
        }
    }

    @Synchronized
    fun isBusy(context: Context): Boolean {
        load(context)
        return busy()
    }

    @Synchronized
    fun run(context: Context, auto: Boolean) {
        enqueue(context, auto, allowInFront = false)
    }

    private fun enqueue(context: Context, auto: Boolean, allowInFront: Boolean) {
        val app = context.applicationContext
        load(app)
        if (Build.VERSION.SDK_INT < 28 || busy()) return
        if (auto && autoTriedThisRun) return
        if (Updates.updateBusy()) {
            notice = Notice.UpdateBusy
            return
        }
        workerActive = true
        recoveryRequired = false
        refresh()
        try {
            submitWork(Runnable { prepareAndReinstall(app, auto, allowInFront) })
        } catch (error: Exception) {
            workerActive = false
            if (auto) autoTriedThisRun = true
            notice = Notice.Failed
            offered = true
            Log.w(TAG, "self compile: cannot start worker", error)
        }
    }

    /** Package queries, durable state writes, APK copying and fsync all run off the UI thread. */
    private fun prepareAndReinstall(app: Context, auto: Boolean, allowInFront: Boolean) {
        var transientNotice: Notice? = null
        try {
            if (Build.VERSION.SDK_INT < 28) return
            if (auto && !worthIt(app)) return
            val existing = runCatching { app.packageManager.packageInstaller.mySessions }
            val boot = bootCount(app)
            var shouldInstall = false
            val captured = synchronized(this) {
                if (auto && inFront && !allowInFront) return@synchronized null
                if (existing.isFailure) {
                    if (!attempt.start(auto, triedThisVersion(app), emptySet())) return@synchronized null
                    if (auto) autoTriedThisRun = true
                    token = UUID.randomUUID().toString()
                    attempt.startFailed(IllegalStateException("Cannot read installer sessions", existing.exceptionOrNull()))
                    snapshot()
                } else if (existing.getOrThrow().any { it.appPackageName == null || it.appPackageName == app.packageName }) {
                    // An unknown or same-package session may be the earlier attempt. Do not race it.
                    transientNotice = Notice.UpdateBusy
                    null
                } else {
                    val before = existing.getOrThrow().map { it.sessionId }.toSet()
                    if (!attempt.start(auto, triedThisVersion(app), before)) return@synchronized null
                    if (auto) autoTriedThisRun = true
                    token = UUID.randomUUID().toString()
                    attemptBoot = boot
                    sessionCreatedHere = false
                    shouldInstall = true
                    snapshot()
                }
            } ?: return
            // Mark a manual attempt on modern Android as well: it must not be followed by an
            // automatic retry if its result is lost or the process restarts.
            if (!save(app, markAuto = auto || Build.VERSION.SDK_INT >= 31, saved = captured)) {
                synchronized(this) {
                    if (attempt.state == captured.state && token == captured.token) {
                        attempt.startFailed(IllegalStateException("Cannot save attempt"))
                    }
                }
                return
            }
            if (shouldInstall) {
                Log.i(TAG, "self compile: reinstalling (" + (if (auto) "auto" else "asked") + ")")
                Updates.reinstallSelf(app, auto, captured.token)
            }
        } catch (error: Exception) {
            startFailed(app, error)
        } finally {
            var showPending = false
            synchronized(this) {
                workerActive = false
                refresh()
                transientNotice?.let { notice = it; offered = true }
                showPending = resumed && pendingActions == 0 && pendingConfirmation != null && !recoveryRequired
            }
            if (showPending) postPending(app)
        }
    }

    /** Called immediately after createSession and before opening, writing or committing it. */
    internal fun sessionCreated(context: Context, id: Int) {
        val captured = synchronized(this) {
            check(attempt.state is SelfCompileAttempt.State.Starting) { "No self-compile attempt" }
            attempt.created(id)
            sessionCreatedHere = true
            snapshot()
        }
        check(save(context, saved = captured)) { "Cannot save self-compile session" }
        synchronized(this) { refresh() }
    }

    /** Start the answer deadline only after APK and metadata have been written and committed. */
    @Synchronized
    internal fun sessionCommitted(context: Context, id: Int) {
        val waiting = attempt.state as? SelfCompileAttempt.State.Waiting ?: return
        if (waiting.id == id && !waiting.confirming) arm(context, id, responseTimeout())
    }

    internal fun startFailed(context: Context, error: Exception) {
        val original = synchronized(this) { attempt.state }
        val canClose = when (original) {
            is SelfCompileAttempt.State.Starting -> true
            is SelfCompileAttempt.State.Waiting -> abandon(context, original.id)
            else -> return
        }
        val captured = synchronized(this) {
            if (attempt.state != original) return@synchronized null
            cancelTimer()
            if (!canClose) recoveryRequired = true
            else when (original) {
                is SelfCompileAttempt.State.Starting -> attempt.startFailed(error)
                is SelfCompileAttempt.State.Waiting -> attempt.submissionFailed(original.id, error)
                else -> Unit
            }
            snapshot()
        } ?: return
        save(context, saved = captured)
    }

    internal fun onResult(context: Context, intent: Intent, status: Int, onFinished: () -> Unit = {}) {
        queueAction(context, Runnable { handleResult(context, intent, status) }, onFinished)
    }

    private fun handleResult(context: Context, intent: Intent, status: Int) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val resultToken = intent.getStringExtra(Updates.EXTRA_SELF_TOKEN)
        val waiting = synchronized(this) {
            load(context)
            if (resultToken != token || token.isEmpty()) return@synchronized null
            (attempt.state as? SelfCompileAttempt.State.Waiting)?.takeIf { it.id == id }
        } ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // A late confirmation after a timeout is still useful, but only for a session whose
            // identity can be established. Never open an intent for an unidentifiable session.
            if (synchronized(this) { recoveryRequired }) {
                val info = runCatching { context.packageManager.packageInstaller.getSessionInfo(id) }.getOrNull()
                val boot = bootCount(context)
                val owned = synchronized(this) {
                    info != null && (info.appPackageName == null || info.appPackageName == context.packageName) &&
                        (sessionCreatedHere || (attemptBoot >= 0 && boot == attemptBoot)) &&
                        attempt.state == waiting && resultToken == token
                }
                if (!owned) return
                synchronized(this) { recoveryRequired = false }
            }
            synchronized(this) { cancelTimer() }
            if (waiting.auto) {
                val closed = abandon(context, id)
                val captured = synchronized(this) {
                    if (attempt.state != waiting || resultToken != token) return@synchronized null
                    if (!closed) recoveryRequired = true
                    else attempt.answer(id, SelfCompileAttempt.Answer.PendingConfirmation)
                    snapshot()
                } ?: return
                save(context, saved = captured)
                return
            }
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm == null) {
                resolveFailure(context, id, SelfCompileAttempt.Answer.Interrupted)
                return
            }
            val captured = synchronized(this) {
                if (attempt.state != waiting || resultToken != token) return@synchronized null
                attempt.answer(id, SelfCompileAttempt.Answer.PendingConfirmation)
                snapshot()
            } ?: return
            if (!save(context, saved = captured)) {
                resolveFailure(context, id, SelfCompileAttempt.Answer.Interrupted)
                return
            }
            synchronized(this) {
                if (token != resultToken || attempt.state !is SelfCompileAttempt.State.Waiting) return
                pendingConfirmation = confirm
            }
            postPending(context)
            return
        }
        val answer = when (status) {
            PackageInstaller.STATUS_SUCCESS -> SelfCompileAttempt.Answer.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfCompileAttempt.Answer.Cancelled
            PackageInstaller.STATUS_FAILURE_BLOCKED -> SelfCompileAttempt.Answer.Rejected
            else -> SelfCompileAttempt.Answer.Failure
        }
        val captured = synchronized(this) {
            if (attempt.state != waiting || resultToken != token) return@synchronized null
            cancelTimer()
            pendingConfirmation = null
            confirmationLaunched = false
            recoveryRequired = false
            attempt.answer(id, answer)
            snapshot()
        } ?: return
        val persisted = runCatching {
            save(context, markAuto = status == PackageInstaller.STATUS_SUCCESS, saved = captured)
        }.getOrDefault(false)
        if (!persisted) {
            synchronized(this) {
                if (attempt.state == captured.state && token == resultToken) {
                    attempt = SelfCompileAttempt(waiting)
                    recoveryRequired = true
                }
            }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            DiagLog.i("self compile: installed, odex " + (odexBytes(context)?.div(1024)) + " KB")
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.w(TAG, "self compile: not installed (" + status + ", " + message + ")")
            DiagLog.i("self compile: not installed (" + status + ", " + message + ")")
        }
    }

    private fun postPending(context: Context) {
        main.post(Runnable { synchronized(this) { presentPending(context) } })
    }

    /** Called on the main thread after all already-queued installer results are handled. */
    private fun presentPending(context: Context) {
        val current = attempt.state as? SelfCompileAttempt.State.Waiting ?: return
        val page = pendingConfirmation ?: return
        if (!resumed || workerActive || pendingActions > 0 || recoveryRequired || !current.confirming) return
        pendingConfirmation = null
        openConfirmation(context, page)
    }

    private fun openConfirmation(context: Context, intent: Intent) {
        val id = (attempt.state as? SelfCompileAttempt.State.Waiting)?.id ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess {
                confirmationLaunched = true
                armConfirmationUnknown(context, id, 4_000L)
            }
            .onFailure { queueFailure(context, id, SelfCompileAttempt.Answer.Interrupted, onlyIfResumed = false) }
    }

    private fun cancelTimer() {
        timer?.let(main::removeCallbacks)
        timer = null
    }

    /** A missing result does not prove that an accepted installation has stopped. */
    private fun armConfirmationUnknown(context: Context, id: Int, delay: Long) {
        cancelTimer()
        timer = Runnable {
            queueAction(context, Runnable {
                val captured = synchronized(this) {
                    val waiting = attempt.state as? SelfCompileAttempt.State.Waiting
                    if (pendingActions != 1) {
                        // A queued callback wins; if it is stale, the drained queue rearms.
                        confirmationLaunched = false
                        return@synchronized null
                    }
                    if (!resumed || waiting?.id != id ||
                        !waiting.confirming || pendingConfirmation != null || recoveryRequired
                    ) return@synchronized null
                    recoveryRequired = true
                    snapshot()
                }
                if (captured != null) save(context, saved = captured)
            })
        }.also { main.postDelayed(it, delay) }
    }

    private fun arm(context: Context, id: Int, ms: Long) {
        cancelTimer()
        timer = Runnable {
            queueAction(context, Runnable {
                val captured = synchronized(this) {
                    val waiting = attempt.state as? SelfCompileAttempt.State.Waiting
                    if (pendingActions != 1 || waiting?.id != id || waiting.confirming || recoveryRequired) {
                        return@synchronized null
                    }
                    // A slow or missing response does not prove that installation has stopped.
                    recoveryRequired = true
                    snapshot()
                }
                if (captured != null) save(context, saved = captured)
            })
        }.also { main.postDelayed(it, ms) }
    }

    private fun queueFailure(context: Context, id: Int, answer: SelfCompileAttempt.Answer, onlyIfResumed: Boolean) {
        queueAction(context, Runnable {
            if (!onlyIfResumed || synchronized(this) { resumed }) resolveFailure(context, id, answer)
        })
    }

    private fun queueAction(context: Context, action: Runnable, onFinished: () -> Unit = {}) {
        synchronized(this) { pendingActions++; refresh() }
        val started = AtomicBoolean(false)
        try {
            submitWork(Runnable {
                started.set(true)
                try {
                    action.run()
                } catch (error: Exception) {
                    synchronized(this) {
                        if (attempt.state is SelfCompileAttempt.State.Starting ||
                            attempt.state is SelfCompileAttempt.State.Waiting ||
                            attempt.state is SelfCompileAttempt.State.Ambiguous
                        ) recoveryRequired = true
                    }
                    Log.w(TAG, "self compile: cannot resolve session", error)
                } finally {
                    try {
                        var showPending = false
                        synchronized(this) {
                            pendingActions--
                            if (pendingActions == 0 && !workerActive) scheduleRecovery(context)
                            refresh()
                            showPending = pendingActions == 0 && !workerActive &&
                                resumed && pendingConfirmation != null && !recoveryRequired
                            val waiting = attempt.state as? SelfCompileAttempt.State.Waiting
                            if (pendingActions == 0 && !workerActive && resumed &&
                                waiting?.confirming == true && pendingConfirmation == null &&
                                !confirmationLaunched && !recoveryRequired
                            ) armConfirmationUnknown(context, waiting.id, RETURN_GRACE_MS)
                        }
                        if (showPending) postPending(context)
                    } finally {
                        onFinished()
                    }
                }
            })
        } catch (error: Exception) {
            // A synchronous executor may propagate an exception after running the task.
            // Its finally block already released the action and finished the broadcast.
            if (started.get()) {
                Log.w(TAG, "self compile: result worker failed", error)
                return
            }
            try {
                synchronized(this) {
                    pendingActions--
                    if (attempt.state is SelfCompileAttempt.State.Starting ||
                        attempt.state is SelfCompileAttempt.State.Waiting ||
                        attempt.state is SelfCompileAttempt.State.Ambiguous
                    ) recoveryRequired = true
                    refresh()
                }
                Log.w(TAG, "self compile: cannot resolve session", error)
            } finally {
                onFinished()
            }
        }
    }

    private fun abandon(context: Context, id: Int): Boolean {
        if (id < 0 || Build.VERSION.SDK_INT < 21) return true
        val installer = context.packageManager.packageInstaller
        // Session IDs can be reused after reboot. Without a matching boot count (or a session
        // created in this process), wait for the session to disappear instead of abandoning it.
        val present = runCatching { installer.getSessionInfo(id) }.getOrElse { return false } ?: return true
        if (present.appPackageName != null && present.appPackageName != context.packageName) return false
        if (!sessionCreatedHere && (attemptBoot < 0 || bootCount(context) != attemptBoot)) return false
        if (runCatching { installer.abandonSession(id) }.isSuccess) return true
        return runCatching { installer.getSessionInfo(id) == null }.getOrDefault(false)
    }

    private fun resolveFailure(
        context: Context, id: Int,
        answer: SelfCompileAttempt.Answer = SelfCompileAttempt.Answer.Failure,
    ) {
        val original = synchronized(this) {
            (attempt.state as? SelfCompileAttempt.State.Waiting)?.takeIf { it.id == id }
        } ?: return
        val closed = abandon(context, id)
        val captured = synchronized(this) {
            if (attempt.state != original) return@synchronized null
            cancelTimer()
            if (!closed) recoveryRequired = true
            else {
                pendingConfirmation = null
                recoveryRequired = false
                attempt.answer(id, answer)
            }
            snapshot()
        } ?: return
        save(context, saved = captured)
        DiagLog.i("self compile: session " + id + " did not finish")
    }

    /**
     * The owner can stop tracking an unidentifiable session. This never abandons it; self-compile
     * still checks the system session list before a later explicit retry, while ordinary updates
     * are no longer blocked by our local record.
     */
    @Synchronized
    fun stopWaiting(context: Context) {
        if (!recoveryRequired || workerActive || pendingActions > 0) return
        val previous = attempt.state
        if (previous !is SelfCompileAttempt.State.Starting &&
            previous !is SelfCompileAttempt.State.Waiting &&
            previous !is SelfCompileAttempt.State.Ambiguous
        ) return
        workerActive = true
        refresh()
        try {
            submitWork(Runnable {
                try {
                    val captured = synchronized(this) {
                        if (!recoveryRequired || attempt.state != previous) return@synchronized null
                        attempt.detach()
                        recoveryRequired = false
                        snapshot()
                    }
                    if (captured != null) {
                        val saved = runCatching { save(context, markAuto = true, saved = captured) }.getOrDefault(false)
                        synchronized(this) {
                            if (saved) {
                                recoveryRequired = false
                                pendingConfirmation = null
                                confirmationLaunched = false
                                cancelTimer()
                            } else {
                                attempt = SelfCompileAttempt(previous)
                                recoveryRequired = true
                            }
                        }
                    }
                } finally {
                    synchronized(this) {
                        workerActive = false
                        refresh()
                    }
                }
            })
        } catch (error: Exception) {
            workerActive = false
            refresh()
            Log.w(TAG, "self compile: cannot stop waiting", error)
        }
    }

    /** Schedule installer queries and durable writes without holding the UI state lock. */
    private fun scheduleRecovery(context: Context) {
        if (Build.VERSION.SDK_INT < 28 || workerActive || pendingActions > 0 ||
            recoveryQueued || recoveryRequired
        ) return
        val state = attempt.state
        if (state !is SelfCompileAttempt.State.Starting &&
            state !is SelfCompileAttempt.State.Ambiguous &&
            (state !is SelfCompileAttempt.State.Waiting || state.confirming)
        ) return
        recoveryQueued = true
        pendingActions++
        refresh()
        try {
            submitWork(Runnable {
                try {
                    recover(context, state)
                } catch (error: Exception) {
                    synchronized(this) { if (attempt.state == state) recoveryRequired = true }
                    Log.w(TAG, "self compile: recovery failed", error)
                } finally {
                    synchronized(this) {
                        recoveryQueued = false
                        pendingActions--
                        refresh()
                        if (recoveryAgain) {
                            recoveryAgain = false
                            scheduleRecovery(context)
                        }
                    }
                }
            })
        } catch (error: Exception) {
            recoveryQueued = false
            pendingActions--
            recoveryRequired = true
            refresh()
            Log.w(TAG, "self compile: cannot schedule recovery", error)
        }
    }

    private fun recover(context: Context, state: SelfCompileAttempt.State) {
        when (state) {
            is SelfCompileAttempt.State.Starting, is SelfCompileAttempt.State.Ambiguous -> {
                val sessions = runCatching { context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet() }
                val captured = synchronized(this) {
                    if (attempt.state != state || recoveryRequired) return@synchronized null
                    if (sessions.isFailure) {
                        cancelTimer()
                        recoveryRequired = true
                        return@synchronized snapshot()
                    }
                    if (state is SelfCompileAttempt.State.Starting &&
                        (sessions.getOrThrow() - state.existingSessions).isEmpty()
                    ) {
                        val remaining = recoveryGraceUntil - SystemClock.elapsedRealtime()
                        if (remaining > 0) {
                            cancelTimer()
                            timer = Runnable {
                                synchronized(this) {
                                    if (recoveryQueued || pendingActions > 0) recoveryAgain = true
                                    else scheduleRecovery(context)
                                }
                            }
                                .also { main.postDelayed(it, remaining) }
                            return@synchronized null
                        }
                    }
                    attempt.recover(sessions.getOrThrow())
                    recoveryRequired = attempt.state is SelfCompileAttempt.State.Ambiguous
                    if (recoveryRequired) cancelTimer()
                    snapshot()
                } ?: return
                save(context, saved = captured)
                val next = synchronized(this) { attempt.state }
                if (!captured.attention && next is SelfCompileAttempt.State.Waiting) recover(context, next)
            }
            is SelfCompileAttempt.State.Waiting -> {
                if (state.confirming) return
                val info = runCatching { context.packageManager.packageInstaller.getSessionInfo(state.id) }
                val boot = bootCount(context)
                val captured = synchronized(this) {
                    if (attempt.state != state || recoveryRequired) return@synchronized null
                    val session = info.getOrNull()
                    if (info.isFailure || (session != null &&
                        ((session.appPackageName != null && session.appPackageName != context.packageName) ||
                            (!sessionCreatedHere && (attemptBoot < 0 || boot != attemptBoot))))
                    ) {
                        recoveryRequired = true
                        snapshot()
                    } else {
                        arm(context, state.id, if (session != null) responseTimeout() else MISSING_SESSION_GRACE_MS)
                        null
                    }
                }
                if (captured != null) save(context, saved = captured)
            }
            else -> Unit
        }
    }

    /** ConfigReceiver's self_compile: now, whether tried or not (for testing on a TV). */
    @Synchronized
    fun runNow(context: Context) {
        load(context)
        if (busy()) return
        autoTriedThisRun = false
        prefs(context).edit { remove(KEY_TRIED) }
        enqueue(context, auto = Build.VERSION.SDK_INT >= 31, allowInFront = true)
    }
}
