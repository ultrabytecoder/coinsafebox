package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.domain.usecase.CreateWalletUseCase
import com.ultrabytecoder.coinsafebox.security.EntropyCombiner
import com.ultrabytecoder.coinsafebox.security.SecureMnemonicCode
import com.ultrabytecoder.coinsafebox.security.SessionLockNotifier
import com.ultrabytecoder.coinsafebox.security.gcHint
import com.ultrabytecoder.coinsafebox.security.wipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CreateWalletViewModel(
    private val createWalletUseCase: CreateWalletUseCase,
    private val draft: CreateWalletFlowDraft
) : ViewModel() {

    enum class Mode { GENERATE_NEW, RESTORE_EXISTING }
    enum class Step { SETUP, PASSPHRASE, GESTURE, REVEAL }

    sealed class Result {
        data class Success(val walletId: Long) : Result()
        data class Error(val message: String) : Result()
    }

    private val _step = MutableStateFlow(Step.SETUP)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _createError = MutableStateFlow<String?>(null)
    val createError: StateFlow<String?> = _createError.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _finalMnemonic = MutableStateFlow<CharArray?>(null)
    val finalMnemonic: StateFlow<CharArray?> = _finalMnemonic.asStateFlow()

    // One-shot creation events. A replay-1 SharedFlow is thread-safe (the
    // creation coroutine emits from Dispatchers.Default, the flow collects on
    // Main) and delivers the event instantly — no polling, no retained value
    // that a recomposition could re-observe. Unlike a raw channel, every
    // collector receives the event, so a second collector can never steal the
    // navigation.
    private val _walletCreated = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val walletCreated: SharedFlow<Long> = _walletCreated.asSharedFlow()

    private val _mode = MutableStateFlow(Mode.GENERATE_NEW)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _wordCount = MutableStateFlow(24)
    val wordCount: StateFlow<Int> = _wordCount.asStateFlow()

    private val _useGesture = MutableStateFlow(false)
    val useGesture: StateFlow<Boolean> = _useGesture.asStateFlow()

    private var walletName: String = "My wallet"

    // Secrets — all wiped on session lock, on clear (onCleared), and after consumption.
    private var passphrase: CharArray = CharArray(0)
    private var gestureDigest: ByteArray? = null
    private var generateJob: Job? = null

    val isGenerateMode: Boolean get() = _mode.value == Mode.GENERATE_NEW
    val currentWordCount: Int get() = _wordCount.value
    val useGestureEnabled: Boolean get() = _useGesture.value
    val walletNameValue: String get() = walletName

    init {
        if (draft.active) {
            // Direct field assignment — bypasses setMode/setWordCount/setUseGesture
            // (which call invalidateGenerated/wipe). Secrets are NOT restored; the
            // user re-enters passphrase, re-draws gesture, and REVEAL regenerates
            // from a fresh cycle.
            _mode.value = draft.mode
            _wordCount.value = draft.wordCount
            _useGesture.value = draft.useGesture
            walletName = draft.walletName
            _step.value = draft.lastStep.resumeAfterLock()
        }
        // SESS-3: wipe secrets the moment the session locks, mirroring
        // ExportMnemonicViewModel (SESS-3). onCleared() never runs for these
        // remember-built VMs (see rememberDisposableViewModel in App.kt), so
        // without this the passphrase/gesture/mnemonic buffers would linger in
        // the dead instance until GC.
        viewModelScope.launch {
            SessionLockNotifier.locked.collect { wipeSecrets() }
        }
    }

    // REVEAL -> PASSPHRASE because the generated mnemonic was wiped on lock;
    // resuming at REVEAL would generate a different mnemonic and could
    // contradict a backup the user already wrote down. All other steps
    // resume as-is (GESTURE: user re-draws; PASSPHRASE: user re-enters;
    // SETUP: prefill only).
    private fun Step.resumeAfterLock(): Step =
        if (this == Step.REVEAL) Step.PASSPHRASE else this

    fun setWalletName(name: String) {
        walletName = name
        syncDraft()
    }

    fun setMode(newMode: Mode) {
        if (newMode == _mode.value) return
        _mode.value = newMode
        // Wipe any passphrase the user may have configured on the previous
        // mode so a secret never leaks into the wrong creation path.
        passphrase.wipe()
        passphrase = CharArray(0)
        // The gesture belongs to the generate-new path; never carry its
        // digest across a mode switch.
        gestureDigest?.wipe()
        gestureDigest = null
        invalidateGenerated()
        syncDraft()
    }

    fun setWordCount(count: Int) {
        require(count in SecureMnemonicCode.SUPPORTED_WORD_COUNTS) {
            "invalid word count $count"
        }
        if (count == _wordCount.value) return
        _wordCount.value = count
        invalidateGenerated()
        syncDraft()
    }

    fun setUseGesture(use: Boolean) {
        if (use == _useGesture.value) return
        _useGesture.value = use
        if (!use) {
            gestureDigest?.wipe()
            gestureDigest = null
        }
        invalidateGenerated()
        syncDraft()
    }

    /**
     * Takes ownership of [chars], wiping the previous passphrase. An empty
     * [chars] clears the passphrase — the UI decides intent explicitly
     * (checkbox state), so a stale "skip" can never silently retain an
     * unintended passphrase.
     */
    fun setPassphrase(chars: CharArray) {
        passphrase.wipe()
        passphrase = chars
    }

    /** Clears any configured passphrase. */
    fun clearPassphrase() {
        passphrase.wipe()
        passphrase = CharArray(0)
    }

    /** True when a non-empty passphrase has been configured. */
    val hasPassphrase: Boolean get() = passphrase.isNotEmpty()

    /**
     * Takes ownership of [digest], wiping the previous one. A new gesture
     * invalidates any already-generated mnemonic: the next Reveal must be
     * generated from THIS digest, never from the previous gesture.
     */
    fun storeGestureDigest(digest: ByteArray) {
        // Cancel the in-flight generation BEFORE wiping the old digest: the
        // generate coroutine may still be reading it on Dispatchers.Default,
        // and wiping mid-read would corrupt the combined entropy.
        invalidateGenerated()
        gestureDigest?.wipe()
        gestureDigest = digest
    }

    fun proceedFromSetup() {
        // Generate mode routes through the gesture screen (when enabled)
        // before the passphrase screen; restore mode handles creation
        // inline and does not change step.
        if (_mode.value == Mode.GENERATE_NEW) {
            _step.value = if (_useGesture.value) Step.GESTURE else Step.PASSPHRASE
        }
        syncDraft()
    }

    fun proceedFromPassphrase() {
        _step.value = Step.REVEAL
        syncDraft()
    }

    fun proceedFromGesture() {
        _step.value = Step.PASSPHRASE
        syncDraft()
    }

    fun back() {
        _step.value = when (_step.value) {
            Step.SETUP -> Step.SETUP
            Step.GESTURE -> Step.SETUP
            Step.PASSPHRASE -> if (_useGesture.value) Step.GESTURE else Step.SETUP
            Step.REVEAL -> Step.PASSPHRASE
        }
        syncDraft()
    }

    /**
     * Generates the final mnemonic ONCE, on a background coroutine. System
     * entropy is drawn here, at Reveal time — never earlier. The result is
     * exposed via [finalMnemonic] and stays stable across recomposition and
     * back/forward navigation, so a written-down backup stays valid.
     */
    fun generateFinalMnemonic() {
        if (_mode.value != Mode.GENERATE_NEW) return
        if (_finalMnemonic.value != null) return
        if (generateJob?.isActive == true) return
        generateJob = viewModelScope.launch(Dispatchers.Default) {
            // Defensive copy: storeGestureDigest may replace/wipe the field
            // while this coroutine is running.
            val digestCopy = gestureDigest?.copyOf()
            val mnemonic = EntropyCombiner.generate(_wordCount.value, digestCopy)
            digestCopy?.wipe()
            if (isActive) {
                _finalMnemonic.value = mnemonic
            } else {
                // The ViewModel was cleared while generation was running; the
                // mnemonic must not be left in a dead StateFlow unwiped.
                mnemonic.wipe()
            }
        }
    }

    fun clearCreateError() {
        _createError.value = null
    }

    /** Way B: validate and persist a user-provided mnemonic. */
    fun createWalletFromMnemonic(mnemonic: CharArray) {
        if (_isCreating.value) return
        _createError.value = null
        _isCreating.value = true
        // Snapshot the passphrase on the calling (Main) thread: the field is
        // wiped/replaced by setPassphrase, and the DB write runs on
        // Dispatchers.Default, so the coroutine must own its own copy.
        val name = walletName.trim()
        val pass = passphrase.copyOf()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = try {
                    val walletId = createWalletUseCase(name, mnemonic, pass)
                    Result.Success(walletId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.Error(userSafeMessage(e))
                } finally {
                    mnemonic.wipe()
                    pass.wipe()
                }
                when (result) {
                    is Result.Success -> {
                        clearPassphrase()
                        // The wallet is already in the DB; the navigation
                        // event must fire even if the coroutine is cancelled
                        // right after the insert. tryEmit never suspends
                        // (replay + buffer absorb it), so no NonCancellable
                        // wrapper is needed.
                        _walletCreated.tryEmit(result.walletId)
                        draft.clear()
                    }
                    is Result.Error -> _createError.value = result.message
                }
            } finally {
                _isCreating.value = false
            }
        }
    }

    /** Way A: persist the internally generated final mnemonic. */
    fun createWalletFromGenerated() {
        if (_isCreating.value) return
        _createError.value = null
        _isCreating.value = true
        // Snapshot the passphrase on the calling (Main) thread (see
        // createWalletFromMnemonic). The final mnemonic is copied here so the
        // DB write runs on a buffer that invalidateGenerated cannot wipe
        // mid-flight.
        val name = walletName.trim()
        val pass = passphrase.copyOf()
        val mnemonicCopy = _finalMnemonic.value?.copyOf()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = if (mnemonicCopy == null) {
                    Result.Error("Mnemonic not generated")
                } else {
                    try {
                        val walletId = createWalletUseCase(name, mnemonicCopy, pass)
                        Result.Success(walletId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.Error(userSafeMessage(e))
                    }
                }
                when (result) {
                    is Result.Success -> {
                        _finalMnemonic.value?.wipe()
                        _finalMnemonic.value = null
                        gestureDigest?.wipe()
                        gestureDigest = null
                        clearPassphrase()
                        gcHint()
                        // The wallet is already in the DB; the navigation
                        // event must fire even if the coroutine is cancelled
                        // right after the insert. tryEmit never suspends
                        // (replay + buffer absorb it), so no NonCancellable
                        // wrapper is needed.
                        _walletCreated.tryEmit(result.walletId)
                        draft.clear()
                    }
                    is Result.Error -> {
                        // The generated mnemonic is no longer usable after a
                        // failed creation; wipe it so the secret does not
                        // linger in memory past its necessary lifetime.
                        _finalMnemonic.value?.wipe()
                        _finalMnemonic.value = null
                        _createError.value = result.message
                    }
                }
            } finally {
                mnemonicCopy?.wipe()
                pass.wipe()
                _isCreating.value = false
            }
        }
    }

    /** Maps an exception to a user-safe message; raw messages may leak internals. */
    private fun userSafeMessage(e: Exception): String = when (e) {
        is IllegalArgumentException -> "The recovery phrase is invalid. Please check your words."
        else -> "Wallet creation failed. Please try again."
    }

    private fun invalidateGenerated() {
        // Cancel any in-flight generation so it cannot publish a mnemonic
        // that was invalidated (wrong word count / mode / gesture) after the
        // user changed their mind.
        generateJob?.cancel()
        generateJob = null
        _finalMnemonic.value?.wipe()
        _finalMnemonic.value = null
    }

    /**
     * Wipes all secret fields. Cancels the generate job FIRST (matching
     * [onCleared]'s established order) to minimize the race where the
     * Default-thread coroutine publishes a mnemonic between wipe calls.
     *
     * Public so the CreateWallet composable can call it from a
     * DisposableEffect onDispose (mirroring ExportMnemonicScreen's dispose
     * pattern) — deterministic cleanup even if the SESS-3 lock collector is
     * cancelled before it fires.
     */
    fun wipeSecrets() {
        generateJob?.cancel()
        generateJob = null
        passphrase.wipe()
        passphrase = CharArray(0)
        gestureDigest?.wipe()
        gestureDigest = null
        _finalMnemonic.value?.wipe()
        _finalMnemonic.value = null
        gcHint()
    }

    /**
     * Single source of draft writes. Activates the draft on first call and
     * always overwrites the non-secret fields + current step, so the draft
     * mirrors the live state at every user-visible transition.
     */
    private fun syncDraft() {
        if (!draft.active) draft.begin(_mode.value)
        draft.updateSettings(_mode.value, _wordCount.value, _useGesture.value, walletName)
        draft.recordStep(_step.value)
    }

    override fun onCleared() {
        super.onCleared()
        wipeSecrets()
    }
}
