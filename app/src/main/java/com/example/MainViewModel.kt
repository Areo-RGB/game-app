package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.GameRepository
import com.example.data.SettingsEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MyGameState {
    IDLE, RUNNING, FAILED, FINISHED
}

enum class ActiveTab {
    GAME, SETTINGS, GAME_SETTINGS, DISPLAY
}

data class DisplayLifeStatus(
    val endpointId: String,
    val label: String,
    val lives: Int,
    val maxLives: Int,
)

data class NearbyEndpoint(
    val id: String,
    val name: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameRepository(AppDatabase.getDatabase(application).settingsDao())

    private val _nearbyStatus = MutableStateFlow("Idle")
    val nearbyStatus: StateFlow<String> = _nearbyStatus.asStateFlow()

    private val _connectedDevicesCount = MutableStateFlow(0)
    val connectedDevicesCount: StateFlow<Int> = _connectedDevicesCount.asStateFlow()

    private val _availableControllers = MutableStateFlow<List<NearbyEndpoint>>(emptyList())
    val availableControllers: StateFlow<List<NearbyEndpoint>> = _availableControllers.asStateFlow()

    private val _displayLifeStatuses = MutableStateFlow<List<DisplayLifeStatus>>(emptyList())
    val displayLifeStatuses: StateFlow<List<DisplayLifeStatus>> = _displayLifeStatuses.asStateFlow()

    private val connectedEndpoints = mutableSetOf<String>()
    private val endpointRoles = mutableMapOf<String, DeviceRole>()
    private val endpointLabels = mutableMapOf<String, String>()
    private val followerLives = mutableMapOf<String, DisplayLifeStatus>()
    private val pendingConnectionEndpoints = mutableSetOf<String>()
    private var activeNearbyMode: Boolean? = null
    private var fullscreenDefaultApplied = false

    private val nearbyServiceId = "com.example.reactiontimer.MIRROR"
    private val localPreset: DevicePreset = DevicePresets.presetFor(application)
    val localRole: DeviceRole = localPreset.role
    val localDeviceLabel: String = localPreset.label

    val settings: StateFlow<SettingsEntity> = repository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsEntity()
        )

    init {
        // Collect settings and automatically manage Nearby Connections mode based on isController parameter
        viewModelScope.launch {
            // Let the ViewModel finish property initialization before Nearby auto-start uses callbacks declared below.
            delay(100)
            settings.collect { currentSettings ->
                val presetSettings = DevicePresets.applyStartupPreset(getApplication(), currentSettings)
                if (presetSettings != currentSettings) {
                    saveSettingsToDb(presetSettings)
                    when (localRole) {
                        DeviceRole.CONTROLLER -> _activeTab.value = ActiveTab.GAME_SETTINGS
                        DeviceRole.DISPLAY -> _activeTab.value = ActiveTab.DISPLAY
                        DeviceRole.FOLLOWER -> Unit
                    }
                    return@collect
                }

                if (!fullscreenDefaultApplied && !currentSettings.fullscreen) {
                    fullscreenDefaultApplied = true
                    saveSettingsToDb(currentSettings.copy(fullscreen = true))
                    return@collect
                }
                fullscreenDefaultApplied = true

                val prevMode = activeNearbyMode
                val newMode = localRole == DeviceRole.CONTROLLER || currentSettings.isController
                if (prevMode != newMode) {
                    activeNearbyMode = newMode
                    restartNearbyForRole(newMode)
                } else if (newMode && prevMode == true) {
                    // Controller setting values changed - broadcast update to all connected clients.
                    broadcastSettings(currentSettings)
                    broadcastDisplayLifeStatuses()
                }
            }
        }
    }

    private val _activeTab = MutableStateFlow(
        when (localRole) {
            DeviceRole.CONTROLLER -> ActiveTab.GAME_SETTINGS
            DeviceRole.DISPLAY -> ActiveTab.DISPLAY
            DeviceRole.FOLLOWER -> ActiveTab.GAME
        }
    )
    val activeTab: StateFlow<ActiveTab> = _activeTab.asStateFlow()

    private val _gameState = MutableStateFlow(MyGameState.IDLE)
    val gameState: StateFlow<MyGameState> = _gameState.asStateFlow()

    private val _round = MutableStateFlow(0)
    val round: StateFlow<Int> = _round.asStateFlow()

    private val _countdown = MutableStateFlow(0L)
    val countdown: StateFlow<Long> = _countdown.asStateFlow()

    private val _remaining = MutableStateFlow(0L)
    val remaining: StateFlow<Long> = _remaining.asStateFlow()

    private val _sessionStart = MutableStateFlow(0L)
    val sessionStart: StateFlow<Long> = _sessionStart.asStateFlow()

    private val _elapsedTime = MutableStateFlow(0L)
    val elapsedTime: StateFlow<Long> = _elapsedTime.asStateFlow()

    private val _stackedTime = MutableStateFlow(0L)
    val stackedTime: StateFlow<Long> = _stackedTime.asStateFlow()

    private val _remainingLives = MutableStateFlow(5)
    val remainingLives: StateFlow<Int> = _remainingLives.asStateFlow()

    private var timerJob: Job? = null

    fun setActiveTab(tab: ActiveTab) {
        _activeTab.value = tab
    }

    private fun roleName(role: DeviceRole = localRole): String =
        role.name.lowercase()

    private fun parseKeyValuePayload(prefix: String, text: String): Map<String, String>? {
        if (!text.startsWith(prefix)) return null
        return text.substringAfter(prefix)
            .split(";")
            .mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun sendRoleToEndpoint(endpointId: String) {
        val text = "ROLE:role=${roleName()};label=$localDeviceLabel"
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(endpointId, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Role send failed: ${e.message}"
            }
    }

    private fun sendLocalLivesStatus(endpointId: String? = null) {
        if (localRole != DeviceRole.FOLLOWER) return
        val text = "LIVES_STATUS:label=$localDeviceLabel;lives=${_remainingLives.value};maxLives=${settings.value.livesCount}"
        val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
        val client = Nearby.getConnectionsClient(getApplication<Application>())
        val target = endpointId ?: connectedEndpoints.firstOrNull() ?: return
        client.sendPayload(target, payload).addOnFailureListener { e ->
            _nearbyStatus.value = "Lives status send failed: ${e.message}"
        }
    }

    private fun handleRolePayload(endpointId: String, text: String) {
        val map = parseKeyValuePayload("ROLE:", text) ?: return
        val role = when (map["role"]?.lowercase()) {
            "controller" -> DeviceRole.CONTROLLER
            "display" -> DeviceRole.DISPLAY
            "follower" -> DeviceRole.FOLLOWER
            else -> DeviceRole.FOLLOWER
        }
        endpointRoles[endpointId] = role
        endpointLabels[endpointId] = map["label"] ?: endpointId
        if (localRole == DeviceRole.CONTROLLER) {
            broadcastDisplayLifeStatuses()
        }
    }

    private fun handleLivesStatusPayload(endpointId: String, text: String) {
        if (localRole != DeviceRole.CONTROLLER) return
        val map = parseKeyValuePayload("LIVES_STATUS:", text) ?: return
        val role = endpointRoles[endpointId]
        if (role != null && role != DeviceRole.FOLLOWER) return
        endpointRoles[endpointId] = DeviceRole.FOLLOWER
        val status = DisplayLifeStatus(
            endpointId = endpointId,
            label = map["label"] ?: endpointLabels[endpointId] ?: endpointId,
            lives = map["lives"]?.toIntOrNull() ?: settings.value.livesCount,
            maxLives = map["maxLives"]?.toIntOrNull() ?: settings.value.livesCount,
        )
        followerLives[endpointId] = status
        broadcastDisplayLifeStatuses()
    }

    private fun handleDisplayLivesPayload(text: String) {
        if (localRole != DeviceRole.DISPLAY) return
        val body = text.substringAfter("DISPLAY_LIVES:", "")
        if (body.isBlank()) {
            _displayLifeStatuses.value = emptyList()
            return
        }
        _displayLifeStatuses.value = body.split("|")
            .mapNotNull { item ->
                val parts = item.split(",", limit = 4)
                if (parts.size != 4) return@mapNotNull null
                DisplayLifeStatus(
                    endpointId = parts[0],
                    label = parts[1],
                    lives = parts[2].toIntOrNull() ?: 0,
                    maxLives = parts[3].toIntOrNull() ?: settings.value.livesCount,
                )
            }
    }

    private fun broadcastDisplayLifeStatuses() {
        if (localRole != DeviceRole.CONTROLLER) return
        val displayEndpoints = connectedEndpoints.filter { endpointRoles[it] == DeviceRole.DISPLAY }
        if (displayEndpoints.isEmpty()) return
        val statuses = followerLives.values.sortedBy { it.label.lowercase() }
        val body = statuses.joinToString("|") { status ->
            "${status.endpointId},${status.label},${status.lives},${status.maxLives}"
        }
        val payload = Payload.fromBytes("DISPLAY_LIVES:$body".toByteArray(Charsets.UTF_8))
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(displayEndpoints, payload)
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Display update failed: ${e.message}"
            }
    }

    fun handlePress() {
        val currentSettings = settings.value
        if (currentSettings.isReverseMode) {
            handlePressReverse(currentSettings)
        } else {
            handlePressStandard(currentSettings)
        }
    }

    private fun handlePressStandard(currentSettings: SettingsEntity) {
        when (_gameState.value) {
            MyGameState.IDLE -> {
                val now = System.currentTimeMillis()
                _sessionStart.value = now
                _round.value = 1
                _remainingLives.value = if (currentSettings.livesEnabled) currentSettings.livesCount else 0
                val limit = currentSettings.initTime.toLong()
                _countdown.value = limit
                _remaining.value = limit
                _gameState.value = MyGameState.RUNNING
                
                // Play sound
                viewModelScope.launch {
                    AudioSynth.beep(440.0, 0.08, "square", 0.2)
                }
                
                startTimerStandard(limit)
            }
            MyGameState.RUNNING -> {
                val nextRound = _round.value + 1
                _round.value = nextRound
                val limit = getEffectiveLimit(_sessionStart.value, currentSettings)
                _countdown.value = limit
                _remaining.value = limit
                
                // Play custom success sound for presses within the active time limit.
                GameAudio.playSuccessPress(getApplication())
                
                startTimerStandard(limit)
            }
            MyGameState.FAILED, MyGameState.FINISHED -> {
                resetGame()
            }
        }
    }

    private fun handlePressReverse(currentSettings: SettingsEntity) {
        when (_gameState.value) {
            MyGameState.IDLE -> {
                _round.value = 1
                _elapsedTime.value = 0L
                _stackedTime.value = 0L
                _gameState.value = MyGameState.RUNNING
                
                // Play sound
                viewModelScope.launch {
                    AudioSynth.beep(440.0, 0.08, "square", 0.2)
                }
                
                startTimerReverse(currentSettings.reverseLimitMs)
            }
            MyGameState.RUNNING -> {
                val currentElapsed = _elapsedTime.value
                val newStacked = _stackedTime.value + currentElapsed
                
                if (newStacked >= currentSettings.reverseLimitMs) {
                    _stackedTime.value = currentSettings.reverseLimitMs
                    _elapsedTime.value = 0L
                    triggerFinishReverse()
                } else {
                    _stackedTime.value = newStacked
                    _elapsedTime.value = 0L
                    _round.value = _round.value + 1
                    
                    // Play custom success sound for presses within the active time limit.
                    GameAudio.playSuccessPress(getApplication())
                    
                    startTimerReverse(currentSettings.reverseLimitMs)
                }
            }
            MyGameState.FAILED, MyGameState.FINISHED -> {
                resetGame()
            }
        }
    }

    private fun startTimerStandard(totalTime: Long) {
        timerJob?.cancel()
        val startTime = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime
                val nextVal = (totalTime - elapsed).coerceAtLeast(0)
                _remaining.value = nextVal

                if (nextVal <= 0) {
                    handleStandardTimeout()
                    break
                }
                delay(12) // smooth loop
            }
        }
    }

    private fun handleStandardTimeout() {
        val currentSettings = settings.value
        if (currentSettings.livesEnabled && _remainingLives.value > 1) {
            _remainingLives.value = _remainingLives.value - 1
            sendLocalLivesStatus()
            val limit = getEffectiveLimit(_sessionStart.value, currentSettings)
            _countdown.value = limit
            _remaining.value = limit
            viewModelScope.launch {
                AudioSynth.beep(220.0, 0.12, "square", 0.22)
            }
            startTimerStandard(limit)
        } else {
            if (currentSettings.livesEnabled) {
                _remainingLives.value = 0
                sendLocalLivesStatus()
            }
            triggerFailStandard()
        }
    }

    private fun startTimerReverse(limitMs: Long) {
        timerJob?.cancel()
        val startTime = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val currentElapsed = now - startTime
                _elapsedTime.value = currentElapsed
                
                val totalElapsed = _stackedTime.value + currentElapsed
                if (totalElapsed >= limitMs) {
                    _elapsedTime.value = limitMs - _stackedTime.value
                    triggerFinishReverse()
                    break
                }
                delay(12)
            }
        }
    }

    private fun triggerFailStandard() {
        timerJob?.cancel()
        _gameState.value = MyGameState.FAILED
        
        // Play failure game sounds sequentially matching web audio schedule
        viewModelScope.launch {
            AudioSynth.beep(180.0, 0.5, "sawtooth", 0.35)
            delay(180)
            AudioSynth.beep(140.0, 0.4, "sawtooth", 0.25)
        }

        // Persist high score if current run's rounds survived exceeds previous high score
        val survivedRounds = (_round.value - 1).coerceAtLeast(0)
        val currentSettings = settings.value
        if (survivedRounds > currentSettings.highScore) {
            viewModelScope.launch {
                repository.saveSettings(currentSettings.copy(highScore = survivedRounds))
            }
        }
    }

    private fun triggerFinishReverse() {
        timerJob?.cancel()
        _gameState.value = MyGameState.FINISHED
        
        // Play achievement sounds sequence
        viewModelScope.launch {
            AudioSynth.beep(520.0, 0.1, "sine", 0.2)
            delay(120)
            AudioSynth.beep(659.0, 0.1, "sine", 0.2)
            delay(120)
            AudioSynth.beep(784.0, 0.2, "sine", 0.2)
        }

        // Persist reverse high score if score exceeds previous best
        val score = _round.value
        val currentSettings = settings.value
        if (score > currentSettings.reverseHighScore) {
            viewModelScope.launch {
                repository.saveSettings(currentSettings.copy(reverseHighScore = score))
            }
        }
    }

    fun resetGame() {
        timerJob?.cancel()
        _gameState.value = MyGameState.IDLE
        _round.value = 0
        _countdown.value = 0L
        _remaining.value = 0L
        _sessionStart.value = 0L
        _elapsedTime.value = 0L
        _stackedTime.value = 0L
        _remainingLives.value = settings.value.livesCount
        sendLocalLivesStatus()
    }

    private fun getEffectiveLimit(startTime: Long, currentSettings: SettingsEntity): Long {
        if (!currentSettings.autoDifficultyEnabled || startTime == 0L) {
            return currentSettings.initTime.toLong()
        }
        val elapsed = System.currentTimeMillis() - startTime
        val interval = currentSettings.scaleInterval.toLong().coerceAtLeast(1L)
        val intervals = elapsed / interval
        val reduced = currentSettings.initTime - (intervals * currentSettings.reduction)
        return reduced.coerceAtLeast(currentSettings.minTime.toLong())
    }

    // Adjustment methods checking constraints
    fun toggleAutoDifficulty() {
        val current = settings.value
        saveSettingsToDb(current.copy(autoDifficultyEnabled = !current.autoDifficultyEnabled))
    }

    fun adjustManualLimit(direction: Int) {
        val current = settings.value
        val step = current.manualLimitStepMs.coerceIn(10, 5000)
        val delta = if (direction < 0) -step else step
        val next = (current.initTime + delta).coerceIn(250, 120000)
        val updatedMin = if (current.minTime >= next) {
            (next - 250).coerceAtLeast(250)
        } else {
            current.minTime
        }
        saveSettingsToDb(current.copy(initTime = next, minTime = updatedMin))
    }

    fun adjustManualLimitStep(delta: Int) {
        val current = settings.value
        val next = (current.manualLimitStepMs + delta).coerceIn(10, 5000)
        saveSettingsToDb(current.copy(manualLimitStepMs = next))
    }

    fun adjustInitTime(delta: Int) {
        val current = settings.value
        val next = (current.initTime + delta).coerceIn(1000, 120000)
        // Safety bounds checks: if current minTime >= next initTime, adjust
        val updatedInit = if (current.minTime >= next) {
            next
        } else {
            next
        }
        val updatedMin = if (current.minTime >= updatedInit) {
            (updatedInit - 250).coerceAtLeast(250)
        } else {
            current.minTime
        }
        saveSettingsToDb(current.copy(initTime = updatedInit, minTime = updatedMin))
    }

    fun adjustReduction(delta: Int) {
        val current = settings.value
        val next = (current.reduction + delta).coerceIn(50, 5000)
        saveSettingsToDb(current.copy(reduction = next))
    }

    fun setScaleIntervalMs(valueMs: Int) {
        val current = settings.value
        val next = valueMs.coerceIn(250, 3_600_000)
        saveSettingsToDb(current.copy(scaleInterval = next))
    }

    fun adjustUrgentThreshold(delta: Int) {
        val current = settings.value
        val next = (current.urgentMs + delta).coerceIn(250, 15000)
        saveSettingsToDb(current.copy(urgentMs = next))
    }

    fun toggleFullscreen() {
        val current = settings.value
        saveSettingsToDb(current.copy(fullscreen = !current.fullscreen))
    }

    fun toggleReverseMode() {
        val current = settings.value
        saveSettingsToDb(current.copy(isReverseMode = !current.isReverseMode))
    }

    fun adjustReverseLimitMs(deltaMs: Long) {
        val current = settings.value
        // Limit range between 10 seconds and 60 minutes
        val next = (current.reverseLimitMs + deltaMs).coerceIn(10000L, 3600000L)
        saveSettingsToDb(current.copy(reverseLimitMs = next))
    }

    fun toggleLivesEnabled() {
        val current = settings.value
        val nextEnabled = !current.livesEnabled
        saveSettingsToDb(current.copy(livesEnabled = nextEnabled))
        _remainingLives.value = if (nextEnabled) current.livesCount else 0
    }

    fun adjustLivesCount(delta: Int) {
        val current = settings.value
        val next = (current.livesCount + delta).coerceIn(1, 99)
        saveSettingsToDb(current.copy(livesCount = next))
        if (_gameState.value == MyGameState.IDLE) {
            _remainingLives.value = next
        }
    }

    fun resetSettingsToDefault() {
        val current = settings.value
        // retain both highScores
        val defaultWithHighScore = SettingsEntity(
            highScore = current.highScore,
            reverseHighScore = current.reverseHighScore
        )
        saveSettingsToDb(defaultWithHighScore)
        resetGame()
    }

    private fun saveSettingsToDb(updated: SettingsEntity) {
        viewModelScope.launch {
            repository.saveSettings(updated)
        }
        // if settings changes, reset the game
        if (_gameState.value != MyGameState.IDLE) {
            resetGame()
        }
    }

    fun toggleController() {
        val current = settings.value
        val nextIsController = !current.isController
        saveSettingsToDb(current.copy(isController = nextIsController))
        if (nextIsController) {
            _activeTab.value = ActiveTab.GAME_SETTINGS
        }
    }

    private fun restartNearbyForRole(isController: Boolean) {
        if (!hasNearbyRuntimePermissions(getApplication())) {
            _nearbyStatus.value = "Nearby permissions missing. Open Settings to grant them."
            return
        }

        if (isController) {
            startNearbyHosting()
        } else {
            startNearbyDiscovering()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Nearby.getConnectionsClient(getApplication<Application>())
                .acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    _nearbyStatus.value = "Accepting connection with ${connectionInfo.endpointName}"
                }
                .addOnFailureListener { e ->
                    _nearbyStatus.value = "Failed to accept connection: ${e.message}"
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingConnectionEndpoints.remove(endpointId)
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                _connectedDevicesCount.value = connectedEndpoints.size
                _nearbyStatus.value = if (settings.value.isController) {
                    "Hosting: Device connected ($endpointId)"
                } else {
                    _availableControllers.value = emptyList()
                    "Follower: Connected to Controller ($endpointId)"
                }
                sendRoleToEndpoint(endpointId)
                if (localRole == DeviceRole.FOLLOWER) {
                    sendLocalLivesStatus(endpointId)
                }
                if (localRole == DeviceRole.CONTROLLER || settings.value.isController) {
                    sendSettingsToEndpoint(endpointId, settings.value)
                    broadcastDisplayLifeStatuses()
                }
            } else {
                _nearbyStatus.value = "Connection failed: ${result.status.statusMessage}. Discovery keeps running."
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            pendingConnectionEndpoints.remove(endpointId)
            endpointRoles.remove(endpointId)
            endpointLabels.remove(endpointId)
            followerLives.remove(endpointId)
            _connectedDevicesCount.value = connectedEndpoints.size
            if (localRole == DeviceRole.CONTROLLER) {
                broadcastDisplayLifeStatuses()
            }
            if (settings.value.isController) {
                _nearbyStatus.value = "Hosting: Device disconnected. Peers: ${connectedEndpoints.size}"
            } else {
                _nearbyStatus.value = "Disconnected from Controller. Retrying discovery..."
                startNearbyDiscovering()
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val endpoint = NearbyEndpoint(endpointId, info.endpointName)
            _availableControllers.value = (_availableControllers.value
                .filterNot { it.id == endpointId } + endpoint)
                .sortedBy { it.name.lowercase() }
            _nearbyStatus.value = "Controller found: ${info.endpointName}. Auto-connecting..."
            connectToController(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            _availableControllers.value = _availableControllers.value.filterNot { it.id == endpointId }
            _nearbyStatus.value = if (_availableControllers.value.isEmpty()) {
                "Lost contact with controller. Scanning continues..."
            } else {
                "Controller list updated."
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, Charsets.UTF_8)
            when {
                text.startsWith("SETTINGS_MIRROR:") -> {
                    val updated = deserializeSettings(text, settings.value)
                    if (updated != null) {
                        saveReceivedSettings(updated)
                    }
                }
                text.startsWith("ROLE:") -> handleRolePayload(endpointId, text)
                text.startsWith("LIVES_STATUS:") -> handleLivesStatusPayload(endpointId, text)
                text.startsWith("DISPLAY_LIVES:") -> handleDisplayLivesPayload(text)
                text == "COMMAND:OPEN_GAME_TAB" -> {
                    if (localRole == DeviceRole.FOLLOWER) {
                        _activeTab.value = ActiveTab.GAME
                    }
                }
                text == "COMMAND:RESET_GAME" -> resetGame()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun startNearbyHosting() {
        val context = getApplication<Application>()
        if (!hasNearbyRuntimePermissions(context)) {
            _nearbyStatus.value = "Hosting paused: Nearby permissions missing."
            return
        }

        val client = Nearby.getConnectionsClient(context)
        client.stopDiscovery()
        client.stopAdvertising()
        connectedEndpoints.clear()
        endpointRoles.clear()
        endpointLabels.clear()
        followerLives.clear()
        pendingConnectionEndpoints.clear()
        _availableControllers.value = emptyList()
        _displayLifeStatuses.value = emptyList()
        _connectedDevicesCount.value = 0

        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        client.startAdvertising(
            "ControllerDevice",
            nearbyServiceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            _nearbyStatus.value = "Controller: Hosting active. Followers and display connect here..."
        }.addOnFailureListener { e ->
            _nearbyStatus.value = "Hosting error: ${e.message}"
        }
    }

    fun startNearbyDiscovering() {
        val context = getApplication<Application>()
        if (!hasNearbyRuntimePermissions(context)) {
            _nearbyStatus.value = "Discovery paused: Nearby permissions missing."
            return
        }

        val client = Nearby.getConnectionsClient(context)
        client.stopAdvertising()
        client.stopDiscovery()
        connectedEndpoints.clear()
        endpointRoles.clear()
        endpointLabels.clear()
        followerLives.clear()
        pendingConnectionEndpoints.clear()
        _availableControllers.value = emptyList()
        _displayLifeStatuses.value = emptyList()
        _connectedDevicesCount.value = 0

        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build()

        client.startDiscovery(
            nearbyServiceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            _nearbyStatus.value = if (localRole == DeviceRole.DISPLAY) {
                "Display: Searching for controller to receive follower lives..."
            } else {
                "Follower: Discovering active. Searching for controller..."
            }
        }.addOnFailureListener { e ->
            _nearbyStatus.value = "Discovery error: ${e.message}"
        }
    }

    fun connectToController(endpointId: String, endpointName: String) {
        val context = getApplication<Application>()
        if (!hasNearbyRuntimePermissions(context)) {
            _nearbyStatus.value = "Connect paused: Nearby permissions missing."
            return
        }
        if (connectedEndpoints.contains(endpointId)) {
            _nearbyStatus.value = "Already connected to $endpointName"
            return
        }
        if (!pendingConnectionEndpoints.add(endpointId)) {
            _nearbyStatus.value = "Connection already pending for $endpointName"
            return
        }

        _nearbyStatus.value = "Connecting to $endpointName..."
        Nearby.getConnectionsClient(context)
            .requestConnection(
                when (localRole) {
                    DeviceRole.DISPLAY -> "DisplayDevice"
                    DeviceRole.CONTROLLER -> "ControllerDevice"
                    DeviceRole.FOLLOWER -> "FollowerDevice"
                },
                endpointId,
                connectionLifecycleCallback
            )
            .addOnSuccessListener {
                _nearbyStatus.value = "Connection request sent to $endpointName"
            }
            .addOnFailureListener { e ->
                pendingConnectionEndpoints.remove(endpointId)
                _nearbyStatus.value = "Failed to request connection: ${e.message}"
            }
    }

    fun sendSettingsToEndpoint(endpointId: String, s: SettingsEntity) {
        val text = serializeSettings(s)
        val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(endpointId, payload)
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Send failed: ${e.message}"
            }
    }

    fun broadcastSettings(s: SettingsEntity) {
        if (connectedEndpoints.isEmpty()) return
        val text = serializeSettings(s)
        val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(connectedEndpoints.toList(), payload)
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Broadcast failed: ${e.message}"
            }
    }

    fun broadcastOpenGameTab() {
        val followerEndpoints = connectedEndpoints.filter { endpointRoles[it] == DeviceRole.FOLLOWER }
        if (followerEndpoints.isEmpty()) {
            _nearbyStatus.value = "No followers connected to switch."
            return
        }
        val payload = Payload.fromBytes("COMMAND:OPEN_GAME_TAB".toByteArray(Charsets.UTF_8))
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(followerEndpoints, payload)
            .addOnSuccessListener {
                _nearbyStatus.value = "Followers switched to Game tab."
            }
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Tab switch failed: ${e.message}"
            }
    }

    fun broadcastResetGame() {
        resetGame()
        if (connectedEndpoints.isEmpty()) {
            _nearbyStatus.value = "Reset local game. No followers connected."
            return
        }
        val payload = Payload.fromBytes("COMMAND:RESET_GAME".toByteArray(Charsets.UTF_8))
        Nearby.getConnectionsClient(getApplication<Application>())
            .sendPayload(connectedEndpoints.toList(), payload)
            .addOnSuccessListener {
                _nearbyStatus.value = "All connected devices reset to start."
            }
            .addOnFailureListener { e ->
                _nearbyStatus.value = "Reset broadcast failed: ${e.message}"
            }
    }

    fun serializeSettings(s: SettingsEntity): String {
        return "SETTINGS_MIRROR:" +
                "initTime=${s.initTime};" +
                "minTime=${s.minTime};" +
                "reduction=${s.reduction};" +
                "scaleInterval=${s.scaleInterval};" +
                "autoDifficultyEnabled=${s.autoDifficultyEnabled};" +
                "manualLimitStepMs=${s.manualLimitStepMs};" +
                "urgentMs=${s.urgentMs};" +
                "fullscreen=${s.fullscreen};" +
                "isReverseMode=${s.isReverseMode};" +
                "reverseLimitMs=${s.reverseLimitMs};" +
                "livesEnabled=${s.livesEnabled};" +
                "livesCount=${s.livesCount}"
    }

    fun deserializeSettings(packet: String, current: SettingsEntity): SettingsEntity? {
        if (!packet.startsWith("SETTINGS_MIRROR:")) return null
        try {
            val body = packet.substringAfter("SETTINGS_MIRROR:")
            val map = body.split(";").mapNotNull {
                val parts = it.split("=")
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()

            return current.copy(
                initTime = map["initTime"]?.toInt() ?: current.initTime,
                minTime = map["minTime"]?.toInt() ?: current.minTime,
                reduction = map["reduction"]?.toInt() ?: current.reduction,
                scaleInterval = map["scaleInterval"]?.toInt() ?: current.scaleInterval,
                autoDifficultyEnabled = map["autoDifficultyEnabled"]?.toBoolean() ?: current.autoDifficultyEnabled,
                manualLimitStepMs = map["manualLimitStepMs"]?.toInt() ?: current.manualLimitStepMs,
                urgentMs = map["urgentMs"]?.toInt() ?: current.urgentMs,
                fullscreen = map["fullscreen"]?.toBoolean() ?: current.fullscreen,
                isReverseMode = map["isReverseMode"]?.toBoolean() ?: current.isReverseMode,
                reverseLimitMs = map["reverseLimitMs"]?.toLong() ?: current.reverseLimitMs,
                livesEnabled = map["livesEnabled"]?.toBoolean() ?: current.livesEnabled,
                livesCount = map["livesCount"]?.toInt() ?: current.livesCount
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun saveReceivedSettings(updated: SettingsEntity) {
        viewModelScope.launch {
            val finalised = updated.copy(isController = false)
            repository.saveSettings(finalised)
            if (localRole == DeviceRole.FOLLOWER) {
                _remainingLives.value = if (finalised.livesEnabled) {
                    _remainingLives.value.coerceIn(0, finalised.livesCount)
                } else {
                    0
                }
                sendLocalLivesStatus()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val client = Nearby.getConnectionsClient(getApplication<Application>())
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }
}
