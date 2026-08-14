package com.alex193a.rootmypixel.feature.install

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.model.VerifiedPayloads
import com.alex193a.rootmypixel.domain.usecase.DownloadPayloadsUseCase
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import com.alex193a.rootmypixel.shizuku.ExploitService
import com.alex193a.rootmypixel.shizuku.IExploitService
import com.alex193a.rootmypixel.utils.NativeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val resolveTargetUseCase: ResolveTargetUseCase by lazy {
        get(ResolveTargetUseCase::class.java)
    }
    private val downloadPayloadsUseCase: DownloadPayloadsUseCase by lazy {
        get(DownloadPayloadsUseCase::class.java)
    }

    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val probe = NativeProbe.run()
                val deviceInfo = NativeProbe.readDeviceSnapshot()
                if (NativeProbe.isKernelSuActive()) {
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Installed,
                        message = app.getString(R.string.status_ksu_active),
                        probeOutput = probe,
                        log = probe,
                    )
                    return@launch
                }
                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )
                val result = resolveTargetUseCase(snapshot)
                when (result) {
                    is Result.Success -> {
                        val profile = result.data
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Ready,
                            message = app.getString(R.string.status_not_installed),
                            probeOutput = probe,
                            log = "$probe\n${app.getString(
                                R.string.log_profile, profile.profileId)}",
                        )
                    }
                    is Result.Error -> {
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Failed,
                            message = app.getString(R.string.status_support_failed),
                            probeOutput = probe,
                            log = "$probe\n[-] ${result.error.message}",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Failed,
                    message = app.getString(R.string.status_support_failed),
                    log = "[-] ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun install(profileId: String? = null, permissiveOnly: Boolean = false) {
        if (installJob?.isActive == true ||
            mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()

        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            try {
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking))
                val deviceInfo = NativeProbe.readDeviceSnapshot()

                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )

                val profile = when {
                    profileId != null -> {
                        when (val r = resolveTargetUseCase(profileId)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                    else -> {
                        when (val r = resolveTargetUseCase(snapshot)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                }
                appendLog(app.getString(R.string.log_profile, profile.profileId))

                setPhase(InstallPhase.Downloading, "Preparing payloads…")
                val payloads = when (
                    val r = downloadPayloadsUseCase(profile) { appendLog("[*] $it") }
                ) {
                    is Result.Success -> r.data
                    is Result.Error ->
                        throw IllegalStateException(r.error.message)
                }
                appendLog("Payloads extracted from APK")

                val useShizuku = hasShizukuPermission()
                require(useShizuku) {
                    app.getString(R.string.error_shizuku_required)
                }
                appendLog("[*] Using Shizuku shell access: $useShizuku")

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit))
                executeExploit(payloads)

                if (permissiveOnly) {
                    setPhase(InstallPhase.Installed, "SELinux permissive + root shell ready")
                    appendLog("Install complete — permissive mode, KernelSU skipped")
                } else {
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_loading_ksu))
                    installKernelSu(payloads)

                    setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                    appendLog(app.getString(R.string.log_install_complete))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
            }
        }
    }

    // --- Shizuku UserService helpers ---

    private data class ShizukuServiceHandle(
        val service: IExploitService,
        val conn: ServiceConnection,
    )

    private fun bindExploitService(): ShizukuServiceHandle? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)

        var service: IExploitService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IExploitService.Stub.asInterface(binder)
                synchronized(this) {
                    (this as Object).notifyAll()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        Shizuku.bindUserService(args, conn)

        // Wait up to 5 seconds for connection
        synchronized(conn as Object) {
            if (service == null) {
                try {
                    (conn as Object).wait(5000)
                } catch (_: InterruptedException) {
                }
            }
        }

        val svc = service ?: run {
            Shizuku.unbindUserService(args, conn, true)
            return null
        }
        return ShizukuServiceHandle(svc, conn)
    }

    private fun unbindExploitService(handle: ShizukuServiceHandle) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)
        Shizuku.unbindUserService(args, handle.conn, true)
    }

    // --- Exploit execution ---

    private suspend fun executeExploit(payloads: VerifiedPayloads) {
        executeExploitViaShizuku(payloads)
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private suspend fun executeExploitViaShizuku(payloads: VerifiedPayloads) {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        require(helper.exists()) { app.getString(R.string.error_helper_unavailable) }

        val handle = bindExploitService()
            ?: throw IllegalStateException("Failed to bind Shizuku UserService")

        try {
            // The exploit races the kernel for a page, and losing is the normal
            // outcome on a busy device rather than a bug. One press used to mean
            // one race: lose it and the user got a failure whose only answer was
            // to press again, which on this hardware often meant pressing at the
            // worst possible moment. Do the retrying here instead, and settle the
            // device first, so a press means "get root" rather than "roll once".
            var lastFailure: String? = null
            for (attempt in 1..EXPLOIT_ATTEMPTS) {
                waitForSettledSystem(handle, attempt)
                appendLog("[*] exploit attempt $attempt/$EXPLOIT_ATTEMPTS")
                val failure = runExploitOnce(handle, payloads, helper)
                if (failure == null) {
                    return
                }
                lastFailure = failure
                appendLog("[-] attempt $attempt did not get root: $failure")
            }
            throw IllegalStateException(
                lastFailure ?: app.getString(R.string.error_success_marker)
            )
        } finally {
            unbindExploitService(handle)
        }
    }

    /**
     * Runs the payload once. Returns null on success, or a description of why
     * it did not get root — a lost race is a return value here, not an
     * exception, because it is worth retrying and a stall or a bad exit is not.
     */
    private suspend fun runExploitOnce(
        handle: ShizukuServiceHandle,
        payloads: VerifiedPayloads,
        helper: File,
    ): String? {
        val logPrefix = mutableState.value.log
        handle.service.startExploit(
            payloads.exploit.readBytes(),
            helper.readBytes(),
            "/data/local/tmp/exploit.log",
        )

        val startedAt = SystemClock.elapsedRealtime()
        var lastProgressAt = startedAt
        var lastRawLog = ""

        while (handle.service.isRunning) {
            val remoteLog = handle.service.getLog()
            val fileLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
            val currentLog = if (fileLog.length > remoteLog.length) fileLog else remoteLog

            if (currentLog != lastRawLog) {
                publishLog(logPrefix, currentLog)
                lastRawLog = currentLog
                lastProgressAt = SystemClock.elapsedRealtime()
            }
            val now = SystemClock.elapsedRealtime()
            // A stall or an overall timeout still throws: those mean something
            // is wrong rather than that a race was lost, and retrying them just
            // burns the clock.
            require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                app.getString(R.string.error_exploit_stalled)
            }
            require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                app.getString(R.string.error_exploit_timeout)
            }
            delay(LOG_POLL_INTERVAL)
        }

        val exitCode = handle.service.waitFor()
        val finalLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
        if (finalLog.isNotBlank()) {
            publishLog(logPrefix, finalLog)
        }

        if (exitCode != 0) {
            return app.getString(R.string.error_payload_exit, exitCode, "")
        }
        if (!finalLog.contains("done=1") || !finalLog.contains("root=1")) {
            return app.getString(R.string.error_success_marker)
        }
        return null
    }

    /**
     * Blocks until the device is quiet enough to be worth racing, or until the
     * budget runs out.
     *
     * The exploit reclaims a slab page that the kernel has just freed, so it is
     * competing with every other process that allocates one. Measured on a
     * Pixel 9a across a single evening, that competition — not anything in the
     * exploit — decided the outcome: runs started within ten minutes of boot
     * lost every time and took the kernel down with them more often than not,
     * while runs on a device that had been idle for forty minutes took root on
     * the first page. Waiting is therefore not politeness, it is the single
     * biggest lever available, and it is one the app can pull by itself instead
     * of asking the user to be patient at exactly the wrong moment.
     */
    private suspend fun waitForSettledSystem(handle: ShizukuServiceHandle, attempt: Int) {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_BUDGET_MILLIS
        var announced = false

        while (SystemClock.elapsedRealtime() < deadline) {
            val uptime = handle.service
                .exec("cut -d. -f1 /proc/uptime 2>/dev/null || echo 0")
                .trim().toLongOrNull() ?: 0L
            val forkRate = sampleForkRate(handle)
            val running = handle.service
                .exec("grep '^procs_running' /proc/stat 2>/dev/null || true")
                .trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
            val state = "uptime ${uptime}s, ${forkRate} forks/s, ${running} running"

            // Both conditions, because neither is sufficient on its own. A run
            // started at 530s of uptime on a device measuring 2% CPU, 1 fork/s
            // and 2 runnable — quiet by any instantaneous measure — still
            // panicked the kernel. Whatever the young-boot penalty is, it is
            // not visible in current activity: most likely the per-CPU partial
            // lists are still full of the mm_structs that boot churned through,
            // so the page the reclaim wants goes back to the cache instead of
            // to the allocator. Time is the only handle on that, and idleness
            // is the only handle on live competition.
            if (uptime >= SETTLE_MIN_UPTIME_SECONDS &&
                forkRate <= SETTLE_MAX_FORKS_PER_SEC &&
                running <= SETTLE_MAX_RUNNING
            ) {
                if (announced) {
                    appendLog("[*] device settled ($state)")
                }
                return
            }

            if (!announced) {
                appendLog("[*] waiting for the device to settle before attempt $attempt ($state)")
                announced = true
            }
            setPhase(InstallPhase.Exploiting, app.getString(R.string.status_settling))
            delay(SETTLE_POLL_INTERVAL)
        }
        appendLog("[*] settle budget spent, racing anyway")
    }

    /**
     * Processes created per second, from the `processes` counter in /proc/stat.
     *
     * This is the number that matters, and it took a measurement to see why.
     * The race is for an `mm_struct` slab page, and an `mm_struct` is allocated
     * when a process is created — so the competition is fork traffic, not CPU
     * load. On a device sitting at 2% CPU the fork rate was 1/s and the system
     * was genuinely out of the way; CPU percentage alone would have said the
     * same thing about a device busy spawning short-lived processes, which is
     * the case that actually loses the race.
     *
     * Load average is worse still: it is a decaying one-minute mean starting
     * from zero, so just after boot — the busiest moment and the least winnable
     * — it reads low and would wave the run straight through.
     */
    private suspend fun sampleForkRate(handle: ShizukuServiceHandle): Int {
        fun read(): Long? = handle.service
            .exec("grep '^processes' /proc/stat 2>/dev/null || true")
            .trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()

        val first = read() ?: return 0
        delay(FORK_SAMPLE_WINDOW)
        val second = read() ?: return 0

        val seconds = FORK_SAMPLE_WINDOW.inWholeSeconds.coerceAtLeast(1)
        return ((second - first) / seconds).toInt().coerceAtLeast(0)
    }

    // Shizuku helpers

    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.isPreV11().not() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            Shizuku.getUid() == 2000
        } catch (_: Exception) {
            false
        }
    }

    // --- KernelSU ---

    private fun installKernelSu(payloads: VerifiedPayloads) {
        val ksudSource = payloads.kernelSu.absolutePath
        val ksudDest = "/data/local/tmp/ksud-pixel"
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

        // 1. Wait for daemon to be ready
        awaitDaemonSocket()
        diagnoseDaemon()

        // 2. Stage ksud via daemon root (cp + chmod + chown)
        appendLog("[*] Staging ReSukiSU binary...")
        val stageCmd = "cp '$ksudSource' $ksudDest && chmod 755 $ksudDest && " +
            "chown root:root $ksudDest"
        var stageSuccess = false
        for (attempt in 1..5) {
            val result = runHelper(helper, "-c", stageCmd)
            if (result.code == 0) {
                val verify = runHelper(helper, "-c", "ls -la $ksudDest")
                if (verify.output.contains("rwxr-xr-x") ||
                    verify.output.contains("-rwxr-xr-x")) {
                    appendLog("ReSukiSU staged: ${verify.output.trim()}")
                    stageSuccess = true
                    break
                }
            }
            appendLog("[!] Stage attempt $attempt: code=${result.code} ${result.output.take(120)}")
            Thread.sleep(1000)
        }
        require(stageSuccess) {
            app.getString(R.string.error_ksu_stage, "stage failed after 5 attempts")
        }

        // 3. Execute late-load via daemon root
        appendLog("[*] Triggering KernelSU late-load (kmi=${payloads.kmi})...")
        val lateResult = runHelper(helper, "-c",
            "$ksudDest late-load --kmi ${payloads.kmi}")
        if (lateResult.output.isNotBlank()) {
            appendLog(lateResult.output.take(2000))
        }

        // 4. Verify KSU is actually loaded (check multiple paths)
        var ksuActive = false
        for (i in 1..10) {
            val check = runHelper(helper, "-c",
                "test -e /dev/kernelsu && echo KSU_OK || " +
                "test -e /sys/kernel/kernelsu && echo KSU_OK || " +
                "test -e /data/adb/ksu && echo KSU_OK || " +
                "echo KSU_NOT_FOUND")
            if (check.output.contains("KSU_OK")) {
                appendLog("[+] KernelSU verified (attempt $i): ${check.output.take(60)}")
                ksuActive = true
                break
            }
            Thread.sleep(500)
        }
        require(ksuActive) {
            app.getString(
                R.string.error_ksu_verify,
                lateResult.code,
                lateResult.output.take(200)
            )
        }
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun runHelper(helper: File, vararg arguments: String): CommandResult {
        for (attempt in 1..5) {
            val process = ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val result = CommandResult(process.waitFor(), output.trim())

            val transient = result.output.contains("No such file or directory") ||
                result.output.contains("Connection refused") ||
                result.code == 127
            if (!transient || attempt == 5) {
                return result
            }
            Thread.sleep(1500)
        }
        return CommandResult(1, "runHelper: exhausted retries")
    }

    private fun awaitDaemonSocket() {
        val sock = File("/data/local/tmp/temp_su.sock")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sock.exists()) return
            Thread.sleep(500)
        }
    }

    private fun diagnoseDaemon() {
        try {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) {
                appendLog("[diag] helper binary missing")
                return
            }
            val suCheck = runHelper(helper, "-c",
                "ls -la /apex/com.android.virt/bin/su /data/local/tmp/su 2>/dev/null || echo 'not found'")
            appendLog("[diag] su binaries: ${suCheck.output.take(200)}")

            val sockCheck = File("/data/local/tmp/temp_su.sock")
            appendLog("[diag] socket file: ${if (sockCheck.exists()) "present" else "NOT FOUND"}")

            val logCheck = runHelper(helper, "-c",
                "cat /data/local/tmp/su_daemon.log 2>/dev/null || echo 'empty'")
            appendLog("[diag] daemon log: ${logCheck.output.take(300)}")
        } catch (e: Exception) {
            appendLog("[diag] error: ${e.message}")
        }
    }

    fun softReboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) return@launch
            val result = runHelper(helper, "-c",
                "killall -9 system_server 2>/dev/null; true")
            appendLog("[*] Soft reboot triggered (exit ${result.code})")
        }
    }

    // --- UI helpers ---

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    private fun publishLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(
            log = listOf(prefix, rawLog)
                .filter(String::isNotBlank)
                .joinToString("\n")
                .takeLast(MAX_LOG_CHARS),
        )
    }

    private fun appendLog(line: String) {
        val cleanLine = line.trim()
        if (cleanLine.isBlank()) return
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + cleanLine)
                .trim()
                .takeLast(MAX_LOG_CHARS),
        )
    }

    data class CommandResult(val code: Int, val output: String)

    companion object {
        private const val EXPLOIT_STALL_MILLIS = 600_000L
        private const val EXPLOIT_TOTAL_MILLIS = 1_800_000L
        private const val MAX_LOG_CHARS = 5 * 1024 * 1024
        private val LOG_POLL_INTERVAL = 250.milliseconds

        // How many times one press will race before giving up. A lost race
        // leaves the device running, so a retry costs only time.
        private const val EXPLOIT_ATTEMPTS = 3

        // Settle thresholds, all measured on a Pixel 9a over one evening.
        // 40 minutes because every run started under ten minutes of uptime
        // failed — five of six by panicking — while runs at 40 minutes and
        // beyond took root, twice on the first page.
        private const val SETTLE_MIN_UPTIME_SECONDS = 2_400L
        // An idle device sits around 1 fork/s with 2 runnable; boot and heavy
        // app use are an order of magnitude above that.
        private const val SETTLE_MAX_FORKS_PER_SEC = 5
        private const val SETTLE_MAX_RUNNING = 4

        // Cap the wait so the UI cannot hang forever on a device that never
        // goes quiet; past the cap it races anyway rather than refusing.
        private const val SETTLE_BUDGET_MILLIS = 2_700_000L
        private val SETTLE_POLL_INTERVAL = 15.seconds
        private val FORK_SAMPLE_WINDOW = 3.seconds
    }
}
