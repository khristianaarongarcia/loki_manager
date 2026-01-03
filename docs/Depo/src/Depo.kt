package org.lokixcz.depo

import kotlinx.serialization.json.*
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bstats.bukkit.Metrics
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
import java.time.Instant
import java.util.jar.JarFile
import org.yaml.snakeyaml.Yaml

class Depo : JavaPlugin() {
    private lateinit var manager: DependencyManager
    private lateinit var log: DepoLogger

    override fun onEnable() {
        saveDefaultConfig()
        // Ensure default messages exist
        if (!File(dataFolder, "messages.yml").exists()) saveResource("messages.yml", false)
        Messages.init(this)

        log = DepoLogger(this)
        manager = DependencyManager(this, log)
        // bStats metrics
        runCatching {
            if (config.getBoolean("metrics.enabled", true)) {
                val id = config.getInt("metrics.service-id", 0)
                if (id > 0) Metrics(this, id)
            }
        }.onFailure { logger.warning("Depo/Metrics: ${it.message}") }
        // Async scan to avoid blocking startup
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable { manager.scanAndResolve() })
        server.pluginManager.registerEvents(manager, this)
        val p = manager.platform
        log.info("Enabled on ${p.engine} ${p.gameVersion} (loader=${p.loader}). Use /depo status for details.")
        // Update checker (notify only)
        if (config.getBoolean("update-checker.enabled", true)) checkForUpdateAsync()
    }

    private fun checkForUpdateAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                val api = "https://api.github.com/repos/${description.authors.firstOrNull() ?: "lokixcz"}/depo/releases/latest"
                val body = Http.getStringWithRetry(api, this) ?: return@Runnable
                val root = Json.parseToJsonElement(body) as? JsonObject ?: return@Runnable
                val tag = ((root["tag_name"] ?: root["name"]) as? JsonPrimitive)?.contentOrNull ?: return@Runnable
                val current = description.version
                if (isNewer(tag, current)) {
                    val msg = Messages.f("notify_installed", mapOf("list" to "New Depo version $tag available (current $current)", "restart" to ""))
                    Bukkit.getConsoleSender().sendMessage(msg)
                    Bukkit.getOnlinePlayers().filter { it.hasPermission("depo.notify") }.forEach { it.sendMessage(msg) }
                }
            } catch (_: Exception) {}
        })
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parse(v: String) = v.trim().removePrefix("v").split('-', '+')[0].split('.').mapNotNull { it.toIntOrNull() }
        val r = parse(remote)
        val l = parse(local)
        val max = maxOf(r.size, l.size)
        for (i in 0 until max) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    override fun onDisable() {}

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!command.name.equals("depo", true)) return false
        if (args.isEmpty()) { sender.sendMessage(Messages.g("cmd_download_usage")); return true }
        val sub = when (args[0].lowercase()) {
            "s" -> "status"
            "dl" -> "download"
            else -> args[0].lowercase()
        }
        when (sub) {
            "status" -> manager.status().forEach { sender.sendMessage(it) }
            "reload" -> { reloadConfig(); Messages.reload(this); manager.reloadFromConfig(); sender.sendMessage(Messages.g("cmd_reload_done")) }
            "tree" -> manager.tree().forEach { sender.sendMessage(it) }
            "resolve" -> {
                if (!sender.hasPermission("depo.resolve")) { sender.sendMessage(Messages.g("cmd_no_permission")); return true }
                if (args.size < 3) { sender.sendMessage(Messages.g("resolve_usage")); return true }
                val dep = args[1]
                val action = args[2].lowercase()
                val cfg = config
                when (action) {
                    "ignore" -> {
                        // add override pointing to blank and mark installed by pretending jar exists next scan via overrides skip
                        cfg.set("overrides.$dep", "")
                        saveConfig(); sender.sendMessage(Messages.f("resolve_done_ignore", mapOf("dep" to dep)))
                    }
                    "relax" -> {
                        if (cfg.isConfigurationSection("version-constraints")) cfg.set("version-constraints.$dep", null)
                        saveConfig(); sender.sendMessage(Messages.f("resolve_done_relax", mapOf("dep" to dep)))
                    }
                    "override" -> {
                        if (args.size < 4) { sender.sendMessage(Messages.g("resolve_usage")); return true }
                        val url = args.drop(3).joinToString(" ")
                        cfg.set("overrides.$dep", url)
                        saveConfig(); sender.sendMessage(Messages.f("resolve_done_override", mapOf("dep" to dep, "url" to url)))
                    }
                    else -> sender.sendMessage(Messages.g("resolve_usage"))
                }
            }
            "soft" -> {
                if (!sender.hasPermission("depo.soft")) { sender.sendMessage(Messages.g("cmd_no_permission")); return true }
                if (args.size == 1 || args[1].equals("list", true)) {
                    val info = manager.scanDependencyMapDetailed()
                    val installed = manager.listSatisfiedNames()
                    val soft = info.soft
                    sender.sendMessage(Messages.f("soft_list_title"))
                    val ordered = soft.keys.sorted()
                    ordered.forEachIndexed { idx, dep ->
                        val status = if (installed.contains(dep)) "installed" else "missing"
                        sender.sendMessage(Messages.f("soft_list_line_numbered", mapOf("index" to (idx + 1).toString(), "dep" to dep, "status" to status)))
                    }
                    return true
                }
                if (args[1].equals("install", true)) {
                    if (args.size < 3) { sender.sendMessage(Messages.g("soft_usage")); return true }
                    val info = manager.scanDependencyMapDetailed()
                    val installed = manager.listSatisfiedNames()
                    val softMissing = info.soft.keys.filter { !installed.contains(it) }
                    val ordered = softMissing.sorted()
                    val inputTokens = args.drop(2).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                    val expanded = mutableSetOf<String>()
                    if (inputTokens.any { it.equals("all", true) }) {
                        expanded.addAll(ordered)
                    } else {
                        inputTokens.forEach { token ->
                            val asInt = token.toIntOrNull()
                            if (asInt != null && asInt in 1..ordered.size) {
                                expanded += ordered[asInt - 1]
                            } else if (softMissing.contains(token)) {
                                expanded += token
                            }
                        }
                    }
                    val finalTargets = expanded.toList()
                    if (finalTargets.isEmpty()) { sender.sendMessage(Messages.g("soft_install_none")); return true }
                    Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
                        finalTargets.forEach { dep ->
                            if (manager.installDependency(dep)) {
                                sender.sendMessage(Messages.f("soft_install_done", mapOf("dep" to dep, "restart" to Messages.g("restarting_required"))))
                            }
                        }
                    })
                    return true
                }
                sender.sendMessage(Messages.g("soft_usage"))
            }
            "download" -> {
                if (!sender.hasPermission("depo.download") && !sender.hasPermission("depo.download.direct") && !sender.hasPermission("depo.download.github")) {
                    sender.sendMessage(Messages.g("cmd_no_permission")); return true
                }
                if (args.size < 3) { sender.sendMessage(Messages.g("cmd_download_usage")); return true }
                when (args[1].lowercase()) {
                    "direct" -> {
                        if (!sender.hasPermission("depo.download") && !sender.hasPermission("depo.download.direct")) { sender.sendMessage(Messages.g("cmd_no_permission")); return true }
                        manager.downloadDirect(sender, args[2])
                    }
                    "github" -> {
                        if (!sender.hasPermission("depo.download") && !sender.hasPermission("depo.download.github")) { sender.sendMessage(Messages.g("cmd_no_permission")); return true }
                        val repo = args[2]
                        val filter = if (args.size >= 4) args.drop(3).joinToString(" ") else null
                        manager.downloadGithub(sender, repo, filter)
                    }
                    else -> sender.sendMessage(Messages.g("cmd_download_usage"))
                }
            }
            else -> sender.sendMessage(Messages.g("cmd_download_usage"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (!command.name.equals("depo", true)) return null
        fun filter(options: Collection<String>, prefix: String): MutableList<String> = options.filter { it.startsWith(prefix, true) }.sorted().toMutableList()
        return when (args.size) {
            0 -> mutableListOf()
            1 -> {
                val opts = mutableListOf("status", "s", "reload", "tree", "resolve")
                if (sender.hasPermission("depo.download") || sender.hasPermission("depo.download.direct") || sender.hasPermission("depo.download.github")) opts += listOf("download", "dl")
                filter(opts, args[0])
            }
            2 -> {
                val sub = when (args[0].lowercase()) { "dl" -> "download"; else -> args[0].lowercase() }
                if (sub != "download") return mutableListOf()
                val opts = mutableListOf<String>()
                if (sender.hasPermission("depo.download") || sender.hasPermission("depo.download.direct")) opts += "direct"
                if (sender.hasPermission("depo.download") || sender.hasPermission("depo.download.github")) opts += "github"
                filter(opts, args[1])
            }
            3 -> {
                val sub = when (args[0].lowercase()) { "dl" -> "download"; else -> args[0].lowercase() }
                if (sub != "download") return mutableListOf()
                when {
                    args[1].equals("direct", true) -> filter(listOf("https://"), args[2])
                    args[1].equals("github", true) -> filter(listOf("owner/repo"), args[2])
                    else -> mutableListOf()
                }
            }
            else -> mutableListOf()
        }
    }
}

private object Messages {
    private var cfg: YamlConfiguration? = null
    fun init(plugin: JavaPlugin) {
        val f = File(plugin.dataFolder, "messages.yml")
        if (!f.exists()) plugin.saveResource("messages.yml", false) else {
            // merge in new defaults without overwriting existing keys
            val defaultStream = plugin.getResource("messages.yml")
            if (defaultStream != null) {
                val defaultCfg = YamlConfiguration.loadConfiguration(InputStreamReader(defaultStream))
                val existing = YamlConfiguration.loadConfiguration(f)
                var changed = false
                for (key in defaultCfg.getKeys(true)) {
                    if (!existing.isConfigurationSection(key) && existing.get(key) == null) {
                        existing.set(key, defaultCfg.get(key))
                        changed = true
                    }
                }
                if (changed) existing.save(f)
            }
        }
        cfg = YamlConfiguration.loadConfiguration(f)
    }
    fun reload(plugin: JavaPlugin) = init(plugin)
    fun g(key: String): String = f(key)
    fun f(key: String, vars: Map<String, String> = emptyMap()): String {
        val raw = cfg?.getString(key) ?: key
        val withVars = vars.entries.fold(raw) { acc, e -> acc.replace("%${e.key}%", e.value) }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', withVars)
    }
}

private class DependencyManager(private val plugin: Depo, private val log: DepoLogger) : org.bukkit.event.Listener {
    private val pluginsDir: File = plugin.dataFolder.parentFile
    private val yaml = Yaml()
    private val json = Json { ignoreUnknownKeys = true }

    data class PlatformInfo(val engine: String, val loader: String, val gameVersion: String)
    val platform: PlatformInfo = detectPlatform()

    private var autoDownload: Boolean = true
    private var repoPriority: List<String> = listOf("modrinth", "spiget")
    private var overrides: Map<String, String> = emptyMap()
    private var aliases: Map<String, String> = emptyMap()
    private var checksums: Map<String, String> = emptyMap()
    private var versionConstraints: Map<String, String> = emptyMap()
    private var categories: Map<String, String> = emptyMap()
    private val conflicts: MutableMap<String, String> = mutableMapOf()

    @Volatile private var downloadQueue: List<String> = emptyList()

    private val providers: Map<String, RepositoryProvider> by lazy {
        mapOf(
            "modrinth" to ModrinthProvider(plugin),
            "spiget" to SpigetProvider(plugin)
        )
    }

    fun reloadFromConfig() {
        migrateConfig()
        plugin.saveDefaultConfig()
        autoDownload = plugin.config.getBoolean("auto-download", true)
        repoPriority = plugin.config.getStringList("repository-priority").ifEmpty { listOf("modrinth", "spiget") }
        overrides = plugin.config.getConfigurationSection("overrides")?.getKeys(false)?.associateWith { plugin.config.getString("overrides.$it")!! } ?: emptyMap()
        aliases = plugin.config.getConfigurationSection("aliases")?.getKeys(false)?.associateWith { plugin.config.getString("aliases.$it")!! } ?: emptyMap()
        checksums = plugin.config.getConfigurationSection("checksums")?.getKeys(false)?.associateWith { plugin.config.getString("checksums.$it")!!.lowercase() } ?: emptyMap()
        versionConstraints = plugin.config.getConfigurationSection("version-constraints")?.getKeys(false)?.associateWith { plugin.config.getString("version-constraints.$it")!!.trim() } ?: emptyMap()
        categories = plugin.config.getConfigurationSection("categories")?.getKeys(false)?.associateWith { plugin.config.getString("categories.$it")!!.trim() } ?: emptyMap()
    }

    private fun migrateConfig() {
        val cfg = plugin.config
        val ver = cfg.getInt("config-version", 0)
        if (ver < 1) {
            if (!cfg.isConfigurationSection("metrics")) { cfg.set("metrics.enabled", true); cfg.set("metrics.service-id", 0) }
            if (!cfg.isConfigurationSection("update-checker")) { cfg.set("update-checker.enabled", true) }
            if (!cfg.isConfigurationSection("security")) { cfg.set("security.block-insecure-downloads", true) }
            if (!cfg.isList("repository-priority")) cfg.set("repository-priority", listOf("modrinth", "spiget"))
            if (!cfg.isConfigurationSection("overrides")) cfg.set("overrides", emptyMap<String, String>())
            if (!cfg.isConfigurationSection("aliases")) cfg.set("aliases", emptyMap<String, String>())
            if (!cfg.isConfigurationSection("checksums")) cfg.set("checksums", emptyMap<String, String>())
            cfg.set("config-version", 1)
            plugin.saveConfig()
        }
        if (ver < 2) {
            if (!cfg.isConfigurationSection("version-constraints")) cfg.set("version-constraints", emptyMap<String, String>())
            cfg.set("config-version", 2)
            plugin.saveConfig()
        }
        if (ver < 3) {
            if (!cfg.isConfigurationSection("categories")) cfg.set("categories", emptyMap<String, String>())
            if (!cfg.isSet("download-progress")) cfg.set("download-progress", true)
            cfg.set("config-version", 3)
            plugin.saveConfig()
        }
        if (ver < 4) {
            if (!cfg.isSet("auto-download-soft")) cfg.set("auto-download-soft", false)
            cfg.set("config-version", 4)
            plugin.saveConfig()
        }
    }

    fun scanAndResolve() {
        reloadFromConfig()
        conflicts.clear()
        val installed = listSatisfiedNames()
        val depInfo = scanDependencyMapDetailed()
        val requiredDeclared = depInfo.required.keys
        val softDeclared = depInfo.soft.keys
        val missingRequired = requiredDeclared.filter { it !in installed }.sorted()
        val missingSoft = softDeclared.filter { it !in installed }.sorted()
        downloadQueue = missingRequired
        if (missingRequired.isEmpty()) {
            log.success("No missing required dependencies.")
        } else {
            log.info("Missing required: ${missingRequired.joinToString(", ")}")
        }
        if (missingSoft.isNotEmpty()) {
            log.info("Missing optional (soft): ${missingSoft.joinToString(", ")} | ${Messages.g("soft_hint_log")}")
        }
        if (!autoDownload) { log.warn("auto-download is disabled. Only logging missing required dependencies."); return }
        val downloadedMap = linkedMapOf<String, Set<String>>()
        for (dep in missingRequired) if (installDependency(dep)) downloadedMap[dep] = depInfo.required[dep] ?: emptySet()
        downloadQueue = emptyList()
        if (downloadedMap.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable { notifyAdminInstall(downloadedMap) })
        if (conflicts.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable { notifyResolversConflicts() })
        // Notify about soft dependencies if not auto-downloaded
        if (missingSoft.isNotEmpty() && !plugin.config.getBoolean("auto-download-soft", false)) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                Bukkit.getOnlinePlayers().filter { it.hasPermission("depo.soft") }.forEach { p ->
                    p.sendMessage(Messages.f("soft_missing_title"))
                    missingSoft.forEach { sd -> p.sendMessage(Messages.f("soft_missing_item", mapOf("dep" to sd))) }
                    p.sendMessage(Messages.f("soft_hint_install"))
                }
            })
        } else if (plugin.config.getBoolean("auto-download-soft", false)) {
            // attempt to also download soft dependencies
            for (dep in missingSoft) if (installDependency(dep)) { /* no separate log grouping */ }
        }
    }

    fun status(): List<String> {
        val installed = listSatisfiedNames()
        val depInfo = scanDependencyMapDetailed()
        val required = depInfo.required
        val soft = depInfo.soft
        val allDeclared = required.keys + soft.keys
        val missingReq = required.keys.filter { it !in installed }.sorted()
        val missingSoft = soft.keys.filter { it !in installed }.sorted()
        val lines = mutableListOf<String>()
        lines += Messages.f("status_title")
        lines += Messages.f("status_platform", mapOf("engine" to platform.engine, "mc" to platform.gameVersion, "loader" to platform.loader))
        lines += Messages.f("status_installed", mapOf("list" to installed.sorted().joinToString(", ")))
        lines += Messages.f("status_declared", mapOf("list" to allDeclared.sorted().joinToString(", ")))
        // categories grouping
        if (categories.isNotEmpty()) {
            val byCat = categories.entries.groupBy({ it.value }, { it.key }).toSortedMap(String.CASE_INSENSITIVE_ORDER)
            lines += Messages.f("status_categories_title")
            byCat.forEach { (cat, list) ->
                val present = list.filter { installed.contains(it) }.sorted()
                if (present.isNotEmpty()) lines += Messages.f("status_category_line", mapOf("cat" to cat, "list" to present.joinToString(", "))) }
        }
        if (conflicts.isNotEmpty()) {
            lines += Messages.f("conflicts_title")
            conflicts.forEach { (dep, reason) -> lines += Messages.f("conflicts_item", mapOf("dep" to dep, "reason" to reason)) }
            lines += Messages.f("conflicts_hint")
        }
        if (missingReq.isEmpty()) lines += Messages.f("status_none_missing") else {
            lines += Messages.f("status_missing_title")
            missingReq.forEach { dep ->
                val reqBy = required[dep]?.sorted()?.joinToString(", ") ?: "unknown"
                lines += Messages.f("status_missing_item", mapOf("dep" to dep, "by" to reqBy))
            }
        }
        if (missingSoft.isNotEmpty()) {
            lines += Messages.f("soft_missing_title")
            missingSoft.forEach { dep ->
                val by = soft[dep]?.sorted()?.joinToString(", ") ?: "unknown"
                lines += Messages.f("soft_missing_item", mapOf("dep" to dep, "by" to by))
            }
            lines += Messages.f("soft_hint_install")
        }
        val queue = downloadQueue
        if (queue.isNotEmpty()) {
            lines += Messages.f("status_queue_title")
            queue.forEach { dep ->
                val by = (required[dep] ?: soft[dep])?.sorted()?.joinToString(", ") ?: "unknown"
                lines += Messages.f("status_queue_item", mapOf("dep" to dep, "by" to by))
            }
        }
        return lines
    }

    data class DependencyMaps(val required: Map<String, Set<String>>, val soft: Map<String, Set<String>>)
    fun scanDependencyMapDetailed(): DependencyMaps {
        val req = mutableMapOf<String, MutableSet<String>>()
        val soft = mutableMapOf<String, MutableSet<String>>()
        val jars = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) }?.toList() ?: emptyList()
        for (jar in jars) {
            runCatching {
                JarFile(jar).use { jf ->
                    val entry = jf.getJarEntry("plugin.yml") ?: return@use
                    jf.getInputStream(entry).use { ins ->
                        InputStreamReader(ins).use { reader ->
                            val map = yaml.load<Map<String, Any?>>(reader)
                            val name = map["name"]?.toString() ?: return@use
                            if (name.equals("Depo", true)) return@use
                            val depends = (map["depend"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            val softList = (map["softdepend"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            depends.forEach { d -> req.computeIfAbsent(d) { mutableSetOf() }.add(name) }
                            softList.forEach { d -> soft.computeIfAbsent(d) { mutableSetOf() }.add(name) }
                        }
                    }
                }
            }.onFailure { ex -> log.warn("Failed reading ${jar.name}: ${ex.message}") }
        }
        return DependencyMaps(req, soft)
    }

    fun tree(): List<String> {
        val declaredMap = scanDependencyMap() // dep -> requiring plugins
        if (declaredMap.isEmpty()) return listOf(Messages.f("tree_no_dependencies"))
        val installed = listSatisfiedNames()
        val missing = declaredMap.keys.filter { it !in installed }.toSet()
        val byPlugin: MutableMap<String, MutableSet<String>> = mutableMapOf()
        // invert declaredMap (dep -> requiringPlugins) to plugin -> declared deps
        declaredMap.forEach { (dep, reqBy) -> reqBy.forEach { plugin -> byPlugin.computeIfAbsent(plugin) { mutableSetOf() }.add(dep) } }
        val lines = mutableListOf<String>()
        lines += Messages.f("tree_title")
        byPlugin.keys.sorted().forEach { pluginName ->
            lines += Messages.f("tree_plugin", mapOf("plugin" to pluginName))
            val deps = byPlugin[pluginName]!!.sorted()
            if (deps.isEmpty()) lines += Messages.f("tree_dep_present", mapOf("dep" to "(none)", "by" to pluginName)) else {
                deps.forEach { dep ->
                    val constraint = versionConstraints[dep]
                    val label = if (dep in missing) Messages.f("tree_dep_missing", mapOf("dep" to (constraint?.let { "$dep@$it" } ?: dep), "by" to pluginName)) else Messages.f("tree_dep_present", mapOf("dep" to (constraint?.let { "$dep@$it" } ?: dep), "by" to pluginName))
                    lines += label
                }
            }
        }
        return lines
    }

    private fun color(s: String): String = org.bukkit.ChatColor.translateAlternateColorCodes('&', s)

    // Build dependency -> requiring plugin names map
    private fun scanDependencyMap(): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        val jars = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) }?.toList() ?: emptyList()
        for (jar in jars) {
            runCatching {
                JarFile(jar).use { jf ->
                    val entry = jf.getJarEntry("plugin.yml") ?: return@use
                    jf.getInputStream(entry).use { ins ->
                        InputStreamReader(ins).use { reader ->
                            val map = yaml.load<Map<String, Any?>>(reader)
                            val name = map["name"]?.toString() ?: return@use
                            if (name.equals("Depo", true)) return@use
                            val depends = (map["depend"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            val soft = (map["softdepend"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            (depends + soft).forEach { dep ->
                                result.computeIfAbsent(dep) { mutableSetOf() }.add(name)
                            }
                        }
                    }
                }
            }.onFailure { ex -> log.warn("Failed reading ${jar.name}: ${ex.message}") }
        }
        return result
    }

    // Determine which dependency names are already satisfied by installed plugins or their `provides`, plus config aliases
    fun listSatisfiedNames(): Set<String> {
        val names = mutableSetOf<String>()
        val plugins: Array<Plugin> = Bukkit.getPluginManager().plugins
        plugins.forEach { p ->
            names += p.name
            runCatching {
                val provides = p.description.provides
                if (provides != null) names.addAll(provides)
            }
        }
        aliases.forEach { (depName, providedBy) ->
            if (plugins.any { it.name.equals(providedBy, true) }) names += depName
        }
        return names.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun installDependency(depName: String): Boolean {
        if (jarWithPluginNameExists(depName)) { log.info("Found existing jar for $depName in /plugins. Skipping download."); return true }
        overrides[depName]?.let { url ->
            return if (downloadToPluginsWithRetry(url, "$depName.jar", depName)) { appendInstallLog(depName, url); true } else false
        }
        val constraint = versionConstraints[depName]
        if (constraint != null) log.info("Applying version constraint '$constraint' for $depName")
        for (key in repoPriority) {
            val provider = providers[key] ?: continue
            log.info("Trying $key for $depName ...")
            val url = provider.resolveDownloadUrl(depName, platform, constraint)
            if (url != null) {
                val filename = provider.suggestFileName(depName, url)
                if (downloadToPluginsWithRetry(url, filename, depName)) { appendInstallLog(depName, url); return true }
            }
        }
        val reason = if (constraint != null) "no version satisfies constraint '$constraint'" else "not found"
        conflicts[depName] = reason
        log.warn("Could not find $depName on trusted repositories${if (constraint!=null) " matching constraint '$constraint'" else ""}.")
        return false
    }

    private fun notifyResolversConflicts() {
        val msgLines = mutableListOf<String>()
        msgLines += Messages.f("conflicts_title")
        conflicts.forEach { (dep, reason) -> msgLines += Messages.f("conflicts_item", mapOf("dep" to dep, "reason" to reason)) }
        msgLines += Messages.f("conflicts_hint")
        Bukkit.getOnlinePlayers().filter { it.hasPermission("depo.resolve") }.forEach { p -> msgLines.forEach { p.sendMessage(it) } }
    }

    private fun appendInstallLog(depName: String, sourceUrl: String) {
        runCatching {
            if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
            val f = File(plugin.dataFolder, "installed.log")
            f.appendText("${Instant.now()} | $depName | $sourceUrl\n")
        }.onFailure { ex -> log.warn("Failed to write installed.log: ${ex.message}") }
    }

    private fun jarWithPluginNameExists(expectedName: String): Boolean {
        val jars = pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", true) } ?: return false
        for (jar in jars) {
            runCatching {
                JarFile(jar).use { jf ->
                    val entry = jf.getJarEntry("plugin.yml") ?: return@use
                    jf.getInputStream(entry).use { ins ->
                        InputStreamReader(ins).use { reader ->
                            val map = yaml.load<Map<String, Any?>>(reader)
                            val name = map["name"]?.toString()?.trim() ?: return@use
                            if (name.equals(expectedName, true)) return true
                            val provides = (map["provides"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            if (provides.any { it.equals(expectedName, true) }) return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun downloadToPluginsWithRetry(url: String, fileName: String, expectedName: String, attempts: Int = 2): Boolean {
        repeat(attempts) { i ->
            if (downloadToPlugins(url, fileName, expectedName)) return true
            if (i < attempts - 1) log.warn("Retrying download: $fileName (attempt ${i + 2}/$attempts)")
        }
        return false
    }

    private fun downloadToPlugins(url: String, fileName: String, expectedName: String): Boolean {
        // Security: block non-HTTPS for any download if configured
        if (plugin.config.getBoolean("security.block-insecure-downloads", true) && url.lowercase().startsWith("http://")) {
            log.warn("Blocked insecure download: $url")
            return false
        }
        return try {
            val target = File(pluginsDir, fileName)
            val ok = Http.get(url, plugin).writeTo(target, plugin, fileName)
            if (!ok) { log.warn("Failed to download $fileName from $url"); return false }
            val valid = validateDownloadedJar(target, expectedName)
            if (!valid) { log.warn("Downloaded file does not contain plugin.yml for $expectedName. Deleting $fileName."); runCatching { target.delete() }; return false }
            // checksum validation (optional)
            checksums[expectedName]?.let { expectedHex ->
                val actual = sha256Hex(target)
                if (!actual.equals(expectedHex, true)) {
                    log.warn("Checksum mismatch for $fileName (expected $expectedHex, got $actual). Deleting file.")
                    runCatching { target.delete() }
                    return false
                }
            }
            log.success("Downloaded $fileName to /plugins.")
            true
        } catch (e: Exception) { log.error("Download error for $url: ${e.message}"); false }
    }

    // Validate that a jar contains plugin.yml declaring expectedName either as name or via provides
    private fun validateDownloadedJar(file: File, expectedName: String): Boolean {
        return try {
            JarFile(file).use { jf ->
                val entry = jf.getJarEntry("plugin.yml") ?: return false
                jf.getInputStream(entry).use { ins ->
                    InputStreamReader(ins).use { reader ->
                        val map = yaml.load<Map<String, Any?>>(reader)
                        val name = map["name"]?.toString()?.trim() ?: return false
                        if (name.equals(expectedName, true)) return true
                        val provides = (map["provides"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                        provides.any { it.equals(expectedName, true) }
                    }
                }
            }
        } catch (_: Exception) { false }
    }

    private fun notifyAdminInstall(downloaded: Map<String, Set<String>>) {
        val parts = downloaded.entries.joinToString(", ") { (dep, reqBy) -> "$dep (by ${reqBy.joinToString(", ")})" }
        val msg = Messages.f("notify_installed", mapOf("list" to parts, "restart" to Messages.g("restarting_required")))
        Bukkit.getOnlinePlayers().filter { it.hasPermission("depo.notify") }.forEach { it.sendMessage(msg) }
        Bukkit.getConsoleSender().sendMessage(msg)
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun detectPlatform(): PlatformInfo {
        val versionStr = Bukkit.getVersion().lowercase()
        val nameStr = Bukkit.getServer().name.lowercase()
        val engine = when {
            "purpur" in versionStr || "purpur" in nameStr -> "purpur"
            "paper" in versionStr || "paper" in nameStr -> "paper"
            "spigot" in versionStr || "spigot" in nameStr -> "spigot"
            else -> "bukkit"
        }
        val loader = if (engine == "purpur" || engine == "paper") "paper" else "spigot"
        val gameVersion = try { Bukkit.getBukkitVersion().substringBefore('-') } catch (_: Exception) {
            val m = Regex("\\(MC: ([^)]+)\\)").find(Bukkit.getVersion()); m?.groupValues?.getOrNull(1) ?: "unknown"
        }
        return PlatformInfo(engine, loader, gameVersion)
    }

    // Manual downloads
    fun downloadDirect(sender: CommandSender, url: String) {
        sender.sendMessage(Messages.g("cmd_download_start_direct"))
        // Security: block non-HTTPS if configured
        if (plugin.config.getBoolean("security.block-insecure-downloads", true) && url.lowercase().startsWith("http://")) {
            sender.sendMessage(org.bukkit.ChatColor.RED.toString() + "Insecure URL blocked (http): $url")
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val suggested = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.jar" }
            val result = downloadArbitrary(url, suggested)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (result != null) sender.sendMessage(Messages.f("cmd_download_success", mapOf("plugin" to result, "restart" to Messages.g("restarting_required"))))
                else sender.sendMessage(Messages.g("cmd_download_fail"))
            })
        })
    }

    fun downloadGithub(sender: CommandSender, ownerRepo: String, assetFilter: String?) {
        sender.sendMessage(Messages.g("cmd_download_start_github"))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val parts = ownerRepo.split('/')
            if (parts.size != 2) {
                Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(org.bukkit.ChatColor.RED.toString() + "Invalid repo. Use owner/repo.") })
                return@Runnable
            }
            val (owner, repo) = parts
            val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val body = Http.getStringWithRetry(apiUrl, plugin)
            if (body.isNullOrBlank()) {
                Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(color("&c[Depo]&r &fNo release info found.")) })
                return@Runnable
            }
            val root = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            val assets = (root?.get("assets") as? JsonArray) ?: JsonArray(emptyList())
            var selectedName: String? = null
            var selectedUrl: String? = null
            for (e in assets) {
                val o = e as? JsonObject ?: continue
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val dl = (o["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: continue
                if (!name.endsWith(".jar", true)) continue
                if (assetFilter != null && !name.contains(assetFilter, true)) continue
                selectedName = name
                selectedUrl = dl
                break
            }
            if (selectedUrl == null) {
                Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(color("&c[Depo]&r &fNo .jar asset found in latest release.")) })
                return@Runnable
            }
            val result = downloadArbitrary(selectedUrl!!, selectedName)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (result != null) sender.sendMessage(Messages.f("cmd_download_success_github", mapOf("plugin" to result, "restart" to Messages.g("restarting_required"))))
                else sender.sendMessage(Messages.g("cmd_download_fail"))
            })
        })
    }

    private fun downloadArbitrary(url: String, suggestedFileName: String?): String? {
        return try {
            val fileName = suggestedFileName?.ifBlank { null } ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download.jar" }
            val tmp = File(pluginsDir, ".depo_tmp_" + System.currentTimeMillis() + ".jar")
            val ok = Http.get(url, plugin).writeTo(tmp, plugin, fileName)
            if (!ok) { log.warn("Failed to download from $url"); runCatching { tmp.delete() }; return null }
            val pluginName = parsePluginNameFromJar(tmp)
            if (pluginName == null) {
                log.warn("Downloaded file has no valid plugin.yml: $fileName")
                runCatching { tmp.delete() }
                return null
            }
            // optional checksum validation
            checksums[pluginName]?.let { expectedHex ->
                val actual = sha256Hex(tmp)
                if (!actual.equals(expectedHex, true)) {
                    log.warn("Checksum mismatch for $fileName (expected $expectedHex, got $actual). Aborting.")
                    runCatching { tmp.delete() }
                    return null
                }
            }
            if (jarWithPluginNameExists(pluginName)) {
                log.warn("A plugin providing '$pluginName' already exists. Skipping.")
                runCatching { tmp.delete() }
                return pluginName
            }
            val target = File(pluginsDir, fileName)
            val finalTarget = if (target.exists()) File(pluginsDir, pluginName + "-" + System.currentTimeMillis() + ".jar") else target
            tmp.copyTo(finalTarget, overwrite = true)
            runCatching { tmp.delete() }
            log.success("Downloaded ${finalTarget.name} to /plugins.")
            appendInstallLog(pluginName, url)
            pluginName
        } catch (e: Exception) {
            log.error("Download error for $url: ${e.message}")
            null
        }
    }

    private fun parsePluginNameFromJar(file: File): String? {
        return try {
            JarFile(file).use { jf ->
                val entry = jf.getJarEntry("plugin.yml") ?: return null
                jf.getInputStream(entry).use { ins ->
                    InputStreamReader(ins).use { reader ->
                        val map = yaml.load<Map<String, Any?>>(reader)
                        map["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }
        } catch (_: Exception) { null }
    }
}

// Provide a single top-level HTTP helper used everywhere
private object Http {
    private fun client(plugin: JavaPlugin): okhttp3.OkHttpClient {
        val cfg = plugin.config
        val connect = cfg.getInt("http.connect-timeout", 10000).toLong()
        val read = cfg.getInt("http.read-timeout", 30000).toLong()
        return okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofMillis(connect))
            .readTimeout(java.time.Duration.ofMillis(read))
            .build()
    }
    fun get(url: String, plugin: JavaPlugin): ResponseWrapper {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Depo/1.0 (+https://github.com/lokixcz/depo)").build()
        val resp = client(plugin).newCall(req).execute()
        return ResponseWrapper(resp)
    }
    class ResponseWrapper(private val response: okhttp3.Response) : AutoCloseable {
        fun string(): String? = response.body?.string().also { close() }
        fun writeTo(target: File, plugin: JavaPlugin? = null, fileLabel: String? = null): Boolean {
            response.use { r ->
                if (!r.isSuccessful) return false
                val body = r.body ?: return false
                val total = body.contentLength()
                val show = plugin?.config?.getBoolean("download-progress", true) == true && total > 64 * 1024 // only show for files >64KB
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var acc: Long = 0
                var lastPct = -1L
                target.outputStream().use { out ->
                    body.byteStream().use { ins ->
                        while (true) {
                            read = ins.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            if (show) {
                                acc += read
                                val pct = ((acc * 100) / total).coerceIn(0, 100)
                                if (pct % 10L == 0L && pct != lastPct) {
                                    lastPct = pct
                                    Bukkit.getConsoleSender().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', Messages.f("progress_prefix", mapOf("file" to (fileLabel ?: target.name), "percent" to pct.toString()))))
                                }
                            }
                        }
                    }
                }
                return true
            }
        }
        override fun close() { response.close() }
    }

    fun getStringWithRetry(url: String, plugin: JavaPlugin, attempts: Int = 2, delayMillis: Long = 300): String? {
        repeat(attempts) { i ->
            try {
                val s = get(url, plugin).string()
                if (!s.isNullOrBlank()) return s
            } catch (_: Exception) {}
            if (i < attempts - 1) try { Thread.sleep(delayMillis) } catch (_: InterruptedException) {}
        }
        return null
    }
}

private interface RepositoryProvider {
    fun resolveDownloadUrl(project: String, platform: DependencyManager.PlatformInfo, constraint: String? = null): String?
    fun suggestFileName(project: String, url: String): String = "$project.jar"
}

private class ModrinthProvider(private val plugin: JavaPlugin) : RepositoryProvider {
    override fun resolveDownloadUrl(project: String, platform: DependencyManager.PlatformInfo, constraint: String?): String? {
        return try {
            val loaders = URLEncoder.encode("[\"${platform.loader}\"]", Charsets.UTF_8)
            val versions = URLEncoder.encode("[\"${platform.gameVersion}\"]", Charsets.UTF_8)
            val filtered = "https://api.modrinth.com/v2/project/$project/version?loaders=$loaders&game_versions=$versions"
            val unfiltered = "https://api.modrinth.com/v2/project/$project/version"
            val body = Http.getStringWithRetry(filtered, plugin) ?: Http.getStringWithRetry(unfiltered, plugin) ?: return null
            val arr = Json.parseToJsonElement(body) as? JsonArray ?: return null
            val candidates = mutableListOf<Pair<SemVer, String>>()
            val constraintObj = constraint?.let { VersionConstraint(it) }
            for (elem in arr) {
                val obj = elem as? JsonObject ?: continue
                val versionStr = (obj["version_number"] as? JsonPrimitive)?.contentOrNull ?: continue
                val sem = SemVer.parseOrNull(versionStr) ?: continue
                if (constraintObj != null && !constraintObj.matches(sem)) continue
                val files = obj["files"] as? JsonArray ?: continue
                val primary = files.firstOrNull { ((it as? JsonObject)?.get("primary") as? JsonPrimitive)?.booleanOrNull == true && ((it as JsonObject)["url"] as? JsonPrimitive)?.contentOrNull?.endsWith(".jar", true) == true }
                val chosen = primary ?: files.firstOrNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.endsWith(".jar", true) == true }
                val url = (chosen as? JsonObject)?.get("url") as? JsonPrimitive ?: continue
                candidates += sem to url.content
            }
            candidates.maxByOrNull { it.first }?.second
        } catch (e: Exception) { plugin.logger.warning("Depo/Modrinth: ${e.message}"); null }
    }
}

private class SpigetProvider(private val plugin: JavaPlugin) : RepositoryProvider {
    override fun resolveDownloadUrl(project: String, platform: DependencyManager.PlatformInfo, constraint: String?): String? {
        return try {
            val url = "https://api.spiget.org/v2/search/resources/$project?size=25&fields=id,name"
            val body = Http.getStringWithRetry(url, plugin) ?: return null
            val arr = Json.parseToJsonElement(body) as? JsonArray ?: return null
            var chosenId: Int? = null
            for (e in arr) {
                val o = e as? JsonObject ?: continue
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: continue
                if (name.equals(project, true)) { chosenId = id; break }
                if (chosenId == null) chosenId = id
            }
            chosenId?.let { "https://api.spiget.org/v2/resources/$it/download" }
        } catch (e: Exception) { plugin.logger.warning("Depo/Spiget: ${e.message}"); null }
    }
}

// Semantic Versioning representation
private data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major - other.major
        if (minor != other.minor) return minor - other.minor
        if (patch != other.patch) return patch - other.patch
        if (pre == null && other.pre != null) return 1
        if (pre != null && other.pre == null) return -1
        if (pre == null && other.pre == null) return 0
        return pre!!.compareTo(other.pre!!)
    }
    companion object {
        private val regex = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?$")
        fun parseOrNull(s: String): SemVer? {
            val m = regex.matchEntire(s.trim()) ?: return null
            val maj = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifBlank { "0" }.toInt()
            val pat = m.groupValues[3].ifBlank { "0" }.toInt()
            val pre = m.groupValues[4].ifBlank { null }
            return SemVer(maj, min, pat, pre)
        }
    }
}

// Constraint evaluator supporting comparators, caret (^), tilde (~), wildcards, and ranges
private class VersionConstraint(raw: String) {
    private val predicates: List<(SemVer) -> Boolean>
    init {
        val trimmed = raw.trim()
        predicates = if (" - " in trimmed) { // inclusive range a - b
            val (a, b) = trimmed.split(" - ", limit = 2)
            val lower = SemVer.parseOrNull(a) ?: SemVer(0,0,0)
            val upper = SemVer.parseOrNull(b) ?: SemVer(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            listOf({ v: SemVer -> v >= lower && v <= upper })
        } else {
            trimmed.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { token -> buildPredicate(token) }
        }
    }
    private fun buildPredicate(token: String): (SemVer) -> Boolean {
        val t = token.trim()
        if (t.endsWith(".x") || t.endsWith(".*")) {
            val base = t.removeSuffix(".x").removeSuffix(".*")
            val segs = base.split('.')
            return when (segs.size) {
                1 -> { v -> v.major == segs[0].toInt() }
                2 -> { v -> v.major == segs[0].toInt() && v.minor == segs[1].toInt() }
                else -> { _ -> true }
            }
        }
        if (t.startsWith("^")) {
            SemVer.parseOrNull(t.substring(1))?.let { base ->
                val upper = SemVer(base.major + 1, 0, 0)
                return { v -> v >= base && v < upper }
            }
        }
        if (t.startsWith("~")) {
            SemVer.parseOrNull(t.substring(1))?.let { base ->
                val upper = SemVer(base.major, base.minor + 1, 0)
                return { v -> v >= base && v < upper }
            }
        }
        val cmp = Regex("^(>=|<=|>|<|=)?(.+)$").matchEntire(t)
        if (cmp != null) {
            val op = cmp.groupValues[1].ifBlank { "=" }
            val ver = SemVer.parseOrNull(cmp.groupValues[2]) ?: return { _ -> true }
            return when (op) {
                ">" -> { v -> v > ver }
                ">=" -> { v -> v >= ver }
                "<" -> { v -> v < ver }
                "<=" -> { v -> v <= ver }
                else -> { v -> v == ver }
            }
        }
        SemVer.parseOrNull(t)?.let { exact -> return { v -> v == exact } }
        return { _ -> true }
    }
    fun matches(v: SemVer): Boolean = predicates.all { it(v) }
}

private class DepoLogger(private val plugin: JavaPlugin) {
    private fun color(s: String): String = org.bukkit.ChatColor.translateAlternateColorCodes('&', s)
    private val prefixColored = color("&6[Depo]&r")
    private val coloredEnabled: Boolean get() = plugin.config.getBoolean("colored-logs", true)
    fun info(message: String) { if (coloredEnabled) Bukkit.getConsoleSender().sendMessage("$prefixColored ${color("&7$message")}") else plugin.logger.info(message) }
    fun warn(message: String) { if (coloredEnabled) Bukkit.getConsoleSender().sendMessage("$prefixColored ${color("&e$message")}") else plugin.logger.warning(message) }
    fun error(message: String) { if (coloredEnabled) Bukkit.getConsoleSender().sendMessage("$prefixColored ${color("&c$message")}") else plugin.logger.severe(message) }
    fun success(message: String) { if (coloredEnabled) Bukkit.getConsoleSender().sendMessage("$prefixColored ${color("&a$message")}") else plugin.logger.info(message) }
}