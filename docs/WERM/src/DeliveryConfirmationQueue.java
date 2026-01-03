package com.werm.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local queue for delivery confirmations to ensure integrity.
 * If API confirmation fails, deliveries are stored locally and retried.
 * This prevents:
 * - Double delivery if API times out but actually succeeded
 * - Lost confirmations if network is unstable
 * - Race conditions between confirmation and re-delivery
 * 
 * Improvements:
 * - 1.3: Batch confirmation support - batches multiple confirmations into single API call
 * - 1.4: Async file I/O - uses debounced writes to prevent I/O blocking
 * 
 * Issue #7: Delivery Confirmation Integrity
 */
public class DeliveryConfirmationQueue {
    
    private final WERMPlugin plugin;
    private final File queueFile;
    private final Gson gson;
    private final ConcurrentLinkedQueue<PendingConfirmation> pendingQueue;
    private static final int MAX_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 30000; // 30 seconds between retries
    
    // Improvement 1.3: Batch processing configuration
    private static final int BATCH_SIZE = 10; // Max confirmations per batch
    private static final long BATCH_WAIT_MS = 5000; // Wait 5 seconds to collect batch
    
    // Improvement 1.4: Async file I/O
    private final ScheduledExecutorService ioExecutor;
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    private static final long SAVE_DEBOUNCE_MS = 2000; // Debounce saves by 2 seconds
    
    /**
     * Represents a pending delivery confirmation
     */
    public static class PendingConfirmation {
        String deliveryId;
        String deliveryToken;
        int commandsExecuted;
        boolean isFailure;
        String errorMessage;
        long timestamp;
        int retryCount;
        long lastRetryTime;
        
        public PendingConfirmation() {}
        
        public PendingConfirmation(String deliveryId, String deliveryToken, int commandsExecuted, 
                                   boolean isFailure, String errorMessage) {
            this.deliveryId = deliveryId;
            this.deliveryToken = deliveryToken;
            this.commandsExecuted = commandsExecuted;
            this.isFailure = isFailure;
            this.errorMessage = errorMessage;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
            this.lastRetryTime = 0;
        }
        
        public String getDeliveryId() { return deliveryId; }
        public String getDeliveryToken() { return deliveryToken; }
        public int getCommandsExecuted() { return commandsExecuted; }
        public boolean isFailure() { return isFailure; }
        public String getErrorMessage() { return errorMessage; }
        public long getTimestamp() { return timestamp; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetry() { 
            this.retryCount++; 
            this.lastRetryTime = System.currentTimeMillis();
        }
        public boolean canRetry() {
            return retryCount < MAX_RETRIES && 
                   (System.currentTimeMillis() - lastRetryTime) >= RETRY_DELAY_MS;
        }
        public boolean isExpired() {
            // Expire after 24 hours regardless of retry count
            return (System.currentTimeMillis() - timestamp) > 86400000;
        }
    }
    
    public DeliveryConfirmationQueue(WERMPlugin plugin) {
        this.plugin = plugin;
        this.queueFile = new File(plugin.getDataFolder(), "pending_confirmations.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.pendingQueue = new ConcurrentLinkedQueue<>();
        
        // Improvement 1.4: Single-threaded executor for async I/O
        this.ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WERM-IO");
            t.setDaemon(true);
            return t;
        });
        
        loadQueue();
    }
    
    /**
     * Shutdown the async I/O executor (call on plugin disable)
     */
    public void shutdown() {
        // Force save any pending changes synchronously before shutdown
        saveQueueSync();
        
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
        }
    }
    
    /**
     * Queue a successful delivery confirmation
     */
    public void queueConfirmation(String deliveryId, String deliveryToken, int commandsExecuted) {
        PendingConfirmation confirmation = new PendingConfirmation(
            deliveryId, deliveryToken, commandsExecuted, false, null
        );
        pendingQueue.add(confirmation);
        plugin.debug("Queued confirmation for delivery: " + redactId(deliveryId));
        saveQueueAsync(); // Improvement 1.4: Async save
    }
    
    /**
     * Queue a failed delivery confirmation
     */
    public void queueFailure(String deliveryId, String deliveryToken, String errorMessage) {
        PendingConfirmation confirmation = new PendingConfirmation(
            deliveryId, deliveryToken, 0, true, errorMessage
        );
        pendingQueue.add(confirmation);
        plugin.debug("Queued failure for delivery: " + redactId(deliveryId));
        saveQueueAsync(); // Improvement 1.4: Async save
    }
    
    /**
     * Check if a delivery is already in the queue (pending confirmation)
     */
    public boolean isPending(String deliveryId) {
        for (PendingConfirmation pc : pendingQueue) {
            if (pc.getDeliveryId().equals(deliveryId)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Remove a confirmation from the queue (after successful API confirmation)
     */
    public void remove(String deliveryId) {
        Iterator<PendingConfirmation> it = pendingQueue.iterator();
        while (it.hasNext()) {
            if (it.next().getDeliveryId().equals(deliveryId)) {
                it.remove();
                plugin.debug("Removed from confirmation queue: " + redactId(deliveryId));
                break;
            }
        }
        saveQueueAsync(); // Improvement 1.4: Async save
    }
    
    /**
     * Remove multiple confirmations from the queue (batch removal)
     * Improvement 1.3: Batch support
     */
    public void removeAll(List<String> deliveryIds) {
        if (deliveryIds.isEmpty()) return;
        
        Iterator<PendingConfirmation> it = pendingQueue.iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (deliveryIds.contains(it.next().getDeliveryId())) {
                it.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            plugin.debug("Batch removed " + removed + " confirmations from queue");
            saveQueueAsync();
        }
    }
    
    /**
     * Process all pending confirmations using batch API (Improvement 1.3)
     * Returns number of successfully processed confirmations
     */
    public int processPendingConfirmations(String endpoint, String pluginToken) {
        if (pendingQueue.isEmpty()) {
            return 0;
        }
        
        plugin.debug("Processing " + pendingQueue.size() + " pending confirmation(s)...");
        
        // Collect confirmations ready for retry
        List<PendingConfirmation> readyForRetry = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        
        for (PendingConfirmation pc : pendingQueue) {
            if (pc.isExpired()) {
                plugin.warn("Confirmation expired, removing: " + redactId(pc.getDeliveryId()));
                expired.add(pc.getDeliveryId());
            } else if (pc.canRetry()) {
                readyForRetry.add(pc);
            }
        }
        
        // Remove expired
        if (!expired.isEmpty()) {
            removeAll(expired);
        }
        
        if (readyForRetry.isEmpty()) {
            return 0;
        }
        
        // Improvement 1.3: Use batch API if multiple confirmations
        if (readyForRetry.size() >= 2) {
            return processBatch(endpoint, pluginToken, readyForRetry);
        }
        
        // Single confirmation - use individual API
        return processSingle(endpoint, pluginToken, readyForRetry.get(0));
    }
    
    /**
     * Process a batch of confirmations in a single API call (Improvement 1.3)
     */
    private int processBatch(String endpoint, String pluginToken, List<PendingConfirmation> confirmations) {
        plugin.debug("Using batch API for " + confirmations.size() + " confirmations");
        
        // Build batch request
        List<VerificationAPI.BatchConfirmation> batch = new ArrayList<>();
        for (PendingConfirmation pc : confirmations) {
            batch.add(new VerificationAPI.BatchConfirmation(
                pc.getDeliveryId(),
                pc.getDeliveryToken(),
                pc.getCommandsExecuted(),
                pc.isFailure(),
                pc.getErrorMessage()
            ));
        }
        
        // Send batch request
        VerificationAPI.BatchConfirmResult result = VerificationAPI.batchConfirmDeliveries(endpoint, pluginToken, batch);
        
        if (!result.success) {
            // Batch failed - increment retry count for all
            for (PendingConfirmation pc : confirmations) {
                pc.incrementRetry();
                if (pc.getRetryCount() >= MAX_RETRIES) {
                    plugin.warn("Max retries reached for delivery: " + redactId(pc.getDeliveryId()));
                }
            }
            return 0;
        }
        
        // Process individual results
        List<String> succeeded = new ArrayList<>();
        for (PendingConfirmation pc : confirmations) {
            Boolean individualSuccess = result.individualResults.get(pc.getDeliveryId());
            if (individualSuccess != null && individualSuccess) {
                succeeded.add(pc.getDeliveryId());
            } else {
                pc.incrementRetry();
                plugin.debug("Retry " + pc.getRetryCount() + "/" + MAX_RETRIES + 
                           " for delivery: " + redactId(pc.getDeliveryId()));
            }
        }
        
        // Remove succeeded
        if (!succeeded.isEmpty()) {
            removeAll(succeeded);
            plugin.log("Batch processed " + succeeded.size() + " confirmation(s)");
        }
        
        return succeeded.size();
    }
    
    /**
     * Process a single confirmation (legacy method)
     */
    private int processSingle(String endpoint, String pluginToken, PendingConfirmation pc) {
        boolean success;
        if (pc.isFailure()) {
            success = VerificationAPI.failDelivery(
                endpoint, pluginToken,
                pc.getDeliveryId(), pc.getDeliveryToken(),
                pc.getErrorMessage()
            );
        } else {
            success = VerificationAPI.confirmDelivery(
                endpoint, pluginToken,
                pc.getDeliveryId(), pc.getDeliveryToken(),
                pc.getCommandsExecuted()
            );
        }
        
        if (success) {
            remove(pc.getDeliveryId());
            plugin.debug("Successfully confirmed delivery: " + redactId(pc.getDeliveryId()));
            return 1;
        } else {
            pc.incrementRetry();
            plugin.debug("Retry " + pc.getRetryCount() + "/" + MAX_RETRIES + 
                       " for delivery: " + redactId(pc.getDeliveryId()));
            
            if (pc.getRetryCount() >= MAX_RETRIES) {
                plugin.warn("Max retries reached for delivery: " + redactId(pc.getDeliveryId()) + 
                          " - Please check API connectivity");
            }
            return 0;
        }
    }
    
    /**
     * Legacy process method - now uses batch processing internally
     * Returns number of successfully processed confirmations
     * @deprecated Use processPendingConfirmations instead
     */
    @Deprecated
    public int processPendingConfirmationsLegacy(String endpoint, String pluginToken) {
        if (pendingQueue.isEmpty()) {
            return 0;
        }
        
        plugin.debug("Processing " + pendingQueue.size() + " pending confirmation(s) (legacy)...");
        
        List<String> toRemove = new ArrayList<>();
        int processed = 0;
        
        for (PendingConfirmation pc : pendingQueue) {
            // Skip if already expired
            if (pc.isExpired()) {
                plugin.warn("Confirmation expired, removing: " + redactId(pc.getDeliveryId()));
                toRemove.add(pc.getDeliveryId());
                continue;
            }
            
            // Skip if not ready for retry
            if (!pc.canRetry()) {
                continue;
            }
            
            boolean success;
            if (pc.isFailure()) {
                success = VerificationAPI.failDelivery(
                    endpoint, pluginToken,
                    pc.getDeliveryId(), pc.getDeliveryToken(),
                    pc.getErrorMessage()
                );
            } else {
                success = VerificationAPI.confirmDelivery(
                    endpoint, pluginToken,
                    pc.getDeliveryId(), pc.getDeliveryToken(),
                    pc.getCommandsExecuted()
                );
            }
            
            if (success) {
                toRemove.add(pc.getDeliveryId());
                processed++;
                plugin.debug("Successfully confirmed delivery: " + redactId(pc.getDeliveryId()));
            } else {
                pc.incrementRetry();
                plugin.debug("Retry " + pc.getRetryCount() + "/" + MAX_RETRIES + 
                           " for delivery: " + redactId(pc.getDeliveryId()));
                
                if (pc.getRetryCount() >= MAX_RETRIES) {
                    plugin.warn("Max retries reached for delivery: " + redactId(pc.getDeliveryId()) + 
                              " - Please check API connectivity");
                }
            }
        }
        
        // Remove processed/expired items
        for (String id : toRemove) {
            remove(id);
        }
        
        if (processed > 0) {
            plugin.log("Processed " + processed + " pending confirmation(s)");
        }
        
        return processed;
    }
    
    /**
     * Get count of pending confirmations
     */
    public int getPendingCount() {
        return pendingQueue.size();
    }
    
    /**
     * Load queue from disk
     */
    private void loadQueue() {
        if (!queueFile.exists()) {
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(queueFile), StandardCharsets.UTF_8))) {
            
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            
            if (content.length() == 0) {
                return;
            }
            
            JsonArray array = JsonParser.parseString(content.toString()).getAsJsonArray();
            for (JsonElement element : array) {
                PendingConfirmation pc = gson.fromJson(element, PendingConfirmation.class);
                if (pc != null && !pc.isExpired()) {
                    pendingQueue.add(pc);
                }
            }
            
            if (!pendingQueue.isEmpty()) {
                plugin.log("Loaded " + pendingQueue.size() + " pending confirmation(s) from disk");
            }
        } catch (Exception e) {
            plugin.warn("Failed to load confirmation queue: " + e.getMessage());
        }
    }
    
    /**
     * Save queue to disk asynchronously with debouncing (Improvement 1.4)
     * Prevents I/O blocking by scheduling writes and debouncing rapid changes
     */
    private void saveQueueAsync() {
        // Debounce: If a save is already scheduled, don't schedule another
        if (saveScheduled.compareAndSet(false, true)) {
            ioExecutor.schedule(() -> {
                saveScheduled.set(false);
                saveQueueSync();
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * Save queue to disk synchronously (used for shutdown and immediate saves)
     */
    private synchronized void saveQueueSync() {
        try {
            // Ensure directory exists
            if (!queueFile.getParentFile().exists()) {
                queueFile.getParentFile().mkdirs();
            }
            
            JsonArray array = new JsonArray();
            for (PendingConfirmation pc : pendingQueue) {
                array.add(gson.toJsonTree(pc));
            }
            
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(queueFile), StandardCharsets.UTF_8))) {
                writer.write(gson.toJson(array));
            }
        } catch (Exception e) {
            plugin.warn("Failed to save confirmation queue: " + e.getMessage());
        }
    }
    
    /**
     * Save queue to disk (legacy - now calls async version)
     * @deprecated Use saveQueueAsync for non-blocking saves
     */
    @Deprecated
    private void saveQueue() {
        saveQueueAsync();
    }
    
    /**
     * Redact delivery ID for logging (security)
     */
    private String redactId(String id) {
        if (id == null || id.length() <= 8) return "***";
        return id.substring(0, 8) + "...";
    }
}
