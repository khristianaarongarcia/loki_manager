package org.lokixcz.theendex

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit
import org.lokixcz.theendex.market.MarketManager
import org.lokixcz.theendex.market.ItemsConfigManager
import org.lokixcz.theendex.commands.MarketCommand
import org.lokixcz.theendex.commands.EndexCommand
import org.lokixcz.theendex.commands.MarketTabCompleter
import org.lokixcz.theendex.commands.EndexTabCompleter
import org.lokixcz.theendex.commands.CommandAliasManager
import net.milkbowl.vault.economy.Economy
import org.lokixcz.theendex.gui.MarketGUI
import org.lokixcz.theendex.gui.PlayerPrefsStore
import org.lokixcz.theendex.gui.GuiConfigManager
import org.lokixcz.theendex.events.EventManager
import org.lokixcz.theendex.web.WebServer
import org.lokixcz.theendex.lang.Lang
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import org.bukkit.scheduler.BukkitTask

class Endex : JavaPlugin() {

    lateinit var marketManager: MarketManager
        private set
    lateinit var itemsConfigManager: ItemsConfigManager
        private set
    var economy: Economy? = null
        private set
    lateinit var marketGUI: MarketGUI
        private set
    lateinit var prefsStore: PlayerPrefsStore
        private set
    lateinit var eventManager: EventManager
        private set
    private var addonManager: org.lokixcz.theendex.addon.AddonManager? = null
    private var addonCommandRouter: org.lokixcz.theendex.addon.AddonCommandRouter? = null
    private var resourceTracker: org.lokixcz.theendex.tracking.ResourceTracker? = null
    private var inventorySnapshots: org.lokixcz.theendex.tracking.InventorySnapshotService? = null
    private var worldStorageScanner: org.lokixcz.theendex.tracking.WorldStorageScanner? = null
    private var webServer: WebServer? = null
    private var deliveryManager: org.lokixcz.theendex.delivery.DeliveryManager? = null
    private var guiConfigManager: GuiConfigManager? = null
    private var commandAliasManager: CommandAliasManager? = null
    private var updateChecker: org.lokixcz.theendex.util.UpdateChecker? = null
    
    // Custom Shop System (EconomyShopGUI-style)
    var customShopManager: org.lokixcz.theendex.shop.CustomShopManager? = null
        private set
    var customShopGUI: org.lokixcz.theendex.shop.CustomShopGUI? = null
        private set
    var shopEditorGUI: org.lokixcz.theendex.shop.editor.ShopEditorGUI? = null
        private set

    private var priceTask: BukkitTask? = null
    private var backupTask: BukkitTask? = null
    private var eventsTask: BukkitTask? = null
    private var trackingSaveTask: BukkitTask? = null
    private val expectedConfigVersion = 1
    private lateinit var logx: org.lokixcz.theendex.util.EndexLogger

    override fun onEnable() {
        // Ensure config exists
        saveDefaultConfig()
        // Initialize logger utility with config
        logx = org.lokixcz.theendex.util.EndexLogger(this)
        logx.verbose = try { config.getBoolean("logging.verbose", false) } catch (_: Throwable) { false }
        
        // Extract translated config files to config_translations/ folder
        extractConfigTranslations()
        
        // Print banner
        runCatching {
            val stream = getResource("banner.txt")
            if (stream != null) {
                val ver = description.version
                val date = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                val lines = stream.bufferedReader().readLines().map { it.replace("%VERSION%", ver).replace("%DATE%", date) }
                logx.banner(lines)
            }
        }

        // Migrate config if needed, then warn if mismatch persists
        val migrated = checkAndMigrateConfig()
        if (migrated) reloadConfig()
        try {
            val got = config.getInt("config-version", -1)
            if (got != expectedConfigVersion) {
                logx.warn("Config-version mismatch (expected $expectedConfigVersion, found $got). Consider backing up and regenerating config.yml.")
            }
        } catch (_: Throwable) {}

        // Initialize language system
        try {
            Lang.init(this)
            logx.info("Language system initialized (locale=${Lang.locale()})")
        } catch (t: Throwable) {
            logx.warn("Failed to initialize language system: ${t.message}")
        }

        // Initialize items config manager (items.yml)
        itemsConfigManager = ItemsConfigManager(this)
        val itemsLoaded = itemsConfigManager.load()
        
        // Initialize market manager and load data
        val useSqlite = config.getBoolean("storage.sqlite", false)
        val sqliteStore = if (useSqlite) org.lokixcz.theendex.market.SqliteStore(this) else null
        marketManager = if (useSqlite) MarketManager(this, sqliteStore) else MarketManager(this)
        marketManager.load()
        
        // Sync items.yml with market.db
        if (!itemsLoaded) {
            // First run or no items.yml - export from existing market data
            if (marketManager.allItems().isNotEmpty()) {
                itemsConfigManager.importFromMarketManager(marketManager)
                itemsConfigManager.save()
                logx.info("Created items.yml from existing market data (${itemsConfigManager.count()} items)")
            } else {
                // No existing data - seed defaults to items.yml
                seedDefaultItems()
                itemsConfigManager.save()
                logx.info("Created items.yml with default items (${itemsConfigManager.count()} items)")
            }
        }
        
        // Sync items.yml pricing rules to market.db
        val syncResult = itemsConfigManager.syncToMarketManager(marketManager, sqliteStore)
        if (syncResult.added > 0 || syncResult.updated > 0) {
            marketManager.save()
            logx.info("Synced items.yml to market: ${syncResult.added} added, ${syncResult.updated} updated")
        }
        
        logx.info("Market loaded (storage=${if (useSqlite) "sqlite" else "yaml"}, items=${itemsConfigManager.enabledCount()})")

        // Initialize delivery manager (for pending item deliveries)
        try {
            deliveryManager = org.lokixcz.theendex.delivery.DeliveryManager(this)
            deliveryManager?.init()
        } catch (t: Throwable) {
            logx.warn("Failed to initialize delivery manager: ${t.message}")
        }

        // Register public API service for other plugins
        try {
            server.servicesManager.register(org.lokixcz.theendex.api.EndexAPI::class.java, org.lokixcz.theendex.api.EndexAPIImpl(this), this, org.bukkit.plugin.ServicePriority.Normal)
        } catch (_: Throwable) {}

        // Setup Vault economy if present (with delayed retry for late-loading economy plugins)
        setupEconomy()
        if (economy == null) {
            // Some economy plugins (like SimpleEconomy) register with Vault after other plugins load
            // Schedule a delayed retry to catch late registrations
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                if (economy == null) {
                    setupEconomy()
                    if (economy != null) {
                        logx.info("Economy provider found on delayed check: ${economy?.name}")
                    }
                }
            }, 40L) // 2 seconds after server finishes loading
        }

        // Schedule periodic tasks
        scheduleTasks()

        // Commands
        getCommand("endex")?.apply {
            setExecutor(EndexCommand(this@Endex))
            tabCompleter = EndexTabCompleter()
        }
        getCommand("market")?.apply {
            setExecutor(MarketCommand(this@Endex))
            tabCompleter = MarketTabCompleter()
        }
        // Listeners
        prefsStore = PlayerPrefsStore(this)
        marketGUI = MarketGUI(this)
        server.pluginManager.registerEvents(marketGUI, this)

        // Events
    eventManager = EventManager(this)
        eventManager.load()
        eventsTask = Bukkit.getScheduler().runTaskTimer(this, Runnable { eventManager.tickExpire() }, 20L, 20L * 30) // every 30s
    logx.debug("Event manager initialized and tick task scheduled")

        // GUI Configuration
        try {
            guiConfigManager = GuiConfigManager(this)
            guiConfigManager?.load()
            logx.info("GUI configs loaded from guis/ folder")
        } catch (t: Throwable) {
            logx.warn("Failed to load GUI configs: ${t.message}")
        }

        // Custom Shop System (EconomyShopGUI-style)
        try {
            val csm = org.lokixcz.theendex.shop.CustomShopManager(this)
            csm.load()
            customShopManager = csm
            
            val csg = org.lokixcz.theendex.shop.CustomShopGUI(this)
            server.pluginManager.registerEvents(csg, this)
            customShopGUI = csg
            
            // Shop Editor GUI (Admin tool for creating/editing custom shops)
            val seg = org.lokixcz.theendex.shop.editor.ShopEditorGUI(this)
            server.pluginManager.registerEvents(seg, this)
            shopEditorGUI = seg
            
            val mode = config.getString("shop.mode", "DEFAULT") ?: "DEFAULT"
            logx.info("Shop system loaded (mode=$mode, shops=${csm.all().size})")
        } catch (t: Throwable) {
            logx.warn("Failed to initialize custom shop system: ${t.message}")
        }

        // Command Aliases
        try {
            commandAliasManager = CommandAliasManager(this)
            commandAliasManager?.load()
        } catch (t: Throwable) {
            logx.warn("Failed to load command aliases: ${t.message}")
        }

        // Resource tracking
        try {
            if (config.getBoolean("tracking.resources.enabled", true)) {
                val rt = org.lokixcz.theendex.tracking.ResourceTracker(this)
                rt.applyToMarket = config.getBoolean("tracking.resources.apply-to-market", false)
                rt.blockBreak = config.getBoolean("tracking.resources.sources.block-break", true)
                rt.mobDrops = config.getBoolean("tracking.resources.sources.mob-drops", true)
                rt.fishing = config.getBoolean("tracking.resources.sources.fishing", true)
                rt.enabled = true
                // Load previous totals
                runCatching { rt.loadFromDisk() }
                rt.start()
                resourceTracker = rt
                logx.info("Resource tracking enabled (sources: block-break=${rt.blockBreak}, mob-drops=${rt.mobDrops}, fishing=${rt.fishing})")
                // Schedule periodic persistence (every 5 minutes)
                trackingSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
                    try { resourceTracker?.saveToDisk() } catch (t: Throwable) { logx.warn("Failed saving tracking.yml: ${t.message}") }
                }, 20L * 60L * 5, 20L * 60L * 5)
            } else {
                logx.debug("Resource tracking disabled by config")
            }
        } catch (t: Throwable) { logx.warn("Failed to initialize resource tracking: ${t.message}") }

        // Optional inventory snapshots for web holdings (online-only)
        try {
            val invSvc = org.lokixcz.theendex.tracking.InventorySnapshotService(this)
            if (invSvc.enabled()) {
                inventorySnapshots = invSvc
                logx.info("Inventory snapshot service enabled (web.holdings.inventory.*)")
            } else {
                logx.debug("Inventory snapshot service disabled by config (web.holdings.inventory.enabled=false)")
            }
        } catch (t: Throwable) {
            logx.warn("Failed to initialize inventory snapshot service: ${t.message}")
        }

        // World storage scanner for global item tracking (chests, barrels, shulkers, etc.)
        try {
            val wss = org.lokixcz.theendex.tracking.WorldStorageScanner(this)
            if (wss.enabled()) {
                wss.start()
                worldStorageScanner = wss
                logx.info("World storage scanner enabled (price-world-storage.*)")
            } else {
                logx.debug("World storage scanner disabled by config (price-world-storage.enabled=false)")
            }
        } catch (t: Throwable) {
            logx.warn("Failed to initialize world storage scanner: ${t.message}")
        }

        // Initialize Web Server instance EARLY so addons can register web routes
        try {
            if (webServer == null) {
                webServer = WebServer(this) // don't start yet; allows addons to register routes
            }
        } catch (t: Throwable) {
            logx.warn("Failed to initialize web server instance: ${t.message}")
        }

        // Addons
        try {
            // Initialize router FIRST so addons can register their commands/aliases during init()
            addonCommandRouter = org.lokixcz.theendex.addon.AddonCommandRouter(this)
            addonManager = org.lokixcz.theendex.addon.AddonManager(this).also {
                it.ensureFolder()
                it.loadAll()
            }
            logx.info("Addons loaded and command router ready")
        } catch (t: Throwable) {
            logx.warn("Addon loading failed: ${t.message}")
        }

        // Web Server
        try {
            if (config.getBoolean("web.enabled", true)) {
                val port = config.getInt("web.port", 3434)
                if (webServer == null) webServer = WebServer(this)
                webServer?.start(port)
                // Dynamically register loaded addons into the web Addons tab
                try {
                    val names = addonManager?.loadedAddonNames() ?: emptyList()
                    if (names.isNotEmpty()) {
                        names.forEach { n -> runCatching { webServer?.registerAddonNav(n) } }
                    }
                } catch (_: Throwable) {}
                logx.info("Web server started on port $port")
            } else {
                logx.debug("Web server disabled by config")
            }
        } catch (t: Throwable) {
            logx.warn("Failed to start web server: ${t.message}")
        }

        // Update Checker
        try {
            updateChecker = org.lokixcz.theendex.util.UpdateChecker(this)
            updateChecker?.init()
        } catch (t: Throwable) {
            logx.warn("Failed to initialize update checker: ${t.message}")
        }

        // PlaceholderAPI Integration
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                org.lokixcz.theendex.hooks.EndexExpansion(this).register()
                logx.info("PlaceholderAPI expansion registered (prefix: endex)")
            } else {
                logx.debug("PlaceholderAPI not found, skipping expansion registration")
            }
        } catch (t: Throwable) {
            logx.warn("Failed to register PlaceholderAPI expansion: ${t.message}")
        }

        // bStats Metrics
        try {
            val pluginId = 28421 // The Endex bStats plugin ID
            val metrics = Metrics(this, pluginId)
            
            // Custom chart: Storage mode (yaml/sqlite)
            metrics.addCustomChart(SimplePie("storage_mode") {
                if (config.getBoolean("storage.sqlite", false)) "sqlite" else "yaml"
            })
            
            // Custom chart: Shop mode (DEFAULT/CUSTOM)
            metrics.addCustomChart(SimplePie("shop_mode") {
                config.getString("shop.mode", "DEFAULT") ?: "DEFAULT"
            })
            
            // Custom chart: Web UI enabled
            metrics.addCustomChart(SimplePie("web_ui_enabled") {
                if (config.getBoolean("web.enabled", true)) "enabled" else "disabled"
            })
            
            // Custom chart: Holdings system enabled
            metrics.addCustomChart(SimplePie("holdings_enabled") {
                if (config.getBoolean("holdings.enabled", true)) "enabled" else "disabled"
            })
            
            // Custom chart: Number of tracked items
            metrics.addCustomChart(SimplePie("tracked_items") {
                val count = itemsConfigManager.enabledCount()
                when {
                    count <= 10 -> "1-10"
                    count <= 25 -> "11-25"
                    count <= 50 -> "26-50"
                    count <= 100 -> "51-100"
                    else -> "100+"
                }
            })
            
            logx.debug("bStats metrics initialized")
        } catch (t: Throwable) {
            logx.debug("Failed to initialize bStats metrics: ${t.message}")
        }
    }

    override fun onDisable() {
        // Stop web server
        try { 
            webServer?.stop() 
            webServer = null
            logx.info("Web server stopped")
        } catch (t: Throwable) { 
            logx.warn("Failed to stop web server: ${t.message}")
        }
        
        // Stop world storage scanner
        try {
            worldStorageScanner?.stop()
            worldStorageScanner = null
        } catch (t: Throwable) {
            logx.warn("Failed to stop world storage scanner: ${t.message}")
        }
        
        // Unregister API service
    try { server.servicesManager.unregister(org.lokixcz.theendex.api.EndexAPI::class.java) } catch (_: Throwable) {}
        // Disable addons
    try { addonManager?.disableAll() } catch (_: Throwable) {}
        addonCommandRouter = null
        // Save market state on shutdown
        if (this::marketManager.isInitialized) {
            try {
                marketManager.save()
            } catch (t: Throwable) {
                logx.error("Failed to save market on disable: ${t.message}")
            }
        }
        // Persist resource tracking
        try { trackingSaveTask?.cancel(); trackingSaveTask = null } catch (_: Throwable) {}
        try { resourceTracker?.saveToDisk() } catch (_: Throwable) {}
    }

    // API for addons to register subcommands and aliases
    fun registerAddonSubcommand(name: String, handler: org.lokixcz.theendex.addon.AddonSubcommandHandler) {
        val ok = try { addonCommandRouter?.registerSubcommand(name, handler); true } catch (_: Throwable) { false }
        if (ok) logx.debug("Registered addon subcommand '$name'") else logx.warn("Failed to register addon subcommand '$name' (router not ready)")
    }
    fun registerAddonAlias(alias: String, targetSubcommand: String): Boolean {
        val registered = addonCommandRouter?.registerAlias(alias, targetSubcommand) ?: false
        if (registered) logx.debug("Registered addon alias '/$alias' -> '$targetSubcommand'") else logx.warn("Failed to register addon alias '/$alias'")
        return registered
    }

    // --- Explicit accessors to replace reflective field access (security hardening) ---
    fun getAddonCommandRouter(): org.lokixcz.theendex.addon.AddonCommandRouter? = addonCommandRouter
    fun getAddonManager(): org.lokixcz.theendex.addon.AddonManager? = addonManager
    fun getResourceTracker(): org.lokixcz.theendex.tracking.ResourceTracker? = resourceTracker

    // Getter for web server
    fun getWebServer(): WebServer? = webServer
    fun getInventorySnapshotService(): org.lokixcz.theendex.tracking.InventorySnapshotService? = inventorySnapshots
    fun getWorldStorageScanner(): org.lokixcz.theendex.tracking.WorldStorageScanner? = worldStorageScanner
    
    // Getter for delivery manager
    fun getDeliveryManager(): org.lokixcz.theendex.delivery.DeliveryManager? = deliveryManager

    // Getter for GUI config manager
    fun getGuiConfigManager(): GuiConfigManager? = guiConfigManager

    // Getter for command alias manager
    fun getCommandAliasManager(): CommandAliasManager? = commandAliasManager

    // Getter for update checker
    fun getUpdateChecker(): org.lokixcz.theendex.util.UpdateChecker? = updateChecker

    private fun setupEconomy() {
        val pm = server.pluginManager
        if (pm.getPlugin("Vault") == null) {
            logx.warn("Vault not found. Economy features (buy/sell) will be disabled.")
            economy = null
            return
        }
        val rsp = server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            logx.warn("Vault Economy provider not found. Economy features will be disabled.")
            economy = null
        } else {
            economy = rsp.provider
            logx.info("Hooked into Vault economy: ${economy?.name}")
        }
    }

    private fun scheduleTasks() {
        // Cancel existing tasks if any
        priceTask?.cancel(); priceTask = null
        backupTask?.cancel(); backupTask = null

        val seconds = config.getInt("update-interval-seconds", 60).coerceAtLeast(5)
        val sensitivity = config.getDouble("price-sensitivity", 0.05)
        val historyLength = config.getInt("history-length", 5).coerceAtLeast(1)
        val autosaveMinutes = config.getInt("autosave-minutes", 5).coerceAtLeast(1)

        priceTask = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            try {
                marketManager.updatePrices(sensitivity, historyLength)
                logx.debug("Prices updated with sensitivity=$sensitivity history=$historyLength")
                if (config.getBoolean("save-on-each-update", true)) {
                    marketManager.save()
                    logx.debug("Market saved after update")
                }
                // Refresh open Market GUIs so players see updated data
                try { marketGUI.refreshAllOpen() } catch (_: Throwable) {}
            } catch (t: Throwable) {
                logx.error("Price update failed: ${t.message}")
            }
        }, 20L * seconds, 20L * seconds)

        backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            try {
                marketManager.backup()
                logx.debug("Market backup created")
            } catch (t: Throwable) {
                logx.error("Market backup failed: ${t.message}")
            }
        }, 20L * 60L * autosaveMinutes, 20L * 60L * autosaveMinutes)
    }

    fun reloadEndex(sender: CommandSender?) {
        try {
            // Save current state
            marketManager.save()
        } catch (_: Throwable) {}

        // Reload configs and resources
        reloadConfig()
        // Migrate on reload too (in case an older file is restored)
        val migrated = checkAndMigrateConfig()
        if (migrated) reloadConfig()
        try {
            val got = config.getInt("config-version", -1)
            if (got != expectedConfigVersion) {
                logx.warn("Config-version mismatch after reload (expected $expectedConfigVersion, found $got). Consider backing up and regenerating config.yml.")
            }
        } catch (_: Throwable) {}
        
        // Reload language system
        try { Lang.reload(); logx.info("Language system reloaded (locale=${Lang.locale()})") } catch (t: Throwable) { logx.warn("Failed to reload language system: ${t.message}") }
        
        try { eventManager.load() } catch (t: Throwable) { logx.warn("Failed to reload events: ${t.message}") }

        // Reload GUI configs
        try { guiConfigManager?.reload() } catch (t: Throwable) { logx.warn("Failed to reload GUI configs: ${t.message}") }

        // Reload command aliases
        try { commandAliasManager?.reload() } catch (t: Throwable) { logx.warn("Failed to reload command aliases: ${t.message}") }

        // Reload items.yml and market data
        try {
            itemsConfigManager.load()
            marketManager.load()
            val sqliteStore = marketManager.sqliteStore()
            val syncResult = itemsConfigManager.syncToMarketManager(marketManager, sqliteStore)
            if (syncResult.added > 0 || syncResult.updated > 0) {
                marketManager.save()
            }
            logx.info("Reloaded items.yml and market data (${itemsConfigManager.enabledCount()} items)")
        } catch (t: Throwable) { logx.warn("Failed to reload market: ${t.message}") }

        // Reschedule tasks with new settings
        scheduleTasks()
        
        // Restart web server with new config (reuse same instance to preserve addon routes)
        try {
            webServer?.stop()
            if (config.getBoolean("web.enabled", true)) {
                val port = config.getInt("web.port", 3434)
                if (webServer == null) webServer = WebServer(this)
                webServer?.start(port)
                // Re-register addon nav entries dynamically after restart
                try {
                    val names = addonManager?.loadedAddonNames() ?: emptyList()
                    if (names.isNotEmpty()) {
                        names.forEach { n -> runCatching { webServer?.registerAddonNav(n) } }
                    }
                } catch (_: Throwable) {}
                logx.info("Web server restarted on port $port")
            }
        } catch (t: Throwable) {
            logx.warn("Failed to restart web server during reload: ${t.message}")
        }
        
        sender?.sendMessage(Lang.colorize(Lang.get("general.reload-complete")))
        logx.info("Reloaded The Endex configuration and tasks.")
    }

    private fun checkAndMigrateConfig(): Boolean {
        return try {
            val got = config.getInt("config-version", -1)
            if (got >= expectedConfigVersion) return false

            val cfgFile = File(dataFolder, "config.yml")
            if (!cfgFile.exists()) return false

            // Backup existing config
            val ts = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val backup = File(dataFolder, "config.yml.bak-$ts")
            try {
                Files.copy(cfgFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logx.info("Backed up existing config.yml to ${backup.name}")
            } catch (t: Throwable) {
                logx.warn("Failed to backup config.yml: ${t.message}")
            }

            // Load defaults from resource
            val fresh = YamlConfiguration()
            val res = this.getResource("config.yml")
            if (res != null) {
                InputStreamReader(res, StandardCharsets.UTF_8).use { reader ->
                    fresh.load(reader)
                }
            } else {
                // No resource? Use current config as baseline
                fresh.load(cfgFile)
            }

            // Merge user values from old into fresh where keys match
            val oldCfg = YamlConfiguration.loadConfiguration(cfgFile)
            for (path in oldCfg.getKeys(true)) {
                if (oldCfg.isConfigurationSection(path)) continue
                if (fresh.contains(path)) {
                    fresh.set(path, oldCfg.get(path))
                }
            }
            // Set new version
            fresh.set("config-version", expectedConfigVersion)
            fresh.options().header("The Endex configuration (auto-migrated on $ts). Some comments may be lost during migration.")

            // Save merged config
            fresh.save(cfgFile)
            logx.info("Migrated config.yml from version $got to $expectedConfigVersion")
            true
        } catch (t: Throwable) {
            logx.error("Config migration failed: ${t.message}")
            false
        }
    }
    
    /**
     * Seed default items to items.yml based on config settings.
     */
    private fun seedDefaultItems() {
        val seedAll = config.getBoolean("seed-all-materials", false)
        val addCurated = config.getBoolean("include-default-important-items", true)
        val blacklist = config.getStringList("blacklist-items").map { it.uppercase() }.toSet()
        
        val materials = if (seedAll) {
            org.bukkit.Material.entries
                .filter { !it.isAir && !it.name.startsWith("LEGACY_") && it.name !in blacklist }
        } else if (addCurated) {
            curatedImportantMaterials().filter { it.name !in blacklist }
        } else {
            emptyList()
        }
        
        for (mat in materials) {
            val basePrice = defaultBasePriceFor(mat)
            itemsConfigManager.addItem(mat, basePrice)
        }
    }
    
    /**
     * Get list of curated important materials for default market.
     */
    private fun curatedImportantMaterials(): List<org.bukkit.Material> = listOf(
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
    ).mapNotNull { org.bukkit.Material.matchMaterial(it) }
    
    /**
     * Get default base price for a material based on its type/rarity.
     */
    private fun defaultBasePriceFor(mat: org.bukkit.Material): Double {
        val name = mat.name
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
    
    /**
     * Extract translated config files to plugins/TheEndex/config_translations/ folder.
     * Only extracts if the folder doesn't exist (first run or folder deleted).
     */
    private fun extractConfigTranslations() {
        val translationsDir = File(dataFolder, "config_translations")
        if (translationsDir.exists()) {
            logx.debug("Config translations folder already exists, skipping extraction")
            return
        }
        
        // List of available translated config files
        val translationFiles = listOf(
            "config_translations/config_en.yml",
            "config_translations/config_zh_CN.yml",
            "config_translations/config_es.yml",
            "config_translations/config_fr.yml",
            "config_translations/config_de.yml",
            "config_translations/config_ja.yml",
            "config_translations/config_ko.yml",
            "config_translations/config_pt.yml",
            "config_translations/config_ru.yml",
            "config_translations/config_pl.yml"
        )
        
        var extracted = 0
        for (resourcePath in translationFiles) {
            try {
                saveResource(resourcePath, false)
                extracted++
            } catch (t: Throwable) {
                logx.debug("Could not extract $resourcePath: ${t.message}")
            }
        }
        
        if (extracted > 0) {
            logx.info("Extracted $extracted translated config files to config_translations/ folder")
            logx.info("To use a different language, copy the desired config file to config.yml")
        }
    }
}
