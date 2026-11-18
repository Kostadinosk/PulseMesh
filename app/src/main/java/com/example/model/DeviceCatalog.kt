package com.example.model

enum class DeviceBrand(val displayName: String) {
    ONEPLUS("OnePlus"),
    SAMSUNG_SNAPDRAGON("Samsung Galaxy (Snapdragon)"),
    SAMSUNG_EXYNOS("Samsung Galaxy (Exynos)"),
    GOOGLE_PIXEL("Google Pixel"),
    XIAOMI_SNAPDRAGON("Xiaomi / Redmi / POCO (Snapdragon)"),
    XIAOMI_MEDIATEK("Xiaomi / Redmi / POCO (MediaTek)"),
    MOTOROLA_NOTHING("Motorola & Nothing"),
    VIVO_OPPO_REALME_GAMING("Vivo / Oppo / ASUS Gaming / Sony"),
    LEGACY_EMULATOR("Legacy / Universal Fallback")
}

enum class SocFamily(val familyName: String, val gpuKeyword: String) {
    QUALCOMM_SNAPDRAGON("Qualcomm Snapdragon", "Adreno"),
    GOOGLE_TENSOR("Google Tensor", "Mali"),
    SAMSUNG_EXYNOS("Samsung Exynos", "Xclipse"),
    MEDIATEK_DIMENSITY("MediaTek Dimensity", "Immortalis"),
    GENERIC_AOSP("Generic Android", "Unknown"),
    EMULATOR_DESKTOP("Emulator / Cloud Container", "SwiftShader")
}

data class PhoneModelProfile(
    val id: String,
    val brand: DeviceBrand,
    val modelName: String,
    val socName: String,
    val socFamily: SocFamily,
    val wifiBleDriver: String,
    val oemBackgroundKiller: String = "AOSP Standard Doze",
    val maxTxPowerDbm: Int = 20,
    val supportsBleCodedPhy: Boolean = true,
    val rootCommandProfile: String = "echo 1 > /dev/cpuset/foreground/boost/cgroup.procs",
    val wcnssConfigPath: String? = null,
    val regionalModelCodenames: List<String> = emptyList(),
    val marketRegions: String = "Global"
)

object DeviceCatalog {

    val allModels: List<PhoneModelProfile> = listOf(
        // =========================================================================
        // 1. ONEPLUS (Oryon & Snapdragon Flagships)
        // =========================================================================
        PhoneModelProfile(
            id = "oneplus_15",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 15",
            socName = "Snapdragon 8 Elite Gen 5 (Adreno 840 GPU)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / qca_cld3 (BLE 6.0 Coded PHY)",
            oemBackgroundKiller = "ColorOS / OxygenOS Hans + Oplus Freezer",
            maxTxPowerDbm = 25,
            rootCommandProfile = "iwpriv wlan0 set_tx_power 25; echo 1 > /dev/cpuset/foreground/boost/cgroup.procs",
            wcnssConfigPath = "/vendor/etc/wifi/WCNSS_qcom_cfg.ini",
            regionalModelCodenames = listOf("PLK110", "PJZ210", "CPH2750", "CPH2751", "OP15", "OnePlus15", "oneplus 15"),
            marketRegions = "China (PLK110, PJZ210), Global (CPH2750), North America (CPH2751)"
        ),
        PhoneModelProfile(
            id = "oneplus_13",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 13",
            socName = "Snapdragon 8 Elite (Adreno 830 GPU)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / qca_cld3 (BLE 5.4)",
            oemBackgroundKiller = "ColorOS / OxygenOS Hans + Oplus Freezer",
            maxTxPowerDbm = 25,
            rootCommandProfile = "iwpriv wlan0 set_tx_power 25",
            wcnssConfigPath = "/vendor/etc/wifi/WCNSS_qcom_cfg.ini",
            regionalModelCodenames = listOf("PJZ110", "CPH2649", "CPH2653", "CPH2655", "corot", "OnePlus13", "oneplus 13"),
            marketRegions = "China (PJZ110), India (CPH2649), Europe (CPH2653), North America (CPH2655)"
        ),
        PhoneModelProfile(
            id = "oneplus_13r",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 13R",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3",
            oemBackgroundKiller = "OxygenOS Hans",
            maxTxPowerDbm = 24,
            regionalModelCodenames = listOf("CPH2645", "CPH2647", "PKB110", "OnePlus13R"),
            marketRegions = "India (CPH2645), Global (CPH2647), China Ace 5 (PKB110)"
        ),
        PhoneModelProfile(
            id = "oneplus_12",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 12",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3 (BLE 5.4)",
            oemBackgroundKiller = "ColorOS Hans / OxygenOS Freezer",
            maxTxPowerDbm = 24,
            rootCommandProfile = "iwpriv wlan0 set_tx_power 24",
            wcnssConfigPath = "/vendor/etc/wifi/WCNSS_qcom_cfg.ini",
            regionalModelCodenames = listOf("PJE110", "CPH2573", "CPH2581", "CPH2583", "waffle", "OnePlus12", "oneplus 12"),
            marketRegions = "China (PJE110), India (CPH2573), Europe (CPH2581), North America (CPH2583)"
        ),
        PhoneModelProfile(
            id = "oneplus_12r",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 12R",
            socName = "Snapdragon 8 Gen 2 (SM8550-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3 (BLE 5.3)",
            oemBackgroundKiller = "OxygenOS Hans",
            maxTxPowerDbm = 23,
            regionalModelCodenames = listOf("CPH2585", "CPH2609", "CPH2611", "PJE120", "iron", "OnePlus12R"),
            marketRegions = "India (CPH2585), Global (CPH2609), North America (CPH2611), China Ace 3 (PJE120)"
        ),
        PhoneModelProfile(
            id = "oneplus_11",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 11",
            socName = "Snapdragon 8 Gen 2 (SM8550-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "ColorOS Hans",
            maxTxPowerDbm = 23,
            regionalModelCodenames = listOf("PHB110", "CPH2447", "CPH2449", "CPH2451", "salami", "OnePlus11", "oneplus 11"),
            marketRegions = "China (PHB110), India (CPH2447), Europe (CPH2449), North America (CPH2451)"
        ),
        PhoneModelProfile(
            id = "oneplus_nord_4",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus Nord 4",
            socName = "Snapdragon 7+ Gen 3 (SM7675)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900",
            oemBackgroundKiller = "OxygenOS Freezer",
            maxTxPowerDbm = 22,
            regionalModelCodenames = listOf("CPH2663", "CPH2661", "OnePlusNord4"),
            marketRegions = "India (CPH2663), Global (CPH2661)"
        ),
        PhoneModelProfile(
            id = "oneplus_open",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus Open",
            socName = "Snapdragon 8 Gen 2 (SM8550-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 (Adreno 740)",
            oemBackgroundKiller = "OxygenOS Multi-Window Hans",
            maxTxPowerDbm = 23,
            regionalModelCodenames = listOf("CPH2551", "audi", "OnePlusOpen"),
            marketRegions = "Global (CPH2551)"
        ),

        // =========================================================================
        // 2. SAMSUNG GALAXY (Snapdragon Variants)
        // =========================================================================
        PhoneModelProfile(
            id = "galaxy_s26_ultra",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S26 Ultra",
            socName = "Snapdragon 8 Elite Gen 2 for Galaxy (SM8850)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / qca_cld3",
            oemBackgroundKiller = "OneUI 8.0 Freecess / PhantomKiller",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "galaxy_s25_ultra",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S25 Ultra",
            socName = "Snapdragon 8 Elite for Galaxy (SM8750-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / Adreno 830",
            oemBackgroundKiller = "OneUI 7.0 Freecess + AppStandby",
            maxTxPowerDbm = 25,
            regionalModelCodenames = listOf("SM-S938B", "SM-S938U", "SM-S938U1", "SM-S938W", "SM-S938N", "SM-S9380", "e3q"),
            marketRegions = "Global (SM-S938B), USA (SM-S938U), Korea (SM-S938N), China (SM-S9380)"
        ),
        PhoneModelProfile(
            id = "galaxy_s25_plus_snap",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S25+ (Snapdragon)",
            socName = "Snapdragon 8 Elite for Galaxy (SM8750-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 24,
            regionalModelCodenames = listOf("SM-S936B", "SM-S936U", "SM-S936U1", "SM-S9360", "e2q"),
            marketRegions = "Global (SM-S936B), USA (SM-S936U), China (SM-S9360)"
        ),
        PhoneModelProfile(
            id = "galaxy_s25_snap",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S25 (Snapdragon)",
            socName = "Snapdragon 8 Elite for Galaxy (SM8750-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 23,
            regionalModelCodenames = listOf("SM-S931B", "SM-S931U", "SM-S931U1", "SM-S9310", "e1q"),
            marketRegions = "Global (SM-S931B), USA (SM-S931U), China (SM-S9310)"
        ),
        PhoneModelProfile(
            id = "galaxy_s24_ultra",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S24 Ultra",
            socName = "Snapdragon 8 Gen 3 for Galaxy (SM8650-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 (Adreno 750)",
            oemBackgroundKiller = "OneUI 6.1 Freecess",
            maxTxPowerDbm = 24,
            regionalModelCodenames = listOf("SM-S928B", "SM-S928U", "SM-S928U1", "SM-S928W", "SM-S928N", "SM-S9280", "eureka"),
            marketRegions = "Global (SM-S928B), USA (SM-S928U), Korea (SM-S928N)"
        ),
        PhoneModelProfile(
            id = "galaxy_s23_ultra",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S23 Ultra",
            socName = "Snapdragon 8 Gen 2 for Galaxy (SM8550-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "galaxy_s23",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S23",
            socName = "Snapdragon 8 Gen 2 for Galaxy",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "galaxy_z_fold_7",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy Z Fold 7",
            socName = "Snapdragon 8 Elite for Galaxy",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "galaxy_z_flip_7",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy Z Flip 7",
            socName = "Snapdragon 8 Elite for Galaxy",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "galaxy_z_fold_6",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy Z Fold 6",
            socName = "Snapdragon 8 Gen 3 for Galaxy",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 23
        ),

        // =========================================================================
        // 3. SAMSUNG GALAXY (Exynos & Mid-Range Regional Splits)
        // =========================================================================
        PhoneModelProfile(
            id = "galaxy_s25_exynos",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy S25 (Exynos 2500)",
            socName = "Exynos 2500 (S5E9955)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung SLSI / Broadcom Wi-Fi (Xclipse 950)",
            oemBackgroundKiller = "OneUI 7.0 Freecess",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "galaxy_s24_intl",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy S24 / S24+ Intl (Exynos)",
            socName = "Exynos 2400 (S5E9945)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung SLSI / Broadcom bcmdhd (Xclipse 940)",
            oemBackgroundKiller = "OneUI 6.1 Freecess",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "galaxy_s24_fe",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy S24 FE",
            socName = "Exynos 2400e",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung SLSI bcmdhd",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "galaxy_a56",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy A56 5G",
            socName = "Exynos 1580 (S5E8855)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung S.LSI (Xclipse 540 / BLE 5.3)",
            oemBackgroundKiller = "OneUI Freecess Light",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "galaxy_a55",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy A55 5G",
            socName = "Exynos 1480 (S5E8845)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung S.LSI (Xclipse 530 / AMD RDNA2)",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "galaxy_a54",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy A54 5G",
            socName = "Exynos 1380 (S5E8835)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung S.LSI / Mali-G68",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 19
        ),
        PhoneModelProfile(
            id = "galaxy_a35",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy A35 5G",
            socName = "Exynos 1380 (S5E8835)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung S.LSI Wi-Fi 6",
            oemBackgroundKiller = "OneUI Freecess",
            maxTxPowerDbm = 19
        ),

        // =========================================================================
        // 4. GOOGLE PIXEL (Tensor G5, G4, G3, G2)
        // =========================================================================
        PhoneModelProfile(
            id = "pixel_10_pro_xl",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 10 Pro XL",
            socName = "Google Tensor G5 (TSMC 3nm)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd BCM4390 / PowerVR DXT (BLE 5.4 Coded)",
            oemBackgroundKiller = "Android 16 Phantom Process Killer / AOSP Freecess",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "pixel_10_pro",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 10 Pro",
            socName = "Google Tensor G5 (TSMC 3nm)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd BCM4390",
            oemBackgroundKiller = "AOSP AppStandby",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "pixel_10",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 10",
            socName = "Google Tensor G5",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd BCM4390",
            oemBackgroundKiller = "AOSP AppStandby",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "pixel_9_pro_xl",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 9 Pro XL",
            regionalModelCodenames = listOf("komodo", "GEC77", "GWVK6", "G1CNE"),
            marketRegions = "Global (GEC77), USA (GWVK6), Japan (G1CNE)",
            socName = "Google Tensor G4 (GS401)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4398 / Exynos 5400 (BLE 5.3)",
            oemBackgroundKiller = "AOSP PhantomProcessKiller + Pixel Freecess",
            maxTxPowerDbm = 23,
            rootCommandProfile = "echo 0 > /sys/fs/cgroup/uid_0/cgroup.procs"
        ),
        PhoneModelProfile(
            id = "pixel_9_pro",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 9 Pro",
            regionalModelCodenames = listOf("caiman", "GGX8Q", "GE43N"),
            marketRegions = "Global (GGX8Q), USA (GE43N)",
            socName = "Google Tensor G4 (GS401)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4398 (Mali-G715 MP7)",
            oemBackgroundKiller = "AOSP PhantomProcessKiller",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "pixel_9",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 9",
            regionalModelCodenames = listOf("tokay", "GUR25", "G18GQ"),
            marketRegions = "Global (GUR25), USA (G18GQ)",
            socName = "Google Tensor G4",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4398",
            oemBackgroundKiller = "AOSP AppStandby",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "pixel_9a",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 9a",
            regionalModelCodenames = listOf("tegu", "GR83Y"),
            marketRegions = "Global / North America",
            socName = "Google Tensor G4",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4398",
            oemBackgroundKiller = "AOSP AppStandby",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "pixel_8_pro",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 8 Pro",
            regionalModelCodenames = listOf("husky", "GC3VE", "G1MNW"),
            marketRegions = "Global (GC3VE), USA (G1MNW)",
            socName = "Google Tensor G3 (ZUMA)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4389 (Immortalis-G715s MC10)",
            oemBackgroundKiller = "Pixel Standby",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "pixel_8",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 8",
            regionalModelCodenames = listOf("shiba", "GKWS6", "G9BQD"),
            marketRegions = "Global (GKWS6), USA (G9BQD)",
            socName = "Google Tensor G3 (ZUMA)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4389",
            oemBackgroundKiller = "Pixel Standby",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "pixel_8a",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 8a",
            socName = "Google Tensor G3",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd4389",
            oemBackgroundKiller = "Pixel Standby",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "pixel_7_pro",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 7 Pro",
            socName = "Google Tensor G2 (GS201)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd (Mali-G710 MP7)",
            oemBackgroundKiller = "Pixel Standby",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "pixel_7",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 7",
            socName = "Google Tensor G2",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom bcmdhd",
            oemBackgroundKiller = "Pixel Standby",
            maxTxPowerDbm = 20
        ),

        // =========================================================================
        // 5. XIAOMI / REDMI / POCO (Snapdragon Flagships)
        // =========================================================================
        PhoneModelProfile(
            id = "xiaomi_15_ultra",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 15 Ultra",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / Adreno 830",
            oemBackgroundKiller = "HyperOS 2.0 Millet + BatterySaver",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "xiaomi_15_pro",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 15 Pro",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "xiaomi_15",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 15",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "xiaomi_14_ultra",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 14 Ultra",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / Adreno 750",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "xiaomi_14",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 14",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "poco_f7_ultra",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "POCO F7 Ultra",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "poco_f7_pro",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "POCO F7 Pro",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "poco_f6_pro",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "POCO F6 Pro",
            socName = "Snapdragon 8 Gen 2 (SM8550-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "poco_f6",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "POCO F6",
            socName = "Snapdragon 8s Gen 3 (SM8635)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "redmi_note_14_pro_plus",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Redmi Note 14 Pro+ 5G",
            socName = "Snapdragon 7s Gen 3 (SM7635)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6700 / Adreno 810",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 21
        ),

        // =========================================================================
        // 6. XIAOMI / REDMI / POCO (MediaTek Dimensity Flagships)
        // =========================================================================
        PhoneModelProfile(
            id = "xiaomi_14t_pro",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "Xiaomi 14T Pro",
            socName = "MediaTek Dimensity 9300+ (MT6989)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek Filogic / wlan_gen4m (Immortalis-G720 MC12)",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 24,
            rootCommandProfile = "echo 1 > /proc/driver/wmt_dbg"
        ),
        PhoneModelProfile(
            id = "xiaomi_14t",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "Xiaomi 14T",
            socName = "MediaTek Dimensity 8300-Ultra (MT6897)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek Filogic / wlan_gen4m",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "poco_x7_pro",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "POCO X7 Pro 5G",
            socName = "MediaTek Dimensity 8400-Ultra (MT6899)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek wlan_gen4m (Mali-G720 MC7)",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "poco_x6_pro",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "POCO X6 Pro 5G",
            socName = "MediaTek Dimensity 8300-Ultra (MT6897)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek wlan_gen4m (Mali-G615 MC6)",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "redmi_note_13_pro_plus",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "Redmi Note 13 Pro+ 5G",
            socName = "MediaTek Dimensity 7200-Ultra (MT6886)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek wlan_gen4m",
            oemBackgroundKiller = "HyperOS Millet",
            maxTxPowerDbm = 21
        ),

        // =========================================================================
        // 7. MOTOROLA & NOTHING
        // =========================================================================
        PhoneModelProfile(
            id = "nothing_phone_3",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Nothing Phone (3)",
            socName = "Snapdragon 8s Gen 3 / 8 Elite (SM8750)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3",
            oemBackgroundKiller = "Nothing OS Light AOSP Killer",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "nothing_phone_2",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Nothing Phone (2)",
            socName = "Snapdragon 8+ Gen 1 (SM8475)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900",
            oemBackgroundKiller = "Nothing OS Standby",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "nothing_phone_2a_plus",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Nothing Phone (2a) Plus",
            socName = "MediaTek Dimensity 7350 Pro (MT6886)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek wlan_gen4m",
            oemBackgroundKiller = "Nothing OS Standby",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "moto_edge_50_ultra",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Motorola Edge 50 Ultra",
            socName = "Snapdragon 8s Gen 3 (SM8635)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "Moto MyUX Standby",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "moto_edge_50_pro",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Motorola Edge 50 Pro",
            socName = "Snapdragon 7 Gen 3 (SM7550)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6700",
            oemBackgroundKiller = "Moto MyUX",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "moto_razr_50_ultra",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Motorola Razr 50 Ultra",
            socName = "Snapdragon 8s Gen 3 (SM8635)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "Moto MyUX Clamshell Power",
            maxTxPowerDbm = 22
        ),

        // =========================================================================
        // 8. VIVO / OPPO / REALME / ASUS GAMING / SONY
        // =========================================================================
        PhoneModelProfile(
            id = "oppo_find_x8_pro",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Oppo Find X8 Pro",
            socName = "MediaTek Dimensity 9400 (MT6991)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek Filogic (Immortalis-G925 MC12 / wlan_gen4m)",
            oemBackgroundKiller = "ColorOS Hans + Oplus Freezer",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "oppo_find_x8_ultra",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Oppo Find X8 Ultra",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "ColorOS Hans",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "vivo_x200_pro",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Vivo X200 Pro",
            socName = "MediaTek Dimensity 9400 (MT6991)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek Filogic Wi-Fi 7 (Immortalis-G925)",
            oemBackgroundKiller = "OriginOS / FuntouchOS Freezer",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "vivo_x100_ultra",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Vivo X100 Ultra",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            oemBackgroundKiller = "OriginOS / FuntouchOS",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "realme_gt_7_pro",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Realme GT 7 Pro",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900",
            oemBackgroundKiller = "Realme UI Freezer",
            maxTxPowerDbm = 25
        ),
        PhoneModelProfile(
            id = "asus_rog_phone_9_pro",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "ASUS ROG Phone 9 Pro",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 (High-Q AirTrigger)",
            oemBackgroundKiller = "ROG Armoury Crate Ultra-Performance",
            maxTxPowerDbm = 26
        ),
        PhoneModelProfile(
            id = "redmagic_10_pro",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Nubia RedMagic 10 Pro",
            socName = "Snapdragon 8 Elite (SM8750-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7900 / ICE 13 Active Cooling",
            oemBackgroundKiller = "RedMagic OS Gaming Unthrottled",
            maxTxPowerDbm = 26
        ),
        PhoneModelProfile(
            id = "sony_xperia_1_vi",
            brand = DeviceBrand.VIVO_OPPO_REALME_GAMING,
            modelName = "Sony Xperia 1 VI",
            socName = "Snapdragon 8 Gen 3 (SM8650-AB)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 (Pure AOSP)",
            oemBackgroundKiller = "Sony Stamina Mode (Bypass Active)",
            maxTxPowerDbm = 24
        ),

        PhoneModelProfile(
            id = "oneplus_ace_5_pro",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus Ace 5 Pro",
            socName = "Snapdragon 8 Elite (SM8750)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3",
            maxTxPowerDbm = 24
        ),
        PhoneModelProfile(
            id = "oneplus_10_pro",
            brand = DeviceBrand.ONEPLUS,
            modelName = "OnePlus 10 Pro",
            socName = "Snapdragon 8 Gen 1 (SM8450)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900 / qca_cld3",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "galaxy_s22_ultra",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy S22 Ultra",
            socName = "Snapdragon 8 Gen 1 (SM8450)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900 / wlan_qcom",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "galaxy_s22_exynos",
            brand = DeviceBrand.SAMSUNG_EXYNOS,
            modelName = "Samsung Galaxy S22 (Exynos)",
            socName = "Exynos 2200 (S5E9925)",
            socFamily = SocFamily.SAMSUNG_EXYNOS,
            wifiBleDriver = "Samsung S.LSI scsc_wlan / bcmdhd",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "galaxy_z_flip_5",
            brand = DeviceBrand.SAMSUNG_SNAPDRAGON,
            modelName = "Samsung Galaxy Z Flip 5",
            socName = "Snapdragon 8 Gen 2 for Galaxy (SM8550-AC)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "pixel_8a",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 8a",
            socName = "Google Tensor G3 (Zuma)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom BCM4389 / bcmdhd (BLE 5.3)",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "pixel_7a",
            brand = DeviceBrand.GOOGLE_PIXEL,
            modelName = "Google Pixel 7a",
            socName = "Google Tensor G2 (GS201)",
            socFamily = SocFamily.GOOGLE_TENSOR,
            wifiBleDriver = "Broadcom BCM4389 / bcmdhd",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "xiaomi_13_ultra",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "Xiaomi 13 Ultra",
            socName = "Snapdragon 8 Gen 2 (SM8550)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800 / qca_cld3",
            maxTxPowerDbm = 23
        ),
        PhoneModelProfile(
            id = "poco_x6_pro",
            brand = DeviceBrand.XIAOMI_MEDIATEK,
            modelName = "POCO X6 Pro 5G",
            socName = "MediaTek Dimensity 8300-Ultra (MT6897)",
            socFamily = SocFamily.MEDIATEK_DIMENSITY,
            wifiBleDriver = "MediaTek MT6631 / wlan_gen4m",
            maxTxPowerDbm = 21
        ),
        PhoneModelProfile(
            id = "poco_f5_pro",
            brand = DeviceBrand.XIAOMI_SNAPDRAGON,
            modelName = "POCO F5 Pro",
            socName = "Snapdragon 8+ Gen 1 (SM8475)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6900 / qca_cld3",
            maxTxPowerDbm = 22
        ),
        PhoneModelProfile(
            id = "nothing_phone_1",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Nothing Phone (1)",
            socName = "Snapdragon 778G+ (SM7325-AE)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 6700",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "motorola_edge_40_pro",
            brand = DeviceBrand.MOTOROLA_NOTHING,
            modelName = "Motorola Edge 40 Pro",
            socName = "Snapdragon 8 Gen 2 (SM8550)",
            socFamily = SocFamily.QUALCOMM_SNAPDRAGON,
            wifiBleDriver = "Qualcomm FastConnect 7800",
            maxTxPowerDbm = 22
        ),

        // =========================================================================
        // 9. LEGACY / UNIVERSAL / EMULATOR FALLBACK
        // =========================================================================
        PhoneModelProfile(
            id = "desktop_emulator_container",
            brand = DeviceBrand.LEGACY_EMULATOR,
            modelName = "Android Cloud Emulator / Desktop Container",
            socName = "SwiftShader / QEMU Goldfish VirtIO",
            socFamily = SocFamily.EMULATOR_DESKTOP,
            wifiBleDriver = "VirtIO Wi-Fi / Soft-MAC BLE Simulator",
            oemBackgroundKiller = "AOSP Standard Process Scheduler",
            maxTxPowerDbm = 20
        ),
        PhoneModelProfile(
            id = "generic_android_legacy",
            brand = DeviceBrand.LEGACY_EMULATOR,
            modelName = "Generic BLE 4.2 / 5.0 Legacy Device",
            socName = "Standard ARM64 SoC",
            socFamily = SocFamily.GENERIC_AOSP,
            wifiBleDriver = "Standard AOSP Bluetooth HCI / cfg80211",
            oemBackgroundKiller = "AOSP Standard Doze",
            maxTxPowerDbm = 18
        )
    )

    fun getProfileById(id: String): PhoneModelProfile {
        return allModels.find { it.id == id } ?: allModels.first()
    }

    /**
     * Automatic hardware profile matching against detected Build.MODEL, Build.DEVICE, Build.BOARD and regional codenames
     */
    fun findBestMatch(
        model: String,
        socModel: String,
        gpu: String,
        isEmulator: Boolean,
        deviceName: String = "",
        boardName: String = ""
    ): PhoneModelProfile {
        if (isEmulator) return getProfileById("desktop_emulator_container")

        val query = "$model $socModel $gpu $deviceName $boardName".lowercase()

        // 1. Precise regional model number and codename matching (e.g. PJZ110, CPH2649, SM-S938B, tokay)
        allModels.forEach { profile ->
            for (code in profile.regionalModelCodenames) {
                if (code.isNotBlank() && (model.equals(code, ignoreCase = true) ||
                            deviceName.equals(code, ignoreCase = true) ||
                            boardName.equals(code, ignoreCase = true) ||
                            query.contains(code.lowercase()))) {
                    return profile
                }
            }
        }

        // 2. Precise model name searches
        allModels.forEach { profile ->
            val pName = profile.modelName.lowercase()
            if (query.contains(pName) || pName.contains(model.lowercase())) {
                return profile
            }
        }

        // 3. SoC chipset keyword fallback
        return when {
            query.contains("8 elite") || query.contains("sm8750") || query.contains("sm8850") -> getProfileById("oneplus_15")
            query.contains("sm8650") || query.contains("8 gen 3") -> getProfileById("oneplus_12")
            query.contains("sm8550") || query.contains("8 gen 2") -> getProfileById("oneplus_11")
            query.contains("tensor g5") -> getProfileById("pixel_10_pro_xl")
            query.contains("tensor g4") || query.contains("gs401") -> getProfileById("pixel_9_pro_xl")
            query.contains("tensor g3") || query.contains("zuma") -> getProfileById("pixel_8_pro")
            query.contains("dimensity 9400") || query.contains("mt6991") -> getProfileById("oppo_find_x8_pro")
            query.contains("dimensity 9300") || query.contains("mt6989") -> getProfileById("xiaomi_14t_pro")
            query.contains("exynos 2400") || query.contains("xclipse 940") -> getProfileById("galaxy_s24_intl")
            query.contains("adreno") -> getProfileById("oneplus_13")
            query.contains("mali") || query.contains("immortalis") -> getProfileById("xiaomi_14t_pro")
            else -> getProfileById("generic_android_legacy")
        }
    }
}
