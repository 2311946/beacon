package me.rerere.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

private const val KILL_WAIT_MS = 2_000L
private const val KILL_ROUNDS = 10
private const val KILL_ROUND_WAIT_MS = 200L
private const val PID_WAIT_MS = 1_000L

/** Terminate PRoot while it is still tracing its guest processes. */
internal fun terminateProot(process: Process, pidFile: File) {
    val pids = readPids(process, pidFile)
    if (pids == null) {
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        return
    }

    val (_, prootPid) = pids
    repeat(KILL_ROUNDS) {
        traceesOf(prootPid).forEach { signal(it, OsConstants.SIGKILL) }
        if (process.waitFor(KILL_ROUND_WAIT_MS, TimeUnit.MILLISECONDS)) return
    }
    process.destroyForcibly()
    process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
}

private fun readPids(process: Process, pidFile: File): Pair<Int, Int>? {
    val deadline = System.currentTimeMillis() + PID_WAIT_MS
    while (true) {
        val pids = runCatching { pidFile.readText() }
            .getOrNull()
            ?.trim()
            ?.split(' ')
            ?.mapNotNull { it.toIntOrNull() }
        if (pids?.size == 2) return pids[0] to pids[1]
        if (!process.isAlive || System.currentTimeMillis() > deadline) return null
        Thread.sleep(50)
    }
}

private fun traceesOf(tracerPid: Int): List<Int> =
    File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val tracer = runCatching {
            File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
            }
        }.getOrNull()
        pid.takeIf { tracer == tracerPid }
    }

private fun signal(pid: Int, signal: Int) {
    try {
        Os.kill(pid, signal)
    } catch (_: ErrnoException) {
        // The process may have exited between reading /proc and sending the signal.
    }
}
