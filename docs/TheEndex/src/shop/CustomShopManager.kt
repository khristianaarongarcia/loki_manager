package org.lokixcz.theendex.shop

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.lokixcz.theendex.Endex
import java.io.File

/**
 * Manages custom shop configurations from the shops/ folder.
 * Handles loading, parsing, and providing shop configs to the GUI system.
 */
class CustomShopManager(private val plugin: Endex) {
    
    private val shops: MutableMap<String, ShopConfig> = mutableMapOf()
    private val shopsFolder: File = File(plugin.dataFolder, "shops")
    
    /**
     * Whether custom shop mode is enabled in config.
     */
    val isCustomMode: Boolean
        get() = plugin.config.getString("shop.mode", "DEFAULT").equals("CUSTOM", ignoreCase = true)
    
    /**
     * The main shop ID from config.
     */
    val mainShopId: String
        get() = plugin.config.getString("shop.main-shop", "main") ?: "main"
    
    /**
     * Get items per page from config.
     */
    val itemsPerPage: Int
        get() = plugin.config.getInt("shop.custom.items-per-page", 45)
    
    /**
     * Load all shop configs from the shops/ folder.
     */
    fun load() {
        // Ensure shops folder exists
        if (!shopsFolder.exists()) {
            shopsFolder.mkdirs()
        }
        
        // Extract default shop config if it doesn't exist
        extractDefaults()
        
        // Clear existing configs
        shops.clear()
        
        // Load all .yml files from shops folder
        shopsFolder.listFiles()?.filter { it.extension == "yml" }?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val id = config.getString("id") ?: file.nameWithoutExtension
                val enabled = config.getBoolean("enabled", true)
                
                if (enabled) {
                    shops[id] = parseShopConfig(id, config)
                    plugin.logger.info("[Shop] Loaded shop config: $id (${shops[id]?.categories?.size ?: 0} categories)")
                }
            } catch (e: Exception) {
                plugin.logger.warning("[Shop] Failed to load ${file.name}: ${e.message}")
                e.printStackTrace()
            }
        }
        
        // Validate main shop exists if in custom mode
        if (isCustomMode && !shops.containsKey(mainShopId)) {
            plugin.logger.warning("[Shop] Custom mode enabled but main shop '$mainShopId' not found!")
        }
    }
    
    /**
     * Reload all shop configs.
     */
    fun reload() {
        shops.clear()
        load()
    }
    
    /**
     * Get a shop config by ID.
     */
    fun get(id: String): ShopConfig? = shops[id]
    
    /**
     * Get the main shop config.
     */
    fun getMainShop(): ShopConfig? = shops[mainShopId]
    
    /**
     * Get all loaded shop configs.
     */
    fun all(): Map<String, ShopConfig> = shops.toMap()
    
    /**
     * Extract default shop config from resources if it doesn't exist.
     */
    private fun extractDefaults() {
        val defaults = listOf("main.yml")
        
        for (name in defaults) {
            val file = File(shopsFolder, name)
            if (!file.exists()) {
                try {
                    plugin.getResource("shops/$name")?.let { stream ->
                        file.parentFile?.mkdirs()
                        stream.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        plugin.logger.info("[Shop] Extracted default config: $name")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("[Shop] Failed to extract $name: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Parse a YAML config into a ShopConfig object.
     */
    private fun parseShopConfig(id: String, config: YamlConfiguration): ShopConfig {
        return ShopConfig(
            id = id,
            title = config.getString("title", "&5&lServer Shop")?.colorize() ?: "&5&lServer Shop".colorize(),
            menuTitle = config.getString("menu.title", "&8Server Shop")?.colorize() ?: "&8Server Shop".colorize(),
            menuSize = config.getInt("menu.size", 54),
            categories = parseCategories(config),
            menuLayout = parseMenuLayout(config),
            decoration = parseDecoration(config),
            sounds = parseSounds(config)
        )
    }
    
    /**
     * Parse categories from config.
     * Categories can be FILTER mode (auto-populate from items.yml) or MANUAL mode (custom items list).
     */
    private fun parseCategories(config: YamlConfiguration): Map<String, ShopCategory> {
        val categories = mutableMapOf<String, ShopCategory>()
        val section = config.getConfigurationSection("categories") ?: return categories
        
        var sortOrder = 0
        for (key in section.getKeys(false)) {
            val catSection = section.getConfigurationSection(key) ?: continue
            
            // Check if this is a MANUAL mode category
            val modeStr = catSection.getString("mode", "FILTER")?.uppercase() ?: "FILTER"
            val isManualMode = modeStr == "MANUAL"
            
            // Parse manual items if in MANUAL mode
            val manualItems = if (isManualMode) {
                parseManualItems(catSection)
            } else {
                emptyList()
            }
            
            categories[key] = ShopCategory(
                id = key,
                name = catSection.getString("name", key)?.colorize() ?: key.colorize(),
                icon = Material.matchMaterial(catSection.getString("icon", "CHEST") ?: "CHEST") ?: Material.CHEST,
                iconName = catSection.getString("icon-name", "&e$key")?.colorize() ?: "&e$key".colorize(),
                iconLore = catSection.getStringList("icon-lore").colorize(),
                pageTitle = catSection.getString("page-title", "&8$key")?.colorize() ?: "&8$key".colorize(),
                pageSize = catSection.getInt("page-size", 54),
                itemSlots = parseItemSlots(catSection),
                fillEmpty = catSection.getBoolean("fill-empty", false),
                emptyMaterial = Material.matchMaterial(catSection.getString("empty-material", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE") ?: Material.GRAY_STAINED_GLASS_PANE,
                sortOrder = sortOrder++,
                // Filter determines which items from items.yml appear in this category
                filter = try {
                    MarketCategoryFilter.valueOf(catSection.getString("filter", "ALL")?.uppercase() ?: "ALL")
                } catch (e: IllegalArgumentException) {
                    MarketCategoryFilter.ALL
                },
                // Manual mode settings
                isManualMode = isManualMode,
                manualItems = manualItems
            )
        }
        
        return categories
    }
    
    /**
     * Parse manual items from a category configuration.
     * These are custom items with optional NBT serialization.
     */
    private fun parseManualItems(catSection: org.bukkit.configuration.ConfigurationSection): List<ManualShopItem> {
        val items = mutableListOf<ManualShopItem>()
        val itemsList = catSection.getMapList("items")
        
        for (itemMap in itemsList) {
            try {
                val id = itemMap["id"]?.toString() ?: continue
                val materialName = itemMap["material"]?.toString() ?: continue
                val material = Material.matchMaterial(materialName) ?: continue
                
                val item = ManualShopItem(
                    id = id,
                    material = material,
                    displayName = itemMap["display-name"]?.toString() ?: material.name,
                    buyPrice = (itemMap["buy-price"] as? Number)?.toDouble() ?: 100.0,
                    sellPrice = (itemMap["sell-price"] as? Number)?.toDouble() ?: 50.0,
                    slot = (itemMap["slot"] as? Number)?.toInt() ?: -1,
                    enabled = itemMap["enabled"] as? Boolean ?: true,
                    isCustomItem = itemMap["is-custom"] as? Boolean ?: false,
                    serializedData = itemMap["serialized-data"]?.toString(),
                    permission = itemMap["permission"]?.toString() ?: "",
                    stockLimit = (itemMap["stock-limit"] as? Number)?.toInt() ?: -1
                )
                
                items.add(item)
                plugin.logger.info("[Shop] Loaded manual item: ${item.id} (${item.displayName})")
            } catch (e: Exception) {
                plugin.logger.warning("[Shop] Failed to parse manual item: ${e.message}")
            }
        }
        
        return items
    }
    
    /**
     * Parse item slot range for a category.
     */
    private fun parseItemSlots(catSection: org.bukkit.configuration.ConfigurationSection): IntRange {
        val slotsSection = catSection.getConfigurationSection("item-slots")
        return if (slotsSection != null) {
            val start = slotsSection.getInt("start", 0)
            val end = slotsSection.getInt("end", 44)
            start..end
        } else {
            0..44
        }
    }
    
    /**
     * Parse menu layout slots.
     */
    private fun parseMenuLayout(config: YamlConfiguration): List<ShopMenuSlot> {
        val layout = mutableListOf<ShopMenuSlot>()
        val section = config.getConfigurationSection("menu.layout") ?: return layout
        
        for (key in section.getKeys(false)) {
            val slotSection = section.getConfigurationSection(key) ?: continue
            val slot = key.toIntOrNull() ?: continue
            
            val typeStr = slotSection.getString("type", "DECORATION")?.uppercase() ?: "DECORATION"
            val type = try {
                MenuSlotType.valueOf(typeStr)
            } catch (e: IllegalArgumentException) {
                MenuSlotType.DECORATION
            }
            
            layout.add(ShopMenuSlot(
                slot = slot,
                type = type,
                categoryId = slotSection.getString("category"),
                material = Material.matchMaterial(slotSection.getString("material", "STONE") ?: "STONE"),
                name = slotSection.getString("name")?.colorize(),
                lore = slotSection.getStringList("lore").colorize().takeIf { it.isNotEmpty() }
            ))
        }
        
        return layout.sortedBy { it.slot }
    }
    
    /**
     * Parse decoration settings.
     */
    private fun parseDecoration(config: YamlConfiguration): ShopDecoration {
        val section = config.getConfigurationSection("decoration") ?: return ShopDecoration(
            fillEmpty = false,
            emptyMaterial = Material.AIR,
            borderMaterial = null,
            borderSlots = emptyList()
        )
        
        return ShopDecoration(
            fillEmpty = section.getBoolean("fill-empty", false),
            emptyMaterial = Material.matchMaterial(section.getString("empty-material", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE") ?: Material.GRAY_STAINED_GLASS_PANE,
            borderMaterial = section.getString("border-material")?.let { Material.matchMaterial(it) },
            borderSlots = section.getIntegerList("border-slots")
        )
    }
    
    /**
     * Parse sound settings.
     */
    private fun parseSounds(config: YamlConfiguration): ShopSounds {
        val section = config.getConfigurationSection("sounds") ?: return ShopSounds(
            openMenu = "UI_BUTTON_CLICK",
            openCategory = "UI_BUTTON_CLICK",
            buy = "ENTITY_EXPERIENCE_ORB_PICKUP",
            sell = "ENTITY_EXPERIENCE_ORB_PICKUP",
            error = "ENTITY_VILLAGER_NO",
            pageChange = "UI_BUTTON_CLICK"
        )
        
        return ShopSounds(
            openMenu = section.getString("open-menu"),
            openCategory = section.getString("open-category"),
            buy = section.getString("buy"),
            sell = section.getString("sell"),
            error = section.getString("error"),
            pageChange = section.getString("page-change")
        )
    }
}
