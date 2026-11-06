package nullvp.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nullvp.domain.model.AppSettings
import nullvp.domain.model.CustomRoutingRule
import nullvp.domain.model.OperationMode
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.model.Subscription
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.i18n.AppLanguage
import nullvp.platform.StorageHelper

@Serializable
data class SavedAppState(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedProfileId: String? = null,
    val settings: AppSettings = AppSettings()
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun toFormattedString(): String {
        val seconds = (timestamp / 1000) % 86400
        val hours = (seconds / 3600) % 24
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val millis = timestamp % 1000
        val timeStr = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
        return "[$timeStr] [${level.name.padEnd(5)}] [$tag] $message"
    }
}

object AppStateStore {

    private val defaultProfiles: List<Profile> = emptyList()

    private val _profiles = MutableStateFlow<List<Profile>>(defaultProfiles)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val jsonSerializer = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        val savedJson = StorageHelper.readStateJson()
        if (!savedJson.isNullOrBlank()) {
            runCatching {
                val loaded = jsonSerializer.decodeFromString(SavedAppState.serializer(), savedJson)
                _profiles.value = loaded.profiles
                _subscriptions.value = loaded.subscriptions
                _selectedProfileId.value = loaded.selectedProfileId ?: loaded.profiles.firstOrNull()?.id
                _settings.value = loaded.settings
            }
        }
    }

    private fun saveState() {
        val state = SavedAppState(
            profiles = _profiles.value,
            subscriptions = _subscriptions.value,
            selectedProfileId = _selectedProfileId.value,
            settings = _settings.value
        )
        val text = runCatching { jsonSerializer.encodeToString(SavedAppState.serializer(), state) }.getOrNull()
        if (text != null) {
            StorageHelper.writeStateJson(text)
        }
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(
        listOf(
            LogEntry(level = LogLevel.INFO, tag = "CoreEngine", message = "NullVP Client v1.0.0 initialized"),
            LogEntry(level = LogLevel.INFO, tag = "Platform", message = "System environment ready. Proxy and routing configured"),
            LogEntry(level = LogLevel.INFO, tag = "Routing", message = "Bypass rules loaded: RU services and private LAN direct, Ads blocked"),
            LogEntry(level = LogLevel.DEBUG, tag = "DNS", message = "Remote DNS: 1.1.1.1 (DoH), Direct DNS: 77.88.8.8")
        )
    )
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun selectProfile(id: String) {
        _selectedProfileId.value = id
        saveState()
    }

    fun addProfile(profile: Profile) {
        val list = _profiles.value.toMutableList()
        val safeId = if (list.any { it.id == profile.id }) {
            "${profile.id}_${System.currentTimeMillis()}"
        } else {
            profile.id
        }
        val safeProfile = if (safeId != profile.id) profile.copy(id = safeId) else profile
        list.add(0, safeProfile)
        _profiles.value = list
        _selectedProfileId.value = safeProfile.id
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Added profile '${safeProfile.name}' (${safeProfile.protocolType})")
    }

    fun updateProfile(profile: Profile) {
        val list = _profiles.value.map { if (it.id == profile.id) profile else it }
        _profiles.value = list
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Updated profile '${profile.name}'")
    }

    fun deleteProfile(id: String) {
        val list = _profiles.value.filter { it.id != id }
        _profiles.value = list
        if (_selectedProfileId.value == id) {
            _selectedProfileId.value = list.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Deleted profile ID: $id")
    }

    fun duplicateProfile(profile: Profile) {
        val copy = profile.copy(
            id = "profile_${System.currentTimeMillis()}_${(100..999).random()}",
            name = "${profile.name} (Copy)"
        )
        addProfile(copy)
    }

    fun addSubscription(sub: Subscription, newProfiles: List<Profile>) {
        val currentSubs = _subscriptions.value.filter { it.id != sub.id }.toMutableList()
        currentSubs.add(0, sub)
        _subscriptions.value = currentSubs

        val otherProfiles = _profiles.value.filter { it.subscriptionId != sub.id }.toMutableList()
        val existingIds = otherProfiles.map { it.id }.toMutableSet()

        val deduplicatedProfiles = newProfiles.mapIndexed { idx, p ->
            var candidateId = "${p.id}_${idx}"
            while (existingIds.contains(candidateId)) {
                candidateId = "${p.id}_${idx}_${System.nanoTime().toString(36)}"
            }
            existingIds.add(candidateId)
            p.copy(id = candidateId, subscriptionId = sub.id)
        }

        otherProfiles.addAll(0, deduplicatedProfiles)
        _profiles.value = otherProfiles

        if (_selectedProfileId.value == null || !otherProfiles.any { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = otherProfiles.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Imported subscription '${sub.name}' with ${deduplicatedProfiles.size} nodes")
    }

    fun renameSubscription(subId: String, newName: String) {
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == subId) it.copy(name = newName) else it
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Renamed subscription to '$newName'")
    }

    fun deleteSubscription(subId: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != subId }
        val remainingProfiles = _profiles.value.filter { it.subscriptionId != subId }
        _profiles.value = remainingProfiles
        if (_selectedProfileId.value != null && remainingProfiles.none { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = remainingProfiles.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Deleted subscription ID: $subId")
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        saveState()
        addLog(LogLevel.INFO, "Settings", "Settings updated. Mode: ${newSettings.operationMode}, Routing: ${newSettings.routingStrategy}")
    }

    fun setLanguage(lang: AppLanguage) {
        _settings.value = _settings.value.copy(language = lang)
        saveState()
        addLog(LogLevel.INFO, "Settings", "Language changed to ${lang.displayName}")
    }

    fun setOperationMode(mode: OperationMode) {
        _settings.value = _settings.value.copy(operationMode = mode)
        saveState()
        addLog(LogLevel.INFO, "Settings", "Operation mode changed to $mode")
    }

    fun addCustomRule(rule: CustomRoutingRule) {
        val updated = _settings.value.customRules.toMutableList()
        updated.add(rule)
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Added custom rule '${rule.name}' -> ${rule.outbound}")
    }

    fun updateCustomRule(rule: CustomRoutingRule) {
        val updated = _settings.value.customRules.map { if (it.id == rule.id) rule else it }
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Updated custom rule '${rule.name}'")
    }

    fun deleteCustomRule(ruleId: String) {
        val updated = _settings.value.customRules.filter { it.id != ruleId }
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Deleted custom rule ID: $ruleId")
    }

    fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        val current = _logs.value.toMutableList()
        if (current.size > 2000) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportLogsToString(): String {
        return _logs.value.joinToString("\n") { it.toFormattedString() }
    }

    fun exportProfileUri(profileId: String): String? {
        val profile = _profiles.value.find { it.id == profileId } ?: return null
        val handler = ProtocolRegistry.getHandler(profile.protocolType) ?: return null
        return handler.formatUri(profile)
    }
}
