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
