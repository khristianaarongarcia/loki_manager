package org.lokixcz.theendex.tracking

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.ItemStack
import org.lokixcz.theendex.Endex
import java.util.concurrent.ConcurrentHashMap

/**
 * ResourceTracker: observes player-gathered resources (block breaks, mob drops, fishing).
 * - Maintains an in-memory counter per Material for the current session.
 * - Optionally applies gathered amounts to MarketManager supply.
 */
class ResourceTracker(private val plugin: Endex) : Listener {
    private val counts = ConcurrentHashMap<Material, Long>()
    var enabled: Boolean = true
    var applyToMarket: Boolean = false
    var blockBreak: Boolean = true
    var mobDrops: Boolean = true
    var fishing: Boolean = true

    private fun file() = java.io.File(plugin.dataFolder, "tracking.yml")

    fun loadFromDisk() {
        val f = file()
        if (!f.exists()) return
        kotlin.runCatching {
            val lines = f.readLines()
            for (line in lines) {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val mat = Material.matchMaterial(parts[0].trim()) ?: continue
                    val amt = parts[1].trim().toLongOrNull() ?: continue
                    counts[mat] = amt
                }
            }
        }
    }

    fun saveToDisk() {
        val f = file(); if (!f.parentFile.exists()) f.parentFile.mkdirs()
        val b = StringBuilder()
        // Simple YAML-ish: MATERIAL: count
        for ((m, v) in counts.toSortedMap(compareBy { it.name })) {
            b.append(m.name).append(": ").append(v).append('\n')
        }
        f.writeText(b.toString())
    }

    fun top(n: Int = 10): List<Pair<Material, Long>> = counts.entries
        .sortedByDescending { it.value }
        .take(n)
        .map { it.key to it.value }

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.logger.info("ResourceTracker enabled (applyToMarket=$applyToMarket)")
    }

    fun stop() {
        // No explicit unregister needed; listeners are unregistered on plugin disable.
    }

    fun snapshot(): Map<Material, Long> = counts.toMap()

    private fun add(material: Material, amount: Int) {
        if (material.isAir) return
        counts.merge(material, amount.toLong()) { a, b -> a + b }
        if (applyToMarket) {
            runCatching { plugin.marketManager.addSupply(material, amount.toDouble()) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(e: BlockBreakEvent) {
        if (!enabled || !blockBreak) return
        val p: Player = e.player
        val drops = e.block.getDrops(p.inventory.itemInMainHand, p)
        if (drops.isEmpty()) return
        for (stack in drops) add(stack.type, stack.amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(e: EntityDeathEvent) {
        if (!enabled || !mobDrops) return
        for (stack: ItemStack in e.drops) add(stack.type, stack.amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(e: PlayerFishEvent) {
        if (!enabled || !fishing) return
        val caught = e.caught
        val item = (caught as? org.bukkit.entity.Item)?.itemStack
        if (item != null) add(item.type, item.amount)
    }
}
