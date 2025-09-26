package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
// import androidx.compose.material.icons.filled.HelpOutline // Keep this line commented or remove if not used elsewhere
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.myapplication.services.NavigationService
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class StableLatLng(val latLng: LatLng) {
    val latitude: Double get() = latLng.latitude
    val longitude: Double get() = latLng.longitude

    constructor(latitude: Double, longitude: Double) : this(LatLng(latitude, longitude))
}

// Data classes for UI state
data class GnssStatusInfo(
    val statusText: String,
    val icon: ImageVector,
    val color: Color
)

data class ConnectivityStatusInfo(
    val statusText: String,
    val icon: ImageVector,
    val color: Color
)

@Immutable
data class PeerDeviceDisplay(
    val id: String,
    val position: StableLatLng, // Uses StableLatLng
    val icon: ImageVector = Icons.Filled.DirectionsCar
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
) : AndroidViewModel(application) {

    private val _currentSpeedKmh = MutableStateFlow(0.0f)
    val currentSpeedKmh: StateFlow<Float> = _currentSpeedKmh.asStateFlow()

    private val _gnssStatus = MutableStateFlow(
        GnssStatusInfo("Initializing...", Icons.Filled.LocationSearching, Color.Gray)
    )
    val gnssStatus: StateFlow<GnssStatusInfo> = _gnssStatus.asStateFlow()

    private val _connectivityStatus = MutableStateFlow(
        ConnectivityStatusInfo("Initializing...", Icons.Filled.WifiOff, Color.Gray)
    )
    val connectivityStatus: StateFlow<ConnectivityStatusInfo> = _connectivityStatus.asStateFlow()

    private val _userLocation = MutableStateFlow(StableLatLng(0.0, 0.0))
    val userLocation: StateFlow<StableLatLng> = _userLocation.asStateFlow()

    private val _userHeading = MutableStateFlow(0.0f)
    val userHeading: StateFlow<Float> = _userHeading.asStateFlow()

    private val _peerDevices = MutableStateFlow<List<PeerDeviceDisplay>>(emptyList())
    val peerDevices: StateFlow<List<PeerDeviceDisplay>> = _peerDevices.asStateFlow()

    var navigationService: NavigationService? = null
    private var isServiceBound = MutableStateFlow(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NavigationService.LocalBinder
            navigationService = binder?.getService()
            isServiceBound.value = true
            Log.d("MainViewModel", "NavigationService connected")
            navigationService?.let {
                startCollectingDataFromService(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            isServiceBound.value = false
            Log.d("MainViewModel", "NavigationService disconnected")
            _gnssStatus.value =
                GnssStatusInfo("Service Disconnected", Icons.Filled.Error, Color.Red)
            _connectivityStatus.value =
                ConnectivityStatusInfo("Service Disconnected", Icons.Filled.Error, Color.Red)
        }
    }

    init {
        bindToNavigationService()
    }

    private fun bindToNavigationService() {
        Intent(getApplication(), NavigationService::class.java).also { intent ->
            getApplication<Application>().bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun startCollectingDataFromService(service: NavigationService) {
        viewModelScope.launch {
            service.currentSpeedFlow.collect { speedMs ->
                _currentSpeedKmh.value = speedMs * 3.6f
            }
        }
        viewModelScope.launch {
            service.userLocationFlow.collect { latLngFromService -> // latLngFromService is LatLng?
                latLngFromService?.let {
                    _userLocation.value = StableLatLng(it) // Convert to StableLatLng
                }
            }
        }
        viewModelScope.launch {
            service.userHeadingFlow.collect { heading ->
                _userHeading.value = heading
            }
        }
        viewModelScope.launch {
            service.gnssStatusTextFlow.collect { statusText ->
                _gnssStatus.value = when {
                    statusText.contains("Strong", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.LocationOn,
                        Color.Green
                    )

                    statusText.contains("Moderate", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.LocationOn,
                        Color.Yellow
                    )

                    statusText.contains("Weak", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.Warning,
                        Color(0xFFFFA500)
                    )

                    statusText.contains(
                        "Searching",
                        ignoreCase = true
                    ) || statusText.contains("Awaiting Fix", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.LocationSearching,
                        Color.Blue
                    )

                    statusText.contains("Permission Denied", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.LocationOff,
                        Color.Red
                    )

                    statusText.contains("Error", ignoreCase = true) -> GnssStatusInfo(
                        statusText,
                        Icons.Filled.Error,
                        Color.Red
                    )

                    statusText.contains("Stopped", ignoreCase = true) || statusText.contains(
                        "Off",
                        ignoreCase = true
                    ) -> GnssStatusInfo(statusText, Icons.Filled.LocationOff, Color.Gray)

                    else -> GnssStatusInfo(statusText, Icons.Filled.LocationSearching, Color.Gray)
                }
            }
        }
        viewModelScope.launch {
            service.connectivityStatusTextFlow.collect { statusText ->
                val peerCount = statusText.substringAfter("Peers: ", "").toIntOrNull()
                    ?: statusText.substringAfter("(Peers: ", "").substringBefore(")", "")
                        .toIntOrNull() ?: 0
                _connectivityStatus.value = when {
                    statusText.contains(
                        "Group Owner",
                        ignoreCase = true
                    ) || statusText.contains(
                        "Client",
                        ignoreCase = true
                    ) || statusText.contains("Peers:", ignoreCase = true) ->
                        ConnectivityStatusInfo(
                            statusText,
                            if (peerCount > 0) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            if (peerCount > 0) Color.Green else Color(0xFFFFA500)
                        )

                    statusText.contains("Discovering", ignoreCase = true) -> ConnectivityStatusInfo(
                        statusText,
                        Icons.Filled.DonutSmall,
                        Color.Blue
                    )

                    statusText.contains(
                        "Not Available",
                        ignoreCase = true
                    ) || statusText.contains(
                        "Error",
                        ignoreCase = true
                    ) || statusText.contains("Failed", ignoreCase = true) ->
                        ConnectivityStatusInfo(statusText, Icons.Filled.Error, Color.Red)

                    statusText.contains("Off", ignoreCase = true) -> ConnectivityStatusInfo(
                        statusText,
                        Icons.Filled.WifiOff,
                        Color.Gray
                    )

                    else -> ConnectivityStatusInfo(statusText, Icons.Filled.DonutSmall, Color.Gray)
                }
            }
        }
        viewModelScope.launch {
            service.nearbyVehiclesFlow.collect { vehiclesFromService -> // vehiclesFromService is List<PeerDeviceDisplay> from Service
                _peerDevices.value = vehiclesFromService.map { vehicleDisplayFromService ->
                    // vehicleDisplayFromService is a PeerDeviceDisplay object from the service.
                    // Its 'position' field is already a StableLatLng.
                    PeerDeviceDisplay( // This constructs MainViewModel.PeerDeviceDisplay
                        id = vehicleDisplayFromService.id,
                        position = vehicleDisplayFromService.position, // Use the StableLatLng directly
                        icon = vehicleDisplayFromService.icon
                    )
                }
            }
        }
        Log.d("MainViewModel", "Started collecting data from NavigationService flows.")
    }

    fun onSettingsClicked() {
        Log.d("MainViewModel", "Settings icon clicked. Navigation to settings not yet implemented.")
    }

    fun onSosClicked() {
        Log.d("MainViewModel", "SOS icon clicked. SOS functionality not yet implemented.")
    }

    override fun onCleared() {
        super.onCleared()
        if (isServiceBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.e("MainViewModel", "Service not registered or already unbound: ${e.message}")
            }
            isServiceBound.value = false
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var requestPermissionLauncher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                requestPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                        Log.d("MainActivity", "Fine location permission granted.")
                    } else {
                        Log.w("MainActivity", "Location permission denied.")
                    }
                }
                LaunchedEffect(Unit) {
                    checkAndRequestPermissions()
                }
                V2VSentinelApp(mainViewModel)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MainActivity", "All required permissions already granted.")
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.onSettingsClicked() }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = { viewModel.onSosClicked() }) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "SOS",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("%.1f", speedKmh),
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
            StatusItem(
                icon = gnssStatus.icon,
                text = gnssStatus.statusText,
                color = gnssStatus.color
            )
            StatusItem(
                icon = connectivityStatus.icon,
                text = connectivityStatus.statusText,
                color = connectivityStatus.color
            )
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
            Log.e("LiveMapScreen", "Google Play Services not available.")
        }
        return
    }

    val cameraPositionState = rememberCameraPositionState {
        // Directly use the unwrapped .latLng property from the State object
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
        uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = false),
        properties = MapProperties(isMyLocationEnabled = false)
    ) {
        val userMarkerActualLatLng: LatLng = userStableLocationFromViewModel.latLng
        if (userMarkerActualLatLng.latitude != 0.0 || userMarkerActualLatLng.longitude != 0.0) {
            Marker(
                state = MarkerState(position = userMarkerActualLatLng),
                title = "My Location",
                snippet = "Speed: ${
                    String.format(
                        "%.1f",
                        viewModel.currentSpeedKmh.collectAsState().value
                    )
                } km/h",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                rotation = userHeadingFromViewModel
            )
        }

        peerDeviceListFromViewModel.forEach { peerDeviceDisplay ->
            // peerDeviceDisplay is PeerDeviceDisplay (ViewModel), position is StableLatLng
            // MarkerState expects LatLng from com.google.android.gms.maps.model
            val peerMarkerActualLatLng: LatLng = peerDeviceDisplay.position.latLng
            Marker(
                state = MarkerState(position = peerMarkerActualLatLng), // This should now be correct
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
    MaterialTheme {
        DashboardScreen(viewModel = MainViewModel(Application()))
    }
}

@SuppressLint("ViewModelConstructorInComposable")
@Preview(showBackground = true)
@Composable
fun LiveMapScreenPreview() {
    MaterialTheme {
        LiveMapScreen(viewModel = MainViewModel(Application()))
    }
}
