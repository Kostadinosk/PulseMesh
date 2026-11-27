// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.kostadinosk.pulsemesh.model.DeviceCatalog
import com.kostadinosk.pulsemesh.model.PhoneModelProfile

data class OemBatteryBypassGuide(
    val oemName: String,
    val backgroundKillerName: String,
    val severityLabel: String,
    val instructions: List<String>,
    val isIgnoringBatteryOptimizations: Boolean,
    val oemSettingsComponents: List<Pair<String, String>> = emptyList()
)

data class HardwareCapabilityReport(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val deviceCodename: String,
    val productCode: String,
    val board: String,
    val hardware: String,
    val socModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val resolvedMarketingName: String,
    val resolvedRegion: String,
    val resolvedSocFamily: String,
    val matchedCatalogProfile: PhoneModelProfile?,
    val bleSupported: Boolean,
    val ble5CodedPhySupported: Boolean,
    val ble2MPhySupported: Boolean,
    val bleExtendedAdvSupported: Boolean,
    val wifiAwareSupported: Boolean,
    val wifiDirectSupported: Boolean,
    val nfcSupported: Boolean,
    val nfcHceSupported: Boolean,
    val usbHostOtgSupported: Boolean,
    val barometerSupported: Boolean,
    val cameraSupported: Boolean,
    val flashlightSupported: Boolean,
    val batteryBypassGuide: OemBatteryBypassGuide
) {
    val activeHardwareSummary: String
        get() = buildList {
            if (ble5CodedPhySupported) add("BLE 5 Coded PHY")
            else if (bleSupported) add("BLE 4.x/5")
            if (wifiAwareSupported) add("Wi-Fi Aware")
            if (wifiDirectSupported) add("Wi-Fi Direct")
            if (nfcHceSupported) add("NFC HCE")
            else if (nfcSupported) add("NFC")
            if (usbHostOtgSupported) add("USB-OTG LoRa")
            if (barometerSupported) add("Barometer")
        }.joinToString(" | ").ifBlank { "Standard Android Radio" }
}

object HardwareCapabilityDetector {

    private data class CodenameEntry(
        val marketingName: String,
        val socName: String,
        val regionHint: String = "Global"
    )

    // Accurate codename and model prefix registry covering major regional variants
    private val codenameRegistry: Map<String, CodenameEntry> = mapOf(
        // Google Pixel (Tensor G1 - G5)
        "frankel" to CodenameEntry("Google Pixel 10", "Google Tensor G5"),
        "blazer" to CodenameEntry("Google Pixel 10 Pro", "Google Tensor G5"),
        "mustang" to CodenameEntry("Google Pixel 10 Pro XL", "Google Tensor G5"),
        "rango" to CodenameEntry("Google Pixel 10 Pro Fold", "Google Tensor G5"),
        "tokay" to CodenameEntry("Google Pixel 9", "Google Tensor G4"),
        "caiman" to CodenameEntry("Google Pixel 9 Pro", "Google Tensor G4"),
        "komodo" to CodenameEntry("Google Pixel 9 Pro XL", "Google Tensor G4"),
        "comet" to CodenameEntry("Google Pixel 9 Pro Fold", "Google Tensor G4"),
        "tegu" to CodenameEntry("Google Pixel 9a", "Google Tensor G4"),
        "shiba" to CodenameEntry("Google Pixel 8", "Google Tensor G3"),
        "husky" to CodenameEntry("Google Pixel 8 Pro", "Google Tensor G3"),
        "akita" to CodenameEntry("Google Pixel 8a", "Google Tensor G3"),
        "panther" to CodenameEntry("Google Pixel 7", "Google Tensor G2"),
        "cheetah" to CodenameEntry("Google Pixel 7 Pro", "Google Tensor G2"),
        "lynx" to CodenameEntry("Google Pixel 7a", "Google Tensor G2"),
        "felix" to CodenameEntry("Google Pixel Fold", "Google Tensor G2"),
        "tangorpro" to CodenameEntry("Google Pixel Tablet", "Google Tensor G2"),
        "oriole" to CodenameEntry("Google Pixel 6", "Google Tensor G1"),
        "raven" to CodenameEntry("Google Pixel 6 Pro", "Google Tensor G1"),
        "bluejay" to CodenameEntry("Google Pixel 6a", "Google Tensor G1"),

        // Samsung Galaxy S / Z / A codenames
        "pa3q" to CodenameEntry("Samsung Galaxy S25 Ultra", "Snapdragon 8 Elite for Galaxy"),
        "pa2q" to CodenameEntry("Samsung Galaxy S25+", "Snapdragon 8 Elite for Galaxy"),
        "pa1q" to CodenameEntry("Samsung Galaxy S25", "Snapdragon 8 Elite for Galaxy"),
        "e3q" to CodenameEntry("Samsung Galaxy S24 Ultra", "Snapdragon 8 Gen 3 for Galaxy"),
        "e2q" to CodenameEntry("Samsung Galaxy S24+", "Snapdragon 8 Gen 3", "North America / China"),
        "e2s" to CodenameEntry("Samsung Galaxy S24+", "Samsung Exynos 2400", "Global / Europe"),
        "e1q" to CodenameEntry("Samsung Galaxy S24", "Snapdragon 8 Gen 3", "North America / China"),
        "e1s" to CodenameEntry("Samsung Galaxy S24", "Samsung Exynos 2400", "Global / Europe"),
        "r12s" to CodenameEntry("Samsung Galaxy S24 FE", "Samsung Exynos 2400e"),
        "dm3q" to CodenameEntry("Samsung Galaxy S23 Ultra", "Snapdragon 8 Gen 2 for Galaxy"),
        "dm2q" to CodenameEntry("Samsung Galaxy S23+", "Snapdragon 8 Gen 2 for Galaxy"),
        "dm1q" to CodenameEntry("Samsung Galaxy S23", "Snapdragon 8 Gen 2 for Galaxy"),
        "r11q" to CodenameEntry("Samsung Galaxy S23 FE", "Snapdragon 8 Gen 1", "North America"),
        "r11s" to CodenameEntry("Samsung Galaxy S23 FE", "Samsung Exynos 2200", "Global / Europe"),
        "b0q" to CodenameEntry("Samsung Galaxy S22 Ultra", "Snapdragon 8 Gen 1", "North America / Korea"),
        "b0s" to CodenameEntry("Samsung Galaxy S22 Ultra", "Samsung Exynos 2200", "Europe"),
        "q6q" to CodenameEntry("Samsung Galaxy Z Fold6", "Snapdragon 8 Gen 3"),
        "b6q" to CodenameEntry("Samsung Galaxy Z Flip6", "Snapdragon 8 Gen 3"),
        "q5q" to CodenameEntry("Samsung Galaxy Z Fold5", "Snapdragon 8 Gen 2"),
        "a55x" to CodenameEntry("Samsung Galaxy A55 5G", "Samsung Exynos 1480"),
        "a54x" to CodenameEntry("Samsung Galaxy A54 5G", "Samsung Exynos 1380"),
        "a53x" to CodenameEntry("Samsung Galaxy A53 5G", "Samsung Exynos 1280"),
        "a35x" to CodenameEntry("Samsung Galaxy A35 5G", "Samsung Exynos 1380"),

        // OnePlus
        "op5d55l1" to CodenameEntry("OnePlus 13", "Snapdragon 8 Elite"),
        "dodge" to CodenameEntry("OnePlus 13", "Snapdragon 8 Elite"),
        "op594dl1" to CodenameEntry("OnePlus 12", "Snapdragon 8 Gen 3"),
        "waffle" to CodenameEntry("OnePlus 12", "Snapdragon 8 Gen 3"),
        "aston" to CodenameEntry("OnePlus 12R", "Snapdragon 8 Gen 2"),
        "salami" to CodenameEntry("OnePlus 11", "Snapdragon 8 Gen 2"),
        "avalon" to CodenameEntry("OnePlus Nord 4", "Snapdragon 7+ Gen 3"),

        // Xiaomi / Redmi / POCO
        "dada" to CodenameEntry("Xiaomi 15", "Snapdragon 8 Elite"),
        "haotian" to CodenameEntry("Xiaomi 15 Pro", "Snapdragon 8 Elite"),
        "xuanyuan" to CodenameEntry("Xiaomi 15 Ultra", "Snapdragon 8 Elite"),
        "houji" to CodenameEntry("Xiaomi 14", "Snapdragon 8 Gen 3"),
        "shennong" to CodenameEntry("Xiaomi 14 Pro", "Snapdragon 8 Gen 3"),
        "aurora" to CodenameEntry("Xiaomi 14 Ultra", "Snapdragon 8 Gen 3"),
        "degas" to CodenameEntry("Xiaomi 14T", "MediaTek Dimensity 8300-Ultra"),
        "rothko" to CodenameEntry("Xiaomi 14T Pro", "MediaTek Dimensity 9300+"),
        "fuxi" to CodenameEntry("Xiaomi 13", "Snapdragon 8 Gen 2"),
        "nuwa" to CodenameEntry("Xiaomi 13 Pro", "Snapdragon 8 Gen 2"),
        "ishtar" to CodenameEntry("Xiaomi 13 Ultra", "Snapdragon 8 Gen 2"),
        "peridot" to CodenameEntry("POCO F6", "Snapdragon 8s Gen 3"),
        "vermeer" to CodenameEntry("POCO F6 Pro", "Snapdragon 8 Gen 2"),
        "marble" to CodenameEntry("POCO F5", "Snapdragon 7+ Gen 2"),
        "mondrian" to CodenameEntry("POCO F5 Pro", "Snapdragon 8+ Gen 1"),
        "duchamp" to CodenameEntry("POCO X6 Pro", "MediaTek Dimensity 8300-Ultra"),
        "rodin" to CodenameEntry("POCO X7 Pro", "MediaTek Dimensity 8400-Ultra"),
        "garnet" to CodenameEntry("Redmi Note 13 Pro 5G / POCO X6", "Snapdragon 7s Gen 2"),
        "zircon" to CodenameEntry("Redmi Note 13 Pro+ 5G", "MediaTek Dimensity 7200-Ultra"),
        "amethyst" to CodenameEntry("Redmi Note 14 Pro+ 5G", "Snapdragon 7s Gen 3"),
        "malachite" to CodenameEntry("Redmi Note 14 Pro 5G", "MediaTek Dimensity 7300-Ultra"),

        // Nothing / Fairphone / Sony / Motorola / ASUS
        "pacman" to CodenameEntry("Nothing Phone (2a)", "MediaTek Dimensity 7200 Pro"),
        "pong" to CodenameEntry("Nothing Phone (2)", "Snapdragon 8+ Gen 1"),
        "spacewar" to CodenameEntry("Nothing Phone (1)", "Snapdragon 778G+"),
        "fp5" to CodenameEntry("Fairphone 5", "Qualcomm QCM6490"),
        "fp4" to CodenameEntry("Fairphone 4", "Snapdragon 750G"),
        "pdx245" to CodenameEntry("Sony Xperia 1 VI", "Snapdragon 8 Gen 3"),
        "pdx234" to CodenameEntry("Sony Xperia 1 V", "Snapdragon 8 Gen 2"),
        "eqs" to CodenameEntry("Motorola Edge 30 Ultra", "Snapdragon 8+ Gen 1"),
        "rtwo" to CodenameEntry("Motorola Edge 40 Pro / Edge+ (2023)", "Snapdragon 8 Gen 2"),
        "eqe" to CodenameEntry("Motorola Edge 50 Ultra", "Snapdragon 8s Gen 3")
    )

    private val modelPrefixRegistry: List<Pair<String, CodenameEntry>> = listOf(
        "SM-S938" to CodenameEntry("Samsung Galaxy S25 Ultra", "Snapdragon 8 Elite for Galaxy"),
        "SM-S936" to CodenameEntry("Samsung Galaxy S25+", "Snapdragon 8 Elite for Galaxy"),
        "SM-S931" to CodenameEntry("Samsung Galaxy S25", "Snapdragon 8 Elite for Galaxy"),
        "SM-S928" to CodenameEntry("Samsung Galaxy S24 Ultra", "Snapdragon 8 Gen 3 for Galaxy"),
        "SM-S926" to CodenameEntry("Samsung Galaxy S24+", "Snapdragon 8 Gen 3 / Exynos 2400"),
        "SM-S921" to CodenameEntry("Samsung Galaxy S24", "Snapdragon 8 Gen 3 / Exynos 2400"),
        "SM-S918" to CodenameEntry("Samsung Galaxy S23 Ultra", "Snapdragon 8 Gen 2 for Galaxy"),
        "SM-S916" to CodenameEntry("Samsung Galaxy S23+", "Snapdragon 8 Gen 2 for Galaxy"),
        "SM-S911" to CodenameEntry("Samsung Galaxy S23", "Snapdragon 8 Gen 2 for Galaxy"),
        "SM-S908" to CodenameEntry("Samsung Galaxy S22 Ultra", "Snapdragon 8 Gen 1 / Exynos 2200"),
        "SM-A556" to CodenameEntry("Samsung Galaxy A55 5G", "Samsung Exynos 1480"),
        "SM-A546" to CodenameEntry("Samsung Galaxy A54 5G", "Samsung Exynos 1380"),
        "SM-F956" to CodenameEntry("Samsung Galaxy Z Fold6", "Snapdragon 8 Gen 3"),
        "SM-F741" to CodenameEntry("Samsung Galaxy Z Flip6", "Snapdragon 8 Gen 3"),
        "CPH2747" to CodenameEntry("OnePlus 15", "Snapdragon 8 Elite Gen 2", "Global / Europe"),
        "PLK110" to CodenameEntry("OnePlus 15", "Snapdragon 8 Elite Gen 2", "China"),
        "CPH2653" to CodenameEntry("OnePlus 13", "Snapdragon 8 Elite", "Global / Europe"),
        "CPH2655" to CodenameEntry("OnePlus 13", "Snapdragon 8 Elite", "North America"),
        "PJZ110" to CodenameEntry("OnePlus 13", "Snapdragon 8 Elite", "China"),
        "CPH2581" to CodenameEntry("OnePlus 12", "Snapdragon 8 Gen 3", "Global / Europe"),
        "CPH2583" to CodenameEntry("OnePlus 12", "Snapdragon 8 Gen 3", "North America"),
        "PJD110" to CodenameEntry("OnePlus 12", "Snapdragon 8 Gen 3", "China"),
        "CPH2449" to CodenameEntry("OnePlus 11", "Snapdragon 8 Gen 2", "Global / Europe"),
        "CPH2451" to CodenameEntry("OnePlus 11", "Snapdragon 8 Gen 2", "North America"),
        "PHB110" to CodenameEntry("OnePlus 11", "Snapdragon 8 Gen 2", "China")
    )

    fun detect(context: Context): HardwareCapabilityReport {
        val pm = context.packageManager
        val manufacturer = (Build.MANUFACTURER ?: "Android").trim()
        val brand = (Build.BRAND ?: manufacturer).trim()
        val model = (Build.MODEL ?: "Device").trim()
        val deviceCodename = (Build.DEVICE ?: "").trim()
        val productCode = (Build.PRODUCT ?: "").trim()
        val board = (Build.BOARD ?: "").trim()
        val hardware = (Build.HARDWARE ?: "").trim()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (Build.SOC_MODEL ?: "").trim().ifBlank { hardware }
        } else {
            hardware
        }

        val btAdapter = runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter
        }.getOrNull()

        val bleSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val ble5CodedPhy = bleSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { btAdapter?.isLeCodedPhySupported == true }.getOrDefault(false)
        val ble2MPhy = bleSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { btAdapter?.isLe2MPhySupported == true }.getOrDefault(false)
        val bleExtAdv = bleSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { btAdapter?.isLeExtendedAdvertisingSupported == true }.getOrDefault(false)

        val wifiAware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val wifiDirect = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        val nfcHce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        val usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val flash = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        val barometer = runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        }.getOrDefault(false)

        val identity = resolveDeviceIdentity(
            manufacturer = manufacturer,
            model = model,
            deviceCodename = deviceCodename,
            productCode = productCode,
            socModel = socModel,
            hardware = hardware
        )

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringDoze = runCatching {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

        val guide = buildOemBatteryGuide(manufacturer, brand, ignoringDoze)

        return HardwareCapabilityReport(
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            deviceCodename = deviceCodename,
            productCode = productCode,
            board = board,
            hardware = hardware,
            socModel = socModel,
            androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
            sdkInt = Build.VERSION.SDK_INT,
            resolvedMarketingName = identity.first,
            resolvedRegion = identity.second,
            resolvedSocFamily = identity.third,
            matchedCatalogProfile = identity.fourth,
            bleSupported = bleSupported,
            ble5CodedPhySupported = ble5CodedPhy,
            ble2MPhySupported = ble2MPhy,
            bleExtendedAdvSupported = bleExtAdv,
            wifiAwareSupported = wifiAware,
            wifiDirectSupported = wifiDirect,
            nfcSupported = nfc,
            nfcHceSupported = nfcHce,
            usbHostOtgSupported = usbHost,
            barometerSupported = barometer,
            cameraSupported = camera,
            flashlightSupported = flash,
            batteryBypassGuide = guide
        )
    }

    internal data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    internal fun resolveDeviceIdentity(
        manufacturer: String,
        model: String,
        deviceCodename: String,
        productCode: String,
        socModel: String,
        hardware: String
    ): Quadruple<String, String, String, PhoneModelProfile?> {
        val modelUpper = model.uppercase()
        val deviceLower = deviceCodename.lowercase()
        val productLower = productCode.lowercase()

        // 1. Direct codename hit
        val codenameHit = codenameRegistry[deviceLower]
            ?: codenameRegistry.entries.firstOrNull { (k, _) ->
                productLower == k || productLower.endsWith("_$k")
            }?.value

        // 2. Direct model prefix hit
        val prefixHit = modelPrefixRegistry.firstOrNull { (prefix, _) ->
            modelUpper.startsWith(prefix)
        }?.second

        // 3. Catalog exact/regional codename match
        val catalogHit = DeviceCatalog.allModels.firstOrNull { prof ->
            prof.regionalModelCodenames.any { code ->
                val clean = code.trim().uppercase()
                clean.isNotEmpty() && (modelUpper == clean || modelUpper.startsWith(clean) || deviceLower == clean.lowercase())
            }
        } ?: DeviceCatalog.allModels.firstOrNull { prof ->
            prof.modelName.equals(model, ignoreCase = true)
        }

        val region = decodeRegion(manufacturer, modelUpper, productLower, codenameHit?.regionHint ?: prefixHit?.regionHint ?: catalogHit?.marketRegions)
        val marketingName = codenameHit?.marketingName
            ?: prefixHit?.marketingName
            ?: catalogHit?.modelName
            ?: if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"

        val socResolved = decodeSocName(socModel, hardware)
            ?: codenameHit?.socName
            ?: prefixHit?.socName
            ?: catalogHit?.socName
            ?: socModel.ifBlank { hardware.ifBlank { "Android SoC" } }

        return Quadruple(marketingName, region, socResolved, catalogHit)
    }

    internal fun decodeRegion(
        manufacturer: String,
        modelUpper: String,
        productLower: String,
        fallback: String?
    ): String {
        val mfr = manufacturer.lowercase()
        // Samsung regional suffixes: B=Global/EU, U/U1=USA, W=Canada, 0=China/HK, N=Korea, E=Asia/MEA/LatAm, J/Q/SCG/SC-=Japan
        if (mfr.contains("samsung") || modelUpper.startsWith("SM-")) {
            return when {
                modelUpper.endsWith("U1") -> "USA (Unlocked)"
                modelUpper.endsWith("U") -> "USA (Carrier)"
                modelUpper.endsWith("W") -> "Canada"
                modelUpper.endsWith("B") || modelUpper.endsWith("B/DS") -> "Global / Europe (EEA)"
                modelUpper.endsWith("E") || modelUpper.endsWith("E/DS") -> "Asia / Middle East / LatAm"
                modelUpper.endsWith("0") -> "China / Hong Kong / Taiwan"
                modelUpper.endsWith("N") -> "South Korea"
                modelUpper.endsWith("J") || modelUpper.endsWith("Q") || modelUpper.startsWith("SCG") || modelUpper.startsWith("SC-") -> "Japan"
                else -> fallback ?: "Global"
            }
        }
        // Xiaomi / Redmi / POCO model trailing region letter (e.g. 24129PN74G / E / C / I / J)
        if (mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco")) {
            return when {
                productLower.contains("eea") || modelUpper.endsWith("E") -> "Europe (EEA)"
                productLower.contains("global") || modelUpper.endsWith("G") -> "Global"
                productLower.contains("_in") || modelUpper.endsWith("I") -> "India"
                modelUpper.endsWith("C") -> "China"
                modelUpper.endsWith("J") -> "Japan"
                else -> fallback ?: "Global"
            }
        }
        // OnePlus product/model codes
        if (mfr.contains("oneplus")) {
            return when {
                productLower.contains("eea") || productLower.endsWith("eu") -> "Europe (EEA)"
                productLower.contains("_in") || productLower.endsWith("in") -> "India"
                productLower.endsWith("na") || modelUpper.endsWith("2655") || modelUpper.endsWith("2583") || modelUpper.endsWith("2451") -> "North America"
                modelUpper.startsWith("PJ") || modelUpper.startsWith("PH") || modelUpper.startsWith("PLK") -> "China"
                else -> fallback ?: "Global"
            }
        }
        return fallback?.takeIf { it.isNotBlank() } ?: "Global"
    }

    internal fun decodeSocName(socModel: String, hardware: String): String? {
        val key = "${socModel.uppercase()} ${hardware.uppercase()}"
        return when {
            "SM8750" in key || "SUN" in key -> "Qualcomm Snapdragon 8 Elite"
            "SM8650" in key || "PINEAPPLE" in key -> "Qualcomm Snapdragon 8 Gen 3"
            "SM8550" in key || "KALAMA" in key -> "Qualcomm Snapdragon 8 Gen 2"
            "SM8475" in key || "SM8450" in key || "TARO" in key -> "Qualcomm Snapdragon 8/8+ Gen 1"
            "SM8635" in key -> "Qualcomm Snapdragon 8s Gen 3"
            "SM7675" in key -> "Qualcomm Snapdragon 7+ Gen 3"
            "SM7435" in key -> "Qualcomm Snapdragon 7s Gen 2"
            "SM7635" in key -> "Qualcomm Snapdragon 7s Gen 3"
            "TENSOR G5" in key || "LGA" in key -> "Google Tensor G5"
            "TENSOR G4" in key || "ZUMA PRO" in key || "ZUMAPRO" in key -> "Google Tensor G4"
            "TENSOR G3" in key || "ZUMA" in key -> "Google Tensor G3"
            "TENSOR G2" in key || "GS201" in key -> "Google Tensor G2"
            "TENSOR" in key || "GS101" in key -> "Google Tensor G1"
            "S5E9945" in key || "EXYNOS2400" in key -> "Samsung Exynos 2400"
            "S5E9925" in key || "EXYNOS2200" in key -> "Samsung Exynos 2200"
            "S5E8845" in key || "EXYNOS1480" in key -> "Samsung Exynos 1480"
            "S5E8835" in key || "EXYNOS1380" in key -> "Samsung Exynos 1380"
            "MT6991" in key -> "MediaTek Dimensity 9400"
            "MT6989" in key -> "MediaTek Dimensity 9300"
            "MT6897" in key -> "MediaTek Dimensity 8300"
            "MT6899" in key -> "MediaTek Dimensity 8400"
            "MT6886" in key -> "MediaTek Dimensity 7200"
            "RANCHU" in key || "GOLDFISH" in key || "ROBOLECTRIC" in key -> "Android Virtual / Emulator"
            else -> null
        }
    }

    private fun buildOemBatteryGuide(
        manufacturer: String,
        brand: String,
        ignoringDoze: Boolean
    ): OemBatteryBypassGuide {
        val m = "$manufacturer $brand".lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> OemBatteryBypassGuide(
                oemName = "Xiaomi / Redmi / POCO",
                backgroundKillerName = "HyperOS / MIUI PowerKeeper",
                severityLabel = "Aggressive background kill",
                instructions = listOf(
                    "Enable Autostart for PulseMesh in Security -> Permissions -> Background autostart.",
                    "Set Battery Saver to No restrictions for PulseMesh.",
                    "Lock PulseMesh in Recents so BLE/Wi-Fi mesh relays survive screen-off."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze,
                oemSettingsComponents = listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
            m.contains("samsung") -> OemBatteryBypassGuide(
                oemName = "Samsung Galaxy",
                backgroundKillerName = "One UI Device Care / Sleeping Apps",
                severityLabel = "Moderate background kill",
                instructions = listOf(
                    "Open App Info -> Battery and select Unrestricted.",
                    "In Device Care -> Battery -> Background usage limits, add PulseMesh to Never auto sleeping apps."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze,
                oemSettingsComponents = listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            )
            m.contains("oneplus") || m.contains("oppo") || m.contains("realme") -> OemBatteryBypassGuide(
                oemName = "OnePlus / Oppo / Realme",
                backgroundKillerName = "OxygenOS / ColorOS Athena App Freeze",
                severityLabel = "Aggressive background kill",
                instructions = listOf(
                    "Open App Info -> Battery usage and enable Allow background activity + Allow auto launch.",
                    "Disable Freeze when in background so BLE mesh and DTN relays stay live."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze,
                oemSettingsComponents = listOf(
                    "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            )
            m.contains("huawei") || m.contains("honor") -> OemBatteryBypassGuide(
                oemName = "Huawei / Honor",
                backgroundKillerName = "EMUI / MagicOS PowerGenie",
                severityLabel = "Very aggressive background kill",
                instructions = listOf(
                    "Open Settings -> Battery -> App launch -> find PulseMesh -> switch to Manage manually.",
                    "Enable Auto-launch, Secondary launch, and Run in background."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze,
                oemSettingsComponents = listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
            m.contains("vivo") || m.contains("iqoo") -> OemBatteryBypassGuide(
                oemName = "Vivo / iQOO",
                backgroundKillerName = "FuntouchOS / OriginOS iManager",
                severityLabel = "Aggressive background kill",
                instructions = listOf(
                    "Set Battery -> Background power consumption to High background power usage for PulseMesh.",
                    "Enable Autostart in iManager -> App manager."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze,
                oemSettingsComponents = listOf(
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            else -> OemBatteryBypassGuide(
                oemName = manufacturer.ifBlank { "Android" },
                backgroundKillerName = "Standard AOSP Doze",
                severityLabel = "Standard",
                instructions = listOf(
                    "Set PulseMesh battery usage to Unrestricted so background mesh relays continue while the screen is off."
                ),
                isIgnoringBatteryOptimizations = ignoringDoze
            )
        }
    }

    fun buildBatteryBypassIntents(
        packageName: String,
        guide: OemBatteryBypassGuide,
        isAlreadyExempt: Boolean
    ): List<Intent> {
        val candidates = mutableListOf<Intent>()

        // 1. When not yet exempt from Doze, request Android's 1-tap system exemption dialog first.
        if (!isAlreadyExempt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            candidates.add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        // 2. Try OEM-specific autostart / power-saver screens (direct launch avoids Android 11+ resolveActivity hiding).
        for ((pkg, cls) in guide.oemSettingsComponents) {
            candidates.add(
                Intent().apply {
                    component = ComponentName(pkg, cls)
                }
            )
        }

        // 3. Standard Android Battery Optimization list screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            candidates.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        // 4. Last-resort: PulseMesh App Info page (Battery -> Unrestricted).
        candidates.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
        return candidates
    }

    fun launchOemBatterySettings(context: Context, guide: OemBatteryBypassGuide): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isAlreadyExempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
            runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
                .getOrDefault(guide.isIgnoringBatteryOptimizations)
        } else {
            guide.isIgnoringBatteryOptimizations
        }

        val addNewTaskFlag = context !is android.app.Activity
        val candidates = buildBatteryBypassIntents(
            packageName = context.packageName,
            guide = guide,
            isAlreadyExempt = isAlreadyExempt
        )

        for (intent in candidates) {
            if (addNewTaskFlag) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }
}
