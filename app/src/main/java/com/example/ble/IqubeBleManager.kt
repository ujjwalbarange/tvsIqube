package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.example.model.BleConnectionPhase
import com.example.model.ClusterAlertType
import com.example.model.GpsBleFusionSample
import com.example.model.IqubeBleSpec
import com.example.model.LastParkedLocation
import com.example.model.NavigationTurnIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full Lifecycle BLE GATT & Telemetry Controller for TVS iQube (`F8:55:48:72:96:DA`).
 *
 * Implements:
 * 1. 6-Step Initialization State Machine:
 *    Scan -> Connect GATT -> Discover Services (Static or Dynamic Service 3 matching) ->
 *    Write `0x0100` to CCCD `0x2902` -> Request MTU `517` (negotiates `247`) ->
 *    Send `[RU` Handshake at `+1.45s` -> Start Periodic `[JD` Heartbeat & Telemetry Stream.
 * 2. Link Watchdog & Supervision Timeout (`0x08`) Detection:
 *    Monitors packet silence (`>2,500 ms`) and write ACK timeout (`>500 ms`), surfaces a
 *    Link Degraded banner, and pauses background `[JD` heartbeats.
 * 3. Exponential Backoff Auto-Reconnect & GATT Error 133 Prevention:
 *    Always calls `gatt.disconnect()` followed by `gatt.close()` and nullifies references
 *    before scheduling a 30-second bounded reconnect attempt.
 * 4. GPS + BLE Speed Fusion & Automatic "Last Parked Location" Capture on Disconnect.
 */
class IqubeBleManager(
    private val context: Context,
    val packetParser: IqubePacketParser = IqubePacketParser()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var scanJob: Job? = null
    private var replayJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttemptCount = 0

    // Connection & Link StateFlows
    private val _connectionPhase = MutableStateFlow(BleConnectionPhase.DISCONNECTED)
    val connectionPhase: StateFlow<BleConnectionPhase> = _connectionPhase.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow(IqubeBleSpec.DEFAULT_DEVICE_ALIAS)
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceMac = MutableStateFlow(IqubeBleSpec.TARGET_MAC_ADDRESS)
    val connectedDeviceMac: StateFlow<String> = _connectedDeviceMac.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _negotiatedMtu = MutableStateFlow<Int?>(null)
    val negotiatedMtu: StateFlow<Int?> = _negotiatedMtu.asStateFlow()

    private val _discoveredServiceUuid = MutableStateFlow<String>(IqubeBleSpec.SERVICE_UUID_SMARTXONNECT)
    val discoveredServiceUuid: StateFlow<String> = _discoveredServiceUuid.asStateFlow()

    private val _isLinkDegraded = MutableStateFlow(false)
    val isLinkDegraded: StateFlow<Boolean> = _isLinkDegraded.asStateFlow()

    private val _linkWarningMessage = MutableStateFlow<String?>(null)
    val linkWarningMessage: StateFlow<String?> = _linkWarningMessage.asStateFlow()

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val _heartbeatEnabled = MutableStateFlow(true)
    val heartbeatEnabled: StateFlow<Boolean> = _heartbeatEnabled.asStateFlow()

    private val _fragmentedSliceTestEnabled = MutableStateFlow(false)
    val fragmentedSliceTestEnabled: StateFlow<Boolean> = _fragmentedSliceTestEnabled.asStateFlow()

    // Replay customization state for testing Trip Reset / Lamp toggle in HCI Replay mode
    private var replayTripATenths = 184
    private var replayTripBTenths = 62
    private var replayOdoTenths = 17504
    private var replayLampOn = false
    private var replayCharging = false

    // GPS + BLE Ride Logger & Last Parked State
    private val _currentGpsSpeedKmh = MutableStateFlow<Double?>(null)
    val currentGpsSpeedKmh: StateFlow<Double?> = _currentGpsSpeedKmh.asStateFlow()

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

    private val _lastParkedLocation = MutableStateFlow<LastParkedLocation?>(null)
    val lastParkedLocation: StateFlow<LastParkedLocation?> = _lastParkedLocation.asStateFlow()

    private val _gpsFusionSamples = MutableStateFlow<List<GpsBleFusionSample>>(emptyList())
    val gpsFusionSamples: StateFlow<List<GpsBleFusionSample>> = _gpsFusionSamples.asStateFlow()

    val commandDispatcher = IqubeCommandDispatcher(
        scope = scope,
        performGattWrite = ::performHardwareOrReplayWrite,
        onWriteAckTimeout = ::onWriteAckTimeoutDetected
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _lastKnownLocation.value = location
            val gpsKmh = if (location.hasSpeed()) {
                ((location.speed * 3.6) * 10.0).roundToInt() / 10.0
            } else 0.0
            _currentGpsSpeedKmh.value = gpsKmh

            val tele = packetParser.telemetryState.value
            val wheelSpeed = tele.speedKmh
            val sample = GpsBleFusionSample(
                timestampMs = System.currentTimeMillis(),
                wheelSpeedKmh = wheelSpeed,
                gpsSpeedKmh = gpsKmh,
                deltaKmh = if (wheelSpeed != null) ((wheelSpeed - gpsKmh) * 10.0).roundToInt() / 10.0 else null,
                batteryPercent = tele.batteryPercent,
                latitude = location.latitude,
                longitude = location.longitude
            )
            _gpsFusionSamples.update { (listOf(sample) + it).take(35) }
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        _autoReconnectEnabled.value = enabled
        if (!enabled) {
            reconnectJob?.cancel()
        }
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        _heartbeatEnabled.value = enabled
    }

    fun setFragmentedSliceTestEnabled(enabled: Boolean) {
        _fragmentedSliceTestEnabled.value = enabled
    }

    fun setCustomServiceUuid(uuidString: String) {
        val trimmed = uuidString.trim()
        if (trimmed.isNotEmpty()) {
            _discoveredServiceUuid.value = trimmed
        }
    }

    fun toggleReplayChargingState() {
        replayCharging = !replayCharging
    }

    /**
     * Starts GPS tracking if location permission has been granted by the user.
     */
    @SuppressLint("MissingPermission")
    fun startGpsTelemetryFusion() {
        try {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val providers = locManager.getProviders(true)
            for (provider in providers) {
                locManager.getLastKnownLocation(provider)?.let { loc ->
                    if (_lastKnownLocation.value == null) {
                        _lastKnownLocation.value = loc
                    }
                }
            }
            if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            } else if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            // Permission not yet granted
        } catch (_: Exception) {
        }
    }

    /**
     * Initiates real BLE scan & GATT connection to TVS iQube (`F8:55:48:72:96:DA` or SmartXonnect UUID).
     */
    @SuppressLint("MissingPermission")
    fun startBleScanAndConnect() {
        stopReplayModeIfNeeded()
        reconnectJob?.cancel()

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _linkWarningMessage.value = "Bluetooth adapter is disabled or unavailable on this device."
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            return
        }

        _isLinkDegraded.value = false
        _linkWarningMessage.value = null
        _connectionPhase.value = BleConnectionPhase.SCANNING

        // Step 1A: Try direct MAC address lookup if already bonded/cached
        try {
            val directDevice = adapter.getRemoteDevice(IqubeBleSpec.TARGET_MAC_ADDRESS)
            if (directDevice != null && directDevice.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                connectToGattDevice(directDevice)
                return
            }
        } catch (_: Exception) {
        }

        // Step 1B: Perform BLE Scanner discovery (30-second bounded window per HCI spec)
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _linkWarningMessage.value = "BLE Scanner unavailable. Check Bluetooth state."
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                scanner.startScan(null, scanSettings, bleScanCallback)
                delay(15_000L)
                if (_connectionPhase.value == BleConnectionPhase.SCANNING) {
                    scanner.stopScan(bleScanCallback)
                    _linkWarningMessage.value =
                        "No TVS iQube (`${IqubeBleSpec.TARGET_MAC_ADDRESS}`) found in range. Use 'Replay btsnooz_hci.log' to inspect captured scooter frames."
                    _connectionPhase.value = BleConnectionPhase.DISCONNECTED
                }
            } catch (e: SecurityException) {
                _linkWarningMessage.value = "Bluetooth scan permission required."
                _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            } catch (e: Exception) {
                _linkWarningMessage.value = "BLE Scan error: ${e.localizedMessage}"
                _connectionPhase.value = BleConnectionPhase.DISCONNECTED
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val mac = device.address ?: ""
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()

            val matchesTarget = mac.equals(IqubeBleSpec.TARGET_MAC_ADDRESS, ignoreCase = true) ||
                name.contains("TVS", ignoreCase = true) ||
                name.contains("iQube", ignoreCase = true) ||
                serviceUuids.contains(IqubeBleSpec.SERVICE_UUID_SMARTXONNECT.lowercase())

            if (matchesTarget && _connectionPhase.value == BleConnectionPhase.SCANNING) {
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                } catch (_: Exception) {
                }
                scanJob?.cancel()
                _rssiDbm.value = result.rssi
                connectToGattDevice(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToGattDevice(device: BluetoothDevice) {
        closeGattCleanly()
        _connectedDeviceMac.value = device.address ?: IqubeBleSpec.TARGET_MAC_ADDRESS
        _connectedDeviceName.value = device.name ?: IqubeBleSpec.DEFAULT_DEVICE_ALIAS
        _connectionPhase.value = BleConnectionPhase.CONNECTING_GATT

        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttemptCount = 0
                _isLinkDegraded.value = false
                _linkWarningMessage.value = null
                _connectionPhase.value = BleConnectionPhase.DISCOVERING_SERVICES
                gatt.readRemoteRssi()
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val isSupervisionTimeout = (status == 0x08) // BLE HCI Connection Timeout (0x08)
                handleHardwareDisconnect(
                    reason = if (isSupervisionTimeout) {
                        "Disconnected: Link Supervision Timeout (0x08)"
                    } else {
                        "Disconnected (GATT status 0x${Integer.toHexString(status)})"
                    }
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleHardwareDisconnect("Service discovery failed (0x${Integer.toHexString(status)})")
                return
            }

            // Locate Service 3 either by known UUID or Dynamic Service 3 matching (128-bit service
            // containing Notify char with 0x2902 CCCD + Write char with 0x2901 CUD)
            var foundNotify: BluetoothGattCharacteristic? = null
            var foundWrite: BluetoothGattCharacteristic? = null

            val targetServiceUuid = runCatching { UUID.fromString(_discoveredServiceUuid.value) }.getOrNull()
            val primaryService = targetServiceUuid?.let { gatt.getService(it) }

            if (primaryService != null) {
                foundNotify = primaryService.getCharacteristic(UUID.fromString(IqubeBleSpec.CHAR_UUID_TELEMETRY_NOTIFY))
                foundWrite = primaryService.getCharacteristic(UUID.fromString(IqubeBleSpec.CHAR_UUID_COMMAND_WRITE))
            }

            // Dynamic Service 3 Fallback Discovery
            if (foundNotify == null || foundWrite == null) {
                for (service in gatt.services) {
                    val chars = service.characteristics
                    val notifyCandidate = chars.firstOrNull { c ->
                        (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 &&
                            c.getDescriptor(UUID.fromString(IqubeBleSpec.DESCRIPTOR_UUID_CCCD)) != null
                    }
                    val writeCandidate = chars.firstOrNull { c ->
                        (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }
                    if (notifyCandidate != null && writeCandidate != null) {
                        foundNotify = notifyCandidate
                        foundWrite = writeCandidate
                        _discoveredServiceUuid.value = service.uuid.toString()
                        break
                    }
                }
            }

            if (foundNotify == null || foundWrite == null) {
                handleHardwareDisconnect("SmartXonnect Notify (0x5354) / Write (0x5352) characteristics not found")
                return
            }

            notifyCharacteristic = foundNotify
            writeCharacteristic = foundWrite

            // Step 3: Enable Telemetry Notifications (Write 0x0100 to CCCD 0x001F / 0x2902)
            _connectionPhase.value = BleConnectionPhase.ENABLING_CCCD
            gatt.setCharacteristicNotification(foundNotify, true)
            val cccd = foundNotify.getDescriptor(UUID.fromString(IqubeBleSpec.DESCRIPTOR_UUID_CCCD))
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                // Proceed directly to Step 4 MTU negotiation if CCCD is implicit
                _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
                gatt.requestMtu(IqubeBleSpec.REQUESTED_MTU)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid.toString().equals(IqubeBleSpec.DESCRIPTOR_UUID_CCCD, ignoreCase = true)) {
                // Step 4: Request MTU 517 (negotiates to 247 bytes on TI BLE 4.2 controller)
                _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
                gatt.requestMtu(IqubeBleSpec.REQUESTED_MTU)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _negotiatedMtu.value = if (status == BluetoothGatt.GATT_SUCCESS) mtu else IqubeBleSpec.EXPECTED_NEGOTIATED_MTU
            // Step 5: Send Initial Handshake `[RU` (`5B 52 55...`) at +1.45s and start sync loop
            _connectionPhase.value = BleConnectionPhase.SENDING_HANDSHAKE
            scope.launch {
                delay(1450L)
                commandDispatcher.enqueueCommand(
                    label = "Handshake [RU (0x0022)",
                    rawPayload = commandDispatcher.buildInitialHandshakeFrame()
                )
                _connectionPhase.value = BleConnectionPhase.STREAMING_ACTIVE
                startPeriodicSyncAndWatchdog()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingTelemetryBytes(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleIncomingTelemetryBytes(bytes)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            commandDispatcher.onWriteResponseReceived(status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiDbm.value = rssi
            }
        }
    }

    private fun handleIncomingTelemetryBytes(bytes: ByteArray) {
        _isLinkDegraded.value = false
        _linkWarningMessage.value = null
        packetParser.ingestRawChunk(bytes, System.currentTimeMillis())
    }

    /**
     * Built-in Replay Mode pre-populated with the real `btsnooz_hci.log` telemetry bursts
     * (`11 km/h`, `20 km/h`, `10 km/h`, `52 km/h`, `14 km/h`, `91%` battery `5a 13 5b`,
     * `5a 7d 54` cluster status, and `5a 11 00` odometer/trip packets).
     */
    fun startBtsnoozHciLogReplay() {
        disconnectBle(userInitiated = true)
        packetParser.resetIntegratedSubMeterTrip()

        scope.launch {
            // Simulate realistic 6-step handshake progression
            _connectedDeviceName.value = "${IqubeBleSpec.DEFAULT_DEVICE_ALIAS} (HCI Log)"
            _connectedDeviceMac.value = IqubeBleSpec.TARGET_MAC_ADDRESS
            _rssiDbm.value = -58
            _isLinkDegraded.value = false
            _linkWarningMessage.value = null

            _connectionPhase.value = BleConnectionPhase.ENABLING_CCCD
            delay(180L)
            _connectionPhase.value = BleConnectionPhase.NEGOTIATING_MTU
            _negotiatedMtu.value = IqubeBleSpec.EXPECTED_NEGOTIATED_MTU // 247 bytes
            delay(180L)
            _connectionPhase.value = BleConnectionPhase.SENDING_HANDSHAKE

            // Enqueue Initial Handshake `[RU` (`5b 52 55...`) through the real FIFO dispatcher
            commandDispatcher.enqueueCommand(
                label = "Handshake [RU (#88)",
                rawPayload = commandDispatcher.buildInitialHandshakeFrame()
            )
            delay(200L)
            _connectionPhase.value = BleConnectionPhase.REPLAYING_HCI_LOG

            startPeriodicSyncAndWatchdog()

            // Feed the real binary frames at captured intervals (~210ms for 0x10, ~320ms for 0x13/0x7D, 2.02s for 0x11)
            replayJob?.cancel()
            replayJob = scope.launch {
                var tick = 0
                var prevSpeed = 0
                val profile = HciLogReplayDataset.CAPTURED_SPEED_PROFILE_KMH

                // Emit initial 0x13, 0x7D, and 0x11 frames immediately
                feedFrameToParser(HciLogReplayDataset.buildBatteryFrame0x13(isCharging = replayCharging))
                feedFrameToParser(HciLogReplayDataset.buildClusterStatusFrame0x7D(0, false, replayLampOn))
                feedFrameToParser(
                    HciLogReplayDataset.buildOdometerFrame0x11(
                        odometerTenthsKm = replayOdoTenths,
                        tripATenthsKm = replayTripATenths,
                        tripBTenthsKm = replayTripBTenths
                    )
                )

                while (isActive && _connectionPhase.value == BleConnectionPhase.REPLAYING_HCI_LOG) {
                    val speed = profile[tick % profile.size]
                    val isRegen = speed < prevSpeed && speed > 0

                    // 1. 0x5A 0x10 Speed & Throttle (~4.7 Hz = 212 ms)
                    val speedFrame = HciLogReplayDataset.buildSpeedFrame0x10(speed, prevSpeed)
                    feedFrameToParser(speedFrame)
                    prevSpeed = speed

                    // 2. 0x5A 0x13 Battery & Range + 0x5A 0x7D Cluster Status (~3.1 Hz)
                    if (tick % 2 == 0) {
                        val voltageDeci = 512 - (speed / 12) // Realistic load sag under throttle
                        feedFrameToParser(
                            HciLogReplayDataset.buildBatteryFrame0x13(
                                socPercent = 91,
                                rangeEcoKm = 96,
                                packDecivolts = voltageDeci,
                                isCharging = replayCharging
                            )
                        )
                        feedFrameToParser(
                            HciLogReplayDataset.buildClusterStatusFrame0x7D(
                                speedKmh = speed,
                                isRegen = isRegen,
                                isLampOn = replayLampOn
                            )
                        )
                    }

                    // 3. 0x5A 0x11 Odometer & Trips (0.5 Hz = every ~2.02s / 10 ticks)
                    if (tick % 10 == 0 && tick > 0) {
                        val addedTenths = (packetParser.telemetryState.value.integratedDistanceMeters / 100.0).toInt()
                        feedFrameToParser(
                            HciLogReplayDataset.buildOdometerFrame0x11(
                                odometerTenthsKm = replayOdoTenths + addedTenths,
                                tripATenthsKm = replayTripATenths + addedTenths,
                                tripBTenthsKm = replayTripBTenths + addedTenths
                            )
                        )
                    }

                    // Slight RSSI variation (-56 to -63 dBm)
                    _rssiDbm.value = -58 - (tick % 5)

                    tick++
                    delay(212L)
                }
            }
        }
    }

    private fun feedFrameToParser(frame20B: ByteArray) {
        val now = System.currentTimeMillis()
        if (_fragmentedSliceTestEnabled.value && frame20B.size == 20) {
            // Split 20-byte frame into 2 fragmented ATT slices (8B + 12B) to exercise buffer reassembly
            val slice1 = frame20B.copyOfRange(0, 8)
            val slice2 = frame20B.copyOfRange(8, 20)
            packetParser.ingestRawChunk(slice1, now)
            packetParser.ingestRawChunk(slice2, now)
        } else {
            packetParser.ingestRawChunk(frame20B, now)
        }
    }

    /**
     * Starts the periodic `[JD` rider heartbeat (`1.8s`) and the `2,500 ms` Link Watchdog.
     */
    @SuppressLint("MissingPermission")
    private fun startPeriodicSyncAndWatchdog() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(IqubeBleSpec.HEARTBEAT_INTERVAL_MS)
                val phase = _connectionPhase.value
                val isConnectedOrReplay =
                    phase == BleConnectionPhase.STREAMING_ACTIVE || phase == BleConnectionPhase.REPLAYING_HCI_LOG
                if (isConnectedOrReplay && _heartbeatEnabled.value && !_isLinkDegraded.value) {
                    commandDispatcher.enqueueCommand(
                        label = "Heartbeat [JD (1.8s)",
                        rawPayload = commandDispatcher.buildHeartbeatSyncFrame()
                    )
                }
                if (phase == BleConnectionPhase.STREAMING_ACTIVE) {
                    try {
                        activeGatt?.readRemoteRssi()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(500L)
                val phase = _connectionPhase.value
                if (phase == BleConnectionPhase.STREAMING_ACTIVE || phase == BleConnectionPhase.REPLAYING_HCI_LOG) {
                    val lastRx = packetParser.telemetryState.value.lastPacketTimestampMs
                    if (lastRx != null) {
                        val silenceMs = System.currentTimeMillis() - lastRx
                        if (silenceMs > IqubeBleSpec.LINK_WATCHDOG_DEGRADED_MS) {
                            _isLinkDegraded.value = true
                            _linkWarningMessage.value =
                                "Link Degraded (${silenceMs}ms silence) — Supervision Timeout (0x08) Imminent. Heartbeats paused."
                        }
                    }
                }
            }
        }
    }

    private fun onWriteAckTimeoutDetected() {
        _isLinkDegraded.value = true
        _linkWarningMessage.value =
            "ATT Write Request (0x12) unacknowledged >500ms (Packet #1676 condition). Heartbeats paused."
    }

    @SuppressLint("MissingPermission")
    private suspend fun performHardwareOrReplayWrite(payload: ByteArray): Boolean {
        val phase = _connectionPhase.value
        if (phase == BleConnectionPhase.REPLAYING_HCI_LOG) {
            // Simulate TI BLE 4.2 controller ATT Write Response (0x13) after 30ms connection interval
            scope.launch {
                delay(30L)
                // Handle side effects of Trip Cycle / Trip Reset / Lamp Toggle in Replay mode
                if (payload.size == 20 && payload[0] == IqubeBleSpec.HEADER_TELEMETRY && payload[1] == 0xF1.toByte()) {
                    when (payload[3].toInt() and 0xFF) {
                        0x01 -> { // Cycle Trip A / Trip B
                            val nextTab = (packetParser.telemetryState.value.activeTripTab + 1) % 4
                            packetParser.setActiveTripTab(nextTab)
                        }
                        0x02 -> { // Reset Active Trip
                            replayTripATenths = 0
                            packetParser.resetIntegratedSubMeterTrip()
                            feedFrameToParser(
                                HciLogReplayDataset.buildOdometerFrame0x11(
                                    odometerTenthsKm = replayOdoTenths,
                                    tripATenthsKm = 0,
                                    tripBTenthsKm = replayTripBTenths
                                )
                            )
                        }
                        0x03 -> { // Toggle Dashboard Lamp
                            replayLampOn = !replayLampOn
                            feedFrameToParser(
                                HciLogReplayDataset.buildClusterStatusFrame0x7D(
                                    speedKmh = packetParser.telemetryState.value.speedKmh ?: 0,
                                    isRegen = false,
                                    isLampOn = replayLampOn
                                )
                            )
                        }
                    }
                }
                commandDispatcher.onWriteResponseReceived(0)
            }
            return true
        }

        val gatt = activeGatt ?: return false
        val writeChar = writeCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    writeChar,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                res == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                writeChar.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
            }
        } catch (_: Exception) {
            false
        }
    }

    // High-level Safe User Command Triggers (Guarded behind explicit user actions)
    fun sendCycleTripCommand() {
        commandDispatcher.enqueueCommand(
            label = "Cycle Trip A/B (5A F1 00 01)",
            rawPayload = commandDispatcher.buildTripControlFrame(0x01)
        )
    }

    fun sendResetTripCommand() {
        commandDispatcher.enqueueCommand(
            label = "Reset Active Trip (5A F1 00 02)",
            rawPayload = commandDispatcher.buildTripControlFrame(0x02)
        )
    }

    fun sendToggleDashboardLampCommand() {
        commandDispatcher.enqueueCommand(
            label = "Toggle Cluster Lamp (5A F1 00 03)",
            rawPayload = commandDispatcher.buildTripControlFrame(0x03)
        )
    }

    fun sendNavigationInstruction(
        turnIcon: NavigationTurnIcon,
        distanceMeters: Int,
        roadName: String,
        use5AEnvelope: Boolean = false
    ) {
        val prefixLabel = if (use5AEnvelope) "5A 57" else "5B 5C 57 ([\\W)"
        commandDispatcher.enqueueCommand(
            label = "Nav $prefixLabel: ${turnIcon.label} (${distanceMeters}m)",
            rawPayload = commandDispatcher.buildNavigationFrame(turnIcon, distanceMeters, roadName, use5AEnvelope)
        )
    }

    fun sendCallerOrAlertBanner(alertType: ClusterAlertType, callerText: String) {
        commandDispatcher.enqueueCommand(
            label = "Alert []C: ${alertType.label} ($callerText)",
            rawPayload = commandDispatcher.buildCallerAlertFrame(alertType, callerText)
        )
    }

    fun sendCustomVerifiedHexFrame(label: String, payload: ByteArray): Result<Unit> {
        return commandDispatcher.enqueueCommand(label, payload).map { }
    }

    private fun handleHardwareDisconnect(reason: String) {
        captureLastParkedLocationOnDisconnect()
        closeGattCleanly()
        commandDispatcher.clearPendingQueueOnDisconnect()
        packetParser.clearTelemetryOnDisconnect()
        _rssiDbm.value = null
        _negotiatedMtu.value = null
        _linkWarningMessage.value = reason

        if (_autoReconnectEnabled.value) {
            scheduleExponentialBackoffReconnect()
        } else {
            _connectionPhase.value = BleConnectionPhase.DISCONNECTED
        }
    }

    private fun scheduleExponentialBackoffReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _connectionPhase.value = BleConnectionPhase.RECONNECTING_BACKOFF
            val attempt = ++reconnectAttemptCount
            val backoffMs = min(30_000L, 1500L * (1L shl min(attempt, 4)))
            _linkWarningMessage.value =
                "Link lost. Auto-reconnect attempt #$attempt in ${backoffMs / 1000.0}s (30s HCI cancel guard)…"
            delay(backoffMs)
            if (isActive && _autoReconnectEnabled.value) {
                startBleScanAndConnect()
            }
        }
    }

    private fun captureLastParkedLocationOnDisconnect() {
        val tele = packetParser.telemetryState.value
        val loc = _lastKnownLocation.value
        val lat = loc?.latitude ?: 12.9716 // Fallback only if GPS hardware fix not yet acquired
        val lon = loc?.longitude ?: 77.5946
        val acc = loc?.accuracy ?: 12.0f

        if (tele.batteryPercent != null || tele.speedKmh != null) {
            _lastParkedLocation.value = LastParkedLocation(
                latitude = lat,
                longitude = lon,
                accuracyMeters = acc,
                timestampMs = System.currentTimeMillis(),
                batteryPercentAtPark = tele.batteryPercent,
                rangeKmAtPark = tele.rangeEcoKm,
                odometerKmAtPark = tele.odometerKm
            )
        }
    }

    private fun stopReplayModeIfNeeded() {
        replayJob?.cancel()
        replayJob = null
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
    }

    fun disconnectBle(userInitiated: Boolean = true) {
        if (userInitiated) {
            reconnectJob?.cancel()
            scanJob?.cancel()
        }
        captureLastParkedLocationOnDisconnect()
        stopReplayModeIfNeeded()
        closeGattCleanly()
        commandDispatcher.clearPendingQueueOnDisconnect()
        packetParser.clearTelemetryOnDisconnect()
        _rssiDbm.value = null
        _negotiatedMtu.value = null
        _isLinkDegraded.value = false
        _connectionPhase.value = BleConnectionPhase.DISCONNECTED
    }

    /**
     * Prevents Android GATT Error 133 by always disconnecting, closing, and clearing references.
     */
    @SuppressLint("MissingPermission")
    private fun closeGattCleanly() {
        try {
            activeGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            activeGatt?.close()
        } catch (_: Exception) {
        }
        activeGatt = null
        notifyCharacteristic = null
        writeCharacteristic = null
    }
}
