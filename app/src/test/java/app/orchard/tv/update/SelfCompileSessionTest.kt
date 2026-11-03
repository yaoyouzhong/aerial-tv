package app.orchard.tv.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.orchard.tv.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class SelfCompileSessionTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val installer get() = app.packageManager.packageInstaller
    private var id = -1
    private var previousBoot = -1
    private val token = "self-compile-test-token"

    @Before
    fun prepare() {
        resetSingleton()
        previousBoot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 42)
        app.getSharedPreferences("self_compile", Context.MODE_PRIVATE).edit().clear().commit()
        id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
        })
        val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        app.getSharedPreferences("self_compile", Context.MODE_PRIVATE).edit()
            .putInt("attempt_version", BuildConfig.VERSION_CODE)
            .putString("attempt_state", "waiting")
            .putString("attempt_token", token)
            .putInt("attempt_boot", boot)
            .putInt("attempt_id", id)
            .putBoolean("attempt_auto", false)
            .putBoolean("attempt_confirming", false)
            .commit()
    }

    @After
    fun cleanup() {
        resetSingleton()
        Updates.resetInstall()
        if (id >= 0) runCatching { installer.abandonSession(id) }
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, previousBoot)
        app.getSharedPreferences("self_compile", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun restoredSessionBlocksRepeatedPressAndCancellationIsVisible() {
        SelfCompile.onHomeStarted(app)
        SelfCompile.onHomeResumed(app)
        assertTrue(SelfCompile.isBusy(app))
        val count = installer.mySessions.size
        SelfCompile.run(app, auto = false)
        assertEquals(count, installer.mySessions.size)

        answer(app, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(SelfCompile.Notice.Cancelled, SelfCompile.notice)
        assertTrue(SelfCompile.offered)
    }

    @Test
    fun restoredSelfCompileSessionBlocksOrdinaryUpdate() {
        SelfCompile.onHomeStarted(app)
        SelfCompile.onHomeResumed(app)
        val count = installer.mySessions.size
        Updates.install(app)
        assertEquals(count, installer.mySessions.size)
        assertEquals(Updates.Install.Failed(Updates.Failure.SelfCompileBusy), Updates.install)
    }

    @Test
    fun resultWithAnOldTokenCannotFinishTheRestoredSession() {
        SelfCompile.onHomeStarted(app)
        SelfCompile.onHomeResumed(app)
        answer(app, PackageInstaller.STATUS_SUCCESS, token = "old-attempt")
        assertTrue(SelfCompile.isBusy(app))
        answer(app, PackageInstaller.STATUS_FAILURE_BLOCKED)
        assertEquals(SelfCompile.Notice.Rejected, SelfCompile.notice)
    }

    @Test
    fun translucentConfirmationRemainsPendingWhileHomeIsPaused() {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) = Unit
        }
        SelfCompile.onHomeStarted(context)
        SelfCompile.onHomeResumed(context)
        SelfCompile.onResult(context, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(Updates.EXTRA_SELF_TOKEN, token)
            putExtra(Intent.EXTRA_INTENT, Intent("test.confirm"))
        }, PackageInstaller.STATUS_PENDING_USER_ACTION)
        SelfCompile.onHomePaused()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
        assertNotNull(installer.getSessionInfo(id))
        assertTrue(SelfCompile.isBusy(context))
        answer(context, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(SelfCompile.Notice.Cancelled, SelfCompile.notice)
    }

    @Test
    fun uncertainSessionCanBeDetachedWithoutAbandoningIt() {
        app.getSharedPreferences("self_compile", Context.MODE_PRIVATE).edit()
            .putInt("attempt_boot", -1).commit()
        SelfCompile.onHomeStarted(app)
        SelfCompile.onHomeResumed(app)
        assertEquals(SelfCompile.Notice.RecoveryRequired, SelfCompile.notice)
        SelfCompile.stopWaiting(app)
        assertEquals(SelfCompile.Notice.Detached, SelfCompile.notice)
        assertTrue(!SelfCompile.isBusy(app))
        assertNotNull(installer.getSessionInfo(id))
        val notOfficial = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "test.other"
        }
        Updates.install(notOfficial)
        assertEquals(Updates.Install.Failed(Updates.Failure.NotOfficial), Updates.install)
        assertNotNull(installer.getSessionInfo(id))
    }

    @Test
    fun restoredConfirmationIsNotSilentlyReopened() {
        app.getSharedPreferences("self_compile", Context.MODE_PRIVATE).edit()
            .putBoolean("attempt_confirming", true).commit()
        SelfCompile.onHomeStarted(app)
        SelfCompile.onHomeResumed(app)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(SelfCompile.Notice.RecoveryRequired, SelfCompile.notice)
        SelfCompile.stopWaiting(app)
        assertEquals(SelfCompile.Notice.Detached, SelfCompile.notice)
        assertNotNull(installer.getSessionInfo(id))
        assertTrue(!SelfCompile.isBusy(app))
    }

    @Test
    fun lateConfirmationAfterTimeoutDoesNotAbandonSession() {
        val context = object : ContextWrapper(app) {
            var launches = 0
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { launches++ }
        }
        SelfCompile.onHomeStarted(context)
        SelfCompile.onHomeResumed(context)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
        assertEquals(SelfCompile.Notice.RecoveryRequired, SelfCompile.notice)
        assertNotNull(installer.getSessionInfo(id))
        SelfCompile.onResult(context, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(Updates.EXTRA_SELF_TOKEN, token)
            putExtra(Intent.EXTRA_INTENT, Intent("test.confirm"))
        }, PackageInstaller.STATUS_PENDING_USER_ACTION)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ZERO)
        assertEquals(1, context.launches)
        assertNotNull(installer.getSessionInfo(id))
    }

    @Test
    fun queuedCancellationDoesNotOpenTheEarlierConfirmation() {
        val queued = mutableListOf<Runnable>()
        val context = object : ContextWrapper(app) {
            var launches = 0
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { launches++ }
        }
        SelfCompile.onHomeStarted(context)
        SelfCompile.onHomeResumed(context)
        SelfCompile.onHomePaused()
        ReflectionHelpers.setStaticField(SelfCompile::class.java, "submitWork", { runnable: Runnable ->
            queued.add(runnable)
            Unit
        })
        SelfCompile.onResult(context, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(Updates.EXTRA_SELF_TOKEN, token)
            putExtra(Intent.EXTRA_INTENT, Intent("test.confirm"))
        }, PackageInstaller.STATUS_PENDING_USER_ACTION)
        answer(context, PackageInstaller.STATUS_FAILURE_ABORTED)
        SelfCompile.onHomeResumed(context)
        assertEquals(2, queued.size)
        queued.removeAt(0).run()
        queued.removeAt(0).run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ZERO)
        assertEquals(0, context.launches)
        assertEquals(SelfCompile.Notice.Cancelled, SelfCompile.notice)
    }

    private fun answer(context: Context, status: Int, token: String = this.token) {
        SelfCompile.onResult(context, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(Updates.EXTRA_SELF_TOKEN, token)
        }, status)
    }

    private fun resetSingleton() {
        val handler = ReflectionHelpers.getStaticField<Handler>(SelfCompile::class.java, "main")
        ReflectionHelpers.getStaticField<Runnable?>(SelfCompile::class.java, "timer")?.let(handler::removeCallbacks)
        handler.removeCallbacks(ReflectionHelpers.getStaticField(SelfCompile::class.java, "auto"))
        fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(SelfCompile::class.java, name, value)
        set("loaded", false)
        set("checkedThisRun", false)
        set("autoTriedThisRun", false)
        set("inFront", false)
        set("resumed", false)
        set("app", null)
        set("attempt", SelfCompileAttempt())
        set("token", "")
        set("attemptBoot", -1)
        set("sessionCreatedHere", false)
        set("recoveryGraceUntil", 0L)
        set("timer", null)
        set("pendingConfirmation", null)
        set("workerActive", false)
        set("pendingActions", 0)
        set("recoveryRequired", false)
        set("recoveryQueued", false)
        set("recoveryAgain", false)
        set("confirmationLaunched", false)
        set("submitWork", { runnable: Runnable -> runnable.run() })
    }
}
