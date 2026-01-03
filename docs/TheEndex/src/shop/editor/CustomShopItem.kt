package org.lokixcz.theendex.shop.editor

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Represents a custom shop item with full ItemStack serialization.
 * 
 * This allows the shop to store ANY item from ANY plugin (ItemsAdder, Oraxen, MMOItems, etc.)
 * by serializing the complete NBT/ItemStack data as Base64.
 * 
 * When a user drags an item into the shop editor, we capture:
 * - The full ItemStack (all NBT data, enchantments, custom model data, lore, etc.)
 * - The buy/sell prices set by the admin
 * - Optional permission and stock settings
 * 
 * Items are stored in the shop YAML and synced to items.yml and market.db.
 */
data class CustomShopItem(
    /** Unique identifier for this item within the category */
    val id: String,
    
    /** The base material type (used for filtering and market integration) */
    val material: Material,
    
    /** Whether this is a custom item (true) or vanilla item (false) */
    val isCustomItem: Boolean,
    
    /** Base64-encoded ItemStack data (only for custom items) */
    val serializedData: String?,
    
    /** Display name for reference (from the original item) */
    val displayName: String,
    
    /** Buy price (what players pay to buy this item) */
    var buyPrice: Double,
    
    /** Sell price (what players receive when selling this item) */
    var sellPrice: Double,
    
    /** Permission required to buy this item (empty = no permission required) */
    var permission: String = "",
    
    /** Stock limit per player (-1 = unlimited) */
    var stockLimit: Int = -1,
    
    /** Slot position in the category (for manual ordering) */
    var slot: Int = -1,
    
    /** Whether this item is enabled in the shop */
    var enabled: Boolean = true
) {
    companion object {
        /**
         * Create a CustomShopItem from an ItemStack.
         * This captures ALL data from the item, including NBT from custom plugins.
         */
        fun fromItemStack(itemStack: ItemStack, buyPrice: Double, sellPrice: Double): CustomShopItem {
            val isCustom = isCustomItem(itemStack)
            val serialized = if (isCustom) serializeItem(itemStack) else null
            val displayName = getDisplayName(itemStack)
            val id = generateId(itemStack)
            
            return CustomShopItem(
                id = id,
                material = itemStack.type,
                isCustomItem = isCustom,
                serializedData = serialized,
                displayName = displayName,
                buyPrice = buyPrice,
                sellPrice = sellPrice
            )
        }
        
        /**
         * Create a CustomShopItem for a vanilla item (no custom NBT).
         */
        fun fromMaterial(material: Material, buyPrice: Double, sellPrice: Double): CustomShopItem {
            return CustomShopItem(
                id = material.name.lowercase(),
                material = material,
                isCustomItem = false,
                serializedData = null,
                displayName = prettyName(material),
                buyPrice = buyPrice,
                sellPrice = sellPrice
            )
        }
        
        /**
         * Check if an ItemStack is a "custom" item (has non-standard NBT data).
         * Custom items need full serialization; vanilla items don't.
         */
        fun isCustomItem(itemStack: ItemStack): Boolean {
            val meta = itemStack.itemMeta ?: return false
            
            // Check for indicators of custom items
            return meta.hasDisplayName() ||
                   meta.hasLore() ||
                   meta.hasEnchants() ||
                   meta.hasCustomModelData() ||
                   meta.hasAttributeModifiers() ||
                   (meta.persistentDataContainer.keys.isNotEmpty())
        }
        
        /**
         * Serialize an ItemStack to Base64 string.
         * This preserves ALL data including custom NBT from plugins.
         */
        fun serializeItem(itemStack: ItemStack): String {
            return try {
                ByteArrayOutputStream().use { outputStream ->
                    BukkitObjectOutputStream(outputStream).use { dataOutput ->
                        dataOutput.writeObject(itemStack)
                    }
                    Base64Coder.encodeLines(outputStream.toByteArray())
                }
            } catch (e: Exception) {
                // Fallback: try YAML serialization
                val yaml = org.bukkit.configuration.file.YamlConfiguration()
                yaml.set("item", itemStack)
                Base64Coder.encodeLines(yaml.saveToString().toByteArray())
            }
        }
        
        /**
         * Deserialize an ItemStack from Base64 string.
         */
        fun deserializeItem(data: String): ItemStack? {
            return try {
                // Remove any whitespace from the Base64 string (YAML may add newlines)
                val cleanData = data.replace(Regex("\\s"), "")
                val bytes = Base64Coder.decodeLines(cleanData)
                ByteArrayInputStream(bytes).use { inputStream ->
                    BukkitObjectInputStream(inputStream).use { dataInput ->
                        dataInput.readObject() as ItemStack
                    }
                }
            } catch (e: Exception) {
                // Try YAML fallback
                try {
                    val cleanData = data.replace(Regex("\\s"), "")
                    val bytes = Base64Coder.decodeLines(cleanData)
                    val yaml = org.bukkit.configuration.file.YamlConfiguration()
                    yaml.loadFromString(String(bytes))
                    yaml.getItemStack("item")
                } catch (e2: Exception) {
                    null
                }
            }
        }
        
        /**
         * Get display name from ItemStack, or generate one from material.
         */
        fun getDisplayName(itemStack: ItemStack): String {
            val meta = itemStack.itemMeta
            return if (meta?.hasDisplayName() == true) {
                // Strip color codes for storage reference
                org.bukkit.ChatColor.stripColor(meta.displayName) ?: prettyName(itemStack.type)
            } else {
                prettyName(itemStack.type)
            }
        }
        
        /**
         * Generate a unique ID for an item.
         */
        fun generateId(itemStack: ItemStack): String {
            val baseName = itemStack.type.name.lowercase()
            val meta = itemStack.itemMeta
            
            return if (meta?.hasDisplayName() == true) {
                val cleanName = (org.bukkit.ChatColor.stripColor(meta.displayName) ?: baseName)
                    .lowercase()
                    .replace(Regex("[^a-z0-9]"), "_")
                    .take(32)
                "${baseName}_${cleanName}_${System.currentTimeMillis() % 10000}"
            } else if (meta?.hasCustomModelData() == true) {
                "${baseName}_cmd${meta.customModelData}"
            } else {
                baseName
            }
        }
        
        /**
         * Pretty format material name.
         */
        fun prettyName(material: Material): String {
            return material.name.lowercase()
                .split('_')
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
        }
    }
    
    /**
     * Get the ItemStack for this shop item.
     * For custom items, deserializes from Base64.
     * For vanilla items, creates a new ItemStack.
     */
    fun toItemStack(amount: Int = 1): ItemStack {
        return if (isCustomItem && serializedData != null) {
            val item = deserializeItem(serializedData)?.clone() ?: ItemStack(material, amount)
            item.amount = amount
            item
        } else {
            ItemStack(material, amount)
        }
    }
    
    /**
     * Get the original lore from the custom item (if any).
     */
    fun getOriginalLore(): List<String> {
        if (!isCustomItem || serializedData == null) return emptyList()
        
        val item = deserializeItem(serializedData) ?: return emptyList()
        return item.itemMeta?.lore ?: emptyList()
    }
    
    /**
     * Check if this item matches another ItemStack.
     * Used for selling - need to match the exact item.
     */
    fun matches(itemStack: ItemStack): Boolean {
        if (itemStack.type != material) return false
        
        if (!isCustomItem) {
            // For vanilla items, just match the material (and no custom data)
            return !isCustomItem(itemStack)
        }
        
        // For custom items, compare serialized data
        val otherSerialized = serializeItem(itemStack)
        return serializedData == otherSerialized
    }
    
    /**
     * Create a market-compatible ID for this item.
     * Used for integration with items.yml and market.db.
     * 
     * Format: MATERIAL_NAME for vanilla, MATERIAL_NAME_customid for custom
     */
    fun getMarketId(): String {
        return if (isCustomItem) {
            "${material.name}_${id.uppercase()}"
        } else {
            material.name
        }
    }
}

/**
 * Category mode - determines how items are populated in a category.
 */
enum class CategoryMode {
    /** Items are auto-populated based on a filter (ORES, FARMING, etc.) */
    FILTER,
    
    /** Items are manually added by admin via drag-and-drop */
    MANUAL
}

/**
 * Extended category configuration that supports both FILTER and MANUAL modes.
 */
data class EditableShopCategory(
    val id: String,
    var name: String,
    var icon: Material,
    var iconName: String,
    var iconLore: List<String>,
    var pageTitle: String,
    var pageSize: Int,
    var itemSlots: IntRange,
    var fillEmpty: Boolean,
    var emptyMaterial: Material,
    var sortOrder: Int,
    
    /** Category mode - FILTER or MANUAL */
    var mode: CategoryMode,
    
    /** Filter type (only used when mode = FILTER) */
    var filter: org.lokixcz.theendex.shop.MarketCategoryFilter,
    
    /** Custom items (only used when mode = MANUAL) */
    val customItems: MutableList<CustomShopItem> = mutableListOf()
) {
    /**
     * Convert to standard ShopCategory for use in CustomShopGUI.
     */
    fun toShopCategory(): org.lokixcz.theendex.shop.ShopCategory {
        return org.lokixcz.theendex.shop.ShopCategory(
            id = id,
            name = name,
            icon = icon,
            iconName = iconName,
            iconLore = iconLore,
            pageTitle = pageTitle,
            pageSize = pageSize,
            itemSlots = itemSlots,
            fillEmpty = fillEmpty,
            emptyMaterial = emptyMaterial,
            sortOrder = sortOrder,
            filter = filter
        )
    }
    
    companion object {
        /**
         * Create from existing ShopCategory.
         */
        fun fromShopCategory(category: org.lokixcz.theendex.shop.ShopCategory): EditableShopCategory {
            return EditableShopCategory(
                id = category.id,
                name = category.name,
                icon = category.icon,
                iconName = category.iconName,
                iconLore = category.iconLore,
                pageTitle = category.pageTitle,
                pageSize = category.pageSize,
                itemSlots = category.itemSlots,
                fillEmpty = category.fillEmpty,
                emptyMaterial = category.emptyMaterial,
                sortOrder = category.sortOrder,
                mode = CategoryMode.FILTER,  // Default to filter mode for existing categories
                filter = category.filter
            )
        }
        
        /**
         * Create a new empty manual category.
         */
        fun createManual(
            id: String,
            name: String,
            icon: Material = Material.CHEST,
            sortOrder: Int = 0
        ): EditableShopCategory {
            return EditableShopCategory(
                id = id,
                name = name,
                icon = icon,
                iconName = "&e$name",
                iconLore = listOf("&7Custom category", "&7", "&eClick to browse!"),
                pageTitle = "&8$name",
                pageSize = 54,
                itemSlots = 0..44,
                fillEmpty = false,
                emptyMaterial = Material.GRAY_STAINED_GLASS_PANE,
                sortOrder = sortOrder,
                mode = CategoryMode.MANUAL,
                filter = org.lokixcz.theendex.shop.MarketCategoryFilter.ALL
            )
        }
    }
}
