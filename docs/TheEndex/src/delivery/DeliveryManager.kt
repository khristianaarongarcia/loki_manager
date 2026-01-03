package org.lokixcz.theendex.delivery

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.util.EndexLogger
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Manages pending item deliveries for players when inventory is full during purchases.
 * Items are stored in database and can be claimed later via GUI or commands.
 */
class DeliveryManager(private val plugin: JavaPlugin) {
    private val logger = EndexLogger(plugin)
    private val dbFile = File(plugin.dataFolder, "deliveries.db")
    
    private val enabled: Boolean
        get() = plugin.config.getBoolean("delivery.enabled", true)
    
    private val maxPendingPerPlayer: Int
        get() = plugin.config.getInt("delivery.max-pending-per-player", 10000)
    
    private val autoClaimOnLogin: Boolean
        get() = plugin.config.getBoolean("delivery.auto-claim-on-login", false)
    
    private fun connect(): Connection {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
    }

    /**
     * Initialize database schema for pending deliveries.
     */
    fun init() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS pending_deliveries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        material TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pending_player ON pending_deliveries(player_uuid)")
            }
        }
        logger.info("Delivery system initialized (enabled: $enabled, max per player: $maxPendingPerPlayer)")
    }

    /**
     * Add items to pending deliveries for a player.
     * Uses atomic transaction to prevent race conditions during concurrent purchases.
     * Returns true if stored successfully, false if delivery system disabled or limit exceeded.
     */
    fun addPending(playerUuid: UUID, material: Material, amount: Int): Boolean {
        if (!enabled) return false
        if (amount <= 0) return false
        
        try {
            connect().use { conn ->
                // Use immediate transaction for concurrency safety
                conn.autoCommit = false
                
                try {
                    // Check current pending count within transaction
                    val currentPending = conn.prepareStatement(
                        "SELECT COALESCE(SUM(amount), 0) FROM pending_deliveries WHERE player_uuid=?"
                    ).use { ps ->
                        ps.setString(1, playerUuid.toString())
                        val rs = ps.executeQuery()
                        if (rs.next()) rs.getInt(1) else 0
                    }
                    
                    // Check if adding this amount would exceed limit
                    if (currentPending + amount > maxPendingPerPlayer) {
                        logger.warn("Player $playerUuid reached pending delivery limit ($maxPendingPerPlayer)")
                        conn.rollback()
                        return false
                    }
                    
                    // Insert new pending delivery
                    conn.prepareStatement(
                        "INSERT INTO pending_deliveries(player_uuid, material, amount, timestamp) VALUES(?,?,?,?)"
                    ).use { ps ->
                        ps.setString(1, playerUuid.toString())
                        ps.setString(2, material.name)
                        ps.setInt(3, amount)
                        ps.setString(4, Instant.now().toString())
                        ps.executeUpdate()
                    }
                    
                    // Commit transaction - both check and insert succeed atomically
                    conn.commit()
                    return true
                    
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to add pending delivery for $playerUuid: ${e.message}")
            return false
        }
    }

    /**
     * Get total amount of pending items across all materials for a player.
     */
    fun getPendingTotalAmount(playerUuid: UUID): Int {
        try {
            connect().use { conn ->
                conn.prepareStatement(
                    "SELECT SUM(amount) FROM pending_deliveries WHERE player_uuid=?"
                ).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to get pending count for $playerUuid: ${e.message}")
        }
        return 0
    }

    /**
     * Get all pending deliveries for a player, grouped by material.
     * Returns map of Material -> total pending amount.
     */
    fun listPending(playerUuid: UUID): Map<Material, Int> {
        val result = mutableMapOf<Material, Int>()
        try {
            connect().use { conn ->
                conn.prepareStatement(
                    "SELECT material, SUM(amount) as total FROM pending_deliveries WHERE player_uuid=? GROUP BY material"
                ).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val mat = Material.matchMaterial(rs.getString("material")) ?: continue
                        result[mat] = rs.getInt("total")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to list pending deliveries for $playerUuid: ${e.message}")
        }
        return result
    }

    /**
     * Get detailed pending deliveries (individual records) for a player.
     * Returns list of PendingDelivery objects.
     */
    fun listPendingDetailed(playerUuid: UUID): List<PendingDelivery> {
        val result = mutableListOf<PendingDelivery>()
        try {
            connect().use { conn ->
                conn.prepareStatement(
                    "SELECT id, material, amount, timestamp FROM pending_deliveries WHERE player_uuid=? ORDER BY timestamp ASC"
                ).use { ps ->
                    ps.setString(1, playerUuid.toString())
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val mat = Material.matchMaterial(rs.getString("material")) ?: continue
                        val timestamp = runCatching { Instant.parse(rs.getString("timestamp")) }.getOrNull() ?: Instant.now()
                        result.add(
                            PendingDelivery(
                                id = rs.getInt("id"),
                                playerUuid = playerUuid,
                                material = mat,
                                amount = rs.getInt("amount"),
                                timestamp = timestamp
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to list detailed pending deliveries for $playerUuid: ${e.message}")
        }
        return result
    }

    /**
     * Attempt to claim pending deliveries for a specific material.
     * Delivers as many items as inventory can hold, returns amount actually delivered.
     * Does NOT remove items from pending if inventory is full (partial claims supported).
     * Uses transaction safety to prevent duplication if database update fails.
     */
    fun claimMaterial(player: Player, material: Material, requestedAmount: Int = Int.MAX_VALUE): ClaimResult {
        if (!enabled) return ClaimResult(0, 0, "Delivery system is disabled")
        
        val pending = listPending(player.uniqueId)[material] ?: 0
        if (pending <= 0) {
            return ClaimResult(0, 0, "No pending deliveries for ${material.name}")
        }
        
        val toClaim = minOf(requestedAmount, pending)
        val capacity = calculateInventoryCapacity(player, material)
        val actualDelivered = minOf(toClaim, capacity)
        
        if (actualDelivered <= 0) {
            return ClaimResult(0, pending, "Inventory is full. Please make space and try again.")
        }
        
        // Remove from database FIRST (before giving items) to prevent duplication
        var toRemove = actualDelivered
        var dbUpdateSuccess = false
        
        try {
            connect().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        "SELECT id, amount FROM pending_deliveries WHERE player_uuid=? AND material=? ORDER BY timestamp ASC"
                    ).use { ps ->
                        ps.setString(1, player.uniqueId.toString())
                        ps.setString(2, material.name)
                        val rs = ps.executeQuery()
                        
                        val toDelete = mutableListOf<Int>()
                        val toUpdate = mutableListOf<Pair<Int, Int>>() // id, new amount
                        
                        while (rs.next() && toRemove > 0) {
                            val id = rs.getInt("id")
                            val amount = rs.getInt("amount")
                            
                            if (amount <= toRemove) {
                                // Delete entire record
                                toDelete.add(id)
                                toRemove -= amount
                            } else {
                                // Partial claim - update amount
                                toUpdate.add(id to (amount - toRemove))
                                toRemove = 0
                            }
                        }
                        
                        // Execute deletes
                        if (toDelete.isNotEmpty()) {
                            conn.prepareStatement(
                                "DELETE FROM pending_deliveries WHERE id IN (${toDelete.joinToString(",") { "?" }})"
                            ).use { delPs ->
                                toDelete.forEachIndexed { idx, id -> delPs.setInt(idx + 1, id) }
                                delPs.executeUpdate()
                            }
                        }
                        
                        // Execute updates
                        toUpdate.forEach { (id, newAmount) ->
                            conn.prepareStatement(
                                "UPDATE pending deliveries SET amount=? WHERE id=?"
                            ).use { updPs ->
                                updPs.setInt(1, newAmount)
                                updPs.setInt(2, id)
                                updPs.executeUpdate()
                            }
                        }
                    }
                    conn.commit()
                    dbUpdateSuccess = true
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to remove claimed deliveries for ${player.name}: ${e.message}")
            return ClaimResult(0, pending, "Database error while claiming items")
        }
        
        // Only give items if database update succeeded (prevents duplication)
        if (dbUpdateSuccess) {
            val stack = org.bukkit.inventory.ItemStack(material)
            var remaining = actualDelivered
            while (remaining > 0) {
                val toGive = kotlin.math.max(1, kotlin.math.min(remaining, stack.maxStackSize))
                val give = stack.clone()
                give.amount = toGive
                val leftovers = player.inventory.addItem(give)
                if (leftovers.isNotEmpty()) {
                    // Should not happen since we checked capacity, but safety fallback
                    leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                }
                remaining -= toGive
            }
        }
        
        val newPending = pending - actualDelivered
        return ClaimResult(actualDelivered, newPending, null)
    }

    /**
     * Claim all pending deliveries for a player (all materials).
     * Returns map of Material -> amount delivered, and total remaining.
     */
    fun claimAll(player: Player): ClaimAllResult {
        val pending = listPending(player.uniqueId)
        if (pending.isEmpty()) {
            return ClaimAllResult(emptyMap(), 0, "No pending deliveries")
        }
        
        val delivered = mutableMapOf<Material, Int>()
        var totalRemaining = 0
        
        pending.forEach { (material, amount) ->
            val result = claimMaterial(player, material, amount)
            if (result.delivered > 0) {
                delivered[material] = result.delivered
            }
            totalRemaining += result.remainingPending
        }
        
        return ClaimAllResult(delivered, totalRemaining, null)
    }

    /**
     * Calculate inventory capacity for a material (same logic as MarketCommand).
     */
    internal fun calculateInventoryCapacity(player: Player, material: Material): Int {
        val inv = player.inventory
        val maxStack = material.maxStackSize
        var capacity = 0
        
        // Count space in existing stacks of this material
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type == material) {
                capacity += (maxStack - stack.amount).coerceAtLeast(0)
            }
        }
        
        // Count empty slots (each can hold maxStack items)
        val emptySlots = inv.storageContents.count { it == null || it.type == org.bukkit.Material.AIR }
        capacity += emptySlots * maxStack
        
        return capacity
    }

    /**
     * Auto-claim deliveries for a player on login (if enabled in config).
     * Called by player join event listener.
     */
    fun tryAutoClaimOnLogin(player: Player) {
        if (!enabled || !autoClaimOnLogin) return
        
        val pending = listPending(player.uniqueId)
        if (pending.isEmpty()) return
        
        val result = claimAll(player)
        if (result.delivered.isNotEmpty()) {
            val total = result.delivered.values.sum()
            player.sendMessage(Lang.colorize(Lang.get("delivery-autoclaim.success", "total" to total.toString())))
            result.delivered.forEach { (mat, amt) ->
                player.sendMessage(Lang.colorize(Lang.get("delivery-autoclaim.item-line", "amount" to amt.toString(), "item" to mat.name)))
            }
            if (result.totalRemaining > 0) {
                player.sendMessage(Lang.colorize(Lang.get("delivery-autoclaim.remaining", "remaining" to result.totalRemaining.toString())))
            }
        }
    }

    /**
     * Get count of unique materials with pending deliveries (for badge display).
     */
    fun getPendingMaterialCount(playerUuid: UUID): Int {
        return listPending(playerUuid).size
    }

    /**
     * Clear all pending deliveries for a player (admin command).
     */
    fun clearAllPending(playerUuid: UUID): Int {
        try {
            connect().use { conn ->
                conn.prepareStatement("DELETE FROM pending_deliveries WHERE player_uuid=?").use { ps ->
                    ps.setString(1, playerUuid.toString())
                    return ps.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to clear pending deliveries for $playerUuid: ${e.message}")
            return 0
        }
    }
}

/**
 * Represents a pending delivery record.
 */
data class PendingDelivery(
    val id: Int,
    val playerUuid: UUID,
    val material: Material,
    val amount: Int,
    val timestamp: Instant
)

/**
 * Result of claiming a specific material.
 */
data class ClaimResult(
    val delivered: Int,
    val remainingPending: Int,
    val error: String?
)

/**
 * Result of claiming all materials.
 */
data class ClaimAllResult(
    val delivered: Map<Material, Int>,
    val totalRemaining: Int,
    val error: String?
)
