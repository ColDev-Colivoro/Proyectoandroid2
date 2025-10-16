package com.example.client_app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresPermission
import android.annotation.SuppressLint

class BluetoothLeService : Service() {

    private val tag = "BluetoothLeService"

    private val binder = LocalBinder()

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertisingCallback: AdvertiseCallback? = null

    private var counterCharacteristic: BluetoothGattCharacteristic? = null
    private var resetCharacteristic: BluetoothGattCharacteristic? = null

    // Store devices that have enabled notifications for the counter characteristic
    private val registeredDevices = ConcurrentHashMap<BluetoothDevice, Boolean>()

    // UUIDs for our custom BLE service and characteristic
    val serviceUuid: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb") // Heart Rate Service for example
    val counterCharacteristicUuid: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb") // Heart Rate Measurement Characteristic for example
    val clientCharacteristicConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val resetCharacteristicUuid: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb") // Example for Heart Rate Control Point

    var currentCounterValue: Int = 0
        set(value) {
            field = value
            updateCharacteristicValue(value)
        }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()

        // Check for BLUETOOTH_CONNECT permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "BLUETOOTH_CONNECT permission not granted.")
            // In a real app, you\'d request the permission from the user
            return
        }

        // Check for BLUETOOTH_SCAN permission (required for advertising on Android 12+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "BLUETOOTH_SCAN permission not granted.")
            // In a real app, you\'d request the permission from the user
            return
        }

        // Check for BLUETOOTH_ADVERTISE permission (required for advertising on Android 12+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "BLUETOOTH_ADVERTISE permission not granted.")
            // In a real app, you\'d request the permission from the user
            return
        }

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.e(tag, "Bluetooth is not enabled.")
            // In a real app, you\'d prompt the user to enable Bluetooth
            return
        }

        setupGattServer()
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        try {
            bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            counterCharacteristic = BluetoothGattCharacteristic(
                counterCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            val configDescriptor = BluetoothGattDescriptor(
                clientCharacteristicConfigUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            counterCharacteristic?.addDescriptor(configDescriptor)
            service.addCharacteristic(counterCharacteristic)

            resetCharacteristic = BluetoothGattCharacteristic(
                resetCharacteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(resetCharacteristic)

            bluetoothGattServer?.addService(service)
            Log.d(tag, "GATT server setup complete.")
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during GATT server setup: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Device connected: ${device?.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Device disconnected: ${device?.address}")
                device?.let {
                    registeredDevices.remove(it)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic?.uuid == counterCharacteristicUuid) {
                val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(currentCounterValue).array()
                try {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: SecurityException) {
                    Log.e(tag, "SecurityException sending response for characteristic read: ${e.message}")
                }
                Log.d(tag, "Read request for counter: $currentCounterValue")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic?.uuid == resetCharacteristicUuid) {
                if (value != null && value.isNotEmpty()) {
                    currentCounterValue = 0
                    Log.d(tag, "Counter reset by device: ${device?.address}. New value: $currentCounterValue")
                    updateCharacteristicValue(currentCounterValue) // Notify connected clients of the reset
                }
                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) {
                        Log.e(tag, "SecurityException sending response for characteristic write: ${e.message}")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor?.uuid == clientCharacteristicConfigUuid) {
                if (value != null && device != null) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        registeredDevices[device] = true
                        Log.d(tag, "Enable notifications for device: ${device.address}")
                    } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        registeredDevices.remove(device)
                        Log.d(tag, "Disable notifications for device: ${device.address}")
                    }
                }
                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) {
                        Log.e(tag, "SecurityException sending response for descriptor write: ${e.message}")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        // Check for BLUETOOTH_ADVERTISE permission (required for advertising on Android 12+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "BLUETOOTH_ADVERTISE permission not granted, cannot start advertising.")
            return
        }
        try {
            val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(tag, "Bluetooth LE Advertiser not available.")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(serviceUuid))
                .build()

            advertisingCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    super.onStartSuccess(settingsInEffect)
                    Log.d(tag, "BLE Advertising started successfully.")
                }

                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)
                    Log.e(tag, "BLE Advertising onStartFailure: $errorCode")
                }
            }
            advertiser.startAdvertising(settings, data, advertisingCallback)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during advertising start: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        // Check for BLUETOOTH_ADVERTISE permission (required for advertising on Android 12+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "BLUETOOTH_ADVERTISE permission not granted, cannot stop advertising.")
            return
        }
        try {
            val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(tag, "Bluetooth LE Advertiser not available for stopping.")
                return
            }
            advertisingCallback?.let {
                advertiser.stopAdvertising(it)
                advertisingCallback = null
                Log.d(tag, "BLE Advertising stopped.")
            }
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during advertising stop: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun updateCharacteristicValue(value: Int) {
        counterCharacteristic?.let { characteristic ->
            characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT32, 0)
            for (device in registeredDevices.keys) {
                try {
                    bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
                }
                catch (e: SecurityException) {
                    Log.e(tag, "SecurityException notifying characteristic changed: ${e.message}")
                }
            }

            Log.d(tag, "Characteristic value updated to: $value")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        stopAdvertising()
        try {
            bluetoothGattServer?.close()
        }
        catch (e: SecurityException) {
            Log.e(tag, "SecurityException closing GATT server: ${e.message}")
        }
        bluetoothGattServer = null
        Log.d(tag, "BluetoothLeService destroyed.")
    }
}