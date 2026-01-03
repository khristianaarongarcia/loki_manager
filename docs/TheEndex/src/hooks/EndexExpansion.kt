package org.lokixcz.theendex.hooks

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.market.MarketItem
import java.text.DecimalFormat
import java.util.UUID

/**
 * PlaceholderAPI expansion for The Endex.
 * 
 * Available placeholders:
 * 
 * == Market Item Placeholders ==
 * %endex_price_<MATERIAL>%          - Current price of an item (e.g., %endex_price_DIAMOND%)
 * %endex_price_formatted_<MATERIAL>% - Formatted price with currency symbol
 * %endex_change_<MATERIAL>%         - Price change percentage since last update
 * %endex_trend_<MATERIAL>%          - Trend arrow (↑, ↓, or →)
 * %endex_supply_<MATERIAL>%         - Current supply of an item
 * %endex_demand_<MATERIAL>%         - Current demand of an item
 * 
 * == Top Items Placeholders ==
 * %endex_top_price_<N>%             - Nth most expensive item name (1-10)
 * %endex_top_price_<N>_value%       - Nth most expensive item price
 * %endex_bottom_price_<N>%          - Nth cheapest item name (1-10)
 * %endex_bottom_price_<N>_value%    - Nth cheapest item price
 * %endex_top_gainer_<N>%            - Nth biggest price gainer name (1-10)
 * %endex_top_gainer_<N>_change%     - Nth biggest gainer change %
 * %endex_top_loser_<N>%             - Nth biggest price loser name (1-10)
 * %endex_top_loser_<N>_change%      - Nth biggest loser change %
 * 
 * == Player Holdings Placeholders ==
 * %endex_holdings_total%            - Player's total holdings value
 * %endex_holdings_count%            - Total number of items in holdings
 * %endex_holdings_top_<N>%          - Nth most valuable holding item name
 * %endex_holdings_top_<N>_value%    - Nth most valuable holding value
 * %endex_holdings_top_<N>_amount%   - Nth most valuable holding amount
 * 
 * == Top Holdings Leaderboard ==
 * %endex_top_holdings_<N>%          - Nth richest player by holdings (name)
 * %endex_top_holdings_<N>_value%    - Nth richest player holdings value
 * 
 * == Market Statistics ==
 * %endex_total_items%               - Total items in market
 * %endex_total_volume%              - Total market volume (sum of all prices)
 * %endex_average_price%             - Average item price
 * %endex_active_events%             - Number of active market events
 */
class EndexExpansion(private val plugin: Endex) : PlaceholderExpansion() {

    private val priceFormat = DecimalFormat("#,##0.00")
    private val percentFormat = DecimalFormat("+0.00%;-0.00%")
    private val wholeFormat = DecimalFormat("#,##0")

    override fun getIdentifier(): String = "endex"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val lower = params.lowercase()

        // === Market Item Placeholders ===
        if (lower.startsWith("price_formatted_")) {
            val material = params.substring(16).uppercase()
            val mat = Material.matchMaterial(material) ?: return "N/A"
            val item = plugin.marketManager.get(mat) ?: return "N/A"
            return "$${priceFormat.format(item.currentPrice)}"
        }

        if (lower.startsWith("price_")) {
            val material = params.substring(6).uppercase()
            val mat = Material.matchMaterial(material) ?: return "N/A"
            val item = plugin.marketManager.get(mat) ?: return "N/A"
            return priceFormat.format(item.currentPrice)
        }

        if (lower.startsWith("change_")) {
            val material = params.substring(7).uppercase()
            val mat = Material.matchMaterial(material) ?: return "N/A"
            val item = plugin.marketManager.get(mat) ?: return "N/A"
            val change = getChangePercent(item)
            return percentFormat.format(change)
        }

        if (lower.startsWith("trend_")) {
            val material = params.substring(6).uppercase()
            val mat = Material.matchMaterial(material) ?: return "→"
            val item = plugin.marketManager.get(mat) ?: return "→"
            val change = getChangePercent(item)
            return when {
                change > 0.001 -> "↑"
                change < -0.001 -> "↓"
                else -> "→"
            }
        }

        if (lower.startsWith("supply_")) {
            val material = params.substring(7).uppercase()
            val mat = Material.matchMaterial(material) ?: return "0"
            val item = plugin.marketManager.get(mat) ?: return "0"
            return wholeFormat.format(item.supply.toInt())
        }

        if (lower.startsWith("demand_")) {
            val material = params.substring(7).uppercase()
            val mat = Material.matchMaterial(material) ?: return "0"
            val item = plugin.marketManager.get(mat) ?: return "0"
            return wholeFormat.format(item.demand.toInt())
        }

        // === Top Price Placeholders ===
        if (lower.startsWith("top_price_") && !lower.contains("_value")) {
            val n = lower.substring(10).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = plugin.marketManager.allItems().sortedByDescending { it.currentPrice }
            return sorted.getOrNull(n - 1)?.let { formatMaterialName(it.material.name) } ?: "N/A"
        }

        if (lower.startsWith("top_price_") && lower.endsWith("_value")) {
            val n = lower.substring(10, lower.length - 6).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = plugin.marketManager.allItems().sortedByDescending { it.currentPrice }
            return sorted.getOrNull(n - 1)?.let { priceFormat.format(it.currentPrice) } ?: "0"
        }

        // === Bottom Price Placeholders ===
        if (lower.startsWith("bottom_price_") && !lower.contains("_value")) {
            val n = lower.substring(13).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = plugin.marketManager.allItems().sortedBy { it.currentPrice }
            return sorted.getOrNull(n - 1)?.let { formatMaterialName(it.material.name) } ?: "N/A"
        }

        if (lower.startsWith("bottom_price_") && lower.endsWith("_value")) {
            val n = lower.substring(13, lower.length - 6).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = plugin.marketManager.allItems().sortedBy { it.currentPrice }
            return sorted.getOrNull(n - 1)?.let { priceFormat.format(it.currentPrice) } ?: "0"
        }

        // === Top Gainer Placeholders ===
        if (lower.startsWith("top_gainer_") && !lower.contains("_change")) {
            val n = lower.substring(11).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = getItemsByChange().sortedByDescending { it.second }
            return sorted.getOrNull(n - 1)?.let { formatMaterialName(it.first.material.name) } ?: "N/A"
        }

        if (lower.startsWith("top_gainer_") && lower.endsWith("_change")) {
            val n = lower.substring(11, lower.length - 7).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = getItemsByChange().sortedByDescending { it.second }
            return sorted.getOrNull(n - 1)?.let { percentFormat.format(it.second) } ?: "0%"
        }

        // === Top Loser Placeholders ===
        if (lower.startsWith("top_loser_") && !lower.contains("_change")) {
            val n = lower.substring(10).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = getItemsByChange().sortedBy { it.second }
            return sorted.getOrNull(n - 1)?.let { formatMaterialName(it.first.material.name) } ?: "N/A"
        }

        if (lower.startsWith("top_loser_") && lower.endsWith("_change")) {
            val n = lower.substring(10, lower.length - 7).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val sorted = getItemsByChange().sortedBy { it.second }
            return sorted.getOrNull(n - 1)?.let { percentFormat.format(it.second) } ?: "0%"
        }

        // === Player Holdings Placeholders ===
        if (player != null) {
            if (lower == "holdings_total") {
                val holdings = getPlayerHoldings(player)
                val total = holdings.sumOf { it.second }
                return priceFormat.format(total)
            }

            if (lower == "holdings_count") {
                val holdings = getPlayerHoldings(player)
                val count = holdings.sumOf { it.first }
                return wholeFormat.format(count)
            }

            if (lower.startsWith("holdings_top_") && !lower.contains("_value") && !lower.contains("_amount")) {
                val n = lower.substring(13).toIntOrNull() ?: return null
                if (n < 1 || n > 10) return null
                val holdings = getPlayerHoldings(player).sortedByDescending { it.second }
                return holdings.getOrNull(n - 1)?.let { formatMaterialName(it.third) } ?: "N/A"
            }

            if (lower.startsWith("holdings_top_") && lower.endsWith("_value")) {
                val n = lower.substring(13, lower.length - 6).toIntOrNull() ?: return null
                if (n < 1 || n > 10) return null
                val holdings = getPlayerHoldings(player).sortedByDescending { it.second }
                return holdings.getOrNull(n - 1)?.let { priceFormat.format(it.second) } ?: "0"
            }

            if (lower.startsWith("holdings_top_") && lower.endsWith("_amount")) {
                val n = lower.substring(13, lower.length - 7).toIntOrNull() ?: return null
                if (n < 1 || n > 10) return null
                val holdings = getPlayerHoldings(player).sortedByDescending { it.second }
                return holdings.getOrNull(n - 1)?.let { wholeFormat.format(it.first) } ?: "0"
            }
        }

        // === Top Holdings Leaderboard ===
        if (lower.startsWith("top_holdings_") && !lower.contains("_value")) {
            val n = lower.substring(13).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val leaderboard = getHoldingsLeaderboard()
            return leaderboard.getOrNull(n - 1)?.first ?: "N/A"
        }

        if (lower.startsWith("top_holdings_") && lower.endsWith("_value")) {
            val n = lower.substring(13, lower.length - 6).toIntOrNull() ?: return null
            if (n < 1 || n > 10) return null
            val leaderboard = getHoldingsLeaderboard()
            return leaderboard.getOrNull(n - 1)?.let { priceFormat.format(it.second) } ?: "0"
        }

        // === Market Statistics ===
        when (lower) {
            "total_items" -> {
                return wholeFormat.format(plugin.marketManager.allItems().size)
            }
            "total_volume" -> {
                val total = plugin.marketManager.allItems().sumOf { it.currentPrice }
                return priceFormat.format(total)
            }
            "average_price" -> {
                val items = plugin.marketManager.allItems()
                if (items.isEmpty()) return "0"
                val avg = items.sumOf { it.currentPrice } / items.size
                return priceFormat.format(avg)
            }
            "active_events" -> {
                return wholeFormat.format(plugin.eventManager.listActive().size)
            }
        }

        return null
    }

    /**
     * Get price change percentage for an item.
     */
    private fun getChangePercent(item: MarketItem): Double {
        val history = item.history
        if (history.size < 2) return 0.0
        val prev = history.elementAt(history.size - 2).price
        return if (prev != 0.0) (item.currentPrice - prev) / prev else 0.0
    }

    /**
     * Get all items with their price change percentage.
     */
    private fun getItemsByChange(): List<Pair<MarketItem, Double>> {
        return plugin.marketManager.allItems().map { item ->
            item to getChangePercent(item)
        }
    }

    /**
     * Get player holdings as list of (amount, value, material).
     */
    private fun getPlayerHoldings(player: OfflinePlayer): List<Triple<Int, Double, String>> {
        val result = mutableListOf<Triple<Int, Double, String>>()
        try {
            val store = plugin.marketManager.sqliteStore() ?: return result
            val holdings = store.listHoldings(player.uniqueId.toString())
            for ((material, pair) in holdings) {
                val amount = pair.first
                val item = plugin.marketManager.get(material)
                val price = item?.currentPrice ?: 0.0
                val value = price * amount
                result.add(Triple(amount, value, material.name))
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Get holdings leaderboard (top players by total holdings value).
     */
    private fun getHoldingsLeaderboard(): List<Pair<String, Double>> {
        val result = mutableListOf<Pair<String, Double>>()
        try {
            val store = plugin.marketManager.sqliteStore() ?: return result
            val allHoldings = store.getAllPlayersHoldings()
            
            for ((uuidStr, holdings) in allHoldings) {
                var totalValue = 0.0
                for ((materialName, amount) in holdings) {
                    val mat = Material.matchMaterial(materialName) ?: continue
                    val item = plugin.marketManager.get(mat)
                    val price = item?.currentPrice ?: 0.0
                    totalValue += price * amount
                }
                val uuid = try { UUID.fromString(uuidStr) } catch (_: Exception) { null }
                val name = uuid?.let { plugin.server.getOfflinePlayer(it).name } ?: uuidStr.take(8)
                result.add(name to totalValue)
            }
            
            result.sortByDescending { it.second }
        } catch (_: Exception) {}
        return result.take(10)
    }

    /**
     * Format material name to be more readable.
     */
    private fun formatMaterialName(material: String): String {
        return material.lowercase()
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }
}
