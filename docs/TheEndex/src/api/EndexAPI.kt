package org.lokixcz.theendex.api

import org.bukkit.Material
import org.lokixcz.theendex.api.model.ActiveMarketEvent
import org.lokixcz.theendex.api.model.PriceSample

/**
 * Public API for The Endex, available via Bukkit ServicesManager.
 * Obtain with:
 * val api = server.servicesManager.load(EndexAPI::class.java)
 */
interface EndexAPI {
    // Market data
    fun listMaterials(): Set<Material>
    fun getBasePrice(material: Material): Double?
    fun getCurrentPrice(material: Material): Double?
    fun getEffectivePrice(material: Material): Double? // applies active event multipliers
    fun getPriceHistory(material: Material, limit: Int = 64): List<PriceSample>

    // Demand/supply adjustment hooks
    fun addDemand(material: Material, amount: Double)
    fun addSupply(material: Material, amount: Double)

    // Blacklist and events
    fun isBlacklisted(material: Material): Boolean
    fun multiplierFor(material: Material): Double
    fun getActiveEvents(): List<ActiveMarketEvent>

    // Resource tracking totals since startup (and last persisted snapshot)
    fun getTrackedTotals(): Map<Material, Long>
}
