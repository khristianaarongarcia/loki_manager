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
     * First checks if the question relates to anything in the plugin documentation,
     * then uses AI for final classification
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

        console.log(`🤖 [AI Check] Analyzing: "${text.substring(0, 60)}${text.length > 60 ? '...' : ''}"`);

        try {
            // Step 1: Search the knowledge base for relevant documentation
            const relevantDocs = knowledgeBase.searchDocuments(pluginKey, text);
            const hasRelevantDocs = relevantDocs.length > 0;
            
            console.log(`📚 [Docs Check] Found ${relevantDocs.length} relevant doc(s) for query`);
            if (relevantDocs.length > 0) {
                console.log(`   Matching files: ${relevantDocs.slice(0, 3).map(d => d.path).join(', ')}`);
            }

            // Step 2: Extract keywords from docs to provide context to AI
            let docContext = '';
            if (hasRelevantDocs) {
                // Get keywords and topics from relevant docs
                const keywords = new Set();
                const topics = [];
                
                relevantDocs.forEach(doc => {
                    doc.keywords.forEach(k => keywords.add(k));
                    // Extract first heading or filename as topic
                    const topicMatch = doc.content.match(/^#\s+(.+)$/m);
                    if (topicMatch) {
                        topics.push(topicMatch[1]);
                    } else {
                        topics.push(doc.path);
                    }
                });
                
                docContext = `\n\nRelevant plugin documentation topics found:\n- ${topics.slice(0, 5).join('\n- ')}\nRelated keywords: ${Array.from(keywords).slice(0, 20).join(', ')}`;
            }

            // Step 3: Get all available topics from the plugin docs for context
            const allDocs = knowledgeBase.getPluginDocuments(pluginKey);
            const allTopics = allDocs.slice(0, 15).map(d => {
                const topicMatch = d.content.match(/^#\s+(.+)$/m);
                return topicMatch ? topicMatch[1] : d.path.replace(/\.[^.]+$/, '');
            });

            // Step 4: Use AI to classify with documentation context
            const systemPrompt = `You are a classifier for a Discord support bot for the Minecraft plugin "${pluginConfig.displayName}".

Your task: Determine if a message is a genuine support question about this plugin. Respond with ONLY "yes" or "no" (lowercase, nothing else).

The plugin documentation covers these topics:
${allTopics.join(', ')}
${docContext}

Answer "yes" if:
- The message asks about ANY topic covered in the plugin documentation
- It's a question about how to use, configure, or troubleshoot the plugin
- It asks about features, commands, permissions, GUIs, or settings
- It reports a bug, error, or issue that could relate to the plugin
- It asks about compatibility, updates, or plugin functionality
- The question matches or relates to keywords/topics from the documentation

Answer "no" if:
- It's casual chat, greetings, or social conversation ("hey", "thanks", "lol")
- It's completely off-topic and unrelated to ANY plugin documentation topic
- It's a simple acknowledgment or reaction
- It's spam or meaningless text
- It's asking about something entirely different (other games, general chat)

Be INCLUSIVE - if the question MIGHT be about the plugin or its features, answer "yes".`;

            const completion = await this.client.chat.completions.create({
                model: 'llama-3.1-8b-instant',
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: `Message to classify: "${text}"` }
                ],
                max_tokens: 5,
                temperature: 0.1
            });

            const response = completion.choices[0]?.message?.content?.trim().toLowerCase();
            const isQuestion = response === 'yes';
            
            console.log(`🤖 [AI Check] Result: ${response} → ${isQuestion ? '✅ Is a plugin question' : '❌ Not a plugin question'}`);
            
            // Additional boost: If we found highly relevant docs, lean towards yes
            if (!isQuestion && hasRelevantDocs && relevantDocs.length >= 2) {
                console.log(`🤖 [AI Check] Override: Found ${relevantDocs.length} relevant docs, treating as plugin question`);
                return true;
            }
            
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
