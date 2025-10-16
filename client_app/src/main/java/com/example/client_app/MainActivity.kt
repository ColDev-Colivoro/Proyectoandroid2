package com.example.client_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client_app.ui.theme.ProyectoAndroid2Theme

class MainActivity : ComponentActivity() {

    private var bluetoothLeService: BluetoothLeService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothLeService.LocalBinder
            bluetoothLeService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothLeService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProyectoAndroid2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ClientScreen(
                        modifier = Modifier.padding(innerPadding),
                        onIncrement = {
                            bluetoothLeService?.currentCounterValue?.let {
                                bluetoothLeService?.currentCounterValue = it + 1
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BluetoothLeService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun ClientScreen(modifier: Modifier = Modifier, onIncrement: () -> Unit) {
    var counter by remember { mutableStateOf(0) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Contador: $counter", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
            counter++
            onIncrement()
        }) {
            Text("Incrementar Contador", fontSize = 24.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ClientScreenPreview() {
    ProyectoAndroid2Theme {
        ClientScreen(onIncrement = {})
    }
}
