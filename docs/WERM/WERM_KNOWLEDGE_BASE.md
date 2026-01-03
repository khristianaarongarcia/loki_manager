# WERM - Complete Knowledge Base

## Platform Overview

WERM (Web Engine for Realm Monetization) is a Minecraft server monetization platform. It provides server discovery, global wallets, storefronts, and a delivery engine for purchases. The backend uses Appwrite for authentication, database, and serverless functions.

### Core Features
- Server Discovery with public listings and search
- Global Wallet System using WERM Credits
- Store System for selling ranks, items, and perks
- Hybrid Delivery Engine with webhook and polling
- Server Owner Dashboard for management
- Minecraft Plugin for Paper/Spigot servers

### Target Users
- Server Owners who want to monetize their servers
- Developers who need API integration
- Players who want to discover servers and buy items

---

## WERM Credits System

### How Credits Work
- 1 WERM Credit = 1 USD cent
- Credits are stored in a global wallet per user
- Players can spend credits on any server using WERM
- Refunds return credits to wallet, not original payment method

### Adding Credits
Players can fund their wallet using:
- Credit and debit cards
- PayPal
- Apple Pay
- Google Pay

Real-time currency conversion shows prices in the player's local currency. Internal storage is always in USD cents.

### Supported Display Currencies
USD, EUR, GBP, CAD, AUD, BRL, MXN, PLN, SEK, NOK, JPY, KRW, CNY and more based on demand.

---

## Plugin Configuration

### config.yml Reference

```yaml
# Plugin Token - Get from dashboard at wermpay.com
plugin-token: "werm_XXXXXXXX_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

# Debug mode for troubleshooting
debug: false

# Heartbeat settings
heartbeat:
  interval: 60        # Seconds between status updates
  send-player-count: true

# Delivery settings
delivery:
  interval: 15        # Seconds between delivery checks

# Customizable messages
messages:
  prefix: "&8[&6WERM&8] &r"
  verify-success: "&aYour Minecraft account has been linked successfully!"
  verify-failed: "&cVerification failed: &7%reason%"
  verify-usage: "&eUsage: &f/werm verify <code>"
  verify-processing: "&7Verifying your account..."
  already-linked: "&cThis Minecraft account is already linked to another user."
  invalid-code: "&cInvalid verification code. Please check and try again."
  code-expired: "&cThis code has expired. Please generate a new one on the website."
  no-permission: "&cYou don't have permission to use this command."
  token-not-configured: "&cPlugin not configured! Please ask the server admin to set up WERM."
  delivery-received: "&aYou received: &e{product} &ax{quantity}"
```

### plugin.yml Reference

```yaml
name: WERM
version: 1.0.3
main: com.werm.plugin.WERMPlugin
description: WERM - Web Engine for Realm Monetization
author: WERM
website: https://werm.gg
api-version: 1.13

commands:
  werm:
    description: WERM verification commands
    usage: /werm verify <code>
    aliases: [w]

permissions:
  werm.verify:
    description: Allows players to verify their Minecraft account with WERM
    default: true
  werm.admin:
    description: Admin commands for WERM
    default: op
```

---

## Plugin Commands

### /werm verify <code>
Links a player's Minecraft account to their WERM account.
- Permission: werm.verify (default: true)
- Code format: 6 alphanumeric characters
- Rate limited: 5 seconds between attempts

### /werm help
Shows all available commands and links to the website.
- Permission: none required

### /werm status
Shows plugin connection status and configuration.
- Permission: werm.admin (default: op)
- Shows: configured status, heartbeat interval, delivery interval, version

### /werm reload
Reloads the configuration file without restarting.
- Permission: werm.admin (default: op)
- Can also be run from console

---

## Delivery System

### How Deliveries Work
1. Player purchases a product on the web store
2. Payment is processed and wallet is debited
3. Order and delivery record are created
4. Plugin polls for pending deliveries every 15 seconds
5. When found, plugin executes the configured commands
6. Plugin confirms delivery to the API
7. Player is notified in-game

### Delivery States
- `pending` - Waiting to be delivered
- `processing` - Currently being executed
- `delivered` - Successfully completed
- `failed` - Failed after maximum retries

### Command Execution
Commands run as console by default. The plugin replaces placeholders before execution:
- `{player}` - Player username
- `{player_name}` - Player username  
- `{uuid}` - Player UUID
- `{player_uuid}` - Player UUID
- `{quantity}` - Purchase quantity
- `{product_name}` - Product name
- `{order_id}` - Order reference
- `{delivery_id}` - Delivery reference

### Require Online Setting
Products can require the player to be online for delivery. If set, the delivery waits until the player joins. When they join, the plugin immediately checks for pending deliveries.

### Retry System
Failed deliveries use exponential backoff:
15s → 30s → 1m → 2m → 5m → 10m → 30m → 1h → 2h → 4h

After 10 failed attempts, the delivery is marked as failed and needs manual retry from the dashboard.

---

## Server Verification

### Verification Methods

#### MOTD Verification
Add your token to server.properties:
```properties
motd=Your Server Name §8[werm_abc123xyz]
```
The system checks every 15 minutes.

#### DNS TXT Verification
Add a DNS TXT record:
- Name: `_werm.yourdomain.com`
- Value: `werm_abc123xyz`

#### Plugin Verification
The plugin automatically verifies when it sends heartbeats with a valid token. This is the easiest method if you have the plugin configured.

### Verification Token
- Format: `werm_` followed by random characters
- Tokens expire after a set time
- Generate new tokens from the dashboard
- Each server gets a unique token

---

## API Integration

### Base URL
```
https://sgp.cloud.appwrite.io/v1/functions/plugin-api/executions
```

### Authentication
Include the plugin token in the X-Plugin-Token header:
```
X-Plugin-Token: werm_<prefix>_<secret>
```

### API Actions

#### heartbeat
Send server status updates.
```json
{
  "action": "heartbeat",
  "playerCount": 45,
  "maxPlayers": 100,
  "version": "1.20.4",
  "pluginVersion": "1.0.0"
}
```

#### verify
Link a Minecraft account.
```json
{
  "action": "verify",
  "code": "ABC123",
  "minecraftUuid": "uuid-here",
  "minecraftUsername": "Steve"
}
```

#### get-pending-deliveries
Fetch deliveries waiting to be executed.
```json
{
  "action": "get-pending-deliveries",
  "limit": 50
}
```

#### confirm-delivery
Mark a delivery as complete.
```json
{
  "action": "confirm-delivery",
  "deliveryToken": "one-time-token"
}
```

#### fail-delivery
Report a failed delivery.
```json
{
  "action": "fail-delivery",
  "deliveryToken": "one-time-token",
  "errorMessage": "Player not online"
}
```

### Rate Limits
- Heartbeat: 1 request per 10 seconds
- Get deliveries: 1 request per 5 seconds  
- Confirm/Fail: 10 requests per second
- Verify: 5 requests per minute per player

---

## Store System

### Product Types
- Ranks - Permission groups
- Permissions - Individual permissions
- Items - In-game items
- Commands - Custom command execution
- Bundles - Multiple products together
- Subscriptions - Recurring purchases (Phase 2)

### Store Features
- Categories for organization
- Coupons and discount codes
- Limited quantity items
- Offline delivery queue
- Sale scheduling

### Command Configuration
Products can have multiple commands:
- Pre-commands - Run before main delivery
- Main commands - Primary delivery commands
- Post-commands - Run after main delivery

Execution context:
- Console - Run as server console
- Player - Run as the player
- Operator - Run as player with temp OP

---

## Dashboard Features

### Overview Section
- Revenue summary
- Recent orders
- Delivery health status
- Quick statistics

### Server Management
- Register new servers
- Verify server ownership
- Generate plugin tokens
- Edit server details

### Store Builder
- Create and edit products
- Configure commands
- Set prices and categories
- Test deliveries

### API & Webhooks
- Generate API keys
- View request logs
- Configure webhooks
- Monitor webhook status

### Payouts
- View balance
- Request payouts
- Payout history
- Payment method settings

---

## Security Features

### Plugin Token Security
- Keep tokens secret in config.yml
- Set file permissions (chmod 600 on Linux)
- Regenerate if compromised
- IP changes are logged

### Delivery Security
- One-time delivery tokens
- HMAC-signed webhook payloads
- Nonce and timestamp validation
- No duplicate deliveries

### Command Security
- Input sanitization prevents injection
- Only alphanumeric characters allowed in placeholders
- CommandValidator blocks dangerous commands
- UUID format validation

### API Security
- All traffic uses HTTPS
- Rate limiting prevents abuse
- Token validation on every request
- Automatic blocking of bad actors

---

## Fraud Prevention

### Detection Signals
- Multiple accounts from same IP
- Rapid purchase velocity
- Billing and IP location mismatch
- VPN or proxy usage
- New account with high value purchase

### Automatic Responses
- Soft block requiring verification
- Hard block denying transaction
- Manual review queue
- Account suspension

### Chargeback Handling
1. Payment processor notifies WERM
2. Order flagged for review
3. Evidence collection automated
4. Dispute response submitted
5. Outcome recorded

Consequences include wallet adjustment, account flagging, and potential banning for repeat offenders.

---

## Fee Structure

### Platform Fees
- Free tier: 5% per transaction
- Starter ($4.99/month): 4%
- Pro ($9.99/month): 3%
- Enterprise: 2.5%

### Payment Processing
Passed through at cost with no markup.

### Free Tier Limits
- Up to $500/month revenue
- WERM branding on store
- Weekly payouts
- Community support only

### Paid Tier Benefits
- Higher revenue limits
- No branding
- Faster payouts (daily for Pro)
- Priority support

---

## Troubleshooting Guide

### Plugin Not Connecting
1. Check plugin-token is set correctly
2. Verify token format: werm_XXXXX_XXXXX
3. Test internet connectivity
4. Check firewall allows HTTPS outbound
5. Enable debug mode for detailed logs

### Deliveries Not Working
1. Enable debug: true in config.yml
2. Check console for error messages
3. Verify plugin token is valid
4. Confirm commands are configured in dashboard
5. Check if player needs to be online

### Verification Failing
1. Ensure code is exactly 6 characters
2. Check code has not expired
3. Verify player is using correct account
4. Wait for rate limit cooldown (5 seconds)

### Heartbeat Failing
1. Check API endpoint is reachable
2. Verify plugin token is correct
3. Look for network/firewall issues
4. Check server has stable internet

### Commands Not Executing
1. Verify command syntax in dashboard
2. Check required plugins are installed
3. Test commands manually from console
4. Review debug logs for errors

---

## Technical Architecture

### Appwrite Services Used
- Auth - User and role management
- Database - Core data storage
- Functions - Serverless business logic
- Storage - Images with CDN
- Realtime - Live updates

### Database Collections
- users
- servers
- server_verification_tokens
- products
- orders
- order_deliveries
- wallets
- transactions
- payouts
- featured_servers
- reviews
- audit_logs
- gifts
- carts
- wishlists
- scheduled_sales

### Backend Functions
- createOrder
- chargeWallet
- dispatchDelivery
- retryDelivery
- processRefund
- calculatePayout
- processGift
- updateCart
- handleChargeback
- detectFraud
- activateScheduledSale
