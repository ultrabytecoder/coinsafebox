package com.ultrabytecoder.coinsafebox.data.walletconnect

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ultrabytecoder.coinsafebox.data.DatabaseProvider
import com.ultrabytecoder.coinsafebox.db.CoinSafeBoxDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real [SqlWcSessionRepository] against a genuine (in-memory) SQLite
 * database, verifying the SQLDelight-generated `wc_sessions` round-trips correctly —
 * including the JSON-encoded metadata and namespaces columns.
 */
class WcSessionRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SqlWcSessionRepository

    private val testSession = WcSession(
        topic = "topic-abc",
        pairingTopic = "pairing-xyz",
        proposerPublicKey = "prop-pub",
        proposerMetadata = WcMetadata(
            name = "DApp",
            description = "A dapp",
            url = "https://dapp.example",
            icons = listOf("https://dapp.example/icon.png"),
        ),
        responderPublicKey = "resp-pub",
        responderMetadata = WcMetadata(name = "Wallet", description = "A wallet", url = "https://wallet.example"),
        namespaces = mapOf(
            "eip155" to WcNamespace(
                accounts = listOf("eip155:1:0xaddress"),
                methods = listOf("personal_sign", "eth_sendTransaction"),
                events = listOf("accountsChanged", "chainChanged"),
            )
        ),
        expiry = 1_893_456_000_000L,
        acknowledged = true,
        settleRequestId = 42L,
    )

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CoinSafeBoxDatabase.Schema.create(driver)
        val database = CoinSafeBoxDatabase(driver)
        val provider = object : DatabaseProvider {
            override fun database(): CoinSafeBoxDatabase = database
        }
        repository = SqlWcSessionRepository(provider)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun upsertThenGetRoundTrips() = runBlocking {
        repository.upsert(testSession)
        val loaded = repository.get("topic-abc")
        assertNotNull(loaded)
        assertEquals(testSession, loaded)
    }

    @Test
    fun getAllReturnsStoredSessions() = runBlocking {
        repository.upsert(testSession)
        repository.upsert(testSession.copy(topic = "topic-2", settleRequestId = null))
        val all = repository.getAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.topic == "topic-abc" })
        assertTrue(all.any { it.topic == "topic-2" })
    }

    @Test
    fun upsertUpdatesExisting() = runBlocking {
        repository.upsert(testSession)
        repository.upsert(testSession.copy(acknowledged = false, expiry = 1L))
        val loaded = repository.get("topic-abc")
        assertNotNull(loaded)
        assertEquals(false, loaded!!.acknowledged)
        assertEquals(1L, loaded.expiry)
    }

    @Test
    fun deleteRemovesSession() = runBlocking {
        repository.upsert(testSession)
        repository.delete("topic-abc")
        assertNull(repository.get("topic-abc"))
    }

    @Test
    fun emptyDatabaseReturnsEmpty() = runBlocking {
        assertTrue(repository.getAll().isEmpty())
        assertNull(repository.get("nope"))
    }
}
