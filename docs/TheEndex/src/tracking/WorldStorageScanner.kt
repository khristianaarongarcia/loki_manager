package org.lokixcz.theendex.tracking

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.ShulkerBox
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.lokixcz.theendex.Endex
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Callable

/**
 * WorldStorageScanner: Scans all container block entities in loaded chunks to
 * provide global item totals for dynamic pricing.
 *
 * Performance Optimizations (v2.0):
 * - Scans only loaded chunks (no chunk loading)
 * - Uses async executor for aggregation, sync for Bukkit API calls
 * - Chunked batch processing to avoid blocking main thread
 * - Configurable scan interval with aggressive caching
 * - Early exit if scan already in progress
 * - Container type filtering with O(1) Material set lookup
 * - Shulker box nested inventory support (optional)
 * - Double chest deduplication to prevent double-counting
 * - Per-chunk item cap to prevent storage farm manipulation
 * - TPS-aware throttling to reduce lag on busy servers
 * - NEW: Chunk-level result caching - only rescan modified chunks
 * - NEW: Event-based dirty chunk tracking
 * - NEW: Disk cache persistence for fast restarts
 * - NEW: Empty inventory skip for faster scanning
 * - NEW: Periodic full refresh to ensure consistency
 */
class WorldStorageScanner(private val plugin: Endex) : Listener {

    // Configuration
    private val enabledFlag: Boolean = plugin.config.getBoolean("price-world-storage.enabled", false)
    private val scanIntervalSeconds: Int = plugin.config.getInt("price-world-storage.scan-interval-seconds", 300).coerceAtLeast(60)
    private val includeChests: Boolean = plugin.config.getBoolean("price-world-storage.containers.chests", true)
    private val includeBarrels: Boolean = plugin.config.getBoolean("price-world-storage.containers.barrels", true)
    private val includeShulkers: Boolean = plugin.config.getBoolean("price-world-storage.containers.shulker-boxes", true)
    private val includeHoppers: Boolean = plugin.config.getBoolean("price-world-storage.containers.hoppers", false)
    private val includeDroppers: Boolean = plugin.config.getBoolean("price-world-storage.containers.droppers", false)
    private val includeDispensers: Boolean = plugin.config.getBoolean("price-world-storage.containers.dispensers", false)
    private val includeFurnaces: Boolean = plugin.config.getBoolean("price-world-storage.containers.furnaces", false)
    private val includeBrewingStands: Boolean = plugin.config.getBoolean("price-world-storage.containers.brewing-stands", false)
    private val scanShulkerContents: Boolean = plugin.config.getBoolean("price-world-storage.scan-shulker-contents", true)
    private val chunksPerTick: Int = plugin.config.getInt("price-world-storage.chunks-per-tick", 50).coerceIn(10, 200)
    private val excludedWorlds: Set<String> = plugin.config.getStringList("price-world-storage.excluded-worlds").map { it.lowercase() }.toSet()
    
    // Anti-manipulation settings
    private val perChunkItemCap: Int = plugin.config.getInt("price-world-storage.anti-manipulation.per-chunk-item-cap", 10000).coerceAtLeast(100)
    private val perMaterialChunkCap: Int = plugin.config.getInt("price-world-storage.anti-manipulation.per-material-chunk-cap", 5000).coerceAtLeast(64)
    private val minTpsForScan: Double = plugin.config.getDouble("price-world-storage.anti-manipulation.min-tps", 18.0).coerceIn(10.0, 20.0)
    private val logSuspiciousActivity: Boolean = plugin.config.getBoolean("price-world-storage.anti-manipulation.log-suspicious", true)
    
    // Caching settings (new)
    private val enableChunkCache: Boolean = plugin.config.getBoolean("price-world-storage.cache.enabled", true)
    private val chunkCacheExpirySeconds: Int = plugin.config.getInt("price-world-storage.cache.chunk-expiry-seconds", 600).coerceAtLeast(60)
    private val fullRefreshInterval: Int = plugin.config.getInt("price-world-storage.cache.full-refresh-cycles", 5).coerceAtLeast(1)
    private val persistCacheToDisk: Boolean = plugin.config.getBoolean("price-world-storage.cache.persist-to-disk", true)

    // Global result cache
    @Volatile
    private var cachedTotals: Map<Material, Long> = emptyMap()

    @Volatile
    private var lastScanEpoch: Long = 0L

    @Volatile
    private var lastScanDurationMs: Long = 0L

    @Volatile
    private var lastContainerCount: Int = 0
    
    @Volatile
    private var skippedDueToTps: Boolean = false
    
    @Volatile
    private var lastFullScanCycle: Int = 0

    private val scanning = AtomicBoolean(false)
    private val scanCounter = AtomicLong(0L)

    // Scheduler
    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    
    // Track scanned double chests to avoid double-counting (location hash set per scan)
    private val scannedDoubleChests = ConcurrentHashMap.newKeySet<String>()
    
    // ===== NEW: Chunk-level caching =====
    
    // Cache for individual chunk scan results
    private data class ChunkCache(
        val totals: Map<Material, Long>,
        val containerCount: Int,
        val scanTimeMs: Long
    )
    private val chunkCache = ConcurrentHashMap<ChunkKey, ChunkCache>()
    
    // Track which chunks have been modified since last scan
    private val dirtyChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
    
    // Simple chunk identifier
    private data class ChunkKey(val worldName: String, val x: Int, val z: Int) {
        override fun toString() = "$worldName:$x:$z"
        
        companion object {
            fun fromString(s: String): ChunkKey? {
                val parts = s.split(":")
                if (parts.size != 3) return null
                return try {
                    ChunkKey(parts[0], parts[1].toInt(), parts[2].toInt())
                } catch (_: Exception) { null }
            }
        }
    }
    
    // Gson for disk persistence
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val cacheFile: File by lazy { File(plugin.dataFolder, "world-scan-cache.json") }

    // ===== Container type filtering with O(1) lookup =====
    
    // Use Material set instead of String set for faster lookup
    private val allowedContainerMaterials: Set<Material> by lazy {
        buildSet {
            if (includeChests) {
                add(Material.CHEST)
                add(Material.TRAPPED_CHEST)
                // Note: ENDER_CHEST excluded - per-player storage
            }
            if (includeBarrels) add(Material.BARREL)
            if (includeShulkers) {
                // All shulker box colors
                addAll(Material.entries.filter { it.name.endsWith("SHULKER_BOX") })
            }
            if (includeHoppers) add(Material.HOPPER)
            if (includeDroppers) add(Material.DROPPER)
            if (includeDispensers) add(Material.DISPENSER)
            if (includeFurnaces) {
                add(Material.FURNACE)
                add(Material.BLAST_FURNACE)
                add(Material.SMOKER)
            }
            if (includeBrewingStands) add(Material.BREWING_STAND)
        }
    }
    
    // Materials that are containers (for event detection)
    private val containerMaterials: Set<Material> by lazy {
        buildSet {
            add(Material.CHEST)
            add(Material.TRAPPED_CHEST)
            add(Material.BARREL)
            add(Material.HOPPER)
            add(Material.DROPPER)
            add(Material.DISPENSER)
            add(Material.FURNACE)
            add(Material.BLAST_FURNACE)
            add(Material.SMOKER)
            add(Material.BREWING_STAND)
            addAll(Material.entries.filter { it.name.endsWith("SHULKER_BOX") })
        }
    }

    fun enabled(): Boolean = enabledFlag

    fun start() {
        if (!enabledFlag) {
            plugin.logger.info("WorldStorageScanner disabled in config.")
            return
        }
        
        // Register event listeners for dirty chunk tracking
        Bukkit.getPluginManager().registerEvents(this, plugin)
        
        // Load cached results from disk if available
        if (persistCacheToDisk) {
            loadCacheFromDisk()
        }
        
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Endex-WorldStorageScanner").apply { isDaemon = true }
        }
        // Initial scan after 10 seconds, then repeat at configured interval
        scheduledTask = executor?.scheduleAtFixedRate(
            { triggerScan() },
            10L,
            scanIntervalSeconds.toLong(),
            TimeUnit.SECONDS
        )
        plugin.logger.info("WorldStorageScanner started (interval=${scanIntervalSeconds}s, chunksPerTick=$chunksPerTick, caching=${if (enableChunkCache) "enabled" else "disabled"})")
    }

    fun stop() {
        scheduledTask?.cancel(false)
        executor?.shutdown()
        try {
            executor?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            executor?.shutdownNow()
        }
        executor = null
        scheduledTask = null
        
        // Save cache to disk on shutdown
        if (persistCacheToDisk) {
            saveCacheToDisk()
        }
        
        plugin.logger.info("WorldStorageScanner stopped.")
    }
    
    // ===== Event Listeners for Dirty Chunk Tracking =====
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClose(e: InventoryCloseEvent) {
        if (!enableChunkCache) return
        val loc = e.inventory.location ?: return
        val world = loc.world ?: return
        if (world.name.lowercase() in excludedWorlds) return
        
        // Check if this is a tracked container type
        val holder = e.inventory.holder
        if (holder is Container && holder.block.type in allowedContainerMaterials) {
            markChunkDirty(world.name, loc.chunk.x, loc.chunk.z)
        } else if (holder is DoubleChest) {
            markChunkDirty(world.name, loc.chunk.x, loc.chunk.z)
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(e: BlockPlaceEvent) {
        if (!enableChunkCache) return
        val block = e.block
        if (block.world.name.lowercase() in excludedWorlds) return
        
        if (block.type in containerMaterials) {
            markChunkDirty(block.world.name, block.chunk.x, block.chunk.z)
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(e: BlockBreakEvent) {
        if (!enableChunkCache) return
        val block = e.block
        if (block.world.name.lowercase() in excludedWorlds) return
        
        if (block.type in containerMaterials) {
            markChunkDirty(block.world.name, block.chunk.x, block.chunk.z)
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(e: ChunkUnloadEvent) {
        if (!enableChunkCache) return
        // Keep cache but mark chunk for refresh when it loads again
        val key = ChunkKey(e.world.name, e.chunk.x, e.chunk.z)
        dirtyChunks.add(key)
    }
    
    private fun markChunkDirty(worldName: String, x: Int, z: Int) {
        dirtyChunks.add(ChunkKey(worldName, x, z))
    }

    /**
     * Get cached totals. Returns empty map if scanner is disabled or no scan completed yet.
     * This is the primary method called by MarketManager during price updates.
     */
    fun snapshotTotals(): Map<Material, Long> = cachedTotals

    /**
     * Get the epoch second of the last completed scan.
     */
    fun lastScanTime(): Long = lastScanEpoch

    /**
     * Get duration of the last scan in milliseconds.
     */
    fun lastScanDuration(): Long = lastScanDurationMs

    /**
     * Get number of containers scanned in last scan.
     */
    fun lastContainerCount(): Int = lastContainerCount

    /**
     * Get total number of scans completed since plugin start.
     */
    fun scanCount(): Long = scanCounter.get()
    
    /**
     * Check if last scan was skipped due to low TPS.
     */
    fun wasSkippedDueToTps(): Boolean = skippedDueToTps
    
    /**
     * Get number of cached chunks.
     */
    fun cachedChunkCount(): Int = chunkCache.size
    
    /**
     * Get number of dirty chunks pending rescan.
     */
    fun dirtyChunkCount(): Int = dirtyChunks.size

    /**
     * Manually trigger a scan (e.g., from admin command).
     * Returns false if a scan is already in progress.
     */
    fun triggerScan(): Boolean {
        if (!enabledFlag) return false
        if (!scanning.compareAndSet(false, true)) {
            // Scan already in progress
            return false
        }
        performScanAsync()
        return true
    }
    
    /**
     * Force a full scan, ignoring chunk cache.
     */
    fun triggerFullScan(): Boolean {
        if (!enabledFlag) return false
        if (!scanning.compareAndSet(false, true)) {
            return false
        }
        chunkCache.clear()
        dirtyChunks.clear()
        performScanAsync()
        return true
    }

    /**
     * Force refresh - blocks until scan completes (for admin commands).
     * Should only be called from async context or with caution.
     */
    fun forceRefresh(): Map<Material, Long> {
        if (!enabledFlag) return emptyMap()
        // Wait for any in-progress scan
        var waited = 0
        while (scanning.get() && waited < 300) { // Max 30 seconds wait
            Thread.sleep(100)
            waited++
        }
        if (scanning.get()) return cachedTotals // Give up waiting
        // Trigger new scan and wait
        triggerScan()
        waited = 0
        while (scanning.get() && waited < 300) {
            Thread.sleep(100)
            waited++
        }
        return cachedTotals
    }
    
    /**
     * Get current server TPS (approximate).
     * Uses reflection to check for Paper's getTPS() method first,
     * then falls back to assuming 20.0 TPS on Spigot/Arclight servers.
     */
    private fun getCurrentTps(): Double {
        return try {
            // Try Paper's Bukkit.getTPS() via reflection to avoid NoSuchMethodError on Spigot/Arclight
            val method = Bukkit::class.java.getMethod("getTPS")
            val tpsArray = method.invoke(null) as DoubleArray
            tpsArray[0].coerceIn(0.0, 20.0)
        } catch (_: NoSuchMethodException) {
            // Paper TPS API not available (Spigot/Arclight) - assume good TPS
            20.0
        } catch (_: Exception) {
            // Any other error - assume good TPS
            20.0
        }
    }

    private fun performScanAsync() {
        val startTime = System.currentTimeMillis()
        
        // Clear double-chest tracking for this scan
        scannedDoubleChests.clear()
        
        // Determine if this should be a full scan
        val cycleCount = scanCounter.get().toInt()
        val forceFullScan = !enableChunkCache || (cycleCount > 0 && cycleCount % fullRefreshInterval == 0)

        // Check TPS before scanning (sync call)
        val tpsFuture = Bukkit.getScheduler().callSyncMethod(plugin, Callable {
            getCurrentTps()
        })

        // Run aggregation in executor thread
        executor?.execute {
            try {
                // TPS check
                val currentTps = try {
                    tpsFuture.get(5, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    20.0
                }
                
                if (currentTps < minTpsForScan) {
                    if (logSuspiciousActivity) {
                        plugin.logger.info("WorldStorageScanner: Skipping scan due to low TPS (${String.format("%.1f", currentTps)} < $minTpsForScan)")
                    }
                    skippedDueToTps = true
                    scanning.set(false)
                    return@execute
                }
                skippedDueToTps = false
                
                // Gather chunks from main thread (Bukkit API requirement)
                val chunksFuture = Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                    gatherChunksToScan()
                })
                
                val allChunks = try {
                    chunksFuture.get(30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    plugin.logger.warning("WorldStorageScanner: Failed to gather chunks: ${e.message}")
                    scanning.set(false)
                    return@execute
                }

                if (allChunks.isEmpty()) {
                    cachedTotals = emptyMap()
                    lastScanEpoch = System.currentTimeMillis() / 1000L
                    lastScanDurationMs = System.currentTimeMillis() - startTime
                    lastContainerCount = 0
                    scanCounter.incrementAndGet()
                    scanning.set(false)
                    return@execute
                }
                
                // ===== Optimized scanning with chunk cache =====
                
                val currentTimeMs = System.currentTimeMillis()
                val cacheExpiryMs = chunkCacheExpirySeconds * 1000L
                
                // Separate chunks into: needs-scan and use-cache
                val chunksToScan: List<ChunkKey>
                val cachedChunks: List<ChunkKey>
                
                if (forceFullScan) {
                    // Full scan - scan everything
                    chunksToScan = allChunks
                    cachedChunks = emptyList()
                    if (plugin.config.getBoolean("logging.verbose", false)) {
                        plugin.logger.info("WorldStorageScanner: Performing full scan (cycle $cycleCount)")
                    }
                } else {
                    // Incremental scan - only scan dirty or expired chunks
                    val needsScan = mutableListOf<ChunkKey>()
                    val useCache = mutableListOf<ChunkKey>()
                    
                    for (chunkKey in allChunks) {
                        val cached = chunkCache[chunkKey]
                        val isDirty = dirtyChunks.remove(chunkKey)
                        val isExpired = cached == null || (currentTimeMs - cached.scanTimeMs > cacheExpiryMs)
                        
                        if (isDirty || isExpired) {
                            needsScan.add(chunkKey)
                        } else {
                            useCache.add(chunkKey)
                        }
                    }
                    
                    chunksToScan = needsScan
                    cachedChunks = useCache
                    
                    if (plugin.config.getBoolean("logging.verbose", false)) {
                        plugin.logger.info("WorldStorageScanner: Incremental scan - ${chunksToScan.size} to scan, ${cachedChunks.size} from cache")
                    }
                }

                // Process chunks that need scanning in batches on main thread
                val totals = ConcurrentHashMap<Material, Long>()
                var containerCount = 0
                val batchSize = chunksPerTick
                val batches = chunksToScan.chunked(batchSize)
                
                // Dynamic delay based on TPS
                val baseDelay = 50L

                for ((batchIndex, batch) in batches.withIndex()) {
                    // Re-check TPS periodically during long scans
                    if (batchIndex > 0 && batchIndex % 10 == 0) {
                        val midScanTps = try {
                            val f = Bukkit.getScheduler().callSyncMethod(plugin, Callable { getCurrentTps() })
                            f.get(2, TimeUnit.SECONDS)
                        } catch (_: Exception) { 20.0 }
                        
                        if (midScanTps < minTpsForScan - 2.0) {
                            if (logSuspiciousActivity) {
                                plugin.logger.warning("WorldStorageScanner: Aborting scan mid-way due to TPS drop (${String.format("%.1f", midScanTps)})")
                            }
                            // Save partial results
                            break
                        }
                    }
                    
                    val batchResult = try {
                        val future = Bukkit.getScheduler().callSyncMethod(plugin, Callable {
                            processBatchWithCaching(batch, currentTimeMs)
                        })
                        future.get(10, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        plugin.logger.warning("WorldStorageScanner: Batch $batchIndex failed: ${e.message}")
                        BatchResult(emptyMap(), 0, emptyList())
                    }

                    // Merge batch results (respecting per-material caps already applied)
                    for ((mat, qty) in batchResult.totals) {
                        totals.merge(mat, qty) { a, b -> a + b }
                    }
                    containerCount += batchResult.containerCount
                    
                    // Log suspicious chunks
                    if (logSuspiciousActivity && batchResult.suspiciousChunks.isNotEmpty()) {
                        for (warning in batchResult.suspiciousChunks) {
                            plugin.logger.warning("WorldStorageScanner: $warning")
                        }
                    }

                    // Adaptive delay between batches
                    if (batchIndex < batches.size - 1) {
                        Thread.sleep(baseDelay)
                    }
                }
                
                // Add cached chunk results
                for (chunkKey in cachedChunks) {
                    val cached = chunkCache[chunkKey] ?: continue
                    for ((mat, qty) in cached.totals) {
                        totals.merge(mat, qty) { a, b -> a + b }
                    }
                    containerCount += cached.containerCount
                }

                // Update cache atomically
                cachedTotals = totals.toMap()
                lastScanEpoch = System.currentTimeMillis() / 1000L
                lastScanDurationMs = System.currentTimeMillis() - startTime
                lastContainerCount = containerCount
                scanCounter.incrementAndGet()
                
                // Clear unloaded chunks from cache (cleanup)
                val loadedChunkKeys = allChunks.toSet()
                chunkCache.keys.removeIf { it !in loadedChunkKeys }

                if (plugin.config.getBoolean("logging.verbose", false)) {
                    val scanType = if (forceFullScan) "full" else "incremental"
                    plugin.logger.info("WorldStorageScanner: Completed $scanType scan - $containerCount containers in ${allChunks.size} chunks (${lastScanDurationMs}ms, scanned=${chunksToScan.size}, cached=${cachedChunks.size})")
                }
                
                // Periodically save cache to disk
                if (persistCacheToDisk && cycleCount % 5 == 0) {
                    saveCacheToDisk()
                }

            } catch (e: Exception) {
                plugin.logger.warning("WorldStorageScanner: Scan failed: ${e.message}")
            } finally {
                scanning.set(false)
                scannedDoubleChests.clear() // Cleanup
            }
        }
    }

    private fun gatherChunksToScan(): List<ChunkKey> {
        val result = mutableListOf<ChunkKey>()
        for (world in Bukkit.getWorlds()) {
            if (world.name.lowercase() in excludedWorlds) continue
            for (chunk in world.loadedChunks) {
                result.add(ChunkKey(world.name, chunk.x, chunk.z))
            }
        }
        return result
    }

    private data class BatchResult(val totals: Map<Material, Long>, val containerCount: Int, val suspiciousChunks: List<String>)

    private fun processBatchWithCaching(chunkKeys: List<ChunkKey>, currentTimeMs: Long): BatchResult {
        val totals = HashMap<Material, Long>()
        var containerCount = 0
        val suspiciousChunks = mutableListOf<String>()

        for (key in chunkKeys) {
            val world = Bukkit.getWorld(key.worldName) ?: continue
            val chunk: Chunk = try {
                if (!world.isChunkLoaded(key.x, key.z)) continue
                world.getChunkAt(key.x, key.z)
            } catch (_: Exception) {
                continue
            }

            // Per-chunk totals for anti-manipulation cap
            val chunkTotals = HashMap<Material, Long>()
            var chunkContainerCount = 0
            var chunkTotalItems = 0L

            // Get tile entities (block states) from chunk
            val tileEntities: Array<BlockState> = try {
                chunk.tileEntities
            } catch (_: Exception) {
                continue
            }

            for (state in tileEntities) {
                if (state !is Container) continue
                
                // O(1) Material lookup instead of string comparison
                val blockType = state.block.type
                if (blockType !in allowedContainerMaterials) continue

                // Skip ender chests (they're per-player, handled by InventorySnapshotService)
                if (blockType == Material.ENDER_CHEST) continue
                
                val inventory: Inventory = try {
                    state.inventory
                } catch (_: Exception) {
                    continue
                }
                
                // ===== Quick win: Skip empty inventories =====
                if (inventory.isEmpty) continue
                
                // Double chest deduplication: only count once per double chest
                val holder = inventory.holder
                if (holder is DoubleChest) {
                    val loc = holder.location
                    if (loc != null) {
                        val dcKey = "${loc.world?.name}:${loc.blockX}:${loc.blockY}:${loc.blockZ}"
                        if (!scannedDoubleChests.add(dcKey)) {
                            // Already scanned this double chest from its other half
                            continue
                        }
                    }
                }

                chunkContainerCount++
                scanInventory(inventory, chunkTotals)
            }
            
            // Calculate total items in this chunk
            chunkTotalItems = chunkTotals.values.sum()
            
            // Check for suspicious activity (potential storage farm)
            if (chunkTotalItems > perChunkItemCap) {
                suspiciousChunks.add("Chunk [${key.worldName}:${key.x},${key.z}] has $chunkTotalItems items (cap: $perChunkItemCap) - capping contribution")
            }
            
            // Apply per-chunk caps to get final chunk contribution
            val cappedChunkTotals = HashMap<Material, Long>()
            for ((mat, qty) in chunkTotals) {
                // Cap per-material contribution from this chunk
                val cappedQty = qty.coerceAtMost(perMaterialChunkCap.toLong())
                
                // Only add if total chunk items aren't over cap, or proportionally reduce
                val multiplier = if (chunkTotalItems > perChunkItemCap) {
                    perChunkItemCap.toDouble() / chunkTotalItems.toDouble()
                } else 1.0
                
                val finalQty = (cappedQty * multiplier).toLong().coerceAtLeast(0)
                if (finalQty > 0) {
                    cappedChunkTotals[mat] = finalQty
                    totals.merge(mat, finalQty) { a, b -> a + b }
                }
            }
            
            containerCount += chunkContainerCount
            
            // Cache this chunk's results
            if (enableChunkCache) {
                chunkCache[key] = ChunkCache(
                    totals = cappedChunkTotals.toMap(),
                    containerCount = chunkContainerCount,
                    scanTimeMs = currentTimeMs
                )
            }
        }

        return BatchResult(totals, containerCount, suspiciousChunks)
    }

    private fun scanInventory(inventory: Inventory, totals: MutableMap<Material, Long>) {
        for (stack in inventory.contents) {
            if (stack == null || stack.type.isAir) continue
            val amount = stack.amount.toLong()
            if (amount <= 0) continue

            totals.merge(stack.type, amount) { a, b -> a + b }

            // If this is a shulker box item (in inventory), scan its contents too
            if (scanShulkerContents && stack.type.name.endsWith("SHULKER_BOX")) {
                scanShulkerBoxItem(stack, totals)
            }
        }
    }

    private fun scanShulkerBoxItem(stack: ItemStack, totals: MutableMap<Material, Long>) {
        val meta = stack.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta ?: return
        val blockState = try {
            meta.blockState
        } catch (_: Exception) {
            return
        }
        if (blockState !is ShulkerBox) return

        val shulkerInventory = try {
            blockState.inventory
        } catch (_: Exception) {
            return
        }

        for (innerStack in shulkerInventory.contents) {
            if (innerStack == null || innerStack.type.isAir) continue
            val amount = innerStack.amount.toLong()
            if (amount <= 0) continue
            totals.merge(innerStack.type, amount) { a, b -> a + b }
            // Note: We don't recurse further (no shulkers in shulkers in vanilla)
        }
    }
    
    // ===== Disk Cache Persistence =====
    
    private data class DiskCacheData(
        val totals: Map<String, Long>,  // Material name -> count
        val scanEpoch: Long,
        val version: Int = 1
    )
    
    private fun saveCacheToDisk() {
        try {
            val data = DiskCacheData(
                totals = cachedTotals.mapKeys { it.key.name },
                scanEpoch = lastScanEpoch
            )
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(gson.toJson(data))
            if (plugin.config.getBoolean("logging.verbose", false)) {
                plugin.logger.info("WorldStorageScanner: Saved cache to disk (${cachedTotals.size} materials)")
            }
        } catch (e: Exception) {
            plugin.logger.warning("WorldStorageScanner: Failed to save cache to disk: ${e.message}")
        }
    }
    
    private fun loadCacheFromDisk() {
        try {
            if (!cacheFile.exists()) return
            
            // Only load if cache is less than 1 hour old
            val maxAge = 3600_000L // 1 hour
            if (System.currentTimeMillis() - cacheFile.lastModified() > maxAge) {
                plugin.logger.info("WorldStorageScanner: Disk cache too old, will perform fresh scan")
                return
            }
            
            val json = cacheFile.readText()
            val data = gson.fromJson(json, DiskCacheData::class.java)
            
            if (data.version != 1) {
                plugin.logger.info("WorldStorageScanner: Disk cache version mismatch, will perform fresh scan")
                return
            }
            
            // Convert material names back to Material enum
            val totals = mutableMapOf<Material, Long>()
            for ((name, count) in data.totals) {
                try {
                    val mat = Material.valueOf(name)
                    totals[mat] = count
                } catch (_: Exception) {
                    // Material no longer exists (version change), skip
                }
            }
            
            cachedTotals = totals
            lastScanEpoch = data.scanEpoch
            
            plugin.logger.info("WorldStorageScanner: Loaded cache from disk (${totals.size} materials from ${java.time.Instant.ofEpochSecond(data.scanEpoch)})")
        } catch (e: Exception) {
            plugin.logger.warning("WorldStorageScanner: Failed to load cache from disk: ${e.message}")
        }
    }
}
