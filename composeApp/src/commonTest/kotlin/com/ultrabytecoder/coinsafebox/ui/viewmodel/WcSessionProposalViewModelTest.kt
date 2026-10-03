package com.ultrabytecoder.coinsafebox.ui.viewmodel

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.walletconnect.AutoWcRelayTransport
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcCrypto
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcEncoding
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcPendingRequestHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcProposalHolder
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRelayClient
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcSessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.buildTestWcRelay
import com.ultrabytecoder.coinsafebox.data.walletconnect.buildTestWcSessionManager
import com.ultrabytecoder.coinsafebox.data.walletconnect.dappPairingForTest
import com.ultrabytecoder.coinsafebox.data.walletconnect.newTestWcCrypto
import com.ultrabytecoder.coinsafebox.data.walletconnect.proposeEnvelopeForTest
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.TransactionInfo
import com.ultrabytecoder.coinsafebox.domain.model.UtxoInfo
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountAddressUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WcSessionProposalViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Testnet build → eip155 chain 11155111.
    private val CHAIN_ID: Long = 11155111L

    private class Harness(
        val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) {
        val crypto: WcCrypto = newTestWcCrypto()
        val transport = AutoWcRelayTransport()
        val relay: WcRelayClient = buildTestWcRelay(crypto, transport, scheduler)
        val manager: WcSessionManager = buildTestWcSessionManager(crypto, relay, scheduler)
        val networkConfig = NetworkConfig.testnet("test-etherscan-key", "test-project-id")
        val controller = WcController(crypto, manager, networkConfig, WcProposalHolder(), WcPendingRequestHolder())

        suspend fun start() {
            // Bypass controller.start() (it launches on Dispatchers.Default);
            // the VM only needs the manager running and the callbacks wired.
            manager.start()
        }

        /** Pairs and pushes a `wc_sessionPropose` for [chains]; returns the pairing topic. */
        suspend fun pushProposal(chains: List<String>, proposalId: Long): String {
            val (pairingTopic, uri) = dappPairingForTest(crypto)
            manager.pair(uri)
            val proposerKeyPair = crypto.generateX25519KeyPair()
            val proposerPubHex = WcEncoding.hexEncode(proposerKeyPair.publicKey)
            transport.pushTopic(
                pairingTopic,
                proposeEnvelopeForTest(crypto, pairingTopic, proposerPubHex, proposalId, chains),
                "p1"
            )
            return pairingTopic
        }
    }

    private suspend fun TestScope.withHarness(
        block: suspend (h: Harness) -> Unit
    ) {
        val harness = Harness(testScheduler)
        harness.start()
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block(harness)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun ethAccount(index: Long, address: String): AccountInfo = AccountInfo(
        id = "eth-$index",
        walletId = 1,
        name = "ETH #$index",
        amount = "0",
        type = AccountType.Eth,
        symbol = "ETH",
        address = address,
        accountIndex = index,
        derivationPath = "m/44'/60'/${index}'/0/0"
    )

    private fun accountUseCases(
        networkConfig: NetworkConfig,
        vararg accounts: AccountInfo,
    ): Pair<GetAccountsUseCase, GetAccountAddressUseCase> {
        val repo = AccountsOnlyRepository(accounts.toList())
        // GetAccountAddressUseCase resolves ETH addresses via its fast path
        // (the account's persisted address), so the fallback repos are never hit.
        return GetAccountsUseCase(repo) to GetAccountAddressUseCase(
            repo, NoopUtxoRepository(), NoopTransactionRepository(), StubKeyProvider(),
            networkConfig, FakeWalletRepository(null)
        )
    }

    @Test
    fun load_showsEthAccountsAndNoMismatch_whenChainMatches() = runTest {
        withHarness { h ->
            val eth1 = ethAccount(0, "0x1111111111111111111111111111111111111111")
            val eth2 = ethAccount(1, "0x2222222222222222222222222222222222222222")
            val btc1 = AccountInfo(
                id = "btc-1", walletId = 1, name = "BTC #1", amount = "0",
                type = AccountType.Btc, symbol = "BTC", address = "bc1qtest",
                accountIndex = 0, derivationPath = "m/84'/0'/0'/0"
            )
            h.pushProposal(chains = listOf("eip155:$CHAIN_ID"), proposalId = 2001L)
            testScheduler.advanceUntilIdle()

            val (getAccounts, getAccountAddress) = accountUseCases(h.networkConfig, eth1, eth2, btc1)
            val vm = WcSessionProposalViewModel(
                2001L, 1L, h.controller, getAccounts, getAccountAddress, h.networkConfig
            )
            testScheduler.advanceUntilIdle()

            val s = vm.state.value
            assertFalse(s.isLoading)
            assertNotNull(s.proposal)
            assertEquals(2001L, s.proposal!!.id)
            // Non-ETH accounts are not offered.
            assertEquals(listOf(eth1, eth2), s.accounts)
            // The first ETH account is preselected.
            assertEquals(setOf(eth1.address), s.selectedAddresses)
            assertFalse(s.chainMismatch)
        }
    }

    @Test
    fun chainMismatch_whenProposalRequestsDifferentChain_andApproveIsBlocked() = runTest {
        withHarness { h ->
            val eth1 = ethAccount(0, "0x1111111111111111111111111111111111111111")
            // dApp only asks for mainnet (eip155:1); this build is testnet (11155111).
            h.pushProposal(chains = listOf("eip155:1"), proposalId = 2002L)
            testScheduler.advanceUntilIdle()

            val (getAccounts, getAccountAddress) = accountUseCases(h.networkConfig, eth1)
            val vm = WcSessionProposalViewModel(
                2002L, 1L, h.controller, getAccounts, getAccountAddress, h.networkConfig
            )
            testScheduler.advanceUntilIdle()

            val s = vm.state.value
            assertTrue(s.chainMismatch)

            vm.approve()
            testScheduler.advanceUntilIdle()

            assertTrue(h.transport.frames("wc_approveSession").isEmpty())
            assertFalse(vm.state.value.done)
        }
    }

    @Test
    fun approve_sendsApproveSessionWithSelectedCaip10Accounts() = runTest {
        withHarness { h ->
            val eth1 = ethAccount(0, "0x1111111111111111111111111111111111111111")
            val eth2 = ethAccount(1, "0x2222222222222222222222222222222222222222")
            h.pushProposal(chains = listOf("eip155:$CHAIN_ID"), proposalId = 2003L)
            testScheduler.advanceUntilIdle()

            val (getAccounts, getAccountAddress) = accountUseCases(h.networkConfig, eth1, eth2)
            val vm = WcSessionProposalViewModel(
                2003L, 1L, h.controller, getAccounts, getAccountAddress, h.networkConfig
            )
            testScheduler.advanceUntilIdle()

            // eth1 is preselected; add eth2.
            vm.toggleAccount(eth2.address!!)
            assertEquals(setOf(eth1.address, eth2.address), vm.state.value.selectedAddresses)

            vm.approve()
            testScheduler.advanceUntilIdle()

            val s = vm.state.value
            assertTrue(s.done)
            assertNull(h.controller.proposalHolder.pendingProposal.value)

            val session = h.manager.getSessions().single()
            val approveFrame = h.transport.frames("wc_approveSession").first()
            val approveParams = approveFrame["params"]!!.jsonObject
            assertEquals(session.topic, approveParams["sessionTopic"]!!.jsonPrimitive.content)

            // The settlement envelope must carry exactly the selected CAIP-10 accounts.
            val settleEnvelope = approveParams["sessionSettlementRequest"]!!.jsonPrimitive.content
            val settleJson = json.parseToJsonElement(h.crypto.decodeEnvelope(session.topic, settleEnvelope)).jsonObject
            val accounts = settleJson["params"]!!.jsonObject["namespaces"]!!.jsonObject["eip155"]!!
                .jsonObject["accounts"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("eip155:$CHAIN_ID:${eth1.address}", "eip155:$CHAIN_ID:${eth2.address}"),
                accounts
            )
        }
    }

    @Test
    fun reject_publishesErrorOnPairingTopicAndMarksDone() = runTest {
        withHarness { h ->
            val eth1 = ethAccount(0, "0x1111111111111111111111111111111111111111")
            val pairingTopic = h.pushProposal(chains = listOf("eip155:$CHAIN_ID"), proposalId = 2004L)
            testScheduler.advanceUntilIdle()
            assertNotNull(h.controller.proposalHolder.pendingProposal.value)

            val (getAccounts, getAccountAddress) = accountUseCases(h.networkConfig, eth1)
            val vm = WcSessionProposalViewModel(
                2004L, 1L, h.controller, getAccounts, getAccountAddress, h.networkConfig
            )
            testScheduler.advanceUntilIdle()

            vm.reject()
            testScheduler.advanceUntilIdle()

            assertTrue(vm.state.value.done)
            assertNull(h.controller.proposalHolder.pendingProposal.value)

            val publish = h.transport.frames("irn_publish").first {
                it["params"]!!.jsonObject["topic"]!!.jsonPrimitive.content == pairingTopic
            }
            val envelope = publish["params"]!!.jsonObject["message"]!!.jsonPrimitive.content
            val errorJson = json.parseToJsonElement(h.crypto.decodeEnvelope(pairingTopic, envelope)).jsonObject
            assertEquals(5000, errorJson["error"]!!.jsonObject["code"]!!.jsonPrimitive.long.toInt())
        }
    }

    private class AccountsOnlyRepository(
        private val accounts: List<AccountInfo>
    ) : AccountRepository {
        override fun getAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(accounts)
        override fun getTokensByParentFlow(parentId: String): Flow<List<AccountInfo>> = flowOf(emptyList())
        override fun getNativeAccountsByWalletFlow(walletId: Long): Flow<List<AccountInfo>> = flowOf(emptyList())
        override suspend fun getAccount(id: String): AccountInfo? = accounts.firstOrNull { it.id == id }
        override suspend fun getMaxAccountIndexByWalletAndAccountType(walletId: Long, type: String): Long? = null
        override suspend fun existsByDerivationPath(walletId: Long, derivationPath: String): Boolean = false
        override suspend fun existsTokenForParent(parentId: String, tokenAddress: String): Boolean = false
        override suspend fun countTokensByParent(parentId: String): Int = 0
        override suspend fun getNativeAccountsByWalletAndType(walletId: Long, type: String): List<AccountInfo> = emptyList()
        override suspend fun insertAccount(account: AccountInfo) {}
        override suspend fun updateAmount(accountId: String, amount: String) {}
        override suspend fun updateParams(accountId: String, params: String) {}
        override suspend fun deleteAccount(id: String) {}
        override suspend fun deleteAccountsByWallet(walletId: Long) {}
        override suspend fun getXpub(id: String): String? = null
        override suspend fun updateXpub(accountId: String, xpub: String) {}
        override suspend fun updateAddress(accountId: String, address: String) {}
    }

    private class NoopUtxoRepository : UtxoRepository {
        override fun getUnspentByAccountFlow(accountId: String): Flow<List<UtxoInfo>> = flowOf(emptyList())
        override suspend fun getUtxosByAccount(accountId: String): List<UtxoInfo> = emptyList()
        override suspend fun insertUtxo(utxo: UtxoInfo) {}
        override suspend fun deleteUtxo(id: Long) {}
        override suspend fun deleteUtxosByAccount(accountId: String) {}
    }

    private class NoopTransactionRepository : TransactionRepository {
        override fun getTransactionsByAccountFlow(accountId: String): Flow<List<TransactionInfo>> = flowOf(emptyList())
        override suspend fun getTransactionsByAccount(accountId: String, limit: Long, offset: Long): List<TransactionInfo> = emptyList()
        override suspend fun getTransactionCount(accountId: String): Long = 0
        override suspend fun getTransactionById(id: String): TransactionInfo? = null
        override suspend fun upsertAll(transactions: List<TransactionInfo>) {}
        override suspend fun deleteByAccount(accountId: String) {}
    }

    private class FakeWalletRepository(private val wallet: WalletInfo?) : WalletRepository {
        override fun getWalletsFlow(): Flow<List<WalletInfo>> = flowOf(wallet?.let { listOf(it) } ?: emptyList())
        override suspend fun getWallet(id: Long): WalletInfo? = wallet
        override suspend fun getMasterSeed(id: Long): ByteArray? = null
        override suspend fun insertWallet(name: String, masterSeed: ByteArray, mnemonic: ByteArray?, hasPassphrase: Boolean): Long = 1
        override suspend fun deleteWallet(id: Long) {}
        override suspend fun getStoredMnemonic(id: Long): ByteArray? = null
        override suspend fun renameWallet(id: Long, name: String) {}
        override suspend fun clearMasterKey(id: Long) {}
        override suspend fun restoreMasterKey(id: Long, masterSeed: ByteArray, mnemonic: ByteArray?) {}
    }

    private class StubKeyProvider : KeyProvider {
        override suspend fun <T> withMasterSeed(walletId: Long, block: suspend (ByteArray) -> T): T = block(ByteArray(0))
        override suspend fun <T> withTransientSeed(mnemonic: CharArray, passphrase: CharArray, block: suspend (ByteArray) -> T): T = block(ByteArray(0))
    }
}
