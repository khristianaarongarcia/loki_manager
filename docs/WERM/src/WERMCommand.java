package com.werm.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WERMCommand implements CommandExecutor, TabCompleter {
    
    private final WERMPlugin plugin;
    private final List<String> subcommands = Arrays.asList("verify", "help", "reload", "status");
    
    // Rate limiting for verification attempts (prevents brute-force)
    private static final long VERIFY_COOLDOWN_MS = 5000; // 5 seconds between attempts
    private final Map<UUID, Long> lastVerifyAttempt = new HashMap<>();
    
    public WERMCommand(WERMPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            // Allow reload from console
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                plugin.getConfigManager().loadConfig();
                sender.sendMessage("§8[§6WERM§8] §aConfiguration reloaded!");
                return true;
            }
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        
        Player player = (Player) sender;
        ConfigManager config = plugin.getConfigManager();
        
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "verify":
                handleVerify(player, args);
                break;
            case "help":
                sendHelp(player);
                break;
            case "reload":
                handleReload(player);
                break;
            case "status":
                handleStatus(player);
                break;
            default:
                player.sendMessage(config.getMessagePrefix() + config.getVerifyUsageMessage());
        }
        
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // First argument - subcommands
            String partial = args[0].toLowerCase();
            for (String sub : subcommands) {
                if (sub.startsWith(partial)) {
                    // Filter by permission
                    if (sub.equals("reload") || sub.equals("status")) {
                        if (sender.hasPermission("werm.admin") || sender.isOp()) {
                            completions.add(sub);
                        }
                    } else {
                        completions.add(sub);
                    }
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("verify")) {
            // Second argument for verify - placeholder
            completions.add("<code>");
        }
        
        return completions;
    }
    
    private void sendHelp(Player player) {
        ConfigManager config = plugin.getConfigManager();
        player.sendMessage("");
        player.sendMessage("§6§lWERM §8- §7Web Engine for Realm Monetization");
        player.sendMessage("§8§m----------------------------------------");
        player.sendMessage("§e/werm verify <code> §8- §7Link your Minecraft account");
        player.sendMessage("§e/werm help §8- §7Show this help message");
        
        if (player.hasPermission("werm.admin") || player.isOp()) {
            player.sendMessage("§e/werm status §8- §7View connection status");
            player.sendMessage("§e/werm reload §8- §7Reload configuration");
            player.sendMessage("§7Server Verification: §aAutomatic via Plugin");
        }
        
        player.sendMessage("§8§m----------------------------------------");
        player.sendMessage("§7Get your verification code at §bhttps://wermpay.com/profile");
        player.sendMessage("");
    }
    
    private void handleStatus(Player player) {
        if (!player.hasPermission("werm.admin") && !player.isOp()) {
            ConfigManager config = plugin.getConfigManager();
            player.sendMessage(config.getMessagePrefix() + config.getNoPermissionMessage());
            return;
        }
        
        ConfigManager config = plugin.getConfigManager();
        player.sendMessage("");
        player.sendMessage("§6§lWERM Status");
        player.sendMessage("§8§m----------------------------------------");
        player.sendMessage("§8├ §7Configured: " + (config.isConfigured() ? "§a✓ Yes" : "§c✗ No"));
        player.sendMessage("§8├ §7Heartbeat: §f" + config.getHeartbeatInterval() + "s");
        player.sendMessage("§8├ §7Delivery: §f" + config.getDeliveryInterval() + "s");
        player.sendMessage("§8└ §7Version: §f" + WERMPlugin.VERSION);
        player.sendMessage("§8§m----------------------------------------");
        player.sendMessage("");
    }
    
    private void handleVerify(Player player, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        
        // Check if plugin is configured
        if (!config.isConfigured()) {
            player.sendMessage(config.getMessagePrefix() + config.getTokenNotConfiguredMessage());
            return;
        }
        
        // Rate limiting check - prevent brute-force attempts
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastAttempt = lastVerifyAttempt.get(playerId);
        
        if (lastAttempt != null) {
            long timeSinceLastAttempt = now - lastAttempt;
            if (timeSinceLastAttempt < VERIFY_COOLDOWN_MS) {
                long remainingSeconds = (VERIFY_COOLDOWN_MS - timeSinceLastAttempt) / 1000 + 1;
                player.sendMessage(config.getMessagePrefix() + "§cPlease wait " + remainingSeconds + " second" + 
                    (remainingSeconds == 1 ? "" : "s") + " before trying again.");
                return;
            }
        }
        
        if (args.length < 2) {
            player.sendMessage(config.getMessagePrefix() + config.getVerifyUsageMessage());
            return;
        }
        
        String code = args[1].toUpperCase();
        
        // Validate code format
        if (!code.matches("^[A-Z0-9]{6}$")) {
            player.sendMessage(config.getMessagePrefix() + "§cInvalid code format. Codes are 6 characters.");
            return;
        }
        
        // Record this attempt for rate limiting
        lastVerifyAttempt.put(playerId, now);
        
        // Clean up old entries periodically (prevent memory leak)
        if (lastVerifyAttempt.size() > 1000) {
            long cutoff = now - (VERIFY_COOLDOWN_MS * 2);
            lastVerifyAttempt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        
        // Send processing message
        player.sendMessage(config.getMessagePrefix() + config.getVerifyProcessingMessage());
        
        // Run async to not block main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    VerificationResult result = VerificationAPI.verifyCode(
                        config.getApiEndpoint(),
                        config.getPluginToken(),
                        code,
                        player.getUniqueId().toString(),
                        player.getName()
                    );
                    
                    // Send message on main thread
                    plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            if (result.isSuccess()) {
                                player.sendMessage(config.getMessagePrefix() + config.getVerifySuccessMessage());
                                plugin.getLogger().info("Player " + player.getName() + " linked their WERM account");
                                
                                // Track for bStats
                                plugin.trackVerification();
                            } else {
                                String errorMessage = result.getError();
                                if (errorMessage.contains("expired")) {
                                    player.sendMessage(config.getMessagePrefix() + config.getCodeExpiredMessage());
                                } else if (errorMessage.contains("Invalid") || errorMessage.contains("not found")) {
                                    player.sendMessage(config.getMessagePrefix() + config.getInvalidCodeMessage());
                                } else if (errorMessage.contains("already linked")) {
                                    player.sendMessage(config.getMessagePrefix() + config.getAlreadyLinkedMessage());
                                } else {
                                    player.sendMessage(config.getMessagePrefix() + 
                                        config.getVerifyFailedMessage().replace("%reason%", errorMessage));
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    plugin.getLogger().severe("Verification error: " + e.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            player.sendMessage(config.getMessagePrefix() + 
                                config.getVerifyFailedMessage().replace("%reason%", "Connection error. Please try again."));
                        }
                    });
                }
            }
        });
    }
    
    private void handleReload(Player player) {
        if (!player.hasPermission("werm.admin") && !player.isOp()) {
            ConfigManager config = plugin.getConfigManager();
            player.sendMessage(config.getMessagePrefix() + config.getNoPermissionMessage());
            return;
        }
        
        plugin.getConfigManager().loadConfig();
        player.sendMessage(plugin.getConfigManager().getMessagePrefix() + "§aConfiguration reloaded!");
    }
}
