package com.example.dashboard_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.dashboard_app.ui.theme.ProyectoAndroid2Theme
import android.Manifest

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothLeClient: BluetoothLeClient
    private var currentCounter by mutableStateOf(0)

    private val REQUEST_BLUETOOTH_PERMISSIONS = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothLeClient = BluetoothLeClient(this,
            onCounterUpdate = { counterValue ->
                currentCounter = counterValue
            },
            onResetReceived = {
                // Handle reset signal if implemented
            }
        )

        requestPermissions()

        setContent {
            ProyectoAndroid2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        counter = currentCounter,
                        onResetClient = { bluetoothLeClient.sendResetSignal() }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
        } else {
            bluetoothLeClient.startScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bluetoothLeClient.startScan()
            } else {
                // Handle permission denial
                Log.e("MainActivity", "Bluetooth permissions denied.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothLeClient.close()
    }
}

@Composable
fun DashboardScreen(modifier: Modifier = Modifier, counter: Int, onResetClient: () -> Unit) {
    val limit = 10
    val backgroundColor = if (counter >= limit) Color.Red else Color.Green

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Contador Recibido: $counter", fontSize = 48.sp, color = Color.White)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onResetClient) {
            Text("Reiniciar Cliente", fontSize = 24.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    ProyectoAndroid2Theme {
        DashboardScreen(counter = 5, onResetClient = {})
    }
}
