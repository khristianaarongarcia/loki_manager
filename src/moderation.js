/**
 * Moderation Module
 * Handles warn, kick, ban functionality and auto-moderation
 */

const { EmbedBuilder, PermissionFlagsBits } = require('discord.js');
const config = require('./config');
const moderationDB = require('./moderationDB');

class Moderation {
    constructor() {
        this.client = null;
    }

    /**
     * Initialize moderation with Discord client
     */
    initialize(client) {
        this.client = client;
        console.log('🛡️ Moderation module initialized');
    }

    /**
     * Check if user has moderation permissions
     */
    hasModPermission(member) {
        if (!member) return false;
        
        // Check if user is owner
        if (member.id === config.owner.userId) return true;
        
        // Check for mod roles
        const modRoles = config.moderation?.modRoles || [];
        if (modRoles.some(roleId => member.roles.cache.has(roleId))) return true;
        
        // Check for Discord permissions
        return member.permissions.has(PermissionFlagsBits.ModerateMembers) ||
               member.permissions.has(PermissionFlagsBits.KickMembers) ||
               member.permissions.has(PermissionFlagsBits.BanMembers);
    }

    /**
     * Warn a user
     */
    async warnUser(guild, target, moderator, reason) {
        try {
            await moderationDB.ensureUser(target);
            
            // Add warning to database
            const warning = await moderationDB.addWarning(
                target.id,
                guild.id,
                moderator.id,
                reason
            );
            
            // Log the action
            await moderationDB.logAction(
                'WARN',
                target.id,
                guild.id,
                moderator.id,
                reason
            );
            
            // Get warning count
            const warningCount = await moderationDB.getWarningCount(target.id, guild.id);
            
            // Check if auto-action is needed
            const autoAction = await this.checkAutoAction(guild, target, warningCount);
            
            // Try to DM the user
            try {
                const dmEmbed = new EmbedBuilder()
                    .setColor(0xFFFF00)
                    .setTitle('⚠️ You have been warned')
                    .setDescription(`You have received a warning in **${guild.name}**`)
                    .addFields(
                        { name: 'Reason', value: reason || 'No reason provided' },
                        { name: 'Total Warnings', value: `${warningCount}` }
                    )
                    .setTimestamp();
                
                await target.send({ embeds: [dmEmbed] });
            } catch (dmError) {
                // User has DMs disabled
            }
            
            return {
                success: true,
                warningId: warning.id,
                warningCount,
                autoAction
            };
        } catch (error) {
            console.error('❌ Error warning user:', error);
            return { success: false, error: error.message };
        }
    }

    /**
     * Kick a user
     */
    async kickUser(guild, target, moderator, reason) {
        try {
            const member = await guild.members.fetch(target.id).catch(() => null);
            
            if (!member) {
                return { success: false, error: 'User is not in the server' };
            }
            
            if (!member.kickable) {
                return { success: false, error: 'I cannot kick this user (insufficient permissions or higher role)' };
            }
            
            await moderationDB.ensureUser(target);
            
            // Try to DM before kick
            try {
                const dmEmbed = new EmbedBuilder()
                    .setColor(0xFFA500)
                    .setTitle('👢 You have been kicked')
                    .setDescription(`You have been kicked from **${guild.name}**`)
                    .addFields(
                        { name: 'Reason', value: reason || 'No reason provided' }
                    )
                    .setTimestamp();
                
                await target.send({ embeds: [dmEmbed] });
            } catch (dmError) {
                // User has DMs disabled
            }
            
            // Perform the kick
            await member.kick(reason);
            
            // Log to database
            await moderationDB.addKick(target.id, guild.id, moderator.id, reason);
            await moderationDB.logAction('KICK', target.id, guild.id, moderator.id, reason);
            
            return { success: true };
        } catch (error) {
            console.error('❌ Error kicking user:', error);
            return { success: false, error: error.message };
        }
    }

    /**
     * Ban a user
     */
    async banUser(guild, target, moderator, reason, durationMinutes = null) {
        try {
            const member = await guild.members.fetch(target.id).catch(() => null);
            
            if (member && !member.bannable) {
                return { success: false, error: 'I cannot ban this user (insufficient permissions or higher role)' };
            }
            
            await moderationDB.ensureUser(target);
            
            // Try to DM before ban
            try {
                const dmEmbed = new EmbedBuilder()
                    .setColor(0xFF0000)
                    .setTitle('🔨 You have been banned')
                    .setDescription(`You have been banned from **${guild.name}**`)
                    .addFields(
                        { name: 'Reason', value: reason || 'No reason provided' },
                        { name: 'Duration', value: durationMinutes ? `${durationMinutes} minutes` : 'Permanent' }
                    )
                    .setTimestamp();
                
                await target.send({ embeds: [dmEmbed] });
            } catch (dmError) {
                // User has DMs disabled
            }
            
            // Perform the ban
            await guild.members.ban(target.id, { reason, deleteMessageSeconds: 86400 });
            
            // Log to database
            const banResult = await moderationDB.addBan(target.id, guild.id, moderator.id, reason, durationMinutes);
            await moderationDB.logAction('BAN', target.id, guild.id, moderator.id, reason, 
                durationMinutes ? `Duration: ${durationMinutes} minutes` : 'Permanent');
            
            return { success: true, expiresAt: banResult.expiresAt };
        } catch (error) {
            console.error('❌ Error banning user:', error);
            return { success: false, error: error.message };
        }
    }

    /**
     * Unban a user
     */
    async unbanUser(guild, userId, moderator, reason) {
        try {
            // Remove ban from Discord
            await guild.members.unban(userId, reason);
            
            // Update database
            await moderationDB.removeBan(userId, guild.id);
            await moderationDB.logAction('UNBAN', userId, guild.id, moderator.id, reason);
            
            return { success: true };
        } catch (error) {
            console.error('❌ Error unbanning user:', error);
            return { success: false, error: error.message };
        }
    }

    /**
     * Check if auto-action should be taken based on warning count
     */
    async checkAutoAction(guild, target, warningCount) {
        const thresholds = config.moderation?.autoAction || {};
        
        if (thresholds.banAt && warningCount >= thresholds.banAt) {
            const result = await this.banUser(
                guild,
                target,
                { id: this.client.user.id },
                `Auto-ban: Reached ${warningCount} warnings`
            );
            return result.success ? 'BAN' : null;
        }
        
        if (thresholds.kickAt && warningCount >= thresholds.kickAt) {
            const result = await this.kickUser(
                guild,
                target,
                { id: this.client.user.id },
                `Auto-kick: Reached ${warningCount} warnings`
            );
            return result.success ? 'KICK' : null;
        }
        
        return null;
    }

    /**
     * Check message for auto-moderation violations
     */
    async checkMessage(message) {
        if (!config.moderation?.enabled) return null;
        if (message.author.bot) return null;
        
        const member = message.member;
        if (!member) return null;
        
        // Skip if user has mod permissions
        if (this.hasModPermission(member)) return null;
        
        const content = message.content.toLowerCase();
        const violations = [];
        
        // Check banned words
        const bannedWords = config.moderation?.bannedWords || [];
        for (const word of bannedWords) {
            if (content.includes(word.toLowerCase())) {
                violations.push({ type: 'BANNED_WORD', word });
            }
        }
        
        // Check banned patterns (regex)
        const bannedPatterns = config.moderation?.bannedPatterns || [];
        for (const pattern of bannedPatterns) {
            try {
                const regex = new RegExp(pattern, 'i');
                if (regex.test(content)) {
                    violations.push({ type: 'BANNED_PATTERN', pattern });
                }
            } catch (e) {
                // Invalid regex, skip
            }
        }
        
        // Check for excessive caps
        if (config.moderation?.maxCapsPercent) {
            const letters = content.replace(/[^a-zA-Z]/g, '');
            if (letters.length >= 10) {
                const capsCount = (content.match(/[A-Z]/g) || []).length;
                const capsPercent = (capsCount / letters.length) * 100;
                if (capsPercent > config.moderation.maxCapsPercent) {
                    violations.push({ type: 'EXCESSIVE_CAPS', percent: capsPercent });
                }
            }
        }
        
        // Check for excessive mentions
        if (config.moderation?.maxMentions) {
            const mentionCount = message.mentions.users.size + message.mentions.roles.size;
            if (mentionCount > config.moderation.maxMentions) {
                violations.push({ type: 'EXCESSIVE_MENTIONS', count: mentionCount });
            }
        }
        
        // Check for spam links
        if (config.moderation?.blockLinks) {
            const linkRegex = /(https?:\/\/[^\s]+)/gi;
            const links = content.match(linkRegex) || [];
            const allowedDomains = config.moderation?.allowedDomains || [];
            
            for (const link of links) {
                const isAllowed = allowedDomains.some(domain => link.includes(domain));
                if (!isAllowed) {
                    violations.push({ type: 'BLOCKED_LINK', link });
                }
            }
        }
        
        // Check for Discord invites
        if (config.moderation?.blockInvites) {
            const inviteRegex = /(discord\.(gg|io|me|li)|discordapp\.com\/invite)\/[a-zA-Z0-9]+/gi;
            if (inviteRegex.test(content)) {
                violations.push({ type: 'DISCORD_INVITE' });
            }
        }
        
        return violations.length > 0 ? violations : null;
    }

    /**
     * Handle auto-moderation violation
     */
    async handleViolation(message, violations) {
        const action = config.moderation?.violationAction || 'warn';
        const deleteMessage = config.moderation?.deleteViolations !== false;
        
        // Delete the message
        if (deleteMessage) {
            try {
                await message.delete();
            } catch (e) {
                // Message might already be deleted
            }
        }
        
        const violationTypes = violations.map(v => v.type).join(', ');
        const reason = `Auto-mod: ${violationTypes}`;
        
        // Log the violation
        await moderationDB.logAction(
            'AUTO_MOD',
            message.author.id,
            message.guild.id,
            this.client.user.id,
            reason,
            JSON.stringify(violations)
        );
        
        // Take action
        if (action === 'warn') {
            const result = await this.warnUser(
                message.guild,
                message.author,
                { id: this.client.user.id },
                reason
            );
            
            // Send notification in channel
            const embed = new EmbedBuilder()
                .setColor(0xFFFF00)
                .setDescription(`⚠️ ${message.author}, your message was removed for violating server rules.\n**Reason:** ${violationTypes}`)
                .setFooter({ text: `Warning #${result.warningCount}` });
            
            const notification = await message.channel.send({ embeds: [embed] });
            
            // Delete notification after 10 seconds
            setTimeout(() => notification.delete().catch(() => {}), 10000);
            
            return { action: 'WARN', result };
        } else if (action === 'kick') {
            return { action: 'KICK', result: await this.kickUser(message.guild, message.author, { id: this.client.user.id }, reason) };
        } else if (action === 'ban') {
            return { action: 'BAN', result: await this.banUser(message.guild, message.author, { id: this.client.user.id }, reason) };
        }
        
        return null;
    }

    /**
     * Get user moderation history
     */
    async getUserHistory(userId, guildId) {
        return await moderationDB.getUserHistory(userId, guildId);
    }

    /**
     * Get user warnings
     */
    async getUserWarnings(userId, guildId) {
        return await moderationDB.getWarnings(userId, guildId);
    }

    /**
     * Clear user warnings
     */
    async clearUserWarnings(userId, guildId, moderatorId) {
        const result = await moderationDB.clearWarnings(userId, guildId);
        await moderationDB.logAction('CLEAR_WARNINGS', userId, guildId, moderatorId, 'Warnings cleared');
        return result;
    }

    /**
     * Remove specific warning
     */
    async removeWarning(warningId, moderatorId, guildId) {
        const result = await moderationDB.removeWarning(warningId);
        if (result.success) {
            await moderationDB.logAction('REMOVE_WARNING', 'N/A', guildId, moderatorId, `Removed warning #${warningId}`);
        }
        return result;
    }

    /**
     * Check for expired bans and unban users
     */
    async checkExpiredBans() {
        try {
            const expiredBans = await moderationDB.getExpiredBans();
            
            for (const ban of expiredBans) {
                try {
                    const guild = await this.client.guilds.fetch(ban.guild_id);
                    await this.unbanUser(guild, ban.user_id, { id: this.client.user.id }, 'Ban expired');
                    console.log(`🔓 Auto-unbanned user ${ban.user_id} (ban expired)`);
                } catch (e) {
                    console.error(`❌ Error auto-unbanning ${ban.user_id}:`, e.message);
                }
            }
        } catch (error) {
            console.error('❌ Error checking expired bans:', error);
        }
    }

    /**
     * Create moderation embed for responses
     */
    createModEmbed(action, target, moderator, reason, extra = {}) {
        const colors = {
            'WARN': 0xFFFF00,
            'KICK': 0xFFA500,
            'BAN': 0xFF0000,
            'UNBAN': 0x00FF00,
            'CLEAR': 0x00FFFF
        };
        
        const emojis = {
            'WARN': '⚠️',
            'KICK': '👢',
            'BAN': '🔨',
            'UNBAN': '🔓',
            'CLEAR': '🧹'
        };
        
        const embed = new EmbedBuilder()
            .setColor(colors[action] || 0x808080)
            .setTitle(`${emojis[action] || '📋'} ${action}`)
            .addFields(
                { name: 'User', value: `${target.tag || target.username} (${target.id})`, inline: true },
                { name: 'Moderator', value: `${moderator.tag || moderator.username}`, inline: true },
                { name: 'Reason', value: reason || 'No reason provided' }
            )
            .setTimestamp();
        
        if (extra.warningCount) {
            embed.addFields({ name: 'Total Warnings', value: `${extra.warningCount}`, inline: true });
        }
        
        if (extra.duration) {
            embed.addFields({ name: 'Duration', value: extra.duration, inline: true });
        }
        
        return embed;
    }
}

module.exports = new Moderation();
