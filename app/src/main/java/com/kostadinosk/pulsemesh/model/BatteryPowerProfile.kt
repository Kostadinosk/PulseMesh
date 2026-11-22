// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.model

enum class BatteryPowerProfile(
    val label: String,
    val description: String,
    val bleScanOnMs: Long,
    val bleScanOffMs: Long,
    val allowPersistentMulticastLock: Boolean,
    val wakeLockBurstMs: Long
) {
    EXPEDITION(
        label = "Expedition",
        description = "Maximum battery endurance: 4s/26s duty-cycled BLE, on-demand Wi-Fi multicast, no sustained WakeLock",
        bleScanOnMs = 4_000L,
        bleScanOffMs = 26_000L,
        allowPersistentMulticastLock = false,
        wakeLockBurstMs = 5_000L
    ),
    BALANCED(
        label = "Balanced",
        description = "Recommended default: 6s/12s duty-cycled BLE, Wi-Fi-only multicast, 15s burst WakeLocks",
        bleScanOnMs = 6_000L,
        bleScanOffMs = 12_000L,
        allowPersistentMulticastLock = false,
        wakeLockBurstMs = 15_000L
    ),
    TURBO(
        label = "Turbo",
        description = "Lowest latency continuous scan and active multicast lock while screen off or plugged in",
        bleScanOnMs = 10_000L,
        bleScanOffMs = 0L,
        allowPersistentMulticastLock = true,
        wakeLockBurstMs = 60_000L
    );

    companion object {
        fun fromName(raw: String?): BatteryPowerProfile =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: BALANCED
    }
}
