package com.ultrabytecoder.coinsafebox.data.walletconnect

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ultrabytecoder.coinsafebox.data.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

interface WcSessionRepository {
    suspend fun getAll(): List<WcSession>
    suspend fun get(topic: String): WcSession?
    suspend fun upsert(session: WcSession)
    suspend fun delete(topic: String)
}

internal class SqlWcSessionRepository(
    private val databaseProvider: DatabaseProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WcSessionRepository {

    private val queries get() = databaseProvider.database().coinSafeBoxDatabaseQueries
    private val namespacesSerializer = MapSerializer(String.serializer(), WcNamespace.serializer())

    override suspend fun getAll(): List<WcSession> = withContext(Dispatchers.IO) {
        queries.selectAllWcSessions().executeAsList().mapNotNull { row ->
            // A corrupt row (e.g. partial write / bad JSON) must not take down the whole load;
            // drop it and move on.
            runCatching { row.toWcSession() }.getOrElse {
                println("WcSessionRepository: dropping corrupt session row ${row.topic} — ${it.message}")
                runCatching { queries.deleteWcSessionByTopic(row.topic) }
                null
            }
        }
    }

    override suspend fun get(topic: String): WcSession? = withContext(Dispatchers.IO) {
        queries.selectWcSessionByTopic(topic).executeAsOneOrNull()
            ?.let { row -> runCatching { row.toWcSession() }.getOrNull() }
    }

    override suspend fun upsert(session: WcSession) {
        withContext(Dispatchers.IO) {
            queries.upsertWcSession(
                topic = session.topic,
                pairing_topic = session.pairingTopic,
                proposer_public_key = session.proposerPublicKey,
                proposer_metadata = json.encodeToString(WcMetadata.serializer(), session.proposerMetadata),
                responder_public_key = session.responderPublicKey,
                responder_metadata = json.encodeToString(WcMetadata.serializer(), session.responderMetadata),
                namespaces = json.encodeToString(namespacesSerializer, session.namespaces),
                expiry = session.expiry,
                acknowledged = session.acknowledged,
                settle_request_id = session.settleRequestId,
            )
        }
    }

    override suspend fun delete(topic: String) {
        withContext(Dispatchers.IO) {
            queries.deleteWcSessionByTopic(topic)
        }
    }

    private fun com.ultrabytecoder.coinsafebox.db.Wc_sessions.toWcSession(): WcSession = WcSession(
        topic = topic,
        pairingTopic = pairing_topic,
        proposerPublicKey = proposer_public_key,
        proposerMetadata = json.decodeFromString(WcMetadata.serializer(), proposer_metadata),
        responderPublicKey = responder_public_key,
        responderMetadata = json.decodeFromString(WcMetadata.serializer(), responder_metadata),
        namespaces = json.decodeFromString(namespacesSerializer, namespaces),
        expiry = expiry,
        acknowledged = acknowledged,
        settleRequestId = settle_request_id,
    )
}
