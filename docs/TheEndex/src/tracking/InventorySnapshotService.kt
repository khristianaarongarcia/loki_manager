package org.lokixcz.theendex.tracking

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.lokixcz.theendex.Endex
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Online-only, lightweight inventory snapshot service with TTL cache.
 * - Counts stack amounts by Material for online players only
 * - Optionally includes Ender Chest per config
 * - Exposes per-player snapshot and aggregated totals
 * - Safe to call from async threads (uses callSyncMethod for Bukkit access)
 */
class InventorySnapshotService(private val plugin: Endex) {

    private val enabledFlag: Boolean = runCatching { plugin.config.getBoolean("web.holdings.inventory.enabled", true) }.getOrDefault(true)
    private val includeEnder: Boolean = runCatching { plugin.config.getBoolean("web.holdings.inventory.include-enderchest", true) }.getOrDefault(true)
    private val cacheSeconds: Int = runCatching { plugin.config.getInt("web.holdings.inventory.cache-seconds", 5) }.getOrDefault(5).coerceAtLeast(1)

    // Per-player cache: uuid -> (epochSecond, snapshot)
    private val perPlayerCache: MutableMap<UUID, Pair<Long, Map<Material, Int>>> = mutableMapOf()
    // Totals cache: (epochSecond, totals)
    @Volatile private var totalsCache: Pair<Long, Map<Material, Int>>? = null

    fun enabled(): Boolean = enabledFlag

    fun onlinePlayerCount(): Int {
        if (!enabledFlag) return 0
        return runSync { Bukkit.getOnlinePlayers().size } ?: 0
    }

    /** Snapshot for a specific player (online-only). Returns empty if player is offline or service disabled. */
    fun snapshotFor(uuid: UUID): Map<Material, Int> {
        if (!enabledFlag) return emptyMap()
        val now = nowSeconds()
        // Serve from cache if fresh
        synchronized(perPlayerCache) {
            val cached = perPlayerCache[uuid]
            if (cached != null && (now - cached.first) <= cacheSeconds) return cached.second
        }
        val snapshot: Map<Material, Int> = runSync {
            val p = Bukkit.getPlayer(uuid) ?: return@runSync emptyMap<Material, Int>()
            computeSnapshot(p)
        } ?: emptyMap()
        synchronized(perPlayerCache) {
            perPlayerCache[uuid] = now to snapshot
        }
        return snapshot
    }

    /** Aggregated totals across all online players. Cached by TTL. */
    fun snapshotTotals(): Map<Material, Int> {
        if (!enabledFlag) return emptyMap()
        val now = nowSeconds()
        val cached = totalsCache
        if (cached != null && (now - cached.first) <= cacheSeconds) return cached.second

        val totals: Map<Material, Int> = runSync {
            val players = Bukkit.getOnlinePlayers().toList()
            val agg = HashMap<Material, Int>()
            for (p in players) {
                val snap = computeSnapshot(p)
                // Update per-player cache as a side effect
                synchronized(perPlayerCache) { perPlayerCache[p.uniqueId] = now to snap }
                for ((mat, qty) in snap) {
                    agg[mat] = (agg[mat] ?: 0) + qty
                }
            }
            agg
        } ?: emptyMap()

        totalsCache = now to totals
        return totals
    }

    private fun computeSnapshot(player: Player): Map<Material, Int> {
        val map = HashMap<Material, Int>()
        // Main inventory (including armor/offhand are separate; we focus on contents only)
        for (stack in player.inventory.contents) {
            if (stack == null || stack.type.isAir) continue
            val amt = stack.amount
            if (amt <= 0) continue
            map[stack.type] = (map[stack.type] ?: 0) + amt
        }
        // Optionally include Ender Chest
        if (includeEnder) {
            for (stack in player.enderChest.contents) {
                if (stack == null || stack.type.isAir) continue
                val amt = stack.amount
                if (amt <= 0) continue
                map[stack.type] = (map[stack.type] ?: 0) + amt
            }
        }
        return map
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun <T> runSync(task: () -> T): T? {
        return try {
            if (Bukkit.isPrimaryThread()) return task()
            val fut = Bukkit.getScheduler().callSyncMethod(plugin, Callable { task() })
            fut.get(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        }
    }
}
