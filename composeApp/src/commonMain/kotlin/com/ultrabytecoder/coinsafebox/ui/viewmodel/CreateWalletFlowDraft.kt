package com.ultrabytecoder.coinsafebox.ui.viewmodel

/**
 * Non-secret progress state of the create-wallet flow, held for the lifetime of the
 * process so an interrupted flow (session lock on background) can resume where the
 * user left off after re-authentication.
 *
 * SECURITY INVARIANT: this class must NEVER hold secrets — no passphrase, mnemonic,
 * or gesture digest. Only display-level settings. Enforced by code review; the fields
 * are deliberately a minimal allowlist.
 *
 * Main thread only (written/reads from Compose composition).
 */
class CreateWalletFlowDraft {
    var active: Boolean = false
        private set
    var mode: CreateWalletViewModel.Mode = CreateWalletViewModel.Mode.GENERATE_NEW
        private set
    var wordCount: Int = 24
        private set
    var useGesture: Boolean = false
        private set
    var walletName: String = "My wallet"
        private set
    var lastStep: CreateWalletViewModel.Step = CreateWalletViewModel.Step.SETUP
        private set

    /**
     * Marks the draft as active and records the chosen mode. Idempotent on
     * [active]: calling [begin] on an already-active draft does not reset
     * the other fields — it only updates [mode]. The caller is expected to
     * follow with [updateSettings] to sync the remaining fields.
     */
    fun begin(mode: CreateWalletViewModel.Mode) {
        active = true
        this.mode = mode
    }

    /**
     * Overwrites the four non-secret display fields. The caller MUST have
     * validated [wordCount] via [CreateWalletViewModel.setWordCount]'s
     * `require(...)` before calling — the draft trusts its caller.
     * Does not change [active]; call [begin] first.
     */
    fun updateSettings(
        mode: CreateWalletViewModel.Mode,
        wordCount: Int,
        useGesture: Boolean,
        walletName: String
    ) {
        this.mode = mode
        this.wordCount = wordCount
        this.useGesture = useGesture
        this.walletName = walletName
    }

    /**
     * Records the step the user is currently on. Always overwrites (NOT
     * monotonic-max): the enum ordinals (`SETUP=0, PASSPHRASE=1, GESTURE=2,
     * REVEAL=3`) do NOT match flow order (`SETUP -> GESTURE -> PASSPHRASE ->
     * REVEAL`), so monotonic-max-by-ordinal would refuse the
     * `GESTURE(2) -> PASSPHRASE(1)` transition. Always-update also gives
     * correct resume semantics for [CreateWalletViewModel.back]
     * (the user lands where they actually are).
     */
    fun recordStep(step: CreateWalletViewModel.Step) {
        lastStep = step
    }

    /**
     * Resets all fields to defaults and marks the draft inactive.
     * Called on successful wallet creation in both code paths.
     */
    fun clear() {
        active = false
        mode = CreateWalletViewModel.Mode.GENERATE_NEW
        wordCount = 24
        useGesture = false
        walletName = "My wallet"
        lastStep = CreateWalletViewModel.Step.SETUP
    }
}
