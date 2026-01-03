package org.lokixcz.theendex.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Utility for handling item names in a way that respects the player's Minecraft client language.
 * 
 * When using translatable components, the Minecraft client automatically translates the item name
 * based on the player's selected language in their Minecraft settings.
 * 
 * For example, a player with Chinese language will see "钻石" instead of "Diamond".
 */
object ItemNames {
    
    /**
     * Get a translatable component for a material's name.
     * This will be translated by the Minecraft client to the player's language.
     * 
     * @param material The material to get the name for
     * @param color Optional color for the name (defaults to AQUA)
     * @return A translatable Component that renders in the client's language
     */
    fun translatable(material: Material, color: NamedTextColor = NamedTextColor.AQUA): Component {
        return try {
            // Paper 1.19.4+ provides translationKey() directly on Material
            val key = material.translationKey()
            Component.translatable(key)
                .color(color)
                .decoration(TextDecoration.ITALIC, false)
        } catch (e: Exception) {
            // Fallback: construct the translation key manually
            // Format: block.minecraft.material_name or item.minecraft.material_name
            val key = if (material.isBlock) {
                "block.minecraft.${material.name.lowercase()}"
            } else {
                "item.minecraft.${material.name.lowercase()}"
            }
            Component.translatable(key)
                .color(color)
                .decoration(TextDecoration.ITALIC, false)
        }
    }
    
    /**
     * Get a translatable component for an ItemStack's name.
     * If the item has a custom display name, it will be used instead.
     * 
     * @param itemStack The item stack to get the name for
     * @param color Optional color for the name (defaults to AQUA)
     * @return A Component representing the item's name
     */
    fun translatable(itemStack: ItemStack, color: NamedTextColor = NamedTextColor.AQUA): Component {
        val meta = itemStack.itemMeta
        return if (meta?.hasDisplayName() == true) {
            // Use the custom display name if set
            meta.displayName() ?: translatable(itemStack.type, color)
        } else {
            translatable(itemStack.type, color)
        }
    }
    
    /**
     * Set the display name of an ItemStack using a translatable component.
     * This makes the item name appear in the player's Minecraft client language.
     * 
     * @param itemStack The item to modify
     * @param color Optional color for the name (defaults to AQUA)
     * @return The modified ItemStack
     */
    fun setTranslatableName(itemStack: ItemStack, color: NamedTextColor = NamedTextColor.AQUA): ItemStack {
        val meta = itemStack.itemMeta ?: return itemStack
        meta.displayName(translatable(itemStack.type, color))
        itemStack.itemMeta = meta
        return itemStack
    }
    
    /**
     * Create an ItemStack with a translatable name.
     * 
     * @param material The material for the item
     * @param color Optional color for the name (defaults to AQUA)
     * @return An ItemStack with a translatable display name
     */
    fun createWithTranslatableName(material: Material, color: NamedTextColor = NamedTextColor.AQUA): ItemStack {
        val item = ItemStack(material)
        return setTranslatableName(item, color)
    }
    
    /**
     * Get the "pretty" English name for a material (fallback for logging/console).
     * This is NOT for display to players - use translatable() for that.
     * 
     * @param material The material
     * @return A formatted string like "Diamond Sword"
     */
    fun prettyName(material: Material): String = material.name.lowercase()
        .split('_')
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
}
