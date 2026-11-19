// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.example.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class RawBleDeviceRecord(
    val address: String,
    val name: String,
    val rssiDbm: Int,
    val packetsReceived: Int = 1,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val rawPayloadHex: String = ""
)

data class BleTxDiagnosticState(
    val isAdvertising: Boolean = false,
    val statusMessage: String = "IDLE — Tap 'Start Live TX Beacon' to test radio PHY",
    val negotiatedTxPowerDbm: Int = 0,
    val txPacketsCount: Int = 0,
    val txStatusCode: Int? = null,
    val isSupported: Boolean = true
)

class LiveBleRadioService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _txState = MutableStateFlow(BleTxDiagnosticState())
    val txState: StateFlow<BleTxDiagnosticState> = _txState.asStateFlow()

    private val _isRxSnifferActive = MutableStateFlow(false)
    val isRxSnifferActive: StateFlow<Boolean> = _isRxSnifferActive.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<RawBleDeviceRecord>>(emptyList())
    val scannedDevices: StateFlow<List<RawBleDeviceRecord>> = _scannedDevices.asStateFlow()

    private val _totalRxPacketsCount = MutableStateFlow(0)
    val totalRxPacketsCount: StateFlow<Int> = _totalRxPacketsCount.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var currentAdvertisingSet: AdvertisingSet? = null

    companion object {
        val PULSEMESH_SERVICE_UUID: UUID = UUID.fromString("0000F2DD-0000-1000-8000-00805F9B34FB")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            val txPower = settingsInEffect?.txPowerLevel ?: 20
            _txState.update {
                it.copy(
                    isAdvertising = true,
                    statusMessage = "Hardware TX Status: ACTIVE (0) | Negotiated Power: +$txPower dBm",
                    negotiatedTxPowerDbm = txPower,
                    txStatusCode = 0,
                    txPacketsCount = it.txPacketsCount + 1
                )
            }
        }

        override fun onStartFailure(errorCode: Int) {
            val errorDesc = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED (1)"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE (2)"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED (5)"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR (3)"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS (4)"
                else -> "ERROR_CODE_$errorCode"
            }
            _txState.update {
                it.copy(
                    isAdvertising = false,
                    statusMessage = "TX Error: $errorDesc — Check Bluetooth & Permissions",
                    txStatusCode = errorCode
                )
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            val device = result.device
            val address = device.address ?: "00:00:00:00:00:00"
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "Unknown BLE Device"
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: "Unknown BLE Device"
            }
            val rssi = result.rssi
            val rawBytes = result.scanRecord?.bytes ?: byteArrayOf()
            val hexString = rawBytes.take(12).joinToString("") { "%02X".format(it) }

            _totalRxPacketsCount.update { it + 1 }

            _scannedDevices.update { list ->
                val existing = list.find { it.address == address }
                if (existing != null) {
                    val updated = existing.copy(
                        name = if (name != "Unknown BLE Device") name else existing.name,
                        rssiDbm = rssi,
                        packetsReceived = existing.packetsReceived + 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        rawPayloadHex = hexString
                    )
                    listOf(updated) + list.filter { it.address != address }
                } else {
                    val record = RawBleDeviceRecord(
                        address = address,
                        name = name,
                        rssiDbm = rssi,
                        packetsReceived = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        rawPayloadHex = hexString
                    )
                    (listOf(record) + list).take(40)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isRxSnifferActive.value = false
            _txState.update {
                it.copy(statusMessage = "RX Scan Failed (Code $errorCode) — Check Permissions")
            }
        }
    }

    fun startLiveTx(localNodeId: String, callsign: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasAdv = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasAdv) {
                _txState.update {
                    it.copy(
                        isAdvertising = false,
                        statusMessage = "Permission Required: BLUETOOTH_ADVERTISE"
                    )
                }
                return
            }
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _txState.update {
                it.copy(
                    isAdvertising = false,
                    statusMessage = "Bluetooth Disabled — Enable Bluetooth in Android Settings",
                    isSupported = false
                )
            }
            return
        }

        try {
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                _txState.update {
                    it.copy(
                        isAdvertising = false,
                        statusMessage = "BLE Peripheral Mode Unsupported on this Host",
                        isSupported = false
                    )
                }
                return
            }

            val pUuid = ParcelUuid(PULSEMESH_SERVICE_UUID)
            val compactPayload = localNodeId.toByteArray(Charsets.UTF_8).take(6).toByteArray()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(pUuid)
                .addServiceData(pUuid, compactPayload)
                .build()

            _txState.update {
                it.copy(statusMessage = "Transmitting Live BLE Beacon via Silicon PHY...")
            }

            advertiser?.stopAdvertising(advertiseCallback)
            advertiser?.startAdvertising(settings, data, advertiseCallback)

        } catch (e: SecurityException) {
            _txState.update {
                it.copy(
                    isAdvertising = false,
                    statusMessage = "Permission Required: BLUETOOTH_ADVERTISE"
                )
            }
        } catch (e: Exception) {
            _txState.update {
                it.copy(
                    isAdvertising = false,
                    statusMessage = "TX Error: ${e.message}"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopLiveTx() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            currentAdvertisingSet = null
            _txState.update {
                it.copy(isAdvertising = false, statusMessage = "Hardware TX Stopped (Idle)")
            }
        } catch (_: Exception) {}
    }

    fun startRawRxSniffer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasScan = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasScan) {
                _isRxSnifferActive.value = false
                return
            }
        } else {
            val hasLoc = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasLoc) {
                _isRxSnifferActive.value = false
                return
            }
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        try {
            scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) return

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            // Run without UUID filters so user captures all real Bluetooth packets in the room!
            scanner?.startScan(null, scanSettings, scanCallback)
            _isRxSnifferActive.value = true
        } catch (e: SecurityException) {
            _isRxSnifferActive.value = false
        } catch (_: Exception) {
            _isRxSnifferActive.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopRawRxSniffer() {
        try {
            scanner?.stopScan(scanCallback)
            _isRxSnifferActive.value = false
        } catch (_: Exception) {}
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyList()
        _totalRxPacketsCount.value = 0
    }
}
