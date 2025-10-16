package com.example.client_app

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BluetoothLeService : Service() {

    private val TAG = "BluetoothLeService"

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
    val SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb") // Heart Rate Service for example
    val COUNTER_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb") // Heart Rate Measurement Characteristic for example
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val RESET_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A39-0000-1000-8000-00805f9b34fb") // Example for Heart Rate Control Point

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

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled.")
            // In a real app, you\'d prompt the user to enable Bluetooth
            return
        }

        setupGattServer()
        startAdvertising()
    }

    private fun setupGattServer() {
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = android.bluetooth.BluetoothGattService(SERVICE_UUID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY)

        counterCharacteristic = BluetoothGattCharacteristic(
            COUNTER_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val configDescriptor = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        counterCharacteristic?.addDescriptor(configDescriptor)
        service.addCharacteristic(counterCharacteristic)

        // Add Reset Characteristic
        resetCharacteristic = BluetoothGattCharacteristic(
            RESET_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(resetCharacteristic)

        bluetoothGattServer?.addService(service)
        Log.d(TAG, "GATT server setup complete.")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: ${device?.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: ${device?.address}")
                device?.let {
                    registeredDevices.remove(it)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic?.uuid == COUNTER_CHARACTERISTIC_UUID) {
                val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(currentCounterValue).array()
                bluetoothGattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, value)
                Log.d(TAG, "Read request for counter: $currentCounterValue")
            }
        }

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
            if (characteristic?.uuid == RESET_CHARACTERISTIC_UUID) {
                if (value != null && value.isNotEmpty()) {
                    currentCounterValue = 0
                    Log.d(TAG, "Counter reset by device: ${device?.address}. New value: $currentCounterValue")
                    updateCharacteristicValue(currentCounterValue) // Notify connected clients of the reset
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor?.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (value != null && device != null) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        registeredDevices[device] = true
                        Log.d(TAG, "Enable notifications for device: ${device.address}")
                    } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        registeredDevices.remove(device)
                        Log.d(TAG, "Disable notifications for device: ${device.address}")
                    }
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }
    }

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "Bluetooth LE Advertiser not available.")
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
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertisingCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                Log.d(TAG, "BLE Advertising started successfully.")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.e(TAG, "BLE Advertising onStartFailure: $errorCode")
            }
        }

        advertiser.startAdvertising(settings, data, advertisingCallback)
    }

    private fun stopAdvertising() {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        advertisingCallback?.let {
            advertiser?.stopAdvertising(it)
            advertisingCallback = null
            Log.d(TAG, "BLE Advertising stopped.")
        }
    }

    @Suppress("DEPRECATION")
    private fun updateCharacteristicValue(value: Int) {
        counterCharacteristic?.let { characteristic ->
            characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT32, 0)
            for (device in registeredDevices.keys) {
                bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
            }
            Log.d(TAG, "Characteristic value updated to: $value")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
        bluetoothGattServer?.close()
        bluetoothGattServer = null
        Log.d(TAG, "BluetoothLeService destroyed.")
    }
}
