package org.lokixcz.theendex.commands

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class MarketTabCompleter : TabCompleter {
    private val sub = listOf("buy", "sell", "price", "top", "event", "holdings", "withdraw", "delivery", "invest", "shop", "stock", "help")
    private val adminSub = listOf("add", "remove", "setbase", "setmin", "setmax", "setprice", "enable", "disable", "items", "editor")
    private val amounts = listOf("1", "8", "16", "32", "64", "128", "256")
    private val priceAmounts = listOf("1", "5", "10", "25", "50", "100", "500", "1000")
    private val deliverySub = listOf("list", "claim", "claim-all", "gui")
    private val investSub = listOf("buy", "list", "redeem-all")
    private val withdrawSub = listOf("all")

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        // Determine available subcommands based on permissions
        val availableSub = if (sender.hasPermission("theendex.admin")) sub + adminSub else sub
        
        return when (args.size) {
            1 -> availableSub.filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            2 -> when (args[0].lowercase()) {
                "buy", "sell", "price" -> materialCompletions(args[1])
                "withdraw" -> {
                    // Show "all" plus materials from holdings
                    val mats = holdingsMaterialCompletions(sender, args[1])
                    val allOption = if ("all".startsWith(args[1], ignoreCase = true)) mutableListOf("all") else mutableListOf()
                    (allOption + mats).toMutableList()
                }
                "event" -> eventCompletions(sender, args.drop(1).joinToString(" "))
                "delivery", "deliveries" -> deliverySub.filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                "invest" -> investSub.filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                "shop" -> shopCompletions(args[1])
                // Admin item management
                "add", "remove", "setbase", "setmin", "setmax", "setprice", "enable", "disable" -> {
                    if (sender.hasPermission("theendex.admin")) materialCompletions(args[1]) else mutableListOf()
                }
                "items" -> if (sender.hasPermission("theendex.admin")) listOf("1", "2", "3", "4", "5").filter { it.startsWith(args[1]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
            3 -> when (args[0].lowercase()) {
                "buy", "sell" -> amounts.filter { it.startsWith(args[2]) }.toMutableList()
                "withdraw" -> if (args[1].equals("all", ignoreCase = true)) mutableListOf() else amounts.filter { it.startsWith(args[2]) }.toMutableList()
                "invest" -> if (args[1].equals("buy", ignoreCase = true)) materialCompletions(args[2]) else mutableListOf()
                "delivery", "deliveries" -> if (args[1].equals("claim", ignoreCase = true)) deliveryMaterialCompletions(sender, args[2]) else mutableListOf()
                // Admin: price completions for setbase, setmin, setmax, setprice, and base price for add
                "setbase", "setmin", "setmax", "setprice", "add" -> {
                    if (sender.hasPermission("theendex.admin")) priceAmounts.filter { it.startsWith(args[2]) }.toMutableList() else mutableListOf()
                }
                else -> mutableListOf()
            }
            4 -> when (args[0].lowercase()) {
                "invest" -> if (args[1].equals("buy", ignoreCase = true)) amounts.filter { it.startsWith(args[3]) }.toMutableList() else mutableListOf()
                "delivery", "deliveries" -> if (args[1].equals("claim", ignoreCase = true)) amounts.filter { it.startsWith(args[3]) }.toMutableList() else mutableListOf()
                // Admin: min price for add command
                "add" -> if (sender.hasPermission("theendex.admin")) priceAmounts.filter { it.startsWith(args[3]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
            5 -> when (args[0].lowercase()) {
                // Admin: max price for add command
                "add" -> if (sender.hasPermission("theendex.admin")) priceAmounts.filter { it.startsWith(args[4]) }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }
            else -> mutableListOf()
        }
    }

    private fun materialCompletions(prefix: String): MutableList<String> {
        val p = prefix.uppercase()
        return Material.entries
            .asSequence()
            .filter { !it.isAir && !it.name.startsWith("LEGACY_") }
            .map { it.name }
            .filter { it.startsWith(p) }
            .take(50)
            .toMutableList()
    }

    private fun holdingsMaterialCompletions(sender: CommandSender, prefix: String): MutableList<String> {
        if (sender !is Player) return mutableListOf()
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("TheEndex") as? org.lokixcz.theendex.Endex
            ?: return mutableListOf()
        val db = plugin.marketManager.sqliteStore() ?: return mutableListOf()
        val holdings = db.listHoldings(sender.uniqueId.toString())
        val p = prefix.uppercase()
        return holdings.keys
            .map { it.name }
            .filter { it.startsWith(p) }
            .take(50)
            .toMutableList()
    }

    private fun deliveryMaterialCompletions(sender: CommandSender, prefix: String): MutableList<String> {
        if (sender !is Player) return mutableListOf()
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("TheEndex") as? org.lokixcz.theendex.Endex
            ?: return mutableListOf()
        val deliveryMgr = plugin.getDeliveryManager() ?: return mutableListOf()
        val pending = deliveryMgr.listPending(sender.uniqueId)
        val p = prefix.uppercase()
        return pending.keys
            .map { it.name }
            .filter { it.startsWith(p) }
            .take(50)
            .toMutableList()
    }

    private fun eventCompletions(sender: CommandSender, prefix: String): MutableList<String> {
        if (!sender.hasPermission("theendex.admin")) return mutableListOf()
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("TheEndex") as? org.lokixcz.theendex.Endex
        val names = plugin?.eventManager?.listEvents()?.map { it.name } ?: emptyList()
        return names.filter { it.startsWith(prefix, ignoreCase = true) }.take(50).toMutableList()
    }
    
    private fun shopCompletions(prefix: String): MutableList<String> {
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("TheEndex") as? org.lokixcz.theendex.Endex
            ?: return mutableListOf()
        val manager = plugin.customShopManager ?: return mutableListOf()
        return manager.all().keys
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(50)
            .toMutableList()
    }
}
