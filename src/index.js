/**
 * MC Plugin Support Bot
 * Main entry point
 */

require('dotenv').config();

const { Client, GatewayIntentBits, Partials, Events, ChannelType, ActionRowBuilder, ButtonBuilder, ButtonStyle, PermissionFlagsBits, EmbedBuilder } = require('discord.js');
const config = require('./config');
const knowledgeBase = require('./knowledgeBase');
const groqAI = require('./groqAI');
const moderation = require('./moderation');
const moderationDB = require('./moderationDB');
const updater = require('./updater');

// Create Discord client with necessary intents
const client = new Client({
    intents: [
        GatewayIntentBits.Guilds,
        GatewayIntentBits.GuildMessages,
        GatewayIntentBits.MessageContent,
        GatewayIntentBits.GuildMessageReactions,
        GatewayIntentBits.DirectMessages,
        GatewayIntentBits.GuildMembers,
        GatewayIntentBits.GuildModeration
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

// Active check tracking for owner
const pendingActiveChecks = new Map(); // messageId -> { timeout, data }
let missedActiveChecks = 0;

// Moderation command prefix
const MOD_PREFIX = '!';

/**
 * Handle moderation commands
 */
async function handleModerationCommand(message) {
    if (!message.content.startsWith(MOD_PREFIX)) return false;
    
    const args = message.content.slice(MOD_PREFIX.length).trim().split(/\s+/);
    const command = args.shift()?.toLowerCase();
    
    if (!command) return false;
    
    // Check if user has mod permissions
    if (!moderation.hasModPermission(message.member)) {
        return false; // Silently ignore if not a mod
    }
    
    const { EmbedBuilder } = require('discord.js');
    
    switch (command) {
        case 'warn': {
            // !warn @user [reason]
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user to warn. Usage: `!warn @user [reason]`');
                return true;
            }
            
            const reason = args.slice(1).join(' ') || 'No reason provided';
            const result = await moderation.warnUser(message.guild, target, message.author, reason);
            
            if (result.success) {
                const embed = moderation.createModEmbed('WARN', target, message.author, reason, {
                    warningCount: result.warningCount
                });
                
                let replyContent = { embeds: [embed] };
                if (result.autoAction) {
                    replyContent.content = `⚠️ Auto-action triggered: **${result.autoAction}**`;
                }
                
                await message.reply(replyContent);
            } else {
                await message.reply(`❌ Failed to warn user: ${result.error}`);
            }
            return true;
        }
        
        case 'kick': {
            // !kick @user [reason]
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user to kick. Usage: `!kick @user [reason]`');
                return true;
            }
            
            const reason = args.slice(1).join(' ') || 'No reason provided';
            const result = await moderation.kickUser(message.guild, target, message.author, reason);
            
            if (result.success) {
                const embed = moderation.createModEmbed('KICK', target, message.author, reason);
                await message.reply({ embeds: [embed] });
            } else {
                await message.reply(`❌ Failed to kick user: ${result.error}`);
            }
            return true;
        }
        
        case 'ban': {
            // !ban @user [duration in minutes] [reason]
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user to ban. Usage: `!ban @user [duration_minutes] [reason]`');
                return true;
            }
            
            let duration = null;
            let reasonArgs = args.slice(1);
            
            // Check if first arg after mention is a number (duration)
            if (reasonArgs.length > 0 && !isNaN(reasonArgs[0])) {
                duration = parseInt(reasonArgs[0]);
                reasonArgs = reasonArgs.slice(1);
            }
            
            const reason = reasonArgs.join(' ') || 'No reason provided';
            const result = await moderation.banUser(message.guild, target, message.author, reason, duration);
            
            if (result.success) {
                const embed = moderation.createModEmbed('BAN', target, message.author, reason, {
                    duration: duration ? `${duration} minutes` : 'Permanent'
                });
                await message.reply({ embeds: [embed] });
            } else {
                await message.reply(`❌ Failed to ban user: ${result.error}`);
            }
            return true;
        }
        
        case 'unban': {
            // !unban <userId> [reason]
            const userId = args[0];
            if (!userId) {
                await message.reply('❌ Please provide a user ID. Usage: `!unban <userId> [reason]`');
                return true;
            }
            
            const reason = args.slice(1).join(' ') || 'No reason provided';
            const result = await moderation.unbanUser(message.guild, userId, message.author, reason);
            
            if (result.success) {
                await message.reply(`✅ User \`${userId}\` has been unbanned.`);
            } else {
                await message.reply(`❌ Failed to unban user: ${result.error}`);
            }
            return true;
        }
        
        case 'warnings': {
            // !warnings @user
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user. Usage: `!warnings @user`');
                return true;
            }
            
            const warnings = await moderation.getUserWarnings(target.id, message.guild.id);
            
            if (warnings.length === 0) {
                await message.reply(`✅ ${target.tag} has no active warnings.`);
            } else {
                const embed = new EmbedBuilder()
                    .setColor(0xFFFF00)
                    .setTitle(`⚠️ Warnings for ${target.tag}`)
                    .setDescription(warnings.map((w, i) => 
                        `**#${w.id}** - ${new Date(w.created_at).toLocaleDateString()}\n└ ${w.reason}`
                    ).join('\n\n'))
                    .setFooter({ text: `Total: ${warnings.length} warning(s)` });
                
                await message.reply({ embeds: [embed] });
            }
            return true;
        }
        
        case 'clearwarnings': {
            // !clearwarnings @user
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user. Usage: `!clearwarnings @user`');
                return true;
            }
            
            const result = await moderation.clearUserWarnings(target.id, message.guild.id, message.author.id);
            await message.reply(`✅ Cleared ${result.cleared} warning(s) for ${target.tag}.`);
            return true;
        }
        
        case 'removewarning': {
            // !removewarning <warningId>
            const warningId = parseInt(args[0]);
            if (!warningId) {
                await message.reply('❌ Please provide a warning ID. Usage: `!removewarning <id>`');
                return true;
            }
            
            const result = await moderation.removeWarning(warningId, message.author.id, message.guild.id);
            if (result.success) {
                await message.reply(`✅ Warning #${warningId} has been removed.`);
            } else {
                await message.reply(`❌ Warning #${warningId} not found or already removed.`);
            }
            return true;
        }
        
        case 'history': {
            // !history @user
            const target = message.mentions.users.first();
            if (!target) {
                await message.reply('❌ Please mention a user. Usage: `!history @user`');
                return true;
            }
            
            const history = await moderation.getUserHistory(target.id, message.guild.id);
            
            if (history.length === 0) {
                await message.reply(`✅ ${target.tag} has no moderation history.`);
            } else {
                const embed = new EmbedBuilder()
                    .setColor(0x808080)
                    .setTitle(`📋 Mod History for ${target.tag}`)
                    .setDescription(history.slice(0, 10).map(h => 
                        `**${h.action_type}** - ${new Date(h.created_at).toLocaleDateString()}\n└ ${h.reason || 'No reason'}`
                    ).join('\n\n'))
                    .setFooter({ text: `Showing ${Math.min(10, history.length)} of ${history.length} records` });
                
                await message.reply({ embeds: [embed] });
            }
            return true;
        }
        
        case 'modhelp': {
            const embed = new EmbedBuilder()
                .setColor(0x5865F2)
                .setTitle('🛡️ Moderation Commands')
                .setDescription('Available moderation commands:')
                .addFields(
                    { name: '`!warn @user [reason]`', value: 'Warn a user', inline: false },
                    { name: '`!kick @user [reason]`', value: 'Kick a user from the server', inline: false },
                    { name: '`!ban @user [duration_minutes] [reason]`', value: 'Ban a user (duration optional)', inline: false },
                    { name: '`!unban <userId> [reason]`', value: 'Unban a user by ID', inline: false },
                    { name: '`!warnings @user`', value: 'View user warnings', inline: false },
                    { name: '`!clearwarnings @user`', value: 'Clear all warnings for a user', inline: false },
                    { name: '`!removewarning <id>`', value: 'Remove a specific warning', inline: false },
                    { name: '`!history @user`', value: 'View moderation history', inline: false },
                    { name: '`!version`', value: 'Show current bot version', inline: false },
                    { name: '`!checkupdate`', value: 'Check for available updates', inline: false },
                    { name: '`!update`', value: 'Pull and apply updates (owner only)', inline: false }
                )
                .setFooter({ text: 'Auto-mod: 3 warnings = kick, 5 warnings = ban' });
            
            await message.reply({ embeds: [embed] });
            return true;
        }

        case 'version': {
            const currentVersion = await updater.getCurrentVersion();
            const embed = new EmbedBuilder()
                .setColor(0x5865F2)
                .setTitle('📦 Bot Version')
                .addFields(
                    { name: 'Commit', value: `\`${currentVersion.hash}\``, inline: true },
                    { name: 'Message', value: currentVersion.message, inline: true },
                    { name: 'When', value: currentVersion.date, inline: true }
                )
                .setTimestamp();
            
            await message.reply({ embeds: [embed] });
            return true;
        }

        case 'checkupdate': {
            const statusMsg = await message.reply('🔍 Checking for updates...');
            
            const updateInfo = await updater.checkForUpdates();
            
            if (updateInfo.available) {
                const embed = new EmbedBuilder()
                    .setColor(0x00FF00)
                    .setTitle('🔄 Update Available!')
                    .setDescription(`There ${updateInfo.commits === 1 ? 'is' : 'are'} **${updateInfo.commits}** new commit(s) available.`)
                    .addFields(
                        { name: '📝 Latest Commit', value: updateInfo.latestMessage || 'Unknown', inline: false },
                        { name: '👤 Author', value: updateInfo.latestAuthor || 'Unknown', inline: true },
                        { name: '🕐 When', value: updateInfo.latestDate || 'Unknown', inline: true }
                    )
                    .setFooter({ text: 'Use !update to apply updates (owner only)' });
                
                await statusMsg.edit({ content: null, embeds: [embed] });
            } else {
                await statusMsg.edit('✅ Bot is up to date! No new updates available.');
            }
            return true;
        }

        case 'update': {
            // Only owner can update
            if (message.author.id !== config.owner.userId) {
                await message.reply('❌ Only the bot owner can apply updates.');
                return true;
            }
            
            const statusMsg = await message.reply('🔍 Checking for updates...');
            
            const updateInfo = await updater.checkForUpdates();
            
            if (!updateInfo.available) {
                await statusMsg.edit('✅ Bot is already up to date!');
                return true;
            }
            
            await statusMsg.edit(`📥 Pulling ${updateInfo.commits} update(s)...`);
            
            const result = await updater.pullUpdates();
            
            if (result.success) {
                const embed = new EmbedBuilder()
                    .setColor(0x00FF00)
                    .setTitle('✅ Updates Applied!')
                    .setDescription('The bot has been updated successfully.')
                    .addFields(
                        { name: '📝 Changes', value: result.message.substring(0, 500) || 'Updated' }
                    )
                    .setFooter({ text: '⚠️ Restart the bot to apply code changes' });
                
                await statusMsg.edit({ content: null, embeds: [embed] });
            } else {
                await statusMsg.edit(`❌ Failed to pull updates: ${result.message}`);
            }
            return true;
        }
        
        default:
            return false;
    }
}

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
 * Notify in the developer channel about a user question
 */
async function notifyDevChannel(question, pluginName, user, channel, response, messageLink) {
    try {
        const devChannel = await client.channels.fetch(config.owner.developerChannel);
        if (!devChannel) {
            console.error('❌ Could not fetch developer channel for notification');
            return;
        }

        const { EmbedBuilder } = require('discord.js');
        
        const embed = new EmbedBuilder()
            .setColor(0x5865F2)
            .setTitle('📩 New Support Question')
            .setDescription(`A user asked a question and I provided an answer.`)
            .addFields(
                { name: '👤 User', value: `${user.tag} (<@${user.id}>)`, inline: true },
                { name: '📦 Plugin', value: pluginName, inline: true },
                { name: '📍 Channel', value: `<#${channel.id}>`, inline: true },
                { name: '❓ Question', value: question.substring(0, 1000) + (question.length > 1000 ? '...' : '') },
                { name: '🤖 My Answer', value: response.substring(0, 1000) + (response.length > 1000 ? '...' : '') }
            )
            .setTimestamp();
        
        if (messageLink) {
            embed.addFields({ name: '🔗 Jump to Message', value: messageLink });
        }

        await devChannel.send({ embeds: [embed] });
        console.log(`📢 Notified dev channel about question from ${user.tag}`);
    } catch (error) {
        console.error('❌ Could not send to developer channel:', error.message);
    }
}

/**
 * Notify owner about available GitHub updates
 */
async function notifyOwnerUpdate(updateInfo) {
    try {
        const owner = await client.users.fetch(config.owner.userId);
        if (!owner) return;

        const embed = new EmbedBuilder()
            .setColor(0x00FF00)
            .setTitle('🔄 Bot Update Available!')
            .setDescription(`There ${updateInfo.commits === 1 ? 'is' : 'are'} **${updateInfo.commits}** new commit(s) available.`)
            .addFields(
                { name: '📝 Latest Commit', value: updateInfo.latestMessage || 'Unknown', inline: false },
                { name: '👤 Author', value: updateInfo.latestAuthor || 'Unknown', inline: true },
                { name: '🕐 When', value: updateInfo.latestDate || 'Unknown', inline: true }
            )
            .setFooter({ text: 'Use !update in Discord or restart the bot to apply updates' })
            .setTimestamp();

        await owner.send({ embeds: [embed] });
        console.log('📬 Notified owner about available update');
    } catch (error) {
        console.error('❌ Could not notify owner about update:', error.message);
    }
}

/**
 * Send DM warning to owner about missed active checks
 */
async function notifyOwnerMissedChecks() {
    try {
        const owner = await client.users.fetch(config.owner.userId);
        if (!owner) {
            console.error('❌ Could not fetch owner user for missed checks notification');
            return;
        }

        const dmContent = `⚠️ **Missed Active Check Warning**

You have missed **${missedActiveChecks}** "I'm Active" button clicks in a row.

Users may be waiting for help with questions the bot couldn't fully answer.

Please check the support channels when you have a moment! 🙏`;

        await owner.send(dmContent);
        console.log(`📬 Notified owner about ${missedActiveChecks} missed active checks`);
    } catch (error) {
        console.error('❌ Could not send DM to owner about missed checks:', error.message);
    }
}

/**
 * Handle missed active check timeout
 */
function handleMissedActiveCheck(messageId, data) {
    pendingActiveChecks.delete(messageId);
    missedActiveChecks++;
    
    console.log(`⏰ Missed active check #${missedActiveChecks} for question from ${data.user.tag}`);
    
    if (missedActiveChecks >= config.owner.missedClickThreshold) {
        notifyOwnerMissedChecks();
        missedActiveChecks = 0; // Reset after notification
    }
}

/**
 * Create the "I'm Active" button row
 */
function createActiveCheckButton(messageId) {
    const row = new ActionRowBuilder()
        .addComponents(
            new ButtonBuilder()
                .setCustomId(`active_check_${messageId}`)
                .setLabel("I'm Active")
                .setStyle(ButtonStyle.Success)
                .setEmoji('✅')
        );
    return row;
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

    // Always add the developer notification message at the end
    const devNotice = `\n\n_If you're not satisfied with my answer, I've already notified the developer about your question._`;
    response += devNotice;

    // Ensure response doesn't exceed Discord limit
    if (response.length > 1900) {
        response = response.substring(0, 1850) + '\n\n*...response truncated*' + devNotice;
    }

    return response;
}

/**
 * Handle incoming messages
 */
async function handleMessage(message) {
    // Ignore bot messages
    if (config.bot.ignoreBots && message.author.bot) return;

    // Handle moderation commands first
    if (message.guild) {
        const wasModCommand = await handleModerationCommand(message);
        if (wasModCommand) return;
        
        // Check for auto-moderation violations
        const violations = await moderation.checkMessage(message);
        if (violations) {
            await moderation.handleViolation(message, violations);
            return; // Don't process further if message violated rules
        }
    }

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

    // Use AI to determine if this is actually a plugin-specific support question
    // This prevents responding to casual chat in support channels
    const isPluginQuestion = await groqAI.isPluginQuestionAI(message.content, pluginKey);
    if (!isPluginQuestion) {
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
        
        // If question was unanswerable, add the "I'm Active" button for owner
        let sentMessage;
        if (result.unanswerable) {
            const row = createActiveCheckButton(Date.now().toString());
            sentMessage = await message.reply({ content: response, components: [row] });
            
            // Set up timeout for active check
            const timeout = setTimeout(() => {
                handleMissedActiveCheck(sentMessage.id, {
                    question: combinedQuestion,
                    plugin: pluginKey,
                    user: message.author,
                    channel: message.channel
                });
                
                // Remove the button after timeout
                sentMessage.edit({ content: response, components: [] }).catch(() => {});
            }, config.owner.activeCheckTimeout);
            
            pendingActiveChecks.set(sentMessage.id, {
                timeout,
                data: {
                    question: combinedQuestion,
                    plugin: pluginKey,
                    user: message.author,
                    channel: message.channel
                }
            });
        } else {
            sentMessage = await message.reply(response);
        }

        // Always notify in developer channel about the question
        const pluginConfig = config.plugins[pluginKey];
        const messageLink = `https://discord.com/channels/${message.guild.id}/${message.channel.id}/${sentMessage.id}`;
        await notifyDevChannel(
            combinedQuestion,
            pluginConfig.displayName,
            message.author,
            message.channel,
            result.message,
            messageLink
        );

        // Also DM owner if question was unanswerable
        if (result.unanswerable) {
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
        
        // Initialize moderation
        await moderationDB.initialize();
        moderation.initialize(client);
        
        // Start expired ban checker
        setInterval(() => {
            moderation.checkExpiredBans();
        }, config.moderation?.banCheckInterval || 60000);
        
        // Initialize GitHub updater (will auto-init git repo if needed)
        await updater.initialize(async (updateInfo) => {
            // Notify owner about available updates
            await notifyOwnerUpdate(updateInfo);
        });
        
        // Check for updates every 30 minutes
        updater.startAutoCheck(config.git?.checkInterval || 30);
        
        // Log current version
        const currentVersion = await updater.getCurrentVersion();
        console.log(`📦 Current version: ${currentVersion.hash} - ${currentVersion.message}`);
        
        console.log('✅ Bot is ready to answer questions!\n');
        console.log('📢 Monitoring channels:');
        for (const [key, plugin] of Object.entries(config.plugins)) {
            console.log(`   ${plugin.displayName}:`);
            console.log(`      - General: ${plugin.channels.general}`);
            console.log(`      - Forum: ${plugin.channels.forum}`);
        }
        console.log('\n🛡️ Moderation enabled - Use !modhelp for commands\n');
    } catch (error) {
        console.error('❌ Error during initialization:', error);
        process.exit(1);
    }
});

client.on(Events.MessageCreate, handleMessage);
client.on(Events.ThreadCreate, handleThreadCreate);

// Handle button interactions
client.on(Events.InteractionCreate, async (interaction) => {
    if (!interaction.isButton()) return;
    
    // Check if this is an "I'm Active" button
    if (!interaction.customId.startsWith('active_check_')) return;
    
    // Only allow the owner to click the button
    if (interaction.user.id !== config.owner.userId) {
        await interaction.reply({ 
            content: '❌ Only the developer can click this button.', 
            ephemeral: true 
        });
        return;
    }
    
    // Find and clear the pending check
    const pendingCheck = pendingActiveChecks.get(interaction.message.id);
    if (pendingCheck) {
        clearTimeout(pendingCheck.timeout);
        pendingActiveChecks.delete(interaction.message.id);
        missedActiveChecks = 0; // Reset missed count when owner is active
        
        console.log(`✅ Owner confirmed active for question from ${pendingCheck.data.user.tag}`);
    }
    
    // Update the message to remove the button and acknowledge
    await interaction.update({ 
        components: [] 
    });
    
    // Send a follow-up confirmation
    await interaction.followUp({ 
        content: '✅ Thanks for confirming! The user knows you\'re aware of their question.', 
        ephemeral: true 
    });
});

client.on(Events.Error, (error) => {
    console.error('❌ Discord client error:', error);
});

// Graceful shutdown
process.on('SIGINT', async () => {
    console.log('\n👋 Shutting down gracefully...');
    await moderationDB.close();
    client.destroy();
    process.exit(0);
});

process.on('SIGTERM', async () => {
    console.log('\n👋 Shutting down gracefully...');
    await moderationDB.close();
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
