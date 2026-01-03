package com.werm.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task that polls for pending deliveries and executes them.
 * 
 * Improvement 1.2: Smart Polling with Exponential Backoff
 * - Dynamically adjusts polling interval based on activity
 * - Increases interval when no deliveries are found (exponential backoff)
 * - Resets to base interval when deliveries are found or player joins
 * - Reduces API load during low activity periods
 */
public class DeliveryTask implements Runnable {
    
    private final WERMPlugin plugin;
    private final Set<String> processingDeliveries = new HashSet<>();
    private final CommandValidator commandValidator;
    
    // Smart polling state (Improvement 1.2)
    private int consecutiveEmptyResponses = 0;
    private long lastDeliveryTime = System.currentTimeMillis();
    private int currentBackoffMultiplier = 1;
    
    // Backoff configuration
    private static final int MAX_BACKOFF_MULTIPLIER = 4; // Max 4x the base interval (60s max if base is 15s)
    private static final int EMPTY_RESPONSES_BEFORE_BACKOFF = 3; // Start backoff after 3 empty responses
    private static final long ACTIVITY_RESET_THRESHOLD_MS = 300000; // Reset backoff after 5 min of activity
    
    public DeliveryTask(WERMPlugin plugin) {
        this.plugin = plugin;
        this.commandValidator = new CommandValidator();
    }
    
    @Override
    public void run() {
        ConfigManager config = plugin.getConfigManager();
        
        if (!config.isConfigured()) {
            return;
        }
        
        // Check if we should skip this poll due to backoff
        if (shouldSkipPoll()) {
            plugin.debug("Skipping poll (backoff: " + currentBackoffMultiplier + "x)");
            return;
        }
        
        plugin.debug("Polling for pending deliveries...");
        
        // Fetch pending deliveries (async)
        List<Delivery> deliveries = VerificationAPI.getPendingDeliveries(
            config.getApiEndpoint(),
            config.getPluginToken()
        );
        
        if (deliveries.isEmpty()) {
            handleEmptyResponse();
            plugin.debug("No pending deliveries" + (currentBackoffMultiplier > 1 ? " (backoff: " + currentBackoffMultiplier + "x)" : ""));
            return;
        }
        
        // Reset backoff on successful delivery fetch
        resetBackoff();
        
        plugin.log("Found " + deliveries.size() + " pending deliver" + (deliveries.size() == 1 ? "y" : "ies"));
        
        for (Delivery delivery : deliveries) {
            processDelivery(delivery);
        }
    }
    
    /**
     * Smart polling: Check if we should skip this poll cycle (Improvement 1.2)
     */
    private boolean shouldSkipPoll() {
        if (currentBackoffMultiplier <= 1) {
            return false;
        }
        
        // Skip based on backoff multiplier
        // If multiplier is 2, skip every other poll
        // If multiplier is 4, skip 3 out of 4 polls
        return (System.currentTimeMillis() / 1000) % currentBackoffMultiplier != 0;
    }
    
    /**
     * Handle empty response - increase backoff (Improvement 1.2)
     */
    private void handleEmptyResponse() {
        consecutiveEmptyResponses++;
        
        // Start exponential backoff after threshold
        if (consecutiveEmptyResponses >= EMPTY_RESPONSES_BEFORE_BACKOFF) {
            if (currentBackoffMultiplier < MAX_BACKOFF_MULTIPLIER) {
                currentBackoffMultiplier = Math.min(currentBackoffMultiplier * 2, MAX_BACKOFF_MULTIPLIER);
                plugin.debug("Increasing backoff to " + currentBackoffMultiplier + "x after " + consecutiveEmptyResponses + " empty responses");
            }
        }
    }
    
    /**
     * Reset backoff to base interval (Improvement 1.2)
     */
    public void resetBackoff() {
        if (currentBackoffMultiplier > 1 || consecutiveEmptyResponses > 0) {
            plugin.debug("Resetting polling backoff (was " + currentBackoffMultiplier + "x)");
        }
        consecutiveEmptyResponses = 0;
        currentBackoffMultiplier = 1;
        lastDeliveryTime = System.currentTimeMillis();
    }
    
    /**
     * Force immediate poll on next cycle (called on player join)
     * Improvement 1.2: Instant check when player joins
     */
    public void triggerImmediatePoll() {
        resetBackoff();
    }
    
    /**
     * Get current backoff multiplier for debugging
     */
    public int getCurrentBackoffMultiplier() {
        return currentBackoffMultiplier;
    }
    
    /**
     * Process deliveries for a specific player (called on join)
     */
    public void processForPlayer(Player player) {
        ConfigManager config = plugin.getConfigManager();
        
        if (!config.isConfigured()) {
            return;
        }
        
        plugin.debug("Checking deliveries for player: " + player.getName());
        
        // Reset backoff when player joins (Improvement 1.2)
        triggerImmediatePoll();
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                List<Delivery> deliveries = VerificationAPI.getPendingDeliveries(
                    config.getApiEndpoint(),
                    config.getPluginToken()
                );
                
                String playerUuid = player.getUniqueId().toString();
                String playerName = player.getName();
                
                int count = 0;
                for (Delivery delivery : deliveries) {
                    // Check if this delivery is for this player
                    boolean isForPlayer = 
                        (delivery.getPlayerUuid() != null && delivery.getPlayerUuid().equalsIgnoreCase(playerUuid)) ||
                        (delivery.getPlayerName() != null && delivery.getPlayerName().equalsIgnoreCase(playerName));
                    
                    if (isForPlayer) {
                        count++;
                        processDelivery(delivery);
                    }
                }
                
                if (count > 0) {
                    plugin.debug("Found " + count + " pending deliver" + (count == 1 ? "y" : "ies") + " for " + player.getName());
                }
            }
        });
    }
    
    private void processDelivery(Delivery delivery) {
        String deliveryId = delivery.getId();
        
        // Prevent duplicate processing
        synchronized (processingDeliveries) {
            if (processingDeliveries.contains(deliveryId)) {
                plugin.debug("Skipping delivery " + deliveryId + " - already processing");
                return;
            }
            processingDeliveries.add(deliveryId);
        }
        
        // Check if this delivery is already pending confirmation (Issue #7)
        DeliveryConfirmationQueue queue = plugin.getConfirmationQueue();
        if (queue != null && queue.isPending(deliveryId)) {
            plugin.debug("Skipping delivery " + deliveryId + " - pending confirmation");
            synchronized (processingDeliveries) {
                processingDeliveries.remove(deliveryId);
            }
            return;
        }
        
        // Check if player needs to be online
        if (delivery.isRequireOnline()) {
            Player player = findPlayer(delivery);
            if (player == null || !player.isOnline()) {
                plugin.debug("Skipping delivery " + deliveryId + " - player " + delivery.getPlayerName() + " not online");
                synchronized (processingDeliveries) {
                    processingDeliveries.remove(deliveryId);
                }
                return;
            }
        }
        
        // Execute delivery on main thread
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                executeDelivery(delivery);
            }
        });
    }
    
    private void executeDelivery(Delivery delivery) {
        String deliveryId = delivery.getId();
        String[] commands = delivery.getCommands();
        int executedCount = 0;
        StringBuilder errorLog = new StringBuilder();
        
        plugin.debug("Executing delivery " + deliveryId + " for " + delivery.getPlayerName() + 
            " - Product: " + delivery.getProductName() + " x" + delivery.getQuantity() + 
            " (" + commands.length + " command" + (commands.length == 1 ? "" : "s") + ")");
        
        for (String cmdTemplate : commands) {
            try {
                // Process placeholders
                String command = delivery.processCommand(cmdTemplate);
                
                // Validate command before execution (security check)
                CommandValidator.ValidationResult validation = commandValidator.validate(command);
                if (!validation.isSuccess()) {
                    errorLog.append("Blocked: ").append(validation.getReason()).append(" [").append(command).append("]; ");
                    plugin.warn("Command blocked by security validator: " + command + " - " + validation.getReason());
                    continue;
                }
                
                plugin.debug("Running command: " + command);
                
                // Execute as console
                boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                
                if (success) {
                    executedCount++;
                } else {
                    errorLog.append("Command returned false: ").append(command).append("; ");
                }
            } catch (Exception e) {
                errorLog.append("Exception: ").append(e.getMessage()).append("; ");
                plugin.warn("Command failed: " + cmdTemplate + " - " + e.getMessage());
            }
        }
        
        // Confirm or fail delivery (async)
        final int finalExecutedCount = executedCount;
        final String finalErrorLog = errorLog.toString();
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                ConfigManager config = plugin.getConfigManager();
                DeliveryConfirmationQueue queue = plugin.getConfirmationQueue();
                
                try {
                    if (finalExecutedCount == commands.length) {
                        // All commands succeeded - add to queue first (Issue #7: ensures integrity)
                        if (queue != null) {
                            queue.queueConfirmation(deliveryId, delivery.getDeliveryToken(), finalExecutedCount);
                        }
                        
                        // Try immediate confirmation
                        boolean confirmed = VerificationAPI.confirmDelivery(
                            config.getApiEndpoint(),
                            config.getPluginToken(),
                            deliveryId,
                            delivery.getDeliveryToken(),
                            finalExecutedCount
                        );
                        
                        if (confirmed) {
                            // Remove from queue on success
                            if (queue != null) {
                                queue.remove(deliveryId);
                            }
                            plugin.log("✓ Delivered: " + delivery.getProductName() + " x" + delivery.getQuantity() + 
                                " to " + delivery.getPlayerName() + " [" + deliveryId.substring(0, 8) + "]");
                            
                            // Track for bStats
                            plugin.trackDelivery();
                            
                            // Notify player
                            notifyPlayer(delivery);
                        } else {
                            // Will be retried by queue processor
                            plugin.warn("Delivery confirmation queued for retry: " + deliveryId.substring(0, 8));
                        }
                    } else {
                        // Some commands failed - add to queue first
                        String errorMsg = "Partial execution: " + finalExecutedCount + "/" + commands.length + ". " + finalErrorLog;
                        if (queue != null) {
                            queue.queueFailure(deliveryId, delivery.getDeliveryToken(), errorMsg);
                        }
                        
                        // Try immediate failure report
                        boolean reported = VerificationAPI.failDelivery(
                            config.getApiEndpoint(),
                            config.getPluginToken(),
                            deliveryId,
                            delivery.getDeliveryToken(),
                            errorMsg
                        );
                        
                        if (reported) {
                            if (queue != null) {
                                queue.remove(deliveryId);
                            }
                        }
                        plugin.warn("✗ Delivery failed: " + deliveryId + " - " + finalErrorLog);
                    }
                } finally {
                    synchronized (processingDeliveries) {
                        processingDeliveries.remove(deliveryId);
                    }
                }
            }
        });
    }
    
    private Player findPlayer(Delivery delivery) {
        // Try UUID first
        if (delivery.getPlayerUuid() != null && !delivery.getPlayerUuid().isEmpty()) {
            try {
                UUID uuid = UUID.fromString(delivery.getPlayerUuid());
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    return player;
                }
            } catch (IllegalArgumentException e) {
                // Invalid UUID, try by name
            }
        }
        
        // Try by name
        if (delivery.getPlayerName() != null && !delivery.getPlayerName().isEmpty()) {
            return Bukkit.getPlayer(delivery.getPlayerName());
        }
        
        return null;
    }
    
    private void notifyPlayer(final Delivery delivery) {
        // Run on main thread
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Player player = findPlayer(delivery);
                if (player != null && player.isOnline()) {
                    ConfigManager config = plugin.getConfigManager();
                    String message = config.getDeliveryReceivedMessage()
                        .replace("{product}", delivery.getProductName())
                        .replace("{quantity}", String.valueOf(delivery.getQuantity()));
                    player.sendMessage(config.getMessagePrefix() + message);
                }
            }
        });
    }
}
