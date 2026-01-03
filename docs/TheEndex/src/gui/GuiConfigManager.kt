package org.lokixcz.theendex.gui

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.lokixcz.theendex.Endex
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Manages GUI configuration files from the guis/ folder.
 * Allows server admins to customize GUI layouts, colors, and categories.
 */
class GuiConfigManager(private val plugin: Endex) {
    
    private val guiConfigs: MutableMap<String, GuiConfig> = mutableMapOf()
    private val guisFolder: File = File(plugin.dataFolder, "guis")
    
    /**
     * Load all GUI configs from the guis/ folder.
     */
    fun load() {
        // Ensure guis folder exists
        if (!guisFolder.exists()) {
            guisFolder.mkdirs()
        }
        
        // Extract default configs if they don't exist
        extractDefaults()
        
        // Load all .yml files from guis folder
        guisFolder.listFiles()?.filter { it.extension == "yml" }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = config.getString("id") ?: file.nameWithoutExtension
                val enabled = config.getBoolean("enabled", true)
                
                if (enabled) {
                    guiConfigs[id] = parseGuiConfig(id, config)
                    plugin.logger.info("[GUI] Loaded GUI config: $id")
                }
            } catch (e: Exception) {
                plugin.logger.warning("[GUI] Failed to load ${file.name}: ${e.message}")
            }
        }
    }
    
    /**
     * Reload all GUI configs.
     */
    fun reload() {
        guiConfigs.clear()
        load()
    }
    
    /**
     * Get a GUI config by ID.
     */
    fun get(id: String): GuiConfig? = guiConfigs[id]
    
    /**
     * Get all loaded GUI configs.
     */
    fun all(): Map<String, GuiConfig> = guiConfigs.toMap()
    
    /**
     * Extract default GUI configs from resources if they don't exist.
     */
    private fun extractDefaults() {
        val defaults = listOf("market.yml", "details.yml", "holdings.yml", "deliveries.yml")
        
        for (name in defaults) {
            val file = File(guisFolder, name)
            if (!file.exists()) {
                try {
                    plugin.getResource("guis/$name")?.let { stream ->
                        file.parentFile?.mkdirs()
                        stream.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        plugin.logger.info("[GUI] Extracted default config: $name")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("[GUI] Failed to extract $name: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Parse a YAML config into a GuiConfig object.
     */
    private fun parseGuiConfig(id: String, config: YamlConfiguration): GuiConfig {
        return GuiConfig(
            id = id,
            title = translateColors(config.getString("title", "&5The Endex")),
            size = config.getInt("size", 54),
            itemSlots = parseSlotRange(config, "item-slots"),
            controls = parseControls(config),
            colors = parseColors(config),
            categories = parseCategories(config),
            amounts = config.getIntegerList("amounts").takeIf { it.isNotEmpty() } ?: listOf(1, 8, 16, 32, 64),
            sortModes = parseSortModes(config),
            itemLore = config.getStringList("item-lore").map { translateColors(it) },
            showEventMultiplier = config.getBoolean("show-event-multiplier", true),
            eventMultiplierFormat = translateColors(config.getString("event-multiplier-format", "&3Event: x%multiplier% &7Eff: &a%effective_price%")),
            buttons = parseButtons(config),
            emptyConfig = parseEmptyConfig(config),
            showChart = config.getBoolean("show-chart", true),
            chartLength = config.getInt("chart-length", 12)
        )
    }
    
    private fun parseSlotRange(config: YamlConfiguration, path: String): IntRange {
        val section = config.getConfigurationSection(path) ?: return 0..44
        val start = section.getInt("start", 0)
        val end = section.getInt("end", 44)
        return start..end
    }
    
    private fun parseControls(config: YamlConfiguration): Map<String, ControlConfig> {
        val controls = mutableMapOf<String, ControlConfig>()
        val section = config.getConfigurationSection("controls") ?: return controls
        
        for (key in section.getKeys(false)) {
            val controlSection = section.getConfigurationSection(key) ?: continue
            controls[key] = ControlConfig(
                slot = controlSection.getInt("slot", 0),
                material = Material.matchMaterial(controlSection.getString("material", "STONE") ?: "STONE") ?: Material.STONE,
                name = translateColors(controlSection.getString("name", "&7$key")),
                lore = controlSection.getStringList("lore").map { translateColors(it) }
            )
        }
        
        return controls
    }
    
    private fun parseColors(config: YamlConfiguration): ColorConfig {
        val section = config.getConfigurationSection("colors") ?: return ColorConfig()
        return ColorConfig(
            itemName = translateColors(section.getString("item-name", "&b")),
            priceUp = translateColors(section.getString("price-up", "&a")),
            priceDown = translateColors(section.getString("price-down", "&c")),
            priceStable = translateColors(section.getString("price-stable", "&e")),
            categoryHeader = translateColors(section.getString("category-header", "&d")),
            loreLabel = translateColors(section.getString("lore-label", "&7")),
            loreValue = translateColors(section.getString("lore-value", "&f")),
            loreHint = translateColors(section.getString("lore-hint", "&8")),
            profit = translateColors(section.getString("profit", "&a")),
            loss = translateColors(section.getString("loss", "&c")),
            neutral = translateColors(section.getString("neutral", "&7"))
        )
    }
    
    private fun parseCategories(config: YamlConfiguration): Map<String, CategoryConfig> {
        val categories = mutableMapOf<String, CategoryConfig>()
        val section = config.getConfigurationSection("categories") ?: return categories
        
        for (key in section.getKeys(false)) {
            val catSection = section.getConfigurationSection(key) ?: continue
            val filter = when {
                catSection.isList("filter") -> catSection.getStringList("filter")
                catSection.isString("filter") -> listOf(catSection.getString("filter")!!)
                else -> listOf("*")
            }
            
            categories[key] = CategoryConfig(
                id = key,
                name = catSection.getString("name", key) ?: key,
                icon = Material.matchMaterial(catSection.getString("icon", "COMPASS") ?: "COMPASS") ?: Material.COMPASS,
                filter = filter
            )
        }
        
        return categories
    }
    
    private fun parseSortModes(config: YamlConfiguration): Map<String, SortModeConfig> {
        val modes = mutableMapOf<String, SortModeConfig>()
        val section = config.getConfigurationSection("sort-modes") ?: return modes
        
        for (key in section.getKeys(false)) {
            val modeSection = section.getConfigurationSection(key) ?: continue
            modes[key] = SortModeConfig(
                id = key,
                name = modeSection.getString("name", key) ?: key,
                icon = Material.matchMaterial(modeSection.getString("icon", "NAME_TAG") ?: "NAME_TAG") ?: Material.NAME_TAG
            )
        }
        
        return modes
    }
    
    private fun parseButtons(config: YamlConfiguration): Map<String, ButtonConfig> {
        val buttons = mutableMapOf<String, ButtonConfig>()
        val section = config.getConfigurationSection("buttons") ?: return buttons
        
        for (key in section.getKeys(false)) {
            val btnSection = section.getConfigurationSection(key) ?: continue
            buttons[key] = ButtonConfig(
                slot = btnSection.getInt("slot", 0),
                material = Material.matchMaterial(btnSection.getString("material", "STONE") ?: "STONE") ?: Material.STONE,
                name = translateColors(btnSection.getString("name", "&7$key"))
            )
        }
        
        return buttons
    }
    
    private fun parseEmptyConfig(config: YamlConfiguration): EmptyConfig? {
        val section = config.getConfigurationSection("empty") ?: return null
        return EmptyConfig(
            material = Material.matchMaterial(section.getString("material", "BARRIER") ?: "BARRIER") ?: Material.BARRIER,
            name = translateColors(section.getString("name", "&7Empty")),
            lore = section.getStringList("lore").map { translateColors(it) }
        )
    }
    
    private fun translateColors(text: String?): String {
        if (text == null) return ""
        return ChatColor.translateAlternateColorCodes('&', text)
    }
}

/**
 * Main GUI configuration data class.
 */
data class GuiConfig(
    val id: String,
    val title: String,
    val size: Int,
    val itemSlots: IntRange,
    val controls: Map<String, ControlConfig>,
    val colors: ColorConfig,
    val categories: Map<String, CategoryConfig>,
    val amounts: List<Int>,
    val sortModes: Map<String, SortModeConfig>,
    val itemLore: List<String>,
    val showEventMultiplier: Boolean,
    val eventMultiplierFormat: String,
    val buttons: Map<String, ButtonConfig>,
    val emptyConfig: EmptyConfig?,
    val showChart: Boolean,
    val chartLength: Int
)

data class ControlConfig(
    val slot: Int,
    val material: Material,
    val name: String,
    val lore: List<String> = emptyList()
)

data class ColorConfig(
    val itemName: String = "${ChatColor.AQUA}",
    val priceUp: String = "${ChatColor.GREEN}",
    val priceDown: String = "${ChatColor.RED}",
    val priceStable: String = "${ChatColor.YELLOW}",
    val categoryHeader: String = "${ChatColor.LIGHT_PURPLE}",
    val loreLabel: String = "${ChatColor.GRAY}",
    val loreValue: String = "${ChatColor.WHITE}",
    val loreHint: String = "${ChatColor.DARK_GRAY}",
    val profit: String = "${ChatColor.GREEN}",
    val loss: String = "${ChatColor.RED}",
    val neutral: String = "${ChatColor.GRAY}"
)

data class CategoryConfig(
    val id: String,
    val name: String,
    val icon: Material,
    val filter: List<String>
) {
    /**
     * Check if a material matches this category's filter.
     */
    fun matches(material: Material): Boolean {
        val name = material.name
        
        for (pattern in filter) {
            when {
                pattern == "*" -> return true
                pattern == "blocks" -> if (material.isBlock) return true
                pattern.startsWith("*") && pattern.endsWith("*") -> {
                    val middle = pattern.substring(1, pattern.length - 1)
                    if (name.contains(middle, ignoreCase = true)) return true
                }
                pattern.startsWith("*") -> {
                    val suffix = pattern.substring(1)
                    if (name.endsWith(suffix, ignoreCase = true)) return true
                }
                pattern.endsWith("*") -> {
                    val prefix = pattern.substring(0, pattern.length - 1)
                    if (name.startsWith(prefix, ignoreCase = true)) return true
                }
                name.equals(pattern, ignoreCase = true) -> return true
            }
        }
        
        return false
    }
}

data class SortModeConfig(
    val id: String,
    val name: String,
    val icon: Material
)

data class ButtonConfig(
    val slot: Int,
    val material: Material,
    val name: String
)

data class EmptyConfig(
    val material: Material,
    val name: String,
    val lore: List<String>
)
