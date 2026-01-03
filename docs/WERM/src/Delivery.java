package com.werm.plugin;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a delivery from the WERM API
 */
public class Delivery {
    
    @SerializedName("$id")
    private String id;
    
    @SerializedName("deliveryId")
    private String deliveryId;
    
    @SerializedName("orderId")
    private String orderId;
    
    @SerializedName("orderItemId")
    private String orderItemId;
    
    @SerializedName("productId")
    private String productId;
    
    @SerializedName("productName")
    private String productName;
    
    @SerializedName("playerId")
    private String playerId;
    
    @SerializedName("playerName")
    private String playerName;
    
    @SerializedName("playerUuid")
    private String playerUuid;
    
    @SerializedName("commands")
    private String[] commands;
    
    @SerializedName("deliveryToken")
    private String deliveryToken;
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("requireOnline")
    private boolean requireOnline;
    
    @SerializedName("quantity")
    private int quantity;
    
    @SerializedName("createdAt")
    private String createdAt;
    
    // Getters
    
    public String getId() {
        // Prefer deliveryId, fallback to $id
        return deliveryId != null ? deliveryId : id;
    }
    
    public String getDeliveryId() {
        return deliveryId != null ? deliveryId : id;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public String getOrderItemId() {
        return orderItemId;
    }
    
    public String getProductId() {
        return productId;
    }
    
    public String getProductName() {
        return productName != null ? productName : "Unknown Product";
    }
    
    public String getPlayerId() {
        return playerId;
    }
    
    public String getPlayerName() {
        return playerName != null ? playerName : "";
    }
    
    public String getPlayerUuid() {
        return playerUuid != null ? playerUuid : "";
    }
    
    public String[] getCommands() {
        return commands != null ? commands : new String[0];
    }
    
    public String getDeliveryToken() {
        return deliveryToken;
    }
    
    public String getStatus() {
        return status;
    }
    
    public boolean isRequireOnline() {
        return requireOnline;
    }
    
    public int getQuantity() {
        return quantity > 0 ? quantity : 1;
    }
    
    /**
     * Sanitize a string to contain only safe characters for command execution.
     * Only allows alphanumeric characters, underscores, and hyphens.
     * This prevents command injection via malicious player names.
     * 
     * @param input The string to sanitize
     * @return Sanitized string with only safe characters
     */
    private String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // Only allow alphanumeric, underscore, and hyphen (valid MC name chars + hyphen for UUIDs)
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "");
    }
    
    /**
     * Sanitize a UUID string to ensure it's valid format
     * @param uuid The UUID string to sanitize
     * @return Sanitized UUID or empty string
     */
    private String sanitizeUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return "";
        }
        // UUID format: 8-4-4-4-12 hex characters with optional hyphens
        String sanitized = uuid.replaceAll("[^a-fA-F0-9\\-]", "");
        // Validate it looks like a UUID (32 hex chars, optionally with hyphens)
        String stripped = sanitized.replace("-", "");
        if (stripped.length() == 32) {
            return sanitized;
        }
        return "";
    }
    
    /**
     * Process a command by replacing placeholders.
     * All player-controlled values are sanitized to prevent command injection.
     */
    public String processCommand(String command) {
        return command
            .replace("{player}", sanitize(getPlayerName()))
            .replace("{player_name}", sanitize(getPlayerName()))
            .replace("{uuid}", sanitizeUuid(getPlayerUuid()))
            .replace("{player_uuid}", sanitizeUuid(getPlayerUuid()))
            .replace("{quantity}", String.valueOf(getQuantity()))
            .replace("{product_id}", sanitize(productId))
            .replace("{product_name}", sanitize(getProductName()))
            .replace("{order_id}", sanitize(orderId))
            .replace("{delivery_id}", sanitize(id));
    }
    
    @Override
    public String toString() {
        return "Delivery{id=" + id + ", product=" + productName + ", player=" + playerName + "}";
    }
}
