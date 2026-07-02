package com.mandala.net.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import com.mandala.net.services.OfflineMapService
import java.io.FileInputStream
import java.util.regex.Pattern
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mandala.net.CyberTheme
import com.mandala.net.service.MockLocationManager
import com.mandala.net.service.MockLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

suspend fun fetchOsrmRoutePoints(waypoints: List<LocationBookmark>): List<Pair<Double, Double>> {
    return withContext(Dispatchers.IO) {
        val result = mutableListOf<Pair<Double, Double>>()
        try {
            val coordinatesStr = waypoints.joinToString(";") { "${it.lng},${it.lat}" }
            val urlStr = "https://router.project-osrm.org/route/v1/driving/$coordinatesStr?overview=full&geometries=geojson"
            val url = URL(urlStr)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "MandalaNetMockLocation/1.0 (Android)")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = org.json.JSONObject(response)
                val routes = jsonObject.optJSONArray("routes")
                if (routes != null && routes.length() > 0) {
                    val geometry = routes.getJSONObject(0).optJSONObject("geometry")
                    if (geometry != null && geometry.getString("type") == "LineString") {
                        val coordsArray = geometry.getJSONArray("coordinates")
                        for (i in 0 until coordsArray.length()) {
                            val coord = coordsArray.getJSONArray(i)
                            result.add(Pair(coord.getDouble(1), coord.getDouble(0)))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        result
    }
}

fun getDeviceActualLocation(context: Context, onLocationReceived: (Double, Double) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    var bestLocation: android.location.Location? = null
    try {
        val providers = locationManager.getProviders(true)
        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.time > bestLocation.time) {
                bestLocation = l
            }
        }
        
        if (bestLocation != null) {
            onLocationReceived(bestLocation.latitude, bestLocation.longitude)
        }
        
        val provider = when {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
            else -> null
        }
        
        if (provider != null) {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    onLocationReceived(location.latitude, location.longitude)
                    locationManager.removeUpdates(this)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                android.os.Looper.getMainLooper()
            )
        } else if (bestLocation == null) {
            Toast.makeText(context, "Nyalakan GPS untuk mendapatkan lokasi terkini", Toast.LENGTH_SHORT).show()
        }
    } catch (e: SecurityException) {
        Toast.makeText(context, "Izin lokasi hardware ditolak.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        if (bestLocation == null) {
            Toast.makeText(context, "Gagal mendapatkan lokasi GPS: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

data class LocationBookmark(val label: String, val lat: Double, val lng: Double)

data class LocationHistory(val lat: Double, val lng: Double, val timestamp: Long)

data class RoutePreset(val name: String, val waypoints: List<LocationBookmark>)

fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockLocationScreen(viewModel: com.mandala.net.MainViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isDark = CyberTheme.isDark || CyberTheme.isAmoled

    val bgMain = androidx.compose.material3.MaterialTheme.colorScheme.background
    val bgSidebar = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val bgCard = CyberTheme.SignalCardBg
    val bgCardInner = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    val bgCardSelected = CyberTheme.SignalCardBorder
    val bgInput = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    val bgFloatingPill = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val borderStrokeColor = CyberTheme.SignalCardBorder
    val textOnPrimaryAccent = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
    val textOnSuccessGreen = Color.White

    val currentLat by MockLocationManager.latitude.collectAsState()
    val currentLng by MockLocationManager.longitude.collectAsState()
    val isServiceActive by MockLocationManager.isActive.collectAsState()
    val showJoystick by MockLocationManager.showJoystick.collectAsState()
    val currentSpeed by MockLocationManager.speedKmh.collectAsState()
    val mockLocationError by MockLocationManager.mockLocationError.collectAsState()

    // Map WebView instance helper
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Geocoding states
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<LocationBookmark>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Battery optimization check
    var isBatteryOptimizing by remember { mutableStateOf(false) }

    val checkBatteryOptimizations = {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isBatteryOptimizing = !pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        checkBatteryOptimizations()
    }

    // Manual coordinate inputs
    var latInput by remember { mutableStateOf(currentLat.toString()) }
    var lngInput by remember { mutableStateOf(currentLng.toString()) }

    // Route Simulation
    val routeWaypoints = remember { mutableStateListOf<LocationBookmark>() }
    var simulationSpeed by remember { mutableStateOf(50f) } // km/h
    var isSimulatingRoute by remember { mutableStateOf(false) }
    
    var routeTotalDistanceMeters by remember { mutableStateOf(0f) }
    var routeRemainingDistanceMeters by remember { mutableStateOf(0f) }
    var routeRemainingSeconds by remember { mutableStateOf(0) }
    val routeTotalDurationSeconds by remember {
        derivedStateOf {
            val speedMs = simulationSpeed * (1000f / 3600f)
            if (speedMs > 0) (routeTotalDistanceMeters / speedMs).toInt() else 0
        }
    }

    LaunchedEffect(routeWaypoints.toList()) {
        if (routeWaypoints.size >= 2) {
            var total = 0f
            val distanceMeters = FloatArray(1)
            for (idx in 0 until routeWaypoints.size) {
                val start = routeWaypoints[idx]
                val end = routeWaypoints[(idx + 1) % routeWaypoints.size]
                android.location.Location.distanceBetween(start.lat, start.lng, end.lat, end.lng, distanceMeters)
                total += distanceMeters[0]
            }
            routeTotalDistanceMeters = total
        } else {
            routeTotalDistanceMeters = 0f
        }
        
        if (routeWaypoints.isNotEmpty()) {
            val jsonWaypoints = routeWaypoints.joinToString(prefix = "[", postfix = "]") {
                "{\"lat\":${it.lat},\"lng\":${it.lng},\"label\":\"${it.label.replace("\"", "\\\"")}\"}"
            }
            webViewInstance?.evaluateJavascript("if(typeof drawWaypoints !== 'undefined') drawWaypoints('$jsonWaypoints');", null)
        } else {
            webViewInstance?.evaluateJavascript("if(typeof drawWaypoints !== 'undefined') drawWaypoints('[]');", null)
        }
    }

    // Sync input fields with current coordinates when they change (due to map click or joystick move)
    LaunchedEffect(currentLat, currentLng) {
        latInput = String.format("%.6f", currentLat).replace(",", ".")
        lngInput = String.format("%.6f", currentLng).replace(",", ".")
    }

    // Map center coordinates states
    var mapCenterLat by remember { mutableStateOf(currentLat) }
    var mapCenterLng by remember { mutableStateOf(currentLng) }

    // Sync map center with current coordinates when they change initially
    LaunchedEffect(currentLat, currentLng) {
        mapCenterLat = currentLat
        mapCenterLng = currentLng
    }

    // Sidebar expand state
    var isSidebarExpanded by remember { mutableStateOf(false) }

    // Tile style state (default "google_road")
    var selectedTileStyle by remember { mutableStateOf("google_road") }

    // Mock Location developer options state check
    var isMockAllowed by remember { mutableStateOf(false) }

    // Initial Geolocation runtime permission launcher
    val initialPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            getDeviceActualLocation(context) { lat, lng ->
                MockLocationManager.latitude.value = lat
                MockLocationManager.longitude.value = lng
                mapCenterLat = lat
                mapCenterLng = lng
                val latVal = String.format(java.util.Locale.US, "%.6f", lat)
                val lngVal = String.format(java.util.Locale.US, "%.6f", lng)
                webViewInstance?.evaluateJavascript("updateMapLocation($latVal, $lngVal);", null)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getDeviceActualLocation(context) { lat, lng ->
                MockLocationManager.latitude.value = lat
                MockLocationManager.longitude.value = lng
                mapCenterLat = lat
                mapCenterLng = lng
                val latVal = String.format(java.util.Locale.US, "%.6f", lat)
                val lngVal = String.format(java.util.Locale.US, "%.6f", lng)
                webViewInstance?.evaluateJavascript("updateMapLocation($latVal, $lngVal);", null)
            }
        } else {
            initialPermissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        
        while (true) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            isMockAllowed = try {
                locationManager.addTestProvider(
                    android.location.LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true, 
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW, 
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
                locationManager.removeTestProvider(android.location.LocationManager.GPS_PROVIDER)
                true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    // Save Location Bookmark states
    var bookmarkNameInput by remember { mutableStateOf("") }
    var showSaveBookmarkDialog by remember { mutableStateOf(false) }
    val sharedPrefs = remember { context.getSharedPreferences("mock_location_bookmarks", Context.MODE_PRIVATE) }
    val bookmarks = remember { mutableStateListOf<LocationBookmark>() }

    // Route Preset States
    var presetNameInput by remember { mutableStateOf("") }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showLoadPresetDialog by remember { mutableStateOf(false) }
    val presetsPrefs = remember { context.getSharedPreferences("mock_location_presets", Context.MODE_PRIVATE) }
    val routePresets = remember { mutableStateListOf<RoutePreset>() }

    // History Log States
    val historyPrefs = remember { context.getSharedPreferences("mock_location_history_v1", Context.MODE_PRIVATE) }
    val historyList = remember { mutableStateListOf<LocationHistory>() }
    var selectedSidebarTab by remember { mutableStateOf(0) }

    // Route Simulation Effect
    LaunchedEffect(isSimulatingRoute) {
        if (isSimulatingRoute && routeWaypoints.size >= 2) {
            try {
                webViewInstance?.evaluateJavascript("clearTrail();", null)
                
                val loopedWaypoints = routeWaypoints.toList() + routeWaypoints.first()
                var routePoints = fetchOsrmRoutePoints(loopedWaypoints)
                if (routePoints.isEmpty()) {
                    routePoints = loopedWaypoints.map { Pair(it.lat, it.lng) }
                }
                
                var currentIndex = 0
                
                // Pre-calculate cumulative distances to prevent O(N) calculations inside the render loop
                val segmentDistances = FloatArray(routePoints.size - 1)
                var totalRoutePointsDistance = 0f
                for (idx in 0 until routePoints.size - 1) {
                    val p1 = routePoints[idx]
                    val p2 = routePoints[idx + 1]
                    val distTmp = FloatArray(1)
                    android.location.Location.distanceBetween(p1.first, p1.second, p2.first, p2.second, distTmp)
                    segmentDistances[idx] = distTmp[0]
                    totalRoutePointsDistance += distTmp[0]
                }
                var remainingDistanceForRoute = totalRoutePointsDistance
                
                // Delegate to Service for reliable background processing
                MockLocationManager.routeWaypoints.value = routePoints
                MockLocationManager.isSimulatingRoute.value = true

                while (isSimulatingRoute && isServiceActive && currentIndex < routePoints.size - 1) {
                    val startPt = routePoints[currentIndex]
                    val endPt = routePoints[currentIndex + 1]
                    val segmentDist = segmentDistances[currentIndex]
                    
                    if (segmentDist > 0) {
                        val speedMs = simulationSpeed * (1000f / 3600f) // km/h to m/s
                        val durationMs = (segmentDist / speedMs) * 1000f
                        val fps = 20f // Lower FPS (20 instead of 30) to give JS engine time to render
                        val steps = (durationMs / (1000f / fps)).toInt().coerceAtLeast(1)
                        
                        val latStep = (endPt.first - startPt.first) / steps
                        val lngStep = (endPt.second - startPt.second) / steps
                        
                        val startLoc = android.location.Location("").apply { latitude = startPt.first; longitude = startPt.second }
                        val endLoc = android.location.Location("").apply { latitude = endPt.first; longitude = endPt.second }
                        MockLocationManager.bearing.value = startLoc.bearingTo(endLoc)
                        
                        for (i in 0 until steps) {
                            if (!isSimulatingRoute || !isServiceActive) break
                            
                            val newLat = startPt.first + latStep * i
                            val newLng = startPt.second + lngStep * i
                            
                            val latVal = String.format(java.util.Locale.US, "%.6f", newLat)
                            val lngVal = String.format(java.util.Locale.US, "%.6f", newLng)
                            val speedColorHex = when {
                                simulationSpeed < 20f -> "#4CAF50"
                                simulationSpeed < 60f -> "#FFB300"
                                simulationSpeed < 100f -> "#E53935"
                                else -> "#8E24AA"
                            }
                            webViewInstance?.evaluateJavascript("updateMarker($latVal, $lngVal); addTrailPoint($latVal, $lngVal, '$speedColorHex');", null)
                            
                            val segmentFractionDone = i.toFloat() / steps
                            val segmentRemaining = segmentDist * (1f - segmentFractionDone)
                            
                            val subsequentDistance = remainingDistanceForRoute - segmentDist
                            routeRemainingDistanceMeters = segmentRemaining + subsequentDistance
                            
                            val currentSpeedMs = simulationSpeed * (1000f / 3600f)
                            routeRemainingSeconds = if (currentSpeedMs > 0) (routeRemainingDistanceMeters / currentSpeedMs).toInt() else 0
                            
                            kotlinx.coroutines.delay((1000f / fps).toLong())
                        }
                    }
                    
                    remainingDistanceForRoute -= segmentDist
                    if (isSimulatingRoute) {
                        currentIndex++
                        if (currentIndex >= routePoints.size - 1) {
                            currentIndex = 0
                            remainingDistanceForRoute = totalRoutePointsDistance
                            webViewInstance?.evaluateJavascript("clearTrail();", null)
                        }
                    }
                }
                if (!isServiceActive) isSimulatingRoute = false
            } finally {
                routeRemainingDistanceMeters = 0f
                routeRemainingSeconds = 0
            }
        } else {
            isSimulatingRoute = false
            routeRemainingDistanceMeters = 0f
            routeRemainingSeconds = 0
        }
    }

    val addLocationToHistory = { lat: Double, lng: Double ->
        val now = System.currentTimeMillis()
        val isDuplicate = historyList.firstOrNull()?.let { last ->
            Math.abs(last.lat - lat) < 0.0001 && Math.abs(last.lng - lng) < 0.0001
        } ?: false
        
        if (!isDuplicate) {
            val newElement = LocationHistory(lat, lng, now)
            historyList.add(0, newElement)
            while (historyList.size > 10) {
                historyList.removeAt(historyList.size - 1)
            }
            val serialized = historyList.joinToString(";") { "${it.lat},${it.lng},${it.timestamp}" }
            historyPrefs.edit().putString("history_items", serialized).apply()
        }
    }

    // Geolocation runtime permission launcher & centering helper
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            getDeviceActualLocation(context) { lat, lng ->
                MockLocationManager.latitude.value = lat
                MockLocationManager.longitude.value = lng
                val latVal = String.format(java.util.Locale.US, "%.6f", lat)
                val lngVal = String.format(java.util.Locale.US, "%.6f", lng)
                webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                addLocationToHistory(lat, lng)
                Toast.makeText(context, "Berhasil menyelaraskan lokasi dengan GPS hardware", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Izin lokasi diperlukan untuk mendapatkan lokasi hardware", Toast.LENGTH_SHORT).show()
        }
    }

    val centerOnHardwareLocation = {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasFine || hasCoarse) {
            getDeviceActualLocation(context) { lat, lng ->
                MockLocationManager.latitude.value = lat
                MockLocationManager.longitude.value = lng
                val latVal = String.format(java.util.Locale.US, "%.6f", lat)
                val lngVal = String.format(java.util.Locale.US, "%.6f", lng)
                webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                addLocationToHistory(lat, lng)
                Toast.makeText(context, "Berhasil menyelaraskan lokasi dengan GPS hardware", Toast.LENGTH_SHORT).show()
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Load initial bookmarks and history
    LaunchedEffect(Unit) {
        val allPrefs = sharedPrefs.all
        bookmarks.clear()
        allPrefs.forEach { (key, value) ->
            if (value is String) {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lng = parts[1].toDoubleOrNull()
                    if (lat != null && lng != null) {
                        bookmarks.add(LocationBookmark(key, lat, lng))
                    }
                }
            }
        }
        
        val allPresets = presetsPrefs.all
        routePresets.clear()
        allPresets.forEach { (key, value) ->
            if (value is String) {
                // format: lat1,lng1,label1;lat2,lng2,label2;...
                val wps = value.split(";").filter { it.isNotEmpty() }.mapNotNull {
                    val parts = it.split(",")
                    if (parts.size >= 3) {
                        val lat = parts[0].toDoubleOrNull()
                        val lng = parts[1].toDoubleOrNull()
                        val label = parts.drop(2).joinToString(",")
                        if (lat != null && lng != null) LocationBookmark(label, lat, lng) else null
                    } else null
                }
                if (wps.isNotEmpty()) {
                    routePresets.add(RoutePreset(key, wps))
                }
            }
        }

        val savedHistory = historyPrefs.getString("history_items", "") ?: ""
        if (savedHistory.isNotEmpty()) {
            val items = savedHistory.split(";").mapNotNull { itemStr ->
                val parts = itemStr.split(",")
                if (parts.size >= 3) {
                    val lat = parts[0].toDoubleOrNull()
                    val lng = parts[1].toDoubleOrNull()
                    val time = parts[2].toLongOrNull()
                    if (lat != null && lng != null && time != null) {
                        LocationHistory(lat, lng, time)
                    } else null
                } else null
            }
            historyList.clear()
            historyList.addAll(items)
        }
    }

    // Direct OSM Nominatim geocode request

    // Reverse Geocoding to get human readable address from coordinates
    fun performReverseGeocode(lat: Double, lng: Double) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val urlString = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json"
                val connection = URL(urlString).openConnection()
                connection.setRequestProperty("User-Agent", "MandalaNetApp/1.0 (ahmadpsgl5@gmail.com)")
                
                val responseText = connection.getInputStream().bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(responseText)
                val displayName = obj.optString("display_name", "")
                if (displayName.isNotEmpty()) {
                    // Shorten name to first 3 elements (e.g. road, district, city)
                    val shortName = displayName.split(",").take(3).joinToString(",")
                    withContext(Dispatchers.Main) {
                        searchQuery = shortName.trim()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MockLocation", "Reverse geocode failed: ${e.message}")
            }
        }
    }

    fun performSearch(query: String) {
        if (query.trim().isEmpty()) return
        isSearching = true
        
        // Try parsing coordinates directly first (e.g. "-6.200000, 106.816666" or "-6.200000 106.816666")
        val cleanedQuery = query.trim().replace(",", " ").replace(";", " ")
        val parts = cleanedQuery.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val first = parts[0].toDoubleOrNull()
            val second = parts[1].toDoubleOrNull()
            if (first != null && second != null && first >= -90.0 && first <= 90.0 && second >= -180.0 && second <= 180.0) {
                MockLocationManager.isSimulatingRoute.value = false
                isSimulatingRoute = false
                MockLocationManager.latitude.value = first
                MockLocationManager.longitude.value = second
                searchQuery = String.format(java.util.Locale.US, "%.6f, %.6f", first, second)
                performReverseGeocode(first, second)
                if (isServiceActive) addLocationToHistory(first, second)
                searchResults = emptyList()
                isSearching = false
                return
            }
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                
                val nominatimSearch = {
                    val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1&addressdetails=1&accept-language=id,en"
                    val connection = URL(urlString).openConnection()
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    
                    val responseText = connection.getInputStream().bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(responseText)
                    if (jsonArray.length() > 0) {
                        val obj = jsonArray.getJSONObject(0)
                        val displayName = obj.optString("display_name", "Tempat Tanpa Nama")
                        val shortName = displayName.split(",").map { it.trim() }.take(6).joinToString(", ")
                        val lat = obj.optDouble("lat", 0.0)
                        val lng = obj.optDouble("lon", 0.0)
                        LocationBookmark(shortName, lat, lng)
                    } else null
                }

                val googlePlacesSearch = {
                    val apiKey = com.mandala.net.BuildConfig.GOOGLE_MAPS_API_KEY
                    val urlString = "https://maps.googleapis.com/maps/api/place/textsearch/json?query=$encodedQuery&key=$apiKey"
                    val connection = URL(urlString).openConnection()
                    val responseText = connection.getInputStream().bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(responseText)
                    val results = jsonObject.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val obj = results.getJSONObject(0)
                        val name = obj.optString("name", "Tempat Tanpa Nama")
                        val formattedAddress = obj.optString("formatted_address", "")
                        val label = if (formattedAddress.isNotEmpty()) "$name, $formattedAddress" else name
                        val location = obj.getJSONObject("geometry").getJSONObject("location")
                        val lat = location.optDouble("lat", 0.0)
                        val lng = location.optDouble("lng", 0.0)
                        LocationBookmark(label, lat, lng)
                    } else null
                }
                
                val bestResult = try {
                    val apiKey = com.mandala.net.BuildConfig.GOOGLE_MAPS_API_KEY
                    if (apiKey.isNotEmpty() && apiKey != "YOUR_API_KEY_HERE" && apiKey != "dummy") {
                        googlePlacesSearch() ?: nominatimSearch()
                    } else {
                        nominatimSearch()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MockLocation", "Google Places API failed: ${e.message}", e)
                    nominatimSearch()
                }
                
                withContext(Dispatchers.Main) {
                    isSearching = false
                    if (bestResult != null) {
                        MockLocationManager.isSimulatingRoute.value = false
                        isSimulatingRoute = false
                        MockLocationManager.latitude.value = bestResult.lat
                        MockLocationManager.longitude.value = bestResult.lng
                        searchQuery = String.format(java.util.Locale.US, "%.6f, %.6f", bestResult.lat, bestResult.lng)
                        performReverseGeocode(bestResult.lat, bestResult.lng)
                        val latVal = String.format(java.util.Locale.US, "%.6f", bestResult.lat)
                        val lngVal = String.format(java.util.Locale.US, "%.6f", bestResult.lng)
                        webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                        if (isServiceActive) {
                            addLocationToHistory(bestResult.lat, bestResult.lng)
                        }
                        searchResults = emptyList()
                        Toast.makeText(context, "Terbang ke: ${bestResult.label}", Toast.LENGTH_SHORT).show()
                    } else {
                        searchResults = emptyList()
                        Toast.makeText(context, "Lokasi tidak ditemukan", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSearching = false
                    Toast.makeText(context, "Error mencari lokasi: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Toggle mock service
    fun toggleService() {
        checkBatteryOptimizations()
        if (isServiceActive) {
            val stopIntent = Intent(context, MockLocationService::class.java).apply {
                action = "ACTION_STOP"
            }
            context.stopService(stopIntent)
            Toast.makeText(context, "Mock Location Dinonaktifkan", Toast.LENGTH_SHORT).show()
        } else {
            // Check overlays draw permission first if joystick wants to be shown
            if (showJoystick && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Buka pengaturan dan berikan izin overlay!", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
                return
            }

            val startIntent = Intent(context, MockLocationService::class.java).apply {
                action = "ACTION_START"
                putExtra("EXTRA_LAT", currentLat)
                putExtra("EXTRA_LNG", currentLng)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
            addLocationToHistory(currentLat, currentLng)
            Toast.makeText(context, "Mock Location Aktif!", Toast.LENGTH_SHORT).show()
        }
    }

    // Static HTML map embedding Leaflet JS
    val mapHtml = remember {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <style>
                html, body {
                    height: 100% !important;
                    width: 100% !important;
                    margin: 0;
                    padding: 0;
                    background-color: #F7F9FC;
                    overflow: hidden;
                    position: relative;
                }
                body.dark-mode {
                    background-color: #070b19;
                }
                #map {
                    display: block;
                    width: 100% !important;
                    height: 100% !important;
                    background: #F7F9FC;
                    position: absolute;
                    top: 0;
                    left: 0;
                    margin: 0;
                    padding: 0;
                }
                #map.dark-theme {
                    background: #070b19;
                }
                .dark-theme:not(.no-filter) .leaflet-tile-container {
                    filter: invert(100%) hue-rotate(180deg) brightness(95%) contrast(90%);
                }
                .leaflet-container {
                    background-color: #F7F9FC !important;
                }
                .dark-theme.leaflet-container {
                    background-color: #070b19 !important;
                }
                .leaflet-control-attribution {
                    display: none !important;
                }
                .leaflet-marker-icon, .leaflet-marker-shadow {
                    transition: transform 0.1s linear;
                }
                #loading {
                    position: absolute;
                    top: 0;
                    bottom: 0;
                    left: 0;
                    right: 0;
                    background-color: #F7F9FC;
                    display: flex;
                    flex-direction: column;
                    justify-content: center;
                    align-items: center;
                    z-index: 9999;
                    color: #0061A4;
                    font-family: sans-serif;
                    font-size: 14px;
                    font-weight: bold;
                }
                #loading.dark-mode {
                    background-color: #070b19;
                    color: #00f0ff;
                }
                .spinner {
                    border: 3px solid #E2E8F0;
                    border-top: 3px solid #0061A4;
                    border-radius: 50%;
                    width: 24px;
                    height: 24px;
                    animation: spin 1s linear infinite;
                    margin-bottom: 12px;
                }
                .dark-mode .spinner {
                    border: 3px solid #131930;
                    border-top: 3px solid #00f0ff;
                }
                @keyframes spin {
                    0% { transform: rotate(0deg); }
                    100% { transform: rotate(360deg); }
                }
            </style>
        </head>
        <body>
            <div id="loading">
                <div class="spinner"></div>
                <div id="loading-text">Menghubungkan Peta Leaflet...</div>
            </div>
            <div id="map"></div>

            <script>
                var map = null;
                var marker = null;
                var currentTileLayer = null;
                var pendingInit = null;
                
                var waypointMarkers = [];
                var iconStart = null;
                var iconEnd = null;
                var iconWaypoint = null;

                function drawWaypoints(waypointsJson) {
                    try {
                        if (!iconStart) {
                            iconStart = L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-green.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
                            });
                            iconEnd = L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
                            });
                            iconWaypoint = L.icon({
                                iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-blue.png',
                                shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
                                iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
                            });
                        }
                        waypointMarkers.forEach(function(m) { map.removeLayer(m); });
                        waypointMarkers = [];
                        var waypoints = JSON.parse(waypointsJson);
                        waypoints.forEach(function(wp, idx) {
                            var ic = iconWaypoint;
                            if (idx === 0) ic = iconStart;
                            else if (idx === waypoints.length - 1) ic = iconEnd;
                            var m = L.marker([wp.lat, wp.lng], {icon: ic}).addTo(map);
                            m.bindPopup("<b>" + wp.label + "</b><br>" + wp.lat.toFixed(6) + ", " + wp.lng.toFixed(6));
                            waypointMarkers.push(m);
                        });
                    } catch(e) {}
                }
                
                var alternateUrls = [
                    'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png',
                    'https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}',
                    'https://tile.openstreetmap.de/{z}/{x}/{y}.png',
                    'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
                ];
                var currentUrlIndex = 0;
                var tileRetryCount = 0;
                var maxRetries = 3;

                // Log to Android
                function logToAndroid(msg) {
                    console.log(msg);
                    if (window.AndroidBridge && window.AndroidBridge.logError) {
                        window.AndroidBridge.logError(msg);
                    }
                }

                // CDNs list for Leaflet CSS and JS
                var cdns = [
                    {
                        css: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
                        js: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
                    },
                    {
                        css: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css",
                        js: "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"
                    },
                    {
                        css: "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css",
                        js: "https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"
                    }
                ];

                var currentCdnIndex = 0;

                function loadLeaflet() {
                    if (currentCdnIndex >= cdns.length) {
                        logToAndroid("[LEAFLET_LOAD] ERROR: All Leaflet CDNs failed to load!");
                        document.getElementById('loading-text').innerHTML = "Gagal memuat peta. Silakan periksa koneksi internet Anda.";
                        return;
                    }

                    var cdn = cdns[currentCdnIndex];
                    logToAndroid("[LEAFLET_LOAD] Trying CDN " + (currentCdnIndex + 1) + ": " + cdn.js);

                    // Remove existing style/script if any
                    var oldStyle = document.getElementById('leaflet-css-link');
                    if (oldStyle) oldStyle.remove();
                    var oldScript = document.getElementById('leaflet-js-script');
                    if (oldScript) oldScript.remove();

                    // Load CSS
                    var link = document.createElement('link');
                    link.id = 'leaflet-css-link';
                    link.rel = 'stylesheet';
                    link.href = cdn.css;
                    document.head.appendChild(link);

                    // Load JS
                    var script = document.createElement('script');
                    script.id = 'leaflet-js-script';
                    script.src = cdn.js;
                    script.onload = function() {
                        logToAndroid("[LEAFLET_LOAD] SUCCESS: Leaflet loaded from CDN index " + currentCdnIndex);
                        
                        // Trigger any pending init
                        if (pendingInit) {
                            realInitMap(pendingInit.lat, pendingInit.lng, pendingInit.styleName, pendingInit.isDark);
                            pendingInit = null;
                        } else {
                            // Fail-safe default init centering on Indonesia
                            setTimeout(function() {
                                if (!map) {
                                    logToAndroid("[FAIL_SAFE] Self-initializing default map in Indonesia...");
                                    realInitMap(-6.2088, 106.8456, 'google_road', false);
                                }
                            }, 1000);
                        }
                    };
                    script.onerror = function() {
                        logToAndroid("[LEAFLET_LOAD] FAILED: Leaflet JS failed from CDN index " + currentCdnIndex);
                        currentCdnIndex++;
                        loadLeaflet();
                    };
                    document.head.appendChild(script);
                }

                // Start loading Leaflet dynamically
                loadLeaflet();

                function runDiagnostics() {
                    console.log("[MAP_DIAGNOSTIC] Starting map diagnostic checks...");
                    
                    // 1. Verify map container element in DOM
                    var mapContainer = document.getElementById('map');
                    if (!mapContainer) {
                        console.error("[MAP_DIAGNOSTIC] ERROR: Map container div with ID 'map' does not exist in the DOM!");
                        logToAndroid("Diagnostic Error: #map container not found in DOM");
                    } else {
                        var w = mapContainer.offsetWidth;
                        var h = mapContainer.offsetHeight;
                        var style = window.getComputedStyle(mapContainer);
                        console.log("[MAP_DIAGNOSTIC] Map Container Dimensions: " + w + "px x " + h + "px");
                        console.log("[MAP_DIAGNOSTIC] Map Container Style Display: " + style.display + ", Position: " + style.position + ", Visibility: " + style.visibility);
                        if (w === 0 || h === 0) {
                            console.warn("[MAP_DIAGNOSTIC] WARNING: Map container has 0 width or height. Leaflet WILL remain blank!");
                        } else {
                            console.log("[MAP_DIAGNOSTIC] SUCCESS: Map container is present with positive dimensions.");
                        }
                    }
                    
                    // 2. Verify Leaflet library state
                    if (typeof L === 'undefined') {
                        console.error("[MAP_DIAGNOSTIC] ERROR: Leaflet library 'L' is not loaded or loaded incorrectly (undefined).");
                    } else {
                        console.log("[MAP_DIAGNOSTIC] SUCCESS: Leaflet (L) loaded. Version: " + L.version);
                        if (map) {
                            console.log("[MAP_DIAGNOSTIC] SUCCESS: Map instance exists. Zoom level: " + map.getZoom() + ", Center: " + map.getCenter().toString());
                        } else {
                            console.warn("[MAP_DIAGNOSTIC] WARNING: Map instance has not been initialized yet.");
                        }
                    }
                    
                    // 3. Validate OSM Tile Fetch process & status
                    var tileSamples = [
                        { name: "CartoDB Voyager (Primary)", url: "https://a.basemaps.cartocdn.com/rastertiles/voyager/12/3233/2117.png" },
                        { name: "Google Maps Road", url: "https://mt1.google.com/vt/lyrs=m&x=3233&y=2117&z=12" },
                        { name: "OpenStreetMap Germany", url: "https://tile.openstreetmap.de/12/3233/2117.png" },
                        { name: "Standard OpenStreetMap", url: "https://a.tile.openstreetmap.org/12/3233/2117.png" }
                    ];
                    
                    tileSamples.forEach(function(sample) {
                        console.log("[MAP_DIAGNOSTIC] Testing network request to: " + sample.name);
                        
                        // HEAD Fetch check
                        fetch(sample.url, { method: 'HEAD', mode: 'no-cors' })
                            .then(function() {
                                console.log("[MAP_DIAGNOSTIC] Tile request OK: " + sample.name);
                            })
                            .catch(function(err) {
                                console.error("[MAP_DIAGNOSTIC] Tile request FAILED: " + sample.name + ". Details: " + err.message);
                            });
                        
                        // Browser Image load verification
                        var img = new Image();
                        img.onload = function() {
                            console.log("[MAP_DIAGNOSTIC] Tile Image successfully parsed: " + sample.name);
                        };
                        img.onerror = function() {
                            console.error("[MAP_DIAGNOSTIC] Tile Image render FAILED: " + sample.name + " (" + sample.url + ")");
                        };
                        img.src = sample.url;
                    });
                }

                function setTheme(isDark) {
                    var mapEl = document.getElementById('map');
                    var bodyEl = document.body;
                    var loadingEl = document.getElementById('loading');
                    if (isDark) {
                        if (mapEl) mapEl.classList.add('dark-theme');
                        if (bodyEl) bodyEl.classList.add('dark-mode');
                        if (loadingEl) loadingEl.classList.add('dark-mode');
                    } else {
                        if (mapEl) mapEl.classList.remove('dark-theme');
                        if (bodyEl) bodyEl.classList.remove('dark-mode');
                        if (loadingEl) loadingEl.classList.remove('dark-mode');
                    }
                }

                function initMap(lat, lng, styleName, isDark) {
                    if (typeof L === 'undefined') {
                        pendingInit = { lat: lat, lng: lng, styleName: styleName, isDark: isDark };
                        return;
                    }
                    realInitMap(lat, lng, styleName, isDark);
                }

                function createTileLayer(styleName) {
                    try {
                        if (currentTileLayer && map) {
                            map.removeLayer(currentTileLayer);
                        }
                        
                        var mapEl = document.getElementById('map');
                        if (mapEl) {
                            if (styleName === 'google_satellite' || styleName === 'carto_dark') {
                                mapEl.classList.add('no-filter');
                            } else {
                                mapEl.classList.remove('no-filter');
                            }
                        }
                        
                        var url;
                        if (styleName === 'google_satellite') {
                            url = 'https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}';
                        } else if (styleName === 'google_road') {
                            url = 'https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}';
                        } else if (styleName === 'carto_voyager') {
                            url = 'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png';
                        } else if (styleName === 'carto_dark') {
                            url = 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png';
                        } else if (styleName === 'osm_de') {
                            url = 'https://tile.openstreetmap.de/{z}/{x}/{y}.png';
                        } else if (styleName === 'osm') {
                            url = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
                        } else {
                            url = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
                        }
                        
                        var maxZoom = (styleName === 'google_satellite' || styleName === 'google_road') ? 20 : 19;
                        currentTileLayer = L.tileLayer(url, { 
                            maxZoom: maxZoom, 
                            attribution: '',
                            crossOrigin: true
                        });
                        
                        currentTileLayer.on('tileerror', function(error) {
                            console.warn("Tile failed to load:", error.url);
                        });
                        
                        if (map) {
                            currentTileLayer.addTo(map);
                        }
                    } catch (e) {
                        console.error("Error setting tile layer:", e);
                    }
                }

                function realInitMap(lat, lng, styleName, isDark) {
                    try {
                        var loadingEl = document.getElementById('loading');
                        if (loadingEl) {
                            loadingEl.style.display = 'none';
                        }
                        if (map) {
                            map.remove();
                        }
                        
                        map = L.map('map', { 
                            zoomControl: false,
                            trackResize: true,
                            preferCanvas: true // Optimization: Render paths on canvas instead of SVG
                        }).setView([lat, lng], 15);
                        
                        // Create and add tile layer with resilient retry/fallback
                        createTileLayer(styleName);

                        marker = L.marker([lat, lng], { draggable: true }).addTo(map);

                        map.on('click', function(e) {
                            marker.setLatLng(e.latlng);
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onMapSelected(e.latlng.lat, e.latlng.lng);
                            }
                        });
                        
                        map.on('contextmenu', function(e) {
                            marker.setLatLng(e.latlng);
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onMapSelected(e.latlng.lat, e.latlng.lng);
                            }
                        });

                        // Track interaction to prevent fighting with auto-pan during simulation
                        map.on('mousedown touchstart dragstart', function() {
                            isUserInteracting = true;
                            isCameraAnimating = false;
                            if (interactionTimeout) clearTimeout(interactionTimeout);
                        });
                        map.on('mouseup touchend dragend', function() {
                            if (interactionTimeout) clearTimeout(interactionTimeout);
                            interactionTimeout = setTimeout(function() {
                                isUserInteracting = false;
                            }, 3000);
                        });

                        // Drag-to-Pin longpress detection logic (Touch & Mouse)
                        var pressTimer = null;
                        
                        function startPress(e) {
                            if (pressTimer) clearTimeout(pressTimer);
                            pressTimer = setTimeout(function() {
                                if (marker && map && e.latlng) {
                                    marker.setLatLng(e.latlng);
                                    if (window.AndroidBridge) {
                                        window.AndroidBridge.onMapSelected(e.latlng.lat, e.latlng.lng);
                                    }
                                }
                            }, 600); // 600ms hold time to register as long-press
                        }
                        
                        function cancelPress() {
                            if (pressTimer) {
                                clearTimeout(pressTimer);
                                pressTimer = null;
                            }
                        }
                        
                        map.on('mousedown', function(e) {
                            if (e.originalEvent && e.originalEvent.button === 0) {
                                startPress(e);
                            }
                        });
                        
                        map.on('touchstart', function(e) {
                            startPress(e);
                        });
                        
                        map.on('mouseup touchend mousemove touchmove zoomstart dragstart movestart', function() {
                            cancelPress();
                        });

                        map.on('contextmenu', function(e) {
                            if (marker && map && e.latlng) {
                                marker.setLatLng(e.latlng);
                                if (window.AndroidBridge) {
                                    window.AndroidBridge.onMapSelected(e.latlng.lat, e.latlng.lng);
                                }
                            }
                        });

                        marker.on('dragend', function(e) {
                            var position = marker.getLatLng();
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onMapSelected(position.lat, position.lng);
                            }
                        });

                        map.on('move', function() {
                            var center = map.getCenter();
                            if (window.AndroidBridge && window.AndroidBridge.onMapCenterChanged) {
                                window.AndroidBridge.onMapCenterChanged(center.lat, center.lng);
                            }
                        });

                        if (window.AndroidBridge && window.AndroidBridge.onMapCenterChanged) {
                            window.AndroidBridge.onMapCenterChanged(lat, lng);
                        }

                        setTheme(isDark);

                        var mapEl = document.getElementById('map');
                        if (styleName === 'google_satellite' || styleName === 'google_road' || styleName === 'carto_dark' || styleName === 'carto_voyager') {
                            mapEl.classList.add('no-filter');
                        } else {
                            mapEl.classList.remove('no-filter');
                        }
                        
                        // Force layout update with staggered checks to ensure map container has accurate size
                        setTimeout(function() { if (map) map.invalidateSize(); }, 50);
                        setTimeout(function() { if (map) map.invalidateSize(); }, 250);
                        setTimeout(function() { if (map) map.invalidateSize(); }, 500);
                        setTimeout(function() { if (map) map.invalidateSize(); }, 1500);
                        
                        // Run map diagnostics to verify rendering and tiles loading
                        setTimeout(runDiagnostics, 1000);
                        
                    } catch (e) {
                        logToAndroid(e.toString());
                    }
                }

                var isUserInteracting = false;
                var isCameraAnimating = false;
                var interactionTimeout = null;

                function animateMapTo(newLat, newLng) {
                    try {
                        if (marker && map) {
                            var newLatLng = new L.LatLng(newLat, newLng);
                            marker.setLatLng(newLatLng);
                            
                            isCameraAnimating = true;
                            var zoom = map.getZoom() || 15;
                            if (zoom < 12) zoom = 15;
                            
                            map.flyTo(newLatLng, zoom, {
                                animate: true,
                                duration: 1.5
                            });
                            
                            // Reset the camera animating flag after the animation completes
                            setTimeout(function() {
                                isCameraAnimating = false;
                            }, 1600);
                        }
                    } catch (e) {
                        isCameraAnimating = false;
                    }
                }

                function updateMapLocation(newLat, newLng) {
                    animateMapTo(newLat, newLng);
                }

                function updateMarker(newLat, newLng) {
                    try {
                        if (marker && map) {
                            var newLatLng = new L.LatLng(newLat, newLng);
                            marker.setLatLng(newLatLng);
                            
                            if (!isUserInteracting && !isCameraAnimating) {
                                // Use setView without animation to create a smooth 20fps follow camera
                                // This prevents animation conflict jitters during high-speed movement
                                map.setView(newLatLng, map.getZoom(), { animate: false });
                            }
                        }
                    } catch (e) {}
                }

                var routePolyline = null;
                var currentPolylineColor = '#E57373';
                var allPolylines = [];

                function clearTrail() {
                    try {
                        allPolylines.forEach(p => map.removeLayer(p));
                        if (routePolyline && map) {
                            map.removeLayer(routePolyline);
                        }
                        routePolyline = null;
                        allPolylines = [];
                    } catch(e) {}
                }

                function addTrailPoint(lat, lng, color) {
                    try {
                        if (!map) return;
                        color = color || '#E57373';
                        if (!routePolyline || currentPolylineColor !== color) {
                            if (routePolyline) {
                                allPolylines.push(routePolyline);
                            }
                            routePolyline = L.polyline([[lat, lng]], {color: color, weight: 4, smoothFactor: 2.0}).addTo(map);
                            currentPolylineColor = color;
                            
                            // Ensure previous polyline connects to new one
                            if (allPolylines.length > 0) {
                                var lastPolyline = allPolylines[allPolylines.length - 1];
                                var lastLatLngs = lastPolyline.getLatLngs();
                                if (lastLatLngs.length > 0) {
                                    // Prepend the last point of the previous line to this new line
                                    var lastPt = lastLatLngs[lastLatLngs.length - 1];
                                    routePolyline.setLatLngs([lastPt, [lat, lng]]);
                                }
                            }
                        } else {
                            routePolyline.addLatLng([lat, lng]);
                        }
                    } catch(e) {}
                }

                function setTileLayer(styleName) {
                    try {
                        if (map) {
                            tileRetryCount = 0;
                            createTileLayer(styleName);
                            
                            var mapEl = document.getElementById('map');
                            if (styleName === 'google_satellite' || styleName === 'google_road' || styleName === 'carto_dark' || styleName === 'carto_voyager') {
                                mapEl.classList.add('no-filter');
                            } else {
                                mapEl.classList.remove('no-filter');
                            }
                        }
                    } catch (e) {}
                }

                // Call invalidateSize on window resize
                window.addEventListener('resize', function() {
                    if (map) {
                        map.invalidateSize();
                    }
                });

                window.onload = function() {
                    setTimeout(runDiagnostics, 3000);
                };
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    // Dynamic map sync trigger: update marker & center when lat/lng changes
    LaunchedEffect(currentLat, currentLng) {
        if (!isSimulatingRoute) {
            val latVal = String.format(java.util.Locale.US, "%.6f", currentLat)
            val lngVal = String.format(java.util.Locale.US, "%.6f", currentLng)
            webViewInstance?.evaluateJavascript("updateMarker($latVal, $lngVal);", null)
        }
    }

    // Tile style dynamic update trigger
    LaunchedEffect(selectedTileStyle) {
        webViewInstance?.evaluateJavascript("setTileLayer('$selectedTileStyle');", null)
    }

    // Dynamic map theme update trigger
    LaunchedEffect(isDark) {
        webViewInstance?.evaluateJavascript("setTheme(${isDark});", null)
    }

    mockLocationError?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { MockLocationManager.mockLocationError.value = null },
            title = {
                Text("Izin Lokasi Palsu Diperlukan", fontWeight = FontWeight.Bold, color = CyberTheme.ErrorRed)
            },
            text = {
                Text(errorMsg, color = CyberTheme.TextPrimary)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        MockLocationManager.mockLocationError.value = null
                        // Optional: Redirect to developer options
                        try {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Tidak dapat membuka Opsi Pengembang", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Buka Pengaturan", color = CyberTheme.PrimaryAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { MockLocationManager.mockLocationError.value = null }) {
                    Text("Tutup", color = CyberTheme.TextSecondary)
                }
            }
        )
    }

    // Save Location dialog
    if (showSaveBookmarkDialog) {
        AlertDialog(
            onDismissRequest = { showSaveBookmarkDialog = false },
            title = {
                Text(
                    "Simpan Favorit",
                    fontWeight = FontWeight.Bold,
                    color = CyberTheme.PrimaryAccent
                )
            },
            text = {
                Column {
                    Text(
                        "Masukkan nama penanda untuk koordinat ini:",
                        fontSize = 13.sp,
                        color = CyberTheme.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = bookmarkNameInput,
                        onValueChange = { bookmarkNameInput = it },
                        placeholder = { Text("Nama Lokasi (misal: Rumah, Kantor)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CyberTheme.TextPrimary,
                            unfocusedTextColor = CyberTheme.TextPrimary,
                            focusedBorderColor = CyberTheme.PrimaryAccent,
                            unfocusedBorderColor = CyberTheme.TextSecondary.copy(alpha = 0.5f),
                            focusedLabelColor = CyberTheme.PrimaryAccent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bookmark_name_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Lat: ${String.format("%.5f", currentLat)}\nLng: ${String.format("%.5f", currentLng)}",
                        fontSize = 11.sp,
                        color = CyberTheme.TextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = if (bookmarkNameInput.trim().isEmpty()) {
                            "Lokasi Saya ${bookmarks.size + 1}"
                        } else {
                            bookmarkNameInput.trim()
                        }
                        sharedPrefs.edit().putString(name, "$currentLat,$currentLng").apply()
                        val existingIndex = bookmarks.indexOfFirst { it.label == name }
                        if (existingIndex >= 0) {
                            bookmarks[existingIndex] = LocationBookmark(name, currentLat, currentLng)
                        } else {
                            bookmarks.add(LocationBookmark(name, currentLat, currentLng))
                        }
                        Toast.makeText(context, "Favorit disimpan: $name", Toast.LENGTH_SHORT).show()
                        bookmarkNameInput = ""
                        showSaveBookmarkDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTheme.PrimaryAccent)
                ) {
                    Text("Simpan", color = textOnPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveBookmarkDialog = false }) {
                    Text("Batal", color = CyberTheme.TextSecondary)
                }
            },
            containerColor = bgSidebar,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.border(1.dp, borderStrokeColor, RoundedCornerShape(16.dp))
        )
    }

    // Save Route Preset Dialog
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = {
                Text(
                    "Simpan Preset Rute",
                    fontWeight = FontWeight.Bold,
                    color = CyberTheme.PrimaryAccent
                )
            },
            text = {
                Column {
                    Text(
                        "Masukkan nama untuk rute ini:",
                        fontSize = 13.sp,
                        color = CyberTheme.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        placeholder = { Text("Nama Preset (misal: Rute Kerja)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CyberTheme.TextPrimary,
                            unfocusedTextColor = CyberTheme.TextPrimary,
                            focusedBorderColor = CyberTheme.PrimaryAccent,
                            unfocusedBorderColor = CyberTheme.TextSecondary.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("preset_name_input")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Total ${routeWaypoints.size} waypoint",
                        fontSize = 11.sp,
                        color = CyberTheme.TextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = if (presetNameInput.trim().isEmpty()) {
                            "Preset ${routePresets.size + 1}"
                        } else {
                            presetNameInput.trim()
                        }
                        val waypointsStr = routeWaypoints.joinToString(";") { "${it.lat},${it.lng},${it.label}" }
                        presetsPrefs.edit().putString(name, waypointsStr).apply()
                        
                        val existingIndex = routePresets.indexOfFirst { it.name == name }
                        if (existingIndex >= 0) {
                            routePresets[existingIndex] = RoutePreset(name, routeWaypoints.toList())
                        } else {
                            routePresets.add(RoutePreset(name, routeWaypoints.toList()))
                        }
                        Toast.makeText(context, "Preset disimpan: $name", Toast.LENGTH_SHORT).show()
                        presetNameInput = ""
                        showSavePresetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberTheme.PrimaryAccent)
                ) {
                    Text("Simpan", color = textOnPrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Batal", color = CyberTheme.TextSecondary)
                }
            },
            containerColor = bgSidebar,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.border(1.dp, borderStrokeColor, RoundedCornerShape(16.dp))
        )
    }

    // Load Route Preset Dialog
    if (showLoadPresetDialog) {
        AlertDialog(
            onDismissRequest = { showLoadPresetDialog = false },
            title = {
                Text(
                    "Muat Preset Rute",
                    fontWeight = FontWeight.Bold,
                    color = CyberTheme.PrimaryAccent
                )
            },
            text = {
                if (routePresets.isEmpty()) {
                    Text("Belum ada preset tersimpan.", color = CyberTheme.TextSecondary, fontSize = 13.sp)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                    ) {
                        items(routePresets.size) { index ->
                            val preset = routePresets[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(bgCardInner, RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isSimulatingRoute) {
                                            Toast.makeText(context, "Hentikan simulasi terlebih dahulu", Toast.LENGTH_SHORT).show()
                                        } else {
                                            routeWaypoints.clear()
                                            routeWaypoints.addAll(preset.waypoints)
                                            showLoadPresetDialog = false
                                            Toast.makeText(context, "Preset dimuat: ${preset.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, color = CyberTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("${preset.waypoints.size} titik waypoint", color = CyberTheme.TextSecondary, fontSize = 11.sp)
                                }
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Hapus",
                                    tint = CyberTheme.ErrorRed,
                                    modifier = Modifier.size(20.dp).clickable {
                                        presetsPrefs.edit().remove(preset.name).apply()
                                        routePresets.removeAt(index)
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoadPresetDialog = false }) {
                    Text("Tutup", color = CyberTheme.PrimaryAccent)
                }
            },
            containerColor = bgSidebar,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.border(1.dp, borderStrokeColor, RoundedCornerShape(16.dp))
        )
    }

    // Modern Overlay Responsive Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgMain)
    ) {
        
        // BACKDROP CLICK TO CLOSE SIDEBAR
        AnimatedVisibility(
            visible = isSidebarExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(5f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isSidebarExpanded = false }
            )
        }

        // COLLAPSIBLE SIDEBAR MENU
        AnimatedVisibility(
            visible = isSidebarExpanded,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
            modifier = Modifier.zIndex(10f).align(Alignment.CenterStart)
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .background(bgSidebar)
                        .drawBehind {
                            drawLine(
                                color = borderStrokeColor,
                                start = Offset(this.size.width, 0f),
                                end = Offset(this.size.width, this.size.height),
                                strokeWidth = 2f
                            )
                        }
                ) {
                // Scrollable container for sidebar content
                val sidebarScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(sidebarScrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    
                    // HEADER WITH LIVE DEVELOPER STATUS BADGE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "MANDALA NET",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyberTheme.PrimaryAccent,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (isMockAllowed) CyberTheme.SuccessGreen else CyberTheme.ErrorRed, CircleShape)
                                )
                            }
                            Text(
                                text = "SPOOFER GPS",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = CyberTheme.TextPrimary
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val badgeColor = if (isMockAllowed) CyberTheme.SuccessGreen else CyberTheme.ErrorRed
                            val badgeText = if (isMockAllowed) "READY" else "BLOCKED"
                            Box(
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = badgeText,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    color = badgeColor
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = CyberTheme.PrimaryAccent.copy(alpha = 0.1f))

                    // TAB SELECTOR
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgCardInner, RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("KONTROL", "RUTE GPS", "RIWAYAT").forEachIndexed { index, label ->
                            val isSelected = selectedSidebarTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) CyberTheme.PrimaryAccent else Color.Transparent)
                                    .clickable { selectedSidebarTab = index }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = if (isSelected) CyberTheme.TextPrimary else CyberTheme.TextSecondary
                                )
                            }
                        }
                    }

                    if (selectedSidebarTab == 0) {
                        // STATUS & QUICK SPOOF SWITCH CARD
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgCard),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("STATUS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isServiceActive) CyberTheme.SuccessGreen.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f),
                                            CircleShape
                                        )
                                        .border(
                                            1.dp,
                                            if (isServiceActive) CyberTheme.SuccessGreen else Color.Red,
                                            CircleShape
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isServiceActive) "AKTIF" else "MATI",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isServiceActive) CyberTheme.SuccessGreen else Color.Red
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Large Service Toggle Button
                            Button(
                                onClick = { toggleService() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isServiceActive) CyberTheme.ErrorRed else CyberTheme.SuccessGreen
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .testTag("spoof_toggle_button")
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isServiceActive) Icons.Default.LocationOff else Icons.Default.LocationOn,
                                        contentDescription = "Trigger Service",
                                        tint = if (isServiceActive) Color.White else textOnSuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isServiceActive) "HENTIKAN SPOOF" else "MULAI SPOOFING",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (isServiceActive) Color.White else textOnSuccessGreen
                                    )
                                }
                            }


                        }
                    }

                    // JOYSTICK & SPEED CONTROL CARD
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgCard),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("JOYSTICK LAYAR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                                Switch(
                                    checked = showJoystick,
                                    onCheckedChange = { MockLocationManager.showJoystick.value = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = textOnPrimaryAccent,
                                        checkedTrackColor = CyberTheme.PrimaryAccent,
                                        uncheckedThumbColor = CyberTheme.TextSecondary,
                                        uncheckedTrackColor = bgCardInner
                                    ),
                                    modifier = Modifier.scale(0.8f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("KECEPATAN JOYSTICK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            // Speed selection chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    Triple("Walk", 5.0, "🚶"),
                                    Triple("Run", 16.0, "🏃"),
                                    Triple("Drive", 60.0, "🚗")
                                ).forEach { (label, value, emoji) ->
                                    val active = currentSpeed == value
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (active) CyberTheme.PrimaryAccent else bgCardInner)
                                            .clickable { MockLocationManager.speedKmh.value = value }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$emoji $label",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) textOnPrimaryAccent else CyberTheme.TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // MAP STYLE SELECTOR
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgCard),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("TAMPILAN PETA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            val styles = listOf(
                                "google_satellite" to "Satelit",
                                "google_road" to "Standard",
                                "carto_dark" to "Gelap"
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                styles.forEach { (styleId, label) ->
                                    val active = selectedTileStyle == styleId
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (active) CyberTheme.PrimaryAccent else bgCardInner)
                                            .clickable { selectedTileStyle = styleId }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (active) textOnPrimaryAccent else CyberTheme.TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SEARCH ADDRESS SECTION (Nominatim)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgCard),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        modifier = Modifier.fillMaxWidth().animateContentSize()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "CARI ALAMAT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberTheme.TextSecondary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Nama kota, jalan...", fontSize = 11.sp, color = CyberTheme.TextSecondary) },
                                    leadingIcon = { Icon(Icons.Default.Search, "Search", tint = CyberTheme.PrimaryAccent, modifier = Modifier.size(16.dp)) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = ""; searchResults = emptyList() }) {
                                                Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { performSearch(searchQuery) }),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = bgInput,
                                        unfocusedContainerColor = bgInput,
                                        focusedIndicatorColor = CyberTheme.PrimaryAccent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedTextColor = CyberTheme.TextPrimary,
                                        unfocusedTextColor = CyberTheme.TextPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .testTag("map_search_input"),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = { performSearch(searchQuery) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberTheme.PrimaryAccent),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(56.dp)
                                ) {
                                    if (isSearching) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = textOnPrimaryAccent, strokeWidth = 2.dp)
                                    } else {
                                        Text("Cari", fontWeight = FontWeight.Bold, color = textOnPrimaryAccent, fontSize = 11.sp)
                                    }
                                }
                            }

                            // Search Results dropdown
                            if (searchResults.isNotEmpty()) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = bgCardSelected),
                                    border = BorderStroke(1.dp, borderStrokeColor),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .heightIn(max = 240.dp)
                                ) {
                                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                        items(searchResults) { loc ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        MockLocationManager.latitude.value = loc.lat
                                                        MockLocationManager.longitude.value = loc.lng
                                                        addLocationToHistory(loc.lat, loc.lng)
                                                        searchResults = emptyList()
                                                        searchQuery = ""
                                                        Toast.makeText(context, "Terbang ke: ${loc.label}", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.LocationOn, "Pin", tint = CyberTheme.PrimaryAccent, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    val parts = loc.label.split(",")
                                                    val title = parts.firstOrNull()?.trim() ?: "Lokasi"
                                                    val subtitle = parts.drop(1).joinToString(", ").trim()
                                                    Text(
                                                        text = title,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = CyberTheme.TextPrimary,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                    if (subtitle.isNotEmpty()) {
                                                        Text(
                                                            text = subtitle,
                                                            fontSize = 9.sp,
                                                            color = CyberTheme.TextSecondary,
                                                            maxLines = 2,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // GO TO COORDINATES SECTION (Manual input)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgCard),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, borderStrokeColor),
                        modifier = Modifier.fillMaxWidth().animateContentSize()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "INPUT MOCK GPS COORDINATES",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberTheme.TextSecondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = latInput,
                                    onValueChange = { latInput = it },
                                    label = { Text("Latitude", fontSize = 9.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = CyberTheme.TextPrimary,
                                        unfocusedTextColor = CyberTheme.TextPrimary,
                                        focusedBorderColor = CyberTheme.PrimaryAccent,
                                        unfocusedBorderColor = borderStrokeColor,
                                        focusedLabelColor = CyberTheme.PrimaryAccent,
                                        unfocusedLabelColor = CyberTheme.TextSecondary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("lat_input_field"),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = lngInput,
                                    onValueChange = { lngInput = it },
                                    label = { Text("Longitude", fontSize = 9.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = CyberTheme.TextPrimary,
                                        unfocusedTextColor = CyberTheme.TextPrimary,
                                        focusedBorderColor = CyberTheme.PrimaryAccent,
                                        unfocusedBorderColor = borderStrokeColor,
                                        focusedLabelColor = CyberTheme.PrimaryAccent,
                                        unfocusedLabelColor = CyberTheme.TextSecondary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("lng_input_field"),
                                    singleLine = true
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    val latDouble = latInput.toDoubleOrNull()
                                    val lngDouble = lngInput.toDoubleOrNull()
                                    if (latDouble != null && lngDouble != null) {
                                        if (latDouble >= -90.0 && latDouble <= 90.0 && lngDouble >= -180.0 && lngDouble <= 180.0) {
                                            MockLocationManager.latitude.value = latDouble
                                            MockLocationManager.longitude.value = lngDouble
                                            addLocationToHistory(latDouble, lngDouble)
                                            
                                            // Fly map to new coordinates
                                            webViewInstance?.evaluateJavascript(
                                                "if (typeof map !== 'undefined' && typeof marker !== 'undefined') { map.setView([$latDouble, $lngDouble], 15); marker.setLatLng([$latDouble, $lngDouble]); }",
                                                null
                                            )
                                            
                                            // Auto-update search query & reverse geocode
                                            searchQuery = String.format(java.util.Locale.US, "%.6f, %.6f", latDouble, lngDouble)
                                            performReverseGeocode(latDouble, lngDouble)
                                            
                                            Toast.makeText(context, "Location Simulated Successfully!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Invalid range: Lat (-90 to 90), Lng (-180 to 180)", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Please enter valid decimal coordinates!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberTheme.PrimaryAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("go_coordinates_button")
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = "Set Location",
                                        tint = textOnPrimaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Set Location", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textOnPrimaryAccent)
                                }
                            }
                        }
                    }

                    if (isBatteryOptimizing) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                        Toast.makeText(context, "Cari 'Mandala Net' dan atur ke 'Jangan Batasi' / 'Don't Optimize'", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Gagal membuka pengaturan baterai", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberTheme.ErrorRed.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, CyberTheme.ErrorRed.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Battery Alert",
                                    tint = CyberTheme.ErrorRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "OPTIMALKAN BATERAI (MOCK SANGAT STABIL)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.ErrorRed
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // DEVELOPER INSTRUCTIONS CARD (CONTEXT-AWARE)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMockAllowed) {
                                CyberTheme.SuccessGreen.copy(alpha = 0.05f)
                            } else {
                                CyberTheme.ErrorRed.copy(alpha = 0.05f)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isMockAllowed) CyberTheme.SuccessGreen.copy(alpha = 0.4f) else CyberTheme.ErrorRed.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (isMockAllowed) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Success",
                                        tint = CyberTheme.SuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "MOCK ACCESS ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CyberTheme.SuccessGreen
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Aplikasi lokasi palsu terdeteksi aktif di Opsi Developer. Mandala Net siap mensimulasikan lokasi dengan performa maksimal!",
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp,
                                    color = CyberTheme.TextSecondary
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = CyberTheme.ErrorRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "SETUP OPSI DEVELOPER (BLOCKED)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CyberTheme.ErrorRed
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "1. Aktifkan Opsi Developer di pengaturan HP.\n2. Buka menu 'Pilih aplikasi lokasi palsu' (Mock Location App).\n3. Pilih 'Mandala Net'.",
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp,
                                    color = CyberTheme.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val devOptionsEnabled = try {
                                            Settings.Secure.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
                                        } catch (e: Exception) {
                                            false
                                        }
                                        if (devOptionsEnabled) {
                                            try {
                                                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                // Try to scroll directly to the mock location app setting (works on some Android versions)
                                                intent.putExtra(":settings:fragment_args_key", "mock_location_app")
                                                context.startActivity(intent)
                                                Toast.makeText(context, "Pilih Mandala Net sebagai aplikasi lokasi palsu.", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                    context.startActivity(intent)
                                                } catch (ex: Exception) {
                                                    Toast.makeText(context, "Buka pengaturan Developer secara manual", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, "Silakan aktifkan Mode Pengembang dengan mengetuk 'Nomor Bentukan' 7 kali.", Toast.LENGTH_LONG).show()
                                            try {
                                                val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                context.startActivity(intent)
                                            } catch (ex: Exception) {
                                                Toast.makeText(context, "Buka pengaturan Developer secara manual", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = bgCardInner),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                ) {
                                    Text("Buka Opsi Developer", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.PrimaryAccent)
                                }
                            }
                        }
                    }

                    } // End of Tab 0

                    if (selectedSidebarTab == 2) {
                        // RECENT LOCATIONS SECTION
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "RECENT LOCATIONS (LAST 10)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.TextSecondary
                                )
                                if (historyList.isNotEmpty()) {
                                    Text(
                                        "Hapus Semua",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberTheme.ErrorRed,
                                        modifier = Modifier.clickable {
                                            historyList.clear()
                                            historyPrefs.edit().remove("history_items").apply()
                                            Toast.makeText(context, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            if (historyList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .background(bgCard, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Belum ada riwayat lokasi",
                                        fontSize = 11.sp,
                                        color = CyberTheme.TextSecondary
                                    )
                                }
                            } else {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    historyList.forEachIndexed { idx, item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bgCard)
                                                .clickable {
                                                    MockLocationManager.latitude.value = item.lat
                                                    MockLocationManager.longitude.value = item.lng
                                                    val latVal = String.format(java.util.Locale.US, "%.6f", item.lat)
                                                    val lngVal = String.format(java.util.Locale.US, "%.6f", item.lng)
                                                    webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                                                    Toast.makeText(
                                                        context,
                                                        "Terbang ke: ${String.format("%.4f", item.lat)}, ${String.format("%.4f", item.lng)}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .background(CyberTheme.PrimaryAccent.copy(alpha = 0.1f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.History,
                                                        contentDescription = "History Icon",
                                                        tint = CyberTheme.PrimaryAccent,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "Lokasi ${idx + 1} (${formatTime(item.timestamp)})",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = CyberTheme.TextPrimary
                                                    )
                                                    Text(
                                                        text = "${String.format("%.6f", item.lat)}, ${String.format("%.6f", item.lng)}",
                                                        fontSize = 9.sp,
                                                        color = CyberTheme.TextSecondary
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val isBookmarked = bookmarks.any { b ->
                                                    Math.abs(b.lat - item.lat) < 0.0001 && Math.abs(b.lng - item.lng) < 0.0001
                                                }

                                                if (!isBookmarked) {
                                                    IconButton(
                                                        onClick = {
                                                            bookmarkNameInput = "Simpanan ${bookmarks.size + 1}"
                                                            MockLocationManager.latitude.value = item.lat
                                                            MockLocationManager.longitude.value = item.lng
                                                            val latVal = String.format(java.util.Locale.US, "%.6f", item.lat)
                                                            val lngVal = String.format(java.util.Locale.US, "%.6f", item.lng)
                                                            webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                                                            showSaveBookmarkDialog = true
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.BookmarkAdd,
                                                            contentDescription = "Simpan ke Favorit",
                                                            tint = CyberTheme.TextSecondary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Bookmark,
                                                        contentDescription = "Tersimpan",
                                                        tint = CyberTheme.PrimaryAccent,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // FAVORIT LOKASI / SAVED PLACES
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("FAVORIT LOKASI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                                Text(
                                    "+ Simpan Aktif",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.PrimaryAccent,
                                    modifier = Modifier.clickable {
                                        bookmarkNameInput = "Lokasi Saya ${bookmarks.size + 1}"
                                        showSaveBookmarkDialog = true
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            if (bookmarks.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(70.dp)
                                        .background(bgCard, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Belum ada lokasi tersimpan", fontSize = 11.sp, color = CyberTheme.TextSecondary)
                                }
                            } else {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    bookmarks.forEach { b ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bgCard)
                                                .clickable {
                                                    MockLocationManager.latitude.value = b.lat
                                                    MockLocationManager.longitude.value = b.lng
                                                    val latVal = String.format(java.util.Locale.US, "%.6f", b.lat)
                                                    val lngVal = String.format(java.util.Locale.US, "%.6f", b.lng)
                                                    webViewInstance?.evaluateJavascript("animateMapTo($latVal, $lngVal);", null)
                                                    addLocationToHistory(b.lat, b.lng)
                                                    Toast.makeText(context, "Terbang ke: ${b.label}", Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Bookmark,
                                                    contentDescription = "Bookmark",
                                                    tint = CyberTheme.PrimaryAccent,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(b.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary, maxLines = 1)
                                                    Text("${String.format("%.5f", b.lat)}, ${String.format("%.5f", b.lng)}", fontSize = 9.sp, color = CyberTheme.TextSecondary)
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Hapus",
                                                tint = CyberTheme.ErrorRed.copy(alpha = 0.8f),
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clickable {
                                                        sharedPrefs.edit().remove(b.label).apply()
                                                        bookmarks.remove(b)
                                                        Toast.makeText(context, "Favorit dihapus", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (selectedSidebarTab == 1) {
                        // ROUTE GPS WAYPOINTS
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("RUTE PERJALANAN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))

                            Card(
                                colors = CardDefaults.cardColors(containerColor = bgCard),
                                border = BorderStroke(1.dp, borderStrokeColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Titik Waypoint", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary)
                                        Text(
                                            "+ Tambah Titik Saat Ini",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberTheme.PrimaryAccent,
                                            modifier = Modifier.clickable {
                                                routeWaypoints.add(LocationBookmark("WP ${routeWaypoints.size + 1}", MockLocationManager.latitude.value, MockLocationManager.longitude.value))
                                            }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (routeWaypoints.isEmpty()) {
                                        Text("Belum ada waypoint. Tambahkan minimal 2 titik untuk simulasi rute.", fontSize = 9.sp, color = CyberTheme.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            routeWaypoints.forEachIndexed { idx, wp ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().background(bgCardInner, RoundedCornerShape(6.dp)).padding(8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${idx + 1}. ${String.format("%.4f", wp.lat)}, ${String.format("%.4f", wp.lng)}", fontSize = 9.sp, color = CyberTheme.TextPrimary)
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Hapus",
                                                        tint = CyberTheme.ErrorRed,
                                                        modifier = Modifier.size(14.dp).clickable { routeWaypoints.removeAt(idx) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Kecepatan Simulasi: ${simulationSpeed.toInt()} km/h", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary)
                                    androidx.compose.material3.Slider(
                                        value = simulationSpeed,
                                        onValueChange = { simulationSpeed = it },
                                        valueRange = 5f..150f,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = CyberTheme.PrimaryAccent,
                                            activeTrackColor = CyberTheme.PrimaryAccent,
                                            inactiveTrackColor = borderStrokeColor
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { 
                                            if (!isSimulatingRoute && routeWaypoints.size >= 2) {
                                                if (!isServiceActive) {
                                                    Toast.makeText(context, "Aktifkan layanan mock location dulu!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    isSimulatingRoute = true
                                                    Toast.makeText(context, "Simulasi Rute Dimulai", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                isSimulatingRoute = false
                                                MockLocationManager.isSimulatingRoute.value = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSimulatingRoute) CyberTheme.ErrorRed else CyberTheme.PrimaryAccent
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(40.dp)
                                    ) {
                                        Text(if (isSimulatingRoute) "Hentikan Simulasi" else "Mulai Simulasi Rute", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = textOnPrimaryAccent)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Preset Section
                            Text("PRESET RUTE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = bgCard),
                                border = BorderStroke(1.dp, borderStrokeColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { showLoadPresetDialog = true },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Muat Preset", fontSize = 10.sp, color = CyberTheme.PrimaryAccent)
                                        }
                                        TextButton(
                                            onClick = {
                                                if (routeWaypoints.size >= 2) {
                                                    presetNameInput = ""
                                                    showSavePresetDialog = true
                                                } else {
                                                    Toast.makeText(context, "Minimal 2 waypoint untuk disimpan", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Simpan Saat Ini", fontSize = 10.sp, color = CyberTheme.PrimaryAccent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            VerticalDivider(
                color = CyberTheme.PrimaryAccent.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxHeight()
            )
        }
    }

        // MAIN MAP INTERACTIVE AREA
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            
            // WEBVIEW EMBEDDED MAP
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        
                        // Enable mixed content and standard user agent
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36 MandalaNet/1.0"
                        
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                android.util.Log.d("MockLocationWebView", "Console: ${consoleMessage?.message()} at line ${consoleMessage?.lineNumber()}")
                                return true
                            }
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onMapSelected(lat: Double, lng: Double) {
                                coroutineScope.launch {
                                    MockLocationManager.isSimulatingRoute.value = false
                                    isSimulatingRoute = false
                                    MockLocationManager.latitude.value = lat
                                    MockLocationManager.longitude.value = lng
                                    searchQuery = String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng)
                                    performReverseGeocode(lat, lng)
                                    if (isServiceActive) {
                                        addLocationToHistory(lat, lng)
                                    }
                                }
                            }
                            @JavascriptInterface
                            fun onMapCenterChanged(lat: Double, lng: Double) {
                                coroutineScope.launch {
                                    mapCenterLat = lat
                                    mapCenterLng = lng
                                }
                            }
                            @JavascriptInterface
                            fun logError(error: String) {
                                android.util.Log.e("MockLocationWebView", "Leaflet JS Error: $error")
                            }
                        }, "AndroidBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val latVal = String.format(java.util.Locale.US, "%.6f", currentLat)
                                val lngVal = String.format(java.util.Locale.US, "%.6f", currentLng)
                                view?.evaluateJavascript("initMap($latVal, $lngVal, '$selectedTileStyle', ${isDark});", null)
                            }
                            
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null
                                
                                // Check if it's a tile request
                                val pattern = Pattern.compile("https?://mt\\d+\\.google\\.com/vt/lyrs=.*?&x=(\\d+)&y=(\\d+)&z=(\\d+)")
                                val matcher = pattern.matcher(url)
                                
                                if (matcher.find()) {
                                    val x = matcher.group(1)?.toIntOrNull()
                                    val y = matcher.group(2)?.toIntOrNull()
                                    val z = matcher.group(3)?.toIntOrNull()
                                    
                                    if (z != null && x != null && y != null) {
                                        if (OfflineMapService.isTileCached(context, z, x, y)) {
                                            val tileFile = java.io.File(OfflineMapService.getTileDirectory(context), "${z}_${x}_${y}.png")
                                            try {
                                                val inputStream = java.io.FileInputStream(tileFile)
                                                return WebResourceResponse("image/png", "UTF-8", inputStream).apply {
                                                    val headers = mutableMapOf<String, String>()
                                                    headers["Access-Control-Allow-Origin"] = "*"
                                                    responseHeaders = headers
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("MockLocationWebView", "Failed to load cached tile", e)
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        
                        loadDataWithBaseURL("https://mt1.google.com", mapHtml, "text/html", "UTF-8", null)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    // Dynamic updates are handled in LaunchedEffects safely
                }
            )

            // PERSISTENT SIMULATION ROUTE METRICS OVERLAY
            AnimatedVisibility(
                visible = isSimulatingRoute && routeTotalDistanceMeters > 0,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .padding(horizontal = 60.dp)
                    .zIndex(6f)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = bgFloatingPill),
                    border = BorderStroke(1.5.dp, CyberTheme.PrimaryAccent.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .widthIn(max = 450.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .testTag("route_simulation_metrics_overlay")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Title row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = "Simulasi Rute",
                                    tint = CyberTheme.PrimaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Simulasi Rute Aktif",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.TextPrimary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            
                            // Speed Badge
                            Box(
                                modifier = Modifier
                                    .background(CyberTheme.PrimaryAccent.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .border(1.dp, CyberTheme.PrimaryAccent.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${simulationSpeed.toInt()} km/h",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyberTheme.PrimaryAccent
                                )
                            }
                        }
                        
                        HorizontalDivider(color = borderStrokeColor.copy(alpha = 0.5f), thickness = 1.dp)
                        
                        // Metrics row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Distance Column
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "JARAK RUTE",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyberTheme.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val totalDistStr = if (routeTotalDistanceMeters >= 1000f) {
                                    String.format(java.util.Locale.US, "%.2f km", routeTotalDistanceMeters / 1000f)
                                } else {
                                    "${routeTotalDistanceMeters.toInt()} m"
                                }
                                Text(
                                    text = totalDistStr,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.TextPrimary
                                )
                            }
                            
                            VerticalDivider(color = borderStrokeColor.copy(alpha = 0.5f), modifier = Modifier.height(24.dp))
                            
                            // Duration Column
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "TOTAL DURASI",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyberTheme.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val hours = routeTotalDurationSeconds / 3600
                                val minutes = (routeTotalDurationSeconds % 3600) / 60
                                val seconds = routeTotalDurationSeconds % 60
                                val durationStr = if (hours > 0) {
                                    "${hours}j ${minutes}m"
                                } else {
                                    "${minutes}m ${seconds}s"
                                }
                                Text(
                                    text = durationStr,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.TextPrimary
                                )
                            }
                            
                            VerticalDivider(color = borderStrokeColor.copy(alpha = 0.5f), modifier = Modifier.height(24.dp))
                            
                            // Remaining Time Column
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "SISA WAKTU",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyberTheme.TextSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val remHours = routeRemainingSeconds / 3600
                                val remMinutes = (routeRemainingSeconds % 3600) / 60
                                val remSeconds = routeRemainingSeconds % 60
                                val remainingStr = if (remHours > 0) {
                                    "${remHours}j ${remMinutes}m"
                                } else if (remMinutes > 0) {
                                    "${remMinutes}m ${remSeconds}s"
                                } else {
                                    "${remSeconds}s"
                                }
                                Text(
                                    text = remainingStr,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyberTheme.SuccessGreen
                                )
                            }
                        }
                        
                        // Remaining Distance info and progress bar
                        val progress = if (routeTotalDistanceMeters > 0) {
                            ((routeTotalDistanceMeters - routeRemainingDistanceMeters) / routeTotalDistanceMeters).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val remDistStr = if (routeRemainingDistanceMeters >= 1000f) {
                                    String.format(java.util.Locale.US, "%.2f km", routeRemainingDistanceMeters / 1000f)
                                } else {
                                    "${routeRemainingDistanceMeters.toInt()} m"
                                }
                                Text(
                                    text = "Sisa Jarak: $remDistStr",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = CyberTheme.TextSecondary
                                )
                                
                                Text(
                                    text = "${(progress * 100).toInt()}% Selesai",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTheme.PrimaryAccent
                                )
                            }
                            
                            LinearProgressIndicator(
                                progress = { progress },
                                color = CyberTheme.PrimaryAccent,
                                trackColor = borderStrokeColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }

            // FLOATING TOP-RIGHT ACTIONS (Refresh Map & Center GPS)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        webViewInstance?.reload()
                        Toast.makeText(context, "Memuat ulang peta...", Toast.LENGTH_SHORT).show()
                    },
                    containerColor = bgFloatingPill,
                    contentColor = CyberTheme.PrimaryAccent,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .size(46.dp)
                        .border(1.dp, borderStrokeColor, RoundedCornerShape(12.dp))
                        .testTag("refresh_map_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Muat Ulang Peta",
                        modifier = Modifier.size(20.dp)
                    )
                }

                FloatingActionButton(
                    onClick = {
                        centerOnHardwareLocation()
                    },
                    containerColor = bgFloatingPill,
                    contentColor = CyberTheme.PrimaryAccent,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .size(46.dp)
                        .border(1.dp, borderStrokeColor, RoundedCornerShape(12.dp))
                        .testTag("center_hardware_location_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Pusat Lokasi GPS",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // FLOATING SIDEBAR TOGGLE BUTTON (Shows when sidebar is collapsed)
            if (!isSidebarExpanded) {
                FloatingActionButton(
                    onClick = { isSidebarExpanded = true },
                    containerColor = CyberTheme.PrimaryAccent,
                    contentColor = textOnPrimaryAccent,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Buka Menu",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // DYNAMIC MAP CENTER COORDINATES STATUS BAR
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = bgFloatingPill),
                border = BorderStroke(1.dp, borderStrokeColor),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
                    .wrapContentWidth()
                    .testTag("map_center_status_bar")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Pusat Peta",
                        tint = CyberTheme.PrimaryAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Pusat Peta: ${String.format(java.util.Locale.US, "%.6f", mapCenterLat)}, ${String.format(java.util.Locale.US, "%.6f", mapCenterLng)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyberTheme.TextPrimary
                    )
                }
            }

            // FLOATING ACTIVE TELEMETRY PILL & BOOKMARK ACTION
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Main telemetry info card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = bgFloatingPill),
                    border = BorderStroke(1.dp, borderStrokeColor),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isServiceActive) CyberTheme.SuccessGreen else Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isServiceActive) "MOCK AKTIF" else "MOCK STANDBY",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServiceActive) CyberTheme.SuccessGreen else CyberTheme.TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${String.format("%.5f", currentLat)}, ${String.format("%.5f", currentLng)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberTheme.PrimaryAccent
                            )
                        }

                        // Tap map instructions helper
                        Text(
                            text = "Geser pin / ketuk\npeta untuk memilih",
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyberTheme.TextSecondary,
                            textAlign = TextAlign.End
                        )
                    }
                }

                // Quick Save Bookmark FAB
                FloatingActionButton(
                    onClick = {
                        bookmarkNameInput = "Lokasi Saya ${bookmarks.size + 1}"
                        showSaveBookmarkDialog = true
                    },
                    containerColor = bgFloatingPill,
                    contentColor = CyberTheme.PrimaryAccent,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .size(46.dp)
                        .border(1.dp, borderStrokeColor, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "Simpan Favorit",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
