package com.werm.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listener for player events
 */
public class PlayerListener implements Listener {
    
    private final WERMPlugin plugin;
    
    public PlayerListener(WERMPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        ConfigManager config = plugin.getConfigManager();
        
        if (!config.isConfigured()) {
            return;
        }
        
        // Notify API that player is online (async, delayed)
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                VerificationAPI.playerOnline(
                    config.getApiEndpoint(),
                    config.getPluginToken(),
                    player.getUniqueId().toString(),
                    player.getName()
                );
            }
        }, 20L); // 1 second delay
        
        // Check for pending deliveries for this player
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline() && plugin.getDeliveryTask() != null) {
                    plugin.getDeliveryTask().processForPlayer(player);
                }
            }
        }, 60L); // 3 second delay
    }
}
