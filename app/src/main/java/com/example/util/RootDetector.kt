// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.util

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class RootProbeResult(
    val isRooted: Boolean,
    val rootType: String,
    val hasSusFs: Boolean,
    val seLinuxMode: String,
    val binaryPath: String?,
    val probeLogs: List<String>,
    val rawIdOutput: String? = null
)

object RootDetector {

    /**
     * Real KernelSU Next / su verification.
     * Executes `su -c id` natively and ONLY sets isRooted = true if the stdout
     * explicitly contains "uid=0(root)".
     * Never uses hardcoded true states.
     */
    fun checkRootAccess(): RootProbeResult {
        val logs = mutableListOf<String>()
        logs.add("INIT: Probing real root privileges via su binary...")

        var isRooted = false
        var rootType = "Unrooted / Not Granted in KernelSU Next"
        var hasSusFs = false
        var seLinuxMode = "Enforcing"
        var binaryPath: String? = null
        var rawIdOutput: String? = null

        // 1. Check for physical presence of su binaries and KSU/SusFS nodes
        val knownSuPaths = listOf(
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )

        for (path in knownSuPaths) {
            val file = File(path)
            if (file.exists()) {
                binaryPath = path
                logs.add("FOUND: su binary candidate at $path")
                break
            }
        }

        val ksuNode = File("/sys/kernel/kernelsu")
        val ksuDir = File("/data/adb/ksu")
        val isKsuPresent = ksuNode.exists() || ksuDir.exists()
        if (isKsuPresent) {
            logs.add("PROBE: KernelSU Next kernel node detected (/sys/kernel/kernelsu)")
        }

        val susFsNode = File("/proc/susfs")
        if (susFsNode.exists()) {
            hasSusFs = true
            logs.add("PROBE: SusFS kernel driver detected (/proc/susfs)")
        }

        // Check SELinux mode
        try {
            val process = Runtime.getRuntime().exec("getenforce")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val mode = reader.readLine()
            reader.close()
            process.destroy()
            if (!mode.isNullOrBlank()) {
                seLinuxMode = mode.trim()
                logs.add("SELINUX: Policy reported as $seLinuxMode")
            }
        } catch (_: Exception) {
            logs.add("SELINUX: Enforcing (Default isolated sandbox)")
        }

        // 2. CRITICAL: Execute real `su -c id` process and verify stdout contains uid=0(root)
        try {
            logs.add("EXEC: Executing ProcessBuilder(\"su\", \"-c\", \"id\")...")
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()

            // Wait with a 2.5s timeout to prevent UI hangs if interactive prompt is pending
            val finished = process.waitFor(2500, TimeUnit.MILLISECONDS)
            if (finished) {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText().trim()
                reader.close()
                rawIdOutput = output
                logs.add("STDOUT: \"$output\" (Exit Code: ${process.exitValue()})")

                if (output.contains("uid=0(root)")) {
                    isRooted = true
                    rootType = when {
                        isKsuPresent && hasSusFs -> "KernelSU Next + SusFS"
                        isKsuPresent -> "KernelSU Next"
                        hasSusFs -> "SusFS Root"
                        else -> "Standard Linux Superuser (uid=0)"
                    }
                    logs.add("VERIFIED: Superuser confirmed uid=0(root)! Super-node RF unlocked.")
                } else {
                    isRooted = false
                    logs.add("DENIED: Output does not contain 'uid=0(root)'. Output: $output")
                }
            } else {
                process.destroyForcibly()
                logs.add("TIMEOUT: 'su -c id' timed out (KernelSU Next authorization prompt pending or denied).")
            }
        } catch (e: Exception) {
            logs.add("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            logs.add("STATUS: su invocation failed or permission denied.")
        }

        if (!isRooted) {
            logs.add("Root Not Granted — Open KernelSU Next Manager -> Superuser -> Enable PulseMesh, then tap Verify Root.")
        }

        return RootProbeResult(
            isRooted = isRooted,
            rootType = rootType,
            hasSusFs = hasSusFs,
            seLinuxMode = seLinuxMode,
            binaryPath = binaryPath,
            probeLogs = logs,
            rawIdOutput = rawIdOutput
        )
    }

    /**
     * Legacy/Testing probe bridge.
     */
    fun executeProbe(forceSimulateOnePlus15Root: Boolean = false): RootProbeResult {
        if (forceSimulateOnePlus15Root) {
            return RootProbeResult(
                isRooted = true,
                rootType = "OnePlus 15 CN (Rooted / KernelSU Next + SusFS)",
                hasSusFs = true,
                seLinuxMode = "Permissive / SusFS Hooked",
                binaryPath = "/data/adb/ksu/bin/su",
                probeLogs = listOf(
                    "SIMULATED: OnePlus 15 CN root profile active.",
                    "VERIFIED: uid=0(root)"
                ),
                rawIdOutput = "uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"
            )
        }
        return checkRootAccess()
    }
}
