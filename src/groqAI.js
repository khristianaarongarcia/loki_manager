/**
 * Groq AI Integration
 * Handles question analysis and answer generation using Groq API
 */

const fs = require('fs');
const path = require('path');
const Groq = require('groq-sdk');
const config = require('./config');
const knowledgeBase = require('./knowledgeBase');

class GroqAI {
    constructor() {
        this.client = null;
        this.instructionsTemplate = '';
    }

    /**
     * Initialize the Groq client
     */
    initialize() {
        if (!process.env.GROQ_API_KEY) {
            throw new Error('GROQ_API_KEY environment variable is not set');
        }

        this.client = new Groq({
            apiKey: process.env.GROQ_API_KEY
        });

        // Load instructions template
        const instructionsPath = path.join(__dirname, 'instructions.txt');
        this.instructionsTemplate = fs.readFileSync(instructionsPath, 'utf-8');
        console.log('📝 Loaded AI instructions from file');

        console.log('🤖 Groq AI initialized successfully!');
    }

    /**
     * Analyze if a message is a question that needs answering
     */
    async isQuestion(message) {
        // Quick checks first
        const text = message.trim();
        
        // Too short to be a real question
        if (text.length < config.bot.minQuestionLength) {
            return false;
        }

        // Common question indicators
        const questionIndicators = [
            /\?$/,                              // Ends with question mark
            /^(how|what|why|when|where|can|does|is|are|will|should|could|would|do)/i,
            /help/i,
            /not working/i,
            /error/i,
            /issue/i,
            /problem/i,
            /doesn't work/i,
            /can't|cannot/i,
            /how to|how do/i,
            /what is|what are/i,
            /configure|setup|install/i
        ];

        return questionIndicators.some(pattern => pattern.test(text));
    }

    /**
     * Check if a message is actually a plugin-specific support question
     * This is more strict than isQuestion() to avoid responding to casual chat
     */
    isPluginSpecificQuestion(message, pluginKey) {
        const text = message.trim().toLowerCase();
        const pluginConfig = config.plugins[pluginKey];
        
        if (!pluginConfig) return false;
        
        // Plugin-specific terms that indicate a real support question
        const pluginTerms = pluginConfig.keywords || [];
        
        // General support/technical terms
        const technicalTerms = [
            // Configuration & Setup
            'config', 'configuration', 'configure', 'setup', 'install', 'setting', 'settings',
            'enable', 'disable', 'permission', 'permissions', 'perm', 'perms',
            
            // Commands & Usage
            'command', 'commands', 'cmd', '//', 'placeholder', 'placeholders',
            'argument', 'arguments', 'syntax', 'usage', 'use',
            
            // Problems & Errors
            'error', 'bug', 'issue', 'problem', 'broken', 'crash', 'exception',
            'not working', 'doesn\'t work', 'won\'t work', 'failed', 'failing',
            'can\'t', 'cannot', 'unable', 'stuck',
            
            // Technical terms
            'plugin', 'server', 'spigot', 'paper', 'bukkit', 'minecraft',
            'yml', 'yaml', 'file', 'folder', 'directory', 'path',
            'reload', 'restart', 'update', 'version', 'api',
            'database', 'mysql', 'sqlite', 'storage', 'data',
            'gui', 'menu', 'inventory', 'item', 'items',
            'player', 'players', 'world', 'worlds',
            'event', 'events', 'hook', 'hooks', 'integration',
            
            // Questions
            'how do i', 'how can i', 'how to', 'what is', 'what are',
            'where is', 'where can', 'why is', 'why does', 'why won\'t',
            'can i', 'is it possible', 'does it', 'will it',
            'help me', 'need help', 'please help', 'anyone know'
        ];
        
        // Check for plugin-specific keywords
        const hasPluginKeyword = pluginTerms.some(term => text.includes(term.toLowerCase()));
        
        // Check for technical/support terms
        const hasTechnicalTerm = technicalTerms.some(term => text.includes(term));
        const matchedTechnicalTerms = technicalTerms.filter(term => text.includes(term));
        
        // Check for question patterns with technical context
        const hasQuestionMark = text.includes('?');
        const startsWithQuestion = /^(how|what|why|when|where|can|does|is|are|will|should|could|would|do|anyone|has anyone)/i.test(text);
        
        // Logging for debugging
        console.log(`🔍 [Pattern Check] Message: "${text.substring(0, 50)}${text.length > 50 ? '...' : ''}"`);
        console.log(`   Plugin: ${pluginKey}, Plugin Keywords: [${pluginTerms.join(', ')}]`);
        console.log(`   Has Plugin Keyword: ${hasPluginKeyword}`);
        console.log(`   Has Technical Term: ${hasTechnicalTerm} (${matchedTechnicalTerms.join(', ') || 'none'})`);
        console.log(`   Has Question Mark: ${hasQuestionMark}, Starts With Question: ${startsWithQuestion}`);
        
        // Must have either:
        // 1. Plugin keyword + question indicator
        // 2. Technical term + question indicator
        // 3. Plugin keyword + technical term
        if (hasPluginKeyword && (hasQuestionMark || startsWithQuestion || hasTechnicalTerm)) {
            console.log(`   ✅ Pattern Match: Plugin keyword + question/technical term`);
            return true;
        }
        
        if (hasTechnicalTerm && (hasQuestionMark || startsWithQuestion)) {
            console.log(`   ✅ Pattern Match: Technical term + question indicator`);
            return true;
        }
        
        if (hasPluginKeyword && hasTechnicalTerm) {
            console.log(`   ✅ Pattern Match: Plugin keyword + technical term`);
            return true;
        }
        
        // Explicit help requests
        if (/\b(help|support|assist|question)\b/i.test(text) && (hasPluginKeyword || hasTechnicalTerm)) {
            console.log(`   ✅ Pattern Match: Help request + plugin/technical term`);
            return true;
        }
        
        console.log(`   ❌ Pattern Match: No match found`);
        return false;
    }

    /**
     * Use AI to determine if a message is a plugin-specific support question
     * More accurate than pattern matching but uses API calls
     */
    async isPluginQuestionAI(message, pluginKey) {
        const pluginConfig = config.plugins[pluginKey];
        if (!pluginConfig) return false;

        const text = message.trim();
        
        // Skip very short messages
        if (text.length < 10) {
            console.log(`🤖 [AI Check] Skipped - message too short (${text.length} chars)`);
            return false;
        }

        console.log(`🤖 [AI Check] Classifying: "${text.substring(0, 60)}${text.length > 60 ? '...' : ''}"`);

        try {
            const systemPrompt = `You are a classifier that determines if a Discord message is a genuine support question about a Minecraft plugin called "${pluginConfig.displayName}".

Your task: Respond with ONLY "yes" or "no" (lowercase, nothing else).

Answer "yes" if the message is:
- A question about how to use, configure, or troubleshoot the plugin
- A request for help with features, commands, permissions, or settings
- Reporting a bug, error, or issue with the plugin
- Asking about plugin compatibility, updates, or functionality

Answer "no" if the message is:
- Casual chat, greetings, or social conversation (e.g., "hey", "thanks", "lol", "nice")
- Off-topic discussion not related to plugin support
- Simple acknowledgments or reactions
- Questions about other topics unrelated to Minecraft plugins
- Spam or meaningless text
- Just expressing emotions or opinions without asking for help`;

            const completion = await this.client.chat.completions.create({
                model: 'llama-3.1-8b-instant', // Use faster model for classification
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: `Message to classify: "${text}"` }
                ],
                max_tokens: 5,
                temperature: 0.1 // Low temperature for consistent classification
            });

            const response = completion.choices[0]?.message?.content?.trim().toLowerCase();
            const isQuestion = response === 'yes';
            
            console.log(`🤖 [AI Check] Result: ${response} → ${isQuestion ? '✅ Is a plugin question' : '❌ Not a plugin question'}`);
            
            return isQuestion;
        } catch (error) {
            console.error('❌ AI classification error:', error.message);
            console.log('🤖 [AI Check] Falling back to pattern matching...');
            // Fall back to pattern matching if AI fails
            return this.isPluginSpecificQuestion(message, pluginKey);
        }
    }

    /**
     * Detect which plugin the question is about
     */
    detectPlugin(message, channelId) {
        const messageLower = message.toLowerCase();
        
        // First check if the channel is plugin-specific
        for (const [pluginKey, pluginConfig] of Object.entries(config.plugins)) {
            if (Object.values(pluginConfig.channels).includes(channelId)) {
                return pluginKey;
            }
        }
        
        // Then check message content for keywords
        for (const [pluginKey, pluginConfig] of Object.entries(config.plugins)) {
            if (pluginConfig.keywords.some(keyword => messageLower.includes(keyword))) {
                return pluginKey;
            }
        }
        
        return null;
    }

    /**
     * Get the appropriate channel for a plugin question
     */
    getPluginForChannel(channelId) {
        for (const [pluginKey, pluginConfig] of Object.entries(config.plugins)) {
            if (Object.values(pluginConfig.channels).includes(channelId)) {
                return pluginKey;
            }
        }
        return null;
    }

    /**
     * Check if a channel is a valid support channel
     */
    isValidSupportChannel(channelId) {
        for (const pluginConfig of Object.values(config.plugins)) {
            if (Object.values(pluginConfig.channels).includes(channelId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate an answer for a plugin question
     */
    async generateAnswer(question, pluginKey, recentMessages = []) {
        const pluginConfig = config.plugins[pluginKey];
        
        if (!pluginConfig) {
            return {
                success: false,
                message: "I couldn't determine which plugin you're asking about."
            };
        }

        // Get relevant context from knowledge base
        const context = knowledgeBase.getContextForQuery(pluginKey, question);
        
        if (context.length === 0) {
            return {
                success: false,
                message: `I don't have any documentation for ${pluginConfig.displayName} yet. Please wait for the documentation to be added.`
            };
        }

        // Build context string
        const contextString = context.map(c => 
            `--- Source: ${c.source} ---\n${c.content}`
        ).join('\n\n');

        // Build recent messages context
        let recentContext = '';
        if (recentMessages.length > 0) {
            recentContext = '\n\nRecent conversation context:\n' + 
                recentMessages.map(m => `${m.author}: ${m.content}`).join('\n');
        }

        // Build the prompt from instructions file
        const systemPrompt = this.instructionsTemplate
            .replace('{{PLUGIN_NAME}}', pluginConfig.displayName)
            .replace('{{DOCUMENTATION}}', contextString);

        const userPrompt = `${recentContext}

User's question: ${question}

Please provide a helpful answer based on the documentation above.`;

        try {
            const completion = await this.client.chat.completions.create({
                model: config.groq.model,
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: userPrompt }
                ],
                max_tokens: config.groq.maxTokens,
                temperature: config.groq.temperature
            });

            const answer = completion.choices[0]?.message?.content;
            
            if (!answer) {
                return {
                    success: false,
                    message: "I encountered an error generating a response. Please try again.",
                    unanswerable: true
                };
            }

            // Check if the AI couldn't find a proper answer
            const unanswerableIndicators = [
                /i don't have (enough )?information/i,
                /not (found|mentioned|covered|documented)/i,
                /i (cannot|can't|couldn't) find/i,
                /no (information|documentation|data) (available|found)/i,
                /i'm (not sure|unsure|unable)/i,
                /beyond (my|the) (knowledge|documentation)/i,
                /please (contact|reach out|ask)/i,
                /i don't know/i,
                /this (is not|isn't) (covered|documented)/i
            ];

            const isUnanswerable = unanswerableIndicators.some(pattern => pattern.test(answer));

            return {
                success: true,
                message: answer,
                plugin: pluginConfig.displayName,
                sourcesUsed: context.map(c => c.source),
                unanswerable: isUnanswerable
            };
        } catch (error) {
            console.error('❌ Groq API Error:', error.message);
            return {
                success: false,
                message: "I encountered an error while processing your question. Please try again later.",
                unanswerable: true
            };
        }
    }

    /**
     * Generate a redirect message when user asks in wrong channel
     */
    generateRedirectMessage(detectedPlugin, currentChannelId) {
        const pluginConfig = config.plugins[detectedPlugin];
        
        if (!pluginConfig) {
            return null;
        }

        const correctChannels = pluginConfig.channels;
        
        return `👋 Hey there! It looks like you have a question about **${pluginConfig.displayName}**.

Please ask your question in one of these channels:
• General Chat: <#${correctChannels.general}>
• Support Forum: <#${correctChannels.forum}>

I'll be happy to help you there! 🎮`;
    }

    /**
     * Get list of all valid support channel IDs
     */
    getAllSupportChannelIds() {
        const channelIds = [];
        for (const pluginConfig of Object.values(config.plugins)) {
            channelIds.push(...Object.values(pluginConfig.channels));
        }
        return channelIds;
    }
}

module.exports = new GroqAI();
