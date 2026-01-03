package org.lokixcz.theendex.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.lokixcz.theendex.Endex

/**
 * A command that acts as an alias for another command.
 * Supports {args} placeholder for passing arguments through.
 */
class AliasCommand(
    name: String,
    private val targetCommand: String,
    private val plugin: Endex,
    customPermission: String? = null,
    customDescription: String? = null
) : Command(name) {
    
    init {
        description = customDescription ?: "Alias for /$targetCommand"
        usage = "/$name"
        permission = customPermission // Use custom permission if provided, otherwise inherit from target
    }
    
    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        val argsString = args.joinToString(" ")
        
        // Replace {args} placeholder with actual arguments
        val finalCommand = if (targetCommand.contains("{args}")) {
            targetCommand.replace("{args}", argsString)
        } else if (args.isNotEmpty()) {
            // If no placeholder but args provided, append them
            "$targetCommand $argsString"
        } else {
            targetCommand
        }
        
        // Execute the command as the sender
        Bukkit.dispatchCommand(sender, finalCommand.trim())
        return true
    }
    
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
        // Extract the base command for tab completion
        val baseCommand = targetCommand
            .replace("{args}", "")
            .trim()
            .split(" ")
            .firstOrNull() ?: return emptyList()
        
        // Get the actual command to delegate tab completion
        val command = Bukkit.getPluginCommand(baseCommand) ?: return emptyList()
        
        // Build the args to pass to the real command
        val targetParts = targetCommand
            .replace("{args}", "")
            .trim()
            .split(" ")
            .drop(1) // Remove base command
            .filter { it.isNotEmpty() }
        
        val combinedArgs = (targetParts + args.toList()).toTypedArray()
        
        return try {
            command.tabComplete(sender, baseCommand, combinedArgs) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
