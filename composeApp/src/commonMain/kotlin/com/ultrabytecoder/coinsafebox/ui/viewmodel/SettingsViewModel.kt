package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    private val settingsStorage: SettingsStorage
) : ViewModel() {

    private val _fiatCurrency = MutableStateFlow(
        FiatCurrency.fromStored(settingsStorage.getString(SettingsKeys.FIAT_CURRENCY))
    )
    val fiatCurrency: StateFlow<FiatCurrency> = _fiatCurrency.asStateFlow()

    fun setFiatCurrency(currency: FiatCurrency) {
        settingsStorage.putString(SettingsKeys.FIAT_CURRENCY, currency.code)
        _fiatCurrency.value = currency
    }
}