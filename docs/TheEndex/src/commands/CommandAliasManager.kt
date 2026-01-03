package org.lokixcz.theendex.commands

import org.bukkit.Bukkit
import org.bukkit.command.CommandMap
import org.bukkit.configuration.file.YamlConfiguration
import org.lokixcz.theendex.Endex
import java.io.File

/**
 * Manages custom command aliases defined in commands.yml.
 * Allows server admins to create shortcut commands that execute market commands.
 */
class CommandAliasManager(private val plugin: Endex) {
    
    private val configFile: File = File(plugin.dataFolder, "commands.yml")
    private val registeredAliases: MutableSet<String> = mutableSetOf()
    private var enabled: Boolean = true
    
    /**
     * Load and register command aliases from commands.yml.
     */
    fun load() {
        // Extract default config if it doesn't exist
        if (!configFile.exists()) {
            extractDefault()
        }
        
        val config = YamlConfiguration.loadConfiguration(configFile)
        enabled = config.getBoolean("enabled", true)
        
        if (!enabled) {
            plugin.logger.info("[Commands] Command aliases are disabled.")
            return
        }
        
        // Get the command map via reflection
        val commandMap = getCommandMap() ?: run {
            plugin.logger.warning("[Commands] Could not access command map. Aliases will not work.")
            return
        }
        
        // Load shortcuts section (new format with target, permission, description)
        val shortcutsSection = config.getConfigurationSection("shortcuts")
        if (shortcutsSection != null) {
            for (alias in shortcutsSection.getKeys(false)) {
                val shortcutSection = shortcutsSection.getConfigurationSection(alias)
                val target = shortcutSection?.getString("target") ?: continue
                val permission = shortcutSection.getString("permission")
                val description = shortcutSection.getString("description") ?: "Shortcut for /$target"
                
                try {
                    val aliasCommand = AliasCommand(alias, target, plugin, permission, description)
                    commandMap.register(plugin.name.lowercase(), aliasCommand)
                    registeredAliases.add(alias)
                    plugin.logger.info("[Commands] Registered shortcut: /$alias -> /$target")
                } catch (e: Exception) {
                    plugin.logger.warning("[Commands] Failed to register shortcut /$alias: ${e.message}")
                }
            }
        }
        
        // Also load legacy 'aliases' section for backwards compatibility (simple alias: target format)
        val aliasesSection = config.getConfigurationSection("aliases")
        if (aliasesSection != null) {
            for (alias in aliasesSection.getKeys(false)) {
                val target = aliasesSection.getString(alias) ?: continue
                
                try {
                    val aliasCommand = AliasCommand(alias, target, plugin)
                    commandMap.register(plugin.name.lowercase(), aliasCommand)
                    registeredAliases.add(alias)
                    plugin.logger.info("[Commands] Registered alias: /$alias -> /$target")
                } catch (e: Exception) {
                    plugin.logger.warning("[Commands] Failed to register alias /$alias: ${e.message}")
                }
            }
        }
        
        plugin.logger.info("[Commands] Loaded ${registeredAliases.size} command aliases.")
    }
    
    /**
     * Reload command aliases.
     * Note: Due to Bukkit limitations, aliases cannot be fully unregistered.
     * A server restart may be required for removed aliases to disappear.
     */
    fun reload() {
        registeredAliases.clear()
        load()
    }
    
    /**
     * Get all registered aliases.
     */
    fun getAliases(): Set<String> = registeredAliases.toSet()
    
    /**
     * Check if aliases are enabled.
     */
    fun isEnabled(): Boolean = enabled
    
    /**
     * Extract the default commands.yml from resources.
     */
    private fun extractDefault() {
        try {
            plugin.getResource("commands.yml")?.let { stream ->
                configFile.parentFile?.mkdirs()
                stream.use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                plugin.logger.info("[Commands] Extracted default commands.yml")
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Commands] Failed to extract commands.yml: ${e.message}")
        }
    }
    
    /**
     * Get the Bukkit command map via reflection.
     */
    private fun getCommandMap(): CommandMap? {
        return try {
            val server = Bukkit.getServer()
            val method = server.javaClass.getMethod("getCommandMap")
            method.invoke(server) as? CommandMap
        } catch (e: Exception) {
            plugin.logger.warning("[Commands] Reflection failed: ${e.message}")
            null
        }
    }
}
