/**
 * MC Plugin Support Bot
 * Main entry point
 */

require('dotenv').config();

const { Client, GatewayIntentBits, Partials, Events, ChannelType } = require('discord.js');
const config = require('./config');
const knowledgeBase = require('./knowledgeBase');
const groqAI = require('./groqAI');

// Create Discord client with necessary intents
const client = new Client({
    intents: [
        GatewayIntentBits.Guilds,
        GatewayIntentBits.GuildMessages,
        GatewayIntentBits.MessageContent,
        GatewayIntentBits.GuildMessageReactions,
        GatewayIntentBits.DirectMessages
    ],
    partials: [
        Partials.Message,
        Partials.Channel,
        Partials.Reaction
    ]
});

// Cooldown tracking
const userCooldowns = new Map();

// Message history cache for multi-message context
const messageHistory = new Map(); // channelId -> array of recent messages

/**
 * Send a DM notification to the owner about unanswerable questions
 */
async function notifyOwnerUnanswerable(question, pluginName, user, channel, response) {
    try {
        const owner = await client.users.fetch(config.owner.userId);
        if (!owner) {
            console.error('❌ Could not fetch owner user for DM notification');
            return;
        }

        const dmContent = `🔔 **Unanswerable Question Alert**

**Plugin:** ${pluginName}
**User:** ${user.tag} (${user.id})
**Channel:** #${channel.name} (<#${channel.id}>)
**Time:** ${new Date().toLocaleString()}

**Question:**
\`\`\`
${question.substring(0, 500)}${question.length > 500 ? '...' : ''}
\`\`\`

**Bot Response:**
\`\`\`
${response.substring(0, 500)}${response.length > 500 ? '...' : ''}
\`\`\`

_Consider adding documentation to cover this topic._`;

        await owner.send(dmContent);
        console.log(`📬 Notified owner about unanswerable question from ${user.tag}`);
    } catch (error) {
        console.error('❌ Could not send DM to owner:', error.message);
    }
}

/**
 * Check if user is on cooldown
 */
function isOnCooldown(userId) {
    const lastTime = userCooldowns.get(userId);
    if (!lastTime) return false;
    return Date.now() - lastTime < config.bot.userCooldown;
}

/**
 * Set cooldown for user
 */
function setCooldown(userId) {
    userCooldowns.set(userId, Date.now());
}

/**
 * Get recent messages from a user in a channel
 */
async function getRecentUserMessages(channel, userId) {
    try {
        const messages = await channel.messages.fetch({ limit: config.bot.messageHistoryLimit });
        const now = Date.now();
        
        return messages
            .filter(m => 
                m.author.id === userId &&
                (now - m.createdTimestamp) < config.bot.messageContextWindow
            )
            .sort((a, b) => a.createdTimestamp - b.createdTimestamp)
            .map(m => ({
                author: m.author.username,
                content: m.content,
                timestamp: m.createdTimestamp
            }));
    } catch (error) {
        console.error('Error fetching message history:', error.message);
        return [];
    }
}

/**
 * Get thread starter message for forum posts
 */
async function getThreadContext(thread) {
    try {
        const starterMessage = await thread.fetchStarterMessage();
        if (starterMessage) {
            return {
                author: starterMessage.author.username,
                content: starterMessage.content,
                timestamp: starterMessage.createdTimestamp
            };
        }
    } catch (error) {
        // Thread might not have a starter message
    }
    return null;
}

/**
 * Format the bot's response
 */
function formatResponse(result) {
    if (!result.success) {
        return `❓ ${result.message}`;
    }

    let response = result.message;
    
    // Add plugin indicator
    if (result.plugin) {
        response = `**${result.plugin} Support** 📚\n\n${response}`;
    }

    // Ensure response doesn't exceed Discord limit
    if (response.length > 1900) {
        response = response.substring(0, 1900) + '\n\n*...response truncated*';
    }

    return response;
}

/**
 * Handle incoming messages
 */
async function handleMessage(message) {
    // Ignore bot messages
    if (config.bot.ignoreBots && message.author.bot) return;

    // Get channel ID (handle threads)
    const channelId = message.channel.isThread() 
        ? message.channel.parentId 
        : message.channel.id;

    // Check if this is a valid support channel
    const pluginKey = groqAI.getPluginForChannel(channelId);
    
    if (!pluginKey) {
        // Not a support channel, ignore
        return;
    }

    // Check if the message looks like a question
    const isQuestion = await groqAI.isQuestion(message.content);
    
    if (!isQuestion) {
        return;
    }

    // Check cooldown
    if (isOnCooldown(message.author.id)) {
        return;
    }

    // Get the plugin from the channel
    const detectedPlugin = groqAI.detectPlugin(message.content, channelId);

    // If the detected plugin doesn't match the channel's plugin
    if (detectedPlugin && detectedPlugin !== pluginKey) {
        // User is asking about a different plugin in wrong channel
        const redirectMsg = groqAI.generateRedirectMessage(detectedPlugin, channelId);
        if (redirectMsg) {
            await message.reply(redirectMsg);
            setCooldown(message.author.id);
            return;
        }
    }

    // Show typing indicator
    await message.channel.sendTyping();

    try {
        // Get recent messages for context (multi-message questions)
        const recentMessages = await getRecentUserMessages(message.channel, message.author.id);
        
        // If this is a forum thread, also get the thread starter
        let threadContext = [];
        if (message.channel.isThread() && message.channel.parent?.type === ChannelType.GuildForum) {
            const starter = await getThreadContext(message.channel);
            if (starter && starter.content !== message.content) {
                threadContext = [starter];
            }
        }

        // Combine the question (might be split across messages)
        const allMessages = [...threadContext, ...recentMessages];
        const combinedQuestion = allMessages.length > 1
            ? allMessages.map(m => m.content).join('\n')
            : message.content;

        // Generate answer
        const result = await groqAI.generateAnswer(combinedQuestion, pluginKey, allMessages);
        
        // Format and send response
        const response = formatResponse(result);
        await message.reply(response);

        // Notify owner if question was unanswerable
        if (result.unanswerable) {
            const pluginConfig = config.plugins[pluginKey];
            await notifyOwnerUnanswerable(
                combinedQuestion,
                pluginConfig.displayName,
                message.author,
                message.channel,
                result.message
            );
        }

        // Set cooldown
        setCooldown(message.author.id);

        // Log the interaction
        console.log(`📝 Answered question from ${message.author.tag} in #${message.channel.name}`);
        if (result.sourcesUsed?.length > 0) {
            console.log(`   Sources: ${result.sourcesUsed.join(', ')}`);
        }

    } catch (error) {
        console.error('❌ Error handling message:', error);
        try {
            await message.reply('❌ Sorry, I encountered an error while processing your question. Please try again later.');
        } catch (replyError) {
            console.error('Could not send error message:', replyError.message);
        }
    }
}

/**
 * Handle new forum threads
 */
async function handleThreadCreate(thread) {
    // Only handle forum threads
    if (thread.parent?.type !== ChannelType.GuildForum) return;

    const parentId = thread.parentId;
    const pluginKey = groqAI.getPluginForChannel(parentId);
    
    if (!pluginKey) return;

    // Wait a moment for the starter message to be available
    await new Promise(resolve => setTimeout(resolve, 2000));

    try {
        const starterMessage = await thread.fetchStarterMessage();
        if (!starterMessage) return;

        // Process the thread starter as a question
        const fakeMessage = {
            content: starterMessage.content,
            author: starterMessage.author,
            channel: thread,
            reply: (content) => thread.send(content),
            createdTimestamp: starterMessage.createdTimestamp
        };

        // Add channel methods
        fakeMessage.channel.sendTyping = () => thread.sendTyping();
        fakeMessage.channel.isThread = () => true;
        fakeMessage.channel.parentId = parentId;

        await handleMessage(fakeMessage);
    } catch (error) {
        console.error('Error handling new thread:', error.message);
    }
}

// Event handlers
client.once(Events.ClientReady, async (c) => {
    console.log(`\n🤖 Bot logged in as ${c.user.tag}`);
    console.log(`📡 Serving ${c.guilds.cache.size} server(s)\n`);

    // Initialize components
    try {
        await knowledgeBase.initialize();
        groqAI.initialize();
        
        console.log('✅ Bot is ready to answer questions!\n');
        console.log('📢 Monitoring channels:');
        for (const [key, plugin] of Object.entries(config.plugins)) {
            console.log(`   ${plugin.displayName}:`);
            console.log(`      - General: ${plugin.channels.general}`);
            console.log(`      - Forum: ${plugin.channels.forum}`);
        }
        console.log('');
    } catch (error) {
        console.error('❌ Error during initialization:', error);
        process.exit(1);
    }
});

client.on(Events.MessageCreate, handleMessage);
client.on(Events.ThreadCreate, handleThreadCreate);

client.on(Events.Error, (error) => {
    console.error('❌ Discord client error:', error);
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('\n👋 Shutting down gracefully...');
    client.destroy();
    process.exit(0);
});

process.on('SIGTERM', () => {
    console.log('\n👋 Shutting down gracefully...');
    client.destroy();
    process.exit(0);
});

// Start the bot
if (!process.env.DISCORD_TOKEN) {
    console.error('❌ DISCORD_TOKEN environment variable is not set!');
    console.error('   Please create a .env file with your Discord bot token.');
    process.exit(1);
}

client.login(process.env.DISCORD_TOKEN);
