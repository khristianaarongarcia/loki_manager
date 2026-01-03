package org.lokixcz.theendex.events

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.lokixcz.theendex.Endex
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

class EventManager(private val plugin: Endex) {
    private val eventsFile = File(plugin.dataFolder, "events.yml")
    private val activeFile = File(plugin.dataFolder, "active_events.yml")
    private val yaml = YamlConfiguration()
    private val definedEvents: MutableList<MarketEvent> = mutableListOf()
    private val active: MutableList<ActiveEvent> = CopyOnWriteArrayList()

    fun load() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!eventsFile.exists()) {
            plugin.saveResource("events.yml", false)
        }
        try { yaml.load(eventsFile) } catch (_: Throwable) {}
        definedEvents.clear()
        val list = yaml.getList("events") ?: emptyList<Any>()
        list.forEach { any ->
            val m = any as? Map<*, *> ?: return@forEach
            val name = m["name"]?.toString() ?: return@forEach
            val affected = m["affected_item"]?.toString() ?: "*"
            val category = (m["affected_category"] as? String)
            val multiplier = (m["multiplier"] as? Number)?.toDouble() ?: 1.0
            val weight = (m["weight"] as? Number)?.toDouble()
            val duration = (m["duration_minutes"] as? Number)?.toLong() ?: 30L
            val broadcast = (m["broadcast"] as? Boolean) ?: true
            val startMsg = m["start_message"]?.toString()
            val endMsg = m["end_message"]?.toString()
            definedEvents += MarketEvent(name, affected, category, multiplier, weight, duration, broadcast, startMsg, endMsg)
        }
        loadActive()
    }

    fun listEvents(): List<MarketEvent> = definedEvents.toList()

    fun listActive(): List<ActiveEvent> = active.toList()

    fun tickExpire() {
        val now = Instant.now()
        val ended = active.filter { it.endsAt.isBefore(now) }
        if (ended.isNotEmpty()) {
            ended.forEach { a ->
                active.remove(a)
                Bukkit.getServer().broadcastMessage("${ChatColor.GOLD}[The Endex] ${ChatColor.GRAY}Event ended: ${ChatColor.AQUA}${a.event.name}")
            }
        }
    }

    fun trigger(name: String): Boolean {
        val def = definedEvents.find { it.name.equals(name, ignoreCase = true) } ?: return false
        val now = Instant.now()
        val act = ActiveEvent(def, now, now.plus(def.durationMinutes, ChronoUnit.MINUTES))
        active += act
        if (def.broadcast) {
            val raw = def.startMessage
            val msg = raw?.let { org.bukkit.ChatColor.translateAlternateColorCodes('&', it) }
                ?: "${ChatColor.GOLD}[The Endex] ${ChatColor.AQUA}${def.name} ${ChatColor.GRAY}started. Multiplier ${ChatColor.GREEN}x${def.multiplier}${ChatColor.GRAY} on ${ChatColor.AQUA}${def.affectedCategory ?: def.affected}"
            Bukkit.getServer().broadcastMessage(msg)
        }
        saveActive()
        return true
    }

    fun multiplierFor(mat: Material): Double {
        val cap = plugin.config.getDouble("events.multiplier-cap", 10.0).coerceAtLeast(1.0)
        val mode = plugin.config.getString("events.stacking.mode")?.lowercase() ?: "multiplicative" // multiplicative|weighted-sum
        val defaultWeight = plugin.config.getDouble("events.stacking.default-weight", 1.0).coerceIn(0.0, 1.0)
        val maxStack = plugin.config.getInt("events.stacking.max-active", 0).coerceAtLeast(0) // 0 => unlimited

        val applicable = active.filter { isAffected(it.event, mat) }
        val limited = if (maxStack > 0) applicable.take(maxStack) else applicable

        if (mode == "weighted-sum") {
            // Interpret event.multiplier as the incremental delta above 1x, and combine via weighted sum.
            // combined = 1 + sum_i( (m_i - 1) * w_i ) subject to cap
            var combined = 1.0
            for (a in limited) {
                val m = a.event.multiplier
                val w = (a.event.weight ?: defaultWeight).coerceIn(0.0, 1.0)
                combined += (m - 1.0) * w
            }
            return combined.coerceAtMost(cap)
        }

        // Default multiplicative stacking with cap
        var mul = 1.0
        for (a in limited) mul *= a.event.multiplier
        return mul.coerceAtMost(cap)
    }

    private fun isAffected(ev: MarketEvent, mat: Material): Boolean {
        ev.affectedCategory?.let { cat ->
            return when (cat.uppercase()) {
                "ALL" -> true
                "ORES" -> mat.name.contains("_ORE") || mat.name.endsWith("_INGOT") || mat.name.endsWith("_BLOCK")
                "FARMING" -> listOf("WHEAT","SEEDS","CARROT","POTATO","BEETROOT").any { mat.name.contains(it) }
                "MOB_DROPS" -> mat.name in listOf("ROTTEN_FLESH","BONE","STRING","SPIDER_EYE","ENDER_PEARL","GUNPOWDER","BLAZE_ROD","GHAST_TEAR","SLIME_BALL")
                "BLOCKS" -> mat.isBlock
                else -> false
            }
        }
        if (ev.affected == "*") return true
        return mat.name.equals(ev.affected, ignoreCase = true)
    }

    // Persistence of active events across restarts
    private fun saveActive() {
        val y = YamlConfiguration()
        val list = active.map { mapOf(
            "name" to it.event.name,
            "started_at" to it.startedAt.toEpochMilli(),
            "ends_at" to it.endsAt.toEpochMilli()
        ) }
        y.set("active", list)
        try { y.save(activeFile) } catch (_: Throwable) {}
    }

    private fun loadActive() {
        if (!activeFile.exists()) return
        val y = YamlConfiguration()
        try { y.load(activeFile) } catch (_: Throwable) { return }
        val list = y.getList("active") ?: return
        active.clear()
        val now = Instant.now()
        list.forEach { any ->
            val m = any as? Map<*, *> ?: return@forEach
            val name = m["name"]?.toString() ?: return@forEach
            val def = definedEvents.find { it.name.equals(name, ignoreCase = true) } ?: return@forEach
            val startedMs = (m["started_at"] as? Number)?.toLong() ?: return@forEach
            val endsMs = (m["ends_at"] as? Number)?.toLong() ?: return@forEach
            val started = Instant.ofEpochMilli(startedMs)
            val ends = Instant.ofEpochMilli(endsMs)
            if (ends.isAfter(now)) active += ActiveEvent(def, started, ends)
        }
    }

    fun end(name: String): Boolean {
        val it = active.iterator()
        var ended = false
        while (it.hasNext()) {
            val a = it.next()
            if (a.event.name.equals(name, ignoreCase = true)) {
                it.remove()
                if (a.event.broadcast) {
                    val raw = a.event.endMessage
                    val msg = raw?.let { org.bukkit.ChatColor.translateAlternateColorCodes('&', it) } ?: "${ChatColor.GOLD}[The Endex] ${ChatColor.AQUA}${a.event.name} ${ChatColor.GRAY}ended."
                    Bukkit.getServer().broadcastMessage(msg)
                }
                ended = true
            }
        }
        if (ended) saveActive()
        return ended
    }

    fun clearAll(): Int {
        val n = active.size
        if (n == 0) return 0
        active.clear()
        saveActive()
        return n
    }
}
