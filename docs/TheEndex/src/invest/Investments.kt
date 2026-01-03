package org.lokixcz.theendex.invest

import java.time.Instant
import java.util.UUID

data class Investment(
    val id: String,
    val owner: UUID,
    val material: String,
    val principal: Double,
    val aprPercent: Double,
    val createdAt: Instant,
    var lastAccruedAt: Instant
)

data class InvestmentSummary(
    val id: String,
    val material: String,
    val principal: Double,
    val accrued: Double
)
