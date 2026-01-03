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
            // Profanity & slurs
            'nigger', 'nigga', 'faggot', 'fag', 'retard', 'retarded',
            'kys', 'kill yourself', 'neck yourself',
            // Spam/Scam keywords
            'free nitro', 'discord nitro free', 'steam gift',
            'claim your prize', 'you have been selected',
            'click here to claim', 'congratulations you won'
        ],
        
        // Banned regex patterns
        bannedPatterns: [
            // AI manipulation attempts - trying to get source code
            'show\\s*(me)?\\s*(the)?\\s*(source)?\\s*code',
            'give\\s*(me)?\\s*(the)?\\s*(source)?\\s*code',
            'paste\\s*(the)?\\s*code',
            'send\\s*(me)?\\s*(the)?\\s*code',
            'share\\s*(the)?\\s*(source)?\\s*code',
            'reveal\\s*(the)?\\s*code',
            'display\\s*(the)?\\s*code',
            'print\\s*(the)?\\s*code',
            'output\\s*(the)?\\s*code',
            'dump\\s*(the)?\\s*code',
            'leak\\s*(the)?\\s*code',
            'expose\\s*(the)?\\s*code',
            
            // AI manipulation - trying to get API keys/secrets
            'api\\s*key',
            'api\\s*token',
            'api\\s*secret',
            'show\\s*(me)?\\s*(the)?\\s*token',
            'give\\s*(me)?\\s*(the)?\\s*key',
            'what\\s*is\\s*(the)?\\s*password',
            'database\\s*(password|credentials)',
            'admin\\s*password',
            'secret\\s*key',
            'private\\s*key',
            'encryption\\s*key',
            
            // AI manipulation - prompt injection attempts
            'ignore\\s*(previous|all|your)\\s*instructions',
            'disregard\\s*(previous|all|your)\\s*instructions',
            'forget\\s*(previous|all|your)\\s*instructions',
            'override\\s*(your)?\\s*instructions',
            'bypass\\s*(your)?\\s*(rules|instructions|restrictions)',
            'pretend\\s*(you\\s*are|to\\s*be)\\s*(a|an)?',
            'act\\s*as\\s*(if|a|an)',
            'role\\s*?play\\s*as',
            'you\\s*are\\s*now\\s*(a|an)?',
            'new\\s*instructions',
            'system\\s*prompt',
            'developer\\s*mode',
            'jailbreak',
            'dan\\s*mode',
            'do\\s*anything\\s*now',
            
            // AI manipulation - trying to extract internal info
            'what\\s*(are)?\\s*your\\s*instructions',
            'show\\s*(me)?\\s*your\\s*prompt',
            'reveal\\s*your\\s*prompt',
            'what\\s*were\\s*you\\s*told',
            'what\\s*is\\s*your\\s*system\\s*(prompt|message)',
            'repeat\\s*(your)?\\s*instructions',
            'print\\s*(your)?\\s*instructions',
            
            // AI manipulation - code extraction
            'write\\s*(me)?\\s*(a|the)?\\s*full\\s*(source)?\\s*code',
            'generate\\s*(the)?\\s*(full|complete)?\\s*code',
            'create\\s*(a)?\\s*copy\\s*of\\s*(the)?\\s*plugin',
            'recreate\\s*(the)?\\s*plugin',
            'reverse\\s*engineer',
            'decompile',
            'give\\s*(me)?\\s*(the)?\\s*implementation',
            'show\\s*(me)?\\s*(the)?\\s*implementation',
            'class\\s*file',
            'java\\s*file',
            'kotlin\\s*file',
            
            // Scam patterns
            'free\\s*(discord)?\\s*nitro',
            '@everyone\\s*(check|click|free)',
            'steam\\s*community\\s*gift',
            'claim\\s*(your)?\\s*(free)?\\s*(gift|nitro|prize)',
            
            // Spam patterns
            '(.)\\1{10,}',  // Repeated characters (11+ times)
            '(\\b\\w+\\b)\\s+\\1\\s+\\1\\s+\\1'  // Same word repeated 4+ times
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
