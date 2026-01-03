package org.lokixcz.theendex.addon

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin
import java.lang.reflect.Field

fun interface AddonSubcommandHandler {
    fun handle(sender: CommandSender, label: String, args: Array<out String>): Boolean
}

fun interface AddonTabCompleter {
    fun complete(sender: CommandSender, label: String, args: Array<out String>): List<String>
}

class AddonCommandRouter(private val plugin: Plugin) {
    private val handlers = mutableMapOf<String, AddonSubcommandHandler>()
    private val aliases = mutableMapOf<String, String>() // alias -> subcommand
    private val completers = mutableMapOf<String, AddonTabCompleter>()

    fun registerSubcommand(name: String, handler: AddonSubcommandHandler) {
        handlers[name.lowercase()] = handler
    }

    fun registerAlias(alias: String, targetSubcommand: String): Boolean {
        val low = alias.lowercase()
        aliases[low] = targetSubcommand.lowercase()
        return registerDynamicCommand(low)
    }

    fun registerCompleter(name: String, completer: AddonTabCompleter) {
        completers[name.lowercase()] = completer
    }

    fun registeredSubcommands(): Set<String> = handlers.keys

    fun complete(sender: CommandSender, label: String, args: Array<out String>): List<String> {
        // 'label' is the top-level addon subcommand name (e.g., "crypto").
        // Use it to find the registered AddonTabCompleter and pass the remaining args through.
        val key = label.lowercase()
        val comp = completers[key] ?: return emptyList()
        return comp.complete(sender, key, args)
    }

    fun dispatch(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) return false
        val sub = args[0].lowercase()
        val handler = handlers[sub] ?: return false
        return handler.handle(sender, sub, args.copyOfRange(1, args.size))
    }

    private fun registerDynamicCommand(name: String): Boolean {
        return try {
            val server = Bukkit.getServer()
            val getCommandMap: Field = server.javaClass.getDeclaredField("commandMap")
            getCommandMap.isAccessible = true
            val map = getCommandMap.get(server) as org.bukkit.command.SimpleCommandMap
            val cmd = object : Command(name), PluginIdentifiableCommand {
                override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
                    // Map alias -> target subcommand
                    val target = this@AddonCommandRouter.aliases[name] ?: return false
                    val merged = arrayOf(target, *args)
                    // Delegate to /endex router
                    val endex = plugin.server.getPluginCommand("endex")
                    if (endex != null) {
                        val exec = endex.executor
                        return if (exec != null) exec.onCommand(sender, endex, commandLabel, merged) else false
                    }
                    return false
                }
                override fun getPlugin(): Plugin = this@AddonCommandRouter.plugin

                override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
                    val target = this@AddonCommandRouter.aliases[name] ?: return mutableListOf()
                    // Delegate directly to the router's completer for the target subcommand so alias commands
                    // suggest the addon's subcommands instead of the core '/endex' top-level options.
                    return this@AddonCommandRouter.complete(sender, target, args).toMutableList()
                }
            }
            val ok = map.register(plugin.name.lowercase(), cmd)
            if (ok) plugin.logger.info("Registered addon alias '/$name'") else plugin.logger.warning("Failed to register addon alias '/$name' (already registered?)")
            ok
        } catch (_: Throwable) { false }
    }
}
