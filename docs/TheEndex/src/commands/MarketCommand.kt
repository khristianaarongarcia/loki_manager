package org.lokixcz.theendex.commands

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.invest.InvestmentsManager
import org.lokixcz.theendex.lang.Lang
import kotlin.math.max
import org.lokixcz.theendex.gui.MarketGUI
import org.lokixcz.theendex.api.events.PreBuyEvent
import org.lokixcz.theendex.api.events.PreSellEvent

class MarketCommand(private val plugin: Endex) : CommandExecutor {
    private val investments by lazy { InvestmentsManager(plugin) }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) {
                // Check shop mode - CUSTOM opens category-based shop, DEFAULT opens market
                val shopMode = plugin.config.getString("shop.mode", "DEFAULT")?.uppercase() ?: "DEFAULT"
                if (shopMode == "CUSTOM" && plugin.customShopGUI != null) {
                    plugin.customShopGUI!!.openMainMenu(sender)
                } else {
                    plugin.marketGUI.open(sender)
                }
            } else {
                sender.sendMessage(Lang.prefixed("help.commands.market"))
            }
            return true
        }
        return when (args[0].lowercase()) {
            "help", "?" -> handleHelp(sender)
            "price" -> handlePrice(sender, args)
            "buy" -> handleBuy(sender, args)
            "sell" -> handleSell(sender, args)
            "sellholdings" -> handleSellHoldings(sender, args)
            "top" -> handleTop(sender)
            "invest" -> handleInvest(sender, args)
            "event" -> handleEvent(sender, args)
            "delivery", "deliveries" -> handleDelivery(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "holdings" -> handleHoldings(sender, args)
            "shop" -> handleShop(sender, args)  // New: Direct shop access
            "default", "stock" -> handleDefaultMarket(sender)  // New: Force default market
            "editor" -> handleEditor(sender)  // New: Shop editor
            // Admin item management commands
            "add" -> handleAddItem(sender, args)
            "remove" -> handleRemoveItem(sender, args)
            "setbase" -> handleSetBase(sender, args)
            "setmin" -> handleSetMin(sender, args)
            "setmax" -> handleSetMax(sender, args)
            "setprice" -> handleSetPrice(sender, args)
            "enable" -> handleEnableItem(sender, args)
            "disable" -> handleDisableItem(sender, args)
            "items" -> handleListItems(sender, args)
            else -> {
                sender.sendMessage(Lang.get("general.invalid-args"))
                true
            }
        }
    }
    
    /**
     * Open custom shop directly (if enabled).
     */
    private fun handleShop(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        val gui = plugin.customShopGUI
        if (gui == null) {
            sender.sendMessage(Lang.get("shops.not-available"))
            return true
        }
        
        val shopId = if (args.size > 1) args[1] else null
        gui.openMainMenu(sender, shopId)
        return true
    }
    
    /**
     * Force open default market GUI (bypasses shop mode).
     */
    private fun handleDefaultMarket(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        plugin.marketGUI.open(sender)
        return true
    }
    
    /**
     * Open shop editor GUI (/market editor).
     */
    private fun handleEditor(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        if (!sender.hasPermission("endex.shop.editor")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        
        val editor = plugin.shopEditorGUI
        if (editor == null) {
            sender.sendMessage(Lang.get("shops.editor-not-available"))
            return true
        }
        
        editor.openShopManager(sender)
        return true
    }

    private fun handleHelp(sender: CommandSender): Boolean {
        sender.sendMessage(Lang.colorize(Lang.get("market-help.separator")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.title")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.separator")))
        sender.sendMessage(Lang.get("market-help.empty-line"))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.market")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.shop")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.stock")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.price")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.buy")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.sell")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.sellholdings")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.basic.top")))
        sender.sendMessage(Lang.get("market-help.empty-line"))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings-section")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.holdings")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.withdraw")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.withdraw-all")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.delivery")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.delivery-claim")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.holdings.delivery-claim-all")))
        sender.sendMessage(Lang.get("market-help.empty-line"))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.invest-section")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.invest.buy")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.invest.list")))
        sender.sendMessage(Lang.colorize(Lang.get("market-help.invest.redeem")))
        
        if (sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("market-help.empty-line"))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-events-section")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-events.list")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-events.trigger")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-events.end")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-events.clear")))
            sender.sendMessage(Lang.get("market-help.empty-line"))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-editor-section")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-editor.editor")))
            sender.sendMessage(Lang.get("market-help.empty-line"))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items-section")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.add")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.remove")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.setbase")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.setmin")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.setmax")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.setprice")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.toggle")))
            sender.sendMessage(Lang.colorize(Lang.get("market-help.admin-items.items")))
        }
        
        sender.sendMessage(Lang.colorize(Lang.get("market-help.separator")))
        return true
    }

    private fun handlePrice(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(Lang.colorize(Lang.get("market-price.usage")))
            return true
        }
        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.colorize(Lang.get("market-price.unknown-material", "material" to args[1])))
            return true
        }
        val item = plugin.marketManager.get(mat)
        if (item == null) {
            sender.sendMessage(Lang.colorize(Lang.get("market-price.not-tracked", "material" to mat.name)))
            return true
        }
    val current = item.currentPrice
    val prev = item.history.toList().dropLast(1).lastOrNull()?.price ?: current
        val diff = current - prev
        val arrow = when {
            diff > 0.0001 -> "${ChatColor.GREEN}↑"
            diff < -0.0001 -> "${ChatColor.RED}↓"
            else -> "${ChatColor.YELLOW}→"
        }
    val mul = plugin.eventManager.multiplierFor(mat)
    sender.sendMessage(Lang.colorize(Lang.get("market-price.header", "item" to prettyName(mat), "price" to format(current), "arrow" to arrow, "diff" to format(diff))))
    if (mul != 1.0) sender.sendMessage(Lang.colorize(Lang.get("market-price.event-multiplier", "multiplier" to format(mul), "effective" to format(current*mul))))
        sender.sendMessage(Lang.colorize(Lang.get("market-price.stats", "base" to format(item.basePrice), "min" to format(item.minPrice), "max" to format(item.maxPrice))))
        // history line (last up to 5)
        if (item.history.isNotEmpty()) {
            val last = item.history.takeLast(12)
            val lastNums = last.map { it.price }
            val min = lastNums.minOrNull() ?: 0.0
            val max = lastNums.maxOrNull() ?: 0.0
            val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
            val bars = lastNums.map {
                val pct = (it - min) / span
                when {
                    pct >= 0.85 -> "█"
                    pct >= 0.70 -> "▇"
                    pct >= 0.55 -> "▆"
                    pct >= 0.40 -> "▅"
                    pct >= 0.25 -> "▃"
                    pct >= 0.10 -> "▂"
                    else -> "▁"
                }
            }.joinToString("")
            val last5 = item.history.takeLast(5).map { format(it.price) }
            sender.sendMessage(Lang.colorize(Lang.get("market-price.history-label", "history" to last5.joinToString(separator = " ${ChatColor.DARK_GRAY}| ${ChatColor.GRAY}"))))
            sender.sendMessage(Lang.colorize(Lang.get("market-price.chart-label", "chart" to bars)))
        }
        return true
    }

    private fun handleBuy(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        if (!sender.hasPermission("theendex.buy")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (plugin.economy == null) {
            sender.sendMessage(Lang.get("errors.economy-unavailable"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("market.buy.usage"))
            return true
        }
        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) { sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1])); return true }
        var amount: Int = args[2].toIntOrNull()?.takeIf { it > 0 }
            ?: run { sender.sendMessage(Lang.get("general.invalid-amount")); return true }
        val item = plugin.marketManager.get(mat)
        if (item == null) {
            sender.sendMessage(Lang.get("market.buy.item-not-tracked", "item" to mat.name))
            return true
        }

        // Check if virtual holdings system is enabled
        val holdingsEnabled = plugin.config.getBoolean("holdings.enabled", true)
        val db = plugin.marketManager.sqliteStore()

        val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
        
        // Apply spread markup for buying (anti-arbitrage protection)
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val buyMarkupPct = if (spreadEnabled) plugin.config.getDouble("spread.buy-markup-percent", 1.5).coerceAtLeast(0.0) else 0.0
        
        var unit = item.currentPrice * (plugin.eventManager.multiplierFor(mat)) * (1.0 + buyMarkupPct / 100.0)
        
        // Fire pre-buy event (modifiable)
        val pre = PreBuyEvent(mat, amount, unit)
        org.bukkit.Bukkit.getPluginManager().callEvent(pre)
        if (pre.isCancelled) { sender.sendMessage(Lang.get("market.buy.cancelled")); return true }
        amount = pre.amount
        unit = pre.unitPrice
        val subtotal = unit * amount
        val tax = subtotal * (taxPct / 100.0)
        val total = subtotal + tax

        val eco = plugin.economy!!
        val bal = eco.getBalance(sender)
        if (bal + 1e-9 < total) {
            sender.sendMessage(Lang.get("market.buy.not-enough-money", "price" to format(total), "balance" to format(bal)))
            return true
        }
        
        // Virtual Holdings Mode: Items go to holdings, not inventory
        if (holdingsEnabled && db != null) {
            // Check holdings limit before purchase
            val success = db.addToHoldings(sender.uniqueId.toString(), mat, amount, unit)
            if (!success) {
                val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
                sender.sendMessage(Lang.get("market.holdings.limit-reached", "max" to maxHoldings))
                sender.sendMessage(Lang.get("market.holdings.withdraw-hint"))
                return true
            }
            
            // Payment successful, deduct money
            val withdraw = eco.withdrawPlayer(sender, total)
            if (!withdraw.transactionSuccess()) {
                // Rollback holdings if payment fails
                db.removeFromHoldings(sender.uniqueId.toString(), mat, amount)
                sender.sendMessage(Lang.get("errors.payment-failed", "error" to (withdraw.errorMessage ?: "Unknown")))
                return true
            }
            
            // Record trade
            db.insertTrade(sender.uniqueId.toString(), mat, "BUY", amount, unit, total)
            
            plugin.marketManager.addDemand(mat, amount.toDouble())
            
            sender.sendMessage(Lang.prefixed("market.buy.success", "amount" to amount, "item" to prettyName(mat), "price" to format(total)))
            sender.sendMessage(Lang.get("market.holdings.added-to-holdings"))
            sender.sendMessage(Lang.get("market.holdings.withdraw-command", "item" to mat.name))
            if (tax > 0) {
                sender.sendMessage(Lang.get("market.buy.tax-info", "tax" to format(tax), "percent" to taxPct.toString()))
            }
            return true
        }
        
        // Legacy Mode: Direct to inventory (fallback if holdings disabled)
        val maxCapacity = calculateInventoryCapacity(sender, mat)
        val originalAmount = amount
        val deliveryEnabled = plugin.getDeliveryManager() != null && plugin.config.getBoolean("delivery.enabled", true)
        
        if (amount > maxCapacity) {
            if (!deliveryEnabled) {
                // Old behavior: cap the purchase
                amount = maxCapacity
                if (amount <= 0) {
                    sender.sendMessage(Lang.get("market.buy.inventory-full"))
                    return true
                }
                sender.sendMessage(Lang.prefixed("market.buy.capped", "amount" to amount, "item" to mat.name, "requested" to originalAmount))
                sender.sendMessage(Lang.get("market.buy.capacity-hint"))
            }
            // Else: delivery enabled, we'll charge for full amount and overflow goes to pending
        }

        val withdraw = eco.withdrawPlayer(sender, total)
        if (!withdraw.transactionSuccess()) {
            sender.sendMessage(Lang.get("errors.payment-failed", "error" to (withdraw.errorMessage ?: "Unknown")))
            return true
        }
        // Give items
        fun countMaterial(): Int = sender.inventory.contents
            .filterNotNull()
            .filter { it.type == mat }
            .sumOf { it.amount }

        var remaining = amount
        var delivered = 0
        var pendingDelivery = 0
        var safety = 0
        
        while (remaining > 0) {
            safety += 1
            if (safety > 256) {
                plugin.logger.warning("Market buy safety break triggered for ${sender.name} purchasing ${mat.name}; remaining=$remaining")
                break
            }

            val beforeCount = countMaterial()
            val toGive = max(1, minOf(remaining, mat.maxStackSize))
            val give = ItemStack(mat, toGive)
            val leftovers = sender.inventory.addItem(give)
            val leftoverCount = leftovers.values.sumOf { it.amount }
            val afterGiveCount = countMaterial()
            val accepted = (afterGiveCount - beforeCount).coerceAtLeast(0)
            
            if (accepted > 0) {
                delivered += accepted
                remaining -= accepted
            }

            if (accepted == 0 && leftoverCount == 0) {
                if (deliveryEnabled) {
                    val success = plugin.getDeliveryManager()?.addPending(sender.uniqueId, mat, remaining) ?: false
                    if (success) {
                        pendingDelivery += remaining
                    } else {
                        sender.sendMessage(Lang.colorize(Lang.get("market-transaction.item-dropped", "count" to remaining.toString(), "item" to mat.name)))
                        repeat(remaining) { sender.world.dropItemNaturally(sender.location, ItemStack(mat, 1)) }
                    }
                } else {
                    repeat(remaining) { sender.world.dropItemNaturally(sender.location, ItemStack(mat, 1)) }
                    sender.sendMessage(Lang.colorize(Lang.get("market-transaction.item-dropped-zero", "count" to remaining.toString(), "item" to mat.name)))
                }
                remaining = 0
                break
            }

            if (leftoverCount > 0) {
                if (deliveryEnabled) {
                    val success = plugin.getDeliveryManager()?.addPending(sender.uniqueId, mat, remaining) ?: false
                    if (success) {
                        pendingDelivery += remaining
                    } else {
                        leftovers.values.forEach { sender.world.dropItemNaturally(sender.location, it) }
                        sender.sendMessage(Lang.colorize(Lang.get("market-transaction.item-dropped-limit", "count" to leftoverCount.toString(), "item" to mat.name)))
                    }
                } else {
                    leftovers.values.forEach { sender.world.dropItemNaturally(sender.location, it) }
                    sender.sendMessage(Lang.colorize(Lang.get("market-transaction.item-dropped-full", "count" to leftoverCount.toString(), "item" to mat.name)))
                }
                remaining = 0
                break
            }
        }

        plugin.marketManager.addDemand(mat, amount.toDouble())
        
        // Build success message
        if (pendingDelivery > 0) {
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.buy-success", "amount" to amount.toString(), "item" to mat.name, "price" to format(total))))
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.buy-delivered", "delivered" to delivered.toString(), "pending" to pendingDelivery.toString())))
        } else if (delivered == amount) {
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.buy-with-tax", "amount" to amount.toString(), "item" to mat.name, "price" to format(total), "tax" to format(tax), "taxpct" to taxPct.toString())))
        } else {
            // Some items dropped (delivery disabled or limit reached)
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.buy-dropped", "amount" to amount.toString(), "item" to mat.name, "price" to format(total))))
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.buy-dropped-note", "delivered" to delivered.toString(), "dropped" to (amount - delivered).toString())))
        }
        
        return true
    }

    private fun handleSell(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        if (!sender.hasPermission("theendex.sell")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (plugin.economy == null) {
            sender.sendMessage(Lang.get("errors.economy-unavailable"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("market.sell.usage"))
            return true
        }
        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) { sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1])); return true }
        var amount: Int = args[2].toIntOrNull()?.takeIf { it > 0 }
            ?: run { sender.sendMessage(Lang.get("general.invalid-amount")); return true }
        val item = plugin.marketManager.get(mat)
        if (item == null) {
            sender.sendMessage(Lang.get("market.sell.item-not-tracked", "item" to mat.name))
            return true
        }

        val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
        
        // Apply spread markdown for selling (anti-arbitrage protection)
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        
        var unit = item.currentPrice * (plugin.eventManager.multiplierFor(mat)) * (1.0 - sellMarkdownPct / 100.0)
        // Fire pre-sell event (modifiable)
        val pre = PreSellEvent(mat, amount, unit)
        org.bukkit.Bukkit.getPluginManager().callEvent(pre)
        if (pre.isCancelled) { sender.sendMessage(Lang.get("market.sell.cancelled")); return true }
        amount = pre.amount
        unit = pre.unitPrice
        // Now remove items based on possibly adjusted amount
        val removed = removeItems(sender, mat, amount)
        if (removed < amount) {
            sender.sendMessage(Lang.get("market.sell.not-enough-items", "owned" to removed, "required" to amount, "item" to mat.name))
            return true
        }
        val subtotal = unit * amount
        val tax = subtotal * (taxPct / 100.0)
        val payout = subtotal - tax

        val eco = plugin.economy!!
        val deposit = eco.depositPlayer(sender, payout)
        if (!deposit.transactionSuccess()) {
            sender.sendMessage(Lang.get("errors.payment-failed", "error" to (deposit.errorMessage ?: "Unknown")))
            return true
        }

        plugin.marketManager.addSupply(mat, amount.toDouble())
        sender.sendMessage(Lang.prefixed("market.sell.success", "amount" to amount, "item" to mat.name, "price" to format(payout), "tax" to format(tax), "percent" to taxPct.toString()))
        return true
    }

    /**
     * Sell items directly from virtual holdings (not from player inventory).
     */
    private fun handleSellHoldings(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        if (!sender.hasPermission("theendex.sell")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (plugin.economy == null) {
            sender.sendMessage(Lang.get("errors.economy-unavailable"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("market.sellholdings.usage"))
            return true
        }
        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) { sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1])); return true }
        var amount: Int = args[2].toIntOrNull()?.takeIf { it > 0 }
            ?: run { sender.sendMessage(Lang.get("general.invalid-amount")); return true }
        val item = plugin.marketManager.get(mat)
        if (item == null) {
            sender.sendMessage(Lang.get("market.sell.item-not-tracked", "item" to mat.name))
            return true
        }

        // Get database reference
        val db = plugin.marketManager.sqliteStore()
        if (db == null) {
            sender.sendMessage(Lang.get("errors.database-error"))
            return true
        }

        // Check holdings first
        val holdingsData = db.getHolding(sender.uniqueId.toString(), mat)
        val holdingsQty = holdingsData?.first ?: 0
        if (holdingsQty <= 0) {
            sender.sendMessage(Lang.get("market.holdings.none-of-item", "item" to mat.name))
            return true
        }

        val taxPct = plugin.config.getDouble("transaction-tax-percent", 0.0).coerceAtLeast(0.0)
        
        // Apply spread markdown for selling (anti-arbitrage protection)
        val spreadEnabled = plugin.config.getBoolean("spread.enabled", true)
        val sellMarkdownPct = if (spreadEnabled) plugin.config.getDouble("spread.sell-markdown-percent", 1.5).coerceAtLeast(0.0) else 0.0
        
        var unit = item.currentPrice * (plugin.eventManager.multiplierFor(mat)) * (1.0 - sellMarkdownPct / 100.0)
        // Fire pre-sell event (modifiable)
        val pre = PreSellEvent(mat, amount, unit)
        org.bukkit.Bukkit.getPluginManager().callEvent(pre)
        if (pre.isCancelled) { sender.sendMessage(Lang.get("market.sell.cancelled")); return true }
        amount = pre.amount
        unit = pre.unitPrice
        
        // Remove from holdings instead of inventory
        val removed = db.removeFromHoldings(sender.uniqueId.toString(), mat, amount)
        if (removed < amount) {
            sender.sendMessage(Lang.get("market.holdings.not-enough", "owned" to removed, "required" to amount, "item" to mat.name))
            return true
        }
        val subtotal = unit * removed
        val tax = subtotal * (taxPct / 100.0)
        val payout = subtotal - tax

        val eco = plugin.economy!!
        val deposit = eco.depositPlayer(sender, payout)
        if (!deposit.transactionSuccess()) {
            sender.sendMessage(Lang.get("errors.payment-failed", "error" to (deposit.errorMessage ?: "Unknown")))
            return true
        }

        plugin.marketManager.addSupply(mat, removed.toDouble())
        sender.sendMessage(Lang.prefixed("market.sellholdings.success", "amount" to removed, "item" to mat.name, "price" to format(payout), "tax" to format(tax), "percent" to taxPct.toString()))
        return true
    }

    private fun handleTop(sender: CommandSender): Boolean {
        val entries = plugin.marketManager.allItems()
        if (entries.isEmpty()) {
            sender.sendMessage(Lang.get("market.trend.no-data"))
            return true
        }
        val changes = entries.map { it.material to pctChange(it.history) }
        val topGainers = changes.sortedByDescending { it.second }.take(5)
        val topLosers = changes.sortedBy { it.second }.take(5)

        sender.sendMessage(Lang.prefixed("market.trend.top-gainers"))
        topGainers.forEach {
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.top-gainer", "percent" to formatPct(it.second), "item" to prettyName(it.first))))
        }
        sender.sendMessage(Lang.prefixed("market.trend.top-losers"))
        topLosers.forEach {
            sender.sendMessage(Lang.colorize(Lang.get("market-transaction.top-loser", "percent" to formatPct(it.second), "item" to prettyName(it.first))))
        }
        return true
    }

    private fun pctChange(history: Collection<org.lokixcz.theendex.market.PricePoint>): Double {
        if (history.size < 2) return 0.0
        val list = if (history is List<org.lokixcz.theendex.market.PricePoint>) history else history.toList()
        val last = list.last().price
        val prev = list[list.lastIndex - 1].price
        if (prev == 0.0) return 0.0
        return (last - prev) / prev * 100.0
    }

    private fun removeItems(player: Player, material: Material, amount: Int): Int {
        var toRemove = amount
        val inv = player.inventory
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type != material) continue
            val take = minOf(toRemove, stack.amount)
            stack.amount -= take
            if (stack.amount <= 0) inv.setItem(slot, null)
            toRemove -= take
            if (toRemove <= 0) break
        }
        return amount - toRemove
    }

    /**
     * Calculate how many items of the given material the player can receive in their inventory.
     * Accounts for existing partial stacks and empty slots.
     */
    private fun calculateInventoryCapacity(player: Player, material: Material): Int {
        val inv = player.inventory
        val maxStack = material.maxStackSize
        var capacity = 0
        
        // Count space in existing stacks of this material
        for (slot in 0 until inv.size) {
            val stack = inv.getItem(slot) ?: continue
            if (stack.type == material) {
                capacity += (maxStack - stack.amount).coerceAtLeast(0)
            }
        }
        
        // Count empty slots (each can hold maxStack items)
        val emptySlots = inv.storageContents.count { it == null || it.type == Material.AIR }
        capacity += emptySlots * maxStack
        
        return capacity
    }

    private fun format(n: Double): String = String.format("%.2f", n)
    private fun formatPct(n: Double): String = String.format("%.2f%%", n)
    private fun prettyName(mat: Material): String = mat.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }

    private fun handleInvest(sender: CommandSender, args: Array<out String>): Boolean {
        if (!plugin.config.getBoolean("investments.enabled", true)) {
            sender.sendMessage(Lang.get("market.invest.not-enabled")); return true
        }
        if (sender !is Player) { sender.sendMessage(Lang.get("general.player-only")); return true }
        if (!sender.hasPermission("theendex.invest")) { sender.sendMessage(Lang.get("general.no-permission")); return true }
        if (args.size == 1) {
            sender.sendMessage(Lang.prefixed("market.invest.usage"))
            return true
        }
        when (args[1].lowercase()) {
            "buy" -> {
                if (plugin.economy == null) { sender.sendMessage(Lang.get("errors.economy-unavailable")); return true }
                if (args.size < 4) { sender.sendMessage(Lang.get("market.invest.buy-usage")); return true }
                val mat = Material.matchMaterial(args[2].uppercase()) ?: run { sender.sendMessage(Lang.get("general.invalid-item", "item" to args[2])); return true }
                val amt = args[3].toDoubleOrNull()?.takeIf { it > 0 } ?: run { sender.sendMessage(Lang.get("general.invalid-amount")); return true }
                val item = plugin.marketManager.get(mat) ?: run { sender.sendMessage(Lang.get("market.buy.item-not-tracked", "item" to mat.name)); return true }
                val unit = item.currentPrice * plugin.eventManager.multiplierFor(mat)
                val totalCost = unit * amt
                val eco = plugin.economy!!
                if (eco.getBalance(sender) + 1e-9 < totalCost) { sender.sendMessage(Lang.get("market.invest.not-enough-money", "price" to format(totalCost))); return true }
                val res = eco.withdrawPlayer(sender, totalCost)
                if (!res.transactionSuccess()) { sender.sendMessage(Lang.get("errors.payment-failed", "error" to (res.errorMessage ?: "Unknown"))); return true }
                // Buy an investment certificate tracking material and principal paid
                val inv = investments.buy(sender.uniqueId, mat.name, totalCost)
                sender.sendMessage(Lang.prefixed("market.invest.success", "item" to prettyName(mat), "price" to format(totalCost), "apr" to investments.defaultApr().toString()))
                return true
            }
            "list" -> {
                val list = investments.list(sender.uniqueId)
                if (list.isEmpty()) { sender.sendMessage(Lang.get("market.invest.no-investments")); return true }
                sender.sendMessage(Lang.prefixed("market.invest.list-header"))
                list.forEach { s ->
                    sender.sendMessage("${ChatColor.GRAY}- ${ChatColor.AQUA}${s.material}${ChatColor.GRAY} ${s.id.take(8)}…  P: ${format(s.principal)}  Accrued: ${ChatColor.GREEN}${format(s.accrued)}")
                }
                return true
            }
            "redeem-all" -> {
                if (plugin.economy == null) { sender.sendMessage(Lang.get("errors.economy-unavailable")); return true }
                val (payout, count) = investments.redeemAll(sender.uniqueId)
                if (count == 0) { sender.sendMessage(Lang.get("market.invest.no-investments")); return true }
                val eco = plugin.economy!!
                val res = eco.depositPlayer(sender, payout)
                if (!res.transactionSuccess()) { sender.sendMessage(Lang.get("errors.payment-failed", "error" to (res.errorMessage ?: "Unknown"))); return true }
                sender.sendMessage(Lang.prefixed("market.invest.redeem-success", "count" to count, "payout" to format(payout)))
                return true
            }
        }
        sender.sendMessage(Lang.get("market.invest.unknown-subcommand"))
        return true
    }

    private fun handleEvent(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size == 1) {
            sender.sendMessage(Lang.prefixed("market.event.header"))
            val defs = plugin.eventManager.listEvents()
            if (defs.isEmpty()) sender.sendMessage(Lang.get("market.event.none")) else defs.forEach {
                val target = it.affectedCategory ?: it.affected
                val bc = if (it.broadcast) Lang.get("market.event.broadcast") else Lang.get("market.event.silent")
                sender.sendMessage("${ChatColor.AQUA}${it.name}${ChatColor.GRAY} -> ${target} x${it.multiplier} ${it.durationMinutes}m ${bc}")
            }
            val act = plugin.eventManager.listActive()
            if (act.isNotEmpty()) sender.sendMessage(Lang.prefixed("market.event.active"))
            act.forEach { sender.sendMessage("${ChatColor.AQUA}${it.event.name}${ChatColor.GRAY} ends ${ChatColor.YELLOW}${java.time.Duration.between(java.time.Instant.now(), it.endsAt).toMinutes()}m") }
            sender.sendMessage(Lang.get("market.event.usage-hint"))
            return true
        }
        if (args[1].equals("clear", ignoreCase = true)) {
            val n = plugin.eventManager.clearAll()
            sender.sendMessage(Lang.prefixed("market.event.cleared", "count" to n))
            return true
        }
        if (args[1].equals("end", ignoreCase = true)) {
            if (args.size < 3) { sender.sendMessage(Lang.get("market.event.end-usage")); return true }
            val name = args.drop(2).joinToString(" ")
            val ok = plugin.eventManager.end(name)
            if (!ok) sender.sendMessage(Lang.get("market.event.not-found", "name" to name))
            return true
        }
        val name = args.drop(1).joinToString(" ")
        val ok = plugin.eventManager.trigger(name)
        if (!ok) sender.sendMessage(Lang.get("market.event.unknown", "name" to name))
        return true
    }

    private fun handleDelivery(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        val deliveryMgr = plugin.getDeliveryManager()
        if (deliveryMgr == null || !plugin.config.getBoolean("delivery.enabled", true)) {
            sender.sendMessage(Lang.get("market.delivery.not-enabled"))
            return true
        }
        
        // /market delivery (default: list)
        if (args.size == 1) {
            return handleDeliveryList(sender, deliveryMgr)
        }
        
        return when (args[1].lowercase()) {
            "list" -> handleDeliveryList(sender, deliveryMgr)
            "claim" -> handleDeliveryClaim(sender, deliveryMgr, args)
            "claim-all", "claimall" -> handleDeliveryClaimAll(sender, deliveryMgr)
            "gui" -> {
                // Open deliveries GUI panel directly
                plugin.marketGUI.open(sender)
                sender.sendMessage(Lang.prefixed("market.delivery.gui-hint"))
                true
            }
            else -> {
                sender.sendMessage(Lang.get("market.delivery.usage"))
                true
            }
        }
    }
    
    private fun handleDeliveryList(player: Player, deliveryMgr: org.lokixcz.theendex.delivery.DeliveryManager): Boolean {
        val pending = deliveryMgr.listPending(player.uniqueId)
        
        if (pending.isEmpty()) {
            player.sendMessage(Lang.prefixed("market.delivery.no-pending"))
            player.sendMessage(Lang.get("market.delivery.no-pending-hint"))
            return true
        }
        
        val totalCount = pending.values.sum()
        player.sendMessage(Lang.get("market.gui.divider"))
        player.sendMessage(Lang.prefixed("market.delivery.header", "count" to totalCount))
        player.sendMessage(Lang.get("market.gui.divider"))
        
        pending.entries.sortedByDescending { it.value }.forEach { (material, amount) ->
            player.sendMessage(Lang.colorize(Lang.get("market-holdings-display.item-line", "item" to prettyName(material), "amount" to amount.toString())))
        }
        
        player.sendMessage(Lang.get("market.gui.divider"))
        player.sendMessage(Lang.get("market.delivery.claim-hint"))
        player.sendMessage(Lang.get("market.delivery.claim-all-hint"))
        player.sendMessage(Lang.get("market.delivery.gui-click-hint"))
        return true
    }
    
    private fun handleDeliveryClaim(player: Player, deliveryMgr: org.lokixcz.theendex.delivery.DeliveryManager, args: Array<out String>): Boolean {
        if (args.size < 3) {
            player.sendMessage(Lang.get("market.delivery.claim-usage"))
            player.sendMessage(Lang.get("market.delivery.claim-example"))
            return true
        }
        
        val mat = Material.matchMaterial(args[2].uppercase())
        if (mat == null) {
            player.sendMessage(Lang.get("general.invalid-item", "item" to args[2]))
            return true
        }
        
        val requestedAmount = if (args.size >= 4) {
            args[3].toIntOrNull()?.takeIf { it > 0 } ?: run {
                player.sendMessage(Lang.get("general.invalid-amount"))
                return true
            }
        } else {
            Int.MAX_VALUE // Claim all by default
        }
        
        val result = deliveryMgr.claimMaterial(player, mat, requestedAmount)
        
        if (result.error != null) {
            player.sendMessage(Lang.get("errors.generic", "error" to result.error))
            return true
        }
        
        if (result.delivered > 0) {
            player.sendMessage(Lang.prefixed("market.delivery.claimed", "amount" to result.delivered, "item" to prettyName(mat)))
        }
        
        if (result.remainingPending > 0) {
            player.sendMessage(Lang.get("market.delivery.still-pending", "count" to result.remainingPending))
            player.sendMessage(Lang.get("market.delivery.make-space"))
        }
        
        return true
    }
    
    private fun handleDeliveryClaimAll(player: Player, deliveryMgr: org.lokixcz.theendex.delivery.DeliveryManager): Boolean {
        val result = deliveryMgr.claimAll(player)
        
        if (result.error != null) {
            player.sendMessage(Lang.get("errors.generic", "error" to result.error))
            return true
        }
        
        if (result.delivered.isEmpty()) {
            player.sendMessage(Lang.get("market.delivery.nothing-claimed"))
            return true
        }
        
        val totalClaimed = result.delivered.values.sum()
        player.sendMessage(Lang.prefixed("market.delivery.claimed-total", "count" to totalClaimed))
        
        result.delivered.entries.sortedByDescending { it.value }.take(5).forEach { (material, count) ->
            player.sendMessage(Lang.colorize(Lang.get("market-holdings-display.delivery-item", "item" to prettyName(material), "count" to count.toString())))
        }
        
        if (result.delivered.size > 5) {
            player.sendMessage(Lang.get("market.delivery.and-more", "count" to (result.delivered.size - 5)))
        }
        
        if (result.totalRemaining > 0) {
            player.sendMessage(Lang.get("market.delivery.still-pending", "count" to result.totalRemaining))
            player.sendMessage(Lang.get("market.delivery.make-space-claimall"))
        }
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // VIRTUAL HOLDINGS SYSTEM
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun handleHoldings(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        val db = plugin.marketManager.sqliteStore()
        if (db == null || !plugin.config.getBoolean("holdings.enabled", true)) {
            sender.sendMessage(Lang.get("market.holdings.not-enabled"))
            return true
        }
        
        val holdings = db.listHoldings(sender.uniqueId.toString())
        
        if (holdings.isEmpty()) {
            sender.sendMessage(Lang.prefixed("market.holdings.empty"))
            sender.sendMessage(Lang.get("market.holdings.empty-hint"))
            return true
        }
        
        val totalItems = holdings.values.sumOf { it.first }
        val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
        
        sender.sendMessage(Lang.get("market.gui.divider"))
        sender.sendMessage(Lang.prefixed("market.holdings.header", "count" to totalItems, "max" to maxHoldings))
        sender.sendMessage(Lang.get("market.gui.divider"))
        
        var totalValue = 0.0
        var totalCost = 0.0
        
        holdings.entries.sortedByDescending { it.value.first }.take(15).forEach { (material, pair) ->
            val (qty, avgCost) = pair
            val marketItem = plugin.marketManager.get(material)
            val currentPrice = marketItem?.currentPrice ?: 0.0
            val value = currentPrice * qty
            val cost = avgCost * qty
            totalValue += value
            totalCost += cost
            
            val pnl = value - cost
            val pnlColor = when {
                pnl > 0.01 -> ChatColor.GREEN
                pnl < -0.01 -> ChatColor.RED
                else -> ChatColor.GRAY
            }
            val pnlSign = if (pnl >= 0) "+" else ""
            
            sender.sendMessage(Lang.colorize(Lang.get("market-holdings-display.item-detail", "item" to prettyName(material), "qty" to qty.toString(), "price" to format(currentPrice), "pnl_color" to pnlColor.toString(), "pnl_sign" to pnlSign, "pnl" to format(pnl))))
        }
        
        if (holdings.size > 15) {
            sender.sendMessage(Lang.get("market.holdings.and-more", "count" to (holdings.size - 15)))
        }
        
        val totalPnl = totalValue - totalCost
        val pnlColor = when {
            totalPnl > 0.01 -> ChatColor.GREEN
            totalPnl < -0.01 -> ChatColor.RED
            else -> ChatColor.GRAY
        }
        val pnlSign = if (totalPnl >= 0) "+" else ""
        
        sender.sendMessage(Lang.get("market.gui.divider"))
        sender.sendMessage(Lang.colorize(Lang.get("market-holdings-display.total-value", "label" to Lang.get("market.holdings.total-value"), "value" to format(totalValue), "pnl_color" to pnlColor.toString(), "pnl_sign" to pnlSign, "pnl" to format(totalPnl))))
        sender.sendMessage(Lang.get("market.holdings.withdraw-hint"))
        sender.sendMessage(Lang.get("market.holdings.gui-hint"))
        
        return true
    }
    
    private fun handleWithdraw(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.get("general.player-only"))
            return true
        }
        
        val db = plugin.marketManager.sqliteStore()
        if (db == null || !plugin.config.getBoolean("holdings.enabled", true)) {
            sender.sendMessage(Lang.get("market.holdings.not-enabled"))
            return true
        }
        
        // /market withdraw (no args) - show help or withdraw all
        if (args.size == 1) {
            sender.sendMessage(Lang.prefixed("market.withdraw.header"))
            sender.sendMessage(Lang.get("market.withdraw.usage-material"))
            sender.sendMessage(Lang.get("market.withdraw.usage-amount"))
            sender.sendMessage(Lang.get("market.withdraw.usage-all"))
            sender.sendMessage(Lang.get("market.withdraw.see-holdings"))
            return true
        }
        
        // /market withdraw all
        if (args[1].equals("all", ignoreCase = true)) {
            return handleWithdrawAll(sender, db)
        }
        
        // /market withdraw <material> [amount]
        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }
        
        val holding = db.getHolding(sender.uniqueId.toString(), mat)
        if (holding == null || holding.first <= 0) {
            sender.sendMessage(Lang.get("market.holdings.none-of-item", "item" to prettyName(mat)))
            return true
        }
        
        val (available, _) = holding
        val requestedAmount = if (args.size >= 3) {
            args[2].toIntOrNull()?.takeIf { it > 0 } ?: run {
                sender.sendMessage(Lang.get("general.invalid-amount"))
                return true
            }
        } else {
            available // Withdraw all by default
        }
        
        val toWithdraw = minOf(requestedAmount, available)
        val capacity = calculateInventoryCapacity(sender, mat)
        
        if (capacity <= 0) {
            sender.sendMessage(Lang.get("market.withdraw.inventory-full"))
            return true
        }
        
        val actualWithdraw = minOf(toWithdraw, capacity)
        
        // Remove from holdings
        val removed = db.removeFromHoldings(sender.uniqueId.toString(), mat, actualWithdraw)
        if (removed <= 0) {
            sender.sendMessage(Lang.get("market.withdraw.failed"))
            return true
        }
        
        // Give items to player
        var remaining = removed
        while (remaining > 0) {
            val toGive = minOf(remaining, mat.maxStackSize)
            val stack = ItemStack(mat, toGive)
            val leftovers = sender.inventory.addItem(stack)
            if (leftovers.isNotEmpty()) {
                // Should not happen since we checked capacity, but safety fallback
                leftovers.values.forEach { sender.world.dropItemNaturally(sender.location, it) }
            }
            remaining -= toGive
        }
        
        sender.sendMessage(Lang.prefixed("market.withdraw.success", "amount" to removed, "item" to prettyName(mat)))
        
        val newHolding = db.getHolding(sender.uniqueId.toString(), mat)
        val stillHave = newHolding?.first ?: 0
        if (stillHave > 0) {
            sender.sendMessage(Lang.get("market.withdraw.still-holding", "count" to stillHave, "item" to prettyName(mat)))
        }
        
        return true
    }
    
    private fun handleWithdrawAll(player: Player, db: org.lokixcz.theendex.market.SqliteStore): Boolean {
        val holdings = db.listHoldings(player.uniqueId.toString())
        
        if (holdings.isEmpty()) {
            player.sendMessage(Lang.get("market.withdraw.nothing"))
            return true
        }
        
        var totalWithdrawn = 0
        val withdrawn = mutableMapOf<Material, Int>()
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
                // Give items
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
                withdrawn[mat] = removed
                
                // Track remaining
                val stillHave = available - removed
                if (stillHave > 0) totalRemaining += stillHave
            } else {
                totalRemaining += available
            }
        }
        
        if (totalWithdrawn == 0) {
            player.sendMessage(Lang.get("market.withdraw.none-withdrawn"))
            return true
        }
        
        player.sendMessage(Lang.prefixed("market.withdraw.total-success", "count" to totalWithdrawn))
        
        withdrawn.entries.sortedByDescending { it.value }.take(5).forEach { (mat, count) ->
            player.sendMessage(Lang.colorize(Lang.get("market-holdings-display.delivery-item", "item" to prettyName(mat), "count" to count.toString())))
        }
        
        if (withdrawn.size > 5) {
            player.sendMessage(Lang.get("market.withdraw.and-more", "count" to (withdrawn.size - 5)))
        }
        
        if (totalRemaining > 0) {
            player.sendMessage(Lang.get("market.withdraw.still-remaining", "count" to totalRemaining))
            player.sendMessage(Lang.get("market.withdraw.make-space"))
        }
        
        return true
    }

    // ==================== ADMIN ITEM MANAGEMENT COMMANDS ====================

    private fun handleAddItem(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("admin.add.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val basePrice = args[2].toDoubleOrNull()?.takeIf { it > 0 }
        if (basePrice == null) {
            sender.sendMessage(Lang.get("admin.invalid-price", "price" to args[2]))
            return true
        }

        val minPrice = if (args.size >= 4) args[3].toDoubleOrNull()?.takeIf { it >= 0 } ?: (basePrice * 0.1) else (basePrice * 0.1)
        val maxPrice = if (args.size >= 5) args[4].toDoubleOrNull()?.takeIf { it > minPrice } ?: (basePrice * 10.0) else (basePrice * 10.0)

        // Check if already exists
        val existing = plugin.itemsConfigManager.get(mat)
        if (existing != null) {
            sender.sendMessage(Lang.get("admin.add.already-exists", "item" to mat.name))
            return true
        }

        // Add to items.yml
        plugin.itemsConfigManager.addItem(mat, basePrice, minPrice, maxPrice)
        plugin.itemsConfigManager.save()

        // Sync to market
        val db = plugin.marketManager.sqliteStore()
        plugin.itemsConfigManager.syncToMarketManager(plugin.marketManager, db)

        sender.sendMessage(Lang.prefixed("admin.add.success", "item" to prettyName(mat)))
        sender.sendMessage(Lang.get("admin.add.details", "base" to format(basePrice), "min" to format(minPrice), "max" to format(maxPrice)))
        return true
    }

    private fun handleRemoveItem(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Lang.get("admin.remove.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.remove.not-found", "item" to mat.name))
            return true
        }

        // Remove from items.yml
        plugin.itemsConfigManager.remove(mat)
        plugin.itemsConfigManager.save()

        // Note: Item will remain in market.db until server restart or manual removal
        sender.sendMessage(Lang.prefixed("admin.remove.success", "item" to prettyName(mat)))
        sender.sendMessage(Lang.get("admin.remove.reload-hint"))
        return true
    }

    private fun handleSetBase(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("admin.setbase.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val price = args[2].toDoubleOrNull()?.takeIf { it > 0 }
        if (price == null) {
            sender.sendMessage(Lang.get("admin.invalid-price", "price" to args[2]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.not-in-config", "item" to mat.name))
            return true
        }

        // Update items.yml
        plugin.itemsConfigManager.setBasePrice(mat, price)
        plugin.itemsConfigManager.save()

        // Update market manager
        val marketItem = plugin.marketManager.get(mat)
        if (marketItem != null) {
            marketItem.basePrice = price
            marketItem.currentPrice = price.coerceIn(existing.minPrice, existing.maxPrice)
        }

        sender.sendMessage(Lang.prefixed("admin.setbase.success", "item" to prettyName(mat), "price" to format(price)))
        return true
    }

    private fun handleSetMin(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("admin.setmin.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val price = args[2].toDoubleOrNull()?.takeIf { it >= 0 }
        if (price == null) {
            sender.sendMessage(Lang.get("admin.invalid-price", "price" to args[2]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.not-in-config", "item" to mat.name))
            return true
        }

        // Update items.yml
        plugin.itemsConfigManager.setMinPrice(mat, price)
        plugin.itemsConfigManager.save()

        // Update market manager
        val marketItem = plugin.marketManager.get(mat)
        if (marketItem != null) {
            marketItem.minPrice = price
            // Clamp current price to new range
            marketItem.currentPrice = marketItem.currentPrice.coerceIn(price, existing.maxPrice)
        }

        sender.sendMessage(Lang.prefixed("admin.setmin.success", "item" to prettyName(mat), "price" to format(price)))
        return true
    }

    private fun handleSetMax(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("admin.setmax.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val price = args[2].toDoubleOrNull()?.takeIf { it > 0 }
        if (price == null) {
            sender.sendMessage(Lang.get("admin.invalid-price", "price" to args[2]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.not-in-config", "item" to mat.name))
            return true
        }

        if (price <= existing.minPrice) {
            sender.sendMessage(Lang.get("admin.setmax.must-be-greater", "min" to format(existing.minPrice)))
            return true
        }

        // Update items.yml
        plugin.itemsConfigManager.setMaxPrice(mat, price)
        plugin.itemsConfigManager.save()

        // Update market manager
        val marketItem = plugin.marketManager.get(mat)
        if (marketItem != null) {
            marketItem.maxPrice = price
            // Clamp current price to new range
            marketItem.currentPrice = marketItem.currentPrice.coerceIn(existing.minPrice, price)
        }

        sender.sendMessage(Lang.prefixed("admin.setmax.success", "item" to prettyName(mat), "price" to format(price)))
        return true
    }

    private fun handleSetPrice(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Lang.get("admin.setprice.usage"))
            sender.sendMessage(Lang.get("admin.setprice.note"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val price = args[2].toDoubleOrNull()?.takeIf { it > 0 }
        if (price == null) {
            sender.sendMessage(Lang.get("admin.invalid-price", "price" to args[2]))
            return true
        }

        val marketItem = plugin.marketManager.get(mat)
        if (marketItem == null) {
            sender.sendMessage(Lang.get("market.buy.item-not-tracked", "item" to mat.name))
            return true
        }

        // Set current price (temporary, will drift back based on supply/demand)
        val clamped = price.coerceIn(marketItem.minPrice, marketItem.maxPrice)
        marketItem.currentPrice = clamped

        sender.sendMessage(Lang.prefixed("admin.setprice.success", "item" to prettyName(mat), "price" to format(clamped)))
        if (clamped != price) {
            sender.sendMessage(Lang.get("admin.setprice.clamped", "min" to format(marketItem.minPrice), "max" to format(marketItem.maxPrice)))
        }
        sender.sendMessage(Lang.get("admin.setprice.temporary"))
        return true
    }

    private fun handleEnableItem(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Lang.get("admin.enable.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.not-in-config", "item" to mat.name))
            return true
        }

        plugin.itemsConfigManager.enable(mat)
        plugin.itemsConfigManager.save()
        val db = plugin.marketManager.sqliteStore()
        plugin.itemsConfigManager.syncToMarketManager(plugin.marketManager, db)

        sender.sendMessage(Lang.prefixed("admin.enable.success", "item" to prettyName(mat)))
        return true
    }

    private fun handleDisableItem(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Lang.get("admin.disable.usage"))
            return true
        }

        val mat = Material.matchMaterial(args[1].uppercase())
        if (mat == null) {
            sender.sendMessage(Lang.get("general.invalid-item", "item" to args[1]))
            return true
        }

        val existing = plugin.itemsConfigManager.get(mat)
        if (existing == null) {
            sender.sendMessage(Lang.get("admin.not-in-config", "item" to mat.name))
            return true
        }

        plugin.itemsConfigManager.disable(mat)
        plugin.itemsConfigManager.save()

        sender.sendMessage(Lang.prefixed("admin.disable.success", "item" to prettyName(mat)))
        sender.sendMessage(Lang.get("admin.disable.reload-hint"))
        return true
    }

    private fun handleListItems(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("theendex.admin")) {
            sender.sendMessage(Lang.get("general.no-permission"))
            return true
        }

        val items = plugin.itemsConfigManager.all()
        if (items.isEmpty()) {
            sender.sendMessage(Lang.get("admin.items.empty"))
            return true
        }

        val page = if (args.size >= 2) args[1].toIntOrNull()?.coerceAtLeast(1) ?: 1 else 1
        val perPage = 10
        val totalPages = (items.size + perPage - 1) / perPage
        val startIndex = (page - 1) * perPage
        val endIndex = minOf(startIndex + perPage, items.size)

        val sortedItems = items.sortedBy { it.material.name }
        val pageItems = sortedItems.subList(startIndex.coerceIn(0, sortedItems.size), endIndex.coerceIn(0, sortedItems.size))

        sender.sendMessage(Lang.prefixed("admin.items.header", "page" to page, "total" to totalPages))
        pageItems.forEach { entry ->
            val status = if (entry.enabled) "${ChatColor.GREEN}✓" else "${ChatColor.RED}✗"
            sender.sendMessage("$status ${ChatColor.AQUA}${prettyName(entry.material)} ${ChatColor.GRAY}B:${format(entry.basePrice)} Min:${format(entry.minPrice)} Max:${format(entry.maxPrice)}")
        }
        
        if (totalPages > 1) {
            sender.sendMessage(Lang.get("admin.items.page-hint"))
        }
        return true
    }
}