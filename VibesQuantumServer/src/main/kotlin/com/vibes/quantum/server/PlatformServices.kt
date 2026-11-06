package com.vibes.quantum.server

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val appId: String,
    val createdAt: String,
    val updatedAt: String,
    val lastSequence: Long,
)

@Serializable
data class ConversationMessage(
    val conversationId: String,
    val sequence: Long,
    val messageId: String,
    val clientMessageId: String? = null,
    val role: String,
    val content: String,
    val createdAt: String,
)

/** Transactional conversation store. Sequence allocation and idempotency checks share one transaction. */
class ConversationRepository(private val database: VibesDatabase) {
    fun list(userId: String, limit: Int = 50): List<ConversationSummary> = database.connection().use { c ->
        c.prepareStatement("SELECT conversation_id,title,app_id,created_at_ms,updated_at_ms,last_sequence FROM conversations WHERE user_id=? AND archived_at_ms IS NULL ORDER BY updated_at_ms DESC LIMIT ?").use { p ->
            p.setString(1, userId); p.setInt(2, limit.coerceIn(1, 100)); p.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ConversationSummary(rs.getString(1), rs.getString(2), rs.getString(3), iso(rs.getLong(4)), iso(rs.getLong(5)), rs.getLong(6))) }
            }
        }
    }

    fun append(userId: String, appId: String, conversationId: String, role: String, content: String, clientMessageId: String? = null): ConversationMessage = database.transaction { c ->
        require(userId.isNotBlank() && conversationId.matches(Regex("[A-Za-z0-9_-]{8,80}")))
        require(role in setOf("user", "assistant", "system") && content.isNotBlank() && content.length <= 60_000)
        clientMessageId?.let { require(it.matches(Regex("[A-Za-z0-9._:-]{8,100}"))) }
        if (clientMessageId != null) findClientMessage(c, userId, clientMessageId)?.let { return@transaction it }
        val now = System.currentTimeMillis()
        val owned = c.prepareStatement("SELECT 1 FROM conversations WHERE conversation_id=? AND user_id=?").use { p -> p.setString(1, conversationId); p.setString(2, userId); p.executeQuery().use { it.next() } }
        if (!owned) {
            val exists = c.prepareStatement("SELECT 1 FROM conversations WHERE conversation_id=?").use { p -> p.setString(1, conversationId); p.executeQuery().use { it.next() } }
            require(!exists) { "Conversation does not belong to this account" }
            c.prepareStatement("INSERT INTO conversations(conversation_id,user_id,app_id,title,created_at_ms,updated_at_ms,last_sequence) VALUES(?,?,?,?,?,?,0)").use { p ->
                p.setString(1, conversationId); p.setString(2, userId); p.setString(3, appId.take(100)); p.setString(4, "New conversation"); p.setLong(5, now); p.setLong(6, now); p.executeUpdate()
            }
        }
        c.prepareStatement("UPDATE conversations SET last_sequence=last_sequence+1,updated_at_ms=? WHERE conversation_id=? AND user_id=?").use { p -> p.setLong(1, now); p.setString(2, conversationId); p.setString(3, userId); check(p.executeUpdate() == 1) }
        val sequence = c.prepareStatement("SELECT last_sequence FROM conversations WHERE conversation_id=? AND user_id=?").use { p -> p.setString(1, conversationId); p.setString(2, userId); p.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) } }
        val message = ConversationMessage(conversationId, sequence, UUID.randomUUID().toString(), clientMessageId, role, content, iso(now))
        c.prepareStatement("INSERT INTO conversation_messages(conversation_id,sequence_no,message_id,user_id,client_message_id,role,content,created_at_ms) VALUES(?,?,?,?,?,?,?,?)").use { p ->
            p.setString(1, conversationId); p.setLong(2, sequence); p.setString(3, message.messageId); p.setString(4, userId); p.setString(5, clientMessageId); p.setString(6, role); p.setString(7, content); p.setLong(8, now); p.executeUpdate()
        }
        if (role == "user" && sequence == 1L) c.prepareStatement("UPDATE conversations SET title=? WHERE conversation_id=? AND user_id=?").use { p -> p.setString(1, content.replace(Regex("\\s+"), " ").take(72)); p.setString(2, conversationId); p.setString(3, userId); p.executeUpdate() }
        message
    }

    fun replay(userId: String, conversationId: String, afterSequence: Long, limit: Int = 500): List<ConversationMessage> = database.connection().use { c ->
        c.prepareStatement("SELECT m.sequence_no,m.message_id,m.client_message_id,m.role,m.content,m.created_at_ms FROM conversation_messages m JOIN conversations v ON v.conversation_id=m.conversation_id WHERE m.conversation_id=? AND v.user_id=? AND m.sequence_no>? ORDER BY m.sequence_no ASC LIMIT ?").use { p ->
            p.setString(1, conversationId); p.setString(2, userId); p.setLong(3, afterSequence.coerceAtLeast(0)); p.setInt(4, limit.coerceIn(1, 500)); p.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ConversationMessage(conversationId, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), iso(rs.getLong(6)))) }
            }
        }
    }

    fun acknowledge(userId: String, deviceId: String, conversationId: String, sequence: Long): Boolean = database.transaction { c ->
        val allowed = c.prepareStatement("SELECT last_sequence FROM conversations WHERE conversation_id=? AND user_id=?").use { p -> p.setString(1, conversationId); p.setString(2, userId); p.executeQuery().use { rs -> rs.next() && sequence in 0..rs.getLong(1) } }
        if (!allowed) return@transaction false
        c.prepareStatement("INSERT INTO conversation_device_cursors(user_id,device_id,conversation_id,acknowledged_sequence,updated_at_ms) VALUES(?,?,?,?,?) ON CONFLICT(user_id,device_id,conversation_id) DO UPDATE SET acknowledged_sequence=CASE WHEN excluded.acknowledged_sequence>conversation_device_cursors.acknowledged_sequence THEN excluded.acknowledged_sequence ELSE conversation_device_cursors.acknowledged_sequence END,updated_at_ms=excluded.updated_at_ms").use { p -> p.setString(1, userId); p.setString(2, deviceId.take(120)); p.setString(3, conversationId); p.setLong(4, sequence); p.setLong(5, System.currentTimeMillis()); p.executeUpdate() }
        true
    }

    fun canAccess(userId: String, conversationId: String): Boolean = database.connection().use { c -> c.prepareStatement("SELECT 1 FROM conversations WHERE conversation_id=? AND user_id=? AND archived_at_ms IS NULL").use { p -> p.setString(1, conversationId); p.setString(2, userId); p.executeQuery().use { it.next() } } }

    private fun findClientMessage(c: Connection, userId: String, clientId: String): ConversationMessage? = c.prepareStatement("SELECT conversation_id,sequence_no,message_id,client_message_id,role,content,created_at_ms FROM conversation_messages WHERE user_id=? AND client_message_id=?").use { p ->
        p.setString(1, userId); p.setString(2, clientId); p.executeQuery().use { rs -> if (!rs.next()) null else ConversationMessage(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), iso(rs.getLong(7))) }
    }
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
}

@Serializable
data class EntitlementSnapshot(
    val userId: String,
    val tier: String,
    val status: String,
    val productId: String? = null,
    val expiresAt: String? = null,
    val dailyCloudCalls: Int,
    val dailySpendCapUsd: Double,
    val callsUsedToday: Long,
    val spendUsedTodayUsd: Double,
    val spendReservedUsd: Double,
)

@Serializable
data class CloudCallReservation(val allowed: Boolean, val reason: String? = null, val tier: String = "FREE", val estimateUsd: Double = 0.0, val reservationId: String? = null)

/** Tier policies are server-owned and overridable with environment variables. */
class EntitlementStore(private val database: VibesDatabase) {
    private data class Policy(val calls: Int, val spend: Double)
    private val policies = mapOf(
        "FREE" to Policy(envInt("VIBES_FREE_DAILY_CLOUD_CALLS", 10), envDouble("VIBES_FREE_DAILY_SPEND_USD", 0.10)),
        "PRO_LITE" to Policy(envInt("VIBES_PRO_LITE_DAILY_CLOUD_CALLS", 100), envDouble("VIBES_PRO_LITE_DAILY_SPEND_USD", 1.00)),
        "PRO" to Policy(envInt("VIBES_PRO_DAILY_CLOUD_CALLS", 500), envDouble("VIBES_PRO_DAILY_SPEND_USD", 5.00)),
        "PRO_PLUS" to Policy(envInt("VIBES_PRO_PLUS_DAILY_CLOUD_CALLS", 2000), envDouble("VIBES_PRO_PLUS_DAILY_SPEND_USD", 20.00)),
    )

    fun snapshot(userId: String): EntitlementSnapshot = database.connection().use { c ->
        val tierRow = c.prepareStatement("SELECT tier,status,product_id,expires_at_ms FROM subscriptions WHERE user_id=?").use { p -> p.setString(1, userId); p.executeQuery().use { rs -> if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) else emptyList() } }
        val expiry = tierRow.getOrNull(3)?.toLongOrNull()
        val active = tierRow.getOrNull(1) == "ACTIVE" && (expiry == null || expiry > System.currentTimeMillis())
        val tier = if (active) tierRow.getOrNull(0)?.uppercase()?.takeIf { it in policies } ?: "FREE" else "FREE"
        val policy = policies.getValue(tier)
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val usage = c.prepareStatement("SELECT llm_calls+audio_calls,spent_usd,reserved_usd FROM cloud_spend_daily WHERE user_id=? AND utc_day=?").use { p -> p.setString(1, userId); p.setString(2, day); p.executeQuery().use { rs -> if (rs.next()) Triple(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)) else Triple(0L, 0.0, 0.0) } }
        EntitlementSnapshot(userId, tier, if (active) "ACTIVE" else "FREE", tierRow.getOrNull(2), expiry?.let { Instant.ofEpochMilli(it).toString() }, policy.calls, policy.spend, usage.first, usage.second, usage.third)
    }

    /** Reserves quota atomically before a cloud request is sent. Rejected requests never reach providers. */
    fun reserveCloudCall(userId: String, service: String, estimatedUsd: Double): CloudCallReservation = database.transaction { c ->
        require(userId.isNotBlank() && service in setOf("llm", "stt", "tts", "audio"))
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        // Ensure the user's counter row exists, then take a write lock before checking limits.
        c.prepareStatement("INSERT INTO cloud_spend_daily(user_id,utc_day,reserved_usd,spent_usd,llm_calls,audio_calls,tokens,updated_at_ms) VALUES(?,?,0,0,0,0,0,?) ON CONFLICT(user_id,utc_day) DO NOTHING").use { p -> p.setString(1, userId); p.setString(2, day); p.setLong(3, System.currentTimeMillis()); p.executeUpdate() }
        c.prepareStatement("UPDATE cloud_spend_daily SET updated_at_ms=updated_at_ms WHERE user_id=? AND utc_day=?").use { p -> p.setString(1, userId); p.setString(2, day); p.executeUpdate() }
        val snap = entitlementInTransaction(c, userId)
        val policy = policies.getValue(snap.tier)
        val estimate = estimatedUsd.takeIf { it.isFinite() && it > 0 } ?: 0.001
        if (snap.callsUsedToday >= policy.calls) return@transaction CloudCallReservation(false, "Daily cloud call allowance reached", snap.tier, estimate)
        if (snap.spendUsedTodayUsd + snap.spendReservedUsd + estimate > policy.spend + 1e-9) return@transaction CloudCallReservation(false, "Daily cloud spend cap reached", snap.tier, estimate)
        val counter = if (service == "llm") "llm_calls" else "audio_calls"
        c.prepareStatement("UPDATE cloud_spend_daily SET reserved_usd=reserved_usd+?, $counter=$counter+1,updated_at_ms=? WHERE user_id=? AND utc_day=?").use { p -> p.setDouble(1, estimate); p.setLong(2, System.currentTimeMillis()); p.setString(3, userId); p.setString(4, day); check(p.executeUpdate() == 1) }
        val reservationId = UUID.randomUUID().toString()
        c.prepareStatement("INSERT INTO cloud_spend_reservations(reservation_id,user_id,utc_day,service,estimated_usd,state,created_at_ms) VALUES(?,?,?,?,?,?,?)").use { p ->
            p.setString(1, reservationId); p.setString(2, userId); p.setString(3, day); p.setString(4, service); p.setDouble(5, estimate); p.setString(6, "RESERVED"); p.setLong(7, System.currentTimeMillis()); p.executeUpdate()
        }
        CloudCallReservation(true, null, snap.tier, estimate, reservationId)
    }

    /** Exactly-once settlement. A retry cannot double-charge a call, even if a response handler runs twice. */
    fun settleCloudCall(userId: String, service: String, reservation: CloudCallReservation, actualUsd: Double, tokens: Long = 0, provider: String? = null) {
        if (!reservation.allowed) return
        val reservationId = reservation.reservationId ?: return
        val actual = actualUsd.takeIf { it.isFinite() && it >= 0 } ?: reservation.estimateUsd
        database.transaction { c ->
            val row = c.prepareStatement("SELECT utc_day,service,estimated_usd,state FROM cloud_spend_reservations WHERE reservation_id=? AND user_id=?").use { p ->
                p.setString(1, reservationId); p.setString(2, userId); p.executeQuery().use { rs -> if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getString(4)) else null }
            } ?: return@transaction
            if (row[3] != "RESERVED") return@transaction
            val day = row[0] as String
            val actualService = row[1] as String
            val estimate = row[2] as Double
            c.prepareStatement("UPDATE cloud_spend_reservations SET state='SETTLED',settled_at_ms=? WHERE reservation_id=? AND state='RESERVED'").use { p -> p.setLong(1, System.currentTimeMillis()); p.setString(2, reservationId); if (p.executeUpdate() != 1) return@transaction }
            c.prepareStatement("UPDATE cloud_spend_daily SET reserved_usd=CASE WHEN reserved_usd>=? THEN reserved_usd-? ELSE 0 END,spent_usd=spent_usd+?,tokens=tokens+?,updated_at_ms=? WHERE user_id=? AND utc_day=?").use { p ->
                p.setDouble(1, estimate); p.setDouble(2, estimate); p.setDouble(3, actual); p.setLong(4, tokens.coerceAtLeast(0)); p.setLong(5, System.currentTimeMillis()); p.setString(6, userId); p.setString(7, day); check(p.executeUpdate() == 1)
            }
            c.prepareStatement("INSERT INTO cloud_spend_events(event_id,user_id,utc_day,service,provider,estimated_usd,actual_usd,token_count,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?)").use { p ->
                p.setString(1, reservationId); p.setString(2, userId); p.setString(3, day); p.setString(4, actualService); p.setString(5, provider); p.setDouble(6, estimate); p.setDouble(7, actual); p.setLong(8, tokens.coerceAtLeast(0)); p.setLong(9, System.currentTimeMillis()); p.executeUpdate()
            }
        }
    }

    fun applyVerifiedPurchase(userId: String, productId: String, tokenHash: String, tier: String, expiryMillis: Long) = database.transaction { c ->
        require(tier in policies && expiryMillis > System.currentTimeMillis())
        c.prepareStatement("SELECT user_id FROM billing_purchases WHERE purchase_token_hash=?").use { p -> p.setString(1, tokenHash); p.executeQuery().use { rs -> if (rs.next()) require(rs.getString(1) == userId) { "Purchase token is already linked to another account" } } }
        c.prepareStatement("DELETE FROM billing_purchases WHERE purchase_token_hash=?").use { p -> p.setString(1, tokenHash); p.executeUpdate() }
        c.prepareStatement("INSERT INTO billing_purchases(purchase_token_hash,user_id,package_name,product_id,tier,purchase_state,expires_at_ms,verified_at_ms) VALUES(?,?,?,?,?,?,?,?)").use { p ->
            p.setString(1, tokenHash); p.setString(2, userId); p.setString(3, System.getenv("VIBES_PLAY_PACKAGE_NAME") ?: ""); p.setString(4, productId); p.setString(5, tier); p.setString(6, "ACTIVE"); p.setLong(7, expiryMillis); p.setLong(8, System.currentTimeMillis()); p.executeUpdate()
        }
        c.prepareStatement("DELETE FROM subscriptions WHERE user_id=?").use { p -> p.setString(1, userId); p.executeUpdate() }
        c.prepareStatement("INSERT INTO subscriptions(user_id,tier,product_id,purchase_token_hash,status,expires_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?)").use { p ->
            p.setString(1, userId); p.setString(2, tier); p.setString(3, productId); p.setString(4, tokenHash); p.setString(5, "ACTIVE"); p.setLong(6, expiryMillis); p.setLong(7, System.currentTimeMillis()); p.executeUpdate()
        }
    }

    private fun entitlementInTransaction(c: Connection, userId: String): EntitlementSnapshot {
        val current = c.prepareStatement("SELECT tier,status,product_id,expires_at_ms FROM subscriptions WHERE user_id=?").use { p -> p.setString(1, userId); p.executeQuery().use { rs -> if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else emptyList() } }
        val expiry = (current.getOrNull(3) as? Long)?.takeIf { it > 0 }
        val active = current.getOrNull(1) == "ACTIVE" && (expiry == null || expiry > System.currentTimeMillis())
        val tier = if (active) current.getOrNull(0)?.uppercase()?.takeIf { it in policies } ?: "FREE" else "FREE"
        val policy = policies.getValue(tier); val day = LocalDate.now(ZoneOffset.UTC).toString()
        val usage = c.prepareStatement("SELECT llm_calls+audio_calls,spent_usd,reserved_usd FROM cloud_spend_daily WHERE user_id=? AND utc_day=?").use { p -> p.setString(1, userId); p.setString(2, day); p.executeQuery().use { rs -> if (rs.next()) Triple(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)) else Triple(0L,0.0,0.0) } }
        return EntitlementSnapshot(userId, tier, if (active) "ACTIVE" else "FREE", current.getOrNull(2) as? String, expiry?.let { Instant.ofEpochMilli(it).toString() }, policy.calls, policy.spend, usage.first, usage.second, usage.third)
    }

    companion object {
        private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: default
        private fun envDouble(key: String, default: Double) = System.getenv(key)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: default
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
