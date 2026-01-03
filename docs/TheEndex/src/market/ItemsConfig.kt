package org.lokixcz.theendex.market

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Static item configuration - defines which items exist in the market
 * and their pricing rules (base, min, max prices).
 * 
 * This is stored in items.yml and can be edited by server owners.
 * Dynamic data (current price, supply/demand) is stored in market.db.
 */
data class ItemConfig(
    val material: Material,
    var basePrice: Double,
    var minPrice: Double,
    var maxPrice: Double,
    var enabled: Boolean = true
) {
    companion object {
        /**
         * Create an ItemConfig with sensible defaults based on material type.
         */
        fun createDefault(material: Material, basePrice: Double): ItemConfig {
            val min = (basePrice * 0.30).coerceAtLeast(1.0)
            val max = (basePrice * 6.0).coerceAtLeast(min + 1.0)
            return ItemConfig(material, basePrice, min, max, true)
        }
    }
}

/**
 * Custom item configuration - for items with NBT data (ItemsAdder, Oraxen, etc.)
 * 
 * Custom items are identified by a unique ID and store full serialized ItemStack data.
 * They can be assigned to categories via the shop editor.
 */
data class CustomItemConfig(
    val id: String,
    val material: Material,
    val displayName: String,
    val serializedData: String,  // Base64 encoded ItemStack
    var basePrice: Double,
    var minPrice: Double,
    var maxPrice: Double,
    var sellPrice: Double,
    var enabled: Boolean = true,
    val shopId: String? = null,      // Which shop this item belongs to
    val categoryId: String? = null,   // Which category within the shop
    val categoryFilter: String? = null // Category filter type for auto-assignment (e.g., "ALL", "BLOCKS", "TOOLS")
) {
    companion object {
        /**
         * Serialize an ItemStack to Base64 string.
         */
        fun serializeItem(item: ItemStack): String {
            return try {
                ByteArrayOutputStream().use { byteOut ->
                    BukkitObjectOutputStream(byteOut).use { objOut ->
                        objOut.writeObject(item)
                    }
                    Base64.getEncoder().encodeToString(byteOut.toByteArray())
                }
            } catch (e: Exception) {
                ""
            }
        }
        
        /**
         * Deserialize a Base64 string back to an ItemStack.
         */
        fun deserializeItem(data: String): ItemStack? {
            return try {
                // Remove any whitespace from the Base64 string (YAML may add newlines)
                val cleanData = data.replace(Regex("\\s"), "")
                val bytes = Base64.getDecoder().decode(cleanData)
                ByteArrayInputStream(bytes).use { byteIn ->
                    BukkitObjectInputStream(byteIn).use { objIn ->
                        objIn.readObject() as? ItemStack
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Create a CustomItemConfig from an ItemStack.
         */
        fun fromItemStack(
            id: String,
            item: ItemStack,
            basePrice: Double,
            sellPrice: Double = basePrice * 0.5,
            shopId: String? = null,
            categoryId: String? = null,
            categoryFilter: String? = null
        ): CustomItemConfig {
            val displayName = item.itemMeta?.displayName 
                ?: item.type.name.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }
            
            return CustomItemConfig(
                id = id,
                material = item.type,
                displayName = displayName,
                serializedData = serializeItem(item),
                basePrice = basePrice,
                minPrice = (basePrice * 0.30).coerceAtLeast(1.0),
                maxPrice = (basePrice * 6.0),
                sellPrice = sellPrice,
                enabled = true,
                shopId = shopId,
                categoryId = categoryId,
                categoryFilter = categoryFilter
            )
        }
    }
    
    /**
     * Recreate the original ItemStack from serialized data.
     */
    fun toItemStack(): ItemStack? = deserializeItem(serializedData)
}

/**
 * Manager for items.yml - the static item configuration file.
 * 
 * Responsibilities:
 * - Load/save items.yml (both vanilla and custom items)
 * - Provide item configs to MarketManager
 * - Sync with admin commands (add, remove, setprice)
 * - Auto-export from existing market.db on first run
 * - Manage custom items with full NBT serialization
 */
class ItemsConfigManager(private val plugin: JavaPlugin) {
    
    private val items: MutableMap<Material, ItemConfig> = mutableMapOf()
    private val customItems: MutableMap<String, CustomItemConfig> = mutableMapOf()
    private val itemsFile: File get() = File(plugin.dataFolder, "items.yml")
    
    /**
     * Get all configured vanilla items.
     */
    fun all(): Collection<ItemConfig> = items.values
    
    /**
     * Get all enabled vanilla items.
     */
    fun allEnabled(): Collection<ItemConfig> = items.values.filter { it.enabled }
    
    /**
     * Get all custom items.
     */
    fun allCustomItems(): Collection<CustomItemConfig> = customItems.values
    
    /**
     * Get all enabled custom items.
     */
    fun allEnabledCustomItems(): Collection<CustomItemConfig> = customItems.values.filter { it.enabled }
    
    /**
     * Get custom items for a specific category filter.
     * Used by FILTER mode categories to include custom items.
     */
    fun getCustomItemsByFilter(filter: String): List<CustomItemConfig> {
        return customItems.values.filter { 
            it.enabled && (it.categoryFilter?.equals(filter, ignoreCase = true) == true || it.categoryFilter == "ALL")
        }
    }
    
    /**
     * Get custom items for a specific shop and category.
     */
    fun getCustomItemsForCategory(shopId: String, categoryId: String): List<CustomItemConfig> {
        return customItems.values.filter { 
            it.enabled && it.shopId == shopId && it.categoryId == categoryId
        }
    }
    
    /**
     * Get config for a specific material.
     */
    fun get(material: Material): ItemConfig? = items[material]
    
    /**
     * Get a custom item by ID.
     */
    fun getCustomItem(id: String): CustomItemConfig? = customItems[id]
    
    /**
     * Check if an item is configured and enabled.
     */
    fun isEnabled(material: Material): Boolean = items[material]?.enabled ?: false
    
    /**
     * Add or update an item configuration.
     * Returns true if item was added, false if updated.
     */
    fun set(config: ItemConfig): Boolean {
        val isNew = !items.containsKey(config.material)
        items[config.material] = config
        return isNew
    }
    
    /**
     * Add or update a custom item configuration.
     * Returns true if item was added, false if updated.
     */
    fun setCustomItem(config: CustomItemConfig): Boolean {
        val isNew = !customItems.containsKey(config.id)
        customItems[config.id] = config
        return isNew
    }
    
    /**
     * Add a new item with the given parameters.
     */
    fun addItem(material: Material, basePrice: Double, minPrice: Double? = null, maxPrice: Double? = null): ItemConfig {
        val min = minPrice ?: (basePrice * 0.30).coerceAtLeast(1.0)
        val max = maxPrice ?: (basePrice * 6.0).coerceAtLeast(min + 1.0)
        val config = ItemConfig(material, basePrice, min, max, true)
        items[material] = config
        return config
    }
    
    /**
     * Add a custom item from an ItemStack.
     */
    fun addCustomItem(
        id: String,
        item: ItemStack,
        basePrice: Double,
        sellPrice: Double = basePrice * 0.5,
        shopId: String? = null,
        categoryId: String? = null,
        categoryFilter: String? = null
    ): CustomItemConfig {
        val config = CustomItemConfig.fromItemStack(id, item, basePrice, sellPrice, shopId, categoryId, categoryFilter)
        customItems[id] = config
        return config
    }
    
    /**
     * Remove an item from the configuration.
     */
    fun remove(material: Material): Boolean {
        return items.remove(material) != null
    }
    
    /**
     * Remove a custom item from the configuration.
     */
    fun removeCustomItem(id: String): Boolean {
        return customItems.remove(id) != null
    }
    
    /**
     * Disable an item (keeps config but won't appear in market).
     */
    fun disable(material: Material): Boolean {
        val config = items[material] ?: return false
        config.enabled = false
        return true
    }
    
    /**
     * Enable a previously disabled item.
     */
    fun enable(material: Material): Boolean {
        val config = items[material] ?: return false
        config.enabled = true
        return true
    }
    
    /**
     * Update base price for an item.
     */
    fun setBasePrice(material: Material, price: Double): Boolean {
        val config = items[material] ?: return false
        config.basePrice = price
        return true
    }
    
    /**
     * Update min price for an item.
     */
    fun setMinPrice(material: Material, price: Double): Boolean {
        val config = items[material] ?: return false
        config.minPrice = price.coerceAtMost(config.maxPrice - 0.01)
        return true
    }
    
    /**
     * Update max price for an item.
     */
    fun setMaxPrice(material: Material, price: Double): Boolean {
        val config = items[material] ?: return false
        config.maxPrice = price.coerceAtLeast(config.minPrice + 0.01)
        return true
    }
    
    /**
     * Load items from items.yml.
     * If file doesn't exist, returns false (caller should seed defaults).
     */
    fun load(): Boolean {
        items.clear()
        customItems.clear()
        
        if (!itemsFile.exists()) {
            return false
        }
        
        val yaml = YamlConfiguration.loadConfiguration(itemsFile)
        
        // Load vanilla items
        val itemsSection = yaml.getConfigurationSection("items")
        if (itemsSection != null) {
            for (key in itemsSection.getKeys(false)) {
                val mat = Material.matchMaterial(key.uppercase()) ?: continue
                val section = itemsSection.getConfigurationSection(key) ?: continue
                
                val basePrice = section.getDouble("base-price", 100.0)
                val minPrice = section.getDouble("min-price", basePrice * 0.3)
                val maxPrice = section.getDouble("max-price", basePrice * 6.0)
                val enabled = section.getBoolean("enabled", true)
                
                items[mat] = ItemConfig(mat, basePrice, minPrice, maxPrice, enabled)
            }
        }
        
        // Load custom items
        val customSection = yaml.getConfigurationSection("custom-items")
        if (customSection != null) {
            for (key in customSection.getKeys(false)) {
                val section = customSection.getConfigurationSection(key) ?: continue
                
                val matName = section.getString("material") ?: continue
                val mat = Material.matchMaterial(matName.uppercase()) ?: continue
                
                val config = CustomItemConfig(
                    id = key,
                    material = mat,
                    displayName = section.getString("display-name") ?: key,
                    serializedData = section.getString("serialized-data") ?: continue,
                    basePrice = section.getDouble("base-price", 100.0),
                    minPrice = section.getDouble("min-price", 30.0),
                    maxPrice = section.getDouble("max-price", 600.0),
                    sellPrice = section.getDouble("sell-price", 50.0),
                    enabled = section.getBoolean("enabled", true),
                    shopId = section.getString("shop"),
                    categoryId = section.getString("category"),
                    categoryFilter = section.getString("category-filter")
                )
                
                customItems[key] = config
            }
        }
        
        plugin.logger.info("Loaded ${items.size} vanilla items and ${customItems.size} custom items from items.yml")
        return true
    }
    
    /**
     * Save all items to items.yml.
     */
    fun save() {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        
        val yaml = YamlConfiguration()
        
        // Add header comment
        yaml.options().setHeader(listOf(
            "═══════════════════════════════════════════════════════════════════════════════",
            "The Endex - Market Items Configuration",
            "═══════════════════════════════════════════════════════════════════════════════",
            "",
            "This file defines which items are tradeable in the market and their pricing rules.",
            "",
            "VANILLA ITEMS (items section):",
            "  For each item:",
            "    base-price: The 'natural' price that the market gravitates toward",
            "    min-price: Floor price - items cannot go below this",
            "    max-price: Ceiling price - items cannot exceed this",
            "    enabled: Whether the item appears in the market (true/false)",
            "",
            "CUSTOM ITEMS (custom-items section):",
            "  For custom items with NBT data (ItemsAdder, Oraxen, etc.):",
            "    material: The base material type",
            "    display-name: The item's display name",
            "    serialized-data: Base64 encoded ItemStack (do not edit manually)",
            "    base-price, min-price, max-price, sell-price: Pricing",
            "    shop: Which shop this item belongs to (optional)",
            "    category: Which category within the shop (optional)",
            "    category-filter: Auto-assign to categories with this filter (e.g., ALL, BLOCKS)",
            "",
            "Dynamic data (current price, supply/demand) is stored in market.db",
            "",
            "Admin commands:",
            "  /market add <item> <base> [min] [max] - Add item to market",
            "  /market remove <item> - Remove item from market",
            "  /market setbase <item> <price> - Set base price",
            "  /market editor - Open the GUI editor to add custom items",
            "",
            "═══════════════════════════════════════════════════════════════════════════════"
        ))
        
        // Save vanilla items - Sort alphabetically for easier editing
        val sortedItems = items.entries.sortedBy { it.key.name }
        
        for ((mat, config) in sortedItems) {
            val path = "items.${mat.name}"
            yaml.set("$path.base-price", config.basePrice)
            yaml.set("$path.min-price", config.minPrice)
            yaml.set("$path.max-price", config.maxPrice)
            yaml.set("$path.enabled", config.enabled)
        }
        
        // Save custom items - Sort by ID
        val sortedCustomItems = customItems.entries.sortedBy { it.key }
        
        for ((id, config) in sortedCustomItems) {
            val path = "custom-items.$id"
            yaml.set("$path.material", config.material.name)
            yaml.set("$path.display-name", config.displayName)
            yaml.set("$path.serialized-data", config.serializedData)
            yaml.set("$path.base-price", config.basePrice)
            yaml.set("$path.min-price", config.minPrice)
            yaml.set("$path.max-price", config.maxPrice)
            yaml.set("$path.sell-price", config.sellPrice)
            yaml.set("$path.enabled", config.enabled)
            if (config.shopId != null) yaml.set("$path.shop", config.shopId)
            if (config.categoryId != null) yaml.set("$path.category", config.categoryId)
            if (config.categoryFilter != null) yaml.set("$path.category-filter", config.categoryFilter)
        }
        
        yaml.save(itemsFile)
        plugin.logger.info("Saved ${items.size} vanilla items and ${customItems.size} custom items to items.yml")
    }
    
    /**
     * Import items from an existing MarketManager (for migration from market.db).
     * Only imports items that don't already exist in items.yml.
     */
    fun importFromMarketManager(marketManager: MarketManager): Int {
        var imported = 0
        
        for (item in marketManager.allItems()) {
            if (!items.containsKey(item.material)) {
                items[item.material] = ItemConfig(
                    material = item.material,
                    basePrice = item.basePrice,
                    minPrice = item.minPrice,
                    maxPrice = item.maxPrice,
                    enabled = true
                )
                imported++
            }
        }
        
        if (imported > 0) {
            plugin.logger.info("Imported $imported items from market data to items.yml")
        }
        
        return imported
    }
    
    /**
     * Sync item configs to MarketManager.
     * Updates base/min/max prices in market.db to match items.yml.
     * Adds new items to market.db, removes items not in items.yml.
     */
    fun syncToMarketManager(marketManager: MarketManager, db: SqliteStore?): SyncResult {
        var added = 0
        var updated = 0
        var removed = 0
        
        val enabledItems = allEnabled()
        val existingMaterials = marketManager.allItems().map { it.material }.toSet()
        val configuredMaterials = enabledItems.map { it.material }.toSet()
        
        // Add or update items from config
        for (config in enabledItems) {
            val existing = marketManager.get(config.material)
            
            if (existing == null) {
                // New item - add to market
                val newItem = MarketItem(
                    material = config.material,
                    basePrice = config.basePrice,
                    minPrice = config.minPrice,
                    maxPrice = config.maxPrice,
                    currentPrice = config.basePrice,  // Start at base price
                    demand = 0.0,
                    supply = 0.0,
                    history = ArrayDeque()
                )
                // Add to internal market map via reflection or direct db insert
                db?.upsertItem(newItem)
                added++
            } else {
                // Existing item - update pricing rules if changed
                var changed = false
                if (existing.basePrice != config.basePrice) {
                    existing.basePrice = config.basePrice
                    changed = true
                }
                if (existing.minPrice != config.minPrice) {
                    existing.minPrice = config.minPrice
                    changed = true
                }
                if (existing.maxPrice != config.maxPrice) {
                    existing.maxPrice = config.maxPrice
                    changed = true
                }
                
                if (changed) {
                    // Ensure current price is within new bounds
                    existing.currentPrice = existing.currentPrice.coerceIn(config.minPrice, config.maxPrice)
                    db?.upsertItem(existing)
                    updated++
                }
            }
        }
        
        // Note: We don't remove items from market.db automatically
        // This preserves historical data. Items just won't appear in GUI.
        
        return SyncResult(added, updated, removed)
    }
    
    /**
     * Get the count of configured items.
     */
    fun count(): Int = items.size
    
    /**
     * Get the count of enabled items.
     */
    fun enabledCount(): Int = items.values.count { it.enabled }
    
    data class SyncResult(val added: Int, val updated: Int, val removed: Int)
}
