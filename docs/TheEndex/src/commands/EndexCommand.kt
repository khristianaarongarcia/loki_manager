package org.lokixcz.theendex.commands

import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.lokixcz.theendex.Endex
import org.lokixcz.theendex.gui.MarketGUI
import org.lokixcz.theendex.lang.Lang

class EndexCommand(private val plugin: Endex) : CommandExecutor {
    
    /**
     * Send a clickable URL link to a player using Spigot's BungeeCord Chat API.
     * This works on Spigot, Paper, and hybrid servers like Arclight.
     * Returns true if successful, false on error.
     */
    private fun sendClickableLink(player: Player, url: String): Boolean {
        return try {
            val linkText = TextComponent("${ChatColor.AQUA}${ChatColor.UNDERLINE}Click here to open The Endex Trading Interface")
            linkText.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
            linkText.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("${ChatColor.GRAY}Open trading interface in your browser"))
            
            player.spigot().sendMessage(linkText)
            true
        } catch (_: Exception) {
            false // BungeeCord chat API not available
        }
    }
    
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(Lang.colorize(Lang.get("endex.plugin-info")))
            sender.sendMessage(Lang.colorize(Lang.get("endex.usage-hint")))
            return true
        }
        
        when (args[0].lowercase()) {
            "help" -> {
                sender.sendMessage(Lang.colorize(Lang.get("endex.help.header")))
                val base = listOf(
                    Lang.get("endex.help.base-info"),
                    Lang.get("endex.help.market"),
                    Lang.get("endex.help.price"),
                    Lang.get("endex.help.top"),
                    Lang.get("endex.help.buy"),
                    Lang.get("endex.help.sell"),
                    Lang.get("endex.help.invest")
                )
                base.forEach { sender.sendMessage(Lang.colorize(it)) }
                if (sender.hasPermission("theendex.admin")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.reload")))
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.shopedit")))
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.webui-export")))
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.webui-reload")))
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.event")))
                }
                if (sender.hasPermission("theendex.web")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.help.web")))
                }
                return true
            }
            
            "market" -> {
                if (sender is Player) {
                    // Check shop mode - CUSTOM opens category-based shop, DEFAULT opens market
                    val shopMode = plugin.config.getString("shop.mode", "DEFAULT")?.uppercase() ?: "DEFAULT"
                    if (shopMode == "CUSTOM" && plugin.customShopGUI != null) {
                        plugin.customShopGUI!!.openMainMenu(sender)
                    } else {
                        plugin.marketGUI.open(sender)
                    }
                } else {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.market.player-only")))
                }
                return true
            }
            
            "version" -> {
                val ver = plugin.description.version ?: "unknown"
                val useSqlite = plugin.config.getBoolean("storage.sqlite", false)
                val storage = if (useSqlite) "SQLite" else "YAML"
                sender.sendMessage(Lang.colorize(Lang.get("endex.version.info", "version" to ver, "storage" to storage)))
                sender.sendMessage(Lang.colorize(Lang.get("endex.version.help-hint")))
                return true
            }
            
            "reload" -> {
                if (!sender.hasPermission("theendex.admin")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.reload.no-permission")))
                    return true
                }
                plugin.reloadEndex(sender)
                return true
            }

            "webui" -> {
                if (!sender.hasPermission("theendex.admin")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.webui.no-permission")))
                    return true
                }
                val sub = args.getOrNull(1)?.lowercase()
                val webServer = plugin.getWebServer()
                if (webServer == null) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.webui.not-available")))
                    return true
                }
                when (sub) {
                    "export" -> {
                        webServer.forceExportDefaultUiOverwrite()
                        sender.sendMessage(Lang.colorize(Lang.get("endex.webui.export-success")))
                        return true
                    }
                    "reload" -> {
                        webServer.forceReloadCustomIndex()
                        sender.sendMessage(Lang.colorize(Lang.get("endex.webui.reload-success")))
                        return true
                    }
                    else -> {
                        sender.sendMessage(Lang.colorize(Lang.get("endex.webui.help-export")))
                        sender.sendMessage(Lang.colorize(Lang.get("endex.webui.help-reload")))
                        return true
                    }
                }
            }
            
            "track" -> {
                if (!sender.hasPermission("theendex.admin")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.track.no-permission")))
                    return true
                }
                val sub = args.getOrNull(1)?.lowercase()
                if (sub == "dump") {
                    try {
                        val rt = plugin.getResourceTracker()
                        if (rt == null) {
                            sender.sendMessage(Lang.colorize(Lang.get("endex.track.disabled")))
                        } else {
                            val top = rt.top(15)
                            sender.sendMessage(Lang.colorize(Lang.get("endex.track.header")))
                            if (top.isEmpty()) sender.sendMessage(Lang.colorize(Lang.get("endex.track.no-data")))
                            for ((mat, amt) in top) {
                                sender.sendMessage(Lang.colorize(Lang.get("endex.track.entry", "material" to mat.name, "amount" to amt.toString())))
                            }
                            sender.sendMessage(Lang.colorize(Lang.get("endex.track.footer")))
                        }
                    } catch (_: Throwable) {
                        sender.sendMessage(Lang.colorize(Lang.get("endex.track.unable-to-read")))
                    }
                    return true
                } else {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.track.usage")))
                    return true
                }
            }
            
            "web" -> {
                if (sender !is Player) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.web.player-only")))
                    return true
                }
                
                if (!sender.hasPermission("theendex.web")) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.web.no-permission")))
                    return true
                }
                
                val webServer = plugin.getWebServer()
                if (webServer == null) {
                    sender.sendMessage(Lang.colorize(Lang.get("endex.web.not-available")))
                    return true
                }
                
                val url = webServer.createSession(sender)
                
                // Send clickable link - try Paper/Adventure API first, fallback to Spigot-compatible method
                sender.sendMessage(Lang.colorize(Lang.get("endex.web.session-header")))
                
                if (sendClickableLink(sender, url)) {
                    // Successfully sent via Adventure API (Paper)
                } else {
                    // Fallback for Spigot/Arclight: send plain URL (player can copy-paste)
                    sender.sendMessage(Lang.colorize(Lang.get("endex.web.link-plain", "url" to url)))
                    sender.sendMessage(Lang.colorize(Lang.get("endex.web.link-hint")))
                }
                
                sender.sendMessage(Lang.colorize(Lang.get("endex.web.session-expires")))
                
                return true
            }
            
            "shopedit" -> {
                return handleShopEdit(sender)
            }
            
            else -> {
                // Try addon router
                val routed = plugin.getAddonCommandRouter()?.dispatch(sender, label, args)
                if (routed == true) return true
                sender.sendMessage(Lang.colorize(Lang.get("endex.unknown-subcommand")))
                return true
            }
        }
    }
    
    /**
     * Handle /endex shopedit command - opens the shop editor GUI.
     * Also handles /market editor, /shop editor via aliases.
     */
    private fun handleShopEdit(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Lang.colorize(Lang.get("endex.shopedit.player-only")))
            return true
        }
        
        if (!sender.hasPermission("endex.shop.editor")) {
            sender.sendMessage(Lang.colorize(Lang.get("endex.shopedit.no-permission")))
            return true
        }
        
        val editor = plugin.shopEditorGUI
        if (editor == null) {
            sender.sendMessage(Lang.colorize(Lang.get("endex.shopedit.not-available")))
            return true
        }
        
        editor.openShopManager(sender)
        return true
    }
}
