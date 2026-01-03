package org.lokixcz.theendex.web

import org.bukkit.entity.Player
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class WebSession(
    val token: String,
    val playerUuid: UUID,
    val playerName: String,
    val role: String,
    val createdAt: Instant,
    val expiresAt: Instant
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}

class SessionManager {
    private val sessions = ConcurrentHashMap<String, WebSession>()
    private val playerSessions = ConcurrentHashMap<UUID, String>()
    
    companion object {
        private const val SESSION_DURATION_HOURS = 2L
    }
    
    fun createSession(player: Player, role: String): WebSession {
        // Invalidate existing session for this player
        invalidatePlayerSession(player.uniqueId)
        
        val token = generateToken()
        val now = Instant.now()
        val session = WebSession(
            token = token,
            playerUuid = player.uniqueId,
            playerName = player.name,
            role = role,
            createdAt = now,
            expiresAt = now.plusSeconds(SESSION_DURATION_HOURS * 3600)
        )
        
        sessions[token] = session
        playerSessions[player.uniqueId] = token
        
        return session
    }
    
    fun getSession(token: String): WebSession? {
        val session = sessions[token] ?: return null
        return if (session.isExpired()) {
            invalidateSession(token)
            null
        } else {
            session
        }
    }
    
    fun invalidateSession(token: String) {
        val session = sessions.remove(token)
        if (session != null) {
            playerSessions.remove(session.playerUuid)
        }
    }
    
    fun invalidatePlayerSession(playerUuid: UUID) {
        val existingToken = playerSessions.remove(playerUuid)
        if (existingToken != null) {
            sessions.remove(existingToken)
        }
    }
    
    fun cleanupExpiredSessions() {
        val expiredTokens = sessions.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        
        expiredTokens.forEach { token ->
            invalidateSession(token)
        }
    }
    
    private fun generateToken(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
}