package org.lokixcz.theendex.shop

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Main shop configuration - represents a complete shop with categories.
 */
data class ShopConfig(
    val id: String,
    val title: String,
    val menuTitle: String,
    val menuSize: Int,
    val categories: Map<String, ShopCategory>,
    val menuLayout: List<ShopMenuSlot>,
    val decoration: ShopDecoration,
    val sounds: ShopSounds
)

/**
 * Supported market category filters for auto-populating items.
 * These match the filters used in the default MarketGUI.
 */
/**
 * Category filters for auto-populating items from items.yml.
 * Items are automatically categorized based on their Material type.
 */
enum class MarketCategoryFilter {
    ALL,       // All market items
    ORES,      // Ores, ingots, raw materials, and gems
    FARMING,   // Crops, seeds, farming items
    MOB_DROPS, // Items dropped by mobs
    BLOCKS,    // Building blocks
    FOOD,      // Edible items
    TOOLS,     // Tools and weapons
    ARMOR,     // Armor pieces
    REDSTONE,  // Redstone components
    POTIONS,   // Brewing ingredients
    MISC       // Everything else
}

/**
 * A category in the shop containing items.
 * Items are automatically populated from items.yml based on the filter (FILTER mode)
 * or manually defined with full ItemStack serialization (MANUAL mode).
 */
data class ShopCategory(
    val id: String,
    val name: String,
    val icon: Material,
    val iconName: String,
    val iconLore: List<String>,
    val pageTitle: String,
    val pageSize: Int,
    val itemSlots: IntRange,
    val fillEmpty: Boolean,
    val emptyMaterial: Material,
    val sortOrder: Int,
    val filter: MarketCategoryFilter = MarketCategoryFilter.ALL,  // Filter to determine which items show (FILTER mode)
    val isManualMode: Boolean = false,  // Whether this category uses manual items
    val manualItems: List<ManualShopItem> = emptyList()  // Items for MANUAL mode
)

/**
 * Represents an item in a MANUAL mode category.
 * Supports both vanilla items and custom items with full NBT serialization.
 */
data class ManualShopItem(
    /** Unique identifier for this item */
    val id: String,
    
    /** The base material type */
    val material: Material,
    
    /** Display name for reference */
    val displayName: String,
    
    /** Buy price (what players pay) */
    val buyPrice: Double,
    
    /** Sell price (what players receive) */
    val sellPrice: Double,
    
    /** Slot position in the category (-1 = auto-assign) */
    val slot: Int = -1,
    
    /** Whether this item is enabled */
    val enabled: Boolean = true,
    
    /** Whether this is a custom item with NBT data */
    val isCustomItem: Boolean = false,
    
    /** Base64-encoded ItemStack data (only for custom items) */
    val serializedData: String? = null,
    
    /** Permission required to buy this item */
    val permission: String = "",
    
    /** Stock limit per player (-1 = unlimited) */
    val stockLimit: Int = -1
) {
    /**
     * Get the ItemStack for this shop item.
     * For custom items, deserializes from Base64.
     * For vanilla items, creates a new ItemStack.
     */
    fun toItemStack(amount: Int = 1): ItemStack {
        return if (isCustomItem && serializedData != null) {
            deserializeItem(serializedData)?.clone()?.apply { this.amount = amount } 
                ?: ItemStack(material, amount)
        } else {
            ItemStack(material, amount)
        }
    }
    
    /**
     * Check if this item matches another ItemStack for selling.
     */
    fun matches(itemStack: ItemStack): Boolean {
        if (itemStack.type != material) return false
        
        if (!isCustomItem) {
            // For vanilla items, just match material and no custom data
            val meta = itemStack.itemMeta ?: return true
            return !meta.hasDisplayName() && !meta.hasCustomModelData()
        }
        
        // For custom items, compare serialized data
        val otherSerialized = serializeItem(itemStack)
        return serializedData == otherSerialized
    }
    
    companion object {
        /**
         * Serialize an ItemStack to Base64 string.
         */
        fun serializeItem(itemStack: ItemStack): String {
            return try {
                java.io.ByteArrayOutputStream().use { outputStream ->
                    org.bukkit.util.io.BukkitObjectOutputStream(outputStream).use { dataOutput ->
                        dataOutput.writeObject(itemStack)
                    }
                    org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.encodeLines(outputStream.toByteArray())
                }
            } catch (e: Exception) {
                // Fallback: try YAML serialization
                val yaml = org.bukkit.configuration.file.YamlConfiguration()
                yaml.set("item", itemStack)
                org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.encodeLines(yaml.saveToString().toByteArray())
            }
        }
        
        /**
         * Deserialize an ItemStack from Base64 string.
         */
        fun deserializeItem(data: String): ItemStack? {
            return try {
                val bytes = org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.decodeLines(data)
                java.io.ByteArrayInputStream(bytes).use { inputStream ->
                    org.bukkit.util.io.BukkitObjectInputStream(inputStream).use { dataInput ->
                        dataInput.readObject() as ItemStack
                    }
                }
            } catch (e: Exception) {
                // Try YAML fallback
                try {
                    val bytes = org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.decodeLines(data)
                    val yaml = org.bukkit.configuration.file.YamlConfiguration()
                    yaml.loadFromString(String(bytes))
                    yaml.getItemStack("item")
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}

/**
 * Slot configuration for the main menu.
 */
data class ShopMenuSlot(
    val slot: Int,
    val type: MenuSlotType,
    val categoryId: String?,
    val material: Material?,
    val name: String?,
    val lore: List<String>?
)

enum class MenuSlotType {
    CATEGORY,      // Links to a category
    DECORATION,    // Static decoration item
    EMPTY,         // Empty/air slot
    CLOSE,         // Close button
    INFO           // Information display
}

/**
 * Decoration settings for shop GUIs.
 */
data class ShopDecoration(
    val fillEmpty: Boolean,
    val emptyMaterial: Material,
    val borderMaterial: Material?,
    val borderSlots: List<Int>
)

/**
 * Sound effects for shop interactions.
 */
data class ShopSounds(
    val openMenu: String?,
    val openCategory: String?,
    val buy: String?,
    val sell: String?,
    val error: String?,
    val pageChange: String?
)

/**
 * Navigation button configuration.
 */
data class ShopNavButton(
    val slot: Int,
    val material: Material,
    val name: String,
    val lore: List<String>
)

/**
 * Sort options for items in category pages.
 */
enum class SortBy { NAME, PRICE, CHANGE }

/**
 * Page state for tracking player's current view.
 */
data class ShopPageState(
    val shopId: String,
    val categoryId: String?,
    val page: Int,
    var amountIdx: Int = 0,  // Index into amounts list [1, 8, 16, 32, 64]
    var search: String = "",  // Search filter for items
    var sort: SortBy = SortBy.NAME,  // Sort order for items
    var inDetails: Boolean = false,  // Whether viewing item details
    var detailOf: Material? = null  // Material being viewed in details
) {
    companion object {
        val AMOUNTS = listOf(1, 8, 16, 32, 64)
        
        fun mainMenu(shopId: String) = ShopPageState(shopId, null, 0)
        fun category(shopId: String, categoryId: String, page: Int = 0, amountIdx: Int = 0, search: String = "", sort: SortBy = SortBy.NAME) = 
            ShopPageState(shopId, categoryId, page, amountIdx, search, sort)
        fun detailsView(shopId: String, categoryId: String, page: Int = 0, search: String = "", material: Material, sort: SortBy = SortBy.NAME) =
            ShopPageState(shopId, categoryId, page, 0, search, sort, true, material)
    }
    
    fun isMainMenu() = categoryId == null
    
    /** Get current selected amount */
    fun getAmount(): Int = AMOUNTS[amountIdx]
    
    /** Cycle to next amount, returns the new amount */
    fun cycleAmount(): Int {
        amountIdx = (amountIdx + 1) % AMOUNTS.size
        return getAmount()
    }
    
    /** Cycle to next sort, returns the new sort */
    fun cycleSort(): SortBy {
        sort = when (sort) {
            SortBy.NAME -> SortBy.PRICE
            SortBy.PRICE -> SortBy.CHANGE
            SortBy.CHANGE -> SortBy.NAME
        }
        return sort
    }
}

/**
 * Result of a shop transaction.
 */
sealed class ShopTransactionResult {
    data class Success(val amount: Int, val totalPrice: Double, val item: Material) : ShopTransactionResult()
    data class InsufficientFunds(val required: Double, val balance: Double) : ShopTransactionResult()
    data class InsufficientItems(val required: Int, val held: Int) : ShopTransactionResult()
    data class InventoryFull(val couldFit: Int) : ShopTransactionResult()
    data class NoPermission(val permission: String) : ShopTransactionResult()
    data class NotInMarket(val material: Material) : ShopTransactionResult()
    data object ItemNotSellable : ShopTransactionResult()
    data object ShopClosed : ShopTransactionResult()
    data class Error(val message: String) : ShopTransactionResult()
}

/**
 * Helper to translate color codes in strings.
 */
fun String.colorize(): String = ChatColor.translateAlternateColorCodes('&', this)

/**
 * Helper to translate color codes in string lists.
 */
fun List<String>.colorize(): List<String> = map { it.colorize() }
