package com.werm.plugin;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ConfigManager {
    
    private final WERMPlugin plugin;
    
    // Default API endpoint - unified WERM API
    public static final String DEFAULT_API_ENDPOINT = "https://api.wermpay.com/plugin";
    
    // Allowed domains for API endpoints (Issue #12: Security validation)
    private static final Set<String> ALLOWED_DOMAINS = new HashSet<>(Arrays.asList(
        "api.wermpay.com",
        "wermpay.com",
        "werm.vercel.app",
        "wermpay.vercel.app",
        "localhost"  // For development only
    ));
    
    // API settings
    private String pluginToken;
    private String apiEndpoint;
    private String fallbackEndpoint;
    private boolean useFallback;
    private int heartbeatInterval;
    private int deliveryInterval;
    private boolean sendPlayerCount;
    private boolean debugMode;
    
    // Messages
    private String messagePrefix;
    private String verifySuccessMessage;
    private String verifyFailedMessage;
    private String verifyUsageMessage;
    private String verifyProcessingMessage;
    private String alreadyLinkedMessage;
    private String invalidCodeMessage;
    private String codeExpiredMessage;
    private String noPermissionMessage;
    private String tokenNotConfiguredMessage;
    private String deliveryReceivedMessage;
    
    public ConfigManager(WERMPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    public void loadConfig() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        
        // API settings
        pluginToken = config.getString("plugin-token", "");
        
        // API endpoint - use default if not set or empty, with validation (Issue #12)
        String configEndpoint = config.getString("api-endpoint", "");
        if (configEndpoint == null || configEndpoint.isEmpty()) {
            apiEndpoint = DEFAULT_API_ENDPOINT;
        } else {
            String validatedEndpoint = validateAndSanitizeEndpoint(configEndpoint);
            if (validatedEndpoint != null) {
                apiEndpoint = validatedEndpoint;
            } else {
                plugin.getLogger().warning("Invalid API endpoint configured: " + configEndpoint);
                plugin.getLogger().warning("Using default endpoint instead. Endpoint must be HTTPS and from a trusted domain.");
                apiEndpoint = DEFAULT_API_ENDPOINT;
            }
        }
        
        // Fallback endpoint - configurable opt-in (Issue #9)
        useFallback = config.getBoolean("api-fallback.enabled", false);
        String configFallback = config.getString("api-fallback.endpoint", "");
        if (useFallback && configFallback != null && !configFallback.isEmpty()) {
            String validatedFallback = validateAndSanitizeEndpoint(configFallback);
            if (validatedFallback != null) {
                fallbackEndpoint = validatedFallback;
                plugin.getLogger().info("Fallback endpoint configured: " + fallbackEndpoint);
            } else {
                plugin.getLogger().warning("Invalid fallback endpoint: " + configFallback);
                useFallback = false;
                fallbackEndpoint = null;
            }
        } else {
            fallbackEndpoint = null;
        }
        
        heartbeatInterval = config.getInt("heartbeat.interval", 60);
        deliveryInterval = config.getInt("delivery.interval", 15);
        sendPlayerCount = config.getBoolean("heartbeat.send-player-count", true);
        debugMode = config.getBoolean("debug", false);
        
        // Messages
        messagePrefix = colorize(config.getString("messages.prefix", "&8[&6WERM&8] &r"));
        verifySuccessMessage = colorize(config.getString("messages.verify-success", "&aYour Minecraft account has been linked successfully!"));
        verifyFailedMessage = colorize(config.getString("messages.verify-failed", "&cVerification failed: &7%reason%"));
        verifyUsageMessage = colorize(config.getString("messages.verify-usage", "&eUsage: &f/werm verify <code>"));
        verifyProcessingMessage = colorize(config.getString("messages.verify-processing", "&7Verifying your account..."));
        alreadyLinkedMessage = colorize(config.getString("messages.already-linked", "&cThis Minecraft account is already linked to another user."));
        invalidCodeMessage = colorize(config.getString("messages.invalid-code", "&cInvalid verification code. Please check and try again."));
        codeExpiredMessage = colorize(config.getString("messages.code-expired", "&cThis code has expired. Please generate a new one on the website."));
        noPermissionMessage = colorize(config.getString("messages.no-permission", "&cYou don't have permission to use this command."));
        tokenNotConfiguredMessage = colorize(config.getString("messages.token-not-configured", "&cPlugin not configured! Please ask the server admin to set up WERM."));
        deliveryReceivedMessage = colorize(config.getString("messages.delivery-received", "&aYou received: &e{product} &ax{quantity}"));
    }
    
    private String colorize(String message) {
        if (message == null) return "";
        return message.replace("&", "§");
    }
    
    /**
     * Validate and sanitize an API endpoint URL (Issue #12)
     * - Must be HTTPS (except localhost for dev)
     * - Must be from an allowed domain
     * - Must be a valid URL format
     * 
     * @param endpoint The endpoint URL to validate
     * @return The validated endpoint, or null if invalid
     */
    private String validateAndSanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return null;
        }
        
        // Trim whitespace
        endpoint = endpoint.trim();
        
        try {
            URL url = new URL(endpoint);
            String protocol = url.getProtocol().toLowerCase();
            String host = url.getHost().toLowerCase();
            
            // Must be HTTPS (allow HTTP only for localhost in dev)
            if (!protocol.equals("https")) {
                if (protocol.equals("http") && host.equals("localhost")) {
                    plugin.getLogger().warning("Using HTTP for localhost - only for development!");
                } else {
                    plugin.getLogger().severe("Security: API endpoint must use HTTPS! Got: " + protocol);
                    return null;
                }
            }
            
            // Check if domain is in allowed list
            boolean domainAllowed = false;
            for (String allowed : ALLOWED_DOMAINS) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) {
                    domainAllowed = true;
                    break;
                }
            }
            
            if (!domainAllowed) {
                plugin.getLogger().severe("Security: API endpoint domain not trusted: " + host);
                plugin.getLogger().severe("Allowed domains: " + ALLOWED_DOMAINS);
                return null;
            }
            
            // Reconstruct URL to ensure it's normalized
            return url.toString();
            
        } catch (MalformedURLException e) {
            plugin.getLogger().severe("Invalid API endpoint URL format: " + endpoint);
            return null;
        }
    }
    
    public boolean isConfigured() {
        return pluginToken != null && !pluginToken.isEmpty() && pluginToken.startsWith("werm_");
    }
    
    // Getters
    public String getPluginToken() { return pluginToken; }
    public String getApiEndpoint() { return apiEndpoint; }
    public String getFallbackEndpoint() { return fallbackEndpoint; }
    public boolean isUseFallback() { return useFallback && fallbackEndpoint != null; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public int getDeliveryInterval() { return deliveryInterval; }
    public boolean shouldSendPlayerCount() { return sendPlayerCount; }
    public boolean isDebugMode() { return debugMode; }
    public String getMessagePrefix() { return messagePrefix; }
    public String getVerifySuccessMessage() { return verifySuccessMessage; }
    public String getVerifyFailedMessage() { return verifyFailedMessage; }
    public String getVerifyUsageMessage() { return verifyUsageMessage; }
    public String getVerifyProcessingMessage() { return verifyProcessingMessage; }
    public String getAlreadyLinkedMessage() { return alreadyLinkedMessage; }
    public String getInvalidCodeMessage() { return invalidCodeMessage; }
    public String getCodeExpiredMessage() { return codeExpiredMessage; }
    public String getNoPermissionMessage() { return noPermissionMessage; }
    public String getTokenNotConfiguredMessage() { return tokenNotConfiguredMessage; }
    public String getDeliveryReceivedMessage() { return deliveryReceivedMessage; }
}
