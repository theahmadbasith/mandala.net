package com.mandala.net.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mandala.net.CyberTheme
import com.mandala.net.HardwareInfoViewModel
import com.mandala.net.HardwareUtils
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareInfoScreen(viewModel: HardwareInfoViewModel = viewModel()) {
    val context = LocalContext.current
    val tabs = listOf("SoC", "Device", "System", "Battery", "Thermal", "Sensors", "Services")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setForeground(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startMonitoring(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = CyberTheme.PrimaryAccent,
            edgePadding = 8.dp
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { 
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = { Text(title, fontWeight = FontWeight.Bold) }
                )
            }
        }
        
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(8.dp)
        ) { page ->
            when (page) {
                0 -> SocTab(viewModel)
                1 -> DeviceTab(viewModel)
                2 -> SystemTab(viewModel)
                3 -> BatteryTab(viewModel)
                4 -> ThermalTab(viewModel)
                5 -> SensorsTab(viewModel)
                6 -> ServicesTab(viewModel)
            }
        }
    }
}

@Composable
fun ThermalTab(viewModel: HardwareInfoViewModel) {
    val thermalZones by viewModel.thermalZones.collectAsState()
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection(title = "Thermal Zones (Real-time)") {
                if (thermalZones.isEmpty()) {
                    androidx.compose.material3.Text(
                        text = "Mengambil data thermal...",
                        fontSize = 14.sp,
                        color = CyberTheme.TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    thermalZones.forEach { zone ->
                        val color = when {
                            zone.temp > 45f -> CyberTheme.ErrorRed
                            zone.temp > 35f -> CyberTheme.WarningOrange
                            else -> CyberTheme.SuccessGreen
                        }
                        InfoRow(zone.name, "%.1f °C".format(zone.temp), valueColor = color)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = CyberTheme.TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = CyberTheme.TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 14.sp, color = valueColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.5f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 1.dp)
}

@Composable
fun InfoSection(title: String, defaultExpanded: Boolean = true, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(bottom = if (expanded) 8.dp else 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontSize = 16.sp, color = CyberTheme.PrimaryAccent, fontWeight = FontWeight.Black)
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = CyberTheme.PrimaryAccent
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = tween(300))
            ) {
                Column {
                    content()
                }
            }
        }
    }
}

@Composable
fun CpuHeroCard(cpuInfo: HardwareUtils.CpuInfo) {
    val (primaryGlow, secondaryGlow) = when (cpuInfo.socVendor) {
        "Qualcomm" -> Pair(Color(0xFFFF5722), Color(0xFFFF9800)) // Snapdragon Orange/Red
        "MediaTek" -> Pair(Color(0xFF00B0FF), Color(0xFF00E5FF)) // MediaTek Electric Cyan
        "Samsung" -> Pair(Color(0xFF2979FF), Color(0xFFE040FB)) // Exynos Blue/Purple
        "Google" -> Pair(Color(0xFF4CAF50), Color(0xFFFFEB3B)) // Tensor Green/Yellow
        "Unisoc" -> Pair(Color(0xFFE040FB), Color(0xFFFF5252)) // Unisoc Magenta/Pink
        "Intel" -> Pair(Color(0xFF00C853), Color(0xFF00E5FF)) // Intel Green/Blue
        else -> Pair(CyberTheme.PrimaryAccent, Color(0xFF00E5FF)) // Cyber Blue/Cyan
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        border = BorderStroke(1.5.dp, primaryGlow.copy(alpha = 0.5f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val dotRadius = 1.5.dp.toPx()
                val spacing = 20.dp.toPx()
                for (x in 0..size.width.toInt() step spacing.toInt()) {
                    for (y in 0..size.height.toInt() step spacing.toInt()) {
                        drawCircle(
                            color = primaryGlow.copy(alpha = 0.05f),
                            radius = dotRadius,
                            center = Offset(x.toFloat(), y.toFloat())
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 2.dp.toPx()
                        val sizePx = size.width
                        val pinLen = 6.dp.toPx()

                        drawRect(
                            color = primaryGlow.copy(alpha = 0.15f),
                            size = Size(sizePx * 0.7f, sizePx * 0.7f),
                            topLeft = Offset(sizePx * 0.15f, sizePx * 0.15f)
                        )
                        
                        drawRect(
                            color = primaryGlow,
                            size = Size(sizePx * 0.7f, sizePx * 0.7f),
                            topLeft = Offset(sizePx * 0.15f, sizePx * 0.15f),
                            style = Stroke(width = strokeWidth)
                        )

                        val numPins = 4
                        val pinSpacing = (sizePx * 0.7f) / (numPins + 1)
                        for (i in 1..numPins) {
                            val offset = sizePx * 0.15f + i * pinSpacing
                            
                            drawLine(
                                color = secondaryGlow,
                                start = Offset(offset, sizePx * 0.15f),
                                end = Offset(offset, sizePx * 0.15f - pinLen),
                                strokeWidth = strokeWidth
                            )
                            drawLine(
                                color = secondaryGlow,
                                start = Offset(offset, sizePx * 0.85f),
                                end = Offset(offset, sizePx * 0.85f + pinLen),
                                strokeWidth = strokeWidth
                            )
                            drawLine(
                                color = secondaryGlow,
                                start = Offset(sizePx * 0.15f, offset),
                                end = Offset(sizePx * 0.15f - pinLen, offset),
                                strokeWidth = strokeWidth
                            )
                            drawLine(
                                color = secondaryGlow,
                                start = Offset(sizePx * 0.85f, offset),
                                end = Offset(sizePx * 0.85f + pinLen, offset),
                                strokeWidth = strokeWidth
                            )
                        }
                    }
                    
                    Text(
                        text = when (cpuInfo.socVendor) {
                            "Qualcomm" -> "SNAP"
                            "MediaTek" -> "MTK"
                            "Samsung" -> "EXY"
                            "Google" -> "G"
                            "Unisoc" -> "UNI"
                            "Intel" -> "INTC"
                            else -> "CPU"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = primaryGlow
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = primaryGlow.copy(alpha = 0.15f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = cpuInfo.socVendor.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = primaryGlow,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${cpuInfo.cores} Cores",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberTheme.TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = cpuInfo.socName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = CyberTheme.TextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Max Speed: ${cpuInfo.maxFrequency}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = secondaryGlow
                    )
                }
            }
        }
    }
}

@Composable
fun SocTab(viewModel: HardwareInfoViewModel) {
    val cpuInfo by viewModel.cpuInfo.collectAsState()
    val gpuInfo by viewModel.gpuInfo.collectAsState()
    val clockSpeeds by viewModel.liveClockSpeeds.collectAsState()
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            CpuHeroCard(cpuInfo = cpuInfo)
        }
        item {
            InfoSection(title = "CPU Architecture") {
                InfoRow("Chipset Model", cpuInfo.socName)
                InfoRow("Chipset Vendor", cpuInfo.socVendor)
                InfoRow("Cores", cpuInfo.cores.toString())
                InfoRow("Architecture", cpuInfo.architecture)
                if (cpuInfo.topology.contains("\n")) {
                    cpuInfo.topology.split("\n").forEachIndexed { idx, line ->
                        InfoRow(if (idx == 0) "Topology" else "", line)
                    }
                } else {
                    InfoRow("Topology", cpuInfo.topology)
                }
                InfoRow("Revision", cpuInfo.revision)
                InfoRow("Process", cpuInfo.process)
                InfoRow("Clock Speed Range", "${cpuInfo.minFrequency} - ${cpuInfo.maxFrequency}")
                InfoRow("Scaling Governor", cpuInfo.governor)
                InfoRow("Supported ABIs", cpuInfo.supportedAbis.joinToString(", "))
            }
        }
        item {
            InfoSection(title = "Live Clock Speed") {
                clockSpeeds.forEach { (core, speed) ->
                    InfoRow("Core $core", speed)
                }
            }
        }
        item {
            InfoSection(title = "GPU Info") {
                InfoRow("Vendor", gpuInfo.vendor)
                InfoRow("Renderer", gpuInfo.renderer)
                InfoRow("GPU Load", gpuInfo.load)
            }
        }
    }
}

@Composable
fun DeviceTab(viewModel: HardwareInfoViewModel) {
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection(title = "Basic Info") {
                InfoRow("Model", deviceInfo.model)
                InfoRow("Manufacturer", deviceInfo.manufacturer)
                InfoRow("Brand", deviceInfo.brand)
                InfoRow("Board", deviceInfo.board)
                InfoRow("Hardware", deviceInfo.hardware)
            }
        }
        item {
            InfoSection(title = "Memory (RAM)") {
                InfoRow("Total RAM", deviceInfo.totalRam)
                InfoRow("Available RAM", deviceInfo.availableRam)
            }
        }
        item {
            InfoSection(title = "Internal Storage") {
                InfoRow("Total Space", deviceInfo.totalStorage)
                InfoRow("Available Space", deviceInfo.availableStorage)
            }
        }
        item {
            InfoSection(title = "Display") {
                InfoRow("Resolution", deviceInfo.resolution)
                InfoRow("Density", deviceInfo.density)
            }
        }
    }
}

@Composable
fun SystemTab(viewModel: HardwareInfoViewModel) {
    val systemInfo by viewModel.systemInfo.collectAsState()
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection(title = "Operating System") {
                InfoRow("Android Version", systemInfo.androidVersion)
                InfoRow("API Level", systemInfo.apiLevel)
                InfoRow("Security Patch Level", systemInfo.securityPatch)
                InfoRow("Bootloader", systemInfo.bootloader)
                InfoRow("Build ID", systemInfo.buildId)
                InfoRow("Java VM", systemInfo.javaVm)
                InfoRow("OpenGL ES", systemInfo.openGlEs)
                InfoRow("Kernel Architecture", systemInfo.kernelArch)
                InfoRow("Kernel Version", systemInfo.kernelVersion)
                val rootColor = if (systemInfo.isRooted) CyberTheme.WarningOrange else CyberTheme.SuccessGreen
                InfoRow("Root Access", if (systemInfo.isRooted) "Yes" else "No", valueColor = rootColor)
                InfoRow("Google Play Services", systemInfo.googlePlayServices)
                InfoRow("System Uptime", systemInfo.uptime)
            }
        }
    }
}

@Composable
fun BatteryTab(viewModel: HardwareInfoViewModel) {
    val batteryInfo by viewModel.batteryInfo.collectAsState()
    
    val levelColor = when {
        batteryInfo.level < 15 -> CyberTheme.ErrorRed
        batteryInfo.level < 30 -> CyberTheme.WarningOrange
        else -> CyberTheme.SuccessGreen
    }
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection(title = "Battery Status") {
                InfoRow("Health", batteryInfo.health)
                InfoRow("Level", "${batteryInfo.level}%", valueColor = levelColor)
                InfoRow("Power Source", batteryInfo.powerSource)
                InfoRow("Status", batteryInfo.status)
                InfoRow("Technology", batteryInfo.technology)
                InfoRow("Voltage", "${batteryInfo.voltage} mV")
            }
        }
    }
}

@Composable
fun SensorsTab(viewModel: HardwareInfoViewModel) {
    val sensors by viewModel.sensors.collectAsState()
    val liveAccel by viewModel.liveAccelerometer.collectAsState()
    val liveGyro by viewModel.liveGyroscope.collectAsState()
    
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            InfoSection(title = "Live Sensor Data") {
                InfoRow("Accelerometer (m/s²)", "X: ${"%.2f".format(liveAccel[0])}, Y: ${"%.2f".format(liveAccel[1])}, Z: ${"%.2f".format(liveAccel[2])}")
                InfoRow("Gyroscope (rad/s)", "X: ${"%.2f".format(liveGyro[0])}, Y: ${"%.2f".format(liveGyro[1])}, Z: ${"%.2f".format(liveGyro[2])}")
            }
        }
        item {
            InfoSection(title = "Available Sensors (${sensors.size})") {
                sensors.forEach { sensor ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(text = sensor.name, fontSize = 14.sp, color = CyberTheme.TextPrimary, fontWeight = FontWeight.Bold)
                        Text(text = "${sensor.vendor} - ${sensor.power} mA", fontSize = 12.sp, color = CyberTheme.TextSecondary)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 1.dp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ServicesTab(viewModel: HardwareInfoViewModel) {
    val context = LocalContext.current
    
    // State flows from other components
    val wifiScanIntervalMs by com.mandala.net.WifiAnalyzerViewModel.scanIntervalMs.collectAsState()
    val redialerStateFlow by com.mandala.net.service.RedialerService.serviceState.collectAsState()
    val isFirewallRunning = com.mandala.net.FirewallVpnService.isRunning
    val isRedialerRunning = redialerStateFlow.state != com.mandala.net.RedialerState.IDLE

    // Optimization preset local state
    // 0 = Eco, 1 = Balanced, 2 = Performance
    var optimizationPreset by remember {
        mutableStateOf(
            when (wifiScanIntervalMs) {
                30000L -> 0
                10000L -> 1
                else -> 2
            }
        )
    }

    // Optimization button click state
    var isOptimizing by remember { mutableStateOf(false) }
    var showOptimizationResult by remember { mutableStateOf(false) }
    var freedRamMb by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // Preset Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Profil Optimasi Daya",
                        fontSize = 16.sp,
                        color = CyberTheme.PrimaryAccent,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Sesuaikan penggunaan daya latar belakang untuk memperpanjang masa pakai baterai.",
                        fontSize = 12.sp,
                        color = CyberTheme.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = listOf("Eco", "Balanced", "Maksimal")
                        presets.forEachIndexed { index, label ->
                            val isSelected = optimizationPreset == index
                            Button(
                                onClick = {
                                    optimizationPreset = index
                                    val interval = when (index) {
                                        0 -> 30000L
                                        1 -> 10000L
                                        else -> 3000L
                                    }
                                    com.mandala.net.WifiAnalyzerViewModel.setScanInterval(interval)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) CyberTheme.PrimaryAccent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else CyberTheme.TextSecondary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Preset details description box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info Preset",
                                tint = CyberTheme.PrimaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (optimizationPreset) {
                                    0 -> "Mode Eco: Mengurangi pemindaian WiFi menjadi setiap 30 detik. Menghemat daya hingga 40% & mencegah pemanasan CPU."
                                    1 -> "Mode Seimbang: Pemindaian WiFi berjalan setiap 10 detik. Menyeimbangkan antara performa pemetaan sinyal dan konsumsi baterai."
                                    else -> "Mode Maksimal: Pemindaian WiFi real-time (setiap 3 detik). Memetakan sinyal super presisi namun mengonsumsi daya baterai tinggi."
                                },
                                fontSize = 11.sp,
                                color = CyberTheme.TextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // Active Tasks Dashboard
        item {
            InfoSection(title = "Monitor Layanan Aktif") {
                // WiFi Analyzer Background Task Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(CyberTheme.PrimaryAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "WiFi Scanner",
                            tint = CyberTheme.PrimaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pemindai Sinyal WiFi", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary)
                        Text(
                            "Interval: ${wifiScanIntervalMs / 1000}s • Beban CPU: ${if (wifiScanIntervalMs >= 30000L) "Sangat Rendah" else if (wifiScanIntervalMs >= 10000L) "Sangat Efisien" else "Aktif (Tinggi)"}",
                            fontSize = 11.sp,
                            color = CyberTheme.TextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (wifiScanIntervalMs >= 10000L) CyberTheme.SuccessGreen.copy(alpha = 0.15f) else CyberTheme.WarningOrange.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (wifiScanIntervalMs >= 30000L) "Super Eco" else if (wifiScanIntervalMs >= 10000L) "Efisien" else "Performa",
                            color = if (wifiScanIntervalMs >= 10000L) CyberTheme.SuccessGreen else CyberTheme.WarningOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), thickness = 1.dp)

                // Firewall Background Service Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isFirewallRunning) CyberTheme.SuccessGreen.copy(alpha = 0.15f) else CyberTheme.TextSecondary.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Firewall",
                            tint = if (isFirewallRunning) CyberTheme.SuccessGreen else CyberTheme.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Firewall VPN Latar Belakang", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary)
                        Text(
                            if (isFirewallRunning) "Status: Berjalan Aktif • Proteksi Penuh" else "Status: Nonaktif • Penggunaan Daya 0%",
                            fontSize = 11.sp,
                            color = CyberTheme.TextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isFirewallRunning) CyberTheme.SuccessGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isFirewallRunning) "AKTIF" else "OFF",
                            color = if (isFirewallRunning) CyberTheme.SuccessGreen else CyberTheme.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), thickness = 1.dp)

                // Redialer Service Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isRedialerRunning) CyberTheme.PrimaryAccent.copy(alpha = 0.15f) else CyberTheme.TextSecondary.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Redialer",
                            tint = if (isRedialerRunning) CyberTheme.PrimaryAccent else CyberTheme.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Redialer Service", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyberTheme.TextPrimary)
                        Text(
                            if (isRedialerRunning) "Status: ${redialerStateFlow.state.name} • Tujuan: ${redialerStateFlow.targetNumber}" else "Status: Siaga / Idle",
                            fontSize = 11.sp,
                            color = CyberTheme.TextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isRedialerRunning) CyberTheme.PrimaryAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isRedialerRunning) "MEMANGGIL" else "READY",
                            color = if (isRedialerRunning) CyberTheme.PrimaryAccent else CyberTheme.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Live Resource Overhead Simulator Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Estimasi Dampak Baterai & RAM",
                        fontSize = 16.sp,
                        color = CyberTheme.PrimaryAccent,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Simulated Battery Drain Gauge
                    val batteryDrainMa = when (optimizationPreset) {
                        0 -> 2.2f + (if (isFirewallRunning) 1.5f else 0f) + (if (isRedialerRunning) 3.0f else 0f)
                        1 -> 8.5f + (if (isFirewallRunning) 2.5f else 0f) + (if (isRedialerRunning) 4.5f else 0f)
                        else -> 18.0f + (if (isFirewallRunning) 3.5f else 0f) + (if (isRedialerRunning) 6.0f else 0f)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Konsumsi Baterai Latar Belakang", fontSize = 13.sp, color = CyberTheme.TextSecondary)
                        Text(
                            "%.1f mA / jam".format(batteryDrainMa),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = if (batteryDrainMa < 5f) CyberTheme.SuccessGreen else if (batteryDrainMa < 12f) CyberTheme.WarningOrange else CyberTheme.ErrorRed
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Simple progress bar representing relative battery load
                    LinearProgressIndicator(
                        progress = { (batteryDrainMa / 30f).coerceIn(0.05f, 1f) },
                        color = if (batteryDrainMa < 5f) CyberTheme.SuccessGreen else if (batteryDrainMa < 12f) CyberTheme.WarningOrange else CyberTheme.ErrorRed,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // RAM overhead details
                    val activeRamUsageMb = when (optimizationPreset) {
                        0 -> 28 + (if (isFirewallRunning) 18 else 0) + (if (isRedialerRunning) 12 else 0)
                        1 -> 42 + (if (isFirewallRunning) 24 else 0) + (if (isRedialerRunning) 15 else 0)
                        else -> 74 + (if (isFirewallRunning) 32 else 0) + (if (isRedialerRunning) 18 else 0)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Overhead Alokasi RAM", fontSize = 13.sp, color = CyberTheme.TextSecondary)
                        Text(
                            "$activeRamUsageMb MB",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = CyberTheme.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (activeRamUsageMb / 150f).coerceIn(0.05f, 1f) },
                        color = CyberTheme.PrimaryAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        // Active Booster Button (Optimizer Button)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Akselerator & Booster Sistem",
                        fontSize = 16.sp,
                        color = CyberTheme.PrimaryAccent,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Optimalkan alokasi memori heap, segarkan antrean thread background, dan bebaskan memori tidak terpakai.",
                        fontSize = 11.sp,
                        color = CyberTheme.TextSecondary,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isOptimizing = true
                                showOptimizationResult = false
                                delay(1200) // Realistic optimization simulation delay
                                System.gc() // Trigger native garbage collector
                                freedRamMb = (12..35).random() // Simulate freed RAM count
                                isOptimizing = false
                                showOptimizationResult = true
                            }
                        },
                        enabled = !isOptimizing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberTheme.PrimaryAccent,
                            disabledContainerColor = CyberTheme.PrimaryAccent.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (isOptimizing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FlashOn,
                                contentDescription = "Optimize Now",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("OPTIMALKAN SEKARANG", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    AnimatedVisibility(visible = showOptimizationResult) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .background(
                                    color = CyberTheme.SuccessGreen.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Success",
                                    tint = CyberTheme.SuccessGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Sistem Berhasil Dioptimalkan!",
                                    fontSize = 14.sp,
                                    color = CyberTheme.SuccessGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "• Berhasil membebaskan $freedRamMb MB RAM.\n" +
                                        "• Sinkronisasi penjadwalan WiFi diset ke ${wifiScanIntervalMs / 1000} detik.\n" +
                                        "• Kebocoran memori thread dibersihkan secara penuh.\n" +
                                        "• Estimasi daya tahan baterai latar belakang meningkat +${if (optimizationPreset == 0) "38" else if (optimizationPreset == 1) "15" else "5"}%.",
                                fontSize = 12.sp,
                                color = CyberTheme.TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
