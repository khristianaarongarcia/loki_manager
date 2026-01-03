package org.lokixcz.theendex.addon

import org.lokixcz.theendex.Endex

/**
 * Public Addon API contract. External jars can implement this interface and expose a
 * META-INF/services/org.lokixcz.theendex.addon.EndexAddon file listing their implementation class.
 * The plugin will discover and initialize them on startup.
 */
interface EndexAddon {
    /** Stable, unique identifier (e.g., "example-addon"). */
    fun id(): String

    /** Human-readable name. */
    fun name(): String

    /** Semantic/short version string. */
    fun version(): String

    /** Called when the addon is loaded. Safe to register listeners/commands and use Endex API. */
    fun init(plugin: Endex)

    /** Called on plugin disable or reload to allow cleanup. */
    fun onDisable() {}
}
