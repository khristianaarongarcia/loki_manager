package org.lokixcz.theendex.addon

import org.lokixcz.theendex.Endex
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.ServiceLoader

class AddonManager(private val plugin: Endex) {
    private val loadedAddons = mutableMapOf<String, EndexAddon>()
    private val classLoaders = mutableListOf<URLClassLoader>()

    val addonsDir: File = File(plugin.dataFolder, "addons")
    val settingsDir: File = File(addonsDir, "settings")

    fun ensureFolder() {
        if (!addonsDir.exists()) {
            addonsDir.mkdirs()
            plugin.logger.info("Created addons folder at ${addonsDir.absolutePath}")
        }
        if (!settingsDir.exists()) {
            settingsDir.mkdirs()
            plugin.logger.info("Created addons settings folder at ${settingsDir.absolutePath}")
        }
    }

    fun loadAll() {
        ensureFolder()
        val jars = addonsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }?.toList() ?: emptyList()
        if (jars.isEmpty()) {
            plugin.logger.info("No addon jars found in ${addonsDir.name}.")
            return
        }
        for (jar in jars) {
            try {
                val cl = URLClassLoader(arrayOf<URL>(jar.toURI().toURL()), plugin.javaClass.classLoader)
                classLoaders.add(cl)
                val loader: ServiceLoader<EndexAddon> = ServiceLoader.load(EndexAddon::class.java, cl)
                for (addon in loader) {
                    val id = runCatching { addon.id() }.getOrElse { "unknown-${jar.nameWithoutExtension}" }
                    if (loadedAddons.containsKey(id)) {
                        plugin.logger.warning("Skipping addon '${addon.name()}': duplicate id '$id'.")
                        continue
                    }
                    // Prepare per-addon settings directory
                    val perAddonSettings = File(settingsDir, id)
                    if (!perAddonSettings.exists()) perAddonSettings.mkdirs()
                    runCatching { addon.init(plugin) }
                        .onFailure { t -> plugin.logger.severe("Addon '${addon.name()}' init failed: ${t.message}") }
                        .onSuccess {
                            loadedAddons[id] = addon
                            plugin.logger.info("Loaded addon '${addon.name()}' v${addon.version()} (id=$id) from ${jar.name}; settings at ${perAddonSettings.relativeToOrSelf(plugin.dataFolder)}")
                        }
                }
            } catch (t: Throwable) {
                plugin.logger.severe("Failed loading addons from ${jar.name}: ${t.message}")
            }
        }
    }

    fun settingsDirFor(addonId: String): File = File(settingsDir, addonId)

    fun disableAll() {
        for ((id, addon) in loadedAddons) {
            runCatching { addon.onDisable() }
                .onFailure { t -> plugin.logger.warning("Addon '$id' onDisable error: ${t.message}") }
        }
        loadedAddons.clear()
        for (cl in classLoaders) runCatching { cl.close() }
        classLoaders.clear()
    }

    fun loadedAddonNames(): List<String> = loadedAddons.values.map { runCatching { it.name() }.getOrDefault("addon") }
}
