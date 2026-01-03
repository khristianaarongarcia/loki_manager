package org.lokixcz.theendex.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.lang.Lang
import com.google.gson.JsonParser
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for plugin updates from Spigot and Modrinth on startup.
 * Notifies console and OPs when a new version is available.
 */
class UpdateChecker(private val plugin: Endex) : Listener {

    companion object {
        // Hardcoded resource IDs
        const val SPIGOT_RESOURCE_ID = "128382"
        const val MODRINTH_PROJECT_ID = "theendex"
        
        // Download URLs
        const val SPIGOT_URL = "https://www.spigotmc.org/resources/128382/"
        const val MODRINTH_URL = "https://modrinth.com/plugin/theendex"
        
        // API URLs
        const val SPIGOT_API = "https://api.spigotmc.org/legacy/update.php?resource=$SPIGOT_RESOURCE_ID"
        const val MODRINTH_API = "https://api.modrinth.com/v2/project/$MODRINTH_PROJECT_ID/version"
        
        const val USER_AGENT = "TheEndex-UpdateChecker"
    }

    private val currentVersion: String = plugin.description.version
    private var latestVersion: String? = null
    private var downloadUrl: String? = null
    
    @Volatile
    private var updateAvailable: Boolean = false

    /**
     * Initialize update checker - check on startup and register listener.
     */
    fun init() {
        if (!isEnabled()) {
            plugin.logger.info("[Update] Update checker disabled in config.")
            return
        }
        
        // Register listener for OP notifications
        if (shouldNotifyOps()) {
            Bukkit.getPluginManager().registerEvents(this, plugin)
        }
        
        // Check for updates async after a short delay
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
            checkForUpdates()
        }, 100L) // 5 seconds after startup
    }

    /**
     * Check for updates from Modrinth and Spigot.
     */
    private fun checkForUpdates() {
        plugin.logger.info("[Update] Checking for updates...")
        
        // Try Modrinth first (better API)
        var foundUpdate = checkModrinth()
        
        // Fallback to Spigot if Modrinth fails
        if (!foundUpdate) {
            foundUpdate = checkSpigot()
        }
        
        if (foundUpdate) {
            announceToConsole()
        } else if (!updateAvailable) {
            plugin.logger.info("[Update] You are running the latest version (v$currentVersion)")
        }
    }

    /**
     * Check Modrinth API for updates.
     */
    private fun checkModrinth(): Boolean {
        try {
            val url = URL(MODRINTH_API)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode != 200) {
                connection.disconnect()
                return false
            }
            
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            
            val jsonArray = JsonParser.parseString(response).asJsonArray
            if (jsonArray.size() == 0) return false
            
            val version = jsonArray[0].asJsonObject.get("version_number").asString
            
            if (isNewerVersion(version)) {
                latestVersion = version
                downloadUrl = MODRINTH_URL
                updateAvailable = true
                return true
            }
        } catch (e: Exception) {
            plugin.logger.fine("[Update] Modrinth check failed: ${e.message}")
        }
        return false
    }

    /**
     * Check Spigot API for updates.
     */
    private fun checkSpigot(): Boolean {
        try {
            val url = URL(SPIGOT_API)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode != 200) {
                connection.disconnect()
                return false
            }
            
            val version = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readLine()?.trim() }
            connection.disconnect()
            
            if (version != null && isNewerVersion(version)) {
                latestVersion = version
                downloadUrl = SPIGOT_URL
                updateAvailable = true
                return true
            }
        } catch (e: Exception) {
            plugin.logger.fine("[Update] Spigot check failed: ${e.message}")
        }
        return false
    }

    /**
     * Compare versions (supports 1.0.0, v1.2.3, 1.0-SNAPSHOT formats).
     */
    private fun isNewerVersion(remoteVersion: String): Boolean {
        try {
            val current = parseVersion(currentVersion)
            val remote = parseVersion(remoteVersion)
            
            for (i in 0 until maxOf(current.size, remote.size)) {
                val c = current.getOrElse(i) { 0 }
                val r = remote.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {
            return remoteVersion != currentVersion
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> {
        return version
            .removePrefix("v").removePrefix("V")
            .split("-")[0]
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * Announce update to console with nice formatting.
     */
    private fun announceToConsole() {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val console = Bukkit.getConsoleSender()
            console.sendMessage("")
            console.sendMessage(Lang.colorize(Lang.get("update-checker.header-console")))
            console.sendMessage(Lang.colorize(Lang.get("update-checker.version-info", "current" to currentVersion, "latest" to (latestVersion ?: "unknown"))))
            console.sendMessage(Lang.colorize(Lang.get("update-checker.download-url", "url" to (downloadUrl ?: ""))))
            console.sendMessage("")
        })
    }

    /**
     * Notify OPs when they join.
     */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        
        if (!updateAvailable || !player.isOp) return
        if (!shouldNotifyOps()) return
        
        // Delay message slightly so it appears after join messages
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            notifyPlayer(player)
        }, 40L) // 2 seconds
    }

    /**
     * Send clickable update notification to a player.
     */
    private fun notifyPlayer(player: Player) {
        player.sendMessage("")
        player.sendMessage(Lang.colorize(Lang.get("update-checker.header-player")))
        player.sendMessage(Lang.colorize(Lang.get("update-checker.version-info", "current" to currentVersion, "latest" to (latestVersion ?: "unknown"))))
        
        // Send clickable links using Spigot's chat components
        try {
            val spigotLink = TextComponent("§7[§bSpigot§7]")
            spigotLink.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, SPIGOT_URL)
            spigotLink.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("§7Click to open SpigotMC"))
            
            val modrinthLink = TextComponent("§7[§aModrinth§7]")
            modrinthLink.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, MODRINTH_URL)
            modrinthLink.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("§7Click to open Modrinth"))
            
            val message = TextComponent("§7Download: ")
            message.addExtra(spigotLink)
            message.addExtra(" ")
            message.addExtra(modrinthLink)
            
            player.spigot().sendMessage(message)
        } catch (_: Exception) {
            // Fallback for servers without Spigot chat API
            player.sendMessage(Lang.colorize(Lang.get("update-checker.download-url", "url" to SPIGOT_URL)))
        }
        player.sendMessage("")
    }

    private fun isEnabled(): Boolean = plugin.config.getBoolean("update-checker.enabled", true)
    private fun shouldNotifyOps(): Boolean = plugin.config.getBoolean("update-checker.notify-ops", true)
    
    fun isUpdateAvailable(): Boolean = updateAvailable
    fun getLatestVersion(): String? = latestVersion
}
