package com.werm.plugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates commands before execution to prevent dangerous operations.
 * This is a security measure to prevent command injection attacks.
 */
public class CommandValidator {
    
    /**
     * Default blacklisted commands that could be dangerous if executed
     * These commands can give elevated permissions, shut down the server,
     * or cause other security/stability issues.
     */
    private static final Set<String> DEFAULT_BLACKLIST = new HashSet<>(Arrays.asList(
        // Permission/operator commands
        "op", "deop", "ban", "ban-ip", "banip", "banlist",
        "pardon", "pardon-ip", "pardonip", "unban",
        "kick", "kickall",
        
        // Server control commands
        "stop", "restart", "reload", "rl",
        "save-all", "save-off", "save-on",
        "whitelist",
        
        // World/file manipulation
        "world", "mv", "multiverse",
        
        // Plugin management
        "plugman", "pluginmanager", "pm",
        
        // Permission plugin commands (common ones)
        "lp", "luckperms", "pex", "permissionsex",
        "group", "promote", "demote", "setrank",
        
        // Console/script execution
        "execute", "minecraft:execute",
        "function", "minecraft:function",
        
        // Potentially dangerous
        "sudo", "runas", "runasop"
    ));
    
    /**
     * Patterns that indicate potentially dangerous command structures
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
        // Commands trying to execute other commands
        Pattern.compile(".*\\bexecute\\b.*\\brun\\b.*", Pattern.CASE_INSENSITIVE),
        // Attempting to use selectors that could target all players
        Pattern.compile(".*@a\\b.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*@e\\b.*", Pattern.CASE_INSENSITIVE),
        // Shell escape attempts (shouldn't work but block anyway)
        Pattern.compile(".*;.*"),
        Pattern.compile(".*\\|.*"),
        Pattern.compile(".*`.*"),
        Pattern.compile(".*\\$\\(.*\\).*")
    );
    
    private final Set<String> blacklist;
    private final Set<String> whitelist;
    private final boolean useWhitelist;
    private final boolean blockPatterns;
    
    /**
     * Creates a validator with default blacklist
     */
    public CommandValidator() {
        this.blacklist = new HashSet<>(DEFAULT_BLACKLIST);
        this.whitelist = new HashSet<>();
        this.useWhitelist = false;
        this.blockPatterns = true;
    }
    
    /**
     * Creates a validator with custom settings
     * @param customBlacklist Additional commands to blacklist
     * @param customWhitelist If not empty, ONLY these commands are allowed
     * @param blockPatterns Whether to check for dangerous patterns
     */
    public CommandValidator(Set<String> customBlacklist, Set<String> customWhitelist, boolean blockPatterns) {
        this.blacklist = new HashSet<>(DEFAULT_BLACKLIST);
        if (customBlacklist != null) {
            for (String cmd : customBlacklist) {
                this.blacklist.add(cmd.toLowerCase());
            }
        }
        
        this.whitelist = new HashSet<>();
        if (customWhitelist != null) {
            for (String cmd : customWhitelist) {
                this.whitelist.add(cmd.toLowerCase());
            }
        }
        
        this.useWhitelist = !this.whitelist.isEmpty();
        this.blockPatterns = blockPatterns;
    }
    
    /**
     * Validates a command for safe execution
     * @param command The command to validate (without leading /)
     * @return ValidationResult with success/failure and reason
     */
    public ValidationResult validate(String command) {
        if (command == null || command.trim().isEmpty()) {
            return ValidationResult.failure("Empty command");
        }
        
        String trimmedCommand = command.trim();
        
        // Remove leading slash if present
        if (trimmedCommand.startsWith("/")) {
            trimmedCommand = trimmedCommand.substring(1);
        }
        
        // Extract base command (first word)
        String[] parts = trimmedCommand.split("\\s+", 2);
        String baseCommand = parts[0].toLowerCase();
        
        // Remove minecraft: prefix if present
        if (baseCommand.startsWith("minecraft:")) {
            baseCommand = baseCommand.substring(10);
        }
        
        // If whitelist mode is enabled, only allow whitelisted commands
        if (useWhitelist) {
            if (!whitelist.contains(baseCommand)) {
                return ValidationResult.failure("Command '" + baseCommand + "' is not in the allowed whitelist");
            }
        }
        
        // Check against blacklist
        if (blacklist.contains(baseCommand)) {
            return ValidationResult.failure("Command '" + baseCommand + "' is blacklisted for security reasons");
        }
        
        // Check for dangerous patterns
        if (blockPatterns) {
            for (Pattern pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(trimmedCommand).matches()) {
                    return ValidationResult.failure("Command contains potentially dangerous pattern");
                }
            }
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Check if a command is safe without detailed reason
     */
    public boolean isSafe(String command) {
        return validate(command).isSuccess();
    }
    
    /**
     * Result of command validation
     */
    public static class ValidationResult {
        private final boolean success;
        private final String reason;
        
        private ValidationResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult failure(String reason) {
            return new ValidationResult(false, reason);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getReason() {
            return reason;
        }
    }
    
    /**
     * Get a copy of the default blacklist for reference
     */
    public static Set<String> getDefaultBlacklist() {
        return new HashSet<>(DEFAULT_BLACKLIST);
    }
}
