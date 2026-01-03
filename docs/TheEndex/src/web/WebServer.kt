package org.lokixcz.theendex.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.staticfiles.Location
import io.javalin.http.sse.SseClient
import io.javalin.websocket.WsContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.util.EndexLogger
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class WebServer(private val plugin: Endex) {
    private var app: Javalin? = null
    // Allow addons to register additional routes (applied on start and immediately if running)
    private val addonRouteConfigurers: MutableList<(Javalin) -> Unit> = mutableListOf()
    // Addon navigation entries for the UI (e.g., "Crypto")
    private val addonNav: MutableSet<String> = linkedSetOf()
    private val sessionManager = SessionManager()
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val logger = EndexLogger(plugin)
    // Icons support (resource pack extraction and serving)
    private var iconsEnabled: Boolean = false
    private var texturesRoot: File? = null
    // Custom Web UI override support
    private var customEnabled: Boolean = false
    private var customRootDir: File? = null
    private var customReload: Boolean = false
    private var customExportDefault: Boolean = false
    @Volatile private var cachedCustomIndex: String? = null
    @Volatile private var cachedCustomIndexMtime: Long = 0L
    // rate limiting
    private val rateWindowSeconds by lazy { runCatching { plugin.config.getInt("web.rate-limit.per-seconds", 10) }.getOrDefault(10) }
    private val rateMaxRequests by lazy { runCatching { plugin.config.getInt("web.rate-limit.requests", 120) }.getOrDefault(120) }
    private val rateEnabled by lazy { runCatching { plugin.config.getBoolean("web.rate-limit.enabled", true) }.getOrDefault(true) }
    private val requestCounts: MutableMap<String, MutableList<Long>> = mutableMapOf()
    // Simple in-memory cache for history responses: key = material|limit -> cached JSON and last timestamp
    private data class CachedHistory(
        val material: Material,
        val limit: Int,
        var lastEpochSecond: Long,
        var json: String
    )
    private val historyCache: MutableMap<String, CachedHistory> = mutableMapOf()
    // SSE clients
    private val sseClients: MutableSet<SseClient> = java.util.Collections.synchronizedSet(mutableSetOf())
    private var sseEnabled: Boolean = false
    // WebSocket clients
    private val wsClients: MutableSet<WsContext> = java.util.Collections.synchronizedSet(mutableSetOf())
    private var wsEnabled: Boolean = false
    private fun historyCacheKey(material: Material, limit: Int) = "$material|$limit"
    
    fun start(port: Int = 3434) {
        try {
            val host = runCatching { plugin.config.getString("web.host", "127.0.0.1") }.getOrDefault("127.0.0.1") ?: "127.0.0.1"
            // Load custom UI settings before creating Javalin (needed for static files)
            customEnabled = runCatching { plugin.config.getBoolean("web.custom.enabled", false) }.getOrDefault(false)
            val customRootName = runCatching { plugin.config.getString("web.custom.root", "webui") }.getOrDefault("webui") ?: "webui"
            customReload = runCatching { plugin.config.getBoolean("web.custom.reload", false) }.getOrDefault(false)
            customExportDefault = runCatching { plugin.config.getBoolean("web.custom.export-default", true) }.getOrDefault(true)
            if (customEnabled) {
                val base = File(plugin.dataFolder, customRootName)
                if (!base.exists()) base.mkdirs()
                customRootDir = base
            }
            // Check if compression is enabled in config (default: true)
            val compressionEnabled = runCatching { plugin.config.getBoolean("web.compression.enabled", true) }.getOrDefault(true)
            val compressionLevel = runCatching { plugin.config.getInt("web.compression.level", 4) }.getOrDefault(4)
            
            app = Javalin.create { config ->
                config.showJavalinBanner = false
                // Enable gzip compression for API responses (reduces bandwidth significantly)
                if (compressionEnabled) {
                    config.compression.gzipOnly(compressionLevel)
                } else {
                    config.compression.none()
                }
                if (customEnabled) {
                    // Serve any additional static assets (css/js/images) from custom root
                    val dir = customRootDir?.absolutePath
                    if (!dir.isNullOrBlank()) {
                        config.staticFiles.add { sf ->
                            sf.directory = dir
                            sf.location = Location.EXTERNAL
                            sf.precompress = false
                        }
                    }
                }
            }.start(host, port)
            
            // Prepare resource pack icons (if configured)
            prepareIcons()

            // Export default UI scaffold if requested and missing
            if (customEnabled && customExportDefault) {
                exportDefaultWebUi(false)
            }
            
            setupRoutes()

            // Apply any addon-registered routes after core routes are set up
            val a = app
            if (a != null) {
                try { addonRouteConfigurers.forEach { cfg -> runCatching { cfg(a) } } } catch (_: Throwable) {}
            }
            
            logger.info("Web server started on $host:$port")
            logger.info("Market interface available at: http://$host:$port")
            
            // Schedule session cleanup task
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                sessionManager.cleanupExpiredSessions()
            }, 20L * 60L, 20L * 60L) // Every minute
            // Broadcast latest prices to SSE/WS clients on each price tick (~update-interval-seconds). Lightweight poll based on manager.
            val period = runCatching { plugin.config.getInt("update-interval-seconds", 60) }.getOrDefault(60).coerceAtLeast(5)
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                try { broadcastLiveTick() } catch (_: Exception) {}
            }, 20L * period, 20L * period)
            
        } catch (e: Exception) {
            logger.error("Failed to start web server: ${e.message}")
        }
    }
    
    fun stop() {
        app?.stop()
        logger.info("Web server stopped")
    }
    
    fun createSession(player: Player): String {
        val defaultRole = plugin.config.getString("web.roles.default", "TRADER") ?: "TRADER"
        val traderPerm = plugin.config.getString("web.roles.trader-permission", "endex.web.trade") ?: "endex.web.trade"
        val role = if (player.hasPermission(traderPerm)) "TRADER" else defaultRole
        val session = sessionManager.createSession(player, role)
        val host = plugin.config.getString("web.host", "127.0.0.1") ?: "127.0.0.1"
        val portCfg = runCatching { plugin.config.getInt("web.port", 3434) }.getOrDefault(3434)
        return "http://${host}:${portCfg}/?session=${session.token}"
    }
    
    private fun setupRoutes() {
    val app = this.app ?: return
    sseEnabled = runCatching { plugin.config.getBoolean("web.sse.enabled", false) }.getOrDefault(false)
    wsEnabled = runCatching { plugin.config.getBoolean("web.websocket.enabled", true) }.getOrDefault(true)
        
        // Serve main interface
        app.get("/") { ctx ->
            val sessionToken = ctx.queryParam("session")
            if (sessionToken != null && sessionManager.getSession(sessionToken) != null) {
                val override = loadCustomIndexHtml()
                if (override != null) ctx.html(override) else ctx.html(getIndexHtml())
            } else {
                ctx.status(403).html(getUnauthorizedHtml())
            }
        }
        
        // API Routes
        app.get("/api/session") { ctx ->
            val session = validateSession(ctx) ?: return@get
            val pollMs = runCatching { plugin.config.getInt("web.poll-ms", 1000) }.getOrDefault(1000)
            val historyLimit = runCatching { plugin.config.getInt("web.history-limit", 120) }.getOrDefault(120)
            val serverName = runCatching { plugin.config.getString("web.brand.name", "The Endex") }.getOrDefault("The Endex")
            val logoText = runCatching { plugin.config.getString("web.brand.logoText", "📈") }.getOrDefault("📈")
            val theme = mapOf(
                "primary" to runCatching { plugin.config.getString("web.theme.primary", "#a78bfa") }.getOrDefault("#a78bfa"),
                "accent" to runCatching { plugin.config.getString("web.theme.accent", "#7c3aed") }.getOrDefault("#7c3aed"),
                "bg1" to runCatching { plugin.config.getString("web.theme.bg1", "#0f0f23") }.getOrDefault("#0f0f23"),
                "bg2" to runCatching { plugin.config.getString("web.theme.bg2", "#1a1a2e") }.getOrDefault("#1a1a2e"),
                "bg3" to runCatching { plugin.config.getString("web.theme.bg3", "#16213e") }.getOrDefault("#16213e")
            )
            ctx.json(mapOf(
                "player" to session.playerName,
                "uuid" to session.playerUuid.toString(),
                "expiresAt" to session.expiresAt.epochSecond,
                "pollMs" to pollMs,
                "historyLimit" to historyLimit,
                "sseEnabled" to sseEnabled,
                "wsEnabled" to wsEnabled,
                "iconsEnabled" to iconsEnabled,
                "invHoldingsEnabled" to (plugin.getInventorySnapshotService()?.enabled() == true),
                "role" to session.role,
                "brand" to mapOf("name" to serverName, "logoText" to logoText),
                "theme" to theme
            ))
        }

        app.get("/api/items") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val items = plugin.marketManager.allItems().map { item ->
                mapOf(
                    "material" to item.material.name,
                    "displayName" to item.material.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    "category" to categoryFor(item.material),
                    "basePrice" to item.basePrice,
                    "currentPrice" to item.currentPrice,
                    "effectivePrice" to item.currentPrice * plugin.eventManager.multiplierFor(item.material),
                    "multiplier" to plugin.eventManager.multiplierFor(item.material)
                )
            }
            ctx.json(items)
        }

        app.get("/api/balance") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            if (plugin.economy == null) {
                ctx.status(500).json(mapOf("error" to "Economy not available"))
                return@get
            }
            val offline = Bukkit.getOfflinePlayer(session.playerUuid)
            val bal = plugin.economy!!.getBalance(offline)
            ctx.json(mapOf("balance" to bal))
        }

        // Watchlist (favorites)
        app.get("/api/watchlist") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val list = plugin.prefsStore.getWatchlist(session.playerUuid)
            ctx.json(list)
        }
        data class WatchlistBody(val material: String?)
        app.post("/api/watchlist/add") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (!checkRateLimit(ctx, session.token)) return@post
            val body = runCatching { objectMapper.readValue(ctx.body(), WatchlistBody::class.java) }.getOrNull()
            val mat = body?.material?.uppercase()
            if (mat.isNullOrBlank()) { ctx.status(400).json(mapOf("error" to "material required")); return@post }
            plugin.prefsStore.addToWatchlist(session.playerUuid, mat)
            ctx.json(mapOf("ok" to true))
        }
        app.post("/api/watchlist/remove") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (!checkRateLimit(ctx, session.token)) return@post
            val body = runCatching { objectMapper.readValue(ctx.body(), WatchlistBody::class.java) }.getOrNull()
            val mat = body?.material?.uppercase()
            if (mat.isNullOrBlank()) { ctx.status(400).json(mapOf("error" to "material required")); return@post }
            plugin.prefsStore.removeFromWatchlist(session.playerUuid, mat)
            ctx.json(mapOf("ok" to true))
        }

        // Holdings view (requires SQLite storage)
        app.get("/api/holdings") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val db = plugin.marketManager.sqliteStore()
            if (db == null) {
                ctx.json(emptyList<Any>())
                return@get
            }
            val map = db.listHoldings(session.playerUuid.toString())
            val items = map.entries.map { (mat, pair) ->
                val qty = pair.first
                val avg = pair.second
                val current = plugin.marketManager.get(mat)?.currentPrice ?: 0.0
                mapOf(
                    "material" to mat.name,
                    "quantity" to qty,
                    "avgCost" to avg,
                    "currentPrice" to current
                )
            }
            ctx.json(items)
        }

        // Combined holdings (DB + optional live inventory snapshot)
        app.get("/api/holdings/combined") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val db = plugin.marketManager.sqliteStore()
            val dbMap = if (db != null) db.listHoldings(session.playerUuid.toString()) else emptyMap()
            // Start from DB holdings
            data class Parts(var investQty: Int = 0, var invQty: Int = 0, var avgCost: Double = 0.0)
            val combined = mutableMapOf<org.bukkit.Material, Parts>()
            dbMap.forEach { (mat, pair) ->
                val p = combined.getOrPut(mat) { Parts() }
                p.investQty = pair.first
                p.avgCost = pair.second
            }
            // Optionally merge in live inventory counts (online-only), tagged as avgCost=0.0 if no DB position
            val invSvc = plugin.getInventorySnapshotService()
            val inv = invSvc?.snapshotFor(session.playerUuid) ?: emptyMap()
            inv.forEach { (mat, qty) ->
                val p = combined.getOrPut(mat) { Parts() }
                p.invQty += qty
            }
            val items = combined.entries.map { (mat, parts) ->
                val qty = parts.investQty + parts.invQty
                val current = plugin.marketManager.get(mat)?.currentPrice ?: 0.0
                mapOf(
                    "material" to mat.name,
                    "quantity" to qty,
                    "investQty" to parts.investQty,
                    "invQty" to parts.invQty,
                    "avgCost" to parts.avgCost,
                    "currentPrice" to current
                )
            }
            ctx.json(items)
        }

        // Admin: Inspect another player's combined holdings (requires permission)
    app.get("/api/holdings/{uuid}") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            // Only allow if session has admin-like perm; we piggyback on a configured permission string
            val perm = plugin.config.getString("web.roles.admin-view-permission", "endex.web.admin") ?: "endex.web.admin"
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null || !player.hasPermission(perm)) { ctx.status(403).json(mapOf("error" to "Forbidden")); return@get }
            val targetUuid = runCatching { java.util.UUID.fromString(ctx.pathParam("uuid")!!) }.getOrNull()
            if (targetUuid == null) { ctx.status(400).json(mapOf("error" to "Invalid UUID")); return@get }
            val db = plugin.marketManager.sqliteStore()
            val dbMap = if (db != null) db.listHoldings(targetUuid.toString()) else emptyMap()
            data class Parts(var investQty: Int = 0, var invQty: Int = 0, var avgCost: Double = 0.0)
            val combined = mutableMapOf<org.bukkit.Material, Parts>()
            dbMap.forEach { (mat, pair) ->
                val p = combined.getOrPut(mat) { Parts() }
                p.investQty = pair.first
                p.avgCost = pair.second
            }
            val invSvc = plugin.getInventorySnapshotService()
            val inv = invSvc?.snapshotFor(targetUuid) ?: emptyMap()
            inv.forEach { (mat, qty) ->
                val p = combined.getOrPut(mat) { Parts() }
                p.invQty += qty
            }
            val items = combined.entries.map { (mat, parts) ->
                val qty = parts.investQty + parts.invQty
                val current = plugin.marketManager.get(mat)?.currentPrice ?: 0.0
                mapOf(
                    "material" to mat.name,
                    "quantity" to qty,
                    "investQty" to parts.investQty,
                    "invQty" to parts.invQty,
                    "avgCost" to parts.avgCost,
                    "currentPrice" to current
                )
            }
            ctx.json(items)
        }

        // Withdraw from holdings to inventory (Virtual Holdings System)
        data class WithdrawRequest(val material: String?, val amount: Int?)
        
        app.post("/api/holdings/withdraw") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (session.role != "TRADER") { ctx.status(403).json(mapOf("error" to "Trading not allowed")); return@post }
            if (!checkRateLimit(ctx, session.token)) return@post
            
            val db = plugin.marketManager.sqliteStore()
            if (db == null || !plugin.config.getBoolean("holdings.enabled", true)) {
                ctx.status(400).json(mapOf("error" to "Holdings system not enabled"))
                return@post
            }
            
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null) {
                ctx.status(400).json(mapOf("error" to "Player not online"))
                return@post
            }
            
            val req = runCatching { objectMapper.readValue(ctx.body(), WithdrawRequest::class.java) }.getOrNull()
            
            // Withdraw all if no material specified
            if (req == null || req.material.isNullOrBlank()) {
                val result = withdrawAllFromHoldings(player, db)
                ctx.json(result)
                return@post
            }
            
            // Withdraw specific material
            val material = runCatching { Material.valueOf(req.material!!) }.getOrNull()
            if (material == null) {
                ctx.status(400).json(mapOf("error" to "Invalid material: ${req.material}"))
                return@post
            }
            
            val requestedAmount = req.amount ?: Int.MAX_VALUE
            if (requestedAmount <= 0) {
                ctx.status(400).json(mapOf("error" to "Invalid amount"))
                return@post
            }
            
            val result = withdrawFromHoldings(player, db, material, requestedAmount)
            ctx.json(result)
        }
        
        // Get holdings stats (total count, limit info)
        app.get("/api/holdings/stats") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            
            val db = plugin.marketManager.sqliteStore()
            val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
            val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
            
            if (db == null || !holdingsEnabled) {
                ctx.json(mapOf(
                    "enabled" to false,
                    "totalItems" to 0,
                    "maxItems" to 0,
                    "uniqueMaterials" to 0
                ))
                return@get
            }
            
            val holdings = db.listHoldings(session.playerUuid.toString())
            val totalItems = holdings.values.sumOf { it.first }
            
            ctx.json(mapOf(
                "enabled" to true,
                "totalItems" to totalItems,
                "maxItems" to maxHoldings,
                "uniqueMaterials" to holdings.size,
                "percentUsed" to if (maxHoldings > 0) (totalItems.toDouble() / maxHoldings * 100) else 0.0
            ))
        }

        // Trade receipts (recent)
        app.get("/api/receipts") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 50
            val db = plugin.marketManager.sqliteStore()
            if (db == null) { ctx.json(emptyList<Any>()); return@get }
            val list = db.listTrades(session.playerUuid.toString(), limit).map { tr ->
                mapOf(
                    "time" to tr.time.epochSecond,
                    "material" to tr.material.name,
                    "type" to tr.type,
                    "amount" to tr.amount,
                    "unitPrice" to tr.unitPrice,
                    "total" to tr.total
                )
            }
            ctx.json(list)
        }

        data class TradeRequest(val material: String, val amount: Int)

        app.post("/api/buy") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (session.role != "TRADER") { ctx.status(403).json(mapOf("error" to "Trading not allowed")); return@post }
            if (!checkRateLimit(ctx, session.token)) return@post
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null) {
                ctx.status(400).json(mapOf("error" to "Player not online"))
                return@post
            }
            val req = runCatching { objectMapper.readValue(ctx.body(), TradeRequest::class.java) }.getOrNull()
            if (req == null) {
                ctx.status(400).json(mapOf("error" to "Invalid request body"))
                return@post
            }
            val material = runCatching { Material.valueOf(req.material) }.getOrNull()
            if (material == null || req.amount <= 0) {
                ctx.status(400).json(mapOf("error" to "Invalid material or amount"))
                return@post
            }
            val ok = performBuy(player, material, req.amount)
            if (!ok) {
                ctx.status(400).json(mapOf("error" to "Buy failed"))
            } else {
                ctx.json(mapOf("success" to true))
            }
        }

        app.post("/api/sell") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (session.role != "TRADER") { ctx.status(403).json(mapOf("error" to "Trading not allowed")); return@post }
            if (!checkRateLimit(ctx, session.token)) return@post
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null) {
                ctx.status(400).json(mapOf("error" to "Player not online"))
                return@post
            }
            val req = runCatching { objectMapper.readValue(ctx.body(), TradeRequest::class.java) }.getOrNull()
            if (req == null) {
                ctx.status(400).json(mapOf("error" to "Invalid request body"))
                return@post
            }
            val material = runCatching { Material.valueOf(req.material) }.getOrNull()
            if (material == null || req.amount <= 0) {
                ctx.status(400).json(mapOf("error" to "Invalid material or amount"))
                return@post
            }
            val ok = performSell(player, material, req.amount)
            if (!ok) {
                ctx.status(400).json(mapOf("error" to "Sell failed"))
            } else {
                ctx.json(mapOf("success" to true))
            }
        }

        // Sell from holdings endpoint
        app.post("/api/sell-holdings") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (session.role != "TRADER") { ctx.status(403).json(mapOf("error" to "Trading not allowed")); return@post }
            if (!checkRateLimit(ctx, session.token)) return@post
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null) {
                ctx.status(400).json(mapOf("error" to "Player not online"))
                return@post
            }
            val req = runCatching { objectMapper.readValue(ctx.body(), TradeRequest::class.java) }.getOrNull()
            if (req == null) {
                ctx.status(400).json(mapOf("error" to "Invalid request body"))
                return@post
            }
            val material = runCatching { Material.valueOf(req.material) }.getOrNull()
            if (material == null || req.amount <= 0) {
                ctx.status(400).json(mapOf("error" to "Invalid material or amount"))
                return@post
            }
            val result = performSellFromHoldings(player, material, req.amount)
            if (!result.first) {
                ctx.status(400).json(mapOf("error" to (result.second ?: "Sell from holdings failed")))
            } else {
                ctx.json(mapOf("success" to true, "sold" to result.third, "message" to result.second))
            }
        }

        // Delivery system endpoints
        app.get("/api/deliveries") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            
            val deliveryMgr = plugin.getDeliveryManager()
            if (deliveryMgr == null || !plugin.config.getBoolean("delivery.enabled", true)) {
                ctx.json(mapOf("enabled" to false, "deliveries" to emptyList<Any>()))
                return@get
            }
            
            val pending = deliveryMgr.listPending(session.playerUuid)
            val result = pending.map { (material, amount) ->
                mapOf(
                    "material" to material.name,
                    "amount" to amount
                )
            }
            
            ctx.json(mapOf(
                "enabled" to true,
                "deliveries" to result,
                "total" to pending.values.sum()
            ))
        }

        data class ClaimRequest(val material: String?, val amount: Int?)

        app.post("/api/deliveries/claim") { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (session.role != "TRADER") { ctx.status(403).json(mapOf("error" to "Trading not allowed")); return@post }
            if (!checkRateLimit(ctx, session.token)) return@post
            
            val deliveryMgr = plugin.getDeliveryManager()
            if (deliveryMgr == null || !plugin.config.getBoolean("delivery.enabled", true)) {
                ctx.status(400).json(mapOf("error" to "Delivery system not enabled"))
                return@post
            }
            
            val player = Bukkit.getPlayer(session.playerUuid)
            if (player == null) {
                ctx.status(400).json(mapOf("error" to "Player not online"))
                return@post
            }
            
            val req = runCatching { objectMapper.readValue(ctx.body(), ClaimRequest::class.java) }.getOrNull()
            
            // Claim all if no material specified
            if (req == null || req.material.isNullOrBlank()) {
                val result = deliveryMgr.claimAll(player)
                if (result.error != null) {
                    ctx.status(400).json(mapOf("error" to result.error))
                    return@post
                }
                
                val totalClaimed = result.delivered.values.sum()
                ctx.json(mapOf(
                    "success" to true,
                    "claimed" to totalClaimed,
                    "remaining" to result.totalRemaining,
                    "details" to result.delivered.map { (mat, count) ->
                        mapOf("material" to mat.name, "amount" to count)
                    }
                ))
                return@post
            }
            
            // Claim specific material
            val material = runCatching { Material.valueOf(req.material!!) }.getOrNull()
            if (material == null) {
                ctx.status(400).json(mapOf("error" to "Invalid material: ${req.material}"))
                return@post
            }
            
            val requestedAmount = req.amount ?: Int.MAX_VALUE
            if (requestedAmount <= 0) {
                ctx.status(400).json(mapOf("error" to "Invalid amount"))
                return@post
            }
            
            val result = deliveryMgr.claimMaterial(player, material, requestedAmount)
            if (result.error != null) {
                ctx.status(400).json(mapOf("error" to result.error))
                return@post
            }
            
            ctx.json(mapOf(
                "success" to true,
                "material" to material.name,
                "claimed" to result.delivered,
                "remaining" to result.remainingPending
            ))
        }

        // Historical price data for chart
        // Historical price data for chart, supports optional since=epochSecond (returns deltas)
        app.get("/api/history") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val matParam = ctx.queryParam("material")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 120
            val since = ctx.queryParam("since")?.toLongOrNull()
            val windowSec = ctx.queryParam("window")?.toLongOrNull() ?: 0L // e.g., last seconds window
            val bucketSec = ctx.queryParam("bucket")?.toLongOrNull() ?: 0L // e.g., bucket size in seconds for downsampling
            if (matParam.isNullOrBlank()) {
                ctx.status(400).json(mapOf("error" to "Missing material"))
                return@get
            }
            val material = runCatching { Material.valueOf(matParam) }.getOrNull()
            if (material == null) {
                ctx.status(400).json(mapOf("error" to "Invalid material"))
                return@get
            }
            val item = plugin.marketManager.get(material)
            if (item == null) {
                ctx.status(404).json(mapOf("error" to "Item not found"))
                return@get
            }
            val all = item.history.toList()
            // Choose base series by window or limit
            val base: List<Pair<Long, Double>> = if (windowSec > 0L) {
                val cutoff = java.time.Instant.now().epochSecond - windowSec
                all.asSequence().map { it.time.epochSecond to it.price }.filter { it.first >= cutoff }.toList()
            } else {
                all.takeLast(limit).map { it.time.epochSecond to it.price }
            }
            val points = if (bucketSec > 0L && since == null) {
                // Aggregate into buckets by average price
                val grouped = base.groupBy { (it.first / bucketSec) * bucketSec }
                grouped.entries
                    .map { (bucketStart, list) ->
                        val avg = list.map { it.second }.average()
                        bucketStart to avg
                    }
                    .sortedBy { it.first }
            } else base
            val latestTs = points.lastOrNull()?.first ?: 0L

            // ETag handling based on last timestamp
            val etag = "W/\"h:${material.name}:$limit:w=$windowSec:b=$bucketSec:$latestTs\""
            val ifNone = ctx.header("If-None-Match")
            if (ifNone != null && ifNone == etag && (since == null || since <= 0L)) {
                ctx.status(304)
                return@get
            }

            // If client asks for deltas since a timestamp, return only newer points
            if (since != null && since > 0L) {
                val delta = points.filter { it.first > since }.map { mapOf("timestamp" to it.first, "price" to it.second) }
                ctx.json(mapOf(
                    "latest" to latestTs,
                    "points" to delta
                ))
                return@get
            }

            // Full response, try cache by material+limit
            val key = historyCacheKey(material, limit)
            val cached = historyCache[key]
            if (windowSec <= 0 && bucketSec <= 0 && cached != null && cached.lastEpochSecond == latestTs) {
                ctx.header("ETag", etag)
                ctx.contentType("application/json").result(cached.json)
                return@get
            }
            val body = objectMapper.writeValueAsString(points.map { mapOf("timestamp" to it.first, "price" to it.second) })
            if (windowSec <= 0 && bucketSec <= 0) {
                historyCache[key] = CachedHistory(material, limit, latestTs, body)
            }
            ctx.header("ETag", etag)
            ctx.contentType("application/json").result(body)
        }

        // Optional SSE endpoint for live price updates (off by default)
        if (sseEnabled) {
            app.sse("/api/sse") { client ->
                // Note: context is not accessible here in our Javalin version; treat this as a public read-only feed.
                sseClients.add(client)
                client.onClose { sseClients.remove(client) }
                try { client.sendEvent("hello", "connected") } catch (_: Exception) {}
            }
        }

        // Optional WebSocket endpoint (on by default)
        if (wsEnabled) {
            app.ws("/api/ws") { ws ->
                ws.onConnect { ctx ->
                    val token = ctx.queryParam("session")
                    val ok = token != null && sessionManager.getSession(token) != null
                    if (!ok) {
                        runCatching { ctx.send("{\"type\":\"error\",\"message\":\"invalid session\"}") }
                        ctx.session.close(1008, "invalid session")
                        return@onConnect
                    }
                    wsClients.add(ctx)
                    runCatching { ctx.send("{\"type\":\"hello\",\"message\":\"connected\"}") }
                }
                ws.onClose { ctx -> wsClients.remove(ctx) }
                ws.onError { ctx -> wsClients.remove(ctx) }
            }
        }

        // Serve item/block icons (PNG) from resource pack textures if enabled (public, no auth)
        app.get("/icon/{material}") { ctx ->
            if (!iconsEnabled) { ctx.status(404); return@get }
            val matName = ctx.pathParam("material").uppercase()
            val mat = runCatching { Material.valueOf(matName) }.getOrNull()
            if (mat == null) { ctx.status(404); return@get }
            val root = texturesRoot
            if (root == null || !root.exists()) { ctx.status(404); return@get }
            // Try item textures first, then block textures as fallback to cover materials with both forms
            val subs = listOf("item", "items", "block", "blocks")
            val baseName = mat.name.lowercase()
            // Build candidate filenames to try (ordered, unique) to handle pack variations and generated icons
            val candSet = linkedSetOf<String>()
            fun add(vararg names: String) { names.forEach { candSet.add(it) } }
            // Always try exact name first
            add("$baseName.png")
            // Generic rules
            if (baseName.endsWith("_spawn_egg")) add("spawn_egg.png")
            when (baseName) {
                "potion" -> add("potion_bottle_drinkable.png")
                "splash_potion" -> add("potion_bottle_splash.png")
                "lingering_potion" -> add("potion_bottle_lingering.png")
                "tipped_arrow" -> add("arrow.png")
                "map" -> add("map.png")
                "filled_map" -> add("filled_map.png")
                "writable_book" -> add("writable_book.png", "book_and_quill.png")
                "written_book" -> add("written_book.png")
            }
            // Crop blocks with singular item icons
            when (mat) {
                Material.CARROTS -> add("carrot.png")
                Material.POTATOES -> add("potato.png")
                Material.BEETROOTS -> add("beetroot.png")
                else -> {}
            }
            // Tricky blocks/items with differing names across packs
            when (mat) {
                Material.PUMPKIN -> add("pumpkin.png", "carved_pumpkin.png", "pumpkin_side.png")
                Material.MELON -> add("melon_side.png", "melon_top.png")
                Material.CACTUS -> add("cactus.png", "cactus_side.png", "cactus_top.png")
                Material.COCOA -> add("cocoa_beans.png")
                Material.LILY_PAD -> add("waterlily.png")
                Material.SWEET_BERRY_BUSH -> add("sweet_berries.png")
                Material.CAVE_VINES, Material.CAVE_VINES_PLANT -> add("glow_berries.png")
                Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM -> add("pumpkin.png")
                Material.MELON_STEM, Material.ATTACHED_MELON_STEM -> add("melon_slice.png", "melon_side.png")
                Material.SUGAR_CANE -> add("sugar_cane.png")
                Material.BAMBOO_SAPLING -> add("bamboo.png")
                Material.VINE -> add("vine.png")
                Material.WHEAT -> add("wheat.png")
                Material.KELP_PLANT -> add("kelp.png")
                Material.NETHER_WART -> add("nether_wart_item.png")
                else -> {}
            }
            // Simple singularization fallback: e.g., beetroots -> beetroot
            if (baseName.endsWith("s") && baseName.length > 1) {
                add(baseName.dropLast(1) + ".png")
            }
            val candidates = candSet.toList()
            var file: File? = null
            outer@ for (s in subs) {
                for (name in candidates) {
                    val cand = File(root, "$s/$name")
                    if (cand.exists()) { file = cand; break@outer }
                }
            }
            if (file == null) {
                val verbose = runCatching { plugin.config.getBoolean("logging.verbose", false) }.getOrDefault(false)
                if (verbose) {
                    try {
                        val tried = buildString {
                            for (s in subs) {
                                for (name in candidates) {
                                    append(File(root, "$s/$name").absolutePath)
                                    append(", ")
                                }
                            }
                        }.trim().trimEnd(',')
                        logger.info("Icon not found for $matName — tried: $tried")
                    } catch (_: Exception) {}
                }
                ctx.status(404); return@get
            }
            val etag = "W/\"icon:${file!!.name}:${file!!.lastModified()}\""
            val inm = ctx.header("If-None-Match")
            if (inm != null && inm == etag) { ctx.status(304); return@get }
            ctx.header("ETag", etag)
            ctx.contentType("image/png").result(file!!.inputStream())
        }

        // (Removed) /api/addons endpoint was used by deprecated Addons tab

        // Simple API docs
        app.get("/docs") { ctx ->
            ctx.contentType("text/html").result(
                """
                <html><head><title>The Endex API Docs</title></head>
                <body style='font-family:Arial;padding:20px;background:#0f0f23;color:#e8e9ea'>
                <h1>The Endex API</h1>
                <p>Authentication: initial in-game link provides <code>?session=TOKEN</code>; the web UI upgrades this to an <code>Authorization: Bearer TOKEN</code> header and strips it from the URL. Legacy <code>?session=</code> remains accepted (incl. WS/SSE). API read-only tokens may use the same header or <code>?token=TOKEN</code>.</p>
                <ul>
                    <li>GET <code>/api/session</code> — session info, theme/brand, feature flags</li>
                    <li>GET <code>/api/items</code> — current items and prices</li>
                    <li>GET <code>/api/balance</code> — player balance</li>
                    <li>POST <code>/api/buy</code> — buy item (TRADER only)</li>
                    <li>POST <code>/api/sell</code> — sell item (TRADER only)</li>
                    <li>GET <code>/api/deliveries</code> — list pending deliveries (if enabled)</li>
                    <li>POST <code>/api/deliveries/claim</code> — claim pending deliveries (body: {material?, amount?})</li>
                    <li>GET <code>/api/history?material=MAT&limit=N&since=TS</code> — history with optional deltas</li>
                    <li>GET <code>/api/holdings</code> — holdings</li>
                    <li>GET <code>/api/holdings/combined</code> — holdings + live inventory (if enabled)</li>
                    <li>GET <code>/api/receipts?limit=N</code> — recent trades</li>
                    <li>GET <code>/api/watchlist</code>, POST <code>/api/watchlist/add|remove</code></li>
                    <li>GET <code>/api/inventory-totals</code> — live per-material online inventory totals (if enabled)</li>
                    <li>SSE <code>/api/sse</code> (if enabled), WS <code>/api/ws</code></li>
                </ul>
                </body></html>
                """.trimIndent()
            )
        }

        // Optional: expose inventory totals for debugging/monitoring (requires inv snapshots enabled)
        app.get("/api/inventory-totals") { ctx ->
            val session = validateSession(ctx) ?: return@get
            if (!checkRateLimit(ctx, session.token)) return@get
            val invSvc = plugin.getInventorySnapshotService()
            if (invSvc == null || !invSvc.enabled()) { ctx.json(emptyMap<String, Any>()); return@get }
            val totals = invSvc.snapshotTotals()
            val map = totals.entries.associate { (mat, qty) -> mat.name to qty }
            ctx.json(map)
        }
    }

    private fun checkRateLimit(ctx: Context, sessionToken: String): Boolean {
        if (!rateEnabled) return true
        // Allow exempting the built-in UI to avoid 429 noise while keeping API protected
        val exemptUi = runCatching { plugin.config.getBoolean("web.rate-limit.exempt-ui", true) }.getOrDefault(true)
        if (exemptUi && ctx.header("X-Endex-UI") == "1") return true
        val now = System.currentTimeMillis()
        val windowStart = now - rateWindowSeconds * 1000L
        val list = requestCounts.getOrPut(sessionToken) { mutableListOf() }
        synchronized(list) {
            // drop old entries
            while (list.isNotEmpty() && list.first() < windowStart) list.removeAt(0)
            if (list.size >= rateMaxRequests) {
                // Advise client when to retry to avoid spamming
                ctx.header("Retry-After", rateWindowSeconds.toString())
                ctx.status(429).json(mapOf(
                    "error" to "Too Many Requests",
                    "retryAfter" to rateWindowSeconds
                ))
                return false
            }
            list.add(now)
        }
        return true
    }

    private fun broadcastLiveTick() {
        val snapshot = plugin.marketManager.allItems().map { item ->
            mapOf(
                "material" to item.material.name,
                "price" to (item.currentPrice * plugin.eventManager.multiplierFor(item.material))
            )
        }
        val payload = objectMapper.writeValueAsString(mapOf("type" to "tick", "items" to snapshot))
        if (sseEnabled) {
            val copySse: List<SseClient> = synchronized(sseClients) { sseClients.toList() }
            for (c in copySse) {
                runCatching { c.sendEvent("tick", payload) }.onFailure { synchronized(sseClients) { sseClients.remove(c) } }
            }
        }
        if (wsEnabled) {
            val copyWs: List<WsContext> = synchronized(wsClients) { wsClients.toList() }
            for (c in copyWs) {
                runCatching { c.send(payload) }.onFailure { synchronized(wsClients) { wsClients.remove(c) } }
            }
        }
    }

    // --- Addon integration helpers ---
    // Add a route configurer; if server already running, apply immediately as well
    fun addAddonRoutes(configurer: (Javalin) -> Unit) {
        addonRouteConfigurers.add(configurer)
        val a = app
        if (a != null) runCatching { configurer(a) }
    }
    // Convenience for simple read-only JSON GET endpoints that require no request context.
    fun addGet(path: String, jsonProvider: () -> Any) {
        addAddonRoutes { a -> a.get(path) { ctx ->
            try { ctx.json(jsonProvider()) } catch (t: Throwable) { ctx.status(500).json(mapOf("error" to (t.message ?: "server error"))) }
        } }
    }

    // Addon GET with optional session validation. If requireSession=true and session is invalid, responds 403.
    fun addGet(path: String, requireSession: Boolean, handler: (WebSession?) -> Any) {
        addAddonRoutes { a -> a.get(path) { ctx ->
            val session = if (requireSession) validateSession(ctx) else null
            if (requireSession && session == null) return@get
            if (session != null && !checkRateLimit(ctx, session.token)) return@get
            try { ctx.json(handler(session)) } catch (t: Throwable) { ctx.status(500).json(mapOf("error" to (t.message ?: "server error"))) }
        } }
    }

    // Addon POST with session validation and body passthrough.
    fun addPost(path: String, handler: (WebSession, String) -> Any) {
        addAddonRoutes { a -> a.post(path) { ctx ->
            val session = validateSession(ctx) ?: return@post
            if (!checkRateLimit(ctx, session.token)) return@post
            val body = runCatching { ctx.body() }.getOrDefault("")
            try { ctx.json(handler(session, body)) } catch (t: Throwable) { ctx.status(500).json(mapOf("error" to (t.message ?: "server error"))) }
        } }
    }

    // Allow addons to register a display name that appears under the Addons tab.
    fun registerAddonNav(name: String) {
        if (name.isNotBlank()) addonNav.add(name)
    }

    // Simple categorization for UI grouping
    private fun categoryFor(mat: Material): String {
        val n = mat.name
        return when {
            n.contains("_ORE") || n.endsWith("_INGOT") || n.endsWith("_BLOCK") -> "Ores"
            listOf("WHEAT","SEEDS","CARROT","POTATO","BEETROOT","MELON","PUMPKIN","SUGAR","BAMBOO","COCOA").any { n.contains(it) } -> "Farming"
            n in setOf("ROTTEN_FLESH","BONE","STRING","SPIDER_EYE","ENDER_PEARL","GUNPOWDER","BLAZE_ROD","GHAST_TEAR","SLIME_BALL","LEATHER","FEATHER") -> "Mob Drops"
            mat.isBlock -> "Blocks"
            else -> "Misc"
        }
    }

    // Prepare resource pack icons for web
    private fun prepareIcons() {
        iconsEnabled = runCatching { plugin.config.getBoolean("web.icons.enabled", false) }.getOrDefault(false)
        if (!iconsEnabled) {
            logger.info("Web icons disabled via config (web.icons.enabled=false)")
            return
        }
        val src = runCatching { plugin.config.getString("web.icons.source") }.getOrNull()?.trim().orEmpty()
        val baseDir = File(plugin.dataFolder, "resourcepack")
        if (!baseDir.exists()) baseDir.mkdirs()

        fun setTexturesFrom(dir: File) {
            // Accept direct textures dir or a pack root containing assets/minecraft/textures
            val direct = File(dir, "assets/minecraft/textures")
            texturesRoot = when {
                dir.name == "textures" && (File(dir, "item").exists() || File(dir, "items").exists() || File(dir, "block").exists() || File(dir, "blocks").exists()) -> dir
                direct.exists() -> direct
                else -> null
            }
        }

        if (src.isBlank()) {
            // If previously extracted exists, use it; otherwise disable gracefully
            val existing = File(baseDir, "assets/minecraft/textures")
            if (existing.exists()) {
                texturesRoot = existing
                iconsEnabled = true
                logger.info("Using previously extracted resource pack textures: ${existing.absolutePath}")
                return
            }
            logger.warn("web.icons.enabled=true but no web.icons.source configured; icons disabled")
            iconsEnabled = false
            return
        }

        try {
            if (src.startsWith("http://") || src.startsWith("https://")) {
                val tmpZip = File(baseDir, "pack.zip")
                URL(src).openStream().use { inp ->
                    Files.copy(inp, tmpZip.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                unzipTextures(tmpZip, baseDir)
                setTexturesFrom(baseDir)
                iconsEnabled = texturesRoot?.exists() == true
            } else {
                val f = File(src)
                if (!f.exists()) {
                    logger.warn("web.icons.source path not found: $src")
                    iconsEnabled = false
                    return
                }
                if (f.isDirectory) {
                    setTexturesFrom(f)
                    iconsEnabled = texturesRoot?.exists() == true
                } else if (f.isFile && f.name.endsWith(".zip", ignoreCase = true)) {
                    unzipTextures(f, baseDir)
                    setTexturesFrom(baseDir)
                    iconsEnabled = texturesRoot?.exists() == true
                }
            }
            if (iconsEnabled) {
                logger.info("Web icons enabled; textures root: ${texturesRoot?.absolutePath}")
            } else {
                logger.warn("Failed to locate textures after processing resource pack; icons disabled")
            }
        } catch (e: Exception) {
            iconsEnabled = false
            logger.warn("Failed to prepare web icons: ${e.message}")
        }
    }

    private fun unzipTextures(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("assets/minecraft/textures/") && !entry.isDirectory) {
                    val out = File(destDir, name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Export the current embedded UI to customRootDir/index.html if it doesn't already exist
     * or if force=true. Keeps everything in a single file for simplicity; server owners can later
     * split into multiple assets and rely on static file serving.
     */
    private fun exportDefaultWebUi(force: Boolean) {
        if (!customEnabled) return
        val dir = customRootDir ?: return
        val target = File(dir, "index.html")
        if (target.exists() && !force) return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(getIndexHtml(), Charsets.UTF_8)
            logger.info("Exported default web UI to ${target.relativeToOrSelf(plugin.dataFolder)}")
        }.onFailure { logger.warn("Failed exporting default web UI: ${it.message}") }
    }

    // Public wrapper for admin command (avoids reflection)
    fun forceExportDefaultUiOverwrite() = exportDefaultWebUi(true)

    // Force next load to re-read custom index if present
    fun forceReloadCustomIndex() {
        if (!customEnabled) return
        cachedCustomIndex = null
        // Temporarily enable reload mode for one cycle if disabled
        customReload = true
    }

    /**
     * Load custom index.html honoring caching / reload flag. Returns null if custom disabled or file absent.
     */
    private fun loadCustomIndexHtml(): String? {
        if (!customEnabled) return null
        val file = File(customRootDir, "index.html")
        if (!file.exists()) return null
        if (!customReload) {
            // Cached mode
            if (cachedCustomIndex != null && file.lastModified() == cachedCustomIndexMtime) {
                return cachedCustomIndex
            }
        }
        val txt = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!customReload) {
            cachedCustomIndex = txt
            cachedCustomIndexMtime = file.lastModified()
        }
        return txt
    }

    private fun validateSession(ctx: Context): WebSession? {
        // 1. Authorization header (preferred). Accepts player session or API read-only token.
        ctx.header("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ")) {
                val raw = auth.substring(7).trim()
                if (raw.isNotBlank()) {
                    val s = sessionManager.getSession(raw)
                    if (s != null) return s
                    if (isConfiguredApiToken(raw)) {
                        val now = java.time.Instant.now()
                        return WebSession(
                            token = raw,
                            playerUuid = java.util.UUID(0L, 0L),
                            playerName = "API",
                            role = "VIEWER",
                            createdAt = now,
                            expiresAt = now.plusSeconds(30L * 24L * 3600L)
                        )
                    }
                }
            }
        }
        // 2. Legacy query parameter ?session= (still accepted, used by WS/SSE initial connection)
        ctx.queryParam("session")?.let { t ->
            val s = sessionManager.getSession(t)
            if (s != null) return s
        }
        // 3. Read-only API token via ?token=
        ctx.queryParam("token")?.let { token ->
            if (token.isNotBlank()) {
                if (isConfiguredApiToken(token)) {
                    val now = java.time.Instant.now()
                    return WebSession(
                        token = token,
                        playerUuid = java.util.UUID(0L, 0L),
                        playerName = "API",
                        role = "VIEWER",
                        createdAt = now,
                        expiresAt = now.plusSeconds(30L * 24L * 3600L)
                    )
                }
            }
        }
        ctx.status(403).json(mapOf("error" to "Invalid or expired session"))
        return null
    }

    /**
     * Returns true if the provided candidate matches a configured API token.
     * Supports both legacy plain tokens (web.api.tokens) and hashed tokens (web.api.token-hashes).
     * Hashed tokens must be stored as lowercase hex SHA-256 digests (64 chars).
     * When at least one hashed token is configured, a warning is emitted if legacy plain tokens are still present.
     */
    private fun isConfiguredApiToken(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val plain = runCatching { plugin.config.getStringList("web.api.tokens") }.getOrDefault(emptyList())
        val hashed = runCatching { plugin.config.getStringList("web.api.token-hashes") }.getOrDefault(emptyList())
        var match = false
        if (plain.isNotEmpty()) {
            // Direct membership test (legacy). This remains for backward compatibility.
            if (plain.contains(candidate)) match = true
        }
        if (hashed.isNotEmpty()) {
            // Compute SHA-256 of candidate and compare (constant time) to each configured digest.
            val dig = sha256Hex(candidate)
            for (h in hashed) {
                if (h.length == 64 && constantTimeEquals(h.lowercase(), dig)) {
                    match = true
                    break
                }
            }
            // If hashed tokens exist but legacy plain array still populated, emit a single deprecation warning once.
            if (plain.isNotEmpty()) emitLegacyTokenWarningOnce()
        }
        return match
    }

    @Volatile private var legacyTokenWarned: Boolean = false
    private fun emitLegacyTokenWarningOnce() {
        if (!legacyTokenWarned) {
            legacyTokenWarned = true
            logger.warn("Both web.api.tokens (plain) and web.api.token-hashes are configured. Plain tokens are deprecated; migrate to hashed digests only.")
        }
    }

    private fun sha256Hex(input: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (e: Exception) { "" }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) {
            r = r or (a[i].code xor b[i].code)
        }
        return r == 0
    }

    private fun getIndexHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>The Endex - Market Trading</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
            <style>
                ${getStylesCss()}
            </style>
        </head>
        <body>
            <header class="header">
                <div class="container">
                    <h1 class="logo">
                        <span class="logo-icon" id="brand-logo">📈</span>
                        <span id="brand-name">The Endex</span>
                    </h1>
                    <div class="header-info">
                        <span class="balance">Balance: $<span id="balance">0.00</span></span>
                        <span class="player-name" id="player-name">Loading...</span>
                        <div id="google_translate_element"></div>
                        <label class="theme-toggle"><input type="checkbox" id="theme-toggle"> Darker</label>
                    </div>
                </div>
            </header>

            <main class="main">
                <div class="container">
                    <div class="trading-grid">
                        <!-- Market Overview -->
                        <div class="panel market-overview">
                            <h2 class="panel-title">Market Overview</h2>
                            <div class="market-stats">
                                <div class="stat-card">
                                    <div class="stat-label">Total Items</div>
                                    <div class="stat-value" id="total-items">-</div>
                                </div>
                                <div class="stat-card">
                                    <div class="stat-label">Average Price</div>
                                    <div class="stat-value" id="avg-price">$-</div>
                                </div>
                                <div class="stat-card">
                                    <div class="stat-label">Total Volume</div>
                                    <div class="stat-value" id="total-volume">-</div>
                                </div>
                                <div class="stat-card">
                                    <div class="stat-label">Top Gainer</div>
                                    <div class="stat-value" id="top-gainer">-</div>
                                </div>
                                <div class="stat-card">
                                    <div class="stat-label">Top Loser</div>
                                    <div class="stat-value" id="top-loser">-</div>
                                </div>
                            </div>
                        </div>

                        <!-- Item List -->
                        <div class="panel item-list">
                            <h2 class="panel-title">Market Items</h2>
                            <div class="search-bar">
                                <div class="search-row">
                                    <input type="text" id="search-input" placeholder="Search items..." class="search-input">
                                    <label class="watchlist-toggle"><input type="checkbox" id="favorites-only"> <span>Favorites only</span></label>
                                    <label class="watchlist-toggle"><input type="checkbox" id="group-by-category" checked> <span>Group by category (A–Z)</span></label>
                                    <button id="toggle-filters" class="filters-btn" type="button">Filters ▸</button>
                                </div>
                                <div class="filters-row" id="filters-row" style="display:none;">
                                    <select id="filter-category" class="filter-select">
                                        <option value="ALL">All categories</option>
                                    </select>
                                    <input type="number" id="filter-min-price" class="price-input" step="0.01" placeholder="Min $">
                                    <input type="number" id="filter-max-price" class="price-input" step="0.01" placeholder="Max $">
                                    <select id="filter-trend" class="filter-select">
                                        <option value="any">Any trend</option>
                                        <option value="up">Up only</option>
                                        <option value="down">Down only</option>
                                    </select>
                                    <select id="sort-by" class="filter-select">
                                        <option value="name">Sort: Name</option>
                                        <option value="price">Sort: Price</option>
                                        <option value="change">Sort: Change</option>
                                        <option value="volume">Sort: Volume</option>
                                    </select>
                                    <label class="watchlist-toggle" style="justify-self:start"><input type="checkbox" id="sort-desc"> <span>Descending</span></label>
                                </div>
                            </div>
                            <div class="items-container" id="items-container">
                                <div class="loading">Loading market data...</div>
                            </div>
                        </div>

                        <!-- Trading Panel -->
                        <div class="panel trading-panel">
                            <h2 class="panel-title">Trade Item</h2>
                            <div class="trading-content">
                                <div class="no-selection">
                                    <span class="selection-icon">🎯</span>
                                    <p>Select an item from the market to start trading</p>
                                </div>

                                <div class="selected-item-info">
                                    <div class="selected-item-name">Select an item</div>
                                    <div class="selected-item-price">$0.00</div>
                                    <div class="item-details">
                                        <div class="detail-item">
                                            <div class="detail-label">Base Price</div>
                                            <div class="detail-value" id="base-price">$0.00</div>
                                        </div>
                                        <div class="detail-item">
                                            <div class="detail-label">Multiplier</div>
                                            <div class="detail-value" id="multiplier">1.00x</div>
                                        </div>
                                        <div class="detail-item">
                                            <div class="detail-label">Change</div>
                                            <div class="detail-value" id="change">0.0%</div>
                                        </div>
                                        <div class="detail-item">
                                            <div class="detail-label">Volume</div>
                                            <div class="detail-value" id="volume">0</div>
                                        </div>
                                    </div>
                                </div>

                                <!-- Buy/Sell Forms -->
                                <div class="trade-forms">
                                    <div class="trade-form buy-form">
                                        <h3>Buy Order</h3>
                                        <div class="selected-item" id="buy-selected">Select an item</div>
                                        <input type="number" class="quantity-input" id="buy-quantity" min="1" value="1" />
                                        <div class="quick-buttons">
                                            <button class="btn-quick" data-target="buy-quantity" data-val="1">1</button>
                                            <button class="btn-quick" data-target="buy-quantity" data-val="5">5</button>
                                            <button class="btn-quick" data-target="buy-quantity" data-val="10">10</button>
                                            <button class="btn-quick" data-target="buy-quantity" data-val="MAX">Max</button>
                                        </div>
                                        <div class="total-cost">Total: $<span id="buy-total">0.00</span></div>
                                        <button id="buy-btn" class="btn btn-buy" disabled>Buy</button>
                                    </div>

                                    <div class="trade-form sell-form">
                                        <h3>Sell Order</h3>
                                        <div class="selected-item" id="sell-selected">Select an item</div>
                                        <input type="number" class="quantity-input" id="sell-quantity" min="1" value="1" />
                                        <div class="quick-buttons">
                                            <button class="btn-quick" data-target="sell-quantity" data-val="1">1</button>
                                            <button class="btn-quick" data-target="sell-quantity" data-val="5">5</button>
                                            <button class="btn-quick" data-target="sell-quantity" data-val="10">10</button>
                                            <button class="btn-quick" data-target="sell-quantity" data-val="MAX">Max</button>
                                        </div>
                                        <div class="total-cost">Total: $<span id="sell-total">0.00</span></div>
                                        <button id="sell-btn" class="btn btn-sell" disabled>Sell</button>
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- Price Chart -->
                        <div class="panel chart-panel">
                            <h2 class="panel-title">Price History</h2>
                            <div class="chart-container">
                                <canvas id="price-chart"></canvas>
                            </div>
                        </div>
                    </div>
                </div>
            </main>

            <!-- Holdings and Receipts Panels -->
            <section class="container" style="margin-top: 20px;">
                <div class="panel holdings-panel">
                    <h2 class="panel-title">My Holdings</h2>
                    <div id="holdings-container" class="list-container">
                        <div class="loading">Loading holdings...</div>
                    </div>
                </div>
                <div class="panel receipts-panel" style="margin-top: 20px;">
                    <h2 class="panel-title">Recent Receipts</h2>
                    <div id="receipts-container" class="list-container">
                        <div class="loading">Loading receipts...</div>
                    </div>
                </div>
            </section>

            <!-- Notification -->
            <div id="notification" class="notification"></div>

            <script>
                ${getAppJs()}
            </script>
            
            <!-- Google Translate -->
            <script type="text/javascript">
                function googleTranslateElementInit() {
                    new google.translate.TranslateElement({
                        pageLanguage: 'en',
                        includedLanguages: 'en,zh-CN,es,fr,de,ja,ko,pt,ru,it,th,vi,id,tr,pl,nl,sv,da,fi,cs,ro,uk,hi,bn,tl,ar',
                        layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                        autoDisplay: false
                    }, 'google_translate_element');
                }
            </script>
            <script type="text/javascript" src="//translate.google.com/translate_a/element.js?cb=googleTranslateElementInit"></script>
        </body>
        </html>
    """.trimIndent()
    private fun getUnauthorizedHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Access Denied - The Endex</title>
            <style>
                body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #1a1a2e; color: #eee; }
                .error { background: #16213e; padding: 30px; border-radius: 10px; max-width: 500px; margin: 0 auto; }
                h1 { color: #0f3460; }
            </style>
        </head>
        <body>
            <div class="error">
                <h1>Access Denied</h1>
                <p>You need a valid session to access The Endex trading interface.</p>
                <p>Please use the <code>/endex web</code> command in-game to get your personal trading link.</p>
            </div>
        </body>
        </html>
    """.trimIndent()
    
    private fun getStylesCss(): String = """
        :root {
            --color-primary: #a78bfa;
            --color-accent: #7c3aed;
            --bg1: #0f0f23;
            --bg2: #1a1a2e;
            --bg3: #16213e;
        }
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, var(--bg1) 0%, var(--bg2) 50%, var(--bg3) 100%);
            color: #e8e9ea;
            min-height: 100vh;
            overflow-x: hidden;
        }
        
        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 0 20px;
        }
        
        /* Header Styles */
        .header {
            background: rgba(40, 18, 60, 0.95);
            backdrop-filter: blur(10px);
            border-bottom: 1px solid rgba(255, 255, 255, 0.1);
            position: sticky;
            top: 0;
            z-index: 100;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
        }
        
        .header .container {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 15px 20px;
        }
        
        .logo {
            display: flex;
            align-items: center;
            gap: 12px;
            font-size: 28px;
            font-weight: 700;
            color: var(--color-primary);
            text-shadow: 0 0 20px rgba(167, 139, 250, 0.35);
        }
        
        .logo-icon {
            font-size: 32px;
            filter: drop-shadow(0 0 10px rgba(167, 139, 250, 0.6));
            animation: pulse 2s infinite;
        }
        
        @keyframes pulse {
            0%, 100% { transform: scale(1); }
            50% { transform: scale(1.05); }
        }
        
        .header-info {
            display: flex;
            align-items: center;
            gap: 25px;
            font-weight: 500;
        }
        .theme-toggle { font-size: 13px; color: #c4b5fd; user-select: none }
        
        /* Google Translate Widget Styling */
        #google_translate_element {
            display: inline-block !important;
        }
        .goog-te-gadget {
            font-family: inherit !important;
            font-size: 0 !important;
        }
        .goog-te-gadget > span { display: none !important; }
        .goog-te-gadget select,
        .goog-te-combo {
            background: #2d2d3d !important;
            color: #ffffff !important;
            border: 1px solid #a78bfa !important;
            border-radius: 8px !important;
            padding: 8px 12px !important;
            font-size: 13px !important;
            cursor: pointer !important;
            font-family: inherit !important;
            -webkit-appearance: none !important;
            appearance: none !important;
        }
        .goog-te-combo option {
            background: #2d2d3d !important;
            color: #ffffff !important;
            padding: 8px !important;
        }
        .goog-te-gadget-simple { background: transparent !important; border: none !important; }
        .goog-te-gadget-icon { display: none !important; }
        .goog-te-banner-frame.skiptranslate { display: none !important; }
        body { top: 0 !important; }
        
        .balance {
            background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
            padding: 8px 16px;
            border-radius: 20px;
            font-weight: 600;
            box-shadow: 0 4px 15px rgba(167, 139, 250, 0.25);
        }
        
        .player-name {
            color: #a0a0a0;
            font-size: 14px;
        }
        
        /* Main Layout */
        .main {
            padding: 30px 0;
        }
        
        .trading-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            grid-template-rows: auto auto auto;
            gap: 25px;
            grid-template-areas:
                "overview items"
                "trading chart"
                "trading chart";
        }
        
        /* Panel Styles */
        .panel {
            background: rgba(40, 18, 60, 0.8);
            backdrop-filter: blur(15px);
            border-radius: 15px;
            border: 1px solid rgba(255, 255, 255, 0.1);
            padding: 25px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
            transition: all 0.3s ease;
        }
        
        .panel:hover {
            transform: translateY(-5px);
            box-shadow: 0 12px 40px rgba(0, 0, 0, 0.3);
            border-color: rgba(167, 139, 250, 0.3);
        }
        
        .panel-title {
            font-size: 22px;
            font-weight: 600;
            margin-bottom: 20px;
            color: var(--color-primary);
            display: flex;
            align-items: center;
            gap: 10px;
        }
        
        .panel-title::before {
            content: '';
            width: 4px;
            height: 20px;
            background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
            border-radius: 2px;
        }
        
        /* Market Overview */
        .market-overview {
            grid-area: overview;
        }
        
        .market-stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
            gap: 15px;
        }
        
        .stat-card {
            background: rgba(15, 15, 35, 0.5);
            padding: 15px;
            border-radius: 10px;
            border: 1px solid rgba(255, 255, 255, 0.05);
            text-align: center;
            transition: all 0.3s ease;
        }
        
        .stat-card:hover {
            background: rgba(35, 16, 55, 0.8);
            border-color: rgba(167, 139, 250, 0.35);
        }
        
        .stat-label {
            font-size: 12px;
            color: #a0a0a0;
            margin-bottom: 5px;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .stat-value {
            font-size: 18px;
            font-weight: 700;
            color: #c4b5fd;
        }
        
        /* Item List */
        .item-list {
            grid-area: items;
        }
        
        .search-bar { margin-bottom: 16px; display: flex; flex-direction: column; gap: 10px }
        .search-row { display:flex; flex-wrap: wrap; gap: 10px; align-items: center }
        .filters-row { display:grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; align-items: center }
        
        .search-input {
            width: 100%;
            padding: 12px 20px;
            background: rgba(15, 15, 35, 0.5);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 25px;
            color: #e8e9ea;
            font-size: 14px;
            transition: all 0.3s ease;
        }
        .filters-btn { padding:8px 12px; background: rgba(15,15,35,0.5); color:#e8e9ea; border:1px solid rgba(255,255,255,0.1); border-radius:8px; cursor:pointer; }
        .filters-btn.active { border-color:#a78bfa; box-shadow:0 0 10px rgba(167,139,250,0.25) }
        
        .search-input:focus {
            outline: none;
            border-color: #a78bfa;
            box-shadow: 0 0 20px rgba(167, 139, 250, 0.25);
        }
        
        .search-input::placeholder {
            color: #666;
        }
        
        .items-container {
            max-height: 400px;
            overflow-y: auto;
            scrollbar-width: thin;
            scrollbar-color: #a78bfa transparent;
        }
        .watchlist-toggle { font-size: 13px; color: #c4b5fd; user-select: none; }
        .category-header { 
            margin: 8px 8px 0; 
            padding: 6px 10px; 
            font-size: 12px; 
            color: #a78bfa; 
            text-transform: uppercase; 
            letter-spacing: 0.05em; 
            background: rgba(167, 139, 250, 0.08); 
            border-left: 3px solid #7c3aed; 
            border-radius: 4px; 
        }
        
        .items-container::-webkit-scrollbar {
            width: 6px;
        }
        
        .items-container::-webkit-scrollbar-track {
            background: transparent;
        }
        
        .items-container::-webkit-scrollbar-thumb {
            background: var(--color-primary);
            border-radius: 3px;
        }
        
        .item-card {
            background: rgba(15, 15, 35, 0.3);
            border: 1px solid rgba(255, 255, 255, 0.05);
            border-radius: 10px;
            padding: 15px;
            margin-bottom: 10px;
            cursor: pointer;
            transition: all 0.3s ease;
            position: relative;
            overflow: hidden;
        }
    .fav-star { position:absolute; top:10px; right:10px; font-size:16px; color:var(--color-primary); opacity:0.8 }
        .fav-star.filled { color:#fbbf24 }
        
        .item-card::before {
            content: '';
            position: absolute;
            top: 0;
            left: -100%;
            width: 100%;
            height: 2px;
            background: linear-gradient(90deg, transparent, #a78bfa, transparent);
            transition: left 0.5s ease;
        }
        
        .item-card:hover::before {
            left: 100%;
        }
        
        .item-card:hover {
            background: rgba(35, 16, 55, 0.6);
            border-color: rgba(167, 139, 250, 0.35);
            transform: translateX(5px);
        }
        
        .item-card.selected {
            background: rgba(167, 139, 250, 0.1);
            border-color: #a78bfa;
            box-shadow: 0 0 20px rgba(167, 139, 250, 0.25);
        }
        
        .item-name {
            font-weight: 600;
            font-size: 16px;
            margin-bottom: 5px;
            color: #fff;
        }
        
        .item-price {
            font-size: 18px;
            font-weight: 700;
            color: #c4b5fd;
            margin-bottom: 5px;
        }
        
        .item-change {
            font-size: 12px;
            font-weight: 500;
        }
        
        .item-change.positive {
            color: #4ade80;
        }
        
        .item-change.negative {
            color: #f87171;
        }
        
        .item-change.neutral {
            color: #a0a0a0;
        }
        
        /* Trading Panel */
        .trading-panel {
            grid-area: trading;
        }
        
        .trading-content {
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        
        .no-selection {
            text-align: center;
            padding: 40px;
            color: #666;
        }
        
        .selection-icon {
            font-size: 48px;
            display: block;
            margin-bottom: 15px;
            opacity: 0.5;
        }
        
        .selected-item-info {
            background: rgba(15, 15, 35, 0.5);
            padding: 20px;
            border-radius: 10px;
            border: 1px solid rgba(255, 255, 255, 0.1);
        }
        
        .selected-item-name {
            font-size: 20px;
            font-weight: 600;
            color: #fff;
            margin-bottom: 10px;
        }
        
        .selected-item-price {
            font-size: 24px;
            font-weight: 700;
            color: #c4b5fd;
            margin-bottom: 15px;
        }
        
        .item-details {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
            gap: 15px;
            margin-top: 15px;
        }
        
        .detail-item {
            text-align: center;
        }
        
        .detail-label {
            font-size: 11px;
            color: #a0a0a0;
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 5px;
        }
        
        .detail-value {
            font-weight: 600;
            color: #fff;
        }
        
        .trade-forms {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
        }
        
        .trade-form {
            background: rgba(15, 15, 35, 0.5);
            padding: 20px;
            border-radius: 10px;
            border: 1px solid rgba(255, 255, 255, 0.1);
        }
        
        .trade-form h3 {
            margin-bottom: 15px;
            font-size: 18px;
            font-weight: 600;
        }
        
        .buy-form {
            border-left: 3px solid #4ade80;
        }
        
        .buy-form h3 {
            color: #4ade80;
        }
        
        .sell-form {
            border-left: 3px solid #f87171;
        }
        
        .sell-form h3 {
            color: #f87171;
        }
        
        .quantity-input {
            width: 100%;
            padding: 12px 15px;
            background: rgba(0, 0, 0, 0.3);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 8px;
            color: #fff;
            font-size: 16px;
            margin-bottom: 15px;
            transition: all 0.3s ease;
        }
        .quick-buttons { display:flex; gap:8px; margin-bottom:10px }
        .btn-quick {
            padding: 6px 10px; border:1px solid rgba(255,255,255,0.1); background: rgba(15,15,35,0.5);
            color:#e8e9ea; border-radius: 6px; cursor:pointer; font-size: 12px;
        }
        .btn-quick:hover { border-color:#a78bfa; box-shadow: 0 0 10px rgba(167,139,250,0.25) }
        
        .quantity-input:focus {
            outline: none;
            border-color: #a78bfa;
            box-shadow: 0 0 15px rgba(167, 139, 250, 0.25);
        }
        
        .btn {
            width: 100%;
            padding: 12px 20px;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.3s ease;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .btn:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .btn-buy {
            background: linear-gradient(135deg, #4ade80, #16a34a);
            color: white;
        }
        
        .btn-buy:hover:not(:disabled) {
            background: linear-gradient(135deg, #16a34a, #15803d);
            transform: translateY(-2px);
            box-shadow: 0 8px 25px rgba(74, 222, 128, 0.3);
        }
        
        .btn-sell {
            background: linear-gradient(135deg, #f87171, #dc2626);
            color: white;
        }
        
        .btn-sell:hover:not(:disabled) {
            background: linear-gradient(135deg, #dc2626, #b91c1c);
            transform: translateY(-2px);
            box-shadow: 0 8px 25px rgba(248, 113, 113, 0.3);
        }
        
        /* Chart Panel */
        .chart-panel {
            grid-area: chart;
        }
        
        .chart-container {
            position: relative;
            height: 400px;
            background: rgba(15, 15, 35, 0.5);
            border-radius: 10px;
            padding: 15px;
        }
        
        /* Loading States */
        .loading {
            text-align: center;
            padding: 40px;
            color: #666;
            font-size: 16px;
        }
        
        .loading::after {
            content: '';
            display: inline-block;
            width: 20px;
            height: 20px;
            border: 2px solid #666;
            border-radius: 50%;
            border-top-color: #00d2ff;
            animation: spin 1s ease-in-out infinite;
            margin-left: 10px;
        }
        
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        
        /* Notification */
        .notification {
            position: fixed;
            top: 20px;
            right: 20px;
            padding: 15px 25px;
            border-radius: 10px;
            font-weight: 600;
            z-index: 1000;
            transform: translateX(100%);
            transition: transform 0.3s ease;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
        }
        
        .notification.show {
            transform: translateX(0);
        }
        
        .notification.success {
            background: linear-gradient(135deg, #4ade80, #16a34a);
            color: white;
        }
        
        .notification.error {
            background: linear-gradient(135deg, #f87171, #dc2626);
            color: white;
        }
        
        .notification.info {
            background: linear-gradient(135deg, #a78bfa, #7c3aed);
            color: white;
        }
        
        /* Responsive Design */
        @media (max-width: 1200px) {
            .trading-grid {
                grid-template-columns: 1fr;
                grid-template-areas:
                    "overview"
                    "items"
                    "trading"
                    "chart";
            }
            
            .trade-forms {
                grid-template-columns: 1fr;
            }
        }
        
        @media (max-width: 768px) {
            .header .container {
                flex-direction: column;
                gap: 15px;
                text-align: center;
            }
            
            .header-info {
                flex-direction: column;
                gap: 10px;
            }
            
            .logo {
                font-size: 24px;
            }
            
            .container {
                padding: 0 15px;
            }
            
            .panel {
                padding: 20px;
            }
            
            .market-stats {
                grid-template-columns: 1fr 1fr;
            }
            
            .chart-container {
                height: 300px;
            }
        }
        
        /* Custom Scrollbar */
        ::-webkit-scrollbar {
            width: 8px;
        }
        
        ::-webkit-scrollbar-track {
            background: rgba(15, 15, 35, 0.5);
        }
        
        ::-webkit-scrollbar-thumb {
            background: linear-gradient(135deg, var(--color-primary), var(--color-accent));
            border-radius: 4px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: linear-gradient(135deg, var(--color-accent), var(--color-primary));
        }

        /* Item visuals */
        .item-header { display: flex; align-items: center; gap: 12px; }
        .item-icon {
            width: 36px; height: 36px; border-radius: 8px;
            background: linear-gradient(135deg, var(--color-accent), var(--color-primary));
            color: #fff; font-size: 11px; font-weight: 800;
            display: flex; align-items: center; justify-content: center;
            box-shadow: 0 6px 16px rgba(124, 58, 237, 0.35);
        }
        .item-icon-img { width:36px; height:36px; border-radius:8px; object-fit:cover; box-shadow: 0 6px 16px rgba(124,58,237,0.35); }
    .filter-select, .price-input { padding:8px 10px; background: rgba(15,15,35,0.5); border:1px solid rgba(255,255,255,0.1); border-radius:6px; color:#e8e9ea; font-size:12px }
        /* Holdings / Receipts */
        .list-container { max-height: 260px; overflow-y:auto }
    .row { display:flex; justify-content: space-between; padding:10px; border-bottom:1px solid rgba(255,255,255,0.06) }
        .row .left { display:flex; gap:10px; align-items:center }
        .row .right { color:#c4b5fd; font-weight:600; display:flex; align-items:center; gap:8px }
    .badge { font-size:11px; padding:2px 6px; border-radius:10px; margin-left:6px; background: rgba(167,139,250,0.15); color:#c4b5fd; border:1px solid rgba(167,139,250,0.25) }
    .badge-holdings { background: rgba(74,222,128,0.2); color:#4ade80; border-color: rgba(74,222,128,0.3) }
    .holdings-header { display:flex; justify-content:flex-end; padding:10px; border-bottom:1px solid rgba(255,255,255,0.1) }
    .btn-withdraw { background: linear-gradient(135deg, #4ade80 0%, #22c55e 100%); color:#fff; border:none; padding:4px 8px; border-radius:6px; cursor:pointer; font-size:12px; transition: all 0.2s }
    .btn-withdraw:hover { transform:scale(1.1); box-shadow: 0 2px 8px rgba(74,222,128,0.3) }
    .btn-withdraw-all { background: linear-gradient(135deg, #a78bfa 0%, #8b5cf6 100%); color:#fff; border:none; padding:8px 16px; border-radius:8px; cursor:pointer; font-size:13px; font-weight:600; transition: all 0.2s }
    .btn-withdraw-all:hover { transform:scale(1.05); box-shadow: 0 4px 12px rgba(167,139,250,0.4) }
    """.trimIndent()
    
    
    private fun getAppJs(): String = """
        class EndexTrading {
            constructor() {
                this.sessionToken = new URLSearchParams(window.location.search).get('session');
                this.selectedItem = null;
                this.items = [];
                this.chart = null;
                this.holdings = new Map();
                this.playerBalance = 0;
                this.pollMs = 1000;
                this.historyLimit = 120;
                this.lastHistoryTs = null; // last timestamp we've seen for selected item
                this.sseEnabled = false;
                this.wsEnabled = false;
                this.sse = null;
                this.ws = null;
                this.pollTimer = null;
                this.iconsEnabled = false;
                // filter/sort state
                this.filterCategory = 'ALL';
                this.filterMinPrice = null;
                this.filterMaxPrice = null;
                this.filterTrend = 'any';
                this.sortBy = 'name';
                this.sortDesc = false;
                this.marketStats = {
                    totalItems: 0,
                    avgPrice: 0,
                    totalVolume: 0,
                    topGainer: null,
                    topLoser: null
                };
                this.presets = {
                    default: null, // filled from server theme on session load
                    darker: { primary: '#8b5cf6', accent: '#5b21b6', bg1: '#070712', bg2: '#0e0e1a', bg3: '#101420' }
                };
                
                this.init();
            }
            
            async init() {
                if (!this.sessionToken) {
                    this.showNotification('No session token found', 'error');
                    return;
                }
                
                this.showLoading('Initializing trading interface...');
                
                await this.loadSession();
                await this.loadWatchlist();
                await this.loadItems();
                await this.loadBalance();
                await this.loadHoldings();
                await this.loadReceipts();
                this.updateMarketStats();
                this.setupEventListeners();
                this.initChart();
                this.hideLoading();
                if (this.wsEnabled) this.startWebSocket();
                else if (this.sseEnabled) this.startSse();
                else this.startPolling();
            }

            startPolling() {
                if (this.pollTimer) return;
                const step = () => {
                    this.loadItems();
                    setTimeout(() => this.loadBalance(), Math.floor(this.pollMs * 0.25));
                    setTimeout(() => this.loadHoldings(), Math.floor(this.pollMs * 0.5));
                    setTimeout(() => this.loadReceipts(), Math.floor(this.pollMs * 0.75));
                    setTimeout(() => {
                        this.updateMarketStats();
                        if (this.selectedItem) this.loadHistory(this.selectedItem.material, true);
                    }, Math.floor(this.pollMs * 0.85));
                };
                step();
                this.pollTimer = setInterval(step, this.pollMs);
            }
            stopPolling() {
                if (this.pollTimer) {
                    clearInterval(this.pollTimer);
                    this.pollTimer = null;
                }
            }
            stopSse() {
                try { if (this.sse) { this.sse.close(); } } catch(_) {}
                this.sse = null;
            }
            stopWs() {
                try { if (this.ws) { this.ws.close(); } } catch(_) {}
                this.ws = null;
            }
            stopLiveFeeds() {
                this.stopPolling();
                this.stopSse();
                this.stopWs();
            }
            
            async loadSession() {
                try {
                    const response = await fetch(`/api/session`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                    const data = await response.json();
                    if (response.ok) {
                        document.getElementById('player-name').textContent = data.player;
                        if (data.pollMs) this.pollMs = data.pollMs;
                        if (data.historyLimit) this.historyLimit = data.historyLimit;
                        this.sseEnabled = !!data.sseEnabled;
                        this.wsEnabled = !!data.wsEnabled;
                        this.iconsEnabled = !!data.iconsEnabled;
                        this.invHoldingsEnabled = !!data.invHoldingsEnabled;
                        // branding
                        if (data.brand) {
                            const bn = document.getElementById('brand-name');
                            const bl = document.getElementById('brand-logo');
                            if (bn && data.brand.name) bn.textContent = data.brand.name;
                            if (bl && data.brand.logoText) bl.textContent = data.brand.logoText;
                        }
                        // theme
                        if (data.theme) {
                            this.presets.default = { ...data.theme };
                            const r = document.documentElement;
                            if (data.theme.primary) r.style.setProperty('--color-primary', data.theme.primary);
                            if (data.theme.accent) r.style.setProperty('--color-accent', data.theme.accent);
                            if (data.theme.bg1) r.style.setProperty('--bg1', data.theme.bg1);
                            if (data.theme.bg2) r.style.setProperty('--bg2', data.theme.bg2);
                            if (data.theme.bg3) r.style.setProperty('--bg3', data.theme.bg3);
                        }
                        // role
                        this.role = data.role || 'TRADER';
                        // apply persisted toggle
                        const saved = localStorage.getItem('endex.theme') || 'default';
                        const chk = document.getElementById('theme-toggle');
                        if (chk) { chk.checked = (saved === 'darker'); }
                        this.applyTheme(saved);

                        // grouping preference
                        const savedGroup = localStorage.getItem('endex.groupByCategory');
                        const gbc = document.getElementById('group-by-category');
                        if (gbc) {
                            const pref = (savedGroup === null) ? true : (savedGroup === '1');
                            gbc.checked = pref;
                        }
                    } else {
                        this.showNotification(data.error || 'Session error', 'error');
                    }
                } catch (error) {
                    this.showNotification('Failed to load session', 'error');
                }
            }

            async loadWatchlist() {
                try {
                    const r = await fetch(`/api/watchlist`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                    const list = await r.json();
                    this.watchlist = Array.isArray(list) ? new Set(list) : new Set();
                } catch(_) {
                    this.watchlist = new Set();
                }
            }

            async loadItems() {
                try {
                    // Fetch market items and crypto ticker in parallel; crypto may fail independently
                    const [response, cryptoTicker] = await Promise.all([
                        fetch(`/api/items`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } }),
                        this.loadCryptoPseudoItem()
                    ]);
                    const items = await response.json();
                    if (response.ok) {
                        this.items = Array.isArray(items) ? items.slice() : [];
                        if (cryptoTicker) this.items.push(cryptoTicker);
                        this.populateCategories();
                        this.renderItems();
                        if (this.selectedItem) {
                            const currentMat = this.selectedItem.material;
                            this.selectedItem = this.items.find(i => i.material === currentMat) || null;
                        }
                    } else {
                        this.showNotification('Failed to load items', 'error');
                    }
                } catch (error) {
                    this.showNotification('Failed to load items', 'error');
                }
            }

            async loadCryptoPseudoItem() {
                try {
                    const r = await fetch(`/api/crypto/tickers`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                    if (!r.ok) return null;
                    const arr = await r.json();
                    if (!Array.isArray(arr) || arr.length === 0) return null;
                    const t = arr[0] || {};
                    const price = parseFloat(t.price || '0');
                    const symbol = (t.symbol || 'CRYPTO').toUpperCase();
                    const name = t.name || symbol;
                    return {
                        material: 'CRYPTO',
                        displayName: `${'$'}{name}`,
                        category: 'Addons',
                        basePrice: price || 0,
                        currentPrice: price || 0,
                        effectivePrice: price || 0,
                        multiplier: 1.0,
                        volume: 0
                    };
                } catch(_) { return null; }
            }

            async refreshCryptoTicker() {
                try {
                    const item = await this.loadCryptoPseudoItem();
                    if (!item) return;
                    const idx = this.items.findIndex(i => i.material === 'CRYPTO');
                    if (idx >= 0) this.items[idx] = item; else this.items.push(item);
                    this.renderItems();
                    if (this.selectedItem && this.selectedItem.material === 'CRYPTO') {
                        this.selectedItem = item;
                        this.updateSelectedItemInfo();
                        this.updateTradeTotals();
                        this.updateTradingButtons();
                    }
                } catch(_) {}
            }

            // Addons UI removed
            
            async loadHistory(material, incremental = false) {
                try {
                    // Handle CRYPTO pseudo-item via its own endpoint
                    if (material === 'CRYPTO') {
                        const r = await fetch(`/api/crypto/history`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                        const data = await r.json();
                        if (r.ok && Array.isArray(data)) {
                            this.selectedItemHistory = data;
                            this.lastHistoryTs = (data.length > 0) ? data[data.length - 1].timestamp : null;
                            if (this.selectedItemHistory.length > this.historyLimit) {
                                this.selectedItemHistory.splice(0, this.selectedItemHistory.length - this.historyLimit);
                            }
                            this.updateChart();
                        }
                        return;
                    }

                    let url = `/api/history?session=${'$'}{this.sessionToken}&material=${'$'}{encodeURIComponent(material)}&limit=${'$'}{this.historyLimit}`;
                    if (incremental && this.lastHistoryTs) {
                        url += `&since=${'$'}{this.lastHistoryTs}`;
                    }
                    const response = await fetch(url, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                    const data = await response.json();
                    if (response.ok) {
                        if (incremental && data && Array.isArray(data.points)) {
                            // Merge deltas
                            this.selectedItemHistory = (this.selectedItemHistory || []).concat(data.points);
                            this.lastHistoryTs = data.latest || this.lastHistoryTs;
                        } else {
                            this.selectedItemHistory = data;
                            // Update last seen ts
                            if (Array.isArray(data) && data.length > 0) {
                                this.lastHistoryTs = data[data.length - 1].timestamp;
                            } else if (data && data.latest) {
                                this.lastHistoryTs = data.latest;
                            } else {
                                this.lastHistoryTs = null;
                            }
                        }
                        // Cap client-side to historyLimit
                        if (Array.isArray(this.selectedItemHistory)) {
                            const excess = this.selectedItemHistory.length - this.historyLimit;
                            if (excess > 0) {
                                this.selectedItemHistory.splice(0, excess);
                            }
                        }
                        this.updateChart();
                    }
                } catch (_) { /* ignore */ }
            }

            populateCategories() {
                try {
                    const sel = document.getElementById('filter-category');
                    if (!sel) return;
                    const cats = Array.from(new Set(this.items.map(i => i.category || 'Other'))).sort((a,b)=>a.localeCompare(b));
                    const current = sel.value || 'ALL';
                    sel.innerHTML = '<option value="ALL">All categories</option>' + cats.map(c => `<option value="${'$'}{c}">${'$'}{c}</option>`).join('');
                    if (Array.from(sel.options).some(o => o.value === current)) sel.value = current; else sel.value = 'ALL';
                } catch(_) {}
            }

            startSse() {
                try {
                    this.sse = new EventSource(`/api/sse?session=${'$'}{this.sessionToken}`);
                    this.sse.addEventListener('tick', (ev) => {
                        try {
                            const payload = JSON.parse(ev.data);
                            if (payload && Array.isArray(payload.items)) {
                                // Update local prices for items in snapshot
                                const map = new Map(payload.items.map(i => [i.material, i.price]));
                                this.items.forEach(it => { if (map.has(it.material)) it.effectivePrice = map.get(it.material); });
                                this.renderItems();
                                this.updateMarketStats();
                                if (this.selectedItem) {
                                    // pull incremental history
                                    this.loadHistory(this.selectedItem.material, true);
                                }
                                // Refresh crypto ticker alongside SSE item tick
                                this.refreshCryptoTicker();
                            }
                        } catch (e) {}
                    });
                    this.sse.addEventListener('error', () => {
                        // fallback to polling if SSE fails
                        if (this.sse) { this.sse.close(); this.sse = null; }
                        this.sseEnabled = false;
                        this.startPolling();
                    });
                } catch (_) {
                    this.startPolling();
                }
            }

            startWebSocket() {
                const proto = (location.protocol === 'https:') ? 'wss' : 'ws';
                const url = `${'$'}{proto}://${'$'}{location.host}/api/ws?session=${'$'}{this.sessionToken}`;
                try {
                    this.ws = new WebSocket(url);
                    this.ws.onmessage = (ev) => {
                        try {
                            const payload = JSON.parse(ev.data);
                            if (payload && payload.type === 'tick' && Array.isArray(payload.items)) {
                                const map = new Map(payload.items.map(i => [i.material, i.price]));
                                this.items.forEach(it => { if (map.has(it.material)) it.effectivePrice = map.get(it.material); });
                                this.renderItems();
                                this.updateMarketStats();
                                if (this.selectedItem) {
                                    this.loadHistory(this.selectedItem.material, true);
                                }
                                // Refresh crypto ticker alongside WS item tick
                                this.refreshCryptoTicker();
                            }
                        } catch(_) {}
                    };
                    this.ws.onclose = () => {
                        // fallback to SSE or polling
                        this.ws = null;
                        if (this.sseEnabled) this.startSse(); else { this.startPolling(); }
                    };
                    this.ws.onerror = () => { try { this.ws.close(); } catch(_) {} };
                } catch(_) {
                    if (this.sseEnabled) this.startSse(); else this.startPolling();
                }
            }
            
            async loadBalance() {
                try {
                    const response = await fetch(`/api/balance`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } });
                    const data = await response.json();
                    
                    if (response.ok) {
                        this.playerBalance = data.balance;
                        // '$' is already in the HTML before the span; only set the numeric value here
                        document.getElementById('balance').textContent = data.balance.toFixed(2);
                        this.updateTradingButtons();
                    }
                } catch (error) {
                    console.error('Failed to load balance:', error);
                }
            }
            
            renderItems() {
                const container = document.getElementById('items-container');
                const search = document.getElementById('search-input').value.toLowerCase();
                const favOnly = document.getElementById('favorites-only').checked;
                const groupBy = document.getElementById('group-by-category')?.checked || false;
                const catSel = document.getElementById('filter-category');
                const minP = parseFloat(document.getElementById('filter-min-price')?.value || '');
                const maxP = parseFloat(document.getElementById('filter-max-price')?.value || '');
                const trend = (document.getElementById('filter-trend')?.value || 'any');
                const sortBy = (document.getElementById('sort-by')?.value || 'name');
                const sortDesc = !!document.getElementById('sort-desc')?.checked;

                // Filter
                let filteredItems = this.items.filter(item =>
                    item.displayName.toLowerCase().includes(search) ||
                    item.material.toLowerCase().includes(search)
                ).filter(item => !favOnly || this.watchlist.has(item.material));

                // Category filter
                const category = (catSel && catSel.value) ? catSel.value : 'ALL';
                if (category !== 'ALL') filteredItems = filteredItems.filter(i => (i.category || 'Other') === category);
                // Price range
                if (!isNaN(minP)) filteredItems = filteredItems.filter(i => (i.effectivePrice || 0) >= minP);
                if (!isNaN(maxP)) filteredItems = filteredItems.filter(i => (i.effectivePrice || 0) <= maxP);
                // Trend filter
                filteredItems = filteredItems.filter(i => {
                    const ch = (i.basePrice > 0) ? ((i.effectivePrice - i.basePrice) / i.basePrice * 100) : 0;
                    if (trend === 'up') return ch > 0; if (trend === 'down') return ch < 0; return true;
                });

                if (!filteredItems || filteredItems.length === 0) {
                    container.innerHTML = '<div class="loading">' + this.tr('no_items_match', 'No items match your search') + '</div>';
                    return;
                }

                // Sort
                filteredItems = filteredItems.slice().sort((a, b) => {
                    if (sortBy === 'name') return a.displayName.localeCompare(b.displayName);
                    if (sortBy === 'price') return (a.effectivePrice - b.effectivePrice);
                    if (sortBy === 'change') {
                        const ca = (a.basePrice>0)?((a.effectivePrice-a.basePrice)/a.basePrice*100):0;
                        const cb = (b.basePrice>0)?((b.effectivePrice-b.basePrice)/b.basePrice*100):0;
                        return ca - cb;
                    }
                    if (sortBy === 'volume') return (a.volume||0) - (b.volume||0);
                    return 0;
                });
                if (sortDesc) filteredItems.reverse();

                const renderCard = (item) => {
                    const priceChange = ((item.effectivePrice - item.basePrice) / item.basePrice * 100);
                    const changeClass = priceChange > 0 ? 'positive' : priceChange < 0 ? 'negative' : 'neutral';
                    const changeSymbol = priceChange > 0 ? '▲' : priceChange < 0 ? '▼' : '●';
                    const initials = item.displayName.split(' ').map(w => w[0]).slice(0, 2).join('').toUpperCase();
                    const starred = this.watchlist.has(item.material);
                    const iconHtml = this.iconsEnabled
                        ? `<img class="item-icon-img" src="/icon/${'$'}{item.material}" onerror="this.outerHTML='<div class\\'item-icon\\'>${'$'}{initials}</div>'"/>`
                        : `<div class="item-icon">${'$'}{initials}</div>`;
                    const isSelected = this.selectedItem && this.selectedItem.material === item.material;
                    return `
                        <div class="item-card ${'$'}{isSelected ? 'selected' : ''}" data-material="${'$'}{item.material}">
                            <div class="fav-star ${'$'}{starred ? 'filled' : ''}" data-star="${'$'}{item.material}">${'$'}{starred ? '★' : '☆'}</div>
                            <div class="item-header">
                                ${'$'}{iconHtml}
                                <div>
                                    <div class="item-name">${'$'}{item.displayName}</div>
                                    <div class="item-price">${'$'}${'$'}{item.effectivePrice.toFixed(2)}</div>
                                </div>
                            </div>
                            <div class="item-change ${'$'}{changeClass}">
                                ${'$'}{changeSymbol} ${'$'}{Math.abs(priceChange).toFixed(1)}%
                            </div>
                        </div>
                    `;
                };

                if (groupBy) {
                    const groups = new Map();
                    filteredItems.forEach(it => {
                        const cat = it.category || 'Other';
                        if (!groups.has(cat)) groups.set(cat, []);
                        groups.get(cat).push(it);
                    });
                    const categories = Array.from(groups.keys()).sort((a, b) => a.localeCompare(b));
                    container.innerHTML = categories.map(cat => {
                        const itemsHtml = groups.get(cat).map(renderCard).join('');
                        return `
                            <div class="category-section">
                                <div class="category-header">${'$'}{cat}</div>
                                <div class="category-items">${'$'}{itemsHtml}</div>
                            </div>
                        `;
                    }).join('');
                } else {
                    container.innerHTML = filteredItems.map(renderCard).join('');
                }
            }
            
            setupEventListeners() {
                // Search functionality with debounce
                let searchTimeout;
                document.getElementById('search-input').addEventListener('input', () => {
                    clearTimeout(searchTimeout);
                    searchTimeout = setTimeout(() => {
                        this.renderItems();
                    }, 300);
                });
                
                // Item selection
                document.getElementById('items-container').addEventListener('click', (e) => {
                    // Watchlist toggle
                    const star = e.target.closest('[data-star]');
                    if (star) {
                        const mat = star.getAttribute('data-star');
                        const isFav = this.watchlist.has(mat);
                        (isFav ? this.removeFromWatchlist(mat) : this.addToWatchlist(mat)).then(() => this.renderItems());
                        e.stopPropagation();
                        return;
                    }
                    const card = e.target.closest('.item-card');
                    if (card) {
                        this.selectItem(card.dataset.material);
                    }
                });
                
                // Favorites-only toggle
                document.getElementById('favorites-only').addEventListener('change', () => this.renderItems());
                // Filters toggle
                const filtersBtn = document.getElementById('toggle-filters');
                const filtersRow = document.getElementById('filters-row');
                try {
                    const open = localStorage.getItem('endex.filters.open') === '1';
                    if (open) { filtersRow.style.display = 'grid'; filtersBtn.classList.add('active'); filtersBtn.textContent = 'Filters ▾'; }
                } catch(_) {}
                if (filtersBtn && filtersRow) {
                    filtersBtn.addEventListener('click', () => {
                        const showing = filtersRow.style.display !== 'none';
                        if (showing) {
                            filtersRow.style.display = 'none';
                            filtersBtn.classList.remove('active');
                            filtersBtn.textContent = 'Filters ▸';
                            try { localStorage.setItem('endex.filters.open', '0'); } catch(_) {}
                        } else {
                            filtersRow.style.display = 'grid';
                            filtersBtn.classList.add('active');
                            filtersBtn.textContent = 'Filters ▾';
                            try { localStorage.setItem('endex.filters.open', '1'); } catch(_) {}
                        }
                    });
                }
                // Populate filters & wire change handlers
                this.populateCategories();
                const refilter = () => this.renderItems();
                const ids = ['filter-category','filter-min-price','filter-max-price','filter-trend','sort-by','sort-desc'];
                ids.forEach(id => {
                    const el = document.getElementById(id);
                    if (!el) return;
                    el.addEventListener('change', refilter);
                    if (id.includes('price')) el.addEventListener('input', () => { clearTimeout(searchTimeout); searchTimeout = setTimeout(refilter, 300); });
                });
                // Grouping toggle
                const gbc = document.getElementById('group-by-category');
                if (gbc) {
                    gbc.addEventListener('change', () => {
                        localStorage.setItem('endex.groupByCategory', gbc.checked ? '1' : '0');
                        this.renderItems();
                    });
                }

                // Trade amount changes with real-time updates
                ['buy-quantity', 'sell-quantity'].forEach(id => {
                    const input = document.getElementById(id);
                    if (input) {
                        input.addEventListener('input', () => {
                            this.updateTradeTotals();
                            this.updateTradingButtons();
                        });
                    }
                });
                // Quick buttons
                document.querySelectorAll('.btn-quick').forEach(btn => {
                    btn.addEventListener('click', (ev) => {
                        const target = btn.getAttribute('data-target');
                        const val = btn.getAttribute('data-val');
                        const input = document.getElementById(target);
                        if (!input) return;
                        if (val === 'MAX') {
                            if (!this.selectedItem) return;
                            if (target === 'buy-quantity') {
                                const max = Math.floor((this.playerBalance || 0) / (this.selectedItem.effectivePrice || 1));
                                input.value = Math.max(0, max);
                            } else {
                                const h = this.holdings.get(this.selectedItem.material);
                                input.value = h ? (h.quantity || 0) : 0;
                            }
                        } else {
                            input.value = parseInt(val, 10);
                        }
                        this.updateTradeTotals();
                        this.updateTradingButtons();
                    });
                });
                
                // Trade buttons
                const buyBtn = document.getElementById('buy-btn');
                const sellBtn = document.getElementById('sell-btn');
                
                if (buyBtn) {
                    buyBtn.addEventListener('click', () => {
                        this.executeTrade('buy');
                    });
                }
                
                if (sellBtn) {
                    sellBtn.addEventListener('click', () => {
                        this.executeTrade('sell');
                    });
                }
                // Hide trading actions if viewer
                if (this.role === 'VIEWER') {
                    document.querySelectorAll('.trade-form .btn').forEach(b => b.setAttribute('disabled','true'));
                    document.querySelectorAll('.trade-form input').forEach(i => i.setAttribute('disabled','true'));
                }
                // Theme toggle
                const toggle = document.getElementById('theme-toggle');
                if (toggle) {
                    toggle.addEventListener('change', () => {
                        const mode = toggle.checked ? 'darker' : 'default';
                        this.applyTheme(mode);
                        localStorage.setItem('endex.theme', mode);
                    });
                }
            }

            applyTheme(mode) {
                const r = document.documentElement;
                const t = (mode === 'darker') ? this.presets.darker : (this.presets.default || {});
                if (!t) return;
                if (t.primary) r.style.setProperty('--color-primary', t.primary);
                if (t.accent) r.style.setProperty('--color-accent', t.accent);
                if (t.bg1) r.style.setProperty('--bg1', t.bg1);
                if (t.bg2) r.style.setProperty('--bg2', t.bg2);
                if (t.bg3) r.style.setProperty('--bg3', t.bg3);
            }
            
            selectItem(material) {
                this.selectedItem = this.items.find(item => item.material === material);
                
                if (!this.selectedItem) return;
                this.lastHistoryTs = null; // reset incremental baseline when switching items
                
                // Update UI
                document.querySelectorAll('.item-card').forEach(card => {
                    card.classList.remove('selected');
                });
                const sel = document.querySelector(`[data-material="${'$'}{material}"]`);
                if (sel) sel.classList.add('selected');
                
                // Show trading panel and hide no-selection message
                const tradingContent = document.querySelector('.trading-content');
                const noSelection = document.querySelector('.no-selection');
                
                if (noSelection) noSelection.style.display = 'none';
                if (tradingContent) tradingContent.style.display = 'block';
                
                // Update selected item info
                this.updateSelectedItemInfo();
                this.updateTradeTotals();
                this.updateTradingButtons();
                this.loadHistory(this.selectedItem.material, false);
            }
            
            updateSelectedItemInfo() {
                if (!this.selectedItem) return;
                
                const priceChange = ((this.selectedItem.effectivePrice - this.selectedItem.basePrice) / this.selectedItem.basePrice * 100);
                const changeClass = priceChange > 0 ? 'positive' : priceChange < 0 ? 'negative' : 'neutral';
                const changeSymbol = priceChange > 0 ? '▲' : priceChange < 0 ? '▼' : '●';
                
                // Update selected item info panel
                const nameEl = document.querySelector('.selected-item-name');
                const priceEl = document.querySelector('.selected-item-price');
                
                if (nameEl) nameEl.textContent = this.selectedItem.displayName;
                if (priceEl) priceEl.textContent = `${'$'}${'$'}{this.selectedItem.effectivePrice.toFixed(2)}`;
                
                // Update detail values
                const detailElements = {
                    'base-price': this.selectedItem.basePrice.toFixed(2),
                    'multiplier': this.selectedItem.multiplier.toFixed(2) + 'x',
                    'change': `${'$'}{changeSymbol} ${'$'}{Math.abs(priceChange).toFixed(1)}%`,
                    'volume': this.selectedItem.volume || '0'
                };
                
                Object.entries(detailElements).forEach(([id, value]) => {
                    const element = document.getElementById(id);
                    if (element) element.textContent = value;
                });
                // Update selected placeholders
                const buySel = document.getElementById('buy-selected');
                const sellSel = document.getElementById('sell-selected');
                if (buySel) buySel.textContent = this.selectedItem.displayName;
                if (sellSel) sellSel.textContent = this.selectedItem.displayName;
            }
            
            updateTradeTotals() {
                if (!this.selectedItem) return;
                
                const buyQuantity = parseInt(document.getElementById('buy-quantity')?.value || '0');
                const sellQuantity = parseInt(document.getElementById('sell-quantity')?.value || '0');
                
                const buyTotal = buyQuantity * this.selectedItem.effectivePrice;
                const sellTotal = sellQuantity * this.selectedItem.effectivePrice;
                
                const buyTotalEl = document.getElementById('buy-total');
                const sellTotalEl = document.getElementById('sell-total');
                
                // '$' is already present in the HTML before these spans; set numeric values only
                if (buyTotalEl) buyTotalEl.textContent = buyTotal.toFixed(2);
                if (sellTotalEl) sellTotalEl.textContent = sellTotal.toFixed(2);
            }
            
            updateTradingButtons() {
                if (!this.selectedItem) return;
                
                const buyBtn = document.getElementById('buy-btn');
                const sellBtn = document.getElementById('sell-btn');
                const buyQuantity = parseInt(document.getElementById('buy-quantity')?.value || '0');
                const sellQuantity = parseInt(document.getElementById('sell-quantity')?.value || '0');
                
                // Update buy button
                if (buyBtn) {
                    const canAfford = (buyQuantity * this.selectedItem.effectivePrice) <= this.playerBalance;
                    buyBtn.disabled = !canAfford || buyQuantity <= 0;
                    buyBtn.title = !canAfford ? 'Insufficient funds' : '';
                }
                
                // Update sell button
                if (sellBtn) {
                    const h = this.holdings.get(this.selectedItem.material);
                    const canSell = h ? (sellQuantity <= (h.quantity || 0)) : false;
                    sellBtn.disabled = sellQuantity <= 0 || !canSell;
                    sellBtn.title = !canSell ? 'Not enough in holdings' : '';
                }
            }
            
            updateMarketStats() {
                if (this.items.length === 0) return;
                
                // Calculate market statistics
                const prices = this.items.map(item => item.effectivePrice);
                const changes = this.items.map(item => 
                    ((item.effectivePrice - item.basePrice) / item.basePrice * 100)
                );
                
                this.marketStats = {
                    totalItems: this.items.length,
                    avgPrice: prices.reduce((a, b) => a + b, 0) / prices.length,
                    totalVolume: this.items.reduce((sum, item) => sum + (item.volume || 0), 0),
                    topGainer: this.items.reduce((max, item) => {
                        const change = ((item.effectivePrice - item.basePrice) / item.basePrice * 100);
                        const maxChange = ((max.effectivePrice - max.basePrice) / max.basePrice * 100);
                        return change > maxChange ? item : max;
                    }),
                    topLoser: this.items.reduce((min, item) => {
                        const change = ((item.effectivePrice - item.basePrice) / item.basePrice * 100);
                        const minChange = ((min.effectivePrice - min.basePrice) / min.basePrice * 100);
                        return change < minChange ? item : min;
                    })
                };
                
                // Update UI
                this.renderMarketStats();
            }
            
            renderMarketStats() {
                const stats = this.marketStats;
                const gainerChange = ((stats.topGainer.effectivePrice - stats.topGainer.basePrice) / stats.topGainer.basePrice * 100);
                const loserChange = ((stats.topLoser.effectivePrice - stats.topLoser.basePrice) / stats.topLoser.basePrice * 100);
                
                const statsElements = {
                    'total-items': stats.totalItems.toString(),
                    'avg-price': `${'$'}${'$'}{stats.avgPrice.toFixed(2)}`,
                    'total-volume': stats.totalVolume.toString(),
                    'top-gainer': `${'$'}{stats.topGainer.displayName.split(' ')[0]} (+${'$'}{gainerChange.toFixed(1)}%)`,
                    'top-loser': `${'$'}{stats.topLoser.displayName.split(' ')[0]} (${'$'}{loserChange.toFixed(1)}%)`
                };
                
                Object.entries(statsElements).forEach(([id, value]) => {
                    const element = document.getElementById(id);
                    if (element) element.textContent = value;
                });
            }
            
            showLoading(message) {
                const loadingElements = document.querySelectorAll('.loading');
                loadingElements.forEach(el => {
                    el.textContent = message;
                    el.style.display = 'block';
                });
            }
            
            hideLoading() {
                const loadingElements = document.querySelectorAll('.loading');
                loadingElements.forEach(el => {
                    el.style.display = 'none';
                });
            }
            
            async executeTrade(type) {
                if (!this.selectedItem) return;
                
                const quantityInput = document.getElementById(`${'$'}{type}-quantity`);
                const amount = this.selectedItem.material === 'CRYPTO'
                    ? parseFloat(quantityInput?.value || '0')
                    : parseInt(quantityInput?.value || '0');
                
                if (!amount || amount <= 0) {
                    this.showNotification('Please enter a valid quantity', 'error');
                    return;
                }
                
                const button = document.getElementById(`${'$'}{type}-btn`);
                const originalText = button.textContent;
                button.disabled = true;
                button.textContent = 'Processing...';
                
                try {
                    const isCrypto = this.selectedItem.material === 'CRYPTO';
                    const endpoint = isCrypto ? `/api/crypto/${'$'}{type}` : `/api/${'$'}{type}`;
                    const body = isCrypto
                        ? JSON.stringify({ amount: amount })
                        : JSON.stringify({ material: this.selectedItem.material, amount: amount });
                    const response = await fetch(`${'$'}{endpoint}`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` },
                        body
                    });
                    
                    const data = await response.json();
                    
                    if (response.ok) {
                        const total = (amount * this.selectedItem.effectivePrice).toFixed(2);
                        const message = type === 'buy' 
                            ? `Successfully bought ${'$'}{amount}x ${'$'}{this.selectedItem.displayName} for ${'$'}${'$'}{total}`
                            : `Successfully sold ${'$'}{amount}x ${'$'}{this.selectedItem.displayName} for ${'$'}${'$'}{total}`;
                            
                        this.showNotification(message, 'success');
                        
                        // Clear the form
                        quantityInput.value = '';
                        
                        // Refresh data
                        await this.loadBalance();
                        await this.loadItems();
                        await this.loadHoldings();
                        this.updateMarketStats();
                        this.updateTradeTotals();
                        this.updateTradingButtons();
                    } else {
                        this.showNotification(data.error || `${'$'}{type.charAt(0).toUpperCase() + type.slice(1)} failed`, 'error');
                    }
                } catch (error) {
                    this.showNotification('Network error occurred', 'error');
                } finally {
                    button.disabled = false;
                    button.textContent = originalText;
                }
            }
            
            async loadHoldings() {
                try {
                    const ep = this.invHoldingsEnabled ? '/api/holdings/combined' : '/api/holdings';
                    const [r, rc] = await Promise.all([
                        fetch(`${'$'}{ep}`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } }),
                        fetch(`/api/crypto/holdings`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } })
                    ]);
                    const list = await r.json();
                    const crypto = rc.ok ? await rc.json() : [];
                    const combined = Array.isArray(list) ? list.slice() : [];
                    if (Array.isArray(crypto) && crypto.length > 0) {
                        // Represent crypto as a pseudo-item row
                        combined.push({ material: 'CRYPTO', quantity: parseFloat(crypto[0].balance || '0'), avgCost: 0, currentPrice: parseFloat((window.cryptoHistoryData?.slice(-1)[0]?.price) || '0') });
                    }
                    if (Array.isArray(combined)) {
                        this.holdings = new Map();
                        combined.forEach(row => this.holdings.set(row.material, { quantity: row.quantity, avgCost: row.avgCost, currentPrice: row.currentPrice }));
                        this.renderHoldings(combined);
                    }
                } catch(_) {}
            }

            renderHoldings(list) {
                const c = document.getElementById('holdings-container');
                if (!c) return;
                if (!list || list.length === 0) { c.innerHTML = '<div class="loading">' + this.tr('no_holdings', 'No holdings yet') + '</div>'; return; }
                
                // Add withdraw all button at top
                let html = `<div class="holdings-header">
                    <button class="btn btn-withdraw-all" onclick="window.endexApp.withdrawAll()">${'$'}{this.tr('withdraw_all', 'Withdraw All')}</button>
                </div>`;
                
                html += list.map(h => {
                    const investQty = h.investQty ?? 0;
                    const invQty = h.invQty ?? 0;
                    const totalQty = h.quantity ?? (investQty + invQty);
                    const pnl = (h.currentPrice - (h.avgCost ?? 0)) * investQty; // PnL from invested position only
                    const cls = pnl >= 0 ? 'positive' : 'negative';
                    const name = (h.material || '').replaceAll('_',' ');
                    const mat = h.material || '';
                    const invBadge = invQty > 0 ? `<span class="badge">Inv ${'$'}{invQty}</span>` : '';
                    const investBadge = investQty > 0 ? `<span class="badge badge-holdings">Hold ${'$'}{investQty}</span>` : '';
                    // Only show withdraw button for items in holdings (not just inventory)
                    const withdrawBtn = investQty > 0 && mat !== 'CRYPTO' ? `<button class="btn-withdraw" onclick="window.endexApp.withdraw('${'$'}{mat}', ${'$'}{investQty})" title="${'$'}{this.tr('withdraw_tooltip', 'Withdraw to inventory')}">${'$'}{this.tr('withdraw_btn', 'Get')}</button>` : '';
                    return `<div class="row">
                        <div class="left"><div class="item-icon">${'$'}{(mat).substring(0,2)}</div><div>${'$'}{name} ${'$'}{invBadge}${'$'}{investBadge}</div></div>
                        <div class="right ${'$'}{cls}">${'$'}{totalQty} • ${'$'}${'$'}{(h.currentPrice||0).toFixed(2)} • PnL ${'$'}${'$'}{pnl.toFixed(2)} ${'$'}{withdrawBtn}</div>
                    </div>`;
                }).join('');
                c.innerHTML = html;
            }
            
            async withdraw(material, amount) {
                try {
                    const res = await fetch('/api/holdings/withdraw', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` },
                        body: JSON.stringify({ material, amount })
                    });
                    const data = await res.json();
                    if (res.ok && data.withdrawn > 0) {
                        this.showNotification(`Withdrew ${'$'}{data.withdrawn}x ${'$'}{material.replace(/_/g,' ')} to inventory!`, 'success');
                        if (data.remaining > 0) {
                            this.showNotification(`${'$'}{data.remaining} items still in holdings (inventory full)`, 'warning');
                        }
                    } else if (data.error) {
                        this.showNotification(data.error, 'error');
                    }
                    // Refresh holdings
                    await this.loadHoldings();
                } catch(e) {
                    this.showNotification('Withdraw failed: ' + e.message, 'error');
                }
            }
            
            async withdrawAll() {
                try {
                    const res = await fetch('/api/holdings/withdraw', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` },
                        body: JSON.stringify({ material: 'ALL' })
                    });
                    const data = await res.json();
                    if (res.ok && data.withdrawn > 0) {
                        this.showNotification(`Withdrew ${'$'}{data.withdrawn} items to inventory!`, 'success');
                        if (data.remaining > 0) {
                            this.showNotification(`${'$'}{data.remaining} items still in holdings (inventory full)`, 'warning');
                        }
                    } else if (data.error) {
                        this.showNotification(data.error, 'error');
                    }
                    // Refresh holdings
                    await this.loadHoldings();
                } catch(e) {
                    this.showNotification('Withdraw failed: ' + e.message, 'error');
                }
            }

            async loadReceipts() {
                try {
                    const [r1, r2] = await Promise.all([
                        fetch(`/api/receipts?limit=50`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } }),
                        fetch(`/api/crypto/receipts`, { headers: { 'X-Endex-UI': '1', 'Authorization': `Bearer ${'$'}{this.sessionToken}` } })
                    ]);
                    const list = await r1.json();
                    const clist = r2.ok ? await r2.json() : [];
                    const merged = Array.isArray(list) ? list.slice() : [];
                    if (Array.isArray(clist) && clist.length > 0) {
                        // show raw entries for crypto audit lines at top
                        clist.slice(-10).forEach(e => merged.unshift({ time: Math.floor(Date.now()/1000), material: 'CRYPTO', type: 'INFO', amount: 0, unitPrice: 0, total: 0, raw: e.entry }));
                    }
                    if (Array.isArray(merged)) this.renderReceipts(merged);
                } catch(_) {}
            }

            renderReceipts(list) {
                const c = document.getElementById('receipts-container');
                if (!c) return;
                if (!list || list.length === 0) { c.innerHTML = '<div class="loading">' + this.tr('no_receipts', 'No receipts yet') + '</div>'; return; }
                c.innerHTML = list.map(r => {
                    const dt = new Date((r.time || r.timestamp) * 1000).toLocaleTimeString();
                    const color = r.type === 'BUY' ? '#f87171' : '#4ade80';
                    return `<div class="row"><div class="left"><div style="width:8px;height:8px;border-radius:50%;background:${'$'}{color}"></div><div>${'$'}{dt} ${'$'}{r.type} ${'$'}{r.amount}x ${'$'}{r.material}</div></div><div class="right">${'$'}${'$'}{r.total.toFixed(2)}</div></div>`;
                }).join('');
            }

            async addToWatchlist(material) {
                try {
                    await fetch(`/api/watchlist/add`, { method:'POST', headers:{'Content-Type':'application/json','X-Endex-UI':'1','Authorization': `Bearer ${'$'}{this.sessionToken}`}, body: JSON.stringify({ material }) });
                    this.watchlist.add(material);
                } catch(_) {}
            }
            async removeFromWatchlist(material) {
                try {
                    await fetch(`/api/watchlist/remove`, { method:'POST', headers:{'Content-Type':'application/json','X-Endex-UI':'1','Authorization': `Bearer ${'$'}{this.sessionToken}`}, body: JSON.stringify({ material }) });
                    this.watchlist.delete(material);
                } catch(_) {}
            }
            
            initChart() {
                const chartCanvas = document.getElementById('price-chart');
                if (!chartCanvas) return;
                
                const ctx = chartCanvas.getContext('2d');
                this.chart = new Chart(ctx, {
                    type: 'line',
                    data: {
                        labels: [],
                        datasets: [{
                            label: 'Price History',
                            data: [],
                            borderColor: '#a78bfa',
                            backgroundColor: 'rgba(167, 139, 250, 0.12)',
                            tension: 0.4,
                            fill: true,
                            pointBackgroundColor: '#c084fc',
                            pointBorderColor: '#ffffff',
                            pointRadius: 4,
                            pointHoverRadius: 6,
                            borderWidth: 3
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: {
                            intersect: false,
                            mode: 'index'
                        },
                        plugins: {
                            legend: {
                                display: true,
                                position: 'top',
                                labels: {
                                    color: '#e8e9ea',
                                    font: {
                                        size: 14,
                                        weight: '600'
                                    },
                                    usePointStyle: true,
                                    pointStyle: 'circle'
                                }
                            },
                            tooltip: {
                                backgroundColor: 'rgba(40, 18, 60, 0.95)',
                                titleColor: '#c4b5fd',
                                bodyColor: '#e8e9ea',
                                borderColor: '#a78bfa',
                                borderWidth: 1,
                                cornerRadius: 8,
                                displayColors: false,
                                callbacks: {
                                    title: function(context) {
                                        return 'Time: ' + context[0].label;
                                    },
                                    label: function(context) {
                                        return 'Price: ${'$'}' + context.parsed.y.toFixed(2);
                                    }
                                }
                            }
                        },
                        scales: {
                            x: {
                                ticks: { 
                                    color: '#a0a0a0',
                                    font: { size: 12 }
                                },
                                grid: { 
                                    color: 'rgba(255, 255, 255, 0.08)',
                                    drawBorder: false
                                },
                                border: { display: false }
                            },
                            y: {
                                ticks: { 
                                    color: '#a0a0a0',
                                    font: { size: 12 },
                                    callback: function(value) {
                                        return '${'$'}' + value.toFixed(2);
                                    }
                                },
                                grid: { 
                                    color: 'rgba(255, 255, 255, 0.08)',
                                    drawBorder: false
                                },
                                border: { display: false }
                            }
                        }
                    }
                });
            }
            
            updateChart() {
                if (!this.selectedItem || !this.chart) return;
                
                const history = this.selectedItemHistory || [];
                const labels = history.map(point => new Date(point.timestamp * 1000).toLocaleTimeString());
                const prices = history.map(point => point.price);
                
                this.chart.data.labels = labels;
                this.chart.data.datasets[0].data = prices;
                this.chart.data.datasets[0].label = `${'$'}{this.selectedItem.displayName} Price`;
                this.chart.update();
            }
            
            showNotification(message, type) {
                const notification = document.getElementById('notification');
                notification.textContent = message;
                notification.className = `notification ${'$'}{type} show`;
                
                setTimeout(() => {
                    notification.classList.remove('show');
                }, 4000);
            }
        }
        
        // Initialize the trading interface when page loads
        document.addEventListener('DOMContentLoaded', () => {
            window.endexApp = new EndexTrading();
        });
    """.trimIndent()
    
    // Helper methods for buy/sell operations
    private fun performBuy(player: Player, material: Material, requestedAmount: Int): Boolean {
        try {
            if (plugin.economy == null) return false
            
            val item = plugin.marketManager.get(material) ?: return false
            val db = plugin.marketManager.sqliteStore()
            val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
            
            val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
            val unit = item.currentPrice * plugin.eventManager.multiplierFor(material)
            val subtotal = unit * requestedAmount
            val tax = subtotal * (taxPct / 100.0)
            val total = subtotal + tax
            
            val eco = plugin.economy!!
            val bal = eco.getBalance(player)
            if (bal + 1e-9 < total) return false
            
            // Virtual Holdings Mode: Items go to holdings, not inventory
            if (holdingsEnabled && db != null) {
                // Check holdings limit before purchase
                val success = db.addToHoldings(player.uniqueId.toString(), material, requestedAmount, unit)
                if (!success) {
                    val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
                    player.sendMessage(Lang.colorize(Lang.get("web-trading.holdings-limit", "limit" to maxHoldings.toString())))
                    return false
                }
                
                // Payment successful, deduct money
                val withdraw = eco.withdrawPlayer(player, total)
                if (!withdraw.transactionSuccess()) {
                    // Rollback holdings if payment fails
                    db.removeFromHoldings(player.uniqueId.toString(), material, requestedAmount)
                    return false
                }
                
                // Record trade
                db.insertTrade(player.uniqueId.toString(), material, "BUY", requestedAmount, unit, total)
                
                plugin.marketManager.addDemand(material, requestedAmount.toDouble())
                
                player.sendMessage(Lang.colorize(Lang.get("web-trading.buy-success-simple", "amount" to requestedAmount.toString(), "item" to material.name)))
                player.sendMessage(Lang.colorize(Lang.get("web-trading.items-added-holdings")))
                player.sendMessage(Lang.colorize(Lang.get("web-trading.buy-withdraw-hint", "item" to material.name)))
                
                return true
            }
            
            // Legacy Mode: Direct to inventory (fallback if holdings disabled)
            // Check inventory capacity BEFORE processing payment
            val maxCapacity = calculateInventoryCapacity(player, material)
            var amount = requestedAmount
            val deliveryEnabled = plugin.getDeliveryManager() != null && plugin.config.getBoolean("delivery.enabled", true)
            
            if (amount > maxCapacity) {
                if (!deliveryEnabled) {
                    // Old behavior: cap the purchase
                    amount = maxCapacity
                    if (amount <= 0) {
                        player.sendMessage(Lang.colorize(Lang.get("web-trading.inventory-full-web")))
                        return false
                    }
                    player.sendMessage(Lang.colorize(Lang.get("web-trading.buy-capped", "amount" to amount.toString(), "item" to material.name, "requested" to requestedAmount.toString())))
                    player.sendMessage(Lang.colorize(Lang.get("web-trading.tip-empty-inventory")))
                }
                // Else: delivery enabled, we'll charge for full amount and overflow goes to pending
            }
            
            val withdraw = eco.withdrawPlayer(player, total)
            if (!withdraw.transactionSuccess()) return false
            
            // Give items to player
            val stack = org.bukkit.inventory.ItemStack(material)
            var remaining = amount
            var delivered = 0
            var pendingDelivery = 0
            
            while (remaining > 0) {
                val toGive = kotlin.math.max(1, kotlin.math.min(remaining, stack.maxStackSize))
                val give = stack.clone()
                give.amount = toGive
                val leftovers = player.inventory.addItem(give)
                
                if (leftovers.isNotEmpty()) {
                    // Inventory full - handle overflow
                    val overflow = leftovers.values.sumOf { it.amount }
                    val actuallyDelivered = toGive - overflow
                    delivered += actuallyDelivered
                    
                    if (deliveryEnabled) {
                        // Send remaining + overflow to pending deliveries
                        val totalPending = overflow + (remaining - toGive)
                        val success = plugin.getDeliveryManager()?.addPending(player.uniqueId, material, totalPending) ?: false
                        if (success) {
                            pendingDelivery = totalPending
                        } else {
                            // Fallback: drop overflow on ground
                            leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                            remaining -= toGive
                            continue
                        }
                    } else {
                        // Delivery disabled: drop overflow on ground
                        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                        remaining -= toGive
                        continue
                    }
                    // Break out - everything handled (delivered or pending)
                    break
                } else {
                    delivered += toGive
                    remaining -= toGive
                }
            }
            
            plugin.marketManager.addDemand(material, amount.toDouble())
            
            // Send appropriate message
            if (pendingDelivery > 0) {
                player.sendMessage(Lang.colorize(Lang.get("web-trading.buy-with-delivery", "amount" to amount.toString(), "item" to material.name)))
                player.sendMessage(Lang.colorize(Lang.get("web-trading.buy-delivery-status", "delivered" to delivered.toString(), "pending" to pendingDelivery.toString())))
            }
            
            return true
        } catch (e: Exception) {
            logger.warn("Buy operation failed: ${e.message}")
            return false
        }
    }
    
    private fun performSell(player: Player, material: Material, amount: Int): Boolean {
        try {
            if (plugin.economy == null) return false
            
            val item = plugin.marketManager.get(material) ?: return false
            val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
            val unit = item.currentPrice * plugin.eventManager.multiplierFor(material)
            
            // Check if player has enough items
            val removed = removeItems(player, material, amount)
            if (removed < amount) {
                // Return items if we couldn't get enough
                if (removed > 0) {
                    val returnStack = org.bukkit.inventory.ItemStack(material, removed)
                    val leftovers = player.inventory.addItem(returnStack)
                    if (leftovers.isNotEmpty()) {
                        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                    }
                }
                return false
            }
            
            val subtotal = unit * amount
            val tax = subtotal * (taxPct / 100.0)
            val total = subtotal - tax
            
            val eco = plugin.economy!!
            val deposit = eco.depositPlayer(player, total)
            if (!deposit.transactionSuccess()) return false
            
            plugin.marketManager.addSupply(material, amount.toDouble())
            // Update holdings and receipt if sqlite is used
            runCatching {
                val db = plugin.marketManager.sqliteStore()
                if (db != null) {
                    db.upsertHolding(player.uniqueId.toString(), material, -amount, unit)
                    db.insertTrade(player.uniqueId.toString(), material, "SELL", amount, unit, total)
                }
            }
            return true
        } catch (e: Exception) {
            logger.warn("Sell operation failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Sell items directly from virtual holdings (not from player inventory).
     * Returns Triple<success, message, amountSold>
     */
    private fun performSellFromHoldings(player: Player, material: Material, amount: Int): Triple<Boolean, String?, Int> {
        try {
            if (plugin.economy == null) return Triple(false, "Economy not available", 0)
            
            val item = plugin.marketManager.get(material) ?: return Triple(false, "Item not tracked", 0)
            val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
            
            // Apply spread markdown for selling (anti-arbitrage protection)
            val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
            val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
            val unit = item.currentPrice * plugin.eventManager.multiplierFor(material) * (1.0 - sellMarkdownPct / 100.0)
            
            // Check holdings
            val db = plugin.marketManager.sqliteStore() ?: return Triple(false, "Database not available", 0)
            val holdingsData = db.getHolding(player.uniqueId.toString(), material)
            val holdingsQty = holdingsData?.first ?: 0
            
            if (holdingsQty <= 0) {
                return Triple(false, "No items in holdings", 0)
            }
            
            // Remove from holdings
            val removed = db.removeFromHoldings(player.uniqueId.toString(), material, amount)
            if (removed <= 0) {
                return Triple(false, "Failed to remove from holdings", 0)
            }
            
            val subtotal = unit * removed
            val tax = subtotal * (taxPct / 100.0)
            val total = subtotal - tax
            
            val eco = plugin.economy!!
            val deposit = eco.depositPlayer(player, total)
            if (!deposit.transactionSuccess()) {
                // Try to restore holdings if payment failed
                runCatching { db.addToHoldings(player.uniqueId.toString(), material, removed, unit) }
                return Triple(false, "Payment failed", 0)
            }
            
            plugin.marketManager.addSupply(material, removed.toDouble())
            // Record trade
            runCatching {
                db.insertTrade(player.uniqueId.toString(), material, "SELL_HOLDINGS", removed, unit, total)
            }
            
            return Triple(true, "Sold $removed $material from holdings for ${String.format("%.2f", total)}", removed)
        } catch (e: Exception) {
            logger.warn("Sell from holdings failed: ${e.message}")
            return Triple(false, e.message ?: "Unknown error", 0)
        }
    }
    
    /**
     * Calculate how many items of the given material the player can receive in their inventory.
     * Accounts for existing partial stacks and empty slots.
     */
    private fun calculateInventoryCapacity(player: Player, material: Material): Int {
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
    
    private fun removeItems(player: Player, material: Material, amount: Int): Int {
        var remaining = amount
        val inventory = player.inventory
        
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type != material) continue
            
            val stackAmount = item.amount
            if (stackAmount <= remaining) {
                inventory.setItem(i, null)
                remaining -= stackAmount
            } else {
                item.amount = stackAmount - remaining
                remaining = 0
            }
            
            if (remaining <= 0) break
        }
        
        return amount - remaining
    }
    
    /**
     * Withdraw items from virtual holdings to player inventory.
     * Returns a map with success status, amount withdrawn, and remaining.
     */
    private fun withdrawFromHoldings(
        player: Player,
        db: org.lokixcz.theendex.market.SqliteStore,
        material: Material,
        requestedAmount: Int
    ): Map<String, Any> {
        val holding = db.getHolding(player.uniqueId.toString(), material)
        if (holding == null || holding.first <= 0) {
            return mapOf(
                "success" to false,
                "error" to "No ${material.name} in holdings",
                "withdrawn" to 0,
                "remaining" to 0
            )
        }
        
        val (available, _) = holding
        val toWithdraw = minOf(requestedAmount, available)
        val capacity = calculateInventoryCapacity(player, material)
        
        if (capacity <= 0) {
            return mapOf(
                "success" to false,
                "error" to "Inventory is full",
                "withdrawn" to 0,
                "remaining" to available
            )
        }
        
        val actualWithdraw = minOf(toWithdraw, capacity)
        
        // Remove from holdings
        val removed = db.removeFromHoldings(player.uniqueId.toString(), material, actualWithdraw)
        if (removed <= 0) {
            return mapOf(
                "success" to false,
                "error" to "Failed to withdraw items",
                "withdrawn" to 0,
                "remaining" to available
            )
        }
        
        // Give items to player
        var remaining = removed
        while (remaining > 0) {
            val toGive = minOf(remaining, material.maxStackSize)
            val stack = org.bukkit.inventory.ItemStack(material, toGive)
            val leftovers = player.inventory.addItem(stack)
            if (leftovers.isNotEmpty()) {
                // Should not happen since we checked capacity, but safety fallback
                leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
            remaining -= toGive
        }
        
        val newHolding = db.getHolding(player.uniqueId.toString(), material)
        val stillHave = newHolding?.first ?: 0
        
        player.sendMessage(Lang.colorize(Lang.get("web-trading.withdraw-success", "amount" to removed.toString(), "item" to material.name)))
        if (stillHave > 0) {
            player.sendMessage(Lang.colorize(Lang.get("web-trading.withdraw-remaining", "remaining" to stillHave.toString(), "item" to material.name)))
        }
        
        return mapOf(
            "success" to true,
            "material" to material.name,
            "withdrawn" to removed,
            "remaining" to stillHave
        )
    }
    
    /**
     * Withdraw all items from virtual holdings to player inventory.
     */
    private fun withdrawAllFromHoldings(
        player: Player,
        db: org.lokixcz.theendex.market.SqliteStore
    ): Map<String, Any> {
        val holdings = db.listHoldings(player.uniqueId.toString())
        
        if (holdings.isEmpty()) {
            return mapOf(
                "success" to false,
                "error" to "No items in holdings",
                "totalWithdrawn" to 0,
                "totalRemaining" to 0,
                "details" to emptyList<Any>()
            )
        }
        
        var totalWithdrawn = 0
        val withdrawn = mutableListOf<Map<String, Any>>()
        var totalRemaining = 0
        
        for ((mat, pair) in holdings) {
            val (available, _) = pair
            if (available <= 0) continue
            
            val capacity = calculateInventoryCapacity(player, mat)
            if (capacity <= 0) {
                totalRemaining += available
                continue
            }
            
            val toWithdraw = minOf(available, capacity)
            val removed = db.removeFromHoldings(player.uniqueId.toString(), mat, toWithdraw)
            
            if (removed > 0) {
                // Give items
                var remaining = removed
                while (remaining > 0) {
                    val toGive = minOf(remaining, mat.maxStackSize)
                    val stack = org.bukkit.inventory.ItemStack(mat, toGive)
                    val leftovers = player.inventory.addItem(stack)
                    if (leftovers.isNotEmpty()) {
                        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                    }
                    remaining -= toGive
                }
                
                totalWithdrawn += removed
                withdrawn.add(mapOf("material" to mat.name, "amount" to removed))
                
                // Track remaining
                val stillHave = available - removed
                if (stillHave > 0) totalRemaining += stillHave
            } else {
                totalRemaining += available
            }
        }
        
        if (totalWithdrawn == 0) {
            return mapOf(
                "success" to false,
                "error" to "No items were withdrawn (inventory may be full)",
                "totalWithdrawn" to 0,
                "totalRemaining" to totalRemaining,
                "details" to emptyList<Any>()
            )
        }
        
        player.sendMessage(Lang.colorize(Lang.get("web-trading.withdraw-all-success", "total" to totalWithdrawn.toString())))
        if (totalRemaining > 0) {
            player.sendMessage(Lang.colorize(Lang.get("web-trading.withdraw-all-remaining", "remaining" to totalRemaining.toString())))
        }
        
        return mapOf(
            "success" to true,
            "totalWithdrawn" to totalWithdrawn,
            "totalRemaining" to totalRemaining,
            "details" to withdrawn
        )
    }
}