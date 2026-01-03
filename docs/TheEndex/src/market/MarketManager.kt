package org.lokixcz.theendex.market

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.api.events.PriceUpdateEvent
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class MarketManager(private val plugin: JavaPlugin, private val db: SqliteStore? = null) {

    private val items: MutableMap<Material, MarketItem> = mutableMapOf()
    private val saving = AtomicBoolean(false)

    private fun dataFolder(): File = File(plugin.dataFolder, "market.yml")
    private fun backupFile(): File = File(plugin.dataFolder, "market_backup.yml")
    private fun historyDir(): File = File(plugin.dataFolder, plugin.config.getString("history-export.folder", "history")!!)

    fun allItems(): Collection<MarketItem> = items.values

    // Explicit accessor to underlying sqlite store (null if YAML mode) – replaces reflective access.
    fun sqliteStore(): SqliteStore? = db

    fun get(material: Material): MarketItem? = items[material]

    fun addDemand(material: Material, amount: Double) {
        items[material]?.let { it.demand += amount }
    }

    fun addSupply(material: Material, amount: Double) {
        items[material]?.let { it.supply += amount }
    }

    fun load() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()

        items.clear()
        if (db != null) {
            db.init()
            items.putAll(db.loadAll())
            applyBlacklist()
            if (items.isEmpty()) {
                // Try migrating from YAML if present
                val yamlFile = dataFolder()
                if (yamlFile.exists()) {
                    loadFromYaml(yamlFile)
                    applyBlacklist()
                    // Persist into DB
                    for ((_, item) in items) {
                        db.upsertItem(item)
                        // persist history
                        for (p in item.history) db.appendHistory(item.material, p)
                    }
                    // Move old YAML to history folder as a backup snapshot
                    try {
                        if (!historyDir().exists()) historyDir().mkdirs()
                        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.now())
                        val dest = File(historyDir(), "market_yaml_${ts}.yml")
                        yamlFile.copyTo(dest, overwrite = true)
                        yamlFile.delete()
                    } catch (t: Throwable) {
                        plugin.logger.warning("Failed to archive old market.yml: ${t.message}")
                    }
                } else {
                    // No prior data, seed a fresh market
                    seedDefaults()
                    save()
                }
            }
            return
        }

        val file = dataFolder()
        if (!file.exists()) {
            // Seed with a small default market based on config defaults
            seedDefaults()
            save()
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)

        for (key in yaml.getKeys(false)) {
            val mat = Material.matchMaterial(key) ?: continue
            val section = yaml.getConfigurationSection(key) ?: continue
            val base = section.getDouble("base_price", 100.0)
            val min = section.getDouble("min_price", 20.0)
            val max = section.getDouble("max_price", 500.0)
            val current = section.getDouble("current_price", base)
            val demand = section.getDouble("demand", 0.0)
            val supply = section.getDouble("supply", 0.0)

            val item = MarketItem(
                material = mat,
                basePrice = base,
                minPrice = min,
                maxPrice = max,
                currentPrice = current,
                demand = demand,
                supply = supply,
                lastDemand = section.getDouble("last_demand", 0.0),
                lastSupply = section.getDouble("last_supply", 0.0),
                history = ArrayDeque()
            )

            val histList = section.getMapList("history")
            for (entry in histList) {
                val timeStr = entry["time"]?.toString()
                val priceNum = entry["price"]?.toString()?.toDoubleOrNull()
                if (timeStr != null && priceNum != null) {
                    runCatching { Instant.parse(timeStr) }.getOrNull()?.let {
                        item.history.addLast(PricePoint(it, priceNum))
                    }
                }
            }

            items[mat] = item
        }
        applyBlacklist()
    }

    private fun loadFromYaml(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val mat = Material.matchMaterial(key) ?: continue
            val section = yaml.getConfigurationSection(key) ?: continue
            val base = section.getDouble("base_price", 100.0)
            val min = section.getDouble("min_price", 20.0)
            val max = section.getDouble("max_price", 500.0)
            val current = section.getDouble("current_price", base)
            val demand = section.getDouble("demand", 0.0)
            val supply = section.getDouble("supply", 0.0)

            val item = MarketItem(
                material = mat,
                basePrice = base,
                minPrice = min,
                maxPrice = max,
                currentPrice = current,
                demand = demand,
                supply = supply,
                lastDemand = section.getDouble("last_demand", 0.0),
                lastSupply = section.getDouble("last_supply", 0.0),
                history = ArrayDeque()
            )

            val histList = section.getMapList("history")
            for (entry in histList) {
                val timeStr = entry["time"]?.toString()
                val priceNum = entry["price"]?.toString()?.toDoubleOrNull()
                if (timeStr != null && priceNum != null) {
                    runCatching { Instant.parse(timeStr) }.getOrNull()?.let {
                        item.history.addLast(PricePoint(it, priceNum))
                    }
                }
            }

            items[mat] = item
        }
    }

    fun save() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (db != null) {
            for ((_, item) in items) {
                db.upsertItem(item)
            }
            return
        }
        val file = dataFolder()
        val yaml = YamlConfiguration()

        for ((mat, item) in items) {
            val path = mat.name
            yaml.set("$path.base_price", item.basePrice)
            yaml.set("$path.min_price", item.minPrice)
            yaml.set("$path.max_price", item.maxPrice)
            yaml.set("$path.current_price", item.currentPrice)
            yaml.set("$path.demand", item.demand)
            yaml.set("$path.supply", item.supply)
            yaml.set("$path.last_demand", item.lastDemand)
            yaml.set("$path.last_supply", item.lastSupply)

            val hist = item.history.map { mapOf("time" to it.time.toString(), "price" to it.price) }
            yaml.set("$path.history", hist)
        }

        yaml.save(file)
    }

    fun saveAsync() {
        if (db != null) {
            // For SQLite, just call save() on the main thread (fast upserts) or keep it simple
            save(); return
        }
        if (!saving.compareAndSet(false, true)) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try { save() } catch (t: Throwable) { plugin.logger.warning("Async save failed: ${t.message}") } finally { saving.set(false) }
        })
    }

    fun backup() {
        if (!plugin.dataFolder.exists()) return
        if (db != null) {
            db.backupDb()
        } else {
            val src = dataFolder()
            if (!src.exists()) return
            val yaml = YamlConfiguration.loadConfiguration(src)
            yaml.save(backupFile())
        }
        if (plugin.config.getBoolean("history-export.enabled", true)) exportHistoryCsv()
    }

    fun updatePrices(sensitivity: Double, historyLength: Int) {
        val clampedSensitivity = sensitivity.coerceIn(0.0, 1.0)
        val smoothingEnabled = plugin.config.getBoolean("price-smoothing.enabled", true)
        val alpha = plugin.config.getDouble("price-smoothing.ema-alpha", 0.3).coerceIn(0.0, 1.0)
        val maxPct = plugin.config.getDouble("price-smoothing.max-change-percent", 15.0).coerceAtLeast(0.0)

        // Inventory-driven price influence (optional) - online player inventories
        val invEnabled = plugin.config.getBoolean("price-inventory.enabled", true)
        val invSens = plugin.config.getDouble("price-inventory.sensitivity", 0.02).coerceAtLeast(0.0)
        val invBaselinePerPlayer = plugin.config.getInt("price-inventory.per-player-baseline", 64).coerceAtLeast(1)
        val invSvc = (plugin as? Endex)?.getInventorySnapshotService()
        val invTotals: Map<org.bukkit.Material, Int> = if (invEnabled && (invSvc?.enabled() == true)) invSvc.snapshotTotals() else emptyMap()
        val onlineCount = if (invEnabled && (invSvc?.enabled() == true)) invSvc.onlinePlayerCount().coerceAtLeast(1) else 1

        // World storage-driven price influence (optional) - global container scanning
        val worldEnabled = plugin.config.getBoolean("price-world-storage.enabled", false)
        val worldSens = plugin.config.getDouble("price-world-storage.sensitivity", 0.01).coerceAtLeast(0.0)
        val worldBaseline = plugin.config.getInt("price-world-storage.global-baseline", 1000).coerceAtLeast(1)
        val worldMaxPct = plugin.config.getDouble("price-world-storage.max-impact-percent", 5.0).coerceAtLeast(0.0)
        val worldSvc = (plugin as? Endex)?.getWorldStorageScanner()
        val worldTotals: Map<org.bukkit.Material, Long> = if (worldEnabled && (worldSvc?.enabled() == true)) worldSvc.snapshotTotals() else emptyMap()

        for (item in items.values) {
            val hadActivity = (item.demand != 0.0) || (item.supply != 0.0)
            
            // 1. Trade-driven delta (buy/sell transactions)
            val tradeDelta = (item.demand - item.supply) * clampedSensitivity
            
            // 2. Inventory-driven delta (online player inventories - higher avg stock => negative pressure)
            val invQty = invTotals[item.material] ?: 0
            val avgPerPlayer = invQty.toDouble() / onlineCount.toDouble()
            val invPressure = (avgPerPlayer - invBaselinePerPlayer) / invBaselinePerPlayer.toDouble()
            val invDeltaRaw = -invPressure * invSens
            val invMaxPctCfg = plugin.config.getDouble("price-inventory.max-impact-percent", 10.0).coerceAtLeast(0.0)
            val invDelta = if (invEnabled) invDeltaRaw.coerceIn(-invMaxPctCfg / 100.0, invMaxPctCfg / 100.0) else 0.0
            
            // 3. World storage-driven delta (global containers - higher total stock => negative pressure)
            val worldQty = worldTotals[item.material] ?: 0L
            val worldPressure = (worldQty.toDouble() - worldBaseline) / worldBaseline.toDouble()
            val worldDeltaRaw = -worldPressure * worldSens
            val worldDelta = if (worldEnabled) worldDeltaRaw.coerceIn(-worldMaxPct / 100.0, worldMaxPct / 100.0) else 0.0
            
            // Combine all influences
            val delta = tradeDelta + invDelta + worldDelta
            val rawTarget = (item.currentPrice * (1.0 + delta)).coerceIn(item.minPrice, item.maxPrice)

            // Apply EMA smoothing towards target if enabled
            val smoothed = if (smoothingEnabled) {
                val ema = item.currentPrice * (1.0 - alpha) + rawTarget * alpha
                ema
            } else rawTarget

            // Clamp per-tick percent change if configured
            val clamped = if (maxPct > 0.0 && item.currentPrice > 0.0) {
                val maxUp = item.currentPrice * (1.0 + maxPct / 100.0)
                val maxDown = item.currentPrice * (1.0 - maxPct / 100.0)
                smoothed.coerceIn(maxDown, maxUp)
            } else smoothed

            val target = clamped.coerceIn(item.minPrice, item.maxPrice)

            // Fire event allowing plugins to modify or cancel the price change
            val ev = PriceUpdateEvent(item.material, item.currentPrice, target)
            Bukkit.getPluginManager().callEvent(ev)
            val newPrice = if (ev.isCancelled) item.currentPrice else ev.newPrice.coerceIn(item.minPrice, item.maxPrice)

            // record history
            val point = PricePoint(Instant.now(), newPrice)
            item.history.addLast(point)
            if (db != null) db.appendHistory(item.material, point)
            while (item.history.size > historyLength) item.history.removeFirst()

            item.currentPrice = newPrice
            // store last cycle demand/supply only if there was activity; otherwise, retain previous
            if (hadActivity) {
                item.lastDemand = item.demand
                item.lastSupply = item.supply
            }
            item.demand = 0.0
            item.supply = 0.0
        }
    }

    private fun seedDefaults() {
        val seedAll = plugin.config.getBoolean("seed-all-materials", false)
        val cfgNames = plugin.config.getStringList("seed-items") // legacy support if present in user config
        val addCurated = plugin.config.getBoolean("include-default-important-items", true)
        val blacklistNames = plugin.config.getStringList("blacklist-items").map { it.uppercase() }.toSet()

        val allNames = if (seedAll) {
            Material.entries
                .asSequence()
                .filter { !it.isAir && !it.name.startsWith("LEGACY_") }
                .map { it.name }
                .toList()
        } else {
            (cfgNames.map { it.uppercase() } + if (addCurated) curatedImportantMaterialNames() else emptyList())
        }
            .distinct()
            .filterNot { it in blacklistNames }

        val defaults = allNames.mapNotNull { Material.matchMaterial(it) }
        for (mat in defaults) {
            val base = defaultBasePriceFor(mat)
            val min = (base * 0.30).coerceAtLeast(1.0)
            val max = (base * 6.0).coerceAtLeast(min + 1.0)
            items[mat] = MarketItem(
                material = mat,
                basePrice = base,
                minPrice = min,
                maxPrice = max,
                currentPrice = base,
                demand = 0.0,
                supply = 0.0,
                history = ArrayDeque()
            )
        }
    }

    private fun curatedImportantMaterialNames(): List<String> = listOf(
        // Ores, ingots, gems
        "COAL", "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "NETHERITE_INGOT",
        "DIAMOND", "EMERALD", "LAPIS_LAZULI", "REDSTONE", "QUARTZ",
        // Wood/logs
        "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG",
        "MANGROVE_LOG", "CHERRY_LOG", "CRIMSON_STEM", "WARPED_STEM", "BAMBOO",
        // Stone/building basics
        "COBBLESTONE", "STONE", "DEEPSLATE", "SAND", "GRAVEL", "GLASS",
        // Crops and plants
        "WHEAT", "CARROT", "POTATO", "BEETROOT", "SUGAR_CANE", "PUMPKIN", "MELON_SLICE",
        "CACTUS", "NETHER_WART", "COCOA_BEANS",
        // Foods (processed)
        "BREAD", "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_CHICKEN", "COOKED_MUTTON",
        "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON",
        // Mob drops / rares
        "ROTTEN_FLESH", "BONE", "STRING", "GUNPOWDER", "ENDER_PEARL", "BLAZE_ROD",
        "GHAST_TEAR", "SLIME_BALL", "SPIDER_EYE", "LEATHER", "FEATHER", "INK_SAC", "SHULKER_SHELL",
        // Misc commodities
        "PAPER", "BOOK", "SUGAR", "HONEY_BOTTLE", "HONEYCOMB", "CLAY_BALL", "BRICK"
    )

    private fun defaultBasePriceFor(mat: Material): Double {
        val name = mat.name
        // High-tier and rares
        return when {
            name == "NETHERITE_INGOT" -> 2000.0
            name == "DIAMOND" -> 800.0
            name == "EMERALD" -> 400.0
            name == "LAPIS_LAZULI" -> 80.0
            name == "REDSTONE" -> 30.0
            name == "QUARTZ" -> 60.0
            name.endsWith("_INGOT") && name.startsWith("GOLD") -> 200.0
            name.endsWith("_INGOT") && name.startsWith("IRON") -> 120.0
            name.endsWith("_INGOT") && name.startsWith("COPPER") -> 60.0
            name.endsWith("_LOG") || name.endsWith("_STEM") || name == "BAMBOO" -> 25.0
            name in listOf("COBBLESTONE") -> 4.0
            name in listOf("STONE", "DEEPSLATE") -> 6.0
            name in listOf("SAND", "GRAVEL") -> 8.0
            name == "GLASS" -> 12.0
            name in listOf("WHEAT", "CARROT", "POTATO", "BEETROOT", "SUGAR_CANE") -> 12.0
            name in listOf("PUMPKIN", "MELON_SLICE", "CACTUS", "NETHER_WART", "COCOA_BEANS") -> 14.0
            name.startsWith("COOKED_") -> 20.0
            name in listOf("ROTTEN_FLESH") -> 2.0
            name in listOf("BONE", "FEATHER", "SPIDER_EYE") -> 6.0
            name in listOf("STRING", "INK_SAC", "CLAY_BALL", "BRICK") -> 8.0
            name == "SLIME_BALL" -> 20.0
            name == "GUNPOWDER" -> 25.0
            name == "ENDER_PEARL" -> 80.0
            name == "BLAZE_ROD" -> 90.0
            name == "GHAST_TEAR" -> 400.0
            name == "SHULKER_SHELL" -> 300.0
            name == "PAPER" -> 6.0
            name == "BOOK" -> 20.0
            name == "SUGAR" -> 6.0
            name == "HONEY_BOTTLE" -> 25.0
            name == "HONEYCOMB" -> 12.0
            else -> 100.0
        }
    }

    private fun exportHistoryCsv() {
        try {
            // Take a consistent snapshot of histories on the main thread to avoid concurrent modification
            val future = Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                items.map { (mat, item) -> mat to item.history.toList() }
            })
            val snapshot = try {
                future.get(5, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                plugin.logger.warning("Failed to snapshot histories for CSV export: ${t.message}")
                return
            }

            val dir = historyDir()
            if (!dir.exists()) dir.mkdirs()

            for ((mat, hist) in snapshot) {
                try {
                    val tmp = File(dir, "${mat.name}.csv.tmp")
                    val fin = File(dir, "${mat.name}.csv")
                    FileWriter(tmp, false).use { w ->
                        w.appendLine("time,price")
                        for (p in hist) {
                            w.appendLine("${p.time},${p.price}")
                        }
                    }
                    try {
                        Files.move(tmp.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (moveErr: Throwable) {
                        // Fallback if ATOMIC_MOVE not supported on FS
                        if (!tmp.renameTo(fin)) throw moveErr
                    }
                } catch (perItemErr: Throwable) {
                    plugin.logger.warning("CSV export failed for ${mat.name}: ${perItemErr.message}")
                }
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to export history CSV: ${t.message}")
        }
    }

    private fun applyBlacklist() {
        val blacklist = plugin.config.getStringList("blacklist-items").map { it.uppercase() }.toSet()
        if (blacklist.isEmpty()) return
        val toRemove = items.filterKeys { it.name in blacklist }.keys
        toRemove.forEach { items.remove(it) }
        if (toRemove.isNotEmpty()) plugin.logger.info("Excluded ${toRemove.size} blacklisted items from market.")
    }
}
