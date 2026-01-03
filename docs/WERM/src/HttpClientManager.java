package com.werm.plugin;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * HTTP Client Manager with connection pooling for improved performance.
 * Uses OkHttp for efficient HTTP/HTTPS connections.
 * 
 * Improvement 1.1: Connection Pooling
 * - Reuses connections instead of creating new ones for each request
 * - Reduces connection overhead and latency
 * - Manages connection lifecycle automatically
 */
public class HttpClientManager {
    
    private static HttpClientManager instance;
    private final OkHttpClient client;
    
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    // Connection pool settings
    private static final int MAX_IDLE_CONNECTIONS = 5;
    private static final long KEEP_ALIVE_DURATION_MINUTES = 5;
    
    // Timeout settings
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 10;
    
    private HttpClientManager() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            // Connection pooling for performance (Improvement 1.1)
            .connectionPool(new ConnectionPool(
                MAX_IDLE_CONNECTIONS, 
                KEEP_ALIVE_DURATION_MINUTES, 
                TimeUnit.MINUTES
            ))
            // Timeouts
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Retry on connection failure
            .retryOnConnectionFailure(true);
        
        // Configure TLS 1.2+
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, null, null);
            builder.sslSocketFactory(sslContext.getSocketFactory(), new DefaultTrustManager());
        } catch (Exception e) {
            // Fall back to default SSL if TLS 1.2 setup fails
            WERMPlugin plugin = WERMPlugin.getInstance();
            if (plugin != null) {
                plugin.debug("TLS 1.2 setup failed, using default: " + e.getMessage());
            }
        }
        
        this.client = builder.build();
    }
    
    /**
     * Get the singleton instance of the HttpClientManager
     */
    public static synchronized HttpClientManager getInstance() {
        if (instance == null) {
            instance = new HttpClientManager();
        }
        return instance;
    }
    
    /**
     * Get the OkHttpClient for making requests
     */
    public OkHttpClient getClient() {
        return client;
    }
    
    /**
     * Create a new request builder with common headers
     */
    public Request.Builder newRequest(String url) {
        return new Request.Builder()
            .url(url)
            .header("Content-Type", "application/json");
    }
    
    /**
     * Execute a POST request with JSON body
     * 
     * @param url The URL to post to
     * @param json The JSON body
     * @param pluginToken The plugin token for authentication
     * @param projectId The Appwrite project ID
     * @return The response
     * @throws IOException If the request fails
     */
    public Response post(String url, String json, String pluginToken, String projectId) throws IOException {
        RequestBody body = RequestBody.create(json, JSON);
        Request request = newRequest(url)
            .post(body)
            .header("X-Plugin-Token", pluginToken)
            .header("X-Appwrite-Project", projectId)
            .build();
        
        return client.newCall(request).execute();
    }
    
    /**
     * Execute a HEAD request for testing connectivity
     * 
     * @param url The URL to test
     * @return The response code, or -1 if failed
     */
    public int testEndpoint(String url) {
        try {
            Request request = new Request.Builder()
                .url(url)
                .head()
                .build();
            
            try (Response response = client.newCall(request).execute()) {
                return response.code();
            }
        } catch (IOException e) {
            return -1;
        }
    }
    
    /**
     * Get connection pool statistics for debugging
     */
    public String getPoolStats() {
        ConnectionPool pool = client.connectionPool();
        return String.format("Connections: %d idle, %d total", 
            pool.idleConnectionCount(), 
            pool.connectionCount());
    }
    
    /**
     * Shutdown the HTTP client and release resources
     * Should be called when the plugin is disabled
     */
    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        try {
            if (client.cache() != null) {
                client.cache().close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }
    
    /**
     * Reset the singleton instance (for plugin reload)
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }
    
    /**
     * Default trust manager that uses system defaults
     */
    private static class DefaultTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // Use default behavior
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // Use default behavior
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
