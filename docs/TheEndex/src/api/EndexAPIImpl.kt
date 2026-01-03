package org.lokixcz.theendex.api

import org.bukkit.Material
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.api.model.ActiveMarketEvent
import org.lokixcz.theendex.api.model.PriceSample

class EndexAPIImpl(private val plugin: Endex) : EndexAPI {
    override fun listMaterials(): Set<Material> = plugin.marketManager.allItems().map { it.material }.toSet()

    override fun getBasePrice(material: Material): Double? = plugin.marketManager.get(material)?.basePrice

    override fun getCurrentPrice(material: Material): Double? = plugin.marketManager.get(material)?.currentPrice

    override fun getEffectivePrice(material: Material): Double? = plugin.marketManager.get(material)?.let { item ->
        val mul = plugin.eventManager.multiplierFor(material)
        item.currentPrice * mul
    }

    override fun getPriceHistory(material: Material, limit: Int): List<PriceSample> =
        plugin.marketManager.get(material)?.history?.takeLast(limit)?.map { PriceSample(it.time, it.price) } ?: emptyList()

    override fun addDemand(material: Material, amount: Double) {
        plugin.marketManager.addDemand(material, amount)
    }

    override fun addSupply(material: Material, amount: Double) {
        plugin.marketManager.addSupply(material, amount)
    }

    override fun isBlacklisted(material: Material): Boolean {
        val blacklist = plugin.config.getStringList("blacklist-items").map { it.uppercase() }.toSet()
        return material.name in blacklist
    }

    override fun multiplierFor(material: Material): Double = plugin.eventManager.multiplierFor(material)

    override fun getActiveEvents(): List<ActiveMarketEvent> = plugin.eventManager.listActive().map {
        ActiveMarketEvent(
            name = it.event.name,
            multiplier = it.event.multiplier,
            affectedItem = it.event.affected.takeIf { s -> s != "*" },
            affectedCategory = it.event.affectedCategory,
            endsAt = it.endsAt
        )
    }

    override fun getTrackedTotals(): Map<Material, Long> = plugin.getResourceTracker()?.snapshot() ?: emptyMap()
}
