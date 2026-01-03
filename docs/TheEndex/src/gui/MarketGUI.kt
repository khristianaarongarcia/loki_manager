package org.lokixcz.theendex.gui

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.market.PricePoint
import org.lokixcz.theendex.market.MarketItem
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.util.ItemNames
import java.util.*

class MarketGUI(private val plugin: Endex) : Listener {
    private val pageSize = 45 // 5 rows for items, last row for controls
    
    // GUI title base - fetched from language file
    private fun titleBase(): String = Lang.colorize(Lang.get("gui.market.title"))
    
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
    
    // Helper to get inventory view title as String for MC 1.20.1 - 1.21+ compatibility
    // MC 1.21+ changed InventoryView from abstract class to interface, breaking direct method calls
    private fun getViewTitleFromView(view: Any): String {
        // Strategy 1: Try direct title() method call via reflection (Paper 1.16+ Adventure API)
        try {
            val titleMethod = view.javaClass.getMethod("title")
            val component = titleMethod.invoke(view)
            if (component != null) {
                val result = serializeComponentToPlainText(component)
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {}
        
        // Strategy 2: Try originalTitle() method (some Paper versions)
        try {
            val origTitleMethod = view.javaClass.getMethod("originalTitle")
            val component = origTitleMethod.invoke(view)
            if (component != null) {
                val result = serializeComponentToPlainText(component)
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {}
        
        // Strategy 3: Try getTitle() legacy method (Spigot/older Paper)
        try {
            val legacyMethod = view.javaClass.getMethod("getTitle")
            val result = legacyMethod.invoke(view)
            if (result is String && result.isNotEmpty()) return result
        } catch (_: Exception) {}
        
        // Strategy 4: Try to get title from top inventory directly
        try {
            val topInvMethod = view.javaClass.getMethod("getTopInventory")
            val topInv = topInvMethod.invoke(view)
            if (topInv != null) {
                // Try to get the inventory's view holder or custom title
                val invClass = topInv.javaClass
                try {
                    val viewMethod = invClass.getMethod("getViewers")
                    // This doesn't give us title, skip
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        
        // Strategy 5: Search all methods for anything title-related
        try {
            val adventureComponentClass = try { Class.forName("net.kyori.adventure.text.Component") } catch (_: Exception) { null }
            for (method in view.javaClass.methods) {
                if (method.name.lowercase().contains("title") && method.parameterCount == 0) {
                    try {
                        val result = method.invoke(view)
                        when {
                            result is String -> if (result.isNotEmpty()) return result
                            adventureComponentClass != null && adventureComponentClass.isInstance(result) -> {
                                val text = serializeComponentToPlainText(result)
                                if (text.isNotEmpty()) return text
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        
        return ""
    }
    
    private fun getViewTitle(event: InventoryClickEvent): String = getViewTitleFromView(event.view)
    private fun getViewTitle(event: InventoryCloseEvent): String = getViewTitleFromView(event.view)
    private fun getViewTitle(event: InventoryDragEvent): String = getViewTitleFromView(event.view)
    
    // Check if the title belongs to any of our GUIs
    private fun isOurGui(title: String): Boolean {
        val stripped = ChatColor.stripColor(title) ?: title
        val marketTitle = ChatColor.stripColor(Lang.colorize(Lang.get("gui.market.title"))) ?: "The Endex"
        val detailsPrefix = ChatColor.stripColor(Lang.colorize(Lang.get("gui.details.title_prefix"))) ?: "Endex:"
        val deliveriesTitle = ChatColor.stripColor(Lang.colorize(Lang.get("gui.deliveries.title"))) ?: "Pending Deliveries"
        val holdingsTitle = ChatColor.stripColor(Lang.colorize(Lang.get("gui.holdings.title"))) ?: "My Holdings"
        return stripped.startsWith(marketTitle) || 
               stripped.startsWith(detailsPrefix) || 
               stripped.startsWith(deliveriesTitle) || 
               stripped.startsWith(holdingsTitle)
    }

    private val amounts = listOf(1, 8, 16, 32, 64)
    private enum class SortBy { NAME, PRICE, CHANGE }
    private enum class Category { ALL, ORES, FARMING, MOB_DROPS, BLOCKS }
    private enum class GuiType { MARKET, DETAILS, DELIVERIES, HOLDINGS, NONE }
    private data class State(
        var page: Int = 0,
        var amountIdx: Int = 0,
        var sort: SortBy = SortBy.NAME, // default A–Z
        var category: Category = Category.ALL, // default show all categories
        var groupBy: Boolean = true, // default grouped view in GUI as well
        var search: String = "",
        var inDetails: Boolean = false,
        var detailOf: Material? = null,
        var currentGui: GuiType = GuiType.NONE  // Track which GUI is currently open
    )
    private val states: MutableMap<UUID, State> = mutableMapOf()
    private val awaitingSearchInput: MutableSet<UUID> = mutableSetOf()
    
    // Track which GUI each player has open by UUID - reliable across all MC versions
    private val openGuis: MutableMap<UUID, GuiType> = mutableMapOf()
    
    // Track open inventories by player UUID - more reliable than title matching
    private val openInventories: MutableMap<UUID, Inventory> = mutableMapOf()

    fun open(player: Player, page: Int = 0) {
        val state = states[player.uniqueId] ?: load(player)
        state.page = page.coerceAtLeast(0)

        val itemsRaw = plugin.marketManager.allItems().toList()
        val filtered = itemsRaw.filter { mi ->
            val matchesCategory = when (state.category) {
                Category.ALL -> true
                Category.ORES -> mi.material.name.contains("_ORE") || mi.material.name.endsWith("_INGOT") || mi.material.name.endsWith("_BLOCK")
                Category.FARMING -> mi.material.name.contains("WHEAT") || mi.material.name.contains("SEEDS") || mi.material.name.contains("CARROT") || mi.material.name.contains("POTATO") || mi.material.name.contains("BEETROOT") || mi.material.name.contains("CROP")
                Category.MOB_DROPS -> mi.material.name in listOf("ROTTEN_FLESH","BONE","STRING","SPIDER_EYE","ENDER_PEARL","GUNPOWDER","BLAZE_ROD","GHAST_TEAR","SLIME_BALL")
                Category.BLOCKS -> mi.material.isBlock
            }
            val matchesSearch = state.search.isBlank() || mi.material.name.contains(state.search, ignoreCase = true)
            matchesCategory && matchesSearch
        }
        val items = filtered.sortedWith { a, b ->
            when (state.sort) {
                SortBy.NAME -> prettyName(a.material).lowercase().compareTo(prettyName(b.material).lowercase())
                SortBy.PRICE -> b.currentPrice.compareTo(a.currentPrice) // Descending - highest price first
                SortBy.CHANGE -> changePercent(b.history).compareTo(changePercent(a.history)) // Descending - biggest change first
            }
        }

        // Build entries, optionally with category headers when viewing ALL
        data class Entry(val header: String? = null, val item: MarketItem? = null)
        val entries: List<Entry> = if (state.groupBy && state.category == Category.ALL) {
            val grouped = items.groupBy { catNameFor(it.material) }
            val cats = grouped.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
            val list = mutableListOf<Entry>()
            for (c in cats) {
                val sorted = grouped[c]?.sortedBy { prettyName(it.material).lowercase() } ?: emptyList()
                if (sorted.isEmpty()) continue
                list += Entry(header = c)
                list += sorted.map { Entry(item = it) }
            }
            list
        } else {
            items.map { Entry(item = it) }
        }

        val totalPages = if (entries.isEmpty()) 1 else ((entries.size - 1) / pageSize + 1)
        if (state.page > totalPages - 1) state.page = totalPages - 1
        val from = state.page * pageSize
        val to = (from + pageSize).coerceAtMost(entries.size)
        val pageEntries = if (from in 0..entries.size) entries.subList(from, to) else emptyList()

        val inv: Inventory = Bukkit.createInventory(player, 54, "${titleBase()} ${ChatColor.DARK_GRAY}[${state.sort.name}] ${ChatColor.GRAY}(${state.page + 1}/$totalPages)")

        pageEntries.forEachIndexed { idx, en ->
            if (en.header != null) {
                val display = ItemStack(Material.PURPLE_STAINED_GLASS_PANE)
                val meta: ItemMeta = display.itemMeta
                meta.setDisplayName("${ChatColor.LIGHT_PURPLE}${en.header}")
                meta.lore = listOf(Lang.colorize(Lang.get("gui.market.category_section")))
                display.itemMeta = meta
                inv.setItem(idx, display)
            } else {
                val mi = en.item ?: return@forEachIndexed
                val display = ItemStack(mi.material.takeIf { it != Material.AIR } ?: Material.PAPER)
                val meta: ItemMeta = display.itemMeta
                // Use translatable name so item appears in player's Minecraft client language
                meta.displayName(ItemNames.translatable(mi.material, NamedTextColor.AQUA))

                val mul = plugin.eventManager.multiplierFor(mi.material)
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
                // Show last cycle demand/supply in percent impact terms
                val ds = mi.lastDemand - mi.lastSupply
                val sens = plugin.config.getDouble("price-sensitivity", 0.05)
                val estPct = ds * sens * 100.0

                // Calculate buy/sell prices with spread
                val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
                val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
                val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
                val effectivePrice = current * mul
                val buyPrice = effectivePrice * (1.0 + buyMarkupPct / 100.0)
                val sellPrice = effectivePrice * (1.0 - sellMarkdownPct / 100.0)

                val last5 = mi.history.takeLast(5).map { String.format("%.2f", it.price) }
                val bal = plugin.economy?.getBalance(player) ?: 0.0
                val invCount = player.inventory.contents.filterNotNull().filter { it.type == mi.material }.sumOf { it.amount }
                val loreCore = mutableListOf<String>()
                loreCore += Lang.colorize(Lang.get("gui.item.price", "price" to format(current), "arrow" to arrow, "diff" to format(diff), "pct" to formatPct(pct)))
                // Show buy/sell prices with spread
                if (spreadEnabled && (buyMarkupPct > 0 || sellMarkdownPct > 0)) {
                    loreCore += Lang.colorize(Lang.get("gui.item.buy_sell", "buy" to format(buyPrice), "sell" to format(sellPrice)))
                }
                loreCore += Lang.colorize(Lang.get("gui.item.last_cycle", "demand" to format(mi.lastDemand), "supply" to format(mi.lastSupply), "pct" to formatPct(estPct)))
                if (mul != 1.0) loreCore += Lang.colorize(Lang.get("gui.item.event_multiplier", "mul" to format(mul), "effective" to format(current*mul)))
                loreCore += Lang.colorize(Lang.get("gui.item.min_max", "min" to format(mi.minPrice), "max" to format(mi.maxPrice)))
                loreCore += Lang.colorize(Lang.get("gui.item.history", "history" to last5.joinToString(" ${ChatColor.DARK_GRAY}| ${ChatColor.GRAY}")))
                loreCore += Lang.colorize(Lang.get("gui.item.click_hint", "amount" to amounts[state.amountIdx].toString()))
                loreCore += Lang.colorize(Lang.get("gui.item.details_hint"))
                loreCore += Lang.colorize(Lang.get("gui.item.you_have", "count" to invCount.toString(), "material" to mi.material.name))
                loreCore += Lang.colorize(Lang.get("gui.item.balance", "balance" to format(bal)))
                meta.lore = loreCore
                display.itemMeta = meta
                inv.setItem(idx, display)
            }
        }

        // Controls (last row)
        inv.setItem(45, namedItem(Material.ARROW, Lang.colorize(Lang.get("gui.buttons.prev_page"))))
        inv.setItem(46, namedItem(Material.BOOK, Lang.colorize(Lang.get("gui.buttons.category", "category" to state.category.name))))
        inv.setItem(47, namedItem(Material.LECTERN, Lang.colorize(Lang.get("gui.buttons.group", "status" to if (state.groupBy) Lang.get("gui.buttons.group_on") else Lang.get("gui.buttons.group_off")))))
        inv.setItem(48, namedItem(Material.OAK_SIGN, Lang.colorize(Lang.get("gui.buttons.search", "search" to if (state.search.isBlank()) Lang.get("gui.buttons.search_none") else state.search))))
        inv.setItem(49, namedItem(Material.COMPARATOR, Lang.colorize(Lang.get("gui.buttons.sort", "sort" to state.sort.name))))
        
        // Holdings button (replaces deliveries for virtual holdings system)
        val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
        val db = plugin.marketManager.sqliteStore()
        
        if (holdingsEnabled && db != null) {
            val holdings = db.listHoldings(player.uniqueId.toString())
            val totalCount = holdings.values.sumOf { it.first }
            val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
            
            val holdingsIcon = ItemStack(Material.CHEST)
            val holdingsMeta = holdingsIcon.itemMeta
            holdingsMeta.setDisplayName(Lang.colorize(Lang.get("gui.buttons.holdings")))
            val holdingsLore = mutableListOf<String>()
            if (totalCount > 0) {
                holdingsLore += Lang.colorize(Lang.get("gui.holdings.count", "count" to totalCount.toString(), "max" to maxHoldings.toString()))
                holdingsLore += Lang.colorize(Lang.get("gui.holdings.materials", "count" to holdings.size.toString()))
                holdingsLore += Lang.colorize(Lang.get("gui.holdings.click_hint"))
            } else {
                holdingsLore += Lang.colorize(Lang.get("gui.holdings.empty"))
                holdingsLore += Lang.colorize(Lang.get("gui.holdings.empty_hint"))
            }
            holdingsMeta.lore = holdingsLore
            holdingsIcon.itemMeta = holdingsMeta
            inv.setItem(51, holdingsIcon)
        } else {
            // Fallback to deliveries button if holdings disabled
            val deliveryMgr = plugin.getDeliveryManager()
            if (deliveryMgr != null && plugin.config.getBoolean("delivery.enabled", true)) {
                val pending = deliveryMgr.listPending(player.uniqueId)
                val totalCount = pending.values.sum()
                val deliveryIcon = ItemStack(Material.ENDER_CHEST)
                val deliveryMeta = deliveryIcon.itemMeta
                deliveryMeta.setDisplayName(Lang.colorize(Lang.get("gui.buttons.deliveries")))
                val deliveryLore = mutableListOf<String>()
                if (totalCount > 0) {
                    deliveryLore += Lang.colorize(Lang.get("gui.deliveries.pending_count", "count" to totalCount.toString()))
                    deliveryLore += Lang.colorize(Lang.get("gui.deliveries.click_hint"))
                } else {
                    deliveryLore += Lang.colorize(Lang.get("gui.deliveries.empty"))
                }
                deliveryMeta.lore = deliveryLore
                deliveryIcon.itemMeta = deliveryMeta
                inv.setItem(51, deliveryIcon)
            }
        }
        
        inv.setItem(53, namedItem(Material.ARROW, Lang.colorize(Lang.get("gui.buttons.next_page"))))

        player.openInventory(inv)
        openGuis[player.uniqueId] = GuiType.MARKET
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        
        // Check if this player has our GUI open using UUID tracking (reliable across MC versions)
        val guiType = openGuis[player.uniqueId]
        
        // Only handle MARKET gui here (other guis have their own handlers)
        if (guiType != GuiType.MARKET) return
        
        // Allow clicks in player's bottom inventory (hotbar, etc.)
        if (e.rawSlot >= e.view.topInventory.size) {
            return
        }
        
        // Cancel the event to prevent item taking/moving in the GUI
        e.isCancelled = true
        
        // Block all item movement actions (shift-click, number keys, drag, etc.)
        if (e.click == ClickType.SHIFT_LEFT || e.click == ClickType.SHIFT_RIGHT ||
            e.click == ClickType.NUMBER_KEY || e.click == ClickType.DOUBLE_CLICK ||
            e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) {
            // Only allow shift-click for details view in item slots
            if (e.rawSlot in 0 until pageSize && e.click == ClickType.SHIFT_LEFT) {
                // Allow - handled below for details view
            } else {
                return
            }
        }
        
        val state = states.getOrPut(player.uniqueId) { State() }

        val slot = e.rawSlot
        if (slot in 0 until pageSize) {
            val clicked = e.currentItem ?: return
            val mat = clicked.type.takeIf { it != Material.AIR } ?: return
            if (plugin.marketManager.get(mat) == null) return
            val amount = amounts[state.amountIdx]
            // Details view open on shift-left or middle click
            if (e.isShiftClick && e.isLeftClick || e.click == ClickType.MIDDLE) {
                openDetails(player, mat)
            } else if (e.isLeftClick) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.performCommand("market buy ${mat.name} $amount")
                    open(player, state.page)
                })
            } else if (e.isRightClick) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.performCommand("market sell ${mat.name} $amount")
                    open(player, state.page)
                })
            }
            return
        }

        when (slot) {
            45 -> { // prev
                state.page = (state.page - 1).coerceAtLeast(0)
                open(player, state.page)
            }
            46 -> { // category
                state.category = when (state.category) {
                    Category.ALL -> Category.ORES
                    Category.ORES -> Category.FARMING
                    Category.FARMING -> Category.MOB_DROPS
                    Category.MOB_DROPS -> Category.BLOCKS
                    Category.BLOCKS -> Category.ALL
                }
                persist(player, state)
                open(player, 0)
            }
            47 -> { // grouping toggle
                state.groupBy = !state.groupBy
                persist(player, state)
                open(player, 0)
            }
            48 -> { // search prompt or clear
                if (e.isRightClick) {
                    state.search = ""
                    persist(player, state)
                    open(player, 0)
                } else {
                    player.closeInventory()
                    player.sendMessage(Lang.prefixed("gui.market.search_prompt"))
                    awaitingSearchInput.add(player.uniqueId)
                }
            }
            49 -> { // sort cycle
                state.sort = when (state.sort) {
                    SortBy.NAME -> SortBy.PRICE
                    SortBy.PRICE -> SortBy.CHANGE
                    SortBy.CHANGE -> SortBy.NAME
                }
                persist(player, state)
                open(player, 0)
            }
            51 -> { // holdings or deliveries
                val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
                val db = plugin.marketManager.sqliteStore()
                if (holdingsEnabled && db != null) {
                    openHoldings(player)
                } else {
                    openDeliveries(player)
                }
            }
            53 -> { // next
                state.page += 1
                open(player, state.page)
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val player = e.player as? Player ?: return
        // Check if this player had our GUI open using UUID tracking
        val guiType = openGuis.remove(player.uniqueId)
        if (guiType != null) {
            states[player.uniqueId]?.let { persist(player, it) }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val player = e.whoClicked as? Player ?: return
        // Check if this player has our GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType == null) return
        // Cancel all drag events in our GUI
        e.isCancelled = true
    }

    @EventHandler
    fun onChat(e: AsyncPlayerChatEvent) {
        val player = e.player
        val uuid = player.uniqueId
        if (!awaitingSearchInput.contains(uuid)) return
        e.isCancelled = true
        val state = states.getOrPut(uuid) { load(player) }
        state.search = e.message.trim()
        awaitingSearchInput.remove(uuid)
        persist(player, state)
        Bukkit.getScheduler().runTask(plugin, Runnable { open(player, 0) })
    }

    private fun changePercent(history: Collection<PricePoint>): Double {
        if (history.size < 2) return 0.0
        val list = history.toList()
        val prev = list[list.lastIndex - 1].price
        val last = list.last().price
        if (prev == 0.0) return 0.0
        return (last - prev) / prev * 100.0
    }

    private fun namedItem(mat: Material, name: String): ItemStack = ItemStack(mat).apply {
        itemMeta = itemMeta.apply { setDisplayName(name) }
    }

    private fun format(n: Double): String = String.format("%.2f", n)
    private fun formatPct(n: Double): String = String.format("%.2f%%", n)
    private fun prettyName(mat: Material): String = mat.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
    private fun catNameFor(mat: Material): String {
        val n = mat.name
        return when {
            n.contains("_ORE") || n.endsWith("_INGOT") || n.endsWith("_BLOCK") -> "Ores"
            listOf("WHEAT","SEEDS","CARROT","POTATO","BEETROOT","MELON","PUMPKIN","SUGAR","BAMBOO","COCOA").any { n.contains(it) } -> "Farming"
            n in setOf("ROTTEN_FLESH","BONE","STRING","SPIDER_EYE","ENDER_PEARL","GUNPOWDER","BLAZE_ROD","GHAST_TEAR","SLIME_BALL","LEATHER","FEATHER") -> "Mob Drops"
            mat.isBlock -> "Blocks"
            else -> "Misc"
        }
    }

    private fun persist(player: Player, state: State) {
        plugin.prefsStore.save(player.uniqueId, mapOf(
            "amountIdx" to state.amountIdx,
            "sort" to state.sort.name,
            "category" to state.category.name,
            "groupBy" to state.groupBy,
            "search" to state.search,
            "page" to state.page
        ))
    }

    private fun load(player: Player): State {
        val m = plugin.prefsStore.load(player.uniqueId)
        val amountIdx = (m["amountIdx"] as? Int) ?: 0
        val sort = runCatching { SortBy.valueOf((m["sort"] as? String ?: "NAME")) }.getOrDefault(SortBy.NAME)
        val category = runCatching { Category.valueOf((m["category"] as? String ?: "ALL")) }.getOrDefault(Category.ALL)
        val groupBy = (m["groupBy"] as? Boolean) ?: true
        val search = (m["search"] as? String) ?: ""
        val page = (m["page"] as? Int) ?: 0
        val s = State(page, amountIdx, sort, category, groupBy, search)
        states[player.uniqueId] = s
        return s
    }

    // Ensure state is loaded before opening
    override fun toString(): String = "MarketGUI"

    // Public: Refresh GUI for a player if our GUI is open
    fun refreshOpenFor(player: Player) {
        val title = player.openInventory.title
        val state = states[player.uniqueId] ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val marketTitle = ChatColor.stripColor(titleBase()) ?: "The Endex"
            val detailsPrefix = ChatColor.stripColor(Lang.colorize(Lang.get("gui.details.title_prefix"))) ?: "Endex:"
            when {
                title.startsWith(marketTitle) || title.startsWith(titleBase()) -> open(player, state.page)
                state.inDetails && state.detailOf != null && (title.contains("Endex:") || title.contains(detailsPrefix)) -> openDetails(player, state.detailOf!!)
            }
        })
    }

    // Public: Refresh all viewers who currently have the Endex GUI open
    fun refreshAllOpen() {
        plugin.server.onlinePlayers.forEach { p -> refreshOpenFor(p) }
    }

    // Details view - public so MarketActions can call it from CustomShopGUI
    fun openDetails(player: Player, mat: Material) {
        val state = states[player.uniqueId] ?: load(player)
        state.inDetails = true
        state.detailOf = mat
        val detailsTitle = Lang.colorize(Lang.get("gui.details.title", "item" to prettyName(mat)))
        val inv = Bukkit.createInventory(player, 36, detailsTitle)

        val mi = plugin.marketManager.get(mat) ?: run {
            open(player, state.page); return
        }
        val itemDisplay = ItemStack(mat)
        val meta = itemDisplay.itemMeta
    val mul = plugin.eventManager.multiplierFor(mat)
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
        
        // Calculate buy/sell prices with spread
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val effectivePrice = current * mul
        val buyPrice = effectivePrice * (1.0 + buyMarkupPct / 100.0)
        val sellPrice = effectivePrice * (1.0 - sellMarkdownPct / 100.0)
        
        val bal = plugin.economy?.getBalance(player) ?: 0.0
        val invCount = player.inventory.contents.filterNotNull().filter { it.type == mat }.sumOf { it.amount }
        // Get holdings count from virtual storage
        val holdingsData = plugin.marketManager.sqliteStore()?.getHolding(player.uniqueId.toString(), mat)
        val holdingsCount = holdingsData?.first ?: 0
        // Use translatable name so item appears in player's Minecraft client language
        meta.displayName(ItemNames.translatable(mat, NamedTextColor.AQUA))
        val lore = mutableListOf<String>()
        lore += Lang.colorize(Lang.get("gui.item.price", "price" to format(current), "arrow" to arrow, "diff" to format(diff), "pct" to formatPct(pct)))
        // Show buy/sell prices with spread
        if (spreadEnabled && (buyMarkupPct > 0 || sellMarkdownPct > 0)) {
            lore += Lang.colorize(Lang.get("gui.item.buy_sell", "buy" to format(buyPrice), "sell" to format(sellPrice)))
        }
        if (mul != 1.0) lore += Lang.colorize(Lang.get("gui.item.event_multiplier", "mul" to format(mul), "effective" to format(current*mul)))
        lore += Lang.colorize(Lang.get("gui.item.min_max", "min" to format(mi.minPrice), "max" to format(mi.maxPrice)))
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
                lore += Lang.colorize(Lang.get("gui.details.chart", "bars" to bars))
            }
        }
        lore += Lang.colorize(Lang.get("gui.details.inventory_holdings", "inv" to invCount.toString(), "holdings" to holdingsCount.toString()))
        lore += Lang.colorize(Lang.get("gui.item.balance", "balance" to format(bal)))
        meta.lore = lore
        itemDisplay.itemMeta = meta
        inv.setItem(13, itemDisplay)

        // Buttons
    inv.setItem(18, namedItem(Material.LIME_DYE, Lang.colorize(Lang.get("gui.details.buy_1"))))
    inv.setItem(20, namedItem(Material.EMERALD_BLOCK, Lang.colorize(Lang.get("gui.details.buy_64"))))
    inv.setItem(24, namedItem(Material.RED_DYE, Lang.colorize(Lang.get("gui.details.sell_1_inv"))))
    inv.setItem(26, namedItem(Material.BARREL, Lang.colorize(Lang.get("gui.details.sell_all_inv", "count" to invCount.toString()))))
    // Holdings sell buttons
    inv.setItem(33, namedItem(Material.PINK_DYE, Lang.colorize(Lang.get("gui.details.sell_1_holdings"))))
    inv.setItem(35, namedItem(Material.ENDER_CHEST, Lang.colorize(Lang.get("gui.details.sell_all_holdings", "count" to holdingsCount.toString()))))
        inv.setItem(22, namedItem(Material.ARROW, Lang.colorize(Lang.get("gui.buttons.back"))))

        player.openInventory(inv)
        openGuis[player.uniqueId] = GuiType.DETAILS
    }

    @EventHandler
    fun onDetailsClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val state = states[player.uniqueId] ?: return
        if (!state.inDetails) return
        val mat = state.detailOf ?: return
        
        // Check if this player has our DETAILS GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType != GuiType.DETAILS) return
        
        // Cancel the event FIRST to prevent item taking/moving
        e.isCancelled = true
        
        // Also cancel if clicking in player inventory
        if (e.clickedInventory == player.inventory) {
            return
        }
        
        // Block item movement actions
        if (e.click == ClickType.SHIFT_LEFT || e.click == ClickType.SHIFT_RIGHT ||
            e.click == ClickType.NUMBER_KEY || e.click == ClickType.DOUBLE_CLICK ||
            e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) {
            return
        }
        
        when (e.rawSlot) {
            18 -> Bukkit.getScheduler().runTask(plugin, Runnable {
                player.performCommand("market buy ${mat.name} 1")
                openDetails(player, mat)
            })
            20 -> Bukkit.getScheduler().runTask(plugin, Runnable {
                player.performCommand("market buy ${mat.name} 64")
                openDetails(player, mat)
            })
            24 -> Bukkit.getScheduler().runTask(plugin, Runnable {
                player.performCommand("market sell ${mat.name} 1")
                openDetails(player, mat)
            })
            26 -> {
                val total = player.inventory.contents.filterNotNull().filter { it.type == mat }.sumOf { it.amount }
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (total > 0) player.performCommand("market sell ${mat.name} $total")
                    openDetails(player, mat)
                })
            }
            // Sell from holdings buttons
            33 -> Bukkit.getScheduler().runTask(plugin, Runnable {
                player.performCommand("market sellholdings ${mat.name} 1")
                openDetails(player, mat)
            })
            35 -> {
                val holdingsData = plugin.marketManager.sqliteStore()?.getHolding(player.uniqueId.toString(), mat)
                val holdingsTotal = holdingsData?.first ?: 0
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (holdingsTotal > 0) player.performCommand("market sellholdings ${mat.name} $holdingsTotal")
                    openDetails(player, mat)
                })
            }
            22 -> { state.inDetails = false; state.detailOf = null; open(player, state.page) }
        }
    }

    // Deliveries panel
    private fun openDeliveries(player: Player) {
        val deliveryMgr = plugin.getDeliveryManager() ?: run {
            player.sendMessage(Lang.prefixed("gui.deliveries.unavailable"))
            return
        }
        
        val pending = deliveryMgr.listPending(player.uniqueId)
        val deliveriesTitle = Lang.colorize(Lang.get("gui.deliveries.title"))
        val inv = Bukkit.createInventory(player, 54, deliveriesTitle)
        
        if (pending.isEmpty()) {
            val noItems = ItemStack(Material.BARRIER)
            val meta = noItems.itemMeta
            meta.setDisplayName(Lang.colorize(Lang.get("gui.deliveries.no_pending")))
            meta.lore = listOf(Lang.colorize(Lang.get("gui.deliveries.all_claimed")))
            noItems.itemMeta = meta
            inv.setItem(22, noItems)
        } else {
            // Display pending materials (up to 45 slots, 5 rows)
            val materials = pending.keys.toList().take(45)
            materials.forEachIndexed { idx, mat ->
                val count = pending[mat] ?: 0
                val display = ItemStack(mat)
                val meta = display.itemMeta
                // Use translatable name so item appears in player's Minecraft client language
                meta.displayName(ItemNames.translatable(mat, NamedTextColor.AQUA))
                val capacity = deliveryMgr.calculateInventoryCapacity(player, mat)
                meta.lore = listOf(
                    Lang.colorize(Lang.get("gui.deliveries.item_pending", "count" to count.toString())),
                    Lang.colorize(Lang.get("gui.deliveries.inv_space", "space" to capacity.toString())),
                    "",
                    Lang.colorize(Lang.get("gui.deliveries.left_click")),
                    Lang.colorize(Lang.get("gui.deliveries.right_click", "stack" to mat.maxStackSize.toString()))
                )
                display.itemMeta = meta
                inv.setItem(idx, display)
            }
        }
        
        // Control buttons (last row)
        val claimAllBtn = ItemStack(Material.EMERALD_BLOCK)
        val claimAllMeta = claimAllBtn.itemMeta
        claimAllMeta.setDisplayName(Lang.colorize(Lang.get("gui.deliveries.claim_all")))
        claimAllMeta.lore = listOf(
            Lang.colorize(Lang.get("gui.deliveries.claim_all_desc")),
            Lang.colorize(Lang.get("gui.deliveries.claim_all_hint"))
        )
        claimAllBtn.itemMeta = claimAllMeta
        inv.setItem(49, claimAllBtn)
        
        val backBtn = ItemStack(Material.ARROW)
        val backMeta = backBtn.itemMeta
        backMeta.setDisplayName(Lang.colorize(Lang.get("gui.buttons.back_to_market")))
        backBtn.itemMeta = backMeta
        inv.setItem(53, backBtn)
        
        player.openInventory(inv)
        openGuis[player.uniqueId] = GuiType.DELIVERIES
    }
    
    @EventHandler
    fun onDeliveriesClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        
        // Check if this player has our DELIVERIES GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType != GuiType.DELIVERIES) return
        
        // Cancel the event FIRST to prevent item taking/moving
        e.isCancelled = true
        
        // Also cancel if clicking in player inventory
        if (e.clickedInventory == player.inventory) {
            return
        }
        
        // Block item movement actions
        if (e.click == ClickType.SHIFT_LEFT || e.click == ClickType.SHIFT_RIGHT ||
            e.click == ClickType.NUMBER_KEY || e.click == ClickType.DOUBLE_CLICK ||
            e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) {
            return
        }
        
        val deliveryMgr = plugin.getDeliveryManager() ?: return
        val slot = e.rawSlot
        val state = states[player.uniqueId] ?: State()
        
        when (slot) {
            in 0..44 -> { // Material slot
                val clicked = e.currentItem ?: return
                val mat = clicked.type.takeIf { it != Material.AIR && it != Material.BARRIER } ?: return
                
                if (e.isLeftClick) {
                    // Claim as much as fits
                    val result = deliveryMgr.claimMaterial(player, mat, Int.MAX_VALUE)
                    if (result.delivered > 0) {
                        player.sendMessage(Lang.prefixed("gui.deliveries.claimed", "count" to result.delivered.toString(), "item" to mat.name))
                    }
                    if (result.remainingPending > 0) {
                        player.sendMessage(Lang.prefixed("gui.deliveries.still_pending", "count" to result.remainingPending.toString(), "item" to mat.name))
                    }
                } else if (e.isRightClick) {
                    // Claim 1 stack
                    val result = deliveryMgr.claimMaterial(player, mat, mat.maxStackSize)
                    if (result.delivered > 0) {
                        player.sendMessage(Lang.prefixed("gui.deliveries.claimed", "count" to result.delivered.toString(), "item" to mat.name))
                    }
                    if (result.remainingPending > 0) {
                        player.sendMessage(Lang.prefixed("gui.deliveries.still_pending_simple", "count" to result.remainingPending.toString(), "item" to mat.name))
                    }
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { openDeliveries(player) })
            }
            49 -> { // Claim All
                val result = deliveryMgr.claimAll(player)
                if (result.delivered.isNotEmpty()) {
                    val totalClaimed = result.delivered.values.sum()
                    player.sendMessage(Lang.prefixed("gui.deliveries.claimed_total", "count" to totalClaimed.toString()))
                    result.delivered.forEach { (mat, count) ->
                        player.sendMessage(Lang.colorize(Lang.get("gui.deliveries.item-detail", "item" to prettyName(mat), "count" to count.toString())))
                    }
                }
                if (result.totalRemaining > 0) {
                    player.sendMessage(Lang.prefixed("gui.deliveries.items_remaining", "count" to result.totalRemaining.toString()))
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { openDeliveries(player) })
            }
            53 -> { // Back
                open(player, state.page)
            }
        }
    }
    
    // Holdings panel (Virtual Holdings System)
    fun openHoldings(player: Player) {
        val db = plugin.marketManager.sqliteStore() ?: run {
            player.sendMessage(Lang.prefixed("gui.holdings.unavailable"))
            return
        }
        
        val holdings = db.listHoldings(player.uniqueId.toString())
        val totalCount = holdings.values.sumOf { it.first }
        val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
        
        val holdingsTitle = Lang.colorize(Lang.get("gui.holdings.title"))
        val inv = Bukkit.createInventory(player, 54, holdingsTitle)
        
        if (holdings.isEmpty()) {
            val noItems = ItemStack(Material.BARRIER)
            val meta = noItems.itemMeta
            meta.setDisplayName(Lang.colorize(Lang.get("gui.holdings.no_items")))
            meta.lore = listOf(
                Lang.colorize(Lang.get("gui.holdings.no_items_hint1")),
                Lang.colorize(Lang.get("gui.holdings.no_items_hint2"))
            )
            noItems.itemMeta = meta
            inv.setItem(22, noItems)
        } else {
            // Display holdings materials (up to 45 slots, 5 rows)
            val materials = holdings.entries.sortedByDescending { it.value.first }.take(45)
            materials.forEachIndexed { idx, (mat, pair) ->
                val (qty, avgCost) = pair
                val display = ItemStack(mat)
                val meta = display.itemMeta
                // Use translatable name so item appears in player's Minecraft client language
                meta.displayName(ItemNames.translatable(mat, NamedTextColor.AQUA))
                
                val marketItem = plugin.marketManager.get(mat)
                val currentPrice = marketItem?.currentPrice ?: 0.0
                val value = currentPrice * qty
                val cost = avgCost * qty
                val pnl = value - cost
                val pnlColor = when {
                    pnl > 0.01 -> ChatColor.GREEN
                    pnl < -0.01 -> ChatColor.RED
                    else -> ChatColor.GRAY
                }
                val pnlSign = if (pnl >= 0) "+" else ""
                
                val capacity = calculateInventoryCapacity(player, mat)
                meta.lore = listOf(
                    Lang.colorize(Lang.get("gui.holdings.item_qty", "qty" to qty.toString())),
                    Lang.colorize(Lang.get("gui.holdings.item_avg_cost", "cost" to format(avgCost))),
                    Lang.colorize(Lang.get("gui.holdings.item_current_price", "price" to format(currentPrice))),
                    Lang.colorize(Lang.get("gui.holdings.item_value", "value" to format(value), "pnl_color" to pnlColor.toString(), "pnl" to "${pnlSign}${format(pnl)}")),
                    "",
                    Lang.colorize(Lang.get("gui.holdings.inv_space", "space" to capacity.toString())),
                    Lang.colorize(Lang.get("gui.holdings.left_click")),
                    Lang.colorize(Lang.get("gui.holdings.right_click", "stack" to mat.maxStackSize.toString()))
                )
                display.itemMeta = meta
                inv.setItem(idx, display)
            }
        }
        
        // Stats panel
        var totalValue = 0.0
        var totalCost = 0.0
        holdings.forEach { (mat, pair) ->
            val (qty, avg) = pair
            val current = plugin.marketManager.get(mat)?.currentPrice ?: 0.0
            totalValue += current * qty
            totalCost += avg * qty
        }
        val totalPnl = totalValue - totalCost
        val pnlColor = when {
            totalPnl > 0.01 -> ChatColor.GREEN
            totalPnl < -0.01 -> ChatColor.RED
            else -> ChatColor.GRAY
        }
        
        val statsItem = ItemStack(Material.PAPER)
        val statsMeta = statsItem.itemMeta
        statsMeta.setDisplayName(Lang.colorize(Lang.get("gui.holdings.stats_title")))
        statsMeta.lore = listOf(
            Lang.colorize(Lang.get("gui.holdings.stats_total_items", "count" to totalCount.toString(), "max" to maxHoldings.toString())),
            Lang.colorize(Lang.get("gui.holdings.stats_unique", "count" to holdings.size.toString())),
            Lang.colorize(Lang.get("gui.holdings.stats_total_value", "value" to format(totalValue))),
            Lang.colorize(Lang.get("gui.holdings.stats_total_cost", "cost" to format(totalCost))),
            "${pnlColor}${Lang.get("gui.holdings.stats_pnl", "pnl" to "${if (totalPnl >= 0) "+" else ""}${format(totalPnl)}")}"
        )
        statsItem.itemMeta = statsMeta
        inv.setItem(45, statsItem)
        
        // Control buttons (last row)
        val withdrawAllBtn = ItemStack(Material.EMERALD_BLOCK)
        val withdrawAllMeta = withdrawAllBtn.itemMeta
        withdrawAllMeta.setDisplayName(Lang.colorize(Lang.get("gui.holdings.withdraw_all")))
        withdrawAllMeta.lore = listOf(
            Lang.colorize(Lang.get("gui.holdings.withdraw_all_desc")),
            Lang.colorize(Lang.get("gui.holdings.withdraw_all_hint"))
        )
        withdrawAllBtn.itemMeta = withdrawAllMeta
        inv.setItem(49, withdrawAllBtn)
        
        val backBtn = ItemStack(Material.ARROW)
        val backMeta = backBtn.itemMeta
        backMeta.setDisplayName(Lang.colorize(Lang.get("gui.buttons.back_to_market")))
        backBtn.itemMeta = backMeta
        inv.setItem(53, backBtn)
        
        player.openInventory(inv)
        openGuis[player.uniqueId] = GuiType.HOLDINGS
    }
    
    /**
     * Calculate how many items of the given material the player can receive in their inventory.
     */
    private fun calculateInventoryCapacity(player: Player, material: Material): Int {
        val inv = player.inventory
        val maxStack = material.maxStackSize
        var capacity = 0
        
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type == material) {
                capacity += (maxStack - stack.amount).coerceAtLeast(0)
            }
        }
        
        val emptySlots = inv.storageContents.count { it == null || it.type == Material.AIR }
        capacity += emptySlots * maxStack
        
        return capacity
    }
    
    @EventHandler
    fun onHoldingsClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        
        // Check if this player has our HOLDINGS GUI open using UUID tracking
        val guiType = openGuis[player.uniqueId]
        if (guiType != GuiType.HOLDINGS) return
        
        // Cancel the event FIRST to prevent item taking/moving
        e.isCancelled = true
        
        // Also cancel if clicking in player inventory
        if (e.clickedInventory == player.inventory) {
            return
        }
        
        // Block item movement actions
        if (e.click == ClickType.SHIFT_LEFT || e.click == ClickType.SHIFT_RIGHT ||
            e.click == ClickType.NUMBER_KEY || e.click == ClickType.DOUBLE_CLICK ||
            e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) {
            return
        }
        
        val db = plugin.marketManager.sqliteStore() ?: return
        val slot = e.rawSlot
        val state = states[player.uniqueId] ?: State()
        
        when (slot) {
            in 0..44 -> { // Material slot
                val clicked = e.currentItem ?: return
                val mat = clicked.type.takeIf { it != Material.AIR && it != Material.BARRIER } ?: return
                
                if (e.isLeftClick) {
                    // Withdraw all
                    val result = withdrawFromHoldings(player, db, mat, Int.MAX_VALUE)
                    if (result.first > 0) {
                        player.sendMessage(Lang.prefixed("gui.holdings.withdrew", "count" to result.first.toString(), "item" to prettyName(mat)))
                    }
                    if (result.second > 0) {
                        player.sendMessage(Lang.prefixed("gui.holdings.still_in_holdings", "count" to result.second.toString(), "item" to prettyName(mat)))
                    }
                } else if (e.isRightClick) {
                    // Withdraw 1 stack
                    val result = withdrawFromHoldings(player, db, mat, mat.maxStackSize)
                    if (result.first > 0) {
                        player.sendMessage(Lang.prefixed("gui.holdings.withdrew", "count" to result.first.toString(), "item" to prettyName(mat)))
                    }
                    if (result.second > 0) {
                        player.sendMessage(Lang.prefixed("gui.holdings.still_in_holdings_simple", "count" to result.second.toString(), "item" to prettyName(mat)))
                    }
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { openHoldings(player) })
            }
            49 -> { // Withdraw All
                val result = withdrawAllFromHoldings(player, db)
                if (result.first > 0) {
                    player.sendMessage(Lang.prefixed("gui.holdings.withdrew_total", "count" to result.first.toString()))
                }
                if (result.second > 0) {
                    player.sendMessage(Lang.prefixed("gui.holdings.items_remaining", "count" to result.second.toString()))
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { openHoldings(player) })
            }
            53 -> { // Back
                open(player, state.page)
            }
        }
    }
    
    /**
     * Withdraw items from virtual holdings to player inventory.
     * Returns Pair(withdrawn, remaining)
     */
    private fun withdrawFromHoldings(
        player: Player,
        db: org.lokixcz.theendex.market.SqliteStore,
        material: Material,
        requestedAmount: Int
    ): Pair<Int, Int> {
        val holding = db.getHolding(player.uniqueId.toString(), material)
        if (holding == null || holding.first <= 0) {
            return Pair(0, 0)
        }
        
        val (available, _) = holding
        val toWithdraw = minOf(requestedAmount, available)
        val capacity = calculateInventoryCapacity(player, material)
        
        if (capacity <= 0) {
            return Pair(0, available)
        }
        
        val actualWithdraw = minOf(toWithdraw, capacity)
        val removed = db.removeFromHoldings(player.uniqueId.toString(), material, actualWithdraw)
        
        if (removed <= 0) {
            return Pair(0, available)
        }
        
        // Give items to player
        var remaining = removed
        while (remaining > 0) {
            val toGive = minOf(remaining, material.maxStackSize)
            val stack = ItemStack(material, toGive)
            val leftovers = player.inventory.addItem(stack)
            if (leftovers.isNotEmpty()) {
                leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
            remaining -= toGive
        }
        
        val newHolding = db.getHolding(player.uniqueId.toString(), material)
        val stillHave = newHolding?.first ?: 0
        
        return Pair(removed, stillHave)
    }
    
    /**
     * Withdraw all items from virtual holdings.
     * Returns Pair(totalWithdrawn, totalRemaining)
     */
    private fun withdrawAllFromHoldings(
        player: Player,
        db: org.lokixcz.theendex.market.SqliteStore
    ): Pair<Int, Int> {
        val holdings = db.listHoldings(player.uniqueId.toString())
        
        if (holdings.isEmpty()) {
            return Pair(0, 0)
        }
        
        var totalWithdrawn = 0
        var totalRemaining = 0
        
        for ((mat, pair) in holdings) {
            val (available, _) = pair
            if (available <= 0) continue
            
            val capacity = calculateInventoryCapacity(player, mat)
            if (capacity <= 0) {
                totalRemaining += available
                continue
            }
            
            val toWithdraw = minOf(available, capacity)
            val removed = db.removeFromHoldings(player.uniqueId.toString(), mat, toWithdraw)
            
            if (removed > 0) {
                var remaining = removed
                while (remaining > 0) {
                    val toGive = minOf(remaining, mat.maxStackSize)
                    val stack = ItemStack(mat, toGive)
                    val leftovers = player.inventory.addItem(stack)
                    if (leftovers.isNotEmpty()) {
                        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                    }
                    remaining -= toGive
                }
                
                totalWithdrawn += removed
                val stillHave = available - removed
                if (stillHave > 0) totalRemaining += stillHave
            } else {
                totalRemaining += available
            }
        }
        
        return Pair(totalWithdrawn, totalRemaining)
    }
}
