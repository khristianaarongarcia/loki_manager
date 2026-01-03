package org.lokixcz.theendex.shop.editor

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.*
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.market.MarketItem
import org.lokixcz.theendex.shop.ShopCategory
import org.lokixcz.theendex.shop.ShopConfig
import org.lokixcz.theendex.shop.colorize
import java.util.*

/**
 * Main Shop Editor GUI system.
 * Provides in-game interface for creating and managing custom shops.
 * 
 * GUI Flow:
 * 1. Shop Manager - List all shops, create new, delete, select to edit
 * 2. Shop Layout Editor - Edit main menu layout (slots, decorations, categories)
 * 3. Category Manager - List categories, create new, edit existing
 * 4. Category Item Editor - Drag & drop items into manual categories
 * 5. Item Price Editor - Set buy/sell prices, permissions, stock limits
 */
class ShopEditorGUI(private val plugin: Endex) : Listener {
    
    companion object {
        private const val EDITOR_PERMISSION = "endex.shop.editor"
        private const val TITLE_PREFIX = "§4§l⚙ "  // Red gear prefix for editor GUIs
    }
    
    // Editor state tracking
    private enum class EditorState {
        SHOP_MANAGER,            // Main shop list view
        SHOP_LAYOUT,             // Editing shop layout
        CATEGORY_MANAGER,        // Managing categories in a shop
        CATEGORY_ITEMS,          // Editing items in a category (drag & drop)
        ITEM_PRICE,              // Setting price for an item
        LAYOUT_CATEGORY_SELECT,  // Selecting a category for layout placement
        LAYOUT_BUTTON_SELECT,    // Selecting a button type for layout
        TEXT_INPUT,              // Waiting for chat input
        NONE
    }
    
    // Track player editor states
    private data class EditorSession(
        var state: EditorState = EditorState.NONE,
        var currentShopId: String? = null,
        var currentCategoryId: String? = null,
        var currentItem: CustomShopItem? = null,
        var pendingInputType: InputType? = null,
        var tempItems: MutableList<CustomShopItem> = mutableListOf(),
        var tempCategoryMode: CategoryMode = CategoryMode.FILTER,
        var unsavedChanges: Boolean = false,
        // Layout editor state
        var tempLayout: MutableMap<Int, LayoutSlotData> = mutableMapOf(),
        var selectedLayoutSlot: Int = -1,
        var layoutEditMode: LayoutEditMode = LayoutEditMode.PLACE_CATEGORY
    )
    
    private enum class InputType {
        SHOP_NAME,
        SHOP_TITLE,
        CATEGORY_NAME,
        CATEGORY_ID,
        ITEM_BUY_PRICE,
        ITEM_SELL_PRICE,
        ITEM_PERMISSION,
        ITEM_STOCK_LIMIT,
        LAYOUT_SLOT_NAME,
        LAYOUT_SLOT_LORE
    }
    
    /**
     * Mode for layout editing - what clicking on a slot does.
     */
    private enum class LayoutEditMode {
        PLACE_CATEGORY,     // Click to place a category icon
        PLACE_DECORATION,   // Click to place a decoration item
        PLACE_BUTTON,       // Click to place a special button (close, info, etc.)
        EDIT_SLOT,          // Click to edit slot properties
        DELETE_SLOT         // Click to clear/delete a slot
    }
    
    /**
     * Data for a single slot in the layout editor.
     */
    private data class LayoutSlotData(
        var type: String = "EMPTY",       // CATEGORY, DECORATION, CLOSE, INFO, EMPTY
        var categoryId: String? = null,   // For CATEGORY type
        var material: Material? = null,   // For DECORATION type
        var name: String? = null,         // Custom display name
        var lore: List<String>? = null    // Custom lore lines
    )
    
    private val sessions: MutableMap<UUID, EditorSession> = mutableMapOf()
    private val awaitingInput: MutableSet<UUID> = mutableSetOf()
    
    /**
     * Open the main shop manager view.
     */
    fun openShopManager(player: Player) {
        if (!player.hasPermission(EDITOR_PERMISSION)) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.no-permission")))
            return
        }
        
        val manager = plugin.customShopManager ?: run {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.not-loaded")))
            return
        }
        
        val inv = Bukkit.createInventory(null, 54, "${TITLE_PREFIX}Shop Manager")
        
        // Fill background
        val filler = createItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (i in 0 until 54) {
            inv.setItem(i, filler)
        }
        
        // Header info
        inv.setItem(4, createItem(
            Material.BOOK,
            "${ChatColor.GOLD}${ChatColor.BOLD}Shop Editor",
            listOf(
                "${ChatColor.GRAY}Create and manage custom shops",
                "",
                "${ChatColor.YELLOW}Mode: ${ChatColor.WHITE}${if (manager.isCustomMode) "CUSTOM" else "DEFAULT"}",
                "${ChatColor.YELLOW}Main Shop: ${ChatColor.WHITE}${manager.mainShopId}",
                "",
                "${ChatColor.DARK_GRAY}Click a shop to edit it",
                "${ChatColor.DARK_GRAY}or create a new one"
            )
        ))
        
        // List existing shops (slots 19-25, 28-34, 37-43)
        val shops = manager.all()
        val shopSlots = listOf(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        
        shops.entries.take(shopSlots.size).forEachIndexed { idx, (id, shop) ->
            val isMain = id == manager.mainShopId
            val icon = if (isMain) Material.GOLD_BLOCK else Material.CHEST
            
            inv.setItem(shopSlots[idx], createItem(
                icon,
                "${ChatColor.AQUA}${ChatColor.BOLD}${shop.title}",
                listOf(
                    "${ChatColor.GRAY}ID: $id",
                    "${ChatColor.GRAY}Categories: ${shop.categories.size}",
                    if (isMain) "${ChatColor.GOLD}★ Main Shop" else "",
                    "",
                    "${ChatColor.GREEN}Left-click: ${ChatColor.WHITE}Edit shop",
                    "${ChatColor.YELLOW}Middle-click: ${ChatColor.WHITE}Set as main",
                    "${ChatColor.RED}Shift+Right-click: ${ChatColor.WHITE}Delete"
                ).filter { it.isNotEmpty() }
            ))
        }
        
        // Create new shop button
        val createSlot = if (shops.size < shopSlots.size) shopSlots[shops.size] else 45
        inv.setItem(createSlot, createItem(
            Material.EMERALD,
            "${ChatColor.GREEN}${ChatColor.BOLD}+ Create New Shop",
            listOf(
                "${ChatColor.GRAY}Click to create a new",
                "${ChatColor.GRAY}custom shop",
                "",
                "${ChatColor.YELLOW}You'll be asked to enter",
                "${ChatColor.YELLOW}a name in chat"
            )
        ))
        
        // Mode toggle button (bottom left)
        val currentMode = if (manager.isCustomMode) "CUSTOM" else "DEFAULT"
        val toggleColor = if (manager.isCustomMode) ChatColor.GREEN else ChatColor.YELLOW
        inv.setItem(45, createItem(
            Material.COMPARATOR,
            "${toggleColor}${ChatColor.BOLD}Mode: $currentMode",
            listOf(
                "${ChatColor.GRAY}Current shop mode",
                "",
                if (manager.isCustomMode) {
                    "${ChatColor.GREEN}✓ CUSTOM mode active"
                } else {
                    "${ChatColor.YELLOW}→ DEFAULT mode active"
                },
                "",
                "${ChatColor.DARK_GRAY}Click to toggle mode",
                "${ChatColor.DARK_GRAY}(requires /endex reload)"
            )
        ))
        
        // Help button
        inv.setItem(49, createItem(
            Material.PAPER,
            "${ChatColor.LIGHT_PURPLE}Help",
            listOf(
                "${ChatColor.GRAY}Shop Editor Help:",
                "",
                "${ChatColor.YELLOW}DEFAULT Mode:",
                "${ChatColor.WHITE}Uses the stock market GUI",
                "${ChatColor.WHITE}Dynamic prices from market.db",
                "",
                "${ChatColor.YELLOW}CUSTOM Mode:",
                "${ChatColor.WHITE}Uses custom shop layouts",
                "${ChatColor.WHITE}Categories filter items.yml",
                "${ChatColor.WHITE}Add custom items via editor",
                "${ChatColor.WHITE}Items saved to items.yml + shop.yml"
            )
        ))
        
        // Close button
        inv.setItem(53, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Close",
            listOf("${ChatColor.GRAY}Close the editor")
        ))
        
        // Track session
        sessions[player.uniqueId] = EditorSession(state = EditorState.SHOP_MANAGER)
        
        player.openInventory(inv)
        playSound(player, Sound.BLOCK_CHEST_OPEN)
    }
    
    /**
     * Open the category manager for a specific shop.
     */
    fun openCategoryManager(player: Player, shopId: String) {
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: run {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-not-found", "shop" to shopId)))
            return
        }
        
        val inv = Bukkit.createInventory(null, 54, "${TITLE_PREFIX}Categories: ${shop.title}")
        
        // Fill background
        val filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (i in 0 until 54) {
            inv.setItem(i, filler)
        }
        
        // Header
        inv.setItem(4, createItem(
            Material.CHEST,
            "${ChatColor.GOLD}${ChatColor.BOLD}${shop.title}",
            listOf(
                "${ChatColor.GRAY}Manage shop categories",
                "",
                "${ChatColor.YELLOW}Categories: ${ChatColor.WHITE}${shop.categories.size}",
                "",
                "${ChatColor.DARK_GRAY}Click a category to edit",
                "${ChatColor.DARK_GRAY}or create a new one"
            )
        ))
        
        // List categories
        val catSlots = listOf(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        
        shop.categories.entries.sortedBy { it.value.sortOrder }.take(catSlots.size).forEachIndexed { idx, (catId, cat) ->
            inv.setItem(catSlots[idx], createItem(
                cat.icon,
                "${ChatColor.AQUA}${cat.name}",
                listOf(
                    "${ChatColor.GRAY}ID: $catId",
                    "${ChatColor.GRAY}Filter: ${cat.filter.name}",
                    "",
                    "${ChatColor.GREEN}Left-click: ${ChatColor.WHITE}Edit items",
                    "${ChatColor.YELLOW}Shift-click: ${ChatColor.WHITE}Edit settings",
                    "${ChatColor.RED}Shift+Right: ${ChatColor.WHITE}Delete"
                )
            ))
        }
        
        // Create new category button
        val createSlot = if (shop.categories.size < catSlots.size) catSlots[shop.categories.size] else 45
        inv.setItem(createSlot, createItem(
            Material.EMERALD,
            "${ChatColor.GREEN}${ChatColor.BOLD}+ Create Category",
            listOf(
                "${ChatColor.GRAY}Click to create a new category",
                "",
                "${ChatColor.YELLOW}Choose between:",
                "${ChatColor.WHITE}• FILTER mode (auto-populate)",
                "${ChatColor.WHITE}• MANUAL mode (drag & drop)"
            )
        ))
        
        // Edit layout button
        inv.setItem(46, createItem(
            Material.PAINTING,
            "${ChatColor.LIGHT_PURPLE}Edit Layout",
            listOf(
                "${ChatColor.GRAY}Edit the main menu layout",
                "",
                "${ChatColor.YELLOW}Arrange category icons",
                "${ChatColor.YELLOW}Add decorations",
                "${ChatColor.YELLOW}Change menu size"
            )
        ))
        
        // Back button
        inv.setItem(48, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back to Shops",
            listOf("${ChatColor.GRAY}Return to shop list")
        ))
        
        // Save button
        inv.setItem(50, createItem(
            Material.LIME_DYE,
            "${ChatColor.GREEN}${ChatColor.BOLD}Save Changes",
            listOf(
                "${ChatColor.GRAY}Save all changes to disk",
                "",
                "${ChatColor.DARK_GRAY}Changes are saved to",
                "${ChatColor.DARK_GRAY}shops/$shopId.yml"
            )
        ))
        
        // Close button
        inv.setItem(53, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Close",
            listOf("${ChatColor.GRAY}Close the editor")
        ))
        
        // Update session
        val session = sessions.getOrPut(player.uniqueId) { EditorSession() }
        session.state = EditorState.CATEGORY_MANAGER
        session.currentShopId = shopId
        session.currentCategoryId = null
        
        player.openInventory(inv)
        playSound(player, Sound.UI_BUTTON_CLICK)
    }
    
    /**
     * Open the layout editor for a shop.
     * Allows admins to visually arrange the main menu layout:
     * - Place category icons in specific slots
     * - Add decoration items
     * - Add special buttons (close, info, etc.)
     * - Set up borders and filler patterns
     */
    fun openLayoutEditor(player: Player, shopId: String) {
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: return
        
        // Create inventory matching shop size
        val shopSize = shop.menuSize.coerceIn(9, 54)
        val inv = Bukkit.createInventory(null, shopSize, "${TITLE_PREFIX}Layout: ${shop.title}")
        
        // Get or create session
        val session = sessions.getOrPut(player.uniqueId) { EditorSession() }
        session.state = EditorState.SHOP_LAYOUT
        session.currentShopId = shopId
        session.unsavedChanges = false
        
        // Load existing layout into temp storage
        session.tempLayout.clear()
        for (slotConfig in shop.menuLayout) {
            session.tempLayout[slotConfig.slot] = LayoutSlotData(
                type = slotConfig.type.name,
                categoryId = slotConfig.categoryId,
                material = slotConfig.material,
                name = slotConfig.name,
                lore = slotConfig.lore
            )
        }
        
        // Render the current layout
        renderLayoutEditor(inv, shop, session)
        
        player.openInventory(inv)
        playSound(player, Sound.UI_BUTTON_CLICK)
    }
    
    /**
     * Render the layout editor inventory.
     */
    private fun renderLayoutEditor(inv: Inventory, shop: ShopConfig, session: EditorSession) {
        val shopSize = inv.size
        
        // First, fill all slots as empty/editable
        for (i in 0 until shopSize) {
            val slotData = session.tempLayout[i]
            
            if (slotData != null) {
                // Render based on slot type
                when (slotData.type) {
                    "CATEGORY" -> {
                        val cat = shop.categories[slotData.categoryId]
                        if (cat != null) {
                            inv.setItem(i, createLayoutCategoryItem(cat, i, slotData))
                        } else {
                            inv.setItem(i, createLayoutEmptySlot(i))
                        }
                    }
                    "DECORATION" -> {
                        inv.setItem(i, createLayoutDecorationItem(slotData, i))
                    }
                    "CLOSE" -> {
                        inv.setItem(i, createLayoutSpecialButton("CLOSE", i))
                    }
                    "INFO" -> {
                        inv.setItem(i, createLayoutSpecialButton("INFO", i))
                    }
                    else -> {
                        inv.setItem(i, createLayoutEmptySlot(i))
                    }
                }
            } else {
                // Check shop decoration settings
                if (shop.decoration.borderSlots.contains(i) && shop.decoration.borderMaterial != null) {
                    inv.setItem(i, createLayoutBorderSlot(shop.decoration.borderMaterial, i))
                } else if (shop.decoration.fillEmpty) {
                    inv.setItem(i, createLayoutFillerSlot(shop.decoration.emptyMaterial, i))
                } else {
                    inv.setItem(i, createLayoutEmptySlot(i))
                }
            }
        }
        
        // Add control bar in the last row (if size allows)
        if (shopSize >= 54) {
            addLayoutControlBar(inv, session, shopSize)
        }
    }
    
    /**
     * Add the control bar for the layout editor.
     */
    private fun addLayoutControlBar(inv: Inventory, session: EditorSession, size: Int) {
        val controlRow = size - 9  // Last row
        
        // Mode indicator / selector (slot 45)
        val modeItem = when (session.layoutEditMode) {
            LayoutEditMode.PLACE_CATEGORY -> createItem(
                Material.CHEST,
                "${ChatColor.GOLD}Mode: ${ChatColor.WHITE}Place Category",
                listOf(
                    "${ChatColor.GRAY}Click a slot to place a category",
                    "",
                    "${ChatColor.YELLOW}Click to change mode:",
                    "${ChatColor.WHITE}• Place Category",
                    "${ChatColor.WHITE}• Place Decoration",
                    "${ChatColor.WHITE}• Place Button",
                    "${ChatColor.WHITE}• Edit Slot",
                    "${ChatColor.WHITE}• Delete Slot"
                )
            )
            LayoutEditMode.PLACE_DECORATION -> createItem(
                Material.PAINTING,
                "${ChatColor.LIGHT_PURPLE}Mode: ${ChatColor.WHITE}Place Decoration",
                listOf(
                    "${ChatColor.GRAY}Click a slot, then place an item",
                    "${ChatColor.GRAY}from your inventory to set decoration",
                    "",
                    "${ChatColor.YELLOW}Click to change mode"
                )
            )
            LayoutEditMode.PLACE_BUTTON -> createItem(
                Material.STONE_BUTTON,
                "${ChatColor.AQUA}Mode: ${ChatColor.WHITE}Place Button",
                listOf(
                    "${ChatColor.GRAY}Click a slot to add a special button:",
                    "${ChatColor.WHITE}• Close button",
                    "${ChatColor.WHITE}• Info display",
                    "",
                    "${ChatColor.YELLOW}Click to change mode"
                )
            )
            LayoutEditMode.EDIT_SLOT -> createItem(
                Material.WRITABLE_BOOK,
                "${ChatColor.GREEN}Mode: ${ChatColor.WHITE}Edit Slot",
                listOf(
                    "${ChatColor.GRAY}Click a slot to edit its properties:",
                    "${ChatColor.WHITE}• Change display name",
                    "${ChatColor.WHITE}• Edit lore",
                    "",
                    "${ChatColor.YELLOW}Click to change mode"
                )
            )
            LayoutEditMode.DELETE_SLOT -> createItem(
                Material.TNT,
                "${ChatColor.RED}Mode: ${ChatColor.WHITE}Delete Slot",
                listOf(
                    "${ChatColor.GRAY}Click a slot to clear it",
                    "",
                    "${ChatColor.YELLOW}Click to change mode"
                )
            )
        }
        inv.setItem(controlRow, modeItem)
        
        // Category selector (slot 46)
        inv.setItem(controlRow + 1, createItem(
            Material.ENDER_CHEST,
            "${ChatColor.YELLOW}Select Category",
            listOf(
                "${ChatColor.GRAY}Click to choose which category",
                "${ChatColor.GRAY}to place in the layout",
                "",
                "${ChatColor.DARK_GRAY}Used with 'Place Category' mode"
            )
        ))
        
        // Fill pattern (slot 47)
        inv.setItem(controlRow + 2, createItem(
            Material.GRAY_STAINED_GLASS_PANE,
            "${ChatColor.GRAY}Fill Pattern",
            listOf(
                "${ChatColor.GRAY}Set the background filler",
                "",
                "${ChatColor.YELLOW}Left-click: ${ChatColor.WHITE}Choose material",
                "${ChatColor.YELLOW}Right-click: ${ChatColor.WHITE}Toggle fill empty"
            )
        ))
        
        // Border setup (slot 48)
        inv.setItem(controlRow + 3, createItem(
            Material.IRON_BARS,
            "${ChatColor.AQUA}Border Setup",
            listOf(
                "${ChatColor.GRAY}Configure menu borders",
                "",
                "${ChatColor.YELLOW}Click to toggle border mode",
                "${ChatColor.DARK_GRAY}Then click slots to add/remove"
            )
        ))
        
        // Back button (slot 49)
        inv.setItem(controlRow + 4, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back",
            listOf("${ChatColor.GRAY}Return to category manager")
        ))
        
        // Preview button (slot 50)
        inv.setItem(controlRow + 5, createItem(
            Material.ENDER_EYE,
            "${ChatColor.LIGHT_PURPLE}Preview",
            listOf(
                "${ChatColor.GRAY}Preview how the shop looks",
                "${ChatColor.GRAY}to players"
            )
        ))
        
        // Undo button (slot 51)
        inv.setItem(controlRow + 6, createItem(
            Material.ORANGE_DYE,
            "${ChatColor.GOLD}Undo",
            listOf("${ChatColor.GRAY}Revert last change")
        ))
        
        // Save button (slot 52)
        val saveItem = if (session.unsavedChanges) {
            createItem(
                Material.LIME_DYE,
                "${ChatColor.GREEN}${ChatColor.BOLD}Save Layout",
                listOf(
                    "${ChatColor.YELLOW}⚠ Unsaved changes!",
                    "",
                    "${ChatColor.GRAY}Click to save layout to file"
                )
            )
        } else {
            createItem(
                Material.LIME_DYE,
                "${ChatColor.GREEN}Save Layout",
                listOf("${ChatColor.GRAY}No unsaved changes")
            )
        }
        inv.setItem(controlRow + 7, saveItem)
        
        // Cancel button (slot 53)
        inv.setItem(controlRow + 8, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Cancel",
            listOf("${ChatColor.GRAY}Discard changes")
        ))
    }
    
    /**
     * Create a layout editor item representing a category.
     */
    private fun createLayoutCategoryItem(category: ShopCategory, slot: Int, slotData: LayoutSlotData? = null): ItemStack {
        // Use custom name from slotData if set, otherwise use category name
        val displayName = slotData?.name ?: "${ChatColor.GOLD}[Category] ${category.name}"
        
        // Build lore - show custom lore if set, then add edit instructions
        val loreLines = mutableListOf<String>()
        loreLines.add("${ChatColor.GRAY}ID: ${category.id}")
        loreLines.add("${ChatColor.GRAY}Slot: $slot")
        
        // Add custom lore if set
        if (slotData?.lore != null && slotData.lore!!.isNotEmpty()) {
            loreLines.add("")
            loreLines.add("${ChatColor.WHITE}Custom Lore:")
            loreLines.addAll(slotData.lore!!.map { "${ChatColor.GRAY}$it" })
        }
        
        loreLines.add("")
        loreLines.add("${ChatColor.YELLOW}Left-click: ${ChatColor.WHITE}Move")
        loreLines.add("${ChatColor.YELLOW}Right-click: ${ChatColor.WHITE}Edit")
        loreLines.add("${ChatColor.RED}Shift+Right: ${ChatColor.WHITE}Remove")
        
        return createItem(category.icon, displayName, loreLines)
    }
    
    /**
     * Create a layout editor item representing a decoration.
     */
    private fun createLayoutDecorationItem(data: LayoutSlotData, slot: Int): ItemStack {
        val mat = data.material ?: Material.STONE
        val item = createItem(
            mat,
            data.name ?: "${ChatColor.DARK_PURPLE}[Decoration]",
            listOf(
                "${ChatColor.GRAY}Slot: $slot",
                "${ChatColor.GRAY}Material: ${mat.name}",
                "",
                "${ChatColor.YELLOW}Left-click: ${ChatColor.WHITE}Edit",
                "${ChatColor.RED}Right-click: ${ChatColor.WHITE}Remove"
            )
        )
        return item
    }
    
    /**
     * Create a layout editor item representing a special button.
     */
    private fun createLayoutSpecialButton(type: String, slot: Int): ItemStack {
        return when (type) {
            "CLOSE" -> createItem(
                Material.BARRIER,
                "${ChatColor.RED}[Close Button]",
                listOf(
                    "${ChatColor.GRAY}Slot: $slot",
                    "${ChatColor.GRAY}Closes the shop menu",
                    "",
                    "${ChatColor.RED}Right-click: ${ChatColor.WHITE}Remove"
                )
            )
            "INFO" -> createItem(
                Material.GOLD_INGOT,
                "${ChatColor.GOLD}[Info Display]",
                listOf(
                    "${ChatColor.GRAY}Slot: $slot",
                    "${ChatColor.GRAY}Shows player balance",
                    "",
                    "${ChatColor.YELLOW}Left-click: ${ChatColor.WHITE}Edit",
                    "${ChatColor.RED}Right-click: ${ChatColor.WHITE}Remove"
                )
            )
            else -> createItem(
                Material.STRUCTURE_VOID,
                "${ChatColor.GRAY}[Unknown Button]",
                listOf("${ChatColor.DARK_GRAY}Slot: $slot")
            )
        }
    }
    
    /**
     * Create an empty slot indicator for the layout editor.
     */
    private fun createLayoutEmptySlot(slot: Int): ItemStack {
        return createItem(
            Material.LIGHT_BLUE_STAINED_GLASS_PANE,
            "${ChatColor.AQUA}Empty Slot ${ChatColor.GRAY}[$slot]",
            listOf(
                "${ChatColor.GRAY}Click to place something here",
                "",
                "${ChatColor.DARK_GRAY}Current mode determines",
                "${ChatColor.DARK_GRAY}what gets placed"
            )
        )
    }
    
    /**
     * Create a border slot indicator.
     */
    private fun createLayoutBorderSlot(material: Material, slot: Int): ItemStack {
        return createItem(
            material,
            "${ChatColor.DARK_GRAY}[Border] ${ChatColor.GRAY}[$slot]",
            listOf(
                "${ChatColor.GRAY}Part of menu border",
                "",
                "${ChatColor.RED}Right-click: ${ChatColor.WHITE}Remove from border"
            )
        )
    }
    
    /**
     * Create a filler slot indicator.
     */
    private fun createLayoutFillerSlot(material: Material, slot: Int): ItemStack {
        return createItem(
            material,
            "${ChatColor.DARK_GRAY}[Filler] ${ChatColor.GRAY}[$slot]",
            listOf(
                "${ChatColor.GRAY}Background filler slot",
                "",
                "${ChatColor.YELLOW}Click to place content"
            )
        )
    }
    
    /**
     * Open the category selector for layout editor.
     */
    private fun openLayoutCategorySelector(player: Player, shopId: String) {
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: return
        val session = sessions[player.uniqueId] ?: return
        
        val inv = Bukkit.createInventory(null, 54, "${TITLE_PREFIX}Select Category")
        
        // Fill background
        val filler = createItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (i in 0 until 54) {
            inv.setItem(i, filler)
        }
        
        // List categories
        shop.categories.values.sortedBy { it.sortOrder }.forEachIndexed { idx, category ->
            if (idx < 45) {
                inv.setItem(idx, createItem(
                    category.icon,
                    "${ChatColor.YELLOW}${category.name}",
                    listOf(
                        "${ChatColor.GRAY}ID: ${category.id}",
                        "",
                        "${ChatColor.GREEN}Click to place at slot ${session.selectedLayoutSlot}"
                    )
                ))
            }
        }
        
        // Back button
        inv.setItem(49, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back to Layout",
            listOf("${ChatColor.GRAY}Return to layout editor")
        ))
        
        session.state = EditorState.LAYOUT_CATEGORY_SELECT
        player.openInventory(inv)
    }
    
    /**
     * Open the button type selector for layout editor.
     */
    private fun openLayoutButtonSelector(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        
        val inv = Bukkit.createInventory(null, 27, "${TITLE_PREFIX}Select Button Type")
        
        // Fill background
        val filler = createItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (i in 0 until 27) {
            inv.setItem(i, filler)
        }
        
        // Close button option
        inv.setItem(10, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Close Button",
            listOf(
                "${ChatColor.GRAY}Closes the shop menu",
                "",
                "${ChatColor.GREEN}Click to place at slot ${session.selectedLayoutSlot}"
            )
        ))
        
        // Info display option
        inv.setItem(12, createItem(
            Material.GOLD_INGOT,
            "${ChatColor.GOLD}Balance Display",
            listOf(
                "${ChatColor.GRAY}Shows player balance",
                "${ChatColor.GRAY}and server info",
                "",
                "${ChatColor.GREEN}Click to place"
            )
        ))
        
        // Help button option
        inv.setItem(14, createItem(
            Material.BOOK,
            "${ChatColor.AQUA}Help Button",
            listOf(
                "${ChatColor.GRAY}Shows usage instructions",
                "",
                "${ChatColor.GREEN}Click to place"
            )
        ))
        
        // Empty/Air option
        inv.setItem(16, createItem(
            Material.GLASS,
            "${ChatColor.WHITE}Empty Slot",
            listOf(
                "${ChatColor.GRAY}A transparent empty slot",
                "",
                "${ChatColor.GREEN}Click to place"
            )
        ))
        
        // Back button
        inv.setItem(22, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back",
            listOf("${ChatColor.GRAY}Return to layout editor")
        ))
        
        session.state = EditorState.LAYOUT_BUTTON_SELECT
        player.openInventory(inv)
    }

    /**
     * Open the category item editor for manual mode categories.
     * This is where users can drag & drop items to add them to the shop.
     */
    fun openCategoryItemEditor(player: Player, shopId: String, categoryId: String) {
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: return
        val category = shop.categories[categoryId] ?: return
        
        // Create a 6-row inventory
        // Top 4 rows = shop items
        // Row 5 = controls
        // Row 6 = player can see their hotbar through the GUI
        val inv = Bukkit.createInventory(null, 54, "${TITLE_PREFIX}Items: ${category.name}")
        
        // Fill top rows with current items or empty slots
        val filler = createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "${ChatColor.GRAY}Empty Slot", 
            listOf(
                "${ChatColor.DARK_GRAY}Drag an item here",
                "${ChatColor.DARK_GRAY}to add it to the shop",
                "",
                "${ChatColor.YELLOW}Supported:",
                "${ChatColor.WHITE}• Vanilla items",
                "${ChatColor.WHITE}• Custom items (any plugin)",
                "${ChatColor.WHITE}• Enchanted items"
            )
        )
        
        // Item area (slots 0-44, 5 rows)
        for (i in 0..44) {
            inv.setItem(i, filler)
        }
        
        // Get session and load existing items if any
        val session = sessions.getOrPut(player.uniqueId) { EditorSession() }
        
        // Load existing custom items into temp list from items.yml
        // Only reload if switching to a different category or first time
        val previousCategoryId = session.currentCategoryId
        
        session.state = EditorState.CATEGORY_ITEMS
        session.currentShopId = shopId
        session.currentCategoryId = categoryId
        
        if (previousCategoryId != categoryId || session.tempItems.isEmpty()) {
            session.tempItems.clear()
            
            // Load custom items from items.yml that belong to this shop/category
            val customItems = plugin.itemsConfigManager.getCustomItemsForCategory(shopId, categoryId)
            customItems.forEachIndexed { idx, config ->
                val customShopItem = CustomShopItem(
                    id = config.id,
                    material = config.material,
                    isCustomItem = config.serializedData.isNotEmpty(),
                    serializedData = config.serializedData.ifEmpty { null },
                    displayName = config.displayName,
                    buyPrice = config.basePrice,
                    sellPrice = config.sellPrice,
                    slot = idx,
                    enabled = config.enabled
                )
                session.tempItems.add(customShopItem)
            }
        }
        
        // Display items at their configured slots
        for (item in session.tempItems) {
            val displaySlot = item.slot
            if (displaySlot in 0..44) {
                val display = item.toItemStack(1)
                val meta = display.itemMeta
                if (meta != null) {
                    val existingLore = meta.lore ?: mutableListOf()
                    val newLore = existingLore.toMutableList()
                    newLore.add("")
                    newLore.add("${ChatColor.GREEN}Buy: ${ChatColor.WHITE}$${String.format("%.2f", item.buyPrice)}")
                    newLore.add("${ChatColor.RED}Sell: ${ChatColor.WHITE}$${String.format("%.2f", item.sellPrice)}")
                    if (item.permission.isNotEmpty()) {
                        newLore.add("${ChatColor.YELLOW}Permission: ${ChatColor.WHITE}${item.permission}")
                    }
                    newLore.add("")
                    newLore.add("${ChatColor.YELLOW}Click to edit price")
                    newLore.add("${ChatColor.RED}Shift+Right: Remove")
                    meta.lore = newLore
                    display.itemMeta = meta
                }
                inv.setItem(displaySlot, display)
            }
        }
        
        // Control bar (row 5, slots 45-53)
        val controlFiller = createItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (i in 45..53) {
            inv.setItem(i, controlFiller)
        }
        
        // Back button
        inv.setItem(45, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back",
            listOf("${ChatColor.GRAY}Return to categories")
        ))
        
        // Info
        inv.setItem(49, createItem(
            Material.PAPER,
            "${ChatColor.AQUA}How to Add Items",
            listOf(
                "${ChatColor.GRAY}Drag items from your",
                "${ChatColor.GRAY}inventory into the slots above",
                "",
                "${ChatColor.YELLOW}Tips:",
                "${ChatColor.WHITE}• Any item works",
                "${ChatColor.WHITE}• Custom items preserved",
                "${ChatColor.WHITE}• Set prices after adding",
                "",
                "${ChatColor.DARK_GRAY}Items: ${session.tempItems.size}"
            )
        ))
        
        // Save button
        inv.setItem(52, createItem(
            Material.LIME_DYE,
            "${ChatColor.GREEN}${ChatColor.BOLD}Save Category",
            listOf(
                "${ChatColor.GRAY}Save all items to this category",
                "",
                "${ChatColor.YELLOW}This will:",
                "${ChatColor.WHITE}• Save to shop config",
                "${ChatColor.WHITE}• Add items to items.yml",
                "${ChatColor.WHITE}• Sync with market.db"
            )
        ))
        
        // Cancel button
        inv.setItem(53, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Cancel",
            listOf("${ChatColor.GRAY}Discard changes and close")
        ))
        
        player.openInventory(inv)
        playSound(player, Sound.UI_BUTTON_CLICK)
    }
    
    /**
     * Open price editor for a specific item.
     */
    fun openItemPriceEditor(player: Player, item: CustomShopItem) {
        val inv = Bukkit.createInventory(null, 27, "${TITLE_PREFIX}Edit: ${item.displayName}")
        
        // Fill background
        val filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (i in 0 until 27) {
            inv.setItem(i, filler)
        }
        
        // Item preview (center)
        val preview = item.toItemStack(1)
        inv.setItem(4, preview)
        
        // Buy price button
        inv.setItem(10, createItem(
            Material.EMERALD,
            "${ChatColor.GREEN}Buy Price: $${String.format("%.2f", item.buyPrice)}",
            listOf(
                "${ChatColor.GRAY}What players pay to buy",
                "",
                "${ChatColor.YELLOW}Click to change",
                "${ChatColor.DARK_GRAY}Type the new price in chat"
            )
        ))
        
        // Sell price button
        inv.setItem(12, createItem(
            Material.GOLD_INGOT,
            "${ChatColor.YELLOW}Sell Price: $${String.format("%.2f", item.sellPrice)}",
            listOf(
                "${ChatColor.GRAY}What players receive when selling",
                "",
                "${ChatColor.YELLOW}Click to change",
                "${ChatColor.DARK_GRAY}Type the new price in chat"
            )
        ))
        
        // Permission button
        inv.setItem(14, createItem(
            Material.NAME_TAG,
            "${ChatColor.LIGHT_PURPLE}Permission: ${if (item.permission.isEmpty()) "None" else item.permission}",
            listOf(
                "${ChatColor.GRAY}Required permission to buy",
                "",
                "${ChatColor.YELLOW}Click to set",
                "${ChatColor.DARK_GRAY}Leave empty for no permission"
            )
        ))
        
        // Stock limit button
        inv.setItem(16, createItem(
            Material.HOPPER,
            "${ChatColor.AQUA}Stock Limit: ${if (item.stockLimit < 0) "Unlimited" else item.stockLimit}",
            listOf(
                "${ChatColor.GRAY}Max items per player",
                "",
                "${ChatColor.YELLOW}Click to change",
                "${ChatColor.DARK_GRAY}Set to -1 for unlimited"
            )
        ))
        
        // Confirm button
        inv.setItem(22, createItem(
            Material.LIME_WOOL,
            "${ChatColor.GREEN}${ChatColor.BOLD}✓ Confirm",
            listOf("${ChatColor.GRAY}Save and return")
        ))
        
        // Back button
        inv.setItem(18, createItem(
            Material.ARROW,
            "${ChatColor.YELLOW}← Back",
            listOf("${ChatColor.GRAY}Return without saving")
        ))
        
        val session = sessions[player.uniqueId] ?: return
        session.state = EditorState.ITEM_PRICE
        session.currentItem = item
        
        player.openInventory(inv)
        playSound(player, Sound.UI_BUTTON_CLICK)
    }
    
    /**
     * Open dialog to create a new category.
     */
    fun openCreateCategoryDialog(player: Player, shopId: String) {
        val inv = Bukkit.createInventory(null, 27, "${TITLE_PREFIX}Create Category")
        
        // Fill background
        val filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (i in 0 until 27) {
            inv.setItem(i, filler)
        }
        
        // Title
        inv.setItem(4, createItem(
            Material.CRAFTING_TABLE,
            "${ChatColor.GOLD}${ChatColor.BOLD}Create New Category",
            listOf(
                "${ChatColor.GRAY}Choose a category type:",
                "",
                "${ChatColor.YELLOW}FILTER Mode:",
                "${ChatColor.WHITE}Auto-populates from items.yml",
                "",
                "${ChatColor.YELLOW}MANUAL Mode:",
                "${ChatColor.WHITE}Drag & drop your own items"
            )
        ))
        
        // FILTER mode option
        inv.setItem(11, createItem(
            Material.COMPASS,
            "${ChatColor.AQUA}${ChatColor.BOLD}FILTER Mode",
            listOf(
                "${ChatColor.GRAY}Auto-populate items from market",
                "",
                "${ChatColor.YELLOW}Best for:",
                "${ChatColor.WHITE}• Standard categories (Ores, Food, etc.)",
                "${ChatColor.WHITE}• Dynamic market items",
                "",
                "${ChatColor.GREEN}Click to create"
            )
        ))
        
        // MANUAL mode option
        inv.setItem(15, createItem(
            Material.CHEST,
            "${ChatColor.GREEN}${ChatColor.BOLD}MANUAL Mode",
            listOf(
                "${ChatColor.GRAY}Drag & drop custom items",
                "",
                "${ChatColor.YELLOW}Best for:",
                "${ChatColor.WHITE}• Custom items from plugins",
                "${ChatColor.WHITE}• Special shop items",
                "${ChatColor.WHITE}• Unique enchanted items",
                "",
                "${ChatColor.GREEN}Click to create"
            )
        ))
        
        // Back button
        inv.setItem(22, createItem(
            Material.BARRIER,
            "${ChatColor.RED}Cancel",
            listOf("${ChatColor.GRAY}Return to category list")
        ))
        
        val session = sessions.getOrPut(player.uniqueId) { EditorSession() }
        session.currentShopId = shopId
        
        player.openInventory(inv)
        playSound(player, Sound.UI_BUTTON_CLICK)
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Event Handlers
    // ─────────────────────────────────────────────────────────────────────────
    
    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (session.state == EditorState.NONE) return
        
        val title = event.view.title
        if (!title.startsWith(TITLE_PREFIX)) return
        
        // Handle based on current state
        when (session.state) {
            EditorState.SHOP_MANAGER -> handleShopManagerClick(event, player, session)
            EditorState.CATEGORY_MANAGER -> handleCategoryManagerClick(event, player, session)
            EditorState.SHOP_LAYOUT -> handleLayoutEditorClick(event, player, session)
            EditorState.CATEGORY_ITEMS -> handleCategoryItemsClick(event, player, session)
            EditorState.ITEM_PRICE -> handleItemPriceClick(event, player, session)
            EditorState.LAYOUT_CATEGORY_SELECT -> handleLayoutCategorySelectClick(event, player, session)
            EditorState.LAYOUT_BUTTON_SELECT -> handleLayoutButtonSelectClick(event, player, session)
            else -> event.isCancelled = true
        }
    }
    
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (session.state == EditorState.NONE) return
        
        val title = event.view.title
        if (!title.startsWith(TITLE_PREFIX)) return
        
        // Only allow drag in category items editor, and only in item slots
        if (session.state == EditorState.CATEGORY_ITEMS) {
            val topInventorySize = event.view.topInventory.size
            val inTopSlots = event.rawSlots.any { it < topInventorySize && it < 45 }
            
            if (inTopSlots) {
                // Allow drag only in item area
                val invalidSlots = event.rawSlots.filter { it >= 45 && it < topInventorySize }
                if (invalidSlots.isNotEmpty()) {
                    event.isCancelled = true
                }
            }
        } else {
            event.isCancelled = true
        }
    }
    
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        
        // Only process if this was an editor GUI
        if (!event.view.title.startsWith(TITLE_PREFIX)) return
        
        // Delay check to handle inventory switching
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val session = sessions[player.uniqueId] ?: return@Runnable
            if (session.state == EditorState.TEXT_INPUT) return@Runnable  // Keep session for text input
            
            val openInv = player.openInventory.topInventory
            val newTitle = player.openInventory.title
            
            // Only remove session if player is no longer in an editor GUI
            if (openInv.size == 0 || !newTitle.startsWith(TITLE_PREFIX)) {
                // Player closed editor without switching to another editor view
                if (session.unsavedChanges) {
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.unsaved-warning")))
                }
                sessions.remove(player.uniqueId)
            }
        }, 1L)
    }
    
    @EventHandler
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (!awaitingInput.contains(player.uniqueId)) return
        
        event.isCancelled = true
        awaitingInput.remove(player.uniqueId)
        
        val session = sessions[player.uniqueId]
        if (session == null) {
            return
        }
        
        val input = event.message.trim()
        val inputType = session.pendingInputType
        
        // Handle cancel
        if (input.equals("cancel", ignoreCase = true)) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.input-cancelled")))
                session.pendingInputType = null
                session.selectedLayoutSlot = -1
                reopenLayoutEditor(player, session)
            })
            return
        }
        
        // Clear the pending type BEFORE processing (so handlers can set a new one)
        session.pendingInputType = null
        
        // Process input based on type
        Bukkit.getScheduler().runTask(plugin, Runnable {
            when (inputType) {
                InputType.SHOP_NAME -> handleShopNameInput(player, session, input)
                InputType.CATEGORY_NAME -> handleCategoryNameInput(player, session, input)
                InputType.CATEGORY_ID -> handleCategoryIdInput(player, session, input)
                InputType.ITEM_BUY_PRICE -> handlePriceInput(player, session, input, true)
                InputType.ITEM_SELL_PRICE -> handlePriceInput(player, session, input, false)
                InputType.ITEM_PERMISSION -> handlePermissionInput(player, session, input)
                InputType.ITEM_STOCK_LIMIT -> handleStockLimitInput(player, session, input)
                InputType.LAYOUT_SLOT_NAME -> handleLayoutSlotNameInput(player, session, input)
                InputType.LAYOUT_SLOT_LORE -> handleLayoutSlotLoreInput(player, session, input)
                else -> { }
            }
            // NOTE: Don't clear pendingInputType here - handlers may have set a new one for chained prompts
        })
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Click Handlers
    // ─────────────────────────────────────────────────────────────────────────
    
    private fun handleShopManagerClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar, etc.)
        if (slot >= topSize) {
            return  // Don't cancel - allow normal inventory interaction
        }
        
        event.isCancelled = true
        
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type == Material.AIR || clickedItem.type == Material.BLACK_STAINED_GLASS_PANE) return
        
        val manager = plugin.customShopManager ?: return
        
        when {
            // Close button
            slot == 53 && clickedItem.type == Material.BARRIER -> {
                player.closeInventory()
            }
            
            // Mode toggle
            slot == 45 && clickedItem.type == Material.COMPARATOR -> {
                // Toggle mode in config
                val newMode = if (manager.isCustomMode) "DEFAULT" else "CUSTOM"
                plugin.config.set("shop.mode", newMode)
                plugin.saveConfig()
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.mode-changed", "mode" to newMode)))
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
                openShopManager(player)  // Refresh
            }
            
            // Create new shop
            clickedItem.type == Material.EMERALD -> {
                promptForInput(player, session, InputType.SHOP_NAME,
                    "${ChatColor.GREEN}Enter the name for your new shop in chat:",
                    "${ChatColor.GRAY}(type 'cancel' to cancel)")
            }
            
            // Clicked on a shop
            clickedItem.type == Material.CHEST || clickedItem.type == Material.GOLD_BLOCK -> {
                // Get shop ID from lore
                val lore = clickedItem.itemMeta?.lore ?: return
                val idLine = lore.find { it.contains("ID:") } ?: return
                val shopId = ChatColor.stripColor(idLine)?.substringAfter("ID:")?.trim() ?: return
                
                when {
                    event.isShiftClick && event.isRightClick -> {
                        // Delete shop
                        if (shopId != manager.mainShopId) {
                            deleteShop(player, shopId)
                        } else {
                            player.sendMessage(Lang.colorize(Lang.get("shop-editor.cannot-delete-main")))
                            playSound(player, Sound.ENTITY_VILLAGER_NO)
                        }
                    }
                    event.click == ClickType.MIDDLE -> {
                        // Set as main shop
                        plugin.config.set("shop.main-shop", shopId)
                        plugin.saveConfig()
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.set-main-shop", "shop" to shopId)))
                        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
                        openShopManager(player)
                    }
                    else -> {
                        // Edit shop
                        openCategoryManager(player, shopId)
                    }
                }
            }
        }
    }
    
    private fun handleCategoryManagerClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar, etc.)
        if (slot >= topSize) {
            return  // Don't cancel - allow normal inventory interaction
        }
        
        event.isCancelled = true
        
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type == Material.AIR || clickedItem.type == Material.GRAY_STAINED_GLASS_PANE) return
        
        val shopId = session.currentShopId ?: return
        
        when {
            // Back button
            slot == 48 && clickedItem.type == Material.ARROW -> {
                openShopManager(player)
            }
            
            // Close button
            slot == 53 && clickedItem.type == Material.BARRIER -> {
                player.closeInventory()
            }
            
            // Save button
            slot == 50 && clickedItem.type == Material.LIME_DYE -> {
                saveShopChanges(player, shopId)
            }
            
            // Edit layout button
            slot == 46 && clickedItem.type == Material.PAINTING -> {
                openLayoutEditor(player, shopId)
            }
            
            // Create new category
            clickedItem.type == Material.EMERALD -> {
                openCreateCategoryDialog(player, shopId)
            }
            
            // Clicked on a category
            else -> {
                val lore = clickedItem.itemMeta?.lore ?: return
                val idLine = lore.find { it.contains("ID:") } ?: return
                val categoryId = ChatColor.stripColor(idLine)?.substringAfter("ID:")?.trim() ?: return
                
                when {
                    event.isShiftClick && event.isRightClick -> {
                        // Delete category
                        deleteCategory(player, shopId, categoryId)
                    }
                    event.isShiftClick -> {
                        // Edit category settings
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.category-settings-soon")))
                    }
                    else -> {
                        // Edit category items
                        openCategoryItemEditor(player, shopId, categoryId)
                    }
                }
            }
        }
    }
    
    private fun handleCategoryItemsClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        val clickedItem = event.currentItem
        
        // Control bar (slots 45-53) - always cancel and handle
        if (slot in 45..53) {
            event.isCancelled = true
            
            when (slot) {
                45 -> {  // Back button
                    openCategoryManager(player, session.currentShopId ?: return)
                }
                52 -> {  // Save button
                    saveCategoryItems(player, session)
                }
                53 -> {  // Cancel button
                    session.tempItems.clear()
                    session.unsavedChanges = false
                    openCategoryManager(player, session.currentShopId ?: return)
                }
            }
            return
        }
        
        // Clicking in player inventory - allow pickup
        if (slot >= topSize) {
            return  // Don't cancel - allow normal interaction
        }
        
        // Clicking in item area (0-44)
        if (slot in 0..44) {
            val current = clickedItem
            val cursor = event.cursor
            
            when {
                // Shift+Right click to remove item
                event.isShiftClick && event.isRightClick && current != null && current.type != Material.AIR 
                    && current.type != Material.LIGHT_GRAY_STAINED_GLASS_PANE -> {
                    event.isCancelled = true
                    removeItemFromSlot(player, session, slot)
                }
                
                // Left click on existing item to edit price
                event.isLeftClick && !event.isShiftClick && current != null && current.type != Material.AIR 
                    && current.type != Material.LIGHT_GRAY_STAINED_GLASS_PANE -> {
                    event.isCancelled = true
                    // Find item by slot property, not list index
                    val item = session.tempItems.find { it.slot == slot }
                    if (item != null) {
                        openItemPriceEditor(player, item)
                    }
                }
                
                // Placing item from cursor
                cursor != null && cursor.type != Material.AIR && 
                    (current == null || current.type == Material.AIR || current.type == Material.LIGHT_GRAY_STAINED_GLASS_PANE) -> {
                    event.isCancelled = true
                    addItemToSlot(player, session, slot, cursor.clone())
                    event.whoClicked.setItemOnCursor(null)  // Take item from cursor
                }
                
                // Shift-click from player inventory to add item
                event.isShiftClick && slot >= topSize -> {
                    // This is handled by not cancelling - item will be placed
                    // We'll track it in the drag event
                }
                
                else -> {
                    event.isCancelled = true
                }
            }
        }
    }
    
    /**
     * Handle clicks in the layout editor.
     */
    private fun handleLayoutEditorClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar, etc.) - needed for picking up items to place
        if (slot >= topSize) {
            // In PLACE_DECORATION mode, allow picking up items from inventory
            if (session.layoutEditMode == LayoutEditMode.PLACE_DECORATION) {
                return  // Allow normal inventory interaction
            }
            event.isCancelled = true
            return
        }
        
        event.isCancelled = true
        
        val clickedItem = event.currentItem ?: return
        val shopId = session.currentShopId ?: return
        
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: return
        
        // Control bar slots (last row)
        val controlRow = if (topSize >= 54) topSize - 9 else -1
        
        if (controlRow >= 0 && slot >= controlRow) {
            val controlSlot = slot - controlRow
            
            when (controlSlot) {
                0 -> {  // Mode selector - cycle through modes
                    session.layoutEditMode = when (session.layoutEditMode) {
                        LayoutEditMode.PLACE_CATEGORY -> LayoutEditMode.PLACE_DECORATION
                        LayoutEditMode.PLACE_DECORATION -> LayoutEditMode.PLACE_BUTTON
                        LayoutEditMode.PLACE_BUTTON -> LayoutEditMode.EDIT_SLOT
                        LayoutEditMode.EDIT_SLOT -> LayoutEditMode.DELETE_SLOT
                        LayoutEditMode.DELETE_SLOT -> LayoutEditMode.PLACE_CATEGORY
                    }
                    val inv = event.inventory
                    renderLayoutEditor(inv, shop, session)
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.mode-changed-layout", "mode" to session.layoutEditMode.name.replace("_", " "))))
                    playSound(player, Sound.UI_BUTTON_CLICK)
                }
                1 -> {  // Category selector
                    if (session.selectedLayoutSlot >= 0) {
                        openLayoutCategorySelector(player, shopId)
                    } else {
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.click-empty-slot")))
                        playSound(player, Sound.ENTITY_VILLAGER_NO)
                    }
                }
                2 -> {  // Fill pattern
                    if (event.isRightClick) {
                        // Toggle fill empty
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.fill-toggle-hint")))
                    } else {
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.drag-filler-hint")))
                    }
                }
                3 -> {  // Border setup
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.border-mode-hint")))
                    // Future: Toggle a special border editing mode
                }
                4 -> {  // Back button
                    if (session.unsavedChanges) {
                        player.sendMessage(Lang.colorize(Lang.get("shop-editor.unsaved-changes-warning")))
                    }
                    openCategoryManager(player, shopId)
                }
                5 -> {  // Preview
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.opening-preview")))
                    plugin.customShopGUI?.openMainMenu(player, shopId)
                }
                6 -> {  // Undo
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.undo-not-implemented")))
                }
                7 -> {  // Save
                    saveLayoutToFile(player, session, shopId)
                }
                8 -> {  // Cancel
                    session.tempLayout.clear()
                    session.unsavedChanges = false
                    openCategoryManager(player, shopId)
                }
            }
            return
        }
        
        // Clicked on a slot in the main area
        if (slot < controlRow || controlRow < 0) {
            handleLayoutSlotClick(event, player, session, slot, shop)
        }
    }
    
    /**
     * Handle click on a layout slot.
     */
    private fun handleLayoutSlotClick(event: InventoryClickEvent, player: Player, session: EditorSession, slot: Int, shop: ShopConfig) {
        val clickedItem = event.currentItem
        val isRightClick = event.isRightClick
        val isShiftClick = event.isShiftClick
        val shopId = session.currentShopId ?: return
        
        // Check if this slot has existing content
        val existingSlotData = session.tempLayout[slot]
        val hasExistingContent = existingSlotData != null && existingSlotData.type != "EMPTY"
        
        // If clicking on existing content, handle based on mode or click type
        if (hasExistingContent) {
            // EDIT_SLOT and DELETE_SLOT modes take priority
            when (session.layoutEditMode) {
                LayoutEditMode.EDIT_SLOT -> {
                    // Edit mode: left-click edits
                    session.selectedLayoutSlot = slot
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.editing-slot", "slot" to slot.toString(), "type" to existingSlotData!!.type)))
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.editing-slot-prompt")))
                    promptForInput(player, session, InputType.LAYOUT_SLOT_NAME,
                        "${ChatColor.GREEN}Enter new display name for slot $slot:",
                        "${ChatColor.GRAY}Current: ${existingSlotData.name ?: "none"}",
                        "${ChatColor.GRAY}(type 'cancel' to cancel, 'skip' to keep current)")
                    return
                }
                LayoutEditMode.DELETE_SLOT -> {
                    // Delete mode: left-click deletes
                    session.tempLayout.remove(slot)
                    session.unsavedChanges = true
                    val inv = event.inventory
                    renderLayoutEditor(inv, shop, session)
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.removed-slot", "slot" to slot.toString())))
                    playSound(player, Sound.ENTITY_ITEM_BREAK)
                    return
                }
                else -> {
                    // Other modes: use click-based actions
                    when {
                        isShiftClick && isRightClick -> {
                            // Shift+Right: Remove
                            session.tempLayout.remove(slot)
                            session.unsavedChanges = true
                            val inv = event.inventory
                            renderLayoutEditor(inv, shop, session)
                            player.sendMessage(Lang.colorize(Lang.get("shop-editor.removed-slot", "slot" to slot.toString())))
                            playSound(player, Sound.ENTITY_ITEM_BREAK)
                        }
                        isRightClick -> {
                            // Right-click: Edit - set slot BEFORE prompting (promptForInput closes inventory)
                            session.selectedLayoutSlot = slot
                            player.sendMessage(Lang.colorize(Lang.get("shop-editor.editing-slot", "slot" to slot.toString(), "type" to existingSlotData!!.type)))
                            player.sendMessage(Lang.colorize(Lang.get("shop-editor.editing-slot-prompt")))
                            promptForInput(player, session, InputType.LAYOUT_SLOT_NAME,
                                "${ChatColor.GREEN}Enter new display name for slot $slot:",
                                "${ChatColor.GRAY}Current: ${existingSlotData.name ?: "none"}",
                                "${ChatColor.GRAY}(type 'cancel' to cancel, 'skip' to keep current)")
                        }
                        else -> {
                            // Left-click: Move - select this slot for moving
                            session.selectedLayoutSlot = slot
                            player.sendMessage(Lang.colorize(Lang.get("shop-editor.click-move-target", "type" to existingSlotData!!.type)))
                            playSound(player, Sound.UI_BUTTON_CLICK)
                        }
                    }
                    return
                }
            }
        }
        
        // Empty slot - check if we're in the middle of a move operation
        if (session.selectedLayoutSlot >= 0 && session.selectedLayoutSlot != slot) {
            // We have a selected slot - move its content to this new slot
            val sourceData = session.tempLayout[session.selectedLayoutSlot]
            if (sourceData != null) {
                session.tempLayout.remove(session.selectedLayoutSlot)
                session.tempLayout[slot] = sourceData
                session.unsavedChanges = true
                session.selectedLayoutSlot = -1
                val inv = event.inventory
                renderLayoutEditor(inv, shop, session)
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.moved-content", "slot" to slot.toString())))
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
                return
            }
        }
        
        // Reset selected slot if clicking same slot
        if (session.selectedLayoutSlot == slot) {
            session.selectedLayoutSlot = -1
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.move-cancelled")))
            return
        }
        
        // Empty slot with no pending move - handle based on mode
        when (session.layoutEditMode) {
            LayoutEditMode.PLACE_CATEGORY -> {
                // Save the target slot and open category selector
                session.selectedLayoutSlot = slot
                openLayoutCategorySelector(player, shopId)
            }
            
            LayoutEditMode.PLACE_DECORATION -> {
                // Place item from cursor as decoration
                val cursor = event.cursor
                if (cursor != null && cursor.type != Material.AIR) {
                    session.tempLayout[slot] = LayoutSlotData(
                        type = "DECORATION",
                        material = cursor.type,
                        name = cursor.itemMeta?.displayName,
                        lore = cursor.itemMeta?.lore
                    )
                    session.unsavedChanges = true
                    
                    // Refresh
                    val inv = event.inventory
                    renderLayoutEditor(inv, shop, session)
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.placed-decoration", "slot" to slot.toString())))
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
                } else {
                    player.sendMessage(Lang.colorize(Lang.get("shop-editor.cursor-item-hint")))
                }
            }
            
            LayoutEditMode.PLACE_BUTTON -> {
                // Open button type selector
                session.selectedLayoutSlot = slot
                openLayoutButtonSelector(player)
            }
            
            LayoutEditMode.EDIT_SLOT -> {
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.slot-empty")))
            }
            
            LayoutEditMode.DELETE_SLOT -> {
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.slot-already-empty")))
            }
        }
    }
    
    /**
     * Save layout changes to the shop YAML file.
     */
    private fun saveLayoutToFile(player: Player, session: EditorSession, shopId: String) {
        val shopFile = java.io.File(plugin.dataFolder, "shops/$shopId.yml")
        if (!shopFile.exists()) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-file-not-found")))
            return
        }
        
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(shopFile)
        
        // Clear existing layout
        yaml.set("menu.layout", null)
        
        // Save new layout
        for ((slot, data) in session.tempLayout) {
            val path = "menu.layout.$slot"
            yaml.set("$path.type", data.type)
            
            when (data.type) {
                "CATEGORY" -> {
                    yaml.set("$path.category", data.categoryId)
                    // Also save custom name and lore if set
                    data.name?.let { yaml.set("$path.name", it) }
                    data.lore?.let { yaml.set("$path.lore", it) }
                }
                "DECORATION" -> {
                    yaml.set("$path.material", data.material?.name ?: "STONE")
                    data.name?.let { yaml.set("$path.name", it) }
                    data.lore?.let { yaml.set("$path.lore", it) }
                }
                "CLOSE", "INFO" -> {
                    data.material?.let { yaml.set("$path.material", it.name) }
                    data.name?.let { yaml.set("$path.name", it) }
                    data.lore?.let { yaml.set("$path.lore", it) }
                }
            }
        }
        
        yaml.save(shopFile)
        
        // Reload shops
        plugin.customShopManager?.reload()
        
        session.unsavedChanges = false
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.layout-saved", "shop" to shopId)))
        playSound(player, Sound.ENTITY_PLAYER_LEVELUP)
    }
    
    /**
     * Handle clicks in the layout category selector.
     */
    private fun handleLayoutCategorySelectClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar)
        if (slot >= topSize) {
            return
        }
        
        event.isCancelled = true
        
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type == Material.AIR || clickedItem.type == Material.BLACK_STAINED_GLASS_PANE) return
        
        val shopId = session.currentShopId ?: return
        val manager = plugin.customShopManager ?: return
        val shop = manager.get(shopId) ?: return
        
        // Back button
        if (slot == 49 && clickedItem.type == Material.ARROW) {
            session.selectedLayoutSlot = -1
            reopenLayoutEditor(player, session)
            return
        }
        
        // Clicked on a category - get the category ID from the lore
        val lore = clickedItem.itemMeta?.lore ?: return
        val idLine = lore.find { it.contains("ID:") } ?: return
        val categoryId = ChatColor.stripColor(idLine)?.substringAfter("ID:")?.trim() ?: return
        
        val category = shop.categories[categoryId] ?: return
        val targetSlot = session.selectedLayoutSlot
        
        if (targetSlot >= 0) {
            // Place category at the selected slot
            session.tempLayout[targetSlot] = LayoutSlotData(
                type = "CATEGORY",
                categoryId = categoryId,
                material = category.icon,
                name = category.name
            )
            session.unsavedChanges = true
            
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.placed-category", "category" to category.name, "slot" to targetSlot.toString())))
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
        }
        
        // Reset and return to layout editor (preserving changes)
        session.selectedLayoutSlot = -1
        reopenLayoutEditor(player, session)
    }
    
    /**
     * Handle clicks in the layout button type selector.
     */
    private fun handleLayoutButtonSelectClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar)
        if (slot >= topSize) {
            return
        }
        
        event.isCancelled = true
        
        val clickedItem = event.currentItem ?: return
        if (clickedItem.type == Material.AIR || clickedItem.type == Material.BLACK_STAINED_GLASS_PANE) return
        
        val shopId = session.currentShopId ?: return
        val targetSlot = session.selectedLayoutSlot
        
        // Back button
        if (slot == 22 && clickedItem.type == Material.ARROW) {
            session.selectedLayoutSlot = -1
            reopenLayoutEditor(player, session)
            return
        }
        
        var buttonType: String? = null
        var buttonMaterial: Material? = null
        var buttonName: String? = null
        
        when (slot) {
            10 -> {  // Close button
                buttonType = "CLOSE"
                buttonMaterial = Material.BARRIER
                buttonName = "${ChatColor.RED}Close"
            }
            12 -> {  // Balance display
                buttonType = "INFO"
                buttonMaterial = Material.GOLD_INGOT
                buttonName = "${ChatColor.GOLD}Balance"
            }
            14 -> {  // Help button
                buttonType = "HELP"
                buttonMaterial = Material.BOOK
                buttonName = "${ChatColor.AQUA}Help"
            }
            16 -> {  // Empty slot
                buttonType = "EMPTY"
                buttonMaterial = Material.AIR
                buttonName = null
            }
        }
        
        if (buttonType != null && targetSlot >= 0) {
            session.tempLayout[targetSlot] = LayoutSlotData(
                type = buttonType,
                material = buttonMaterial,
                name = buttonName
            )
            session.unsavedChanges = true
            
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.placed-button", "button" to buttonType, "slot" to targetSlot.toString())))
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
        }
        
        // Reset and return to layout editor (preserving changes)
        session.selectedLayoutSlot = -1
        reopenLayoutEditor(player, session)
    }

    private fun handleItemPriceClick(event: InventoryClickEvent, player: Player, session: EditorSession) {
        val slot = event.rawSlot
        val topSize = event.view.topInventory.size
        
        // Allow player inventory interaction (hotbar, etc.)
        if (slot >= topSize) {
            return  // Don't cancel - allow normal inventory interaction
        }
        
        event.isCancelled = true
        
        val item = session.currentItem ?: return
        
        when (slot) {
            10 -> {  // Buy price
                promptForInput(player, session, InputType.ITEM_BUY_PRICE,
                    "${ChatColor.GREEN}Enter the buy price for ${item.displayName}:",
                    "${ChatColor.GRAY}Current: $${String.format("%.2f", item.buyPrice)} (type 'cancel' to cancel)")
            }
            12 -> {  // Sell price
                promptForInput(player, session, InputType.ITEM_SELL_PRICE,
                    "${ChatColor.GREEN}Enter the sell price for ${item.displayName}:",
                    "${ChatColor.GRAY}Current: $${String.format("%.2f", item.sellPrice)} (type 'cancel' to cancel)")
            }
            14 -> {  // Permission
                promptForInput(player, session, InputType.ITEM_PERMISSION,
                    "${ChatColor.GREEN}Enter the permission node (or 'none' for no permission):",
                    "${ChatColor.GRAY}Current: ${item.permission.ifEmpty { "none" }} (type 'cancel' to cancel)")
            }
            16 -> {  // Stock limit
                promptForInput(player, session, InputType.ITEM_STOCK_LIMIT,
                    "${ChatColor.GREEN}Enter the stock limit (-1 for unlimited):",
                    "${ChatColor.GRAY}Current: ${if (item.stockLimit < 0) "unlimited" else item.stockLimit} (type 'cancel' to cancel)")
            }
            18 -> {  // Back without saving
                reopenPreviousView(player, session)
            }
            22 -> {  // Confirm
                session.unsavedChanges = true
                player.sendMessage(Lang.colorize(Lang.get("shop-editor.item-settings-saved")))
                playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP)
                reopenPreviousView(player, session)
            }
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Input Handlers
    // ─────────────────────────────────────────────────────────────────────────
    
    private fun handleShopNameInput(player: Player, session: EditorSession, input: String) {
        val shopId = input.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        
        // Check if shop already exists
        if (plugin.customShopManager?.get(shopId) != null) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-exists", "shop" to shopId)))
            openShopManager(player)
            return
        }
        
        // Create new shop
        createNewShop(player, shopId, input)
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-created", "name" to input, "id" to shopId)))
        playSound(player, Sound.ENTITY_PLAYER_LEVELUP)
        openCategoryManager(player, shopId)
    }
    
    private fun handleCategoryNameInput(player: Player, session: EditorSession, input: String) {
        session.pendingInputType = InputType.CATEGORY_ID
        val suggestedId = input.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        
        promptForInput(player, session, InputType.CATEGORY_ID,
            "${ChatColor.GREEN}Enter the category ID (suggested: $suggestedId):",
            "${ChatColor.GRAY}Or press Enter to use the suggested ID")
    }
    
    private fun handleCategoryIdInput(player: Player, session: EditorSession, input: String) {
        // TODO: Create the category
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.category-creation-soon")))
        reopenPreviousView(player, session)
    }
    
    private fun handlePriceInput(player: Player, session: EditorSession, input: String, isBuyPrice: Boolean) {
        val price = input.toDoubleOrNull()
        if (price == null || price < 0) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.invalid-price")))
            openItemPriceEditor(player, session.currentItem ?: return)
            return
        }
        
        val item = session.currentItem ?: return
        if (isBuyPrice) {
            item.buyPrice = price
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.buy-price-set", "price" to String.format("%.2f", price))))
        } else {
            item.sellPrice = price
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.sell-price-set", "price" to String.format("%.2f", price))))
        }
        
        session.unsavedChanges = true
        openItemPriceEditor(player, item)
    }
    
    private fun handlePermissionInput(player: Player, session: EditorSession, input: String) {
        val item = session.currentItem ?: return
        item.permission = if (input.equals("none", ignoreCase = true)) "" else input
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.permission-set", "permission" to item.permission.ifEmpty { "none" })))
        session.unsavedChanges = true
        openItemPriceEditor(player, item)
    }
    
    private fun handleStockLimitInput(player: Player, session: EditorSession, input: String) {
        val limit = input.toIntOrNull()
        if (limit == null) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.invalid-number")))
            openItemPriceEditor(player, session.currentItem ?: return)
            return
        }
        
        val item = session.currentItem ?: return
        item.stockLimit = limit
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.stock-limit-set", "limit" to if (limit < 0) "unlimited" else limit.toString())))
        session.unsavedChanges = true
        openItemPriceEditor(player, item)
    }
    
    private fun handleLayoutSlotNameInput(player: Player, session: EditorSession, input: String) {
        val slot = session.selectedLayoutSlot
        if (slot < 0) {
            player.sendMessage("${ChatColor.RED}No slot selected!")
            reopenLayoutEditor(player, session)
            return
        }
        
        val slotData = session.tempLayout[slot]
        if (slotData == null) {
            player.sendMessage("${ChatColor.RED}Slot is empty!")
            reopenLayoutEditor(player, session)
            return
        }
        
        // Update name unless 'skip' was entered
        if (!input.equals("skip", ignoreCase = true)) {
            slotData.name = input.colorize()
            session.unsavedChanges = true
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.slot-name-updated", "name" to input)))
        } else {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.keeping-current-name")))
        }
        
        // Now prompt for lore
        val currentLore = slotData.lore?.joinToString(" | ") ?: "none"
        promptForInput(player, session, InputType.LAYOUT_SLOT_LORE,
            "${ChatColor.GREEN}Enter lore for slot $slot:",
            "${ChatColor.GRAY}Use | to separate lines (e.g. Line 1 | Line 2 | Line 3)",
            "${ChatColor.GRAY}Current: $currentLore",
            "${ChatColor.GRAY}(type 'cancel' to cancel, 'skip' to keep current)")
    }
    
    private fun handleLayoutSlotLoreInput(player: Player, session: EditorSession, input: String) {
        val slot = session.selectedLayoutSlot
        if (slot < 0) {
            player.sendMessage("${ChatColor.RED}No slot selected!")
            reopenLayoutEditor(player, session)
            return
        }
        
        val slotData = session.tempLayout[slot]
        if (slotData == null) {
            player.sendMessage("${ChatColor.RED}Slot is empty!")
            reopenLayoutEditor(player, session)
            return
        }
        
        // Update lore unless 'skip' was entered
        if (!input.equals("skip", ignoreCase = true)) {
            // Parse lore - split by | for multiple lines
            slotData.lore = input.split("|").map { it.trim().colorize() }
            session.unsavedChanges = true
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.slot-lore-updated")))
        } else {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.keeping-current-lore")))
        }
        
        session.selectedLayoutSlot = -1
        reopenLayoutEditor(player, session)
    }
    
    private fun reopenLayoutEditor(player: Player, session: EditorSession) {
        val shopId = session.currentShopId
        
        if (shopId != null) {
            val manager = plugin.customShopManager ?: return
            val shop = manager.get(shopId) ?: return
            
            // Create inventory matching shop size - but DON'T reset the session data
            val shopSize = shop.menuSize.coerceIn(9, 54)
            val inv = Bukkit.createInventory(null, shopSize, "${TITLE_PREFIX}Layout: ${shop.title}")
            
            // Keep the session state but update to SHOP_LAYOUT
            session.state = EditorState.SHOP_LAYOUT
            // DON'T clear tempLayout or reset unsavedChanges - we want to preserve edits!
            
            // Render the current layout with our temp changes
            renderLayoutEditor(inv, shop, session)
            
            player.openInventory(inv)
            playSound(player, Sound.UI_BUTTON_CLICK)
        } else {
            openShopManager(player)
        }
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────
    
    private fun promptForInput(player: Player, session: EditorSession, type: InputType, vararg messages: String) {
        session.state = EditorState.TEXT_INPUT
        session.pendingInputType = type
        awaitingInput.add(player.uniqueId)
        
        player.closeInventory()
        messages.forEach { player.sendMessage(it) }
        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING)
    }
    
    private fun reopenPreviousView(player: Player, session: EditorSession) {
        when {
            session.currentItem != null -> {
                openCategoryItemEditor(player, session.currentShopId ?: return, session.currentCategoryId ?: return)
            }
            session.currentCategoryId != null -> {
                openCategoryManager(player, session.currentShopId ?: return)
            }
            session.currentShopId != null -> {
                openCategoryManager(player, session.currentShopId!!)
            }
            else -> {
                openShopManager(player)
            }
        }
    }
    
    private fun addItemToSlot(player: Player, session: EditorSession, slot: Int, itemStack: ItemStack) {
        // Create CustomShopItem from the ItemStack
        val defaultBuyPrice = 100.0  // Default price, user can edit
        val defaultSellPrice = 50.0
        
        val customItem = CustomShopItem.fromItemStack(itemStack, defaultBuyPrice, defaultSellPrice)
        customItem.slot = slot
        
        // Add to temp items (replacing if slot already has item)
        session.tempItems.removeIf { it.slot == slot }
        session.tempItems.add(customItem)
        session.unsavedChanges = true
        
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.item-added", "item" to customItem.displayName, "slot" to slot.toString())))
        playSound(player, Sound.ENTITY_ITEM_PICKUP)
        
        // Refresh GUI
        openCategoryItemEditor(player, session.currentShopId ?: return, session.currentCategoryId ?: return)
    }
    
    private fun removeItemFromSlot(player: Player, session: EditorSession, slot: Int) {
        val removed = session.tempItems.find { it.slot == slot }
        session.tempItems.removeIf { it.slot == slot }
        session.unsavedChanges = true
        
        if (removed != null) {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.item-removed", "item" to removed.displayName, "slot" to slot.toString())))
            playSound(player, Sound.ENTITY_ITEM_PICKUP)
        }
        
        // Refresh GUI
        openCategoryItemEditor(player, session.currentShopId ?: return, session.currentCategoryId ?: return)
    }
    
    private fun saveCategoryItems(player: Player, session: EditorSession) {
        val shopId = session.currentShopId ?: return
        val categoryId = session.currentCategoryId ?: return
        
        // TODO: Actually save to YAML and sync with items.yml / market.db
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.items-saved", "count" to session.tempItems.size.toString(), "category" to categoryId)))
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.items-saved-note")))
        
        // Sync items to items.yml and market
        syncItemsToMarket(shopId, categoryId, session.tempItems)
        
        // Save to the shop YAML file as well
        saveCategoryToShopFile(shopId, categoryId, session.tempItems, session.tempCategoryMode)
        
        session.unsavedChanges = false
        playSound(player, Sound.ENTITY_PLAYER_LEVELUP)
        
        openCategoryManager(player, shopId)
    }
    
    /**
     * Sync custom items to items.yml and market.db for dynamic pricing.
     * 
     * For vanilla items: Just adds the material to items.yml
     * For custom items: Stores full serialized data in items.yml under custom-items section
     *                   Also sets the category-filter so items appear in FILTER mode categories
     */
    private fun syncItemsToMarket(shopId: String, categoryId: String, items: List<CustomShopItem>) {
        val itemsConfig = plugin.itemsConfigManager
        val marketManager = plugin.marketManager
        val db = marketManager.sqliteStore()
        
        // Get the category filter from the shop config to assign to custom items
        val shop = plugin.customShopManager?.get(shopId)
        val category = shop?.categories?.get(categoryId)
        val categoryFilter = category?.filter?.name ?: "ALL"
        
        for (item in items) {
            if (item.isCustomItem) {
                // Custom item - use ItemsConfigManager to add with category filter
                val customConfig = org.lokixcz.theendex.market.CustomItemConfig(
                    id = item.id,
                    material = item.material,
                    displayName = item.displayName,
                    serializedData = item.serializedData ?: "",
                    basePrice = item.buyPrice,
                    minPrice = item.buyPrice * 0.3,
                    maxPrice = item.buyPrice * 3.0,
                    sellPrice = item.sellPrice,
                    enabled = item.enabled,
                    shopId = shopId,
                    categoryId = categoryId,
                    categoryFilter = categoryFilter  // This makes the item appear in FILTER categories
                )
                itemsConfig.setCustomItem(customConfig)
            } else {
                // Vanilla item - use normal ItemsConfigManager
                itemsConfig.addItem(
                    item.material,
                    item.buyPrice,
                    item.buyPrice * 0.3,  // min
                    item.buyPrice * 3.0   // max
                )
            }
            
            // Sync to market database for dynamic pricing
            if (db != null) {
                val marketItem = MarketItem(
                    material = item.material,
                    basePrice = item.buyPrice,
                    minPrice = item.buyPrice * 0.3,
                    maxPrice = item.buyPrice * 3.0,
                    currentPrice = item.buyPrice,
                    demand = 0.0,
                    supply = 0.0
                )
                db.upsertItem(marketItem)
            }
        }
        
        // Save items.yml with all items (vanilla + custom)
        itemsConfig.save()
    }
    
    /**
     * Save category items to the shop YAML file.
     * This stores the items in the shop's category configuration.
     */
    private fun saveCategoryToShopFile(shopId: String, categoryId: String, items: List<CustomShopItem>, mode: CategoryMode) {
        val shopFile = java.io.File(plugin.dataFolder, "shops/$shopId.yml")
        if (!shopFile.exists()) {
            return
        }
        
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(shopFile)
        val categoryPath = "categories.$categoryId"
        
        // Set category mode
        yaml.set("$categoryPath.mode", mode.name)
        
        if (mode == CategoryMode.MANUAL) {
            // Save items list
            val itemsList = mutableListOf<Map<String, Any>>()
            for (item in items) {
                val itemData = mutableMapOf<String, Any>(
                    "id" to item.id,
                    "material" to item.material.name,
                    "display-name" to item.displayName,
                    "buy-price" to item.buyPrice,
                    "sell-price" to item.sellPrice,
                    "slot" to item.slot,
                    "enabled" to item.enabled
                )
                
                // Only store serialized data for custom items
                if (item.isCustomItem && item.serializedData != null) {
                    itemData["is-custom"] = true
                    itemData["serialized-data"] = item.serializedData
                }
                
                if (item.permission.isNotEmpty()) {
                    itemData["permission"] = item.permission
                }
                if (item.stockLimit > 0) {
                    itemData["stock-limit"] = item.stockLimit
                }
                
                itemsList.add(itemData)
            }
            
            yaml.set("$categoryPath.items", itemsList)
        }
        
        yaml.save(shopFile)
        
        // Reload the shop manager to pick up changes
        plugin.customShopManager?.reload()
    }
    
    private fun createNewShop(player: Player, shopId: String, displayName: String) {
        // For now, we'll create a basic shop file
        val shopFile = java.io.File(plugin.dataFolder, "shops/$shopId.yml")
        if (!shopFile.parentFile.exists()) shopFile.parentFile.mkdirs()
        
        val yaml = org.bukkit.configuration.file.YamlConfiguration()
        yaml.set("id", shopId)
        yaml.set("enabled", true)
        yaml.set("title", "&5&l$displayName")
        yaml.set("menu.title", "&8$displayName")
        yaml.set("menu.size", 54)
        yaml.set("categories", mapOf<String, Any>())
        
        yaml.save(shopFile)
        
        // Reload shops
        plugin.customShopManager?.reload()
    }
    
    private fun deleteShop(player: Player, shopId: String) {
        val shopFile = java.io.File(plugin.dataFolder, "shops/$shopId.yml")
        if (shopFile.exists()) {
            shopFile.delete()
            plugin.customShopManager?.reload()
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-deleted", "shop" to shopId)))
            playSound(player, Sound.ENTITY_GENERIC_EXPLODE)
        } else {
            player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-file-not-found-delete")))
        }
        openShopManager(player)
    }
    
    private fun deleteCategory(player: Player, shopId: String, categoryId: String) {
        // TODO: Implement category deletion
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.category-deletion-soon")))
    }
    
    private fun saveShopChanges(player: Player, shopId: String) {
        // TODO: Implement saving
        player.sendMessage(Lang.colorize(Lang.get("shop-editor.shop-changes-saved")))
        playSound(player, Sound.ENTITY_PLAYER_LEVELUP)
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Utility Methods
    // ─────────────────────────────────────────────────────────────────────────
    
    private fun createItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name))
        if (lore.isNotEmpty()) {
            meta.lore = lore.map { ChatColor.translateAlternateColorCodes('&', it) }
        }
        item.itemMeta = meta
        return item
    }
    
    private fun playSound(player: Player, sound: Sound) {
        player.playSound(player.location, sound, 1.0f, 1.0f)
    }
    
    /**
     * Check if player has editor permission.
     */
    fun hasPermission(player: Player): Boolean {
        return player.hasPermission(EDITOR_PERMISSION)
    }
}
