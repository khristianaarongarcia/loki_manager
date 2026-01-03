package com.werm.plugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

public class WERMPlugin extends JavaPlugin {
    
    private static WERMPlugin instance;
    private ConfigManager configManager;
    private DeliveryConfirmationQueue confirmationQueue;
    private BukkitTask heartbeatTask;
    private BukkitTask deliveryTask;
    private BukkitTask confirmationQueueTask;
    private DeliveryTask deliveryTaskInstance;
    private Metrics metrics;
    
    // Track statistics for bStats
    private int totalDeliveriesThisSession = 0;
    private int totalVerificationsThisSession = 0;
    
    public static final String VERSION = "1.0.3";
    private static final int BSTATS_PLUGIN_ID = 28553; // WERM bStats plugin ID
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default config
        saveDefaultConfig();
        
        // Initialize config manager
        configManager = new ConfigManager(this);
        
        // Initialize delivery confirmation queue (for integrity - Issue #7)
        confirmationQueue = new DeliveryConfirmationQueue(this);
        
        // Initialize bStats metrics
        initializeMetrics();
        
        // Register commands with tab completion
        WERMCommand wermCommand = new WERMCommand(this);
        getCommand("werm").setExecutor(wermCommand);
        getCommand("werm").setTabCompleter(wermCommand);
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        
        // Start heartbeat task if configured
        if (configManager.isConfigured()) {
            startHeartbeat();
            startDeliveryTask();
            startConfirmationQueueTask();
            log("WERM Plugin v" + VERSION + " enabled and connected!");
            log("Debug mode: " + (configManager.isDebugMode() ? "ENABLED" : "disabled"));
            
            // Show pending confirmations if any
            int pending = confirmationQueue.getPendingCount();
            if (pending > 0) {
                log("Found " + pending + " pending delivery confirmation(s) - will retry");
            }
            
            // Test API connectivity on startup (async)
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                testApiConnectivity();
                // Send immediate heartbeat to verify server if needed
                sendImmediateHeartbeat();
            });
        } else {
            getLogger().warning("WERM Plugin enabled but not configured!");
            getLogger().warning("Please set your plugin-token in config.yml");
        }
    }
    
    private void testApiConnectivity() {
        String endpoint = configManager.getApiEndpoint();
        log("Testing API connection: " + endpoint);
        
        try {
            long startTime = System.currentTimeMillis();
            int statusCode = HttpClientManager.getInstance().testEndpoint(endpoint);
            long latency = System.currentTimeMillis() - startTime;
            
            if (statusCode > 0) {
                log("API reachable (HTTP " + statusCode + ", " + latency + "ms)");
            } else {
                getLogger().severe("API connection failed!");
            }
        } catch (Exception e) {
            getLogger().severe("API connection failed: " + e.getMessage());
        }
    }
    
    private void sendImmediateHeartbeat() {
        if (!configManager.isConfigured()) return;
        
        int playerCount = configManager.shouldSendPlayerCount() ? getServer().getOnlinePlayers().size() : 0;
        int maxPlayers = getServer().getMaxPlayers();
        String version = getServer().getVersion();
        
        debug("Sending startup heartbeat...");
        
        VerificationAPI.HeartbeatResult result = VerificationAPI.sendHeartbeat(
            configManager.getApiEndpoint(),
            configManager.getPluginToken(),
            playerCount,
            maxPlayers,
            version,
            VERSION
        );
        
        if (result.success) {
            log("Server heartbeat successful - server verified!");
        } else {
            getLogger().warning("Heartbeat failed (HTTP " + result.statusCode + "): " + result.message);
        }
    }
    
    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (deliveryTask != null) {
            deliveryTask.cancel();
        }
        if (confirmationQueueTask != null) {
            confirmationQueueTask.cancel();
        }
        
        // Process any remaining confirmations before shutdown
        if (confirmationQueue != null && confirmationQueue.getPendingCount() > 0) {
            log("Processing " + confirmationQueue.getPendingCount() + " pending confirmation(s) before shutdown...");
            confirmationQueue.processPendingConfirmations(
                configManager.getApiEndpoint(),
                configManager.getPluginToken()
            );
        }
        
        // Shutdown confirmation queue I/O executor (Improvement 1.4)
        if (confirmationQueue != null) {
            confirmationQueue.shutdown();
        }
        
        // Shutdown HTTP client and connection pool (Improvement 1.1)
        HttpClientManager.getInstance().shutdown();
        
        getLogger().info("WERM Plugin disabled!");
    }
    
    private void startHeartbeat() {
        int intervalTicks = configManager.getHeartbeatInterval() * 20; // Convert seconds to ticks
        
        heartbeatTask = getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                if (!configManager.isConfigured()) return;
                
                int playerCount = configManager.shouldSendPlayerCount() ? getServer().getOnlinePlayers().size() : 0;
                int maxPlayers = getServer().getMaxPlayers();
                String version = getServer().getVersion();
                
                debug("Sending heartbeat (players: " + playerCount + "/" + maxPlayers + ")");
                
                VerificationAPI.HeartbeatResult result = VerificationAPI.sendHeartbeat(
                    configManager.getApiEndpoint(),
                    configManager.getPluginToken(),
                    playerCount,
                    maxPlayers,
                    version,
                    VERSION
                );
                
                if (!result.success) {
                    debug("Heartbeat failed (HTTP " + result.statusCode + "): " + result.message);
                }
            }
        }, 20L, intervalTicks); // Start after 1 second, then repeat
        
        log("Heartbeat started (" + configManager.getHeartbeatInterval() + "s interval)");
    }
    
    private void startDeliveryTask() {
        int intervalTicks = configManager.getDeliveryInterval() * 20; // Convert seconds to ticks
        deliveryTaskInstance = new DeliveryTask(this);
        
        deliveryTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this, 
            deliveryTaskInstance, 
            200L, // Start after 10 seconds
            intervalTicks
        );
        
        log("Delivery polling started (" + configManager.getDeliveryInterval() + "s interval)");
    }
    
    private void startConfirmationQueueTask() {
        // Process pending confirmations every 60 seconds
        confirmationQueueTask = getServer().getScheduler().runTaskTimerAsynchronously(
            this,
            new Runnable() {
                @Override
                public void run() {
                    if (!configManager.isConfigured()) return;
                    confirmationQueue.processPendingConfirmations(
                        configManager.getApiEndpoint(),
                        configManager.getPluginToken()
                    );
                }
            },
            600L, // Start after 30 seconds
            1200L // Repeat every 60 seconds
        );
        debug("Confirmation queue processor started");
    }
    
    /**
     * Log an info message
     */
    public void log(String message) {
        getLogger().info(message);
    }
    
    /**
     * Log a debug message (only if debug mode is enabled)
     */
    public void debug(String message) {
        if (configManager != null && configManager.isDebugMode()) {
            getLogger().info("[DEBUG] " + message);
        }
    }
    
    /**
     * Log a warning message
     */
    public void warn(String message) {
        getLogger().warning(message);
    }
    
    /**
     * Log an error message
     */
    public void error(String message) {
        getLogger().severe(message);
    }
    
    public static WERMPlugin getInstance() {
        return instance;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public DeliveryTask getDeliveryTask() {
        return deliveryTaskInstance;
    }
    
    public DeliveryConfirmationQueue getConfirmationQueue() {
        return confirmationQueue;
    }
    
    /**
     * Initialize bStats metrics with custom charts
     */
    private void initializeMetrics() {
        try {
            metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            
            // Custom chart: Is plugin configured?
            metrics.addCustomChart(new SimplePie("configured", () -> 
                configManager.isConfigured() ? "Yes" : "No"
            ));
            
            // Custom chart: Debug mode enabled?
            metrics.addCustomChart(new SimplePie("debug_mode", () -> 
                configManager.isDebugMode() ? "Enabled" : "Disabled"
            ));
            
            // Custom chart: Heartbeat interval
            metrics.addCustomChart(new SimplePie("heartbeat_interval", () -> {
                int interval = configManager.getHeartbeatInterval();
                if (interval <= 30) return "30s or less";
                if (interval <= 60) return "31-60s";
                if (interval <= 120) return "61-120s";
                return "More than 120s";
            }));
            
            // Custom chart: Delivery interval
            metrics.addCustomChart(new SimplePie("delivery_interval", () -> {
                int interval = configManager.getDeliveryInterval();
                if (interval <= 10) return "10s or less";
                if (interval <= 15) return "11-15s";
                if (interval <= 30) return "16-30s";
                return "More than 30s";
            }));
            
            // Custom chart: Sends player count?
            metrics.addCustomChart(new SimplePie("sends_player_count", () -> 
                configManager.shouldSendPlayerCount() ? "Yes" : "No"
            ));
            
            // Custom chart: Deliveries this session
            metrics.addCustomChart(new SingleLineChart("deliveries", () -> {
                int deliveries = totalDeliveriesThisSession;
                totalDeliveriesThisSession = 0; // Reset after reporting
                return deliveries;
            }));
            
            // Custom chart: Verifications this session
            metrics.addCustomChart(new SingleLineChart("verifications", () -> {
                int verifications = totalVerificationsThisSession;
                totalVerificationsThisSession = 0; // Reset after reporting
                return verifications;
            }));
            
            debug("bStats metrics initialized (Plugin ID: " + BSTATS_PLUGIN_ID + ")");
        } catch (Exception e) {
            debug("Failed to initialize bStats: " + e.getMessage());
        }
    }
    
    /**
     * Track a successful delivery for bStats
     */
    public void trackDelivery() {
        totalDeliveriesThisSession++;
    }
    
    /**
     * Track a successful verification for bStats
     */
    public void trackVerification() {
        totalVerificationsThisSession++;
    }
}
