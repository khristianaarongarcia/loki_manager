package com.werm.plugin;

public class VerificationResult {
    
    private final boolean success;
    private final String error;
    private final String username;
    private final String uuid;
    
    public VerificationResult(boolean success, String error, String username, String uuid) {
        this.success = success;
        this.error = error;
        this.username = username;
        this.uuid = uuid;
    }
    
    public static VerificationResult success(String username, String uuid) {
        return new VerificationResult(true, null, username, uuid);
    }
    
    public static VerificationResult failure(String error) {
        return new VerificationResult(false, error, null, null);
    }
    
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
}
