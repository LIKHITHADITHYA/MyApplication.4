package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DonutSmall
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.VehicleData
import com.example.myapplication.services.NavigationService
import com.example.myapplication.ui.settings.SettingsActivity
import com.example.myapplication.util.AppPreferences
import com.example.myapplication.util.CollisionAlert
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@Immutable
data class StableLatLng(val latLng: LatLng) {
    val latitude: Double get() = latLng.latitude
    val longitude: Double get() = latLng.longitude

    constructor(latitude: Double, longitude: Double) : this(LatLng(latitude, longitude))
}

data class GnssStatusInfo(val statusText: String, val icon: ImageVector, val color: Color)
data class ConnectivityStatusInfo(val statusText: String, val icon: ImageVector, val color: Color)

@Immutable
data class PeerDeviceDisplay(
    val id: String,
    val position: StableLatLng,
    val icon: ImageVector = Icons.Filled.DirectionsCar
)

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _currentSpeedKmh = MutableStateFlow(0.0f)
    val currentSpeedKmh: StateFlow<Float> = _currentSpeedKmh.asStateFlow()
    private val _gnssStatus = MutableStateFlow(
        GnssStatusInfo(
            "Initializing...",
            Icons.Filled.LocationSearching,
            Color.Gray
        )
    )
    val gnssStatus: StateFlow<GnssStatusInfo> = _gnssStatus.asStateFlow()
    private val _connectivityStatus = MutableStateFlow(
        ConnectivityStatusInfo(
            "Initializing...",
            Icons.Filled.WifiOff,
            Color.Gray
        )
    )
    val connectivityStatus: StateFlow<ConnectivityStatusInfo> = _connectivityStatus.asStateFlow()
    private val _userLocation = MutableStateFlow(StableLatLng(0.0, 0.0))
    val userLocation: StateFlow<StableLatLng> = _userLocation.asStateFlow()
    private val _userHeading = MutableStateFlow(0.0f)
    val userHeading: StateFlow<Float> = _userHeading.asStateFlow()
    private val _peerDevices = MutableStateFlow<List<PeerDeviceDisplay>>(emptyList())
    val peerDevices: StateFlow<List<PeerDeviceDisplay>> = _peerDevices.asStateFlow()

    private val _collisionAlert = MutableStateFlow<CollisionAlert?>(null)
    val collisionAlert: StateFlow<CollisionAlert?> = _collisionAlert.asStateFlow()

    private val _navigateToSettings = MutableSharedFlow<Unit>()
    val navigateToSettings: SharedFlow<Unit> = _navigateToSettings.asSharedFlow()

    private val _showSosDialog = MutableStateFlow(false)
    val showSosDialog: StateFlow<Boolean> = _showSosDialog.asStateFlow()

    var navigationService: NavigationService? = null
    private var isServiceBound = MutableStateFlow(false)

    val deviceId: StateFlow<String?> = MutableStateFlow(null)

    private val serviceConnection = object : ServiceConnection {
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NavigationService.LocalBinder
            navigationService = binder?.getService()
            isServiceBound.value = true
            // Corrected: Safely convert to String to prevent ClassCastException
            (deviceId as MutableStateFlow).value = navigationService?.deviceId?.toString()
            Log.d(
                "MainViewModel",
                "NavigationService connected, deviceId: ${navigationService?.deviceId?.toString()}"
            )
            navigationService?.let { startCollectingDataFromService(it) }
        }


        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            isServiceBound.value = false
            (deviceId as MutableStateFlow).value = null
            Log.d("MainViewModel", "NavigationService disconnected")
            _gnssStatus.value =
                GnssStatusInfo("Service Disconnected", Icons.Filled.Error, Color.Red)
            _connectivityStatus.value =
                ConnectivityStatusInfo("Service Disconnected", Icons.Filled.Error, Color.Red)
        }
    }

    init {
        // Service binding will be initiated by Activity after permission checks
    }

    fun bindToNavigationService() {
        if (isServiceBound.value || navigationService != null) return
        Log.d("MainViewModel", "Attempting to bind to NavigationService.")
        Intent(getApplication(), NavigationService::class.java).also { intent ->
            getApplication<Application>().bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun startNavigationServiceViaIntent() {
        Log.d("MainViewModel", "Attempting to start NavigationService via intent.")
        Intent(getApplication(), NavigationService::class.java).also { intent ->
            ContextCompat.startForegroundService(getApplication(), intent)
        }
    }

    private fun startCollectingDataFromService(service: NavigationService) {
        viewModelScope.launch {
            service.currentSpeedFlow.collect { _currentSpeedKmh.value = it * 3.6f }
        }
        viewModelScope.launch {
            service.userLocationFlow.collect { latLng ->
                latLng?.let {
                    _userLocation.value = StableLatLng(it)
                }
            }
        }
        viewModelScope.launch { service.userHeadingFlow.collect { _userHeading.value = it } }
        viewModelScope.launch {
            service.gnssStatusTextFlow.collect {
                _gnssStatus.value = when {
                    it.contains("Strong", true) -> GnssStatusInfo(
                        it,
                        Icons.Filled.LocationOn,
                        Color.Green
                    )

                    it.contains("Moderate", true) -> GnssStatusInfo(
                        it,
                        Icons.Filled.LocationOn,
                        Color(0xFFFFA500)
                    )

                    it.contains("Weak", true) -> GnssStatusInfo(
                        it,
                        Icons.Filled.Warning,
                        Color.Yellow
                    )

                    it.contains("Searching", true) || it.contains(
                        "Awaiting Fix",
                        true
                    ) -> GnssStatusInfo(it, Icons.Filled.LocationSearching, Color.Blue)

                    it.contains("Permission Denied", true) -> GnssStatusInfo(
                        it,
                        Icons.Filled.LocationOff,
                        Color.Red
                    )

                    it.contains("Error", true) -> GnssStatusInfo(it, Icons.Filled.Error, Color.Red)
                    it.contains("Stopped", true) || it.contains("Off", true) -> GnssStatusInfo(
                        it,
                        Icons.Filled.LocationOff,
                        Color.Gray
                    )

                    else -> GnssStatusInfo(it, Icons.Filled.LocationSearching, Color.Gray)
                }
            }
        }
        viewModelScope.launch {
            service.connectivityStatusTextFlow.collect {
                val peerCount = it.substringAfter("Peers: ", "").toIntOrNull() ?: it.substringAfter(
                    "(Peers: ",
                    ""
                ).substringBefore(")", "").toIntOrNull() ?: 0
                _connectivityStatus.value = when {
                    it.contains("GO", true) || it.contains("Client", true) || it.contains(
                        "Peers:",
                        true
                    ) ->
                        ConnectivityStatusInfo(
                            it,
                            if (peerCount > 0) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            if (peerCount > 0) Color.Green else Color(0xFFFFA500)
                        )

                    it.contains("Discovering", true) -> ConnectivityStatusInfo(
                        it,
                        Icons.Filled.DonutSmall,
                        Color.Blue
                    )

                    it.contains("Not Available", true) || it.contains("Error", true) || it.contains(
                        "Failed",
                        true
                    ) ->
                        ConnectivityStatusInfo(it, Icons.Filled.Error, Color.Red)

                    it.contains("Off", true) || it.contains(
                        "Stopped",
                        true
                    ) -> ConnectivityStatusInfo(it, Icons.Filled.WifiOff, Color.Gray)

                    else -> ConnectivityStatusInfo(it, Icons.Filled.DonutSmall, Color.Gray)
                }
            }
        }
        viewModelScope.launch {
            service.nearbyVehiclesFlow.collect {
                _peerDevices.value = it.map { v ->
                    PeerDeviceDisplay(
                        v.deviceId,
                        StableLatLng(v.latitude, v.longitude)
                    )
                }
            }
        }
        viewModelScope.launch {
            service.collisionAlertFlow.collect { alert ->
                _collisionAlert.value = alert
            }
        }
        Log.d("MainViewModel", "Started collecting data from NavigationService flows.")
    }

    fun onSettingsClicked() {
        viewModelScope.launch { _navigateToSettings.emit(Unit) }
    }

    fun onWarningClicked() { /* TODO */
    }

    fun onSosClicked() {
        _showSosDialog.value = true
    }

    fun onSosDialogDismiss() {
        _showSosDialog.value = false
    }

    fun onSosDialogConfirm() {
        _showSosDialog.value = false
        Log.d("MainViewModel", "SOS action confirmed by user.")

        val currentLoc = _userLocation.value
        val currentSpeed = _currentSpeedKmh.value / 3.6f // Convert km/h back to m/s
        val currentHeading = _userHeading.value
        val timestamp = System.currentTimeMillis()
        val currentDeviceId = deviceId.value ?: "unknownDevice-${UUID.randomUUID()}"

        // V2V SOS Broadcast
        if (currentLoc.latitude == 0.0 && currentLoc.longitude == 0.0) {
            Log.w("MainViewModel", "SOS: Current location unknown, cannot send full V2V SOS data.")
            val sosDataMinimal = VehicleData(
                deviceId = currentDeviceId,
                latitude = 0.0,
                longitude = 0.0,
                speed = 0f,
                bearing = 0f,
                timestamp = timestamp,
                isSOS = true,
                sosMessage = "Emergency SOS - Location Unavailable",
                sosId = UUID.randomUUID().toString()
            )
            navigationService?.sendSosBroadcast(sosDataMinimal)
            Log.i("MainViewModel", "SOS V2V broadcast initiated (location unknown).")
        } else {
            val sosData = VehicleData(
                deviceId = currentDeviceId,
                latitude = currentLoc.latitude,
                longitude = currentLoc.longitude,
                speed = currentSpeed,
                bearing = currentHeading,
                timestamp = timestamp,
                isSOS = true,
                sosMessage = "Emergency SOS",
                sosId = UUID.randomUUID().toString()
            )
            navigationService?.sendSosBroadcast(sosData)
            Log.i("MainViewModel", "SOS V2V broadcast initiated with location.")
        }

        // SMS Sending
        val sosPhoneNumber = AppPreferences.getEmergencyContactPhone(getApplication())

        if (sosPhoneNumber.isBlank()) {
            Log.w(
                "MainViewModel",
                "SOS SMS not sent: Emergency contact number is not set in preferences."
            )
            // Optionally, you could emit a SharedFlow event to MainActivity to show a Toast.
            // For example: viewModelScope.launch { _toastMessage.emit("SOS contact not set. Please configure in Settings.") }
            return // Do not proceed if phone number is blank
        }

        val message = if (currentLoc.latitude != 0.0 || currentLoc.longitude != 0.0) {
            "SOS! My current location: https://www.google.com/maps?q=${currentLoc.latitude},${currentLoc.longitude}"
        } else {
            "SOS! My location is currently unavailable. Emergency event triggered."
        }

        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$sosPhoneNumber")
                putExtra("sms_body", message)
            }
            getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i("MainViewModel", "SOS SMS intent launched for number: $sosPhoneNumber")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Could not launch SOS SMS intent: ${e.message}", e)
            // Optionally, emit a SharedFlow event for a Toast: e.g., viewModelScope.launch { _toastMessage.emit("Could not open SMS app.") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.e("MainViewModel", "Service not registered: ${e.message}")
            }
            isServiceBound.value = false
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var permissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
    private var showPermissionRationaleDialog by mutableStateOf(false)
    private var rationalePermissionsList = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                permissionLauncher =
                    rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) {
                        handlePermissionsResult(it)
                    }
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    mainViewModel.navigateToSettings.collect {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java)
                        )
                    }
                }
                val showSosDialogState by mainViewModel.showSosDialog.collectAsState()
                if (showSosDialogState) {
                    SosConfirmationDialog(
                        { mainViewModel.onSosDialogConfirm() },
                        { mainViewModel.onSosDialogDismiss() })
                }
                LaunchedEffect(Unit) { checkAndRequestInitialPermissions() }
                if (showPermissionRationaleDialog) {
                    PermissionRationaleDialog(
                        rationalePermissionsList,
                        {
                            showPermissionRationaleDialog = false; permissionLauncher.launch(
                            rationalePermissionsList.toTypedArray()
                        )
                        },
                        { showPermissionRationaleDialog = false })
                }
                V2VSentinelApp(mainViewModel)
            }
        }
    }

    private fun getRequiredPermissions(): List<String> {
        return mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_WIFI_STATE)
                add(Manifest.permission.CHANGE_WIFI_STATE)
            }
        }.distinct()
    }

    private fun checkAndRequestInitialPermissions() {
        val requiredPermissions = getRequiredPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isEmpty()) {
            startServiceAndBind()
        } else {
            val permissionsToShowRationale =
                permissionsToRequest.filter { shouldShowRequestPermissionRationale(it) }
            if (permissionsToShowRationale.isNotEmpty()) {
                rationalePermissionsList = permissionsToShowRationale
                showPermissionRationaleDialog = true
            } else {
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    private fun handlePermissionsResult(permissionsResult: Map<String, Boolean>) {
        val allGranted = getRequiredPermissions().all {
            permissionsResult[it] == true || ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startServiceAndBind() else Log.w(
            "MainActivity",
            "Not all permissions granted."
        )
    }

    private fun startServiceAndBind() {
        mainViewModel.startNavigationServiceViaIntent()
        mainViewModel.bindToNavigationService()
    }
}

@Composable
fun SosConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm SOS") },
        text = { Text("Are you sure you want to send an SOS message with your current location? This will use SMS and broadcast to nearby vehicles.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Confirm SOS")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PermissionRationaleDialog(
    permissions: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val permissionText = remember(permissions) {
        permissions.joinToString(separator = "\n- ", prefix = "- ") {
            when (it) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "Precise Location (for navigation and P2P)"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications (to keep service running and provide updates)"
                Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Devices (for Wi-Fi Direct P2P)"
                Manifest.permission.ACCESS_WIFI_STATE -> "Access Wi-Fi State (for P2P)"
                Manifest.permission.CHANGE_WIFI_STATE -> "Change Wi-Fi State (for P2P)"
                Manifest.permission.SEND_SMS -> "Send SMS (for SOS functionality)"
                else -> it.substringAfter("android.permission.")
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions Required") },
        text = { Text("This app needs the following permissions to function correctly:\n$permissionText\n\nPlease grant these permissions.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Grant Permissions") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CollisionAlertBanner(alert: CollisionAlert?) {
    AnimatedVisibility(
        visible = alert != null,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        alert?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Red),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Warning Icon",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)) {
                        Text(
                            "POTENTIAL COLLISION!",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "Peer: ${it.collidingPeer.deviceId.take(8)}... in approx. ${
                                String.format(
                                    Locale.US,
                                    "%.1f",
                                    it.timeToCollisionSeconds
                                )
                            }s",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun V2VSentinelApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { AppBottomNavigation(navController = navController) }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            viewModel = viewModel,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun AppBottomNavigation(navController: NavHostController) {
    val items = listOf(Screen.Dashboard, Screen.LiveMap)
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        items.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = currentRoute == screen.route,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route,
        modifier = modifier
    ) {
        composable(Screen.Dashboard.route) { DashboardScreen(viewModel) }
        composable(Screen.LiveMap.route) { LiveMapScreen(viewModel) }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Notifications)
    object LiveMap : Screen("livemap", "Live Map", Icons.Filled.Map)
}

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val speedKmh by viewModel.currentSpeedKmh.collectAsState()
    val gnssStatus by viewModel.gnssStatus.collectAsState()
    val connectivityStatus by viewModel.connectivityStatus.collectAsState()
    val collisionAlert by viewModel.collisionAlert.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CollisionAlertBanner(alert = collisionAlert)
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.onSettingsClicked() }) {
                        Icon(Icons.Filled.Settings, "Settings", Modifier.size(36.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.onWarningClicked() }) {
                            Icon(
                                Icons.Filled.Warning,
                                "Warning",
                                Modifier.size(36.dp),
                                tint = Color.Yellow
                            )
                        }
                        IconButton(onClick = { viewModel.onSosClicked() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                "SOS",
                                Modifier.size(36.dp),
                                tint = Color.Red
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f", speedKmh),
                        fontSize = 100.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(text = "km/h", fontSize = 24.sp, textAlign = TextAlign.Center)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusItem(gnssStatus.icon, gnssStatus.statusText, gnssStatus.color)
                    StatusItem(
                        connectivityStatus.icon,
                        connectivityStatus.statusText,
                        connectivityStatus.color
                    )
                }
            }
        }
    }
}

@Composable
fun StatusItem(icon: ImageVector, text: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(max = 150.dp)
    ) {
        Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text, fontSize = 14.sp, color = color, textAlign = TextAlign.Center)
    }
}

@SuppressLint("DefaultLocale")
@Composable
fun LiveMapScreen(viewModel: MainViewModel) {
    val userStableLocationFromViewModel by viewModel.userLocation.collectAsState()
    val userHeadingFromViewModel by viewModel.userHeading.collectAsState()
    val peerDeviceListFromViewModel by viewModel.peerDevices.collectAsState()
    val context = LocalContext.current

    val playServicesAvailable = remember {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    if (!playServicesAvailable) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Google Play Services is not available. Map cannot be displayed.",
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userStableLocationFromViewModel.latLng, 15f)
    }

    LaunchedEffect(userStableLocationFromViewModel) {
        val currentActualUserLatLng: LatLng = userStableLocationFromViewModel.latLng
        if (currentActualUserLatLng.latitude != 0.0 || currentActualUserLatLng.longitude != 0.0) {
            cameraPositionState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLng(
                    currentActualUserLatLng
                ), 1000
            )
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = true),
        properties = MapProperties(isMyLocationEnabled = true)
    ) {
        val userMarkerActualLatLng: LatLng = userStableLocationFromViewModel.latLng
        if (userMarkerActualLatLng.latitude != 0.0 || userMarkerActualLatLng.longitude != 0.0) {
            Marker(
                state = MarkerState(position = userMarkerActualLatLng),
                title = "My Location",
                snippet = "Speed: ${
                    String.format(
                        Locale.US,
                        "%.1f",
                        viewModel.currentSpeedKmh.collectAsState().value
                    )
                } km/h",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                rotation = userHeadingFromViewModel
            )
        }
        peerDeviceListFromViewModel.forEach { peerDeviceDisplay ->
            val peerMarkerActualLatLng: LatLng = peerDeviceDisplay.position.latLng
            Marker(
                state = MarkerState(position = peerMarkerActualLatLng),
                title = "Peer ${peerDeviceDisplay.id}",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            )
        }
    }
}

@SuppressLint("ViewModelConstructorInComposable")
@Preview(showBackground = true, device = "spec:width=1080px,height=2340px,dpi=440")
@Composable
fun DashboardScreenPreview() {
    MaterialTheme { DashboardScreen(viewModel = MainViewModel(Application())) }
}

@SuppressLint("ViewModelConstructorInComposable")
@Preview(showBackground = true)
@Composable
fun LiveMapScreenPreview() {
    MaterialTheme { LiveMapScreen(viewModel = MainViewModel(Application())) }
}
