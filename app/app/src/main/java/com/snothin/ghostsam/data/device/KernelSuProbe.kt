package com.snothin.ghostsam.data.device

import android.content.Context
import java.io.File
import com.snothin.ghostsam.data.channel.ExploitChannel
import com.snothin.ghostsam.data.channel.SuController
import com.snothin.ghostsam.data.prefs.ExploitPrefs

/** MidoriSU activation probe. A Shizuku grant != root; on Samsung, file probes alone are
 *  unreliable (/sys/module hidden; app uid cannot read /proc/modules) - prefer the channel
 *  probe once ready. MidoriSU keeps the KernelSU driver and module name internally. */
object KernelSuProbe {
    private const val PACKAGE_MIDORISU = "com.midori.supermanager"

    fun managerPackageCandidates(context: Context): List<String> = listOf(PACKAGE_MIDORISU)

    fun preferredManagerPackage(context: Context): String {
        val candidates = managerPackageCandidates(context)
        return candidates.firstOrNull { launchIntentVisible(context, it) } ?: candidates.first()
    }

    /** Rooted but su denied: rooted = this-boot receipt or module fallback; denied = SuController.isAvailable
     *  false. A no-ksu session has no su by design (unless upgraded - then expect a pending grant). */
    fun isRootedWithSuDenied(context: Context): Boolean {
        if (SuController.isAvailable()) return false
        if (ExploitPrefs.receiptMode(context) == "noksu" && !ExploitPrefs.isKsudUpgraded(context)) return false
        val token = ExploitPrefs.currentBootToken()
        return (token != null && ExploitPrefs.isReceiptVerified(context, token)) || isActive()
    }

    fun isManagerInstalled(context: Context): Boolean =
        managerPackageCandidates(context).any { launchIntentVisible(context, it) }

    fun probeManagerInstalled(context: Context): Boolean = isManagerInstalled(context)

    private fun launchIntentVisible(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    fun isActive(): Boolean {
        if (File("/sys/module/kernelsu").exists()) return true
        return runCatching {
            File("/proc/modules").readText().lineSequence()
                .any { it.startsWith("kernelsu ") }
        }.getOrDefault(false)
    }

    fun isActiveViaShizuku(): Boolean = runCatching {
        val process = ExploitChannel.exec(
            arrayOf("sh", "-c", "grep -q '^kernelsu ' /proc/modules && su -c id"),
        )
        val output = readProcessBounded(process)
        process.waitFor() == 0 && output.contains("uid=0")
    }.getOrDefault(false)

    fun isModuleLoadedViaChannel(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (moduleProbeAt != 0L && now - moduleProbeAt < MODULE_PROBE_TTL_MS) return moduleProbeValue
        val result = runCatching {
            val process = ExploitChannel.exec(
                arrayOf("sh", "-c", "grep -q '^kernelsu ' /proc/modules && echo LOADED"),
            )
            val output = readProcessBounded(process)
            process.waitFor()
            output.contains("LOADED")
        }.getOrDefault(false)
        moduleProbeValue = result
        moduleProbeAt = now
        return result
    }

    private const val MODULE_PROBE_TTL_MS = 5_000L

    @Volatile
    private var moduleProbeValue: Boolean = false

    @Volatile
    private var moduleProbeAt: Long = 0L

    private fun readProcessBounded(process: Process, timeoutMs: Long = 5_000): String {
        val holder = arrayOf("")
        val reader = Thread {
            runCatching { holder[0] = process.inputStream.bufferedReader().use { it.readText() } }
        }.apply { isDaemon = true }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) runCatching { process.destroy() }
        return holder[0]
    }

    enum class SuAuthState { Rooted, Denied, Unavailable }

    private const val SU_AUTH_PROBE =
        "grep -q '^kernelsu ' /proc/modules && { command -v su; su -c id; } || echo NO_MODULE"

    private const val SU_AUTH_NO_MODULE = "NO_MODULE"

    // Module check comes first: after a reboot su binaries may linger while the module is gone;
    // that is "needs re-exploit", NOT "not granted".
    fun suAuthState(): SuAuthState = runCatching {
        val process = ExploitChannel.exec(arrayOf("sh", "-c", SU_AUTH_PROBE))
        val output = readProcessBounded(process)
        process.waitFor()
        when {
            output.contains(SU_AUTH_NO_MODULE) -> SuAuthState.Unavailable
            output.contains("uid=0") -> SuAuthState.Rooted
            output.lineSequence().any { it.contains("su") } -> SuAuthState.Denied
            else -> SuAuthState.Unavailable
        }
    }.getOrDefault(SuAuthState.Unavailable)
}
