package com.tekhmos.vuelinghelp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.tekhmos.vuelinghelp.viewmodel.NearbyViewModel

class MainActivity : ComponentActivity() {


    private val serviceId = "com.tekhmos.vuelinghelp.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER
    private val userName = android.os.Build.MODEL


    private val viewModel: NearbyViewModel by viewModels()

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            ensureLocationAndStartNearby()
        } else {
            Toast.makeText(this, "Permisos necesarios no concedidos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    PermissionUI(
                        requiredPermissions = requiredPermissions,
                        onPermissionsChecked = {
                            ensureLocationAndStartNearby()
                        },
                        onRequestPermissions = {
                            permissionLauncher.launch(requiredPermissions)
                        },
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    DeviceList(viewModel)
                    Spacer(Modifier.height(16.dp))
                    ChatUI(viewModel = viewModel, onSend = { msg -> sendMessageToAll(msg) })
                }
            }
        }
    }

    private fun ensureLocationAndStartNearby() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!enabled) {
            Toast.makeText(this, "Activa la ubicación del dispositivo", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        startNearby()
    }

    private fun startNearby() {
        val client = Nearby.getConnectionsClient(this)
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()

        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(
                userName,
                serviceId,
                connectionLifecycleCallback,
                options
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Anunciando Nearby", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("Nearby", "Error al anunciar", e)
                Toast.makeText(this, "Error al anunciar: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(
                serviceId,
                endpointDiscoveryCallback,
                options
            )
            .addOnSuccessListener {
                Toast.makeText(this, "Descubriendo dispositivos", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("Nearby", "Error al descubrir", e)
                Toast.makeText(this, "Error al descubrir: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(applicationContext)
                .acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                viewModel.markConnected(endpointId, true)
                Toast.makeText(applicationContext, "Conectado con $endpointId", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onDisconnected(endpointId: String) {
            viewModel.markConnected(endpointId, false)
            Toast.makeText(applicationContext, "Desconectado de $endpointId", Toast.LENGTH_SHORT).show()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            viewModel.addOrUpdateDevice(endpointId, info.endpointName, false)
            Nearby.getConnectionsClient(applicationContext)
                .requestConnection(userName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            viewModel.removeDevice(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val msg = payload.asBytes()?.toString(Charsets.UTF_8)
            if (msg != null) {
                viewModel.addMessage(endpointId, msg)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessageToAll(message: String) {
        val payload = Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
        viewModel.devices.value.forEach { (endpointId, device) ->
            if (device.isConnected) {
                Nearby.getConnectionsClient(applicationContext)
                    .sendPayload(endpointId, payload)
            }
        }
        viewModel.addMessage("me", message)
    }
}
@Composable
fun PermissionUI(
    requiredPermissions: Array<String>,
    onPermissionsChecked: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val missingPermissions = remember {
        derivedStateOf {
            requiredPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        }
    }

    Column {
        if (missingPermissions.value.isEmpty()) {
            Text("Permisos OK ✅")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onPermissionsChecked) {
                Text("Iniciar Nearby")
            }
        } else {
            Text("Permisos faltantes ❌")
            Spacer(Modifier.height(8.dp))
            missingPermissions.value.forEach {
                Text(it)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermissions) {
                Text("Solicitar permisos")
            }
            Button(onClick = onOpenSettings) {
                Text("Abrir ajustes")
            }
        }
    }
}

@Composable
fun DeviceList(viewModel: NearbyViewModel) {
    val devices by viewModel.devices.collectAsState()

    Column {
        Text("Dispositivos cercanos:", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Text("Ninguno detectado aún...")
        } else {
            devices.values.forEach { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.isConnected)
                            MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name)
                        Text(if (device.isConnected) "Conectado ✅" else "No conectado ❌")
                    }
                }
            }
        }
    }
}
@Composable
fun ChatUI(
    viewModel: NearbyViewModel,
    onSend: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    var text by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Chat", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Column(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(8.dp)) {
            messages.forEach { msg ->
                val align = if (msg.from == "me") Arrangement.End else Arrangement.Start
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = align) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.from == "me")
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text(
                            msg.message,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Mensaje") },
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Button(onClick = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            }) {
                Text("Enviar")
            }
        }
    }
}


