package com.ultrabytecoder.coinsafebox.domain.usecase

import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.repository.SecurityMethod

class SetSecurityMethodUseCase(
    private val settingsStorage: SettingsStorage
) {
    operator fun invoke(method: SecurityMethod) {
        settingsStorage.putString(SettingsKeys.SECURITY_METHOD, method.name)
    }
}
