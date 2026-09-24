package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.model.ChainType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CustomNodeEntry(
    val chain: ChainType,
    val defaultUrl: String,
    val customUrl: String?,
    val draft: String,
    val isDirty: Boolean,
)

data class CustomNodesState(
    val entries: Map<ChainType, CustomNodeEntry> = emptyMap(),
)

class CustomNodesViewModel(
    private val settingsStorage: SettingsStorage,
    networkConfig: NetworkConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(loadState(networkConfig))
    val state: StateFlow<CustomNodesState> = _state.asStateFlow()

    private fun loadState(networkConfig: NetworkConfig): CustomNodesState {
        val entries = ChainType.entries.associateWith { chain ->
            val custom = settingsStorage.getString(chain.storageKey)
            val default = chain.defaultUrl(networkConfig)
            CustomNodeEntry(
                chain = chain,
                defaultUrl = default,
                customUrl = custom,
                draft = custom ?: default,
                isDirty = false,
            )
        }
        return CustomNodesState(entries = entries)
    }

    fun updateDraft(chain: ChainType, url: String) {
        _state.update { s ->
            val entry = s.entries.getValue(chain)
            val isDirty = url.trim() != (entry.customUrl ?: entry.defaultUrl)
            s.copy(
                entries = s.entries + (chain to entry.copy(
                    draft = url,
                    isDirty = isDirty,
                ))
            )
        }
    }

    fun save(chain: ChainType) {
        val entry = _state.value.entries.getValue(chain)
        val trimmed = entry.draft.trim()
        when {
            trimmed.isEmpty() || trimmed == entry.defaultUrl -> resetToDefault(chain)
            else -> {
                settingsStorage.putString(chain.storageKey, trimmed)
                _state.update { s ->
                    val e = s.entries.getValue(chain)
                    s.copy(entries = s.entries + (chain to e.copy(
                        customUrl = trimmed,
                        draft = trimmed,
                        isDirty = false,
                    )))
                }
            }
        }
    }

    fun resetToDefault(chain: ChainType) {
        settingsStorage.remove(chain.storageKey)
        _state.update { s ->
            val e = s.entries.getValue(chain)
            s.copy(entries = s.entries + (chain to e.copy(
                customUrl = null,
                draft = e.defaultUrl,
                isDirty = false,
            )))
        }
    }
}