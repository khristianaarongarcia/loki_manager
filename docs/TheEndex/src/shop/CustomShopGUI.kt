package org.lokixcz.theendex.shop

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.gui.MarketActions
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.market.CustomItemConfig
import org.lokixcz.theendex.util.ItemNames
import java.util.*

/**
 * Represents an item in the shop - can be either vanilla (Material) or custom (with NBT).
 */
sealed class ShopDisplayItem {
    /** Vanilla item - just a Material from the market */
    data class Vanilla(val mat: Material) : ShopDisplayItem()
    
    /** Custom item with full NBT data from items.yml custom-items section */
    data class Custom(val config: CustomItemConfig) : ShopDisplayItem()
    
    /** Get the material for sorting/filtering purposes */
    fun getMaterial(): Material = when (this) {
        is Vanilla -> mat
        is Custom -> config.material
    }
    
    /** Get display name for sorting/search */
    fun getDisplayName(): String = when (this) {
        is Vanilla -> MarketActions.prettyName(mat)
        is Custom -> config.displayName
    }
}

/**
 * Custom Shop GUI - EconomyShopGUI-style category-based shop interface.
 * 
 * IMPORTANT: This GUI provides a DIFFERENT LAYOUT but IDENTICAL FUNCTIONALITY
 * to the default MarketGUI. All buy/sell operations go through MarketActions
 * which uses the market command system for holdings integration.
 * 
 * Only items that exist in the market (marketManager.get(mat) != null) are tradeable.
 */
class CustomShopGUI(private val plugin: Endex) : Listener {
    
    companion object {
        private const val SHOP_TITLE_PREFIX = "§8"  // Dark gray prefix for shop titles
        private const val ADMIN_PERMISSION = "endex.shop.admin"
    }
    
    // GUI type enum to track what the player is viewing
    private enum class GuiType { MAIN_MENU, CATEGORY, DETAILS, NONE }
    
    // Track player states: shopId -> categoryId -> page
    private val playerStates: MutableMap<UUID, ShopPageState> = mutableMapOf()
    
    // Track which GUI type each player has open (UUID-based, reliable across MC versions)
    private val openGuis: MutableMap<UUID, GuiType> = mutableMapOf()
    
    // Track which shop/category player is viewing
    private val viewingShop: MutableMap<UUID, String> = mutableMapOf()
    
    // Track players waiting for search input
    private val awaitingSearchInput: MutableSet<UUID> = mutableSetOf()
    
    // Track custom items being displayed for click handling
    private val displayedCustomItems: MutableMap<UUID, MutableMap<Int, CustomItemConfig>> = mutableMapOf()
    
    /**
     * Get the CustomShopManager from plugin.
     */
    private fun shopManager(): CustomShopManager? = plugin.customShopManager
    
    // Helper to serialize Adventure Component to plain text using reflection (Arclight/Spigot compatible)
    private fun serializeComponentToPlainText(component: Any): String {
        return try {
            val serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer")
            val plainTextMethod = serializerClass.getMethod("plainText")
            val serializer = plainTextMethod.invoke(null)
            val componentClass = Class.forName("net.kyori.adventure.text.Component")
            val serializeMethod = serializerClass.getMethod("serialize", componentClass)
            serializeMethod.invoke(serializer, component) as? String ?: ""
        } catch (_: Exception) {
            // Adventure API not available - try toString fallback
            component.toString()
        }
    }
    
    // Helper to get inventory view title as String (MC 1.20.1 - 1.21+ compatibility)
    private fun getViewTitleFromView(view: Any): String {
        try {
            val titleMethod = view.javaClass.getMethod("title")
            val component = titleMethod.invoke(view)
            if (component != null) {
                val result = serializeComponentToPlainText(component)
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {}
        
        try {
            val legacyMethod = view.javaClass.getMethod("getTitle")
            val result = legacyMethod.invoke(view)
            if (result is String && result.isNotEmpty()) return result
        } catch (_: Exception) {}
        
        return ""
    }
    
    /**
     * Check if a title belongs to our custom shop GUI.
     */
    private fun isOurShop(title: String): Boolean {
        val stripped = ChatColor.stripColor(title) ?: title
        // Check for our shop title patterns
        val manager = shopManager() ?: return false
        
        for ((_, shop) in manager.all()) {
            val shopTitle = ChatColor.stripColor(shop.menuTitle) ?: shop.menuTitle
            if (stripped.contains(shopTitle) || stripped.startsWith(shopTitle)) return true
            
            for ((_, cat) in shop.categories) {
                val catTitle = ChatColor.stripColor(cat.pageTitle) ?: cat.pageTitle
                if (stripped.contains(catTitle) || stripped.startsWith(catTitle)) return true
            }
        }
        
        return false
    }
    
    /**
     * Open the main shop menu for a player.
     */
    fun openMainMenu(player: Player, shopId: String? = null) {
        val manager = shopManager() ?: run {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.not-loaded")))
            return
        }
        
        val shop = if (shopId != null) manager.get(shopId) else manager.getMainShop()
        if (shop == null) {
            player.sendMessage(Lang.colorize(Lang.get("shops.not-found", "name" to (shopId ?: manager.mainShopId))))
            return
        }
        
        val inv = Bukkit.createInventory(null, shop.menuSize, shop.menuTitle)
        
        // Fill decoration if enabled
        if (shop.decoration.fillEmpty) {
            val filler = createFillerItem(shop.decoration.emptyMaterial)
            for (i in 0 until shop.menuSize) {
                inv.setItem(i, filler)
            }
        }
        
        // Place border if configured
        shop.decoration.borderMaterial?.let { borderMat ->
            val border = createFillerItem(borderMat)
            for (slot in shop.decoration.borderSlots) {
                if (slot in 0 until shop.menuSize) {
                    inv.setItem(slot, border)
                }
            }
        }
        
        // Process menu layout
        for (slotConfig in shop.menuLayout) {
            if (slotConfig.slot !in 0 until shop.menuSize) continue
            
            val item = when (slotConfig.type) {
                MenuSlotType.CATEGORY -> {
                    val category = shop.categories[slotConfig.categoryId]
                    if (category != null) {
                        createCategoryIcon(category, slotConfig.name, slotConfig.lore)
                    } else null
                }
                MenuSlotType.DECORATION -> {
                    createDecorItem(slotConfig.material ?: Material.STONE, slotConfig.name, slotConfig.lore)
                }
                MenuSlotType.CLOSE -> {
                    createCloseButton()
                }
                MenuSlotType.INFO -> {
                    createInfoItem(player, slotConfig.name, slotConfig.lore)
                }
                MenuSlotType.EMPTY -> null
            }
            
            inv.setItem(slotConfig.slot, item)
        }
        
        // If no layout defined, auto-arrange categories
        if (shop.menuLayout.isEmpty()) {
            val categories = shop.categories.values.sortedBy { it.sortOrder }
            val startSlot = 10  // Start from second row
            categories.forEachIndexed { idx, cat ->
                val slot = startSlot + idx + (idx / 7) * 2  // Skip borders
                if (slot < shop.menuSize) {
                    inv.setItem(slot, createCategoryIcon(cat))
                }
            }
        }
        
        // Track state BEFORE opening inventory (critical for event handling)
        playerStates[player.uniqueId] = ShopPageState.mainMenu(shop.id)
        viewingShop[player.uniqueId] = shop.id
        openGuis[player.uniqueId] = GuiType.MAIN_MENU
        
        // Play sound
        playSound(player, shop.sounds.openMenu)
        
        player.openInventory(inv)
    }
    
    /**
     * Open a category page.
     * 
     * In FILTER mode, this shows:
     * 1. Vanilla items from market that match the category filter
     * 2. Custom items from items.yml that have matching category-filter
     */
    fun openCategory(player: Player, shopId: String, categoryId: String, page: Int = 0, amountIdx: Int? = null, search: String? = null, sort: SortBy? = null) {
        val manager = shopManager() ?: return
        val shop = manager.get(shopId) ?: return
        val category = shop.categories[categoryId] ?: return
        
        // Preserve state from existing state if not specified
        val existingState = playerStates[player.uniqueId]
        val actualAmountIdx = amountIdx ?: existingState?.amountIdx ?: 0
        val actualSearch = search ?: existingState?.search ?: ""
        val actualSort = sort ?: existingState?.sort ?: SortBy.NAME
        
        // Get items based on category mode
        val isManual = category.isManualMode
        
        // Build the list of items to display (both vanilla and custom)
        var displayItems: List<ShopDisplayItem> = if (isManual) {
            // MANUAL mode: Get items from manually configured items in shop YAML
            category.manualItems.filter { it.enabled }.map { ShopDisplayItem.Vanilla(it.material) }
        } else {
            // FILTER mode: Get vanilla items from market + custom items from items.yml
            val vanillaItems = MarketActions.getMarketItemsByFilter(plugin, category.filter)
                .map { ShopDisplayItem.Vanilla(it) }
            
            // Also get custom items that match this category's filter
            val filterName = category.filter.name
            val customItems = plugin.itemsConfigManager.getCustomItemsByFilter(filterName)
                .map { ShopDisplayItem.Custom(it) }
            
            // Also include custom items specifically assigned to this shop/category
            val categoryCustomItems = plugin.itemsConfigManager.getCustomItemsForCategory(shopId, categoryId)
                .map { ShopDisplayItem.Custom(it) }
            
            // Combine all items (dedupe by ID for custom items)
            val allCustom = (customItems + categoryCustomItems).distinctBy { 
                (it as ShopDisplayItem.Custom).config.id 
            }
            
            vanillaItems + allCustom
        }
        
        // Apply search filter if set
        if (actualSearch.isNotBlank()) {
            displayItems = displayItems.filter { item ->
                item.getDisplayName().contains(actualSearch, ignoreCase = true) ||
                item.getMaterial().name.contains(actualSearch, ignoreCase = true)
            }
        }
        
        // Apply sorting
        displayItems = when (actualSort) {
            SortBy.NAME -> displayItems.sortedBy { it.getDisplayName().lowercase() }
            SortBy.PRICE -> displayItems.sortedByDescending { item ->
                when (item) {
                    is ShopDisplayItem.Vanilla -> plugin.marketManager.get(item.mat)?.currentPrice ?: 0.0
                    is ShopDisplayItem.Custom -> item.config.basePrice
                }
            }
            SortBy.CHANGE -> displayItems.sortedByDescending { item ->
                when (item) {
                    is ShopDisplayItem.Vanilla -> {
                        val mi = plugin.marketManager.get(item.mat) ?: return@sortedByDescending 0.0
                        val history = mi.history.toList()
                        if (history.size < 2) 0.0
                        else {
                            val prev = history[history.lastIndex - 1].price
                            val curr = history.last().price
                            if (prev != 0.0) (curr - prev) / prev * 100.0 else 0.0
                        }
                    }
                    is ShopDisplayItem.Custom -> 0.0  // Custom items don't have price history yet
                }
            }
        }
        
        val itemsPerPage = manager.itemsPerPage
        val totalPages = if (displayItems.isEmpty()) 1 else ((displayItems.size - 1) / itemsPerPage + 1)
        val safePage = page.coerceIn(0, totalPages - 1)
        
        // Calculate page items
        val from = safePage * itemsPerPage
        val to = (from + itemsPerPage).coerceAtMost(displayItems.size)
        val pageItems = if (from < displayItems.size) displayItems.subList(from, to) else emptyList()
        
        // For MANUAL mode, we also need the ManualShopItem data for custom items
        val manualItemsMap = if (isManual) {
            category.manualItems.associateBy { it.material }
        } else {
            emptyMap()
        }
        
        // Create title with page indicator, sort, and search info
        val modeIndicator = if (isManual) "${ChatColor.GOLD}[M] " else ""
        val sortInfo = "[${actualSort.name}]"
        val searchInfo = if (actualSearch.isNotBlank()) " ${ChatColor.YELLOW}[${actualSearch}]" else ""
        val title = "$modeIndicator${category.pageTitle} ${ChatColor.DARK_GRAY}$sortInfo (${safePage + 1}/$totalPages)$searchInfo"
        val inv = Bukkit.createInventory(null, category.pageSize, title)
        
        // Fill empty slots if enabled
        if (category.fillEmpty) {
            val filler = createFillerItem(category.emptyMaterial)
            for (i in 0 until category.pageSize) {
                inv.setItem(i, filler)
            }
        }
        
        // Create state now so item creation can access amount
        val state = ShopPageState.category(shopId, categoryId, safePage, actualAmountIdx, actualSearch, actualSort)
        
        // Clear and rebuild custom items tracking for this player
        displayedCustomItems[player.uniqueId] = mutableMapOf()
        
        // Place items
        val slots = category.itemSlots.toList()
        pageItems.forEachIndexed { idx, item ->
            val slot = if (idx < slots.size) {
                slots[idx]
            } else {
                return@forEachIndexed
            }
            
            when (item) {
                is ShopDisplayItem.Vanilla -> {
                    if (isManual) {
                        // MANUAL mode: Use custom item data with full NBT support
                        val manualItem = manualItemsMap[item.mat]
                        if (manualItem != null) {
                            val itemStack = createManualShopItem(player, manualItem, state.getAmount())
                            inv.setItem(slot, itemStack)
                        }
                    } else {
                        // FILTER mode: Use MarketActions for consistent item display
                        val itemStack = MarketActions.createMarketItem(plugin, player, item.mat, state.getAmount())
                        inv.setItem(slot, itemStack)
                    }
                }
                is ShopDisplayItem.Custom -> {
                    // Custom item from items.yml - create display with pricing info
                    val itemStack = createCustomShopItem(player, item.config, state.getAmount())
                    inv.setItem(slot, itemStack)
                    // Track this custom item for click handling
                    displayedCustomItems[player.uniqueId]?.set(slot, item.config)
                }
            }
        }
        
        // Navigation buttons
        val config = plugin.config
        val showBack = config.getBoolean("shop.custom.show-back-button", true)
        val showPagination = config.getBoolean("shop.custom.show-pagination", true)
        val showSearch = config.getBoolean("shop.custom.show-search-button", true)
        val backSlot = config.getInt("shop.custom.back-button-slot", 49)
        val prevSlot = config.getInt("shop.custom.prev-page-slot", 48)
        val nextSlot = config.getInt("shop.custom.next-page-slot", 50)
        val searchSlot = config.getInt("shop.custom.search-button-slot", 45)
        val sortSlot = config.getInt("shop.custom.sort-button-slot", 46)
        val holdingsSlot = config.getInt("shop.custom.holdings-button-slot", 53)
        
        // Back button
        if (showBack) {
            inv.setItem(backSlot, createNavButton(
                Material.BARRIER,
                Lang.colorize(Lang.get("shops.gui.back-to-menu")),
                listOf(Lang.colorize(Lang.get("shops.gui.back-to-menu-lore")))
            ))
        }
        
        // Search button
        if (showSearch) {
            val searchLore = if (actualSearch.isBlank()) {
                listOf(
                    Lang.colorize(Lang.get("shops.gui.search-lore-empty")),
                    "",
                    Lang.colorize(Lang.get("shops.gui.search-lore-left")),
                    Lang.colorize(Lang.get("shops.gui.search-lore-hint"))
                )
            } else {
                listOf(
                    Lang.colorize(Lang.get("shops.gui.search-lore-current", "query" to actualSearch)),
                    Lang.colorize(Lang.get("shops.gui.search-lore-found", "count" to displayItems.size.toString())),
                    "",
                    Lang.colorize(Lang.get("shops.gui.search-lore-new")),
                    Lang.colorize(Lang.get("shops.gui.search-lore-right"))
                )
            }
            val searchTitle = if (actualSearch.isNotBlank()) {
                Lang.colorize(Lang.get("shops.gui.search-title-with", "query" to actualSearch))
            } else {
                Lang.colorize(Lang.get("shops.gui.search-title"))
            }
            inv.setItem(searchSlot, createNavButton(
                Material.COMPASS,
                searchTitle,
                searchLore
            ))
        }
        
        // Sort button
        inv.setItem(sortSlot, createNavButton(
            Material.COMPARATOR,
            Lang.colorize(Lang.get("shops.gui.sort-title", "sort" to actualSort.name)),
            listOf(
                Lang.colorize(Lang.get("shops.gui.sort-lore-current", "sort" to actualSort.name)),
                "",
                Lang.colorize(Lang.get("shops.gui.sort-lore-cycle")),
                Lang.colorize(Lang.get("shops.gui.sort-lore-options"))
            )
        ))
        
        // Holdings button
        val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
        val db = plugin.marketManager.sqliteStore()
        
        if (holdingsEnabled && db != null) {
            val holdings = db.listHoldings(player.uniqueId.toString())
            val totalCount = holdings.values.sumOf { it.first }
            val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
            
            val holdingsLore = mutableListOf<String>()
            if (totalCount > 0) {
                holdingsLore += Lang.colorize(Lang.get("shops.gui.holdings-count", "count" to totalCount.toString(), "max" to maxHoldings.toString()))
                holdingsLore += Lang.colorize(Lang.get("shops.gui.holdings-materials", "count" to holdings.size.toString()))
                holdingsLore += Lang.colorize(Lang.get("shops.gui.holdings-click-hint"))
            } else {
                holdingsLore += Lang.colorize(Lang.get("shops.gui.holdings-empty"))
                holdingsLore += Lang.colorize(Lang.get("shops.gui.holdings-empty-hint"))
            }
            
            inv.setItem(holdingsSlot, createNavButton(
                Material.CHEST,
                Lang.colorize(Lang.get("shops.gui.holdings-title")),
                holdingsLore
            ))
        }
        
        // Pagination
        if (showPagination && totalPages > 1) {
            if (safePage > 0) {
                inv.setItem(prevSlot, createNavButton(
                    Material.ARROW,
                    Lang.colorize(Lang.get("shops.gui.prev-page")),
                    listOf(Lang.colorize(Lang.get("shops.gui.prev-page-lore", "page" to safePage.toString())))
                ))
            }
            if (safePage < totalPages - 1) {
                inv.setItem(nextSlot, createNavButton(
                    Material.ARROW,
                    Lang.colorize(Lang.get("shops.gui.next-page")),
                    listOf(Lang.colorize(Lang.get("shops.gui.next-page-lore", "page" to (safePage + 2).toString())))
                ))
            }
        }
        
        // Track state BEFORE opening inventory (critical for event handling)
        playerStates[player.uniqueId] = state
        viewingShop[player.uniqueId] = shopId
        openGuis[player.uniqueId] = GuiType.CATEGORY
        
        // Play sound
        playSound(player, shop.sounds.openCategory)
        
        player.openInventory(inv)
    }
    
    /**
     * Create a category icon ItemStack.
     * Shows item count from market based on filter.
     */
    private fun createCategoryIcon(category: ShopCategory, customName: String? = null, customLore: List<String>? = null): ItemStack {
        val item = ItemStack(category.icon)
        val meta = item.itemMeta ?: return item
        
        // Use custom name from layout if provided, otherwise category default
        meta.setDisplayName(customName ?: category.iconName)
        
        // Get item count based on filter (all categories use market items)
        val itemCount = MarketActions.getMarketItemsByFilter(plugin, category.filter).size
        
        val lore = mutableListOf<String>()
        // Use custom lore from layout if provided, otherwise category default
        if (customLore != null && customLore.isNotEmpty()) {
            lore.addAll(customLore)
        } else {
            lore.addAll(category.iconLore)
        }
        lore.add("")
        lore.add(Lang.colorize(Lang.get("shops.gui.category-items", "count" to itemCount.toString())))
        lore.add(Lang.colorize(Lang.get("shops.gui.category-click")))
        meta.lore = lore
        
        item.itemMeta = meta
        return item
    }
    
    /**
     * Create a shop item with price info in lore.
     * Uses MarketActions for IDENTICAL lore to default MarketGUI.
     * All items are sourced from items.yml via market manager.
     */
    private fun createShopItem(player: Player, material: Material, state: ShopPageState): ItemStack {
        val currentAmount = state.getAmount()
        
        // Use MarketActions for consistent display (same as default MarketGUI)
        return MarketActions.createMarketItem(plugin, player, material, currentAmount)
    }
    
    /**
     * Get buy and sell prices for a material.
     * Prices always come from items.yml via marketManager.
     */
    private fun getItemPrices(material: Material): Pair<Double, Double> {
        // Get market price from items.yml via marketManager
        val marketItem = plugin.marketManager.get(material)
        if (marketItem == null) {
            // Not in market - return 0
            return 0.0 to 0.0
        }
        
        val basePrice = marketItem.currentPrice
        val eventMultiplier = plugin.eventManager.multiplierFor(material)
        val effectivePrice = basePrice * eventMultiplier
        
        // Apply spread
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkup = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5) / 100.0 else 0.0
        val sellMarkdown = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5) / 100.0 else 0.0
        
        val buyPrice = effectivePrice * (1 + buyMarkup)
        val sellPrice = effectivePrice * (1 - sellMarkdown)
        
        return buyPrice to sellPrice
    }
    
    /**
     * Create a navigation button.
     */
    private fun createNavButton(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }
    
    /**
     * Create a shop item for MANUAL mode categories.
     * Uses the custom item data including full NBT serialization for custom items.
     */
    private fun createManualShopItem(player: Player, item: ManualShopItem, amount: Int): ItemStack {
        // Get the actual ItemStack - either deserialized custom or vanilla
        val itemStack = item.toItemStack(amount)
        val meta = itemStack.itemMeta ?: return itemStack
        
        // Get dynamic price from market if available, otherwise use static price
        val marketItem = plugin.marketManager.get(item.material)
        val currentPrice = marketItem?.currentPrice ?: item.buyPrice
        val sellPrice = item.sellPrice
        
        // Build lore - preserve original lore for custom items, then add market info
        val lore = mutableListOf<String>()
        
        // Preserve original lore from custom items
        if (item.isCustomItem) {
            meta.lore?.let { originalLore ->
                lore.addAll(originalLore)
                lore.add("")  // Separator
            }
        }
        
        // Add market info
        lore.add(Lang.colorize(Lang.get("shops.gui.item-separator")))
        lore.add("")
        lore.add(Lang.colorize(Lang.get("shops.gui.item-buy-price", "price" to formatPrice(currentPrice * amount))))
        lore.add(Lang.colorize(Lang.get("shops.gui.item-sell-price", "price" to formatPrice(sellPrice * amount))))
        lore.add("")
        
        // Show dynamic price info if using market prices
        if (marketItem != null) {
            val change = if (marketItem.history.size >= 2) {
                val prev = marketItem.history.toList()[marketItem.history.size - 2].price
                val curr = marketItem.currentPrice
                if (prev != 0.0) (curr - prev) / prev * 100.0 else 0.0
            } else 0.0
            
            val changeColor = if (change >= 0) "&a" else "&c"
            val changeSymbol = if (change >= 0) "▲" else "▼"
            lore.add(Lang.colorize(Lang.get("shops.gui.item-change", "color" to changeColor, "symbol" to changeSymbol, "change" to String.format("%.1f", kotlin.math.abs(change)))))
            lore.add("")
        }
        
        // Permission warning
        if (item.permission.isNotEmpty() && !player.hasPermission(item.permission)) {
            lore.add(Lang.colorize(Lang.get("shops.gui.item-permission", "permission" to item.permission)))
            lore.add("")
        }
        
        // Click instructions
        lore.add(Lang.colorize(Lang.get("shops.gui.item-left-click", "amount" to amount.toString())))
        lore.add(Lang.colorize(Lang.get("shops.gui.item-right-click", "amount" to amount.toString())))
        lore.add(Lang.colorize(Lang.get("shops.gui.item-shift-click")))
        lore.add("")
        lore.add(Lang.colorize(Lang.get("shops.gui.item-separator")))
        
        meta.lore = lore
        itemStack.itemMeta = meta
        
        return itemStack
    }
    
    /**
     * Create a shop item for custom items from items.yml.
     * Uses the serialized ItemStack data and adds market pricing info.
     */
    private fun createCustomShopItem(player: Player, config: CustomItemConfig, amount: Int): ItemStack {
        // Deserialize the original ItemStack
        val originalItem = config.toItemStack() ?: ItemStack(config.material, amount)
        val itemStack = originalItem.clone()
        itemStack.amount = amount.coerceIn(1, 64)
        
        val meta = itemStack.itemMeta ?: return itemStack
        
        // Get dynamic price from market if available (for custom items with matching material)
        val marketItem = plugin.marketManager.get(config.material)
        val current = marketItem?.currentPrice ?: config.basePrice
        val sellPrice = config.sellPrice
        
        // Calculate price change
        val list = marketItem?.history?.toList() ?: emptyList()
        val prev = if (list.size >= 2) list[list.lastIndex - 1].price else current
        val diff = current - prev
        val pct = if (prev != 0.0) (diff / prev * 100.0) else 0.0
        val arrow = when {
            diff > 0.0001 -> "${ChatColor.GREEN}↑"
            diff < -0.0001 -> "${ChatColor.RED}↓"
            else -> "${ChatColor.YELLOW}→"
        }
        
        // Calculate buy/sell prices with spread
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        
        // Get event multiplier
        val mul = plugin.eventManager.multiplierFor(config.material)
        val effectivePrice = current * mul
        val buyPrice = effectivePrice * (1.0 + buyMarkupPct / 100.0)
        val effectiveSellPrice = sellPrice * mul * (1.0 - sellMarkdownPct / 100.0)
        
        // Player stats
        val bal = plugin.economy?.getBalance(player) ?: 0.0
        val invCount = player.inventory.contents.filterNotNull()
            .filter { it.type == config.material }
            .sumOf { it.amount }
        
        // Build lore - preserve original lore, then add market info
        val lore = mutableListOf<String>()
        
        // Preserve original lore from custom items
        meta.lore?.let { originalLore ->
            lore.addAll(originalLore)
            lore.add("")  // Separator
        }
        
        // Add market info in standard format
        lore.add("${ChatColor.GRAY}Price: ${ChatColor.GREEN}${format(current)} ${ChatColor.GRAY}($arrow ${format(diff)}, ${formatPct(pct)})")
        
        // Show buy/sell prices with spread
        if (spreadEnabled && (buyMarkupPct > 0 || sellMarkdownPct > 0)) {
            lore.add("${ChatColor.GREEN}Buy: ${format(buyPrice)} ${ChatColor.GRAY}| ${ChatColor.YELLOW}Sell: ${format(effectiveSellPrice)}")
        }
        
        // Show demand/supply if we have market data
        if (marketItem != null) {
            val ds = marketItem.lastDemand - marketItem.lastSupply
            val sens = plugin.config.getDouble("price-sensitivity", 0.05)
            val estPct = ds * sens * 100.0
            lore.add("${ChatColor.DARK_GRAY}Last cycle: ${ChatColor.GRAY}D ${format(marketItem.lastDemand)} / S ${format(marketItem.lastSupply)} (${formatPct(estPct)})")
            
            if (mul != 1.0) {
                lore.add("${ChatColor.DARK_AQUA}Event: x${format(mul)} ${ChatColor.GRAY}Eff: ${ChatColor.GREEN}${format(effectivePrice)}")
            }
            
            lore.add("${ChatColor.DARK_GRAY}Min ${format(marketItem.minPrice)}  Max ${format(marketItem.maxPrice)}")
            
            val last5 = marketItem.history.takeLast(5).map { String.format("%.2f", it.price) }
            if (last5.isNotEmpty()) {
                lore.add("${ChatColor.GRAY}History: ${last5.joinToString(" ${ChatColor.DARK_GRAY}| ${ChatColor.GRAY}")}")
            }
        } else {
            // Static pricing for items not in market
            lore.add(Lang.colorize(Lang.get("shops.gui.item-static")))
            if (mul != 1.0) {
                lore.add(Lang.colorize(Lang.get("gui.item.event_multiplier", "mul" to format(mul), "effective" to format(effectivePrice))))
            }
        }
        
        // Click instructions & player info
        lore.add(Lang.colorize(Lang.get("gui.item.click_hint", "amount" to amount.toString())))
        lore.add(Lang.colorize(Lang.get("gui.item.details_hint")))
        lore.add(Lang.colorize(Lang.get("gui.item.you_have", "count" to invCount.toString(), "material" to config.material.name)))
        lore.add(Lang.colorize(Lang.get("gui.item.balance", "balance" to format(bal))))
        
        // Custom item indicator
        lore.add("")
        lore.add(Lang.colorize(Lang.get("shops.gui.item-custom")))
        
        meta.lore = lore
        itemStack.itemMeta = meta
        
        return itemStack
    }
    
    /**
     * Create a filler/decoration item.
     */
    private fun createFillerItem(material: Material): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(" ")
        item.itemMeta = meta
        return item
    }
    
    /**
     * Create a decoration item.
     */
    private fun createDecorItem(material: Material, name: String?, lore: List<String>?): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name ?: " ")
        if (lore != null) meta.lore = lore
        item.itemMeta = meta
        return item
    }
    
    /**
     * Create a close button.
     */
    private fun createCloseButton(): ItemStack {
        return createNavButton(
            Material.BARRIER,
            Lang.colorize(Lang.get("shops.gui.close")),
            listOf(Lang.colorize(Lang.get("shops.gui.close-lore")))
        )
    }
    
    /**
     * Create an info display item.
     */
    private fun createInfoItem(player: Player, name: String?, lore: List<String>?): ItemStack {
        val bal = plugin.economy?.getBalance(player) ?: 0.0
        val actualName = name?.replace("%balance%", formatPrice(bal)) ?: Lang.colorize(Lang.get("shops.gui.balance-display", "balance" to formatPrice(bal)))
        val actualLore = lore?.map { it.replace("%balance%", formatPrice(bal)) } ?: emptyList()
        
        return createDecorItem(Material.GOLD_INGOT, actualName, actualLore)
    }
    
    /**
     * Handle inventory click events.
     * Uses the same logic as default MarketGUI for consistent behavior.
     */
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        
        // Skip if this is a shop editor GUI (editor has priority)
        val title = event.view.title
        if (title.startsWith("§4§l⚙ ")) return
        
        // Check if this player has our GUI open using UUID tracking (reliable across MC versions)
        val guiType = openGuis[player.uniqueId]
        if (guiType == null || guiType == GuiType.NONE) return
        
        // Additional safety check: verify the inventory title belongs to our shop
        // This prevents intercepting other plugins' GUIs if our tracking is stale
        if (!isOurShop(title)) {
            // Not our inventory - clear stale tracking and return
            openGuis.remove(player.uniqueId)
            playerStates.remove(player.uniqueId)
            viewingShop.remove(player.uniqueId)
            displayedCustomItems.remove(player.uniqueId)
            return
        }
        
        // Allow player inventory interaction (hotbar, etc.)
        // Check this BEFORE cancelling the event
        if (event.rawSlot >= event.view.topInventory.size) {
            return  // Don't cancel - allow normal inventory interaction
        }
        
        // Cancel the event to prevent item taking/moving in the shop GUI
        event.isCancelled = true
        
        // Block all item movement actions EXCEPT shift-left-click (for details view)
        // This matches the default MarketGUI behavior exactly
        if (event.click == ClickType.SHIFT_RIGHT ||
            event.click == ClickType.NUMBER_KEY || event.click == ClickType.DOUBLE_CLICK ||
            event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP) {
            return
        }
        
        val state = playerStates[player.uniqueId] ?: return
        
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type == Material.AIR) return
        
        // Handle based on current GUI type
        when (guiType) {
            GuiType.MAIN_MENU -> handleMainMenuClick(player, state, event.rawSlot, clickedItem, event.click)
            GuiType.CATEGORY -> handleCategoryClick(player, state, event.rawSlot, clickedItem, event.click)
            GuiType.DETAILS -> handleDetailsClick(player, state, event.rawSlot)
            GuiType.NONE -> { /* Do nothing */ }
        }
    }
    
    /**
     * Handle main menu click.
     */
    private fun handleMainMenuClick(player: Player, state: ShopPageState, slot: Int, item: ItemStack, click: ClickType) {
        val manager = shopManager() ?: return
        val shop = manager.get(state.shopId) ?: return
        
        // Check if this slot is a category in the layout
        val layoutSlot = shop.menuLayout.find { it.slot == slot }
        
        if (layoutSlot != null) {
            when (layoutSlot.type) {
                MenuSlotType.CATEGORY -> {
                    val categoryId = layoutSlot.categoryId ?: return
                    openCategory(player, state.shopId, categoryId, 0)
                }
                MenuSlotType.CLOSE -> {
                    player.closeInventory()
                }
                else -> { /* Decoration, do nothing */ }
            }
        } else {
            // Auto-layout mode - check if clicked on a category icon
            val categoryByIcon = shop.categories.values.find { it.icon == item.type }
            if (categoryByIcon != null) {
                openCategory(player, state.shopId, categoryByIcon.id, 0)
            }
        }
    }
    
    /**
     * Handle category page click.
     * Uses MarketActions for buy/sell/details - IDENTICAL to default MarketGUI.
     */
    private fun handleCategoryClick(player: Player, state: ShopPageState, slot: Int, item: ItemStack, click: ClickType) {
        val manager = shopManager() ?: return
        val shop = manager.get(state.shopId) ?: return
        val category = shop.categories[state.categoryId] ?: return
        
        val config = plugin.config
        val backSlot = config.getInt("shop.custom.back-button-slot", 49)
        val prevSlot = config.getInt("shop.custom.prev-page-slot", 48)
        val nextSlot = config.getInt("shop.custom.next-page-slot", 50)
        val searchSlot = config.getInt("shop.custom.search-button-slot", 45)
        val sortSlot = config.getInt("shop.custom.sort-button-slot", 46)
        val holdingsSlot = config.getInt("shop.custom.holdings-button-slot", 53)
        val clearSearchSlot = config.getInt("shop.custom.clear-search-slot", 53)
        
        // Get current items count from market filter for pagination
        val itemCount = MarketActions.getMarketItemsByFilter(plugin, category.filter).size
        
        // Navigation handling
        when (slot) {
            backSlot -> {
                openMainMenu(player, state.shopId)
                return
            }
            prevSlot -> {
                if (state.page > 0) {
                    openCategory(player, state.shopId, state.categoryId!!, state.page - 1, state.amountIdx, state.search, state.sort)
                    playSound(player, shop.sounds.pageChange)
                }
                return
            }
            nextSlot -> {
                val totalPages = ((itemCount - 1) / manager.itemsPerPage + 1).coerceAtLeast(1)
                if (state.page < totalPages - 1) {
                    openCategory(player, state.shopId, state.categoryId!!, state.page + 1, state.amountIdx, state.search, state.sort)
                    playSound(player, shop.sounds.pageChange)
                }
                return
            }
            searchSlot -> {
                if (click == ClickType.RIGHT || click.isRightClick) {
                    // Right-click: Clear search (if there is an active search)
                    if (state.search.isNotEmpty()) {
                        openCategory(player, state.shopId, state.categoryId!!, 0, state.amountIdx, "", state.sort)
                        player.sendMessage(Lang.colorize(Lang.get("shops.gui.search-cleared")))
                        playSound(player, Sound.UI_BUTTON_CLICK.name)
                    }
                } else {
                    // Left-click: Open search input
                    awaitingSearchInput.add(player.uniqueId)
                    player.closeInventory()
                    player.sendMessage(Lang.colorize(Lang.get("shops.gui.search-prompt")))
                    playSound(player, Sound.UI_BUTTON_CLICK.name)
                }
                return
            }
            sortSlot -> {
                // Cycle sort order
                val newSort = when (state.sort) {
                    SortBy.NAME -> SortBy.PRICE
                    SortBy.PRICE -> SortBy.CHANGE
                    SortBy.CHANGE -> SortBy.NAME
                }
                openCategory(player, state.shopId, state.categoryId!!, 0, state.amountIdx, state.search, newSort)
                playSound(player, Sound.UI_BUTTON_CLICK.name)
                return
            }
            holdingsSlot -> {
                // Open holdings via MarketGUI (same as default GUI)
                plugin.marketGUI.openHoldings(player)
                return
            }
        }
        
        // Get the material from the clicked item
        val mat = item.type
        if (mat == Material.AIR) return
        
        // Check if this is a custom item first
        val customItem = displayedCustomItems[player.uniqueId]?.get(slot)
        
        if (customItem != null) {
            // Handle custom item click - these items may not be in the market
            val amount = state.getAmount()
            
            when {
                click == ClickType.SHIFT_LEFT || click == ClickType.MIDDLE -> {
                    // For custom items, we can't use the details view since it requires market data
                    // Show a message with item info instead
                    player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-item-info-name", "name" to customItem.displayName)))
                    player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-item-info-prices", "buy" to formatPrice(customItem.basePrice * amount), "sell" to formatPrice(customItem.sellPrice * amount))))
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING.name)
                }
                click == ClickType.LEFT || click.isLeftClick -> {
                    // Buy custom item
                    buyCustomItem(player, customItem, amount) {
                        openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
                    }
                }
                click == ClickType.RIGHT || click.isRightClick -> {
                    // Sell custom item
                    sellCustomItem(player, customItem, amount) {
                        openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
                    }
                }
            }
            return
        }
        
        // CRITICAL: Only handle vanilla items that exist in the market
        // This matches the default MarketGUI behavior exactly
        if (!MarketActions.isInMarket(plugin, mat)) {
            return
        }
        
        val amount = state.getAmount()
        
        // Handle click actions - Use our custom details view for shift-click
        when {
            click == ClickType.SHIFT_LEFT || click == ClickType.MIDDLE -> {
                // Open our custom details view (NOT MarketGUI.openDetails)
                // This ensures back button returns to our custom shop
                openDetails(player, state.shopId, state.categoryId!!, mat, state.page, state.search, state.sort)
            }
            click == ClickType.LEFT || click.isLeftClick -> {
                // Buy - use MarketActions
                MarketActions.buy(plugin, player, mat, amount) {
                    openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
                }
            }
            click == ClickType.RIGHT || click.isRightClick -> {
                // Sell - use MarketActions
                MarketActions.sell(plugin, player, mat, amount) {
                    openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
                }
            }
        }
    }
    
    /**
     * Open item details view - Custom implementation with correct back navigation.
     * Matches the EXACT SAME layout as default MarketGUI.openDetails().
     * When back button is clicked, returns to our CustomShopGUI category view.
     */
    fun openDetails(player: Player, shopId: String, categoryId: String, material: Material, page: Int = 0, search: String = "", sort: SortBy = SortBy.NAME) {
        val manager = shopManager() ?: return
        val shop = manager.get(shopId) ?: return
        val category = shop.categories[categoryId] ?: return
        
        // Get market data for this item
        val mi = plugin.marketManager.get(material) ?: return
        
        // Create inventory - SAME as MarketGUI (3 rows = 27 slots)
        val inv = Bukkit.createInventory(null, 27, "${ChatColor.DARK_PURPLE}${shop.menuTitle}: ${ChatColor.AQUA}${prettyName(material)}")
        
        // Calculate price info - SAME as MarketGUI
        val mul = plugin.eventManager.multiplierFor(material)
        val current = mi.currentPrice
        val list = mi.history.toList()
        val prev = if (list.size >= 2) list[list.lastIndex - 1].price else current
        val diff = current - prev
        val pct = if (prev != 0.0) (diff / prev * 100.0) else 0.0
        val arrow = when {
            diff > 0.0001 -> "${ChatColor.GREEN}↑"
            diff < -0.0001 -> "${ChatColor.RED}↓"
            else -> "${ChatColor.YELLOW}→"
        }
        
        // Calculate buy/sell prices with spread - SAME as MarketGUI
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val effectivePrice = current * mul
        val buyPrice = effectivePrice * (1.0 + buyMarkupPct / 100.0)
        val sellPrice = effectivePrice * (1.0 - sellMarkdownPct / 100.0)
        
        val bal = plugin.economy?.getBalance(player) ?: 0.0
        val invCount = player.inventory.contents.filterNotNull().filter { it.type == material }.sumOf { it.amount }
        
        // Item display - SAME slot and lore as MarketGUI (slot 13)
        val displayItem = ItemStack(material).apply {
            itemMeta = itemMeta?.apply {
                // Use translatable name so item appears in player's Minecraft client language
                displayName(ItemNames.translatable(material, NamedTextColor.AQUA))
                val loreList = mutableListOf<String>()
                loreList += "${ChatColor.GRAY}Price: ${ChatColor.GREEN}${format(current)} ${ChatColor.GRAY}(${arrow} ${format(diff)}, ${formatPct(pct)})"
                if (spreadEnabled && (buyMarkupPct > 0 || sellMarkdownPct > 0)) {
                    loreList += "${ChatColor.GREEN}Buy: ${format(buyPrice)} ${ChatColor.GRAY}| ${ChatColor.YELLOW}Sell: ${format(sellPrice)}"
                }
                if (mul != 1.0) loreList += "${ChatColor.DARK_AQUA}Event: x${format(mul)} ${ChatColor.GRAY}Eff: ${ChatColor.GREEN}${format(current*mul)}"
                loreList += "${ChatColor.DARK_GRAY}Min ${format(mi.minPrice)}  Max ${format(mi.maxPrice)}"
                
                // Chart - SAME as MarketGUI
                if (plugin.config.getBoolean("gui.details-chart", true)) {
                    val last = mi.history.takeLast(12)
                    if (last.isNotEmpty()) {
                        val arr = last.map { it.price }
                        val min = arr.minOrNull() ?: 0.0
                        val max = arr.maxOrNull() ?: 0.0
                        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
                        val bars = arr.map {
                            val pct2 = (it - min) / span
                            when {
                                pct2 >= 0.85 -> "█"
                                pct2 >= 0.70 -> "▇"
                                pct2 >= 0.55 -> "▆"
                                pct2 >= 0.40 -> "▅"
                                pct2 >= 0.25 -> "▃"
                                pct2 >= 0.10 -> "▂"
                                else -> "▁"
                            }
                        }.joinToString("")
                        loreList += "${ChatColor.DARK_GRAY}Chart: ${ChatColor.GRAY}${bars}"
                    }
                }
                
                loreList += "${ChatColor.GRAY}You have: ${ChatColor.AQUA}$invCount"
                loreList += "${ChatColor.GRAY}Balance: ${ChatColor.GOLD}${format(bal)}"
                lore = loreList
            }
        }
        inv.setItem(13, displayItem)  // SAME slot as MarketGUI
        
        // Buttons - SAME slots and materials as MarketGUI
        inv.setItem(18, namedItem(Material.LIME_DYE, "${ChatColor.GREEN}Buy 1"))
        inv.setItem(20, namedItem(Material.EMERALD_BLOCK, "${ChatColor.GREEN}Buy 64"))
        inv.setItem(24, namedItem(Material.RED_DYE, "${ChatColor.RED}Sell 1"))
        inv.setItem(26, namedItem(Material.BARREL, "${ChatColor.RED}Sell All ($invCount)"))
        inv.setItem(22, namedItem(Material.ARROW, "${ChatColor.YELLOW}Back"))  // Back goes to custom shop, not default market
        
        // Update state
        val state = ShopPageState.detailsView(shopId, categoryId, page, search, material, sort)
        playerStates[player.uniqueId] = state
        openGuis[player.uniqueId] = GuiType.DETAILS
        viewingShop[player.uniqueId] = shopId
        
        player.openInventory(inv)
        playSound(player, shop.sounds.openCategory)
    }
    
    /**
     * Create a named item (helper, same as MarketGUI).
     */
    private fun namedItem(mat: Material, name: String): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        item.itemMeta = meta
        return item
    }
    
    /**
     * Handle details view click.
     * Uses SAME slot layout as MarketGUI.onDetailsClick().
     */
    private fun handleDetailsClick(player: Player, state: ShopPageState, slot: Int) {
        val material = state.detailOf ?: return
        
        when (slot) {
            18 -> { // Buy 1 - SAME as MarketGUI
                MarketActions.buy(plugin, player, material, 1) {
                    openDetails(player, state.shopId, state.categoryId!!, material, state.page, state.search, state.sort)
                }
            }
            20 -> { // Buy 64 - SAME as MarketGUI
                MarketActions.buy(plugin, player, material, 64) {
                    openDetails(player, state.shopId, state.categoryId!!, material, state.page, state.search, state.sort)
                }
            }
            24 -> { // Sell 1 - SAME as MarketGUI
                MarketActions.sell(plugin, player, material, 1) {
                    openDetails(player, state.shopId, state.categoryId!!, material, state.page, state.search, state.sort)
                }
            }
            26 -> { // Sell All - SAME as MarketGUI
                MarketActions.sellAll(plugin, player, material) {
                    openDetails(player, state.shopId, state.categoryId!!, material, state.page, state.search, state.sort)
                }
            }
            22 -> { // Back button - Returns to CustomShopGUI category (NOT MarketGUI!)
                openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
            }
        }
    }
    
    /**
     * Handle chat input for search functionality.
     */
    @EventHandler
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        
        // Check if this player is awaiting search input
        if (!awaitingSearchInput.contains(player.uniqueId)) return
        
        // Cancel the chat message (don't broadcast search query)
        event.isCancelled = true
        awaitingSearchInput.remove(player.uniqueId)
        
        val message = event.message.trim()
        
        // Check for cancel
        if (message.equals("cancel", ignoreCase = true)) {
            // Get previous state and reopen category
            val state = playerStates[player.uniqueId]
            if (state != null && state.categoryId != null) {
                // Run on main thread (chat event is async)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.sendMessage("${ChatColor.YELLOW}Search cancelled.")
                    openCategory(player, state.shopId, state.categoryId!!, state.page, state.amountIdx, state.search, state.sort)
                })
            }
            return
        }
        
        // Apply search filter
        val state = playerStates[player.uniqueId]
        if (state != null && state.categoryId != null) {
            // Run on main thread (chat event is async)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage("${ChatColor.GREEN}✔ ${ChatColor.GRAY}Searching for: ${ChatColor.WHITE}$message")
                // Reset to page 0 when applying new search
                openCategory(player, state.shopId, state.categoryId!!, 0, state.amountIdx, message, state.sort)
            })
        }
    }
    
    /**
     * Handle inventory drag events.
     */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        
        // Skip if this is a shop editor GUI (editor has priority)
        val title = event.view.title
        if (title.startsWith("§4§l⚙ ")) return
        
        // Check if this player has our GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType == null || guiType == GuiType.NONE) return
        
        // Additional safety check: verify the inventory title belongs to our shop
        if (!isOurShop(title)) {
            // Not our inventory - clear stale tracking and return
            openGuis.remove(player.uniqueId)
            playerStates.remove(player.uniqueId)
            viewingShop.remove(player.uniqueId)
            displayedCustomItems.remove(player.uniqueId)
            return
        }
        
        // Cancel all drag events in our GUI
        event.isCancelled = true
    }
    
    /**
     * Handle inventory close.
     */
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        
        // Check if this player had our GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType == null || guiType == GuiType.NONE) return
        
        // Check if the closed inventory was ours by checking the title
        val closedTitle = event.view.title
        if (!isOurShop(closedTitle)) {
            // Not our GUI being closed, clean up immediately since another GUI took over
            openGuis.remove(player.uniqueId)
            playerStates.remove(player.uniqueId)
            viewingShop.remove(player.uniqueId)
            displayedCustomItems.remove(player.uniqueId)
            return
        }
        
        // It was our GUI - use delayed check to handle internal GUI switching
        // (e.g., main menu -> category page triggers close event but immediately opens new inventory)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // If player's inventory is no longer our custom inventory, clean up
            val currentGuiType = openGuis[player.uniqueId] ?: return@Runnable
            
            // Check if player's current open inventory matches our expected state
            val topInv = player.openInventory.topInventory
            val newTitle = player.openInventory.title
            
            // Clean up if no GUI open OR if the new GUI isn't ours
            if (topInv.size == 0 || !isOurShop(newTitle)) {
                openGuis.remove(player.uniqueId)
                playerStates.remove(player.uniqueId)
                viewingShop.remove(player.uniqueId)
                displayedCustomItems.remove(player.uniqueId)
            }
        }, 1L)
    }
    
    /**
     * Play a sound effect.
     */
    private fun playSound(player: Player, soundName: String?) {
        if (soundName.isNullOrBlank()) return
        
        try {
            val sound = Sound.valueOf(soundName.uppercase())
            player.playSound(player.location, sound, 1.0f, 1.0f)
        } catch (e: IllegalArgumentException) {
            // Invalid sound name, ignore
        }
    }
    
    /**
     * Format a number for display (2 decimal places).
     */
    private fun format(n: Double): String = String.format("%.2f", n)
    
    /**
     * Format a percentage for display.
     */
    private fun formatPct(n: Double): String = String.format("%.2f%%", n)
    
    /**
     * Format a price for display with currency symbol.
     */
    private fun formatPrice(price: Double): String = String.format("$%.2f", price)
    
    /**
     * Get stock level as string.
     */
    private fun getStock(material: Material): String {
        val item = plugin.marketManager.get(material) ?: return "N/A"
        return String.format("%.0f", item.supply)
    }
    
    /**
     * Get demand level as string.
     */
    private fun getDemand(material: Material): String {
        val item = plugin.marketManager.get(material) ?: return "N/A"
        return String.format("%.0f", item.demand)
    }
    
    /**
     * Pretty format material name.
     */
    private fun prettyName(material: Material): String {
        return material.name.lowercase()
            .split('_')
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
    }
    
    /**
     * Buy a custom item.
     * Custom items are purchased at their configured base price.
     * The actual item given is the full serialized ItemStack with NBT data.
     */
    private fun buyCustomItem(player: Player, config: CustomItemConfig, amount: Int, onComplete: () -> Unit) {
        val economy = plugin.economy
        if (economy == null) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-economy-unavailable")))
            onComplete()
            return
        }
        
        val totalPrice = config.basePrice * amount
        val balance = economy.getBalance(player)
        
        if (balance < totalPrice) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-not-enough-money", "need" to formatPrice(totalPrice), "have" to formatPrice(balance))))
            playSound(player, Sound.ENTITY_VILLAGER_NO.name)
            onComplete()
            return
        }
        
        // Check inventory space
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-inventory-full")))
            playSound(player, Sound.ENTITY_VILLAGER_NO.name)
            onComplete()
            return
        }
        
        // Withdraw money
        val result = economy.withdrawPlayer(player, totalPrice)
        if (!result.transactionSuccess()) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-transaction-failed", "error" to result.errorMessage)))
            onComplete()
            return
        }
        
        // Give the custom item
        val itemStack = config.toItemStack()?.clone() ?: ItemStack(config.material, amount)
        itemStack.amount = amount.coerceIn(1, 64)
        
        val leftover = player.inventory.addItem(itemStack)
        if (leftover.isNotEmpty()) {
            // Drop remaining items at player's feet
            leftover.values.forEach { item ->
                player.world.dropItem(player.location, item)
            }
        }
        
        player.sendMessage("${ChatColor.GREEN}Purchased ${ChatColor.WHITE}${amount}x ${config.displayName} ${ChatColor.GREEN}for ${ChatColor.WHITE}${formatPrice(totalPrice)}")
        playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP.name)
        
        onComplete()
    }
    
    /**
     * Sell a custom item.
     * Custom items are sold at their configured sell price.
     * Requires the player to have a matching item in their inventory (with NBT data if applicable).
     * For vanilla items (no custom serialized data), also checks virtual holdings.
     */
    private fun sellCustomItem(player: Player, config: CustomItemConfig, amount: Int, onComplete: () -> Unit) {
        val economy = plugin.economy
        if (economy == null) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-economy-unavailable")))
            onComplete()
            return
        }
        
        // Find matching items in player inventory
        // For custom items, we need to match the full serialized data
        val templateItem = config.toItemStack()
        var remaining = amount
        val toRemove = mutableListOf<Pair<Int, Int>>()  // slot to amount
        
        // Determine if this is a vanilla item (no custom NBT data)
        val isVanillaItem = config.serializedData.isEmpty()
        
        for ((idx, invItem) in player.inventory.contents.withIndex()) {
            if (invItem == null || invItem.type == Material.AIR) continue
            
            // Check if this item matches the custom item
            val matches = if (config.serializedData.isNotEmpty() && templateItem != null) {
                // Custom item - check if the item is similar (same type, name, lore, etc.)
                invItem.isSimilar(templateItem)
            } else {
                // Vanilla-ish item - just match material
                invItem.type == config.material
            }
            
            if (matches) {
                val take = minOf(invItem.amount, remaining)
                toRemove.add(idx to take)
                remaining -= take
                if (remaining <= 0) break
            }
        }
        
        val inventorySold = amount - remaining
        
        // Remove items from inventory first
        for ((slotIdx, takeAmount) in toRemove) {
            val invItem = player.inventory.getItem(slotIdx) ?: continue
            if (takeAmount >= invItem.amount) {
                player.inventory.setItem(slotIdx, null)
            } else {
                invItem.amount -= takeAmount
            }
        }
        
        // For vanilla items, also check holdings if we still need more
        var holdingsSold = 0
        if (isVanillaItem && remaining > 0) {
            val db = plugin.marketManager.sqliteStore()
            if (db != null) {
                holdingsSold = db.removeFromHoldings(player.uniqueId.toString(), config.material, remaining)
                remaining -= holdingsSold
            }
        }
        
        val totalSold = inventorySold + holdingsSold
        if (totalSold == 0) {
            player.sendMessage(Lang.colorize(Lang.get("shops.gui.custom-no-item", "item" to config.displayName)))
            playSound(player, Sound.ENTITY_VILLAGER_NO.name)
            onComplete()
            return
        }
        
        // Give money
        val totalPrice = config.sellPrice * totalSold
        economy.depositPlayer(player, totalPrice)
        
        // Build detailed message
        val details = mutableListOf<String>()
        if (inventorySold > 0) details.add("${inventorySold}x from inventory")
        if (holdingsSold > 0) details.add("${holdingsSold}x from holdings")
        
        player.sendMessage("${ChatColor.GREEN}Sold ${ChatColor.WHITE}${totalSold}x ${config.displayName} ${ChatColor.GREEN}for ${ChatColor.WHITE}${formatPrice(totalPrice)} ${ChatColor.GRAY}(${details.joinToString(", ")})")
        playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP.name)
        
        onComplete()
    }
    
    /**
     * Refresh GUI for a player if viewing our shop.
     */
    fun refreshOpenFor(player: Player) {
        val state = playerStates[player.uniqueId] ?: return
        
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (state.isMainMenu()) {
                openMainMenu(player, state.shopId)
            } else {
                openCategory(player, state.shopId, state.categoryId!!, state.page)
            }
        })
    }
    
    /**
     * Refresh all viewers of custom shop.
     */
    fun refreshAllOpen() {
        plugin.server.onlinePlayers.forEach { p ->
            val guiType = openGuis[p.uniqueId]
            if (guiType != null && guiType != GuiType.NONE) {
                refreshOpenFor(p)
            }
        }
    }
}
