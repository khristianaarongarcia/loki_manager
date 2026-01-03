# The Endex - Frequently Asked Questions

## Installation & Setup

### How do I install The Endex?
1. Download the JAR from Spigot or Modrinth
2. Place it in your `plugins/` folder
3. Make sure you have Vault and an economy plugin (EssentialsX, CMI, etc.)
4. Start your server
5. The plugin creates `plugins/TheEndex/` with default configs

### What are the requirements?
- **Server:** Paper or Spigot 1.20.1 - 1.21.x
- **Java:** 17 or higher
- **Required:** Vault + an economy plugin
- **Optional:** PlaceholderAPI for placeholders

### Why is economy not working?
Make sure you have:
1. Vault plugin installed
2. An economy plugin (EssentialsX, CMI, iConomy, etc.)
3. Both plugins loaded before The Endex

The plugin has a 2-second delayed retry for late-loading economy plugins.

---

## Commands & Usage

### How do I open the market?
Use any of these commands:
- `/market`
- `/shop`
- `/m`
- `/trade`
- `/exchange`
- `/bazaar`

### How do I buy and sell?
**In GUI:**
- Left-click = Buy
- Right-click = Sell
- Use the Amount button to change quantity (1, 8, 16, 32, 64)

**In Commands:**
- `/market buy DIAMOND 64` - Buy 64 diamonds
- `/market sell DIAMOND 64` - Sell 64 diamonds

### What does Shift+Left-click do?
Opens the item details view with:
- Price history chart
- More buy/sell buttons
- Sell from holdings option

### Why isn't middle-click working?
In Creative mode, Minecraft intercepts middle-click for the "pick block" feature before plugins can use it.

**Solutions:**
- Use Shift+Left-click instead
- Use Right-click for selling
- Switch to Survival mode

### How do I check an item's price?
- `/market price DIAMOND`
- Or open the GUI and look at the item's lore

---

## Holdings System

### What are holdings?
Virtual storage for your purchased items. When you buy something, it goes to holdings instead of your inventory. This prevents inventory space issues.

### How do I withdraw items?
- `/market withdraw DIAMOND` - Withdraw all diamonds
- `/market withdraw DIAMOND 64` - Withdraw 64 diamonds
- `/market withdraw all` - Withdraw everything
- In GUI: Go to Holdings panel, click items

### Can I sell directly from holdings?
Yes! Use `/market sellholdings DIAMOND 64` or click the "Sell from Holdings" buttons in the item details view.

### Why can't I use holdings?
Holdings requires SQLite storage mode. Check that `storage.sqlite: true` in config.yml.

---

## Pricing & Economy

### How do prices change?
Prices change based on:
1. **Buy/Sell transactions** - More buying = higher price, more selling = lower price
2. **Inventory scanning** (if enabled) - Items players hold affect prices
3. **World storage scanning** (if enabled) - Items in chests/barrels affect prices
4. **Market events** - Temporary multipliers

### Why aren't prices changing?
Check these:
1. `update-interval-seconds` in config - prices update on this cycle
2. Players need to actually buy/sell for demand-based changes
3. `price-world-storage.enabled` and `price-inventory.enabled` for passive changes

### What is the spread?
The buy/sell spread creates a gap between what you pay and what you receive:
- Buy price = market price + 1.5% (default)
- Sell price = market price - 1.5% (default)

This prevents instant profit from buy-sell loops.

### What is transaction tax?
Optional percentage taken from all transactions. Acts as a money sink. Default is 0%.

---

## Configuration

### How do I reload the config?
Use `/endex reload` (requires op or `theendex.admin` permission)

### How do I add items to the market?
Edit `plugins/TheEndex/items.yml`:
```yaml
DIAMOND:
  enabled: true
  base-price: 800.0
  min-price: 100.0
  max-price: 5000.0
```
Then `/endex reload`

### How do I remove an item?
In `items.yml`, set `enabled: false` for that item, or add it to `blacklist-items` in config.yml.

### How do I change the language?
In config.yml:
```yaml
language:
  locale: pl  # en, pl, zh_CN, ru, es, de, fr, pt, ja, ko
```
Then `/endex reload`

### Where are translated configs?
After first run, check `plugins/TheEndex/config_translations/` for configs with translated comments in 10 languages.

---

## Web Dashboard

### How do I access the web dashboard?
1. Make sure `web.enabled: true` in config
2. Use `/market web` in-game to get a session link
3. Open the link in your browser

### Why can't I connect to the web dashboard?
Check:
1. `web.enabled: true`
2. Firewall allows the port (default 3434)
3. `web.host` is set correctly (`127.0.0.1` for local, `0.0.0.0` for external)

### How do I set up HTTPS?
Use a reverse proxy (nginx, Apache, Caddy) in front of the web server. The plugin itself only serves HTTP.

### How long do web sessions last?
Default is 2 hours (`web.session-duration-hours`). After that, get a new link with `/market web`.

---

## Performance

### The plugin is using too much CPU
Try these optimizations:
1. Set `price-world-storage.enabled: false` (biggest impact)
2. Increase `update-interval-seconds` to 120-300
3. Set `save-on-each-update: false`
4. Set `price-inventory.enabled: false`

### What's the most performance-intensive feature?
World Storage Scanner (`price-world-storage`). It scans all containers in loaded chunks. Disable it for minimal resource usage.

### What are the recommended settings for large servers?
```yaml
update-interval-seconds: 300
price-world-storage:
  enabled: false
price-inventory:
  enabled: false
save-on-each-update: false
history-length: 30
storage:
  sqlite: true
```

---

## Market Events

### How do I create an event?
Use `/market event <event-name>` where the event is defined in `events.yml`.

### How do I configure events?
Edit `plugins/TheEndex/events.yml`:
```yaml
events:
  diamond-rush:
    display-name: "Diamond Rush"
    materials: [DIAMOND, DIAMOND_ORE, DIAMOND_BLOCK]
    multiplier: 2.0
    duration-minutes: 60
```

### How do I end an event?
- `/market event end <event-name>` - End specific event
- `/market event clear` - End all events

---

## Custom Shop

### How do I use custom shop mode?
In config.yml:
```yaml
shop:
  mode: CUSTOM
  main-shop: main
```

Configure your shop in `plugins/TheEndex/shops/main.yml`

### How do I edit shop layouts?
Use the in-game editor: `/endex shop editor main`

This opens a visual editor where you can:
- Place categories
- Add decorations
- Configure buttons
- Set item positions

---

## Troubleshooting

### Items disappearing when I buy
Fixed in version 1.3.1+. Update to the latest version.

### GUI clicks not working (MC 1.21+)
Fixed in version 1.5.2+. Update to the latest version.

### Plugin not loading on Arclight/hybrid servers
Fixed in version 1.5.7+. Make sure you're using the latest version.

### "Config-version mismatch" warning
Your config is from an older version. Back up your config, delete it, and let the plugin generate a new one. Then copy your settings back.

### PlaceholderAPI placeholders not working
1. Make sure PlaceholderAPI is installed
2. The expansion registers automatically on startup
3. Use correct format: `%endex_price_DIAMOND%`

---

## Data & Storage

### Where is my data stored?
- **SQLite mode:** `plugins/TheEndex/market.db`
- **YAML mode:** `plugins/TheEndex/market.yml`
- Holdings/Deliveries: `market.db` or `deliveries.db`

### How do I backup my data?
The plugin creates automatic backups in `plugins/TheEndex/backups/`. You can also manually copy the database files.

### How do I reset the market?
1. Stop the server
2. Delete `market.db` (or `market.yml`)
3. Optionally delete `items.yml` for fresh item list
4. Start the server

### Can I migrate from YAML to SQLite?
Yes! Set `storage.sqlite: true` and restart. The plugin auto-migrates existing YAML data.

---

## Permissions

### What permissions do players need?
By default, all players can:
- Open market (`theendex.market`)
- Buy items (`theendex.buy`)
- Sell items (`theendex.sell`)
- Use holdings (`theendex.holdings`, `theendex.withdraw`)
- Use investments (`theendex.invest`)
- Access web dashboard (`theendex.web`)

### What permissions are admin-only?
- `theendex.admin` - Reload config, manage events
- `endex.web.admin` - View other players' holdings

### How do I restrict trading?
Remove `theendex.buy` or `theendex.sell` permission from players/groups using your permissions plugin.

---

## Integration

### Does it work with EssentialsX?
Yes! The Endex works with any Vault-compatible economy plugin including EssentialsX.

### Does it work with CMI?
Yes! CMI's economy is Vault-compatible.

### Can I use placeholders in other plugins?
Yes! Install PlaceholderAPI and use placeholders like `%endex_price_DIAMOND%` in any compatible plugin.

### Is there an API for developers?
Yes! See the knowledge base for Bukkit API and HTTP API documentation.

---

## Updates

### How do I check for updates?
The plugin checks automatically on startup if `update-checker.enabled: true`. OPs are notified on join.

### How do I update?
1. Download the new JAR
2. Replace the old JAR in plugins folder
3. Restart the server
4. Check for any config migration messages
