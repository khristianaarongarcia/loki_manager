package org.lokixcz.theendex.api.model

import org.bukkit.Material
import java.time.Instant

data class PriceSample(val time: Instant, val price: Double)

data class ActiveMarketEvent(
    val name: String,
    val multiplier: Double,
    val affectedItem: String?,
    val affectedCategory: String?,
    val endsAt: Instant
)
