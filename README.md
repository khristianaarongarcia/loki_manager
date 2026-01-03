# MC Plugin Support Bot

A Discord bot that automatically answers questions about your Minecraft plugins using AI (Groq) and plugin documentation.

## Features

- 🤖 **Automatic Question Detection** - Analyzes messages to identify questions
- 📚 **Knowledge Base** - Indexes documentation from plugin folders (TheEndex, WERM, Depo)
- 🔍 **Smart Search** - Finds relevant documentation based on the question
- 💬 **Multi-Message Context** - Understands questions split across multiple messages
- 🧵 **Forum Support** - Automatically responds to new forum threads
- 🎯 **Plugin Detection** - Identifies which plugin the question is about
- ⚡ **Channel Enforcement** - Redirects users to correct support channels
- 🛡️ **Cooldown System** - Prevents spam

## Supported Plugins

| Plugin | General Chat | Forum |
|--------|-------------|-------|
| The Endex | `1410357900106399794` | `1410357987268235366` |
| WERM | `1452706956870418452` | `1452707033458544801` |
| Depo | `1410350428125794416` | `1410357182737940571` |

## Setup

### 1. Prerequisites

- Node.js 18+ installed
- A Discord Bot Token ([Discord Developer Portal](https://discord.com/developers/applications))
- A Groq API Key ([Groq Console](https://console.groq.com/))

### 2. Installation

```bash
# Navigate to the bot directory
cd discord_bot

# Install dependencies
npm install
```

### 3. Configuration

Create a `.env` file in the `discord_bot` folder:

```env
# Discord Bot Token (from Discord Developer Portal)
DISCORD_TOKEN=your_discord_bot_token_here

# Groq API Key (from Groq Console)
GROQ_API_KEY=your_groq_api_key_here

# Groq Model (optional, defaults to compound-beta)
GROQ_MODEL=compound-beta
```

### 4. Discord Bot Setup

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Create a new application
3. Go to "Bot" section and create a bot
4. Enable these **Privileged Gateway Intents**:
   - Message Content Intent
   - Server Members Intent (optional)
5. Copy the bot token to your `.env` file
6. Go to OAuth2 → URL Generator:
   - Select `bot` scope
   - Select permissions: `Read Messages/View Channels`, `Send Messages`, `Read Message History`, `Embed Links`
7. Use the generated URL to invite the bot to your server

### 5. Run the Bot

```bash
# Production
npm start

# Development (with auto-restart)
npm run dev
```

## Project Structure

```
discord_bot/
├── src/
│   ├── index.js         # Main bot entry point
│   ├── config.js        # Configuration settings
│   ├── knowledgeBase.js # Documentation loader & search
│   └── groqAI.js        # Groq AI integration
├── docs/                # Plugin documentation folders
│   ├── TheEndex/        # The Endex plugin docs
│   ├── WERM/            # WERM plugin docs
│   └── Depo/            # Depo plugin docs
├── .env                 # Environment variables (create this)
├── .env.example         # Example environment file
├── package.json         # Dependencies
└── README.md            # This file
```

## How It Works

1. **Message Detection**: Bot monitors configured support channels
2. **Question Analysis**: Uses pattern matching to identify questions
3. **Plugin Detection**: Determines which plugin based on channel/keywords
4. **Context Gathering**: Fetches recent messages for multi-message questions
5. **Knowledge Search**: Searches relevant documentation files
6. **AI Generation**: Groq generates an answer based on documentation
7. **Response**: Bot replies with the formatted answer

## Adding New Plugins

To add a new plugin, edit `src/config.js`:

```javascript
plugins: {
    // ... existing plugins ...
    
    newplugin: {
        name: 'NewPlugin',
        displayName: 'New Plugin',
        folder: 'NewPlugin',  // Folder name in bot-docs
        channels: {
            general: 'CHANNEL_ID_HERE',
            forum: 'FORUM_CHANNEL_ID_HERE'
        },
        keywords: ['newplugin', 'keyword1', 'keyword2']
    }
}
```

Then create a folder with documentation files (`.md`, `.yml`, `.txt`, etc.) in the `docs/` directory.

## Documentation Format

The bot indexes these file types:
- `.md` - Markdown documentation
- `.yml` / `.yaml` - Configuration files
- `.txt` - Text files
- `.kt` / `.java` - Source code (for API reference)

For best results, include:
- A `FAQ.md` file with common questions
- A `KNOWLEDGE_BASE.md` or similar with comprehensive docs
- Well-commented configuration files

## Troubleshooting

### Bot not responding
- Check that the bot has proper permissions in the channel
- Verify the channel IDs in `config.js` match your server
- Ensure Message Content Intent is enabled in Discord Developer Portal

### No documentation found
- Check that plugin folders exist in the parent directory
- Verify file extensions are supported
- Check console output for loading errors

### Rate limiting
- Groq has rate limits; consider adjusting `userCooldown` in config
- The bot has a built-in cooldown to prevent spam

## License

ISC
