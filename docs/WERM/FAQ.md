# WERM - Frequently Asked Questions

## General Questions

### What is WERM?
WERM stands for Web Engine for Realm Monetization. It is a Minecraft server monetization platform similar to Tebex. It lets server owners sell ranks, items, and perks to players. Players can use a global wallet system to buy things across different servers. The platform handles payments, deliveries, and server discovery all in one place.

### How is WERM different from Tebex?
WERM offers a modern, API-first design with lower fees. It has a global wallet system where players can use WERM Credits across any server. The platform fee is 3-5% depending on your plan, compared to Tebex's typical 5%. WERM also includes server discovery features and a cleaner dashboard interface.

### What is the WERM Credits system?
WERM Credits are the global currency used on the platform. 1 Credit equals 1 USD cent. Players can add credits to their wallet using credit cards, PayPal, Apple Pay, or Google Pay. These credits can be spent on any server using WERM. If a refund happens, the credits go back to the wallet.

---

## Plugin Installation & Setup

### How do I install the WERM plugin?
Download the WERM plugin JAR file and put it in your server's plugins folder. Restart your server. The plugin will create a config.yml file. You need to add your plugin token to this file to connect your server to WERM.

### Where do I get my plugin token?
Log into your WERM dashboard at wermpay.com. Go to your server settings and click on Plugin. Click Generate Token to create a new plugin token. Copy this token and paste it into your config.yml file under the plugin-token setting.

### What does the config.yml look like?
The main settings in config.yml are:
- `plugin-token` - Your server's unique token from the dashboard
- `debug` - Set to true for extra logging when troubleshooting
- `heartbeat.interval` - How often to send status updates (default 60 seconds)
- `heartbeat.send-player-count` - Whether to share player count
- `delivery.interval` - How often to check for pending purchases (default 15 seconds)

### How do I enable debug mode?
Open your config.yml file and change `debug: false` to `debug: true`. Run the command `/werm reload` in-game or restart the server. Debug mode shows detailed logs about API calls and deliveries.

---

## Commands & Permissions

### What commands does WERM have?
- `/werm verify <code>` - Links a player's Minecraft account to their WERM account
- `/werm help` - Shows all available commands
- `/werm status` - Shows connection status and plugin info (admin only)
- `/werm reload` - Reloads the config file (admin only)

### What permissions are available?
- `werm.verify` - Allows players to use the verify command (default: everyone)
- `werm.admin` - Allows access to status and reload commands (default: op)

### How do players link their accounts?
Players go to wermpay.com/profile and generate a verification code. The code is 6 characters long. They then run `/werm verify <code>` in-game. The plugin links their Minecraft UUID to their WERM account.

---

## Delivery System

### How do purchases get delivered?
When a player buys something, the order goes into a delivery queue. The plugin checks for pending deliveries every 15 seconds by default. When it finds a delivery, it runs the configured commands on the server. Once all commands succeed, it confirms the delivery to WERM.

### What happens if a player is offline?
If a product requires the player to be online, the delivery stays in the queue. When the player joins the server, the plugin immediately checks for pending deliveries and runs them. The system remembers all purchases until they are delivered.

### What are delivery commands?
Commands are what the plugin runs when delivering a purchase. You set these up in the WERM dashboard for each product. Commands can use placeholders like `{player}` for the player name, `{uuid}` for their UUID, and `{quantity}` for how many they bought.

### What placeholders can I use in commands?
- `{player}` or `{player_name}` - The player's Minecraft username
- `{uuid}` or `{player_uuid}` - The player's Minecraft UUID
- `{quantity}` or `{amount}` - How many of the item they purchased
- `{product}` or `{product_name}` - The name of the product
- `{order_id}` - The order ID for reference
- `{delivery_id}` - The delivery ID

---

## Server Verification

### How do I verify my server?
There are three ways to verify your server owns the IP address:
1. MOTD Verification - Add your verification token to your server.properties motd
2. DNS Verification - Add a TXT record to your domain
3. Plugin Verification - The plugin can verify automatically using your token

### What is MOTD verification?
Open your server.properties file. Find the motd line and add your verification token at the end. For example: `motd=My Server Name §8[werm_abc123xyz]`. The system checks every 15 minutes for the token in your MOTD.

### What is DNS verification?
Add a TXT record to your domain. The record should be `_werm.yourdomain.com` with the value being your verification token. This proves you own the domain your server runs on.

---

## Troubleshooting

### The plugin says not configured
Make sure you have added your plugin token to the config.yml file. The token should look like `werm_XXXXXXXX_XXXXXXXXXXXXXXXX`. After adding it, run `/werm reload` or restart your server.

### Deliveries are not working
First enable debug mode by setting `debug: true` in config.yml. Check your console for error messages. Make sure your plugin token is correct. Verify your server has internet access to reach the WERM API.

### Verification code is invalid
Verification codes are case-sensitive and only valid for a limited time. Make sure you are entering the code exactly as shown. If it expired, generate a new code on the website. Codes are 6 characters long with letters and numbers.

### Heartbeat is failing
This usually means the plugin cannot reach the WERM servers. Check your server's internet connection. Make sure no firewall is blocking outbound HTTPS connections. The API endpoint should be reachable at the default WERM domains.

### Commands are not running
Check that your command syntax is correct in the dashboard. Make sure any plugins your commands need are installed. Enable debug mode to see exactly what commands the plugin is trying to run.

---

## Security

### Is my plugin token safe?
Keep your plugin token secret. Anyone with your token can control deliveries to your server. Set proper file permissions on config.yml so only trusted users can read it. If you think your token is compromised, regenerate it from the dashboard immediately.

### How does the plugin prevent command injection?
The plugin sanitizes all player-controlled data before running commands. Only safe characters are allowed in player names and UUIDs. The CommandValidator blocks dangerous commands that could harm your server.

### What data does the plugin send?
The plugin sends heartbeats with player count and server version. It sends player names and UUIDs when verifying accounts. It receives delivery commands from the API. All communication uses HTTPS encryption.

---

## Fees & Pricing

### What are WERM's fees?
The platform fee depends on your plan:
- Free tier: 5% per transaction
- Starter ($4.99/month): 4% per transaction
- Pro ($9.99/month): 3% per transaction
- Enterprise: 2.5% per transaction
Payment processing fees are passed through at cost with no markup.

### What does the free tier include?
The free tier lets you sell unlimited products with standard delivery. You get basic analytics and community support. The limit is $500 per month in revenue. Your store will show WERM branding.

### How do payouts work?
Server owners can request payouts from their dashboard. Free tier servers get weekly payouts. Paid plans get faster payouts, with Pro getting daily payouts. Payouts go to your linked payment method.
