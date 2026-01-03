package org.lokixcz.theendex.market

import org.bukkit.Material
import java.time.Instant

data class PricePoint(
    val time: Instant,
    val price: Double
)

data class MarketItem(
    val material: Material,
    var basePrice: Double,
    var minPrice: Double,
    var maxPrice: Double,
    var currentPrice: Double,
    var demand: Double = 0.0,
    var supply: Double = 0.0,
    var lastDemand: Double = 0.0,
    var lastSupply: Double = 0.0,
    val history: ArrayDeque<PricePoint> = ArrayDeque()
)
