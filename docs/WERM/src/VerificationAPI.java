package com.werm.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VerificationAPI {
    
    private static final Gson gson = new Gson();
    private static final JsonParser jsonParser = new JsonParser();
    
    // Project ID - auto-discovered from API or falls back to default
    private static String appwriteProjectId = null;
    private static final String DEFAULT_PROJECT_ID = "6941d6fd00242272bb37";
    
    // Track which endpoint is working
    private static String workingEndpoint = null;
    private static long lastEndpointCheck = 0;
    private static final long ENDPOINT_CHECK_INTERVAL = 300000; // 5 minutes
    
    /**
     * Initialize API settings by auto-discovering project ID
     * Called during plugin startup
     */
    public static void initialize(String endpoint, String pluginToken) {
        try {
            // Try to get project ID from API settings endpoint
            String discoveredId = discoverProjectId(endpoint, pluginToken);
            if (discoveredId != null && !discoveredId.isEmpty()) {
                appwriteProjectId = discoveredId;
                WERMPlugin.getInstance().debug("Auto-discovered Project ID: " + redactId(appwriteProjectId));
            } else {
                appwriteProjectId = DEFAULT_PROJECT_ID;
                WERMPlugin.getInstance().debug("Using default Project ID");
            }
        } catch (Exception e) {
            appwriteProjectId = DEFAULT_PROJECT_ID;
            WERMPlugin.getInstance().debug("Project ID discovery failed, using default");
        }
    }
    
    /**
     * Discover the Appwrite Project ID from API (using OkHttp)
     */
    private static String discoverProjectId(String endpoint, String pluginToken) {
        HttpClientManager httpClient = HttpClientManager.getInstance();
        
        try {
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "get-settings");
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, DEFAULT_PROJECT_ID)) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonResponse = jsonParser.parse(responseBody).getAsJsonObject();
                    
                    // Check for projectId in response
                    if (jsonResponse.has("platform") && jsonResponse.getAsJsonObject("platform").has("projectId")) {
                        return jsonResponse.getAsJsonObject("platform").get("projectId").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            // Silent failure - will use default
        }
        
        return null;
    }
    
    /**
     * Get the current project ID (auto-discovered or default)
     */
    public static String getProjectId() {
        return appwriteProjectId != null ? appwriteProjectId : DEFAULT_PROJECT_ID;
    }
    
    /**
     * Redact sensitive data for logging
     */
    private static String redactToken(String token) {
        if (token == null || token.length() < 12) {
            return "[REDACTED]";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }
    
    /**
     * Redact ID for logging (show first and last few chars)
     */
    private static String redactId(String id) {
        if (id == null || id.length() < 8) {
            return "[REDACTED]";
        }
        return id.substring(0, 4) + "..." + id.substring(id.length() - 4);
    }
    
    /**
     * Redact UUID for logging
     */
    private static String redactUuid(String uuid) {
        if (uuid == null || uuid.length() < 16) {
            return "[REDACTED]";
        }
        return uuid.substring(0, 8) + "-****-****-****-" + uuid.substring(uuid.length() - 12);
    }
    
    /**
     * Debug log with automatic sensitive data redaction
     */
    private static void debugLog(String message) {
        WERMPlugin.getInstance().debug(message);
    }
    
    /**
     * Get the best working endpoint, with fallback support
     */
    public static String getWorkingEndpoint(String primaryEndpoint, String fallbackEndpoint) {
        long now = System.currentTimeMillis();
        
        // If we have a working endpoint and haven't checked recently, use it
        if (workingEndpoint != null && (now - lastEndpointCheck) < ENDPOINT_CHECK_INTERVAL) {
            return workingEndpoint;
        }
        
        HttpClientManager httpClient = HttpClientManager.getInstance();
        
        // Try primary first
        int primaryCode = httpClient.testEndpoint(primaryEndpoint);
        if (primaryCode == 200 || primaryCode == 401 || primaryCode == 405) {
            workingEndpoint = primaryEndpoint;
            lastEndpointCheck = now;
            return primaryEndpoint;
        }
        
        // Fall back to secondary
        WERMPlugin.getInstance().getLogger().warning("[API] Primary endpoint failed, trying fallback...");
        int fallbackCode = httpClient.testEndpoint(fallbackEndpoint);
        if (fallbackCode == 200 || fallbackCode == 401 || fallbackCode == 405) {
            workingEndpoint = fallbackEndpoint;
            lastEndpointCheck = now;
            WERMPlugin.getInstance().getLogger().info("[API] Using fallback endpoint");
            return fallbackEndpoint;
        }
        
        // Both failed, return primary and let caller handle error
        WERMPlugin.getInstance().getLogger().severe("[API] Both endpoints unreachable!");
        return primaryEndpoint;
    }
    
    /**
     * Test if an endpoint is reachable (using OkHttp connection pool)
     */
    private static boolean testEndpoint(String endpoint) {
        int code = HttpClientManager.getInstance().testEndpoint(endpoint);
        // 401 means the server is responding (just needs auth)
        return code == 200 || code == 401 || code == 405;
    }
    
    /**
     * Verify a code using the WERM Plugin API (using OkHttp connection pooling)
     * 
     * @param endpoint The API endpoint URL
     * @param pluginToken The plugin token (werm_xxx_xxx format)
     * @param code The verification code entered by the player
     * @param minecraftUuid The player's Minecraft UUID
     * @param minecraftUsername The player's username
     * @return VerificationResult with success/failure info
     */
    public static VerificationResult verifyCode(
            String endpoint,
            String pluginToken,
            String code,
            String minecraftUuid,
            String minecraftUsername
    ) throws Exception {
        
        HttpClientManager httpClient = HttpClientManager.getInstance();
        
        // Build request body
        JsonObject body = new JsonObject();
        body.addProperty("pluginToken", pluginToken);
        body.addProperty("action", "verify");
        body.addProperty("code", code);
        body.addProperty("minecraftUuid", minecraftUuid);
        body.addProperty("minecraftUsername", minecraftUsername);
        
        // Debug log with redacted data
        debugLog("Verifying code for player: " + minecraftUsername + " UUID: " + redactUuid(minecraftUuid));
        
        try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            // Parse response
            JsonObject jsonResponse = jsonParser.parse(responseBody).getAsJsonObject();
            
            boolean success = jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
            
            if (success) {
                String username = jsonResponse.has("username") 
                    ? jsonResponse.get("username").getAsString() 
                    : minecraftUsername;
                String uuid = jsonResponse.has("uuid") 
                    ? jsonResponse.get("uuid").getAsString() 
                    : minecraftUuid;
                return VerificationResult.success(username, uuid);
            } else {
                String error = jsonResponse.has("error") 
                    ? jsonResponse.get("error").getAsString() 
                    : "Unknown error";
                return VerificationResult.failure(error);
            }
        }
    }
    
    /**
     * Send heartbeat to WERM API (using OkHttp connection pooling)
     */
    public static HeartbeatResult sendHeartbeat(
            String endpoint,
            String pluginToken,
            int playerCount,
            int maxPlayers,
            String version,
            String pluginVersion
    ) {
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "heartbeat");
            body.addProperty("playerCount", playerCount);
            body.addProperty("maxPlayers", maxPlayers);
            body.addProperty("version", version);
            body.addProperty("pluginVersion", pluginVersion);
            
            // Debug with redacted token
            debugLog("Sending heartbeat - Token: " + redactToken(pluginToken) + ", Players: " + playerCount + "/" + maxPlayers);
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                String responseBody = response.body() != null ? response.body().string() : "";
                return new HeartbeatResult(response.isSuccessful(), response.code(), responseBody);
            }
        } catch (Exception e) {
            return new HeartbeatResult(false, -1, e.getMessage());
        }
    }
    
    /**
     * Heartbeat result holder
     */
    public static class HeartbeatResult {
        public final boolean success;
        public final int statusCode;
        public final String message;
        
        public HeartbeatResult(boolean success, int statusCode, String message) {
            this.success = success;
            this.statusCode = statusCode;
            this.message = message;
        }
    }
    
    /**
     * Get pending deliveries from WERM API (using OkHttp connection pooling)
     */
    public static List<Delivery> getPendingDeliveries(String endpoint, String pluginToken) {
        List<Delivery> deliveries = new ArrayList<>();
        
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "get-pending-deliveries");
            body.addProperty("limit", 50);
            
            // Debug with redacted token
            debugLog("Fetching pending deliveries - Token: " + redactToken(pluginToken));
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                WERMPlugin.getInstance().debug("API response code: " + response.code());
                
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    
                    WERMPlugin.getInstance().debug("API raw response: " + responseBody.substring(0, Math.min(300, responseBody.length())));
                    
                    JsonObject jsonResponse = jsonParser.parse(responseBody).getAsJsonObject();
                    
                    if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                        if (jsonResponse.has("deliveries")) {
                            JsonArray arr = jsonResponse.getAsJsonArray("deliveries");
                            deliveries = gson.fromJson(arr, new TypeToken<List<Delivery>>(){}.getType());
                        }
                    } else {
                        // Log error from API
                        String error = jsonResponse.has("error") ? jsonResponse.get("error").getAsString() : "Unknown";
                        WERMPlugin.getInstance().warn("API error: " + error);
                    }
                } else {
                    // Log error response
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    WERMPlugin.getInstance().warn("API error response (HTTP " + response.code() + "): " + errorBody);
                }
            }
        } catch (Exception e) {
            // Log error with more detail
            String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            WERMPlugin.getInstance().warn("Failed to fetch deliveries: " + errorMsg);
        }
        
        return deliveries;
    }
    
    /**
     * Confirm a delivery was successful (using OkHttp connection pooling)
     */
    public static boolean confirmDelivery(String endpoint, String pluginToken, 
            String deliveryId, String deliveryToken, int commandsExecuted) {
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "confirm-delivery");
            body.addProperty("deliveryId", deliveryId);
            body.addProperty("deliveryToken", deliveryToken);
            body.addProperty("commandsExecuted", commandsExecuted);
            
            // Debug with redacted data
            debugLog("Confirming delivery: " + redactId(deliveryId) + " - Commands: " + commandsExecuted);
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonResponse = jsonParser.parse(responseBody).getAsJsonObject();
                    return jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
                }
            }
        } catch (Exception e) {
            WERMPlugin.getInstance().getLogger().warning("Failed to confirm delivery: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Batch confirm multiple deliveries in a single API call (Improvement 1.3)
     * Reduces API calls by batching confirmations together.
     * 
     * @param confirmations List of confirmations to process
     * @return BatchConfirmResult with details of processed confirmations
     */
    public static BatchConfirmResult batchConfirmDeliveries(String endpoint, String pluginToken,
            List<BatchConfirmation> confirmations) {
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build confirmations array
            JsonArray confirmArray = new JsonArray();
            for (BatchConfirmation conf : confirmations) {
                JsonObject confObj = new JsonObject();
                confObj.addProperty("deliveryId", conf.deliveryId);
                confObj.addProperty("deliveryToken", conf.deliveryToken);
                confObj.addProperty("commandsExecuted", conf.commandsExecuted);
                confObj.addProperty("isFailure", conf.isFailure);
                if (conf.errorMessage != null) {
                    confObj.addProperty("errorMessage", conf.errorMessage);
                }
                confirmArray.add(confObj);
            }
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "batch-confirm-deliveries");
            body.add("confirmations", confirmArray);
            
            debugLog("Batch confirming " + confirmations.size() + " deliveries");
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonObject jsonResponse = jsonParser.parse(responseBody).getAsJsonObject();
                    
                    if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                        BatchConfirmResult result = new BatchConfirmResult(true);
                        
                        // Parse summary
                        if (jsonResponse.has("summary")) {
                            JsonObject summary = jsonResponse.getAsJsonObject("summary");
                            result.succeeded = summary.has("succeeded") ? summary.get("succeeded").getAsInt() : 0;
                            result.failed = summary.has("failed") ? summary.get("failed").getAsInt() : 0;
                            result.skipped = summary.has("skipped") ? summary.get("skipped").getAsInt() : 0;
                        }
                        
                        // Parse individual results
                        if (jsonResponse.has("results")) {
                            JsonArray resultsArray = jsonResponse.getAsJsonArray("results");
                            for (int i = 0; i < resultsArray.size(); i++) {
                                JsonObject res = resultsArray.get(i).getAsJsonObject();
                                String deliveryId = res.has("deliveryId") ? res.get("deliveryId").getAsString() : "";
                                boolean success = res.has("success") && res.get("success").getAsBoolean();
                                result.individualResults.put(deliveryId, success);
                            }
                        }
                        
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            WERMPlugin.getInstance().getLogger().warning("Failed to batch confirm deliveries: " + e.getMessage());
        }
        
        return new BatchConfirmResult(false);
    }
    
    /**
     * Batch confirmation input data
     */
    public static class BatchConfirmation {
        public String deliveryId;
        public String deliveryToken;
        public int commandsExecuted;
        public boolean isFailure;
        public String errorMessage;
        
        public BatchConfirmation(String deliveryId, String deliveryToken, int commandsExecuted, 
                                 boolean isFailure, String errorMessage) {
            this.deliveryId = deliveryId;
            this.deliveryToken = deliveryToken;
            this.commandsExecuted = commandsExecuted;
            this.isFailure = isFailure;
            this.errorMessage = errorMessage;
        }
    }
    
    /**
     * Batch confirmation result
     */
    public static class BatchConfirmResult {
        public boolean success;
        public int succeeded;
        public int failed;
        public int skipped;
        public java.util.Map<String, Boolean> individualResults = new java.util.HashMap<>();
        
        public BatchConfirmResult(boolean success) {
            this.success = success;
        }
    }
    
    /**
     * Report a failed delivery (using OkHttp connection pooling)
     */
    public static boolean failDelivery(String endpoint, String pluginToken,
            String deliveryId, String deliveryToken, String errorMessage) {
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "fail-delivery");
            body.addProperty("deliveryId", deliveryId);
            body.addProperty("deliveryToken", deliveryToken);
            body.addProperty("errorMessage", errorMessage);
            
            // Debug with redacted data
            debugLog("Reporting failed delivery: " + redactId(deliveryId));
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            WERMPlugin.getInstance().getLogger().warning("Failed to report delivery failure: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Notify API that a player is online (using OkHttp connection pooling)
     */
    public static void playerOnline(String endpoint, String pluginToken, String uuid, String username) {
        try {
            HttpClientManager httpClient = HttpClientManager.getInstance();
            
            // Build request body
            JsonObject body = new JsonObject();
            body.addProperty("pluginToken", pluginToken);
            body.addProperty("action", "player-online");
            body.addProperty("uuid", uuid);
            body.addProperty("username", username);
            
            try (Response response = httpClient.post(endpoint, gson.toJson(body), pluginToken, getProjectId())) {
                // Response not needed, just ensure request was sent
            }
        } catch (Exception e) {
            // Silently fail - non-critical
        }
    }
}
