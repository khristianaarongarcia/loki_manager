package org.lokixcz.theendex.events

import java.time.Instant

data class MarketEvent(
    val name: String,
    val affected: String, // material name or "*"; ignored if affectedCategory is set
    val affectedCategory: String? = null, // e.g., ORES, FARMING, MOB_DROPS, BLOCKS
    val multiplier: Double,
    val weight: Double? = null, // optional weighting factor for 2.0 stacking (0..1), null => defaults from config
    val durationMinutes: Long,
    val broadcast: Boolean = true,
    val startMessage: String? = null,
    val endMessage: String? = null
)

data class ActiveEvent(
    val event: MarketEvent,
    val startedAt: Instant,
    val endsAt: Instant
)

