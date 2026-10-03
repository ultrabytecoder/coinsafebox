package com.ultrabytecoder.coinsafebox.sqlcipher

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Empirical probe: does a SQLDelight `asFlow()` query re-emit when rows are
 * upserted through the generated query functions on the native SQLCipher driver?
 *
 * This isolates the reactive-notification chain (generated `notifyQueries` ->
 * `TransacterImpl` commit hook -> `driver.notifyListeners` -> `asFlow` listener)
 * independent of any ViewModel.
 */
class SqlDelightNotifyProbeTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("coinsafebox-notify-probe", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun asFlowReEmitsAfterUpsertTransaction() = runBlocking {
        val dbFile = File(tempDir, "probe.db")
        val driver = NativeSqlCipherDriver(
            dbPath = dbFile.absolutePath,
            key = "probe-key".toByteArray(),
            schema = CoinSafeBoxDatabase.Schema,
            migrateEmptySchema = true
        )
        val database = CoinSafeBoxDatabase(driver)
        val queries = database.coinSafeBoxDatabaseQueries

        // Satisfy the FK chain (transactions -> accounts -> wallets) so upserts are legal.
        queries.insertWallet(id = 1L, name = "w", master_seed = byteArrayOf(1), mnemonic = null, has_passphrase = 0L)
        queries.insert(
            id = "acct-1",
            wallet_id = 1L,
            name = "A",
            amount = "0",
            type = "BTC",
            address = "addr",
            account_index = 0L,
            derivation_path = "m/0",
            params = null,
            symbol = "BTC",
            parent_account_id = null,
            token_address = null
        )

        // Emission row-counts, delivered from the collector (Dispatchers.Default) to this
        // test coroutine via a Channel — thread-safe, no shared mutable list.
        val emissions = Channel<Long>(Channel.UNLIMITED)

        // Subscribe first (mirrors the repository's getTransactionsByAccountFlow).
        val collector = launch(Dispatchers.Default) {
            queries.selectTransactionsByAccount("acct-1", Long.MAX_VALUE, 0)
                .asFlow()
                .mapToList(Dispatchers.Default)
                .collect { emissions.send(it.size.toLong()) }
        }

        // The initial (pre-write) emission is the empty list. receive() suspends until it
        // arrives — no fixed delay, so it is robust on a loaded CI runner.
        val initial = withTimeout(3000) { emissions.receive() }
        assertEquals(0L, initial, "initial emission should be the empty list before the write")

        // Write a transaction through the generated query, in a transaction (mirrors upsertAll).
        queries.transaction {
            queries.upsertTransaction(
                id = "tx-1",
                account_id = "acct-1",
                tx_hash = "hash-1",
                direction = "SEND",
                amount = "1.0",
                fee = "0.001",
                timestamp = 1000L,
                status = "CONFIRMED",
                counterparty_address = "addr",
                block_height = 100L,
                chain_data = null
            )
        }

        // The committed upsert must notify the flow so it re-emits a non-empty result, with
        // no manual re-read. Tolerate any extra 0-emissions; wait for the first non-zero count.
        var updated: Long? = null
        withTimeout(3000) {
            while (updated == null) {
                val count = emissions.receive()
                if (count >= 1L) updated = count
            }
        }
        assertTrue(updated != null, "asFlow() did not re-emit a non-empty result after upsertTransaction")

        collector.cancel()
        emissions.close()
        driver.close()
    }
}
