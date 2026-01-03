package org.lokixcz.theendex.util

import org.bukkit.ChatColor
import org.bukkit.plugin.Plugin

class EndexLogger(private val plugin: Plugin) {
    var verbose: Boolean = false

    fun info(msg: String) {
        val line = prefix(ChatColor.GREEN, msg)
        plugin.server.consoleSender.sendMessage(line)
        plugin.logger.info(stripColors(line))
    }
    fun warn(msg: String) {
        val line = prefix(ChatColor.YELLOW, msg)
        plugin.server.consoleSender.sendMessage(line)
        plugin.logger.warning(stripColors(line))
    }
    fun error(msg: String) {
        val line = prefix(ChatColor.RED, msg)
        plugin.server.consoleSender.sendMessage(line)
        plugin.logger.severe(stripColors(line))
    }
    fun debug(msg: String) {
        if (verbose) {
            val line = prefix(ChatColor.AQUA, "[DEBUG] $msg")
            plugin.server.consoleSender.sendMessage(line)
            plugin.logger.info(stripColors(line))
        }
    }

    fun console(msg: String) {
        // Send colored message to console as well
        val console = plugin.server.consoleSender
        console.sendMessage("§6[The Endex] §r$msg")
    }

    fun banner(lines: List<String>) {
        val console = plugin.server.consoleSender
        for (line in lines) {
            console.sendMessage(colorize(line))
        }
    }

    private fun prefix(color: ChatColor, msg: String) = "${color}[The Endex] ${ChatColor.RESET}$msg"
    private fun stripColors(s: String) = s.replace(Regex("§."), "")
    private fun colorize(s: String): String = s
        .replace("&0", "§0").replace("&1", "§1").replace("&2", "§2").replace("&3", "§3")
        .replace("&4", "§4").replace("&5", "§5").replace("&6", "§6").replace("&7", "§7")
        .replace("&8", "§8").replace("&9", "§9").replace("&a", "§a").replace("&b", "§b")
        .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e").replace("&f", "§f")
        .replace("&r", "§r").replace("&l", "§l").replace("&n", "§n").replace("&o", "§o")
        .replace("&m", "§m")
}
