package org.lokixcz.theendex.api.events

import org.bukkit.Material
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** Fired when The Endex computes a new price for a material. */
class PriceUpdateEvent(val material: Material, var oldPrice: Double, var newPrice: Double) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

/** Fired immediately before a buy operation is finalized. */
class PreBuyEvent(val material: Material, var amount: Int, var unitPrice: Double) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}

/** Fired immediately before a sell operation is finalized. */
class PreSellEvent(val material: Material, var amount: Int, var unitPrice: Double) : Event(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}
