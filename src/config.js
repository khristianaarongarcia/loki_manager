/**
 * Configuration for the MC Plugin Support Bot
 */

module.exports = {
    // Plugin configurations with their respective channel IDs
    plugins: {
        endex: {
            name: 'TheEndex',
            displayName: 'The Endex',
            folder: 'TheEndex',
            channels: {
                general: '1410357900106399794',  // Endex-Support General Chat
                forum: '1410357987268235366'     // Endex-Support Forum
            },
            keywords: ['endex', 'the endex', 'theendex', 'market', 'economy', 'trading', 'shop', 'invest']
        },
        werm: {
            name: 'WERM',
            displayName: 'WERM',
            folder: 'WERM',
            channels: {
                general: '1452706956870418452',  // WERM Gen Chat
                forum: '1452707033458544801'     // WERM Support Forum
            },
            keywords: ['werm', 'world edit', 'region', 'management']
        },
        depo: {
            name: 'Depo',
            displayName: 'Depo',
            folder: 'Depo',
            channels: {
                general: '1410350428125794416',  // Depo Gen Chat
                forum: '1410357182737940571'     // Depo Forum
            },
            keywords: ['depo', 'deposit', 'storage', 'bank']
        }
    },

    // Owner settings
    owner: {
        userId: '274032691865976833',  // Owner to notify for unanswerable questions
        developerChannel: '1410455088207761469',  // Channel where users can ping developer
        missedClickThreshold: 3,  // Number of missed "I'm Active" clicks before sending DM
        activeCheckTimeout: 5 * 60 * 1000  // 5 minutes to click "I'm Active" button
    },

    // Bot settings
    bot: {
        // How many recent messages to consider for context (multi-message questions)
        messageHistoryLimit: 10,
        
        // Time window to consider messages as part of same conversation (in ms)
        messageContextWindow: 5 * 60 * 1000, // 5 minutes
        
        // Minimum message length to consider as a question
        minQuestionLength: 10,
        
        // Bot response cooldown per user (in ms)
        userCooldown: 5000,
        
        // Whether to respond to bot messages
        ignoreBots: true,
        
        // Typing indicator delay (ms)
        typingDelay: 1000
    },

    // Groq AI settings
    groq: {
        model: process.env.GROQ_MODEL || 'compound-beta',
        maxTokens: 1500,
        temperature: 0.3
    },

    // Knowledge base settings
    knowledgeBase: {
        // File extensions to index
        supportedExtensions: ['.md', '.yml', '.yaml', '.txt', '.kt', '.java'],
        
        // Files/folders to exclude
        excludePatterns: ['node_modules', '.git', 'build', 'target', '*.class'],
        
        // Maximum file size to index (in bytes)
        maxFileSize: 500 * 1024 // 500KB
    },

    // Moderation settings
    moderation: {
        // Enable/disable auto-moderation
        enabled: true,
        
        // Roles that have moderation permissions (in addition to Discord perms)
        modRoles: [],  // Add role IDs here
        
        // Auto-action thresholds based on warning count
        autoAction: {
            kickAt: 3,   // Auto-kick at 3 warnings
            banAt: 5     // Auto-ban at 5 warnings
        },
        
        // What action to take on auto-mod violations
        violationAction: 'warn',  // 'warn', 'kick', or 'ban'
        
        // Whether to delete messages that violate rules
        deleteViolations: true,
        
        // Banned words list
        bannedWords: [
            // Add banned words here
            // 'badword1',
            // 'badword2'
        ],
        
        // Banned regex patterns
        bannedPatterns: [
            // Add regex patterns here
            // '\\b(spam|scam)\\b'
        ],
        
        // Maximum percentage of caps allowed (null to disable)
        maxCapsPercent: 70,
        
        // Maximum mentions allowed in a single message (null to disable)
        maxMentions: 5,
        
        // Block links (except allowed domains)
        blockLinks: false,
        
        // Allowed domains when blockLinks is true
        allowedDomains: [
            'discord.com',
            'spigotmc.org',
            'github.com',
            'polymart.org'
        ],
        
        // Block Discord server invites
        blockInvites: true,
        
        // Check interval for expired bans (in ms)
        banCheckInterval: 60 * 1000  // 1 minute
    }
};
