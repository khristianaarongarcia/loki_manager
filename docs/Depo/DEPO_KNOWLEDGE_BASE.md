# Depo - Complete Knowledge Base

## Plugin Overview

Depo is a universal dependency manager for Spigot and Paper Minecraft servers. It automatically detects plugins that are missing dependencies by scanning plugin.yml files and downloads them from trusted repositories like Modrinth and Spiget.

### Key Features
- Automatic detection of missing dependencies from plugin.yml
- Downloads from Modrinth with loader and version filtering
- Falls back to Spiget if not found on Modrinth
- Platform-aware queries for Paper, Purpur, and Spigot
- Async scanning to avoid blocking server startup
- Manual download commands for direct URLs and GitHub releases
- SHA-256 checksum validation
- Version constraints with semantic versioning
- Soft dependency management
- Dependency conflict resolution
- Colored console logs and localization

---

## Commands Reference

### /depo status
Shows the current status including installed plugins, declared dependencies, missing required dependencies, and missing optional dependencies. Shortcut: `/depo s`

### /depo reload
Reloads the configuration and messages files without restarting the server.

### /depo tree
Displays a dependency tree showing which plugins require which dependencies and their status.

### /depo download direct <url>
Downloads a plugin JAR directly from a URL. Requires depo.download.direct permission.

### /depo download github <owner/repo> [filter]
Downloads the latest release JAR from a GitHub repository. Optional filter matches asset filename. Shortcut: `/depo dl github`. Requires depo.download.github permission.

### /depo resolve <dependency> <action>
Resolves dependency conflicts. Actions available:
- `ignore` - Marks the dependency as ignored, no more download attempts
- `relax` - Removes version constraint for the dependency  
- `override <url>` - Sets a custom download URL

### /depo soft list
Lists all optional dependencies with numbers and their installation status.

### /depo soft install <target>
Installs optional dependencies. Target can be:
- A number from the list
- A dependency name
- `all` to install everything
- Comma-separated values like `1,3,5` or `Vault,LuckPerms`

---

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| depo.use | Allows use of /depo commands | op |
| depo.notify | Receive notifications about installed dependencies | op |
| depo.download | Parent permission for download commands | op |
| depo.download.direct | Allows /depo download direct | op |
| depo.download.github | Allows /depo download github | op |
| depo.resolve | Allows resolving dependency conflicts | op |
| depo.soft | Manage soft dependency downloads | op |

---

## Configuration Reference

### config.yml

```yaml
# Config version for migrations
config-version: 4

# Enable automatic downloading of missing dependencies
auto-download: true

# Also auto-download soft dependencies (optional deps)
auto-download-soft: false

# Repository priority order
repository-priority:
  - modrinth
  - spiget

# Manual override URLs for specific plugins
overrides: {}

# Aliases: treat one plugin as providing another
aliases: {}

# SHA-256 checksums for validation
checksums: {}

# Semantic version constraints
version-constraints: {}

# Plugin categories for status grouping
categories: {}

# Show download progress in console
download-progress: true

# HTTP timeout settings in milliseconds
http:
  connect-timeout: 10000
  read-timeout: 30000

# Enable colored console messages
colored-logs: true

# bStats metrics
metrics:
  enabled: true
  service-id: 0

# Check for Depo updates
update-checker:
  enabled: true

# Security settings
security:
  block-insecure-downloads: true
```

### messages.yml
Contains all user-facing messages with color code support using ampersand. Messages use placeholders like `%dep%`, `%by%`, `%list%`, `%restart%` that get replaced with actual values.

---

## Version Constraints

Depo supports semantic versioning constraints for dependencies. Add constraints under version-constraints in config.yml.

### Supported Formats

| Format | Example | Meaning |
|--------|---------|---------|
| Exact | `1.2.3` | Must be exactly version 1.2.3 |
| Greater than | `>1.2.0` | Must be higher than 1.2.0 |
| Greater or equal | `>=1.2.0` | Must be 1.2.0 or higher |
| Less than | `<2.0.0` | Must be lower than 2.0.0 |
| Less or equal | `<=1.5.0` | Must be 1.5.0 or lower |
| Caret | `^1.2.3` | Compatible with 1.x, at least 1.2.3 |
| Tilde | `~1.2.3` | Patch updates only, 1.2.x |
| Wildcard | `1.x` or `1.2.x` | Any version matching pattern |
| Range | `1.0.0 - 2.0.0` | Between versions inclusive |

---

## Override System

Use overrides to specify exact download URLs for plugins that cannot be found automatically.

```yaml
overrides:
  PluginName: "https://example.com/Plugin.jar"
```

Setting an empty string ignores the dependency:
```yaml
overrides:
  IgnoredPlugin: ""
```

---

## Alias System

Use aliases when a plugin provides functionality under a different name.

```yaml
aliases:
  Vault: "ComplexVault"
```

This tells Depo that if ComplexVault is installed, the Vault dependency is satisfied.

---

## Checksum Validation

Add SHA-256 checksums to verify downloaded files:

```yaml
checksums:
  PluginName: "abc123def456..."
```

Downloads that do not match the checksum are rejected and deleted.

---

## Platform Detection

Depo automatically detects your server platform:
- Engine: Paper, Purpur, Spigot, or Bukkit
- Loader: Paper or Spigot (used for Modrinth queries)
- Game Version: Minecraft version like 1.20.4

This information is used to find compatible plugin versions on Modrinth.

---

## Repository Providers

### Modrinth
Primary repository with advanced filtering by loader type and Minecraft version. Returns the highest matching version that satisfies constraints.

### Spiget
Fallback repository that searches by plugin name. Does not support version filtering but covers plugins not on Modrinth.

---

## Dependency Types

### Required Dependencies (depend)
Listed in plugin.yml under `depend`. These are mandatory and the plugin will not work without them. Depo downloads these automatically when auto-download is enabled.

### Optional Dependencies (softdepend)
Listed in plugin.yml under `softdepend`. The plugin works without these but may have reduced functionality. Depo lists these separately and does not download them automatically unless auto-download-soft is enabled.

---

## Install Log

Depo keeps a log of all installed dependencies at `plugins/Depo/installed.log`. Each entry includes timestamp, plugin name, and source URL.

---

## Security Features

### HTTPS Enforcement
By default, Depo blocks downloads from non-HTTPS URLs. This prevents man-in-the-middle attacks. Controlled by security.block-insecure-downloads setting.

### JAR Validation
Downloaded files are validated to contain a valid plugin.yml with a name field. Invalid JARs are deleted.

### Checksum Verification
Optional SHA-256 checksum validation ensures downloaded files match expected hashes.

---

## Troubleshooting

### Plugin not found
- Check the exact name used in depend matches Modrinth/Spiget
- Add a manual override URL in config
- Try searching on Modrinth or Spiget manually

### Version constraint not satisfied
- Use `/depo resolve <plugin> relax` to remove constraint
- Update the constraint to a range that exists
- Set an override URL to specific version

### Connection timeouts
- Increase http.connect-timeout and http.read-timeout in config
- Check server firewall allows outbound HTTPS
- Verify DNS resolution works

### Checksum mismatch
- Plugin may have been updated, get new checksum
- Remove checksum entry to skip validation
- Check for tampering if unexpected
