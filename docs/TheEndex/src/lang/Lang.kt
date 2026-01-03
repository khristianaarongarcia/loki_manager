package org.lokixcz.theendex.lang

import org.lokixcz.theendex.Endex
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader

/**
 * Multi-language support manager for The Endex plugin.
 * 
 * Supports: English (en), Chinese Simplified (zh_CN), Russian (ru),
 * Spanish (es), German (de), French (fr), Portuguese (pt), Japanese (ja), Korean (ko)
 */
class Lang(private val plugin: Endex) {
    
    companion object {
        private lateinit var instance: Lang
        
        // Supported languages
        val SUPPORTED_LANGUAGES = listOf("en", "zh_CN", "ru", "es", "de", "fr", "pt", "ja", "ko")
        
        /**
         * Initialize the language system. Must be called during plugin enable.
         */
        fun init(plugin: Endex) {
            instance = Lang(plugin)
        }
        
        /**
         * Check if the language system is initialized.
         */
        fun isInitialized(): Boolean = ::instance.isInitialized
        
        /**
         * Get a translated message with optional placeholders.
         * Usage: Lang.get("market.buy.success", "amount" to "10", "item" to "Diamond")
         */
        fun get(key: String, vararg placeholders: Pair<String, Any>): String {
            if (!isInitialized()) return "§c[Lang not initialized: $key]"
            return instance.getMessage(key, *placeholders)
        }
        
        /**
         * Get a translated message WITHOUT color codes (raw text).
         * Used for web UI where color codes are not needed.
         */
        fun getRaw(key: String, vararg placeholders: Pair<String, Any>): String {
            if (!isInitialized()) return key
            return instance.getMessageRaw(key, *placeholders)
        }
        
        /**
         * Get a translated message list (for multi-line messages like help).
         */
        fun getList(key: String, vararg placeholders: Pair<String, Any>): List<String> {
            if (!isInitialized()) return listOf("§c[Lang not initialized: $key]")
            return instance.getMessageList(key, *placeholders)
        }
        
        /**
         * Get the prefix for plugin messages.
         */
        fun prefix(): String {
            if (!isInitialized()) return "§6[TheEndex]"
            return instance.getMessage("general.prefix")
        }
        
        /**
         * Get a prefixed message.
         */
        fun prefixed(key: String, vararg placeholders: Pair<String, Any>): String {
            return "${prefix()} ${get(key, *placeholders)}"
        }
        
        /**
         * Reload the language system.
         */
        fun reload() {
            if (isInitialized()) {
                instance.reload()
            }
        }
        
        /**
         * Get the current locale.
         */
        fun locale(): String {
            if (!isInitialized()) return "en"
            return instance.getCurrentLocale()
        }
        
        /**
         * Colorize a string with Minecraft color codes.
         * Supports & color codes and hex colors like &#RRGGBB.
         * Note: Lang.get() already returns colorized strings, so this is typically redundant.
         */
        fun colorize(message: String): String {
            if (!isInitialized()) return message
            return instance.translateColors(message)
        }
    }
    
    private var currentLocale: String = "en"
    private var fallbackLocale: String = "en"
    private lateinit var messages: YamlConfiguration
    private lateinit var fallbackMessages: YamlConfiguration
    
    init {
        reload()
    }
    
    /**
     * Reload language files from disk.
     */
    fun reload() {
        // Get locale from config
        currentLocale = plugin.config.getString("language.locale", "en") ?: "en"
        fallbackLocale = "en" // Always use English as fallback
        
        // Validate locale
        if (currentLocale !in SUPPORTED_LANGUAGES) {
            plugin.logger.warning("Unsupported language: $currentLocale, falling back to English")
            currentLocale = "en"
        }
        
        // Save default language files if they don't exist
        saveDefaultLanguageFiles()
        
        // Load current locale
        messages = loadLanguageFile(currentLocale)
        
        // Load fallback locale (always English by default)
        fallbackMessages = if (currentLocale != fallbackLocale) {
            loadLanguageFile(fallbackLocale)
        } else {
            messages
        }
        
        plugin.logger.info("Loaded language: $currentLocale (fallback: $fallbackLocale)")
    }
    
    /**
     * Save all default language files to the lang folder if they don't exist.
     */
    private fun saveDefaultLanguageFiles() {
        val langFolder = File(plugin.dataFolder, "lang")
        if (!langFolder.exists()) {
            langFolder.mkdirs()
        }
        
        for (locale in SUPPORTED_LANGUAGES) {
            val file = File(langFolder, "$locale.yml")
            if (!file.exists()) {
                // Try to save from resources
                val resourcePath = "lang/$locale.yml"
                try {
                    val resource = plugin.getResource(resourcePath)
                    if (resource != null) {
                        plugin.saveResource(resourcePath, false)
                        plugin.logger.info("Created language file: $locale.yml")
                    }
                } catch (t: Throwable) {
                    plugin.logger.warning("Failed to create language file $locale.yml: ${t.message}")
                }
            }
        }
    }
    
    /**
     * Load a language file from the lang folder.
     */
    private fun loadLanguageFile(locale: String): YamlConfiguration {
        val file = File(plugin.dataFolder, "lang/$locale.yml")
        
        plugin.logger.info("[Lang] Loading language file for: $locale")
        plugin.logger.info("[Lang] File path: ${file.absolutePath}")
        plugin.logger.info("[Lang] File exists: ${file.exists()}")
        
        return if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            val keys = config.getKeys(true).size
            plugin.logger.info("[Lang] Loaded $locale.yml from disk with $keys keys")
            // Debug: Show a sample key to verify content
            val testKey = config.getString("general.prefix")
            plugin.logger.info("[Lang] Sample key 'general.prefix' = $testKey")
            config
        } else {
            // Try to load from jar resources as fallback
            val resource = plugin.getResource("lang/$locale.yml")
            if (resource != null) {
                val config = YamlConfiguration.loadConfiguration(InputStreamReader(resource, Charsets.UTF_8))
                val keys = config.getKeys(true).size
                plugin.logger.info("[Lang] Loaded $locale.yml from JAR with $keys keys")
                config
            } else {
                plugin.logger.warning("Language file not found: $locale.yml")
                YamlConfiguration()
            }
        }
    }
    
    /**
     * Get a translated message with optional placeholders.
     */
    fun getMessage(key: String, vararg placeholders: Pair<String, Any>): String {
        // Try current locale first
        var message = messages.getString(key)
        var usedFallback = false
        
        // Fall back to fallback locale
        if (message == null && currentLocale != fallbackLocale) {
            message = fallbackMessages.getString(key)
            usedFallback = true
            // Debug: Log when fallback is used
            if (message != null) {
                plugin.logger.warning("[Lang] FALLBACK used for key '$key' (missing in $currentLocale, found in $fallbackLocale)")
            }
        }
        
        // If still not found, return the key
        if (message == null) {
            plugin.logger.warning("[Lang] MISSING key: $key (not found in $currentLocale or $fallbackLocale)")
            return "§c[Missing: $key]"
        }
        
        // Apply color codes
        var result = translateColors(message)
        
        // Apply placeholders
        for ((placeholder, value) in placeholders) {
            result = result.replace("{$placeholder}", value.toString())
        }
        
        return result
    }
    
    /**
     * Get a translated message WITHOUT color codes (raw text for web UI).
     */
    fun getMessageRaw(key: String, vararg placeholders: Pair<String, Any>): String {
        // Try current locale first
        var message: String? = messages.getString(key)
        
        // Fall back to fallback locale
        if (message == null && currentLocale != fallbackLocale) {
            message = fallbackMessages.getString(key)
        }
        
        // If still not found, return the key itself
        if (message == null) {
            return key.substringAfterLast(".")
        }
        
        // Apply placeholders (no color codes)
        var result: String = message
        for ((placeholder, value) in placeholders) {
            result = result.replace("{$placeholder}", value.toString())
        }
        
        // Strip any color codes (& or §)
        return result.replace(Regex("&[0-9a-fk-or]|§[0-9a-fk-or]"), "")
    }
    
    /**
     * Get a list of translated messages (for multi-line messages).
     */
    fun getMessageList(key: String, vararg placeholders: Pair<String, Any>): List<String> {
        // Try current locale first
        var list = messages.getStringList(key)
        
        // Fall back to fallback locale
        if (list.isEmpty()) {
            list = fallbackMessages.getStringList(key)
        }
        
        // If still empty, return a list with the key
        if (list.isEmpty()) {
            return listOf("§c[Missing: $key]")
        }
        
        // Apply color codes and placeholders
        return list.map { line ->
            var processed = translateColors(line)
            for ((placeholder, value) in placeholders) {
                processed = processed.replace("{$placeholder}", value.toString())
            }
            processed
        }
    }
    
    /**
     * Translate color codes in a message.
     * Supports both & and § color codes, plus hex colors like &#RRGGBB.
     */
    internal fun translateColors(message: String): String {
        var result = message
        
        // Translate hex colors (&#RRGGBB format)
        val hexPattern = "&#([A-Fa-f0-9]{6})".toRegex()
        result = hexPattern.replace(result) { matchResult ->
            val hex = matchResult.groupValues[1]
            "§x§${hex[0]}§${hex[1]}§${hex[2]}§${hex[3]}§${hex[4]}§${hex[5]}"
        }
        
        // Translate standard color codes
        result = ChatColor.translateAlternateColorCodes('&', result)
        
        return result
    }
    
    /**
     * Get the current locale.
     */
    fun getCurrentLocale(): String = currentLocale
    
    /**
     * Get all supported languages.
     */
    fun getSupportedLanguages(): List<String> = SUPPORTED_LANGUAGES
}
