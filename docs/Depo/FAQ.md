# Depo - Frequently Asked Questions

## General Questions

### What is Depo?
Depo is a universal dependency manager for Spigot and Paper Minecraft servers. It automatically detects missing plugin dependencies and downloads them for you. Instead of manually searching for required plugins, Depo reads your plugin.yml files and fetches the missing dependencies from Modrinth or Spiget.

### How does Depo find dependencies?
Depo scans all your installed plugins and reads their plugin.yml files. It looks at the depend and softdepend sections to find what other plugins are needed. Then it searches Modrinth first and Spiget as a fallback to download the missing plugins automatically.

### What is the difference between depend and softdepend?
Dependencies listed under depend are required for the plugin to work. Depo downloads these automatically by default. Dependencies under softdepend are optional and the plugin can work without them. Depo lists these separately and you can install them manually using the soft command.

---

## Installation & Setup

### How do I install Depo?
Download the Depo JAR file and place it in your server's plugins folder. Restart your server and Depo will create its config files. It will immediately start scanning for missing dependencies and download them if auto-download is enabled.

### What servers does Depo support?
Depo works on Spigot, Paper, and Purpur servers running Minecraft 1.20 and above. It automatically detects which server software you are using and adjusts its Modrinth queries accordingly to find compatible plugin versions.

### Where does Depo download plugins from?
Depo downloads from Modrinth as the first choice because it filters by your server type and Minecraft version. If a plugin is not found on Modrinth, it falls back to Spiget. You can change the repository priority in the config.

---

## Commands

### What commands does Depo have?
The main command is `/depo` with these subcommands: `status` or `s` shows missing dependencies, `reload` reloads the config, `tree` shows the dependency tree, `download` lets you manually download plugins, `soft` manages optional dependencies, and `resolve` handles conflicts.

### How do I check what dependencies are missing?
Run `/depo status` or the shortcut `/depo s` to see a list of all missing required and optional dependencies. It also shows which plugins need them and the current download queue.

### How do I download a plugin manually?
Use `/depo download direct <url>` to download from a direct JAR link, or `/depo download github <owner/repo>` to get the latest release from a GitHub repository. You can add an asset filter to pick a specific JAR file from the release.

### How do I install optional dependencies?
Run `/depo soft list` to see all optional dependencies with numbers. Then use `/depo soft install <number>` or `/depo soft install <name>` to install specific ones. You can also use `/depo soft install all` to install everything or comma-separated values.

### How do I resolve dependency conflicts?
If Depo cannot find a matching version, use `/depo resolve <dependency> ignore` to skip it, `/depo resolve <dependency> relax` to remove version constraints, or `/depo resolve <dependency> override <url>` to set a custom download URL.

---

## Configuration

### How do I disable automatic downloads?
Open the config.yml file in the Depo folder and change `auto-download: true` to `auto-download: false`. Depo will still scan and report missing dependencies but will not download them automatically.

### How do I set a custom download URL for a plugin?
Add an entry under the overrides section in config.yml. For example to always download Vault from a specific URL, add `Vault: "https://example.com/Vault.jar"` under overrides.

### What are aliases used for?
Aliases let you tell Depo that one plugin provides another. For example if you use ComplexVault which provides Vault functionality, add `Vault: "ComplexVault"` under aliases. Depo will then consider Vault as satisfied when ComplexVault is installed.

### How do I set version constraints?
Under version-constraints in config.yml, add entries like `PluginName: ">=1.2.0"` to require minimum versions. You can use exact versions, comparators like greater than or less than, caret for compatible versions, tilde for patch updates, wildcards like 1.x, or ranges like 1.0.0 - 2.0.0.

### How do I verify downloaded files?
Add SHA-256 checksums under the checksums section in config.yml. Map the plugin name to its expected hash in lowercase hex. Depo will verify downloads against these checksums and reject mismatches.

---

## Troubleshooting

### Depo is not downloading a plugin
Check that auto-download is enabled in config.yml. Make sure the plugin name matches exactly what is listed in the depend section. Some plugins use different names on Modrinth or Spiget than their plugin.yml name. You may need to add a manual override URL.

### A plugin version is not compatible
Use version constraints to specify which versions you need. If the constraint is too strict and no matching version exists, use `/depo resolve <plugin> relax` to remove the constraint or set an override URL to a specific compatible version.

### Downloads keep failing
Check your server has internet access. Depo requires HTTPS connections by default for security. If you see timeout errors, try increasing the http connect-timeout and read-timeout values in config.yml. The defaults are 10 and 30 seconds.

### I get a checksum mismatch error
This means the downloaded file does not match the expected hash in your checksums config. Either the file has been updated and you need to update the checksum, or there may be a security issue. Remove the checksum entry to skip validation or update it to the correct value.

---

## Permissions

### What permissions does Depo use?
The main permissions are: `depo.use` for basic commands, `depo.notify` to receive notifications about installed plugins, `depo.download` for manual downloads, `depo.download.direct` and `depo.download.github` for specific download methods, `depo.resolve` for conflict resolution, and `depo.soft` for managing optional dependencies.

### Who can use Depo commands by default?
All Depo permissions default to op only. Regular players cannot see or use any Depo commands unless you grant them specific permissions through a permissions plugin.

---

## Security

### Is it safe to auto-download plugins?
Depo only downloads from Modrinth and Spiget which are trusted plugin repositories. It validates that downloaded JARs contain a valid plugin.yml. You can add checksum verification for extra security. Insecure HTTP downloads are blocked by default.

### Can I block HTTP downloads?
Yes, the security.block-insecure-downloads setting is enabled by default. This prevents downloading from non-HTTPS URLs. Only disable this if you absolutely need to download from an HTTP source.
