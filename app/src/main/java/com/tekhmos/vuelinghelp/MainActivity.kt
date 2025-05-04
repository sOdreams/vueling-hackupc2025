package com.tekhmos.vuelinghelp

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.tekhmos.vuelinghelp.ui.VisualUI1
import com.tekhmos.vuelinghelp.ui.VuelingColorScheme
import com.tekhmos.vuelinghelp.ui.mainScreen
import com.tekhmos.vuelinghelp.viewmodel.NearbyViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val serviceId = "com.tekhmos.vuelinghelp.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER
    private val userName: String by lazy { getUsername() }
    private val seenMessages = mutableSetOf<String>()
    private val viewModel: NearbyViewModel by viewModels()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val requiredPermissions = arrayOf( // solicitar en runtime
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
            checkLocationAndStartNearby()
        } else {
            Toast.makeText(this, "Activa 'Dispositivos Cercanos' y 'Ubicación' en ajustes.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var showSplashScreen by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(2000)
                showSplashScreen = false
            }

            if (showSplashScreen) {
                MaterialTheme(colorScheme = mainScreen) {
                    VisualUI1()
                }
            } else {
                val sharedPref = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val savedUsername = sharedPref.getString("username", null)
                if (hasAllPermissions()) {
                    checkLocationAndStartNearby()
                } else {
                    permissionLauncher.launch(requiredPermissions)
                }
                val showLoginScreen = remember { mutableStateOf(savedUsername.isNullOrBlank()) }

                MaterialTheme(colorScheme = darkColorScheme()) {
                    if (showLoginScreen.value) {
                        LoginScreen(onLogin = { username ->
                            saveUsername(username)
                            showLoginScreen.value = false
                        })
                    } else {
                        MaterialTheme(colorScheme = VuelingColorScheme) {
                            MainAppContent()
                        }
                    }
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getUsername(): String {
        val sharedPref = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return sharedPref.getString("username", android.os.Build.MODEL) ?: android.os.Build.MODEL
    }

    private fun saveUsername(username: String) {
        val sharedPref = getSharedPreferences("user_prefs", Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("username", username)
            apply()
        }
    }

    private fun checkLocationAndStartNearby() {
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
                Log.d("Nearby", "Anuncio iniciado")
            }
            .addOnFailureListener { e ->
                Log.e("Nearby", "Error al anunciar", e)
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
                Log.d("Nearby", "Descubrimiento iniciado")
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
                Log.d("Nearby", "Conectado a ${viewModel.devices.value[endpointId]?.name}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            viewModel.markConnected(endpointId, false)
            Log.d("Nearby", "Desconectado de $endpointId")
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
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return

            val json = JSONObject(raw)
            val timestamp = json.optLong("timestamp")
            val originDevice = json.optString("originDevice")
            val messageId = "$originDevice:$timestamp"

            if (originDevice == userName || seenMessages.contains(messageId)) return

            seenMessages.add(messageId)
            viewModel.addMessage(originDevice, json.toString())

            val relay = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
            viewModel.devices.value.forEach { (id, device) ->
                if (device.isConnected && id != endpointId) {
                    Nearby.getConnectionsClient(applicationContext).sendPayload(id, relay)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun sendMessage(content: String, infoLevel: String = "normal") {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("originDevice", userName)
            put("type", "message")
            put("infoLevel", infoLevel)
            put("message", content)
        }

        val payload = Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))
        viewModel.devices.value.forEach { (endpointId, device) ->
            if (device.isConnected) {
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
            }
        }

        viewModel.addMessage("me", json.toString())
    }

    @Composable
    fun LoginScreen(onLogin: (String) -> Unit) {
        var username by remember { mutableStateOf("") }
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Introduce tu nombre de usuario", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Nombre de usuario") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (username.isNotBlank()) {
                        onLogin(username)
                    } else {
                        Toast.makeText(context, "El nombre de usuario no puede estar vacío", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Acceder")
            }
        }
    }

    @Composable
    fun MainAppContent() {
        var criticalInfo by remember { mutableStateOf(" --- CRITICAL INFO ---") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            PermissionUI(
                requiredPermissions = requiredPermissions,
                onPermissionsChecked = {
                    checkLocationAndStartNearby()
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

            // Text area para información crítica
            Text(
                text = "Información Importante",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error // Color rojo para indicar importancia
            )
            Spacer(Modifier.height(4.dp))

            // Text area para información crítica
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), // Fondo rojo claro
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp), // Elevación para destacar
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error) // Borde rojo
            ) {
                Text(
                    text = criticalInfo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, // Color de texto sobre el fondo rojo claro
                    style = MaterialTheme.typography.bodyLarge // Texto un poco más grande
                )
            }
            DeviceList(viewModel = viewModel)
            Spacer(Modifier.height(16.dp))
            ChatUI(viewModel = viewModel, onSend = { msg -> sendMessage(msg) })
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
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 90.dp)
            ) {
                items(devices.values.toList()) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.isConnected)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(if (device.isConnected) "Conectado ✅" else "No conectado ❌", style = MaterialTheme.typography.bodySmall)
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
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.lastIndex)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text("Chat", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                state = listState
            ) {
                items(messages) { msg ->
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
}