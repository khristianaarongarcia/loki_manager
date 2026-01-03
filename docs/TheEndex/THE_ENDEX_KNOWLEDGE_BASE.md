# The Endex - Complete Knowledge Base

## Overview

The Endex is a dynamic economy plugin for Minecraft (Paper/Spigot 1.20.1 - 1.21.x) that brings realistic market mechanics. Prices fluctuate based on player trading activity, server-wide item storage, and random market events.

**Current Version:** 1.5.7-dec1038
**Supported Minecraft:** 1.20.1 - 1.21.x
**Required:** Vault + Economy plugin (EssentialsX, CMI, etc.)
**Optional:** PlaceholderAPI

---

## Key Features

### 1. Dynamic Pricing
- Prices respond to buy/sell demand with configurable sensitivity
- EMA (Exponential Moving Average) smoothing prevents wild swings
- Per-item min/max price clamps
- Formula: `new_price = old_price × (1 + (demand - supply) × sensitivity)`

### 2. World Storage Scanner
- Scans ALL containers (chests, barrels, shulker boxes) across loaded chunks
- Prices react to global item scarcity/abundance
- Anti-manipulation protection with per-chunk caps
- TPS-aware throttling (pauses if server TPS drops below 18)
- Chunk caching for performance (70-90% faster on established servers)

### 3. Virtual Holdings System
- Purchased items go to virtual storage instead of inventory
- Tracks average cost basis and profit/loss per item
- Players withdraw items when ready
- Prevents inventory space issues during bulk purchases

### 4. Market GUI
- Beautiful interface with categories, search, sorting
- Real-time price charts and sparklines
- Quick buy/sell buttons
- Holdings panel, delivery panel
- Click actions:
  - Left-click: Buy
  - Right-click: Sell
  - Shift+Left-click or Middle-click: Open details view

### 5. Web Dashboard
- REST API with live updates
- Browser-based trading
- Real-time charts
- WebSocket support for instant updates
- Session-based authentication

### 6. Market Events
- Time-boxed price multipliers (e.g., "Ore Rush" = 2x prices)
- Server broadcasts when events start/end
- Configurable via events.yml or commands

### 7. Investment System
- Players can invest in items for passive APR-based returns
- Compound interest calculated continuously
- Commands: `/market invest buy/list/redeem-all`

### 8. PlaceholderAPI Integration
30+ placeholders available for scoreboards, holograms, tab lists.

### 9. Delivery System
- Overflow protection when inventory is full
- Items queue safely for later claiming
- GUI access via Ender Chest button

### 10. Custom Shop Mode
- EconomyShopGUI-style category-based interface
- In-game visual editor for layouts
- Multiple shop configurations

---

## Commands

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/market` or `/shop` | Open market GUI | `theendex.market` (default: true) |
| `/market buy <item> <amount>` | Purchase items | `theendex.buy` (default: true) |
| `/market sell <item> <amount>` | Sell items | `theendex.sell` (default: true) |
| `/market price <item>` | Check current price | `theendex.market` |
| `/market top` | View top traded items | `theendex.market` |
| `/market holdings` | View virtual holdings | `theendex.holdings` (default: true) |
| `/market withdraw <item> [amount]` | Withdraw from holdings | `theendex.withdraw` (default: true) |
| `/market withdraw all` | Withdraw all holdings | `theendex.withdraw` |
| `/market sellholdings <item> <amount>` | Sell directly from holdings | `theendex.sell` |
| `/market delivery list` | View pending deliveries | `theendex.market` |
| `/market delivery claim` | Claim delivered items | `theendex.market` |
| `/market delivery gui` | Open delivery GUI | `theendex.market` |
| `/market invest buy <item> <amount>` | Buy investment | `theendex.invest` (default: true) |
| `/market invest list` | View investments | `theendex.invest` |
| `/market invest redeem-all` | Redeem all investments | `theendex.invest` |
| `/market web` | Get web dashboard link | `theendex.web` (default: true) |

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/endex reload` | Reload configuration | `theendex.admin` (default: op) |
| `/endex version` | Show plugin version | `theendex.market` |
| `/endex help` | Show help menu | `theendex.market` |
| `/market event list` | View active events | `theendex.admin` |
| `/market event <name>` | Start an event | `theendex.admin` |
| `/market event end <name>` | End an event | `theendex.admin` |
| `/market event clear` | Clear all events | `theendex.admin` |
| `/endex shop editor <shop-id>` | Open shop editor | `theendex.admin` |
| `/endex webui export` | Export default web UI | `theendex.admin` |
| `/endex webui reload` | Reload custom web UI | `theendex.admin` |

### Command Aliases
- `/market` aliases: `shop`, `m`, `trade`, `exchange`, `bazaar`
- `/endex` aliases: `ex`
- Namespaced: `/endex:shop`, `/endex:market`, `/endex:m`

---

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `theendex.market` | Access market GUI | true |
| `theendex.buy` | Purchase items | true |
| `theendex.sell` | Sell items | true |
| `theendex.holdings` | Access holdings system | true |
| `theendex.withdraw` | Withdraw from holdings | true |
| `theendex.invest` | Use investments | true |
| `theendex.web` | Access web dashboard | true |
| `theendex.admin` | Admin commands | op |
| `endex.web.trade` | Web dashboard trading | true |
| `endex.web.admin` | View other players' holdings | op |

---

## Configuration Options

### Core Settings

```yaml
# Language (en, pl, zh_CN, ru, es, de, fr, pt, ja, ko)
language:
  locale: en

# How often prices update (seconds)
update-interval-seconds: 120  # Default: 120 (optimized), can be 60 for more responsive

# Price sensitivity (how much demand affects prices)
price-sensitivity: 0.05  # 5% per unit of net demand

# Price history length for charts
history-length: 5  # Points to keep

# Auto-save interval (minutes)
autosave-minutes: 5

# Save after each price update
save-on-each-update: false  # Default: false (better performance)

# Storage mode
storage:
  sqlite: true  # true = SQLite (faster), false = YAML
```

### Price Smoothing

```yaml
price-smoothing:
  enabled: true
  ema-alpha: 0.3  # 0.0-1.0, lower = smoother
  max-change-percent: 15.0  # Max price change per cycle
```

### Buy/Sell Spread

```yaml
spread:
  enabled: true
  buy-markup-percent: 1.5  # Players pay 1.5% above market
  sell-markdown-percent: 1.5  # Players receive 1.5% below market
```

### Inventory-Based Pricing

```yaml
price-inventory:
  enabled: false  # Default: disabled for performance
  sensitivity: 0.02
  per-player-baseline: 64
  max-impact-percent: 10.0
```

### World Storage Scanner

```yaml
price-world-storage:
  enabled: false  # Default: disabled (biggest CPU saver)
  scan-interval-seconds: 300
  sensitivity: 0.01
  global-baseline: 1000
  max-impact-percent: 5.0
  chunks-per-tick: 50
  containers:
    chests: true
    barrels: true
    shulker-boxes: true
    hoppers: false
    droppers: false
    dispensers: false
    furnaces: false
    brewing-stands: false
  scan-shulker-contents: true
  excluded-worlds: []
  anti-manipulation:
    per-chunk-item-cap: 10000
    per-material-chunk-cap: 5000
    min-tps: 18.0
    log-suspicious: true
  cache:
    enabled: true
    chunk-expiry-seconds: 600
    full-refresh-cycles: 5
    persist-to-disk: true
```

### Virtual Holdings

```yaml
holdings:
  enabled: true
  max-total-per-player: 100000
  auto-withdraw-on-login: false
```

### Delivery System

```yaml
delivery:
  enabled: true
  auto-claim-on-login: false
  max-pending-per-player: 10000
```

### Investments

```yaml
investments:
  enabled: true
  apr-percent: 5.0  # Annual percentage rate
```

### Shop Mode

```yaml
shop:
  mode: DEFAULT  # DEFAULT or CUSTOM
  main-shop: main
  command: shop
```

### Web Dashboard

```yaml
web:
  enabled: true
  host: 127.0.0.1
  port: 3434
  session-duration-hours: 2
  compression:
    enabled: true
    level: 4
  poll-ms: 1000
  history-limit: 120
  websocket:
    enabled: true
  rate-limit:
    enabled: true
    requests: 30
    per-seconds: 10
    exempt-ui: true
  roles:
    default: TRADER
    trader-permission: endex.web.trade
    admin-view-permission: endex.web.admin
```

### Events

```yaml
events:
  multiplier-cap: 10.0  # Maximum stacked multiplier
```

### Transaction Tax

```yaml
transaction-tax-percent: 0.0  # 0-10% typical
```

---

## PlaceholderAPI Placeholders

### Price Data
- `%endex_price_<MATERIAL>%` - Current price (e.g., `%endex_price_DIAMOND%`)
- `%endex_trend_<MATERIAL>%` - Trend arrow (↑/↓/→)
- `%endex_supply_<MATERIAL>%` - Current supply
- `%endex_demand_<MATERIAL>%` - Current demand

### Top Items
- `%endex_top_price_<1-10>%` - Top N most expensive items
- `%endex_bottom_price_<1-10>%` - Top N cheapest items
- `%endex_top_gainer_<1-10>%` - Top N price gainers
- `%endex_top_loser_<1-10>%` - Top N price losers

### Player Holdings
- `%endex_holdings_total%` - Total holdings value
- `%endex_holdings_count%` - Number of items held
- `%endex_top_holdings_<1-10>%` - Top N players by holdings

### Market Stats
- `%endex_total_items%` - Total items in market
- `%endex_total_volume%` - Total trading volume
- `%endex_average_price%` - Average item price
- `%endex_active_events%` - Number of active events

---

## GUI Click Actions

### Main Market GUI
- **Left-click on item:** Buy (amount based on selected quantity)
- **Right-click on item:** Sell (amount based on selected quantity)
- **Shift+Left-click or Middle-click:** Open item details view
- **Amount button (slot 47):** Cycle through 1, 8, 16, 32, 64
- **Category button (slot 46):** Cycle through categories
- **Sort button (slot 49):** Cycle sort modes (Name, Price, Change)
- **Search button (slot 50):** Enter search text
- **Holdings button (slot 51):** Open holdings panel
- **Prev/Next (slots 45, 53):** Page navigation

### Details View
- **Buy buttons:** Buy 1, Buy 64, Buy Max
- **Sell buttons:** Sell 1, Sell 64, Sell All
- **Sell from Holdings:** Sell items directly from virtual holdings

### Holdings Panel
- **Left-click:** Withdraw all of that material
- **Right-click:** Withdraw one stack (64 or max stack size)
- **Withdraw All button:** Claim everything that fits

### Custom Shop GUI
- **Left-click:** Buy
- **Right-click:** Sell
- **Shift+Left-click or Middle-click:** Sell stack (64)

---

## Troubleshooting

### Middle-click not working in Creative mode
This is a Minecraft limitation. In Creative mode, Minecraft intercepts middle-click to "pick" the item before plugins can process it.
**Solution:** Use Shift+Left-click or Right-click instead, or switch to Survival mode.

### "Economy unavailable" error
The economy plugin loaded after The Endex. The plugin has a delayed retry (2 seconds) built in.
**Solution:** Restart the server, or ensure economy plugin is in `softdepend` list.

### Prices not updating
Check these settings:
1. `update-interval-seconds` - How often prices update
2. `save-on-each-update` - Whether changes are saved immediately
3. Make sure players are actually trading

### Holdings not working
Requires SQLite storage mode (`storage.sqlite: true`).

### Web dashboard not accessible
1. Check `web.enabled: true`
2. Check firewall allows the port (default 3434)
3. Check `web.host` setting (use `0.0.0.0` for external access)
4. Use `/market web` to get a session link

### GUI clicks not registering (MC 1.21+)
Fixed in version 1.5.2. The plugin uses UUID-based GUI state tracking instead of title matching.

### Items disappearing on purchase
Fixed in version 1.3.1. Purchases now check inventory capacity before deducting money.

---

## File Structure

```
plugins/TheEndex/
├── config.yml           # Main configuration
├── items.yml            # Item definitions and base prices
├── events.yml           # Market events configuration
├── commands.yml         # Command aliases
├── market.db            # SQLite database (if enabled)
├── deliveries.db        # Delivery system database
├── tracking.yml         # Resource tracking data
├── world-scan-cache.json # World scanner cache
├── lang/                # Language files
│   ├── en.yml
│   ├── pl.yml
│   └── ...
├── config_translations/ # Translated config files
│   ├── config_en.yml
│   ├── config_pl.yml
│   └── ...
├── guis/                # GUI layout configs
│   ├── market.yml
│   ├── details.yml
│   ├── holdings.yml
│   └── deliveries.yml
├── shops/               # Custom shop configs
│   └── main.yml
├── addons/              # Addon JAR files
├── history/             # CSV price history exports
└── backups/             # Automatic backups
```

---

## API for Developers

### Bukkit API

```kotlin
val api = server.servicesManager.load(org.lokixcz.theendex.api.EndexAPI::class.java)
if (api != null) {
    val materials = api.listMaterials()
    val price = api.getCurrentPrice(Material.DIAMOND)
    val info = api.getItemInfo(Material.DIAMOND)
}
```

### Bukkit Events

```kotlin
@EventHandler
fun onPriceUpdate(e: org.lokixcz.theendex.api.events.PriceUpdateEvent) {
    // Inspect, modify, or cancel price changes
}

@EventHandler
fun onPreBuy(e: org.lokixcz.theendex.api.events.PreBuyEvent) {
    // Adjust price or amount before purchase
}

@EventHandler
fun onPreSell(e: org.lokixcz.theendex.api.events.PreSellEvent) {
    // Enforce rules before selling
}
```

### HTTP API Endpoints

- `GET /api/items` - List all items with prices
- `GET /api/items/{material}` - Get specific item info
- `POST /api/buy` - Buy items (body: `{material, amount}`)
- `POST /api/sell` - Sell items (body: `{material, amount}`)
- `GET /api/holdings` - Get player holdings
- `POST /api/holdings/withdraw` - Withdraw items
- `GET /api/deliveries` - Get pending deliveries
- `POST /api/deliveries/claim` - Claim deliveries
- `GET /api/events` - List active events
- `GET /api/sse` - Server-Sent Events stream
- `WS /api/ws` - WebSocket connection

---

## Performance Tips

### For Small Servers (< 30 players)
Default optimized config works well. Can enable more features if desired.

### For Medium Servers (30-100 players)
```yaml
update-interval-seconds: 120
price-world-storage:
  enabled: true  # If wanted
  scan-interval-seconds: 300
storage:
  sqlite: true
```

### For Large Servers (100+ players)
```yaml
update-interval-seconds: 300-600
price-world-storage:
  enabled: false  # Or scan-interval-seconds: 600
  chunks-per-tick: 20-30
price-inventory:
  enabled: false
history-length: 30-50
save-on-each-update: false
```

---

## Version History Highlights

- **1.5.7-dec1038:** Polish language support, config translations auto-extract, optimized defaults
- **1.5.7-dec1022:** Web dashboard translation (26+ languages), performance indicators
- **1.5.7:** Sell from holdings, Arclight/hybrid server support
- **1.5.6:** In-game shop layout editor
- **1.5.5:** Economy plugin compatibility fix
- **1.5.4:** bStats metrics, custom shop enhancements
- **1.5.3:** PlaceholderAPI integration, update checker
- **1.5.2:** Optimized world storage scanner, GUI fix for MC 1.21+
- **1.5.1:** World storage scanner
- **1.5.0:** Virtual holdings system
- **1.4.0:** Virtual delivery system
- **1.3.0:** Security hardening, hashed API tokens

---

## Support

- **Documentation:** https://lokixcz-plugins.kagsystems.tech/
- **Discord:** https://discord.gg/ujFRXksUBE
- **GitHub:** https://github.com/khristianaarongarcia/endex
- **Spigot:** https://www.spigotmc.org/resources/128382/
- **Modrinth:** https://modrinth.com/plugin/theendex

---

## Languages Supported

1. English (en) - Default
2. Polish (pl)
3. Chinese Simplified (zh_CN)
4. Russian (ru)
5. Spanish (es)
6. German (de)
7. French (fr)
8. Portuguese (pt)
9. Japanese (ja)
10. Korean (ko)

Language files are in `plugins/TheEndex/lang/`. Set `language.locale` in config.yml.

---

## Common Questions

**Q: How do I add items to the market?**
A: Edit `items.yml` in the plugin folder, then run `/endex reload`.

**Q: How do I change default prices?**
A: Edit the `base-price` value for each item in `items.yml`.

**Q: How do I disable an item from trading?**
A: Set `enabled: false` for that item in `items.yml`, or add it to `blacklist-items` in config.yml.

**Q: How do I create market events?**
A: Use `/market event <event-name>` or configure in `events.yml`.

**Q: How do I give players starting money?**
A: This is handled by your economy plugin (EssentialsX, CMI, etc.), not The Endex.

**Q: How do I change the GUI layout?**
A: Edit files in `plugins/TheEndex/guis/` folder.

**Q: How do I set up the web dashboard with HTTPS?**
A: Use a reverse proxy (nginx, Apache, Caddy) in front of the web server. See docs/REVERSE_PROXY.md.

**Q: Why are prices not changing?**
A: Prices only change when players buy/sell, or based on world storage/inventory scanning if enabled.

**Q: How do I reset the market?**
A: Delete `market.db` (or `market.yml` if using YAML) and restart the server.

**Q: Can players trade with each other?**
A: The plugin is a server market, not player-to-player trading. Players buy from and sell to the server market.
