/**
 * Knowledge Base Loader
 * Loads and indexes plugin documentation from folders
 */

const fs = require('fs');
const path = require('path');
const { glob } = require('glob');
const config = require('./config');

class KnowledgeBase {
    constructor() {
        this.documents = new Map(); // pluginName -> array of documents
        this.basePath = path.resolve(__dirname, '../docs'); // Points to discord_bot/docs folder
    }

    /**
     * Initialize the knowledge base by loading all plugin documentation
     */
    async initialize() {
        console.log('📚 Initializing Knowledge Base...');
        
        for (const [pluginKey, pluginConfig] of Object.entries(config.plugins)) {
            await this.loadPluginDocs(pluginKey, pluginConfig);
        }
        
        console.log('✅ Knowledge Base initialized successfully!');
        this.printStats();
    }

    /**
     * Load documentation for a specific plugin
     */
    async loadPluginDocs(pluginKey, pluginConfig) {
        const pluginPath = path.join(this.basePath, pluginConfig.folder);
        
        if (!fs.existsSync(pluginPath)) {
            console.warn(`⚠️ Plugin folder not found: ${pluginPath}`);
            this.documents.set(pluginKey, []);
            return;
        }

        const documents = [];
        const extensions = config.knowledgeBase.supportedExtensions.join(',');
        const pattern = `**/*{${extensions}}`;
        
        try {
            const files = await glob(pattern, {
                cwd: pluginPath,
                nodir: true,
                ignore: config.knowledgeBase.excludePatterns
            });

            for (const file of files) {
                const fullPath = path.join(pluginPath, file);
                const doc = await this.loadFile(fullPath, file, pluginConfig);
                if (doc) {
                    documents.push(doc);
                }
            }

            this.documents.set(pluginKey, documents);
            console.log(`📁 Loaded ${documents.length} files for ${pluginConfig.displayName}`);
        } catch (error) {
            console.error(`❌ Error loading docs for ${pluginConfig.displayName}:`, error.message);
            this.documents.set(pluginKey, []);
        }
    }

    /**
     * Load and parse a single file
     */
    async loadFile(fullPath, relativePath, pluginConfig) {
        try {
            const stats = fs.statSync(fullPath);
            
            // Skip files that are too large
            if (stats.size > config.knowledgeBase.maxFileSize) {
                return null;
            }

            const content = fs.readFileSync(fullPath, 'utf-8');
            const extension = path.extname(fullPath).toLowerCase();
            
            return {
                path: relativePath,
                fullPath: fullPath,
                plugin: pluginConfig.name,
                type: this.getFileType(extension),
                content: content,
                summary: this.generateSummary(content, relativePath),
                keywords: this.extractKeywords(content, relativePath)
            };
        } catch (error) {
            console.warn(`⚠️ Could not load file ${fullPath}:`, error.message);
            return null;
        }
    }

    /**
     * Get file type based on extension
     */
    getFileType(extension) {
        const types = {
            '.md': 'documentation',
            '.yml': 'config',
            '.yaml': 'config',
            '.txt': 'text',
            '.kt': 'source',
            '.java': 'source'
        };
        return types[extension] || 'unknown';
    }

    /**
     * Generate a summary of the file content
     */
    generateSummary(content, filePath) {
        // For markdown files, extract the first heading and paragraph
        if (filePath.endsWith('.md')) {
            const lines = content.split('\n').filter(l => l.trim());
            const heading = lines.find(l => l.startsWith('#'));
            const firstPara = lines.find(l => !l.startsWith('#') && l.length > 20);
            return `${heading || ''}\n${firstPara || ''}`.trim().substring(0, 500);
        }
        
        // For config files, extract comments
        if (filePath.endsWith('.yml') || filePath.endsWith('.yaml')) {
            const comments = content.match(/^#.*/gm) || [];
            return comments.slice(0, 5).join('\n').substring(0, 500);
        }
        
        // For source files, extract class/function definitions
        if (filePath.endsWith('.kt') || filePath.endsWith('.java')) {
            const classMatch = content.match(/(?:class|interface|object)\s+\w+/g) || [];
            const funcMatch = content.match(/(?:fun|function)\s+\w+/g) || [];
            return [...classMatch, ...funcMatch].slice(0, 10).join(', ').substring(0, 500);
        }
        
        return content.substring(0, 500);
    }

    /**
     * Extract keywords from content
     */
    extractKeywords(content, filePath) {
        const keywords = new Set();
        
        // Add filename parts
        const fileName = path.basename(filePath, path.extname(filePath));
        fileName.split(/[-_.]/).forEach(part => {
            if (part.length > 2) keywords.add(part.toLowerCase());
        });
        
        // Extract words from headings (markdown)
        const headings = content.match(/^#+\s+(.+)$/gm) || [];
        headings.forEach(h => {
            h.replace(/^#+\s+/, '').split(/\s+/).forEach(word => {
                if (word.length > 3) keywords.add(word.toLowerCase().replace(/[^a-z0-9]/g, ''));
            });
        });
        
        // Extract common technical terms
        const technicalTerms = content.match(/`([^`]+)`/g) || [];
        technicalTerms.forEach(term => {
            const cleaned = term.replace(/`/g, '').toLowerCase();
            if (cleaned.length > 2 && cleaned.length < 30) keywords.add(cleaned);
        });
        
        return Array.from(keywords);
    }

    /**
     * Search for relevant documents for a query
     */
    searchDocuments(pluginKey, query) {
        const docs = this.documents.get(pluginKey) || [];
        const queryLower = query.toLowerCase();
        const queryWords = queryLower.split(/\s+/).filter(w => w.length > 2);
        
        // Score each document
        const scored = docs.map(doc => {
            let score = 0;
            
            // Check keywords
            for (const word of queryWords) {
                if (doc.keywords.some(k => k.includes(word) || word.includes(k))) {
                    score += 10;
                }
            }
            
            // Check content
            for (const word of queryWords) {
                const matches = (doc.content.toLowerCase().match(new RegExp(word, 'g')) || []).length;
                score += Math.min(matches, 5) * 2;
            }
            
            // Boost documentation files
            if (doc.type === 'documentation') score *= 1.5;
            
            // Boost FAQ files
            if (doc.path.toLowerCase().includes('faq')) score *= 2;
            
            // Boost knowledge base files
            if (doc.path.toLowerCase().includes('knowledge')) score *= 2;
            
            return { doc, score };
        });
        
        // Sort by score and return top results
        return scored
            .filter(s => s.score > 0)
            .sort((a, b) => b.score - a.score)
            .slice(0, 5)
            .map(s => s.doc);
    }

    /**
     * Get all documents for a plugin
     */
    getPluginDocuments(pluginKey) {
        return this.documents.get(pluginKey) || [];
    }

    /**
     * Get context for AI from relevant documents
     */
    getContextForQuery(pluginKey, query) {
        const relevantDocs = this.searchDocuments(pluginKey, query);
        
        if (relevantDocs.length === 0) {
            // If no relevant docs found, return general plugin docs
            const allDocs = this.getPluginDocuments(pluginKey);
            const docFiles = allDocs.filter(d => d.type === 'documentation');
            return docFiles.slice(0, 3).map(d => ({
                source: d.path,
                content: d.content.substring(0, 3000)
            }));
        }
        
        return relevantDocs.map(doc => ({
            source: doc.path,
            content: doc.content.substring(0, 3000)
        }));
    }

    /**
     * Print statistics about loaded documents
     */
    printStats() {
        console.log('\n📊 Knowledge Base Statistics:');
        for (const [pluginKey, docs] of this.documents.entries()) {
            const pluginConfig = config.plugins[pluginKey];
            const byType = {};
            docs.forEach(d => {
                byType[d.type] = (byType[d.type] || 0) + 1;
            });
            console.log(`   ${pluginConfig.displayName}: ${docs.length} files`);
            Object.entries(byType).forEach(([type, count]) => {
                console.log(`      - ${type}: ${count}`);
            });
        }
        console.log('');
    }

    /**
     * Reload the knowledge base
     */
    async reload() {
        this.documents.clear();
        await this.initialize();
    }
}

module.exports = new KnowledgeBase();
