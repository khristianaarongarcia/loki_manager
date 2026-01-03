package org.lokixcz.theendex.gui

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.lang.Lang
import org.lokixcz.theendex.shop.MarketCategoryFilter
import org.lokixcz.theendex.util.ItemNames

/**
 * Shared market action handler used by both MarketGUI and CustomShopGUI.
 * This ensures identical functionality across different GUI layouts.
 * 
 * All buy/sell operations go through the market command system which handles:
 * - Holdings integration (virtual storage)
 * - Balance checks
 * - Spread pricing
 * - Transaction logging
 * - Event multipliers
 */
object MarketActions {
    
    /**
     * Standard amounts for buy/sell operations.
     */
    val AMOUNTS = listOf(1, 8, 16, 32, 64)
    
    /**
     * Handle an item click in the market GUI.
     * This is the main entry point for buy/sell/details actions.
     * 
     * @param plugin The Endex plugin instance
     * @param player The player who clicked
     * @param material The material that was clicked
     * @param click The type of click
     * @param amount The amount to buy/sell (from amount selector)
     * @param onComplete Callback after action completes (for GUI refresh)
     * @return true if the click was handled, false if the item is not in market
     */
    fun handleItemClick(
        plugin: Endex,
        player: Player,
        material: Material,
        click: ClickType,
        amount: Int,
        onComplete: () -> Unit
    ): Boolean {
        // CRITICAL: Only handle items that exist in the market
        // This matches the default MarketGUI behavior exactly
        if (plugin.marketManager.get(material) == null) {
            return false
        }
        
        when {
            // Details view on shift-left or middle click
            click == ClickType.SHIFT_LEFT || click == ClickType.MIDDLE -> {
                openDetails(plugin, player, material)
            }
            // Buy on left click
            click == ClickType.LEFT -> {
                buy(plugin, player, material, amount, onComplete)
            }
            // Sell on right click
            click == ClickType.RIGHT -> {
                sell(plugin, player, material, amount, onComplete)
            }
        }
        
        return true
    }
    
    /**
     * Execute a buy transaction through the market command.
     * This goes through the holdings system automatically.
     */
    fun buy(plugin: Endex, player: Player, material: Material, amount: Int, onComplete: () -> Unit = {}) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.performCommand("market buy ${material.name} $amount")
            onComplete()
        })
    }
    
    /**
     * Execute a sell transaction through the market command.
     * This goes through the holdings system automatically.
     */
    fun sell(plugin: Endex, player: Player, material: Material, amount: Int, onComplete: () -> Unit = {}) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.performCommand("market sell ${material.name} $amount")
            onComplete()
        })
    }
    
    /**
     * Sell all of a specific material the player has in inventory.
     */
    fun sellAll(plugin: Endex, player: Player, material: Material, onComplete: () -> Unit = {}) {
        val total = player.inventory.contents.filterNotNull()
            .filter { it.type == material }
            .sumOf { it.amount }
        
        if (total > 0) {
            sell(plugin, player, material, total, onComplete)
        } else {
            player.sendMessage(Lang.colorize(Lang.get("market.sell.nothing-to-sell")))
            onComplete()
        }
    }
    
    /**
     * Open the details view for a material.
     * Directly calls MarketGUI.openDetails() for the full details panel.
     */
    fun openDetails(plugin: Endex, player: Player, material: Material) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            plugin.marketGUI.openDetails(player, material)
        })
    }
    
    /**
     * Open the holdings panel.
     * Uses the market holdings command.
     */
    fun openHoldings(plugin: Endex, player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.performCommand("market holdings")
        })
    }
    
    /**
     * Open the deliveries panel.
     * Uses the market deliveries command.
     */
    fun openDeliveries(plugin: Endex, player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.performCommand("market deliveries")
        })
    }
    
    /**
     * Check if a material is tradeable (exists in the market).
     */
    fun isInMarket(plugin: Endex, material: Material): Boolean {
        return plugin.marketManager.get(material) != null
    }
    
    /**
     * Get all market items (for displaying in GUI).
     */
    fun getAllMarketItems(plugin: Endex): List<Material> {
        return plugin.marketManager.allItems().map { it.material }
    }
    
    /**
     * Get market items filtered by category.
     * Uses the same filtering logic as MarketGUI for consistency.
     */
    fun getMarketItemsByFilter(plugin: Endex, filter: MarketCategoryFilter): List<Material> {
        return plugin.marketManager.allItems()
            .filter { mi ->
                when (filter) {
                    MarketCategoryFilter.ALL -> true
                    MarketCategoryFilter.ORES -> mi.material.name.contains("_ORE") || 
                                                  mi.material.name.endsWith("_INGOT") || 
                                                  mi.material.name.endsWith("_BLOCK") ||
                                                  mi.material.name in listOf("COAL", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "QUARTZ", "AMETHYST_SHARD", "RAW_IRON", "RAW_GOLD", "RAW_COPPER")
                    MarketCategoryFilter.FARMING -> mi.material.name.contains("WHEAT") || 
                                                     mi.material.name.contains("SEEDS") || 
                                                     mi.material.name.contains("CARROT") || 
                                                     mi.material.name.contains("POTATO") || 
                                                     mi.material.name.contains("BEETROOT") ||
                                                     mi.material.name.contains("MELON") ||
                                                     mi.material.name.contains("PUMPKIN") ||
                                                     mi.material.name.contains("CACTUS") ||
                                                     mi.material.name.contains("SUGAR_CANE") ||
                                                     mi.material.name.contains("COCOA") ||
                                                     mi.material.name.contains("BAMBOO") ||
                                                     mi.material.name in listOf("SWEET_BERRIES", "GLOW_BERRIES", "NETHER_WART")
                    MarketCategoryFilter.MOB_DROPS -> mi.material.name in listOf(
                        "ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "ENDER_PEARL", "GUNPOWDER",
                        "BLAZE_ROD", "GHAST_TEAR", "SLIME_BALL", "MAGMA_CREAM", "PHANTOM_MEMBRANE",
                        "LEATHER", "RABBIT_HIDE", "RABBIT_FOOT", "FEATHER", "INK_SAC", "GLOW_INK_SAC",
                        "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "NAUTILUS_SHELL", "HEART_OF_THE_SEA",
                        "SHULKER_SHELL", "WITHER_SKELETON_SKULL", "NETHER_STAR", "DRAGON_BREATH",
                        "HONEY_BOTTLE", "HONEYCOMB", "SCUTE", "ARMADILLO_SCUTE", "BREEZE_ROD"
                    )
                    MarketCategoryFilter.BLOCKS -> mi.material.isBlock
                    MarketCategoryFilter.FOOD -> mi.material.isEdible || mi.material.name in listOf(
                        "APPLE", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "BREAD", "COOKED_BEEF", "COOKED_PORKCHOP",
                        "COOKED_CHICKEN", "COOKED_MUTTON", "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON",
                        "BEEF", "PORKCHOP", "CHICKEN", "MUTTON", "RABBIT", "COD", "SALMON",
                        "COOKIE", "CAKE", "PUMPKIN_PIE", "MUSHROOM_STEW", "RABBIT_STEW", "BEETROOT_SOUP",
                        "SUSPICIOUS_STEW", "GOLDEN_CARROT", "MELON_SLICE", "DRIED_KELP", "BAKED_POTATO",
                        "POISONOUS_POTATO", "CHORUS_FRUIT", "TROPICAL_FISH", "PUFFERFISH", "SPIDER_EYE",
                        "ROTTEN_FLESH", "GLOW_BERRIES", "SWEET_BERRIES"
                    )
                    MarketCategoryFilter.TOOLS -> mi.material.name.endsWith("_PICKAXE") ||
                                                   mi.material.name.endsWith("_AXE") ||
                                                   mi.material.name.endsWith("_SHOVEL") ||
                                                   mi.material.name.endsWith("_HOE") ||
                                                   mi.material.name.endsWith("_SWORD") ||
                                                   mi.material.name in listOf(
                                                       "FISHING_ROD", "FLINT_AND_STEEL", "SHEARS", "LEAD", "NAME_TAG",
                                                       "COMPASS", "CLOCK", "SPYGLASS", "BRUSH", "MACE", "BOW", "CROSSBOW",
                                                       "TRIDENT", "SHIELD", "ELYTRA", "TOTEM_OF_UNDYING"
                                                   )
                    MarketCategoryFilter.ARMOR -> mi.material.name.endsWith("_HELMET") ||
                                                   mi.material.name.endsWith("_CHESTPLATE") ||
                                                   mi.material.name.endsWith("_LEGGINGS") ||
                                                   mi.material.name.endsWith("_BOOTS") ||
                                                   mi.material.name in listOf("TURTLE_HELMET", "SHIELD", "ELYTRA", "WOLF_ARMOR")
                    MarketCategoryFilter.REDSTONE -> mi.material.name.contains("REDSTONE") ||
                                                      mi.material.name.contains("PISTON") ||
                                                      mi.material.name.contains("OBSERVER") ||
                                                      mi.material.name.contains("COMPARATOR") ||
                                                      mi.material.name.contains("REPEATER") ||
                                                      mi.material.name.contains("HOPPER") ||
                                                      mi.material.name.contains("DROPPER") ||
                                                      mi.material.name.contains("DISPENSER") ||
                                                      mi.material.name.contains("LEVER") ||
                                                      mi.material.name.contains("BUTTON") ||
                                                      mi.material.name.contains("PRESSURE_PLATE") ||
                                                      mi.material.name.contains("TRIPWIRE") ||
                                                      mi.material.name.contains("DAYLIGHT") ||
                                                      mi.material.name.contains("TARGET") ||
                                                      mi.material.name.contains("SCULK_SENSOR") ||
                                                      mi.material.name in listOf("SLIME_BLOCK", "HONEY_BLOCK", "TNT", "NOTE_BLOCK", "BELL")
                    MarketCategoryFilter.POTIONS -> mi.material.name.contains("POTION") ||
                                                     mi.material.name in listOf(
                                                         "BLAZE_POWDER", "BREWING_STAND", "CAULDRON", "GLASS_BOTTLE",
                                                         "DRAGON_BREATH", "FERMENTED_SPIDER_EYE", "GLISTERING_MELON_SLICE",
                                                         "GOLDEN_CARROT", "MAGMA_CREAM", "NETHER_WART", "RABBIT_FOOT",
                                                         "REDSTONE", "GLOWSTONE_DUST", "GUNPOWDER", "SUGAR", "SPIDER_EYE",
                                                         "GHAST_TEAR", "BLAZE_ROD", "PHANTOM_MEMBRANE", "TURTLE_HELMET"
                                                     )
                    MarketCategoryFilter.MISC -> !matchesAnyFilter(mi.material, listOf(
                        MarketCategoryFilter.ORES, MarketCategoryFilter.FARMING, MarketCategoryFilter.MOB_DROPS,
                        MarketCategoryFilter.BLOCKS, MarketCategoryFilter.FOOD, MarketCategoryFilter.TOOLS,
                        MarketCategoryFilter.ARMOR, MarketCategoryFilter.REDSTONE, MarketCategoryFilter.POTIONS
                    ))
                }
            }
            .map { it.material }
            .sortedBy { prettyName(it).lowercase() }
    }
    
    /**
     * Helper to check if a material matches any of the given filters.
     * Used for MISC category to find items that don't fit elsewhere.
     */
    private fun matchesAnyFilter(material: Material, filters: List<MarketCategoryFilter>): Boolean {
        for (filter in filters) {
            val matches = when (filter) {
                MarketCategoryFilter.ALL -> true
                MarketCategoryFilter.ORES -> material.name.contains("_ORE") || 
                                              material.name.endsWith("_INGOT") || 
                                              material.name.endsWith("_BLOCK") ||
                                              material.name in listOf("COAL", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "QUARTZ", "AMETHYST_SHARD", "RAW_IRON", "RAW_GOLD", "RAW_COPPER")
                MarketCategoryFilter.FARMING -> material.name.contains("WHEAT") || 
                                                 material.name.contains("SEEDS") || 
                                                 material.name.contains("CARROT") || 
                                                 material.name.contains("POTATO") || 
                                                 material.name.contains("BEETROOT") ||
                                                 material.name.contains("MELON") ||
                                                 material.name.contains("PUMPKIN") ||
                                                 material.name.contains("CACTUS") ||
                                                 material.name.contains("SUGAR_CANE") ||
                                                 material.name.contains("COCOA") ||
                                                 material.name.contains("BAMBOO") ||
                                                 material.name in listOf("SWEET_BERRIES", "GLOW_BERRIES", "NETHER_WART")
                MarketCategoryFilter.MOB_DROPS -> material.name in listOf(
                    "ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "ENDER_PEARL", "GUNPOWDER",
                    "BLAZE_ROD", "GHAST_TEAR", "SLIME_BALL", "MAGMA_CREAM", "PHANTOM_MEMBRANE",
                    "LEATHER", "RABBIT_HIDE", "RABBIT_FOOT", "FEATHER", "INK_SAC", "GLOW_INK_SAC",
                    "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "NAUTILUS_SHELL", "HEART_OF_THE_SEA",
                    "SHULKER_SHELL", "WITHER_SKELETON_SKULL", "NETHER_STAR", "DRAGON_BREATH",
                    "HONEY_BOTTLE", "HONEYCOMB", "SCUTE", "ARMADILLO_SCUTE", "BREEZE_ROD"
                )
                MarketCategoryFilter.BLOCKS -> material.isBlock
                MarketCategoryFilter.FOOD -> material.isEdible
                MarketCategoryFilter.TOOLS -> material.name.endsWith("_PICKAXE") ||
                                               material.name.endsWith("_AXE") ||
                                               material.name.endsWith("_SHOVEL") ||
                                               material.name.endsWith("_HOE") ||
                                               material.name.endsWith("_SWORD")
                MarketCategoryFilter.ARMOR -> material.name.endsWith("_HELMET") ||
                                               material.name.endsWith("_CHESTPLATE") ||
                                               material.name.endsWith("_LEGGINGS") ||
                                               material.name.endsWith("_BOOTS")
                MarketCategoryFilter.REDSTONE -> material.name.contains("REDSTONE") ||
                                                  material.name.contains("PISTON") ||
                                                  material.name.contains("HOPPER")
                MarketCategoryFilter.POTIONS -> material.name.contains("POTION")
                MarketCategoryFilter.MISC -> false
            }
            if (matches) return true
        }
        return false
    }
    
    /**
     * Build the standard item lore matching MarketGUI format.
     */
    fun buildItemLore(plugin: Endex, player: Player, material: Material, currentAmount: Int): List<String> {
        val mi = plugin.marketManager.get(material) ?: return emptyList()
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
        
        // Calculate buy/sell prices with spread
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        val effectivePrice = current * mul
        val buyPrice = effectivePrice * (1.0 + buyMarkupPct / 100.0)
        val sellPrice = effectivePrice * (1.0 - sellMarkdownPct / 100.0)
        
        // Show last cycle demand/supply in percent impact terms
        val ds = mi.lastDemand - mi.lastSupply
        val sens = plugin.config.getDouble("price-sensitivity", 0.05)
        val estPct = ds * sens * 100.0
        
        val last5 = mi.history.takeLast(5).map { String.format("%.2f", it.price) }
        val bal = plugin.economy?.getBalance(player) ?: 0.0
        val invCount = player.inventory.contents.filterNotNull().filter { it.type == material }.sumOf { it.amount }
        
        val lore = mutableListOf<String>()
        
        // Build lore matching original MarketGUI format
        lore += "${ChatColor.GRAY}Price: ${ChatColor.GREEN}${format(current)} ${ChatColor.GRAY}(${arrow} ${format(diff)}, ${formatPct(pct)})"
        
        // Show buy/sell prices with spread
        if (spreadEnabled && (buyMarkupPct > 0 || sellMarkdownPct > 0)) {
            lore += "${ChatColor.GREEN}Buy: ${format(buyPrice)} ${ChatColor.GRAY}| ${ChatColor.YELLOW}Sell: ${format(sellPrice)}"
        }
        
        lore += "${ChatColor.DARK_GRAY}Last cycle: ${ChatColor.GRAY}Demand ${format(mi.lastDemand)} / Supply ${format(mi.lastSupply)} (${formatPct(estPct)})"
        
        if (mul != 1.0) {
            lore += "${ChatColor.DARK_AQUA}Event: x${format(mul)} ${ChatColor.GRAY}Eff: ${ChatColor.GREEN}${format(current * mul)}"
        }
        
        lore += "${ChatColor.DARK_GRAY}Min ${format(mi.minPrice)}  Max ${format(mi.maxPrice)}"
        lore += "${ChatColor.GRAY}History: ${last5.joinToString(" ${ChatColor.DARK_GRAY}| ${ChatColor.GRAY}")}"
        lore += "${ChatColor.DARK_GRAY}Left: Buy  Right: Sell  Amount: $currentAmount"
        lore += "${ChatColor.DARK_GRAY}Shift/Middle-click: Details"
        lore += "${ChatColor.GRAY}You have: ${ChatColor.AQUA}$invCount ${material.name}"
        lore += "${ChatColor.GRAY}Balance: ${ChatColor.GOLD}${format(bal)}"
        
        return lore
    }
    
    /**
     * Create an item display for the market GUI.
     * Uses translatable item names so they appear in the player's Minecraft client language.
     */
    fun createMarketItem(plugin: Endex, player: Player, material: Material, currentAmount: Int): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        
        // Use translatable name so item appears in player's Minecraft client language
        meta.displayName(ItemNames.translatable(material, NamedTextColor.AQUA))
        meta.lore = buildItemLore(plugin, player, material, currentAmount)
        
        item.itemMeta = meta
        return item
    }
    
    // ─────────────────────────────────────────────────────────────────────────
    // Utility functions
    // ─────────────────────────────────────────────────────────────────────────
    
    private fun format(n: Double): String = String.format("%.2f", n)
    private fun formatPct(n: Double): String = String.format("%.2f%%", n)
    
    fun prettyName(mat: Material): String = mat.name.lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
}
