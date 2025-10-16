package com.example.dashboard_app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import android.annotation.SuppressLint

class BluetoothLeClient(private val context: Context, private val onCounterUpdate: (Int) -> Unit, private val onResetReceived: () -> Unit) {

    private val tag = "BluetoothLeClient"

    private val bluetoothAdapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var counterCharacteristic: BluetoothGattCharacteristic? = null
    private var resetCharacteristic: BluetoothGattCharacteristic? = null

    // UUIDs for our custom BLE service and characteristic (must match Client app)
    val serviceUuid: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    val counterCharacteristicUuid: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    val clientCharacteristicConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val resetCharacteristicUuid: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb")

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(tag, "Bluetooth is not enabled.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_SCAN permission not granted.")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "ACCESS_FINE_LOCATION permission not granted.")
                return
            }
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.e(tag, "Bluetooth LE Scanner not available.")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.d(tag, "BLE Scan started.")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_SCAN permission not granted. Cannot stop scan.")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "ACCESS_FINE_LOCATION permission not granted. Cannot stop scan.")
                return
            }
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        Log.d(tag, "BLE Scan stopped.")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                Log.d(tag, "Found BLE device: ${device.name} (${device.address})")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(tag, "BLE Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_CONNECT permission not granted.")
                return
            }
        }
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(tag, "Connecting to GATT server on device: ${device.address}")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Connected to GATT server.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(tag, "BLUETOOTH_CONNECT permission not granted.")
                        return
                    }
                }
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Disconnected from GATT server.")
                close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(serviceUuid)
                counterCharacteristic = service?.getCharacteristic(counterCharacteristicUuid)
                resetCharacteristic = service?.getCharacteristic(resetCharacteristicUuid)

                counterCharacteristic?.let { characteristic ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            Log.w(tag, "BLUETOOTH_CONNECT permission not granted.")
                            return
                        }
                    }
                    gatt?.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(clientCharacteristicConfigUuid)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                Log.w(tag, "BLUETOOTH_CONNECT permission not granted. Cannot write descriptor.")
                                return
                            }
                            @Suppress("DEPRECATION")
                            gatt?.writeDescriptor(descriptor)
                        }
                    }
                    Log.d(tag, "Services discovered and notifications enabled.")
                } ?: run {
                    Log.e(tag, "Counter characteristic not found.")
                }
            } else {
                Log.e(tag, "onServicesDiscovered received: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (characteristic.uuid == counterCharacteristicUuid) {
                val counter = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt()
                handler.post { onCounterUpdate(counter) }
                Log.d(tag, "Received counter update: $counter")
            } else if (characteristic.uuid == resetCharacteristicUuid) {
                handler.post { onResetReceived() }
                Log.d(tag, "Received reset signal.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendResetSignal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_CONNECT permission not granted. Cannot send reset signal.")
                return
            }
        }

        resetCharacteristic?.let { characteristic ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(characteristic, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.setValue(byteArrayOf(1)) // A simple byte array to signify a reset
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(tag, "BLUETOOTH_CONNECT permission not granted. Cannot write characteristic.")
                    return
                }
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
            Log.d(tag, "Sending reset signal to client.")
        } ?: run {
            Log.e(tag, "Reset characteristic not found. Cannot send reset signal.")
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "BLUETOOTH_CONNECT permission not granted. Cannot close BluetoothGatt.")
                return
            }
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
        Log.d(tag, "BluetoothGatt closed.")
    }
}