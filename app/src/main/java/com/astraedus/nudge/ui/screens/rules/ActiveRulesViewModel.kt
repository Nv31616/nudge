package com.astraedus.nudge.ui.screens.rules

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.usecase.ExportRulesUseCase
import com.astraedus.nudge.domain.usecase.ImportOutcome
import com.astraedus.nudge.domain.usecase.ImportPreview
import com.astraedus.nudge.domain.usecase.ImportRulesUseCase
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Immutable
import javax.inject.Inject

@Immutable
data class ActiveRulesGroup(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val enabled: Boolean,
    val summaryText: String,
    val ruleCount: Int
)

@Immutable
data class ActiveRulesUiState(
    val groups: List<ActiveRulesGroup> = emptyList(),
    val isLoading: Boolean = true,
    val exportJson: String? = null,
    val importPreview: ImportPreview? = null,
    val importOutcome: ImportOutcome? = null,
    val importError: String? = null
)

@HiltViewModel
class ActiveRulesViewModel @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val exportRulesUseCase: ExportRulesUseCase,
    private val importRulesUseCase: ImportRulesUseCase,
    nudgePreferences: NudgePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActiveRulesUiState())
    val uiState: StateFlow<ActiveRulesUiState> = _uiState.asStateFlow()

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    init {
        loadActiveRules()
    }

    private fun loadActiveRules() {
        viewModelScope.launch {
            // Resolve the installed-app map ONCE (cached in the repo), not on every
            // rules-flow emission — the heavy PackageManager work must not re-run per collect.
            val appInfoMap = installedAppsRepository.getInstalledApps()
                .associateBy { it.packageName }

            blockRuleRepository.getAllRules().collect { rules ->
                val grouped = rules
                    .filter { it.packageName != null }
                    .groupBy { it.packageName!! }
                    .map { (pkg, pkgRules) ->
                        val appInfo = appInfoMap[pkg]
                        val appName = appInfo?.appName ?: pkg
                        val appIcon = appInfo?.icon
                        val enabled = pkgRules.any { it.enabled }
                        val summaryText = buildSummaryText(pkgRules)

                        ActiveRulesGroup(
                            packageName = pkg,
                            appName = appName,
                            appIcon = appIcon,
                            enabled = enabled,
                            summaryText = summaryText,
                            ruleCount = pkgRules.size
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                _uiState.value = ActiveRulesUiState(groups = grouped, isLoading = false)
            }
        }
    }

    fun toggleAppEnabled(packageName: String, currentlyEnabled: Boolean) {
        viewModelScope.launch {
            // Disabling a rule (enabled -> false) weakens protection; gate it under Strict Mode.
            // Re-enabling is free.
            if (currentlyEnabled) {
                strictModeGate.run(prompt = "Disable blocking for this app") {
                    blockRuleRepository.setEnabledForPackage(packageName, false)
                }
            } else {
                blockRuleRepository.setEnabledForPackage(packageName, true)
            }
        }
    }

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    // --- Export/Import ---

    fun exportRules() {
        viewModelScope.launch {
            val json = exportRulesUseCase.invoke()
            _uiState.value = _uiState.value.copy(exportJson = json)
        }
    }

    fun clearExport() {
        _uiState.value = _uiState.value.copy(exportJson = null)
    }

    /**
     * Reads and previews an import file.
     *
     * [readJson] is a lambda rather than a String because the file is read on the IO dispatcher
     * here: an export now carries the user's whole block history, so both the read and the parse
     * are unbounded work that used to run on the UI thread from the file-picker callback.
     */
    fun previewImport(readJson: suspend () -> String?) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { readJson() }
            if (json == null) {
                _uiState.value = _uiState.value.copy(
                    importError = "Could not read that file.",
                    importPreview = null
                )
                return@launch
            }
            val preview = importRulesUseCase.preview(json)
            val error = preview.result.error
            _uiState.value = if (error != null) {
                _uiState.value.copy(importError = error, importPreview = null)
            } else {
                _uiState.value.copy(importPreview = preview, importError = null)
            }
        }
    }

    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        viewModelScope.launch {
            val outcome = importRulesUseCase.execute(preview.result)
            _uiState.value = _uiState.value.copy(
                importOutcome = outcome,
                importPreview = null
            )
        }
    }

    fun cancelImport() {
        _uiState.value = _uiState.value.copy(importPreview = null, importError = null)
    }

    fun clearImportOutcome() {
        _uiState.value = _uiState.value.copy(importOutcome = null, importError = null)
    }

    companion object {
        /**
         * Builds a human-readable summary from all BlockRule rows for one package.
         *
         * Examples:
         *   "Hard Block"
         *   "Delay 15s - 30min daily limit"
         *   "Delay 15s - Reels: Hard Block"
         *   "Breathing - Scheduled"
         */
        fun buildSummaryText(rules: List<BlockRule>): String {
            val defaultRule = rules.find { it.inAppFeatures.isNullOrBlank() }
            val featureRules = rules.filter { !it.inAppFeatures.isNullOrBlank() }

            val parts = mutableListOf<String>()

            // Default rule mode
            if (defaultRule != null) {
                parts.add(formatMode(defaultRule.mode, defaultRule.delaySeconds))

                if (defaultRule.dailyLimitMinutes != null) {
                    parts.add("${defaultRule.dailyLimitMinutes}min limit")
                }

                if (defaultRule.scheduleDays != null || defaultRule.scheduleStartMinute != null) {
                    parts.add("Scheduled")
                }
            }

            // Feature overrides
            for (featureRule in featureRules) {
                val features = featureRule.inAppFeatures!!
                    .split(",")
                    .filter { it.isNotBlank() }
                    .joinToString("/") { it.trim().lowercase().replaceFirstChar { c -> c.uppercase() } }
                val mode = formatMode(featureRule.mode, featureRule.delaySeconds)
                parts.add("$features: $mode")
            }

            // If no default and no features somehow, fall back
            if (parts.isEmpty()) {
                return "Configured"
            }

            return parts.joinToString(" · ")
        }

        private fun formatMode(mode: String, delaySeconds: Int): String {
            return when (mode) {
                // The app itself is not gated; only this rule's feature overrides and daily limit
                // are. Without this branch the list rendered the raw enum name "NONE" at the user.
                "NONE" -> "Not blocked"
                "HARD_BLOCK" -> "Hard Block"
                "DELAY" -> "Delay ${delaySeconds}s"
                "BREATHING" -> "Breathing ${delaySeconds}s"
                else -> mode
            }
        }
    }
}
