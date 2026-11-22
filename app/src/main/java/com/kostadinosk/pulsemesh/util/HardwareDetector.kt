// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.telephony.euicc.EuiccManager
import com.kostadinosk.pulsemesh.model.DeviceBrand
import com.kostadinosk.pulsemesh.model.DeviceCatalog
import com.kostadinosk.pulsemesh.model.PhoneModelProfile
import com.kostadinosk.pulsemesh.model.SocFamily
import java.io.File

data class DetectedHardwareInfo(
    val model: String,
    val manufacturer: String,
    val brand: String = manufacturer,
    val deviceCodename: String = "",
    val productName: String = "",
    val hardwareBoard: String,
    val socManufacturer: String = "",
    val socModel: String = "",
    val cpuInfoHardware: String = "",
    val cpuCores: Int = 8,
    val totalRamMb: Long = 0L,
    val androidRelease: String = Build.VERSION.RELEASE ?: "Unknown",
    val androidSdkInt: Int = Build.VERSION.SDK_INT,
    val gpuRenderer: String,
    val detectedSocFamily: SocFamily,
    val batteryPercent: Int,
    val isEmulator: Boolean,
    val supportsBle: Boolean = true,
    val supportsBleExtendedAdv: Boolean = false,
    val supportsBleCodedPhy: Boolean = false,
    val supportsBle2MPhy: Boolean = false,
    val supportsWifiDirect: Boolean = false,
    val supportsWifiAware: Boolean = false,
    val supportsEuicc: Boolean = false
) {
    val resolvedSocDisplay: String
        get() = listOf(socManufacturer, socModel, cpuInfoHardware)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .distinct()
            .joinToString(" ")
            .ifBlank { detectedSocFamily.familyName }

    val supportedRadioLayersSummary: String
        get() = buildList {
            if (supportsBle) add("BLE 1M")
            if (supportsBleExtendedAdv) add("BLE Ext")
            if (supportsBleCodedPhy) add("Coded PHY")
            if (supportsBle2MPhy) add("BLE 2M")
            if (supportsWifiDirect) add("Wi-Fi Direct")
            if (supportsWifiAware) add("Wi-Fi Aware")
            if (supportsEuicc) add("eUICC")
        }.joinToString(" • ").ifBlank { "Standard AOSP Radios" }
}

data class HardwareMismatchReport(
    val isMismatched: Boolean,
    val detectedSummary: String,
    val selectedSummary: String,
    val recommendedProfile: PhoneModelProfile,
    val wlanDriverCollisionRisk: String,
    val virtualInterfaceLockRisk: String,
    val txPowerMismatchRisk: String,
    val oemFreezerMismatchRisk: String
)

object HardwareDetector {

    fun detectHardware(context: Context): DetectedHardwareInfo {
        val model = Build.MODEL ?: "Unknown"
        val manufacturer = (Build.MANUFACTURER ?: "Generic").uppercase()
        val brand = (Build.BRAND ?: manufacturer).uppercase()
        val deviceCodename = Build.DEVICE ?: ""
        val productName = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: "unknown"
        val board = Build.BOARD ?: "unknown"

        val socMfg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { Build.SOC_MANUFACTURER ?: "" }.getOrDefault("")
        } else ""
        val socMdl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { Build.SOC_MODEL ?: "" }.getOrDefault("")
        } else ""
        val cpuHw = readCpuInfoHardware()
        val cpuCores = runCatching { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }.getOrDefault(8)
        val totalRamMb = readTotalRamMb(context)

        val gpu = queryGpuRenderer()
        val isEmu = isRunningInEmulator(model, hardware, board, gpu)

        val combinedSocQuery = "$socMfg $socMdl $cpuHw $hardware $board $gpu $model".lowercase()
        val socFamily = when {
            isEmu -> SocFamily.EMULATOR_DESKTOP
            gpu.contains("Adreno", ignoreCase = true) ||
                combinedSocQuery.contains("qcom") ||
                combinedSocQuery.contains("qualcomm") ||
                combinedSocQuery.contains("snapdragon") ||
                combinedSocQuery.contains("sm8") ||
                combinedSocQuery.contains("sm7") ||
                combinedSocQuery.contains("sm6") ->
                SocFamily.QUALCOMM_SNAPDRAGON
            gpu.contains("Xclipse", ignoreCase = true) ||
                combinedSocQuery.contains("exynos") ||
                combinedSocQuery.contains("s5e") ||
                combinedSocQuery.contains("samsung slsi") ->
                SocFamily.SAMSUNG_EXYNOS
            combinedSocQuery.contains("tensor") ||
                combinedSocQuery.contains("gs1") ||
                combinedSocQuery.contains("gs2") ||
                combinedSocQuery.contains("gs4") ||
                combinedSocQuery.contains("zuma") ||
                combinedSocQuery.contains("cloudripper") ||
                model.contains("Pixel", ignoreCase = true) ->
                SocFamily.GOOGLE_TENSOR
            gpu.contains("Mali", ignoreCase = true) ||
                gpu.contains("Immortalis", ignoreCase = true) ||
                combinedSocQuery.contains("mt6") ||
                combinedSocQuery.contains("mt8") ||
                combinedSocQuery.contains("mediatek") ||
                combinedSocQuery.contains("dimensity") ||
                combinedSocQuery.contains("helio") ->
                SocFamily.MEDIATEK_DIMENSITY
            else -> SocFamily.GENERIC_AOSP
        }

        val pm = context.packageManager
        val supportsBle = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) }.getOrDefault(true)
        val supportsWifiDirect = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) }.getOrDefault(false)
        val supportsWifiAware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) }.getOrDefault(false)
        } else false

        val btAdapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

        val supportsBleExtAdv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && btAdapter != null) {
            runCatching { btAdapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)
        } else false
        val supportsBleCodedPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && btAdapter != null) {
            runCatching { btAdapter.isLeCodedPhySupported }.getOrDefault(false)
        } else false
        val supportsBle2MPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && btAdapter != null) {
            runCatching { btAdapter.isLe2MPhySupported }.getOrDefault(false)
        } else false
        val supportsEuicc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                (context.getSystemService(Context.EUICC_SERVICE) as? EuiccManager)?.isEnabled == true
            }.getOrDefault(false)
        } else false

        val battery = readBatteryPercentage(context)

        return DetectedHardwareInfo(
            model = model,
            manufacturer = manufacturer,
            brand = brand,
            deviceCodename = deviceCodename,
            productName = productName,
            hardwareBoard = "$hardware / $board",
            socManufacturer = socMfg,
            socModel = socMdl,
            cpuInfoHardware = cpuHw,
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            androidSdkInt = Build.VERSION.SDK_INT,
            gpuRenderer = gpu,
            detectedSocFamily = socFamily,
            batteryPercent = battery,
            isEmulator = isEmu,
            supportsBle = supportsBle,
            supportsBleExtendedAdv = supportsBleExtAdv,
            supportsBleCodedPhy = supportsBleCodedPhy,
            supportsBle2MPhy = supportsBle2MPhy,
            supportsWifiDirect = supportsWifiDirect,
            supportsWifiAware = supportsWifiAware,
            supportsEuicc = supportsEuicc
        )
    }

    /**
     * Resolves a PhoneModelProfile for ANY Android phone hardware:
     * Checks DeviceCatalog first, and if unlisted, dynamically builds a complete live hardware profile
     * from Android's runtime APIs (`Build.MODEL`, SoC, CPU cores, RAM, BLE PHY, and Wi-Fi capabilities).
     */
    fun resolveAutoMatchedProfile(detected: DetectedHardwareInfo): PhoneModelProfile {
        if (detected.isEmulator) {
            return DeviceCatalog.getProfileById("desktop_emulator_container")
        }

        val catalogCandidate = DeviceCatalog.findBestMatch(
            model = detected.model,
            socModel = "${detected.socManufacturer} ${detected.socModel} ${detected.cpuInfoHardware} ${detected.hardwareBoard}",
            gpu = detected.gpuRenderer,
            isEmulator = detected.isEmulator,
            deviceName = detected.deviceCodename,
            boardName = Build.BOARD ?: ""
        )

        val exactCatalogHit = catalogCandidate.id != "generic_android_legacy" &&
            (catalogCandidate.modelName.contains(detected.model, ignoreCase = true) ||
                catalogCandidate.regionalModelCodenames.any {
                    it.equals(detected.model, ignoreCase = true) ||
                        it.equals(detected.deviceCodename, ignoreCase = true)
                })

        if (exactCatalogHit) {
            return catalogCandidate
        }

        return buildLiveRuntimeProfile(detected)
    }

    fun buildLiveRuntimeProfile(detected: DetectedHardwareInfo): PhoneModelProfile {
        val cleanMfg = detected.manufacturer.lowercase().replaceFirstChar { it.uppercase() }
        val displayModel = if (detected.model.contains(detected.manufacturer, ignoreCase = true)) {
            detected.model
        } else {
            "$cleanMfg ${detected.model}".trim()
        }
        val brandEnum = when {
            detected.manufacturer.contains("ONEPLUS", ignoreCase = true) -> DeviceBrand.ONEPLUS
            detected.manufacturer.contains("SAMSUNG", ignoreCase = true) ->
                if (detected.detectedSocFamily == SocFamily.QUALCOMM_SNAPDRAGON) DeviceBrand.SAMSUNG_SNAPDRAGON else DeviceBrand.SAMSUNG_EXYNOS
            detected.manufacturer.contains("GOOGLE", ignoreCase = true) -> DeviceBrand.GOOGLE_PIXEL
            detected.manufacturer.contains("XIAOMI", ignoreCase = true) ||
                detected.manufacturer.contains("REDMI", ignoreCase = true) ||
                detected.manufacturer.contains("POCO", ignoreCase = true) ->
                if (detected.detectedSocFamily == SocFamily.QUALCOMM_SNAPDRAGON) DeviceBrand.XIAOMI_SNAPDRAGON else DeviceBrand.XIAOMI_MEDIATEK
            detected.manufacturer.contains("MOTOROLA", ignoreCase = true) ||
                detected.manufacturer.contains("NOTHING", ignoreCase = true) -> DeviceBrand.MOTOROLA_NOTHING
            detected.manufacturer.contains("VIVO", ignoreCase = true) ||
                detected.manufacturer.contains("OPPO", ignoreCase = true) ||
                detected.manufacturer.contains("REALME", ignoreCase = true) ||
                detected.manufacturer.contains("SONY", ignoreCase = true) ||
                detected.manufacturer.contains("ASUS", ignoreCase = true) -> DeviceBrand.VIVO_OPPO_REALME_GAMING
            else -> DeviceBrand.LEGACY_EMULATOR
        }

        val ramText = if (detected.totalRamMb > 0) " • ${(detected.totalRamMb / 1024.0).let { String.format(java.util.Locale.US, "%.1f GB RAM", it) }}" else ""
        val socText = "${detected.resolvedSocDisplay} (${detected.cpuCores} Cores$ramText)"
        val oemKiller = when {
            detected.manufacturer.contains("SAMSUNG", ignoreCase = true) -> "OneUI Freecess / AppStandby"
            detected.manufacturer.contains("XIAOMI", ignoreCase = true) ||
                detected.manufacturer.contains("REDMI", ignoreCase = true) ||
                detected.manufacturer.contains("POCO", ignoreCase = true) -> "HyperOS / MIUI Millet"
            detected.manufacturer.contains("ONEPLUS", ignoreCase = true) ||
                detected.manufacturer.contains("OPPO", ignoreCase = true) ||
                detected.manufacturer.contains("REALME", ignoreCase = true) -> "ColorOS / OxygenOS Hans"
            detected.manufacturer.contains("HONOR", ignoreCase = true) ||
                detected.manufacturer.contains("HUAWEI", ignoreCase = true) -> "MagicOS PowerGenie"
            else -> "AOSP Standard Doze"
        }

        val sanitizedId = "live_${detected.manufacturer}_${detected.model}"
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        return PhoneModelProfile(
            id = sanitizedId.ifBlank { "live_android_device" },
            brand = brandEnum,
            modelName = displayModel.ifBlank { "Android Mesh Node" },
            socName = socText,
            socFamily = detected.detectedSocFamily,
            wifiBleDriver = detected.supportedRadioLayersSummary,
            oemBackgroundKiller = oemKiller,
            maxTxPowerDbm = if (detected.supportsBleCodedPhy) 23 else 20,
            supportsBleCodedPhy = detected.supportsBleCodedPhy,
            regionalModelCodenames = listOfNotNull(
                detected.model.takeIf { it.isNotBlank() },
                detected.deviceCodename.takeIf { it.isNotBlank() }
            ),
            marketRegions = "Runtime Auto-Profile (API ${detected.androidSdkInt})"
        )
    }

    fun evaluateMismatch(
        detected: DetectedHardwareInfo,
        selectedProfile: PhoneModelProfile
    ): HardwareMismatchReport {
        val isUniversalAutoProfile = selectedProfile.id.startsWith("live_") ||
            selectedProfile.id == "generic_android_legacy"
        val familyMatches = isUniversalAutoProfile ||
            detected.detectedSocFamily == selectedProfile.socFamily ||
            detected.detectedSocFamily == SocFamily.GENERIC_AOSP
        val isMismatched = !familyMatches

        val recommended = resolveAutoMatchedProfile(detected)

        val wlanRisk = "Injecting ${selectedProfile.wifiBleDriver} commands into real host driver (${detected.hardwareBoard} / ${detected.gpuRenderer}) may conflict with host HAL."
        val interfaceLockRisk = "Incompatible 802.11 vendor IE interface for ${selectedProfile.modelName} can affect wpa_supplicant stability."
        val txRisk = "Applying ${selectedProfile.modelName} ${selectedProfile.maxTxPowerDbm} dBm calibrations on unmatched RF front-end may throttle TX power."
        val freezerRisk = "Running ${selectedProfile.oemBackgroundKiller} bypass procedures on host (${detected.manufacturer} / ${detected.model}) may leave background tasks unexempted."

        return HardwareMismatchReport(
            isMismatched = isMismatched,
            detectedSummary = "${detected.manufacturer} ${detected.model} (${detected.resolvedSocDisplay})",
            selectedSummary = "${selectedProfile.modelName} [${selectedProfile.socFamily.familyName}]",
            recommendedProfile = recommended,
            wlanDriverCollisionRisk = wlanRisk,
            virtualInterfaceLockRisk = interfaceLockRisk,
            txPowerMismatchRisk = txRisk,
            oemFreezerMismatchRisk = freezerRisk
        )
    }

    private fun readCpuInfoHardware(): String {
        return runCatching {
            val file = File("/proc/cpuinfo")
            if (!file.canRead()) return@runCatching ""
            file.readLines().firstNotNullOfOrNull { line ->
                val lower = line.lowercase()
                if (lower.startsWith("hardware") || lower.startsWith("model name")) {
                    line.substringAfter(':', "").trim().takeIf { it.isNotBlank() }
                } else null
            } ?: ""
        }.getOrDefault("")
    }

    private fun readTotalRamMb(context: Context): Long {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(0L)
    }

    fun readBatteryPercentage(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap in 1..100) {
                return cap
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(1, 100)
            } else {
                85
            }
        } catch (_: Exception) {
            85
        }
    }

    private fun isRunningInEmulator(model: String, hardware: String, board: String, gpu: String): Boolean {
        val lowerGpu = gpu.lowercase()
        val lowerModel = model.lowercase()
        val lowerHw = hardware.lowercase()
        val lowerBoard = board.lowercase()

        return lowerGpu.contains("swiftshader") ||
            lowerGpu.contains("llvmpipe") ||
            lowerGpu.contains("emulator") ||
            lowerGpu.contains("angle") ||
            lowerModel.contains("sdk") ||
            lowerModel.contains("emulator") ||
            lowerHw.contains("goldfish") ||
            lowerHw.contains("ranchu") ||
            lowerBoard.contains("goldfish")
    }

    private fun queryGpuRenderer(): String {
        return try {
            val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "GLES Default"

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "GLES Default"

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return "GLES Default"

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)

            EGL14.eglMakeCurrent(display, surface, surface, context)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GLES Renderer"

            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)

            renderer
        } catch (_: Exception) {
            "GLES Virtual Sandbox"
        }
    }
}
