/**
 * Moderation Database Module
 * Handles SQLite storage for user warnings, kicks, and bans
 */

const sqlite3 = require('sqlite3').verbose();
const path = require('path');

class ModerationDB {
    constructor() {
        this.db = null;
    }

    /**
     * Initialize the database and create tables
     */
    initialize() {
        return new Promise((resolve, reject) => {
            const dbPath = path.join(__dirname, '..', 'moderation.db');
            
            this.db = new sqlite3.Database(dbPath, (err) => {
                if (err) {
                    console.error('❌ Error opening moderation database:', err.message);
                    reject(err);
                    return;
                }
                
                console.log('📂 Connected to moderation database');
                this.createTables().then(resolve).catch(reject);
            });
        });
    }

    /**
     * Create necessary tables
     */
    createTables() {
        return new Promise((resolve, reject) => {
            const queries = [
                // Users table - tracks basic user info
                `CREATE TABLE IF NOT EXISTS users (
                    user_id TEXT PRIMARY KEY,
                    username TEXT,
                    discriminator TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )`,
                
                // Warnings table
                `CREATE TABLE IF NOT EXISTS warnings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    guild_id TEXT NOT NULL,
                    moderator_id TEXT NOT NULL,
                    reason TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    active INTEGER DEFAULT 1,
                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )`,
                
                // Kicks table
                `CREATE TABLE IF NOT EXISTS kicks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    guild_id TEXT NOT NULL,
                    moderator_id TEXT NOT NULL,
                    reason TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )`,
                
                // Bans table
                `CREATE TABLE IF NOT EXISTS bans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    guild_id TEXT NOT NULL,
                    moderator_id TEXT NOT NULL,
                    reason TEXT,
                    duration INTEGER,
                    expires_at DATETIME,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    active INTEGER DEFAULT 1,
                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )`,
                
                // Moderation log table
                `CREATE TABLE IF NOT EXISTS mod_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    action_type TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    guild_id TEXT NOT NULL,
                    moderator_id TEXT NOT NULL,
                    reason TEXT,
                    details TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )`,

                // Create indexes for faster queries
                `CREATE INDEX IF NOT EXISTS idx_warnings_user ON warnings(user_id, guild_id)`,
                `CREATE INDEX IF NOT EXISTS idx_bans_user ON bans(user_id, guild_id)`,
                `CREATE INDEX IF NOT EXISTS idx_mod_log_user ON mod_log(user_id, guild_id)`
            ];

            this.db.serialize(() => {
                queries.forEach(query => {
                    this.db.run(query, (err) => {
                        if (err) {
                            console.error('❌ Error creating table:', err.message);
                        }
                    });
                });
                
                console.log('✅ Moderation tables created/verified');
                resolve();
            });
        });
    }

    /**
     * Ensure user exists in database
     */
    async ensureUser(user) {
        return new Promise((resolve, reject) => {
            const query = `
                INSERT INTO users (user_id, username, discriminator, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    username = excluded.username,
                    discriminator = excluded.discriminator,
                    updated_at = CURRENT_TIMESTAMP
            `;
            
            this.db.run(query, [user.id, user.username, user.discriminator || '0'], (err) => {
                if (err) reject(err);
                else resolve();
            });
        });
    }

    /**
     * Add a warning to a user
     */
    async addWarning(userId, guildId, moderatorId, reason) {
        return new Promise((resolve, reject) => {
            const query = `
                INSERT INTO warnings (user_id, guild_id, moderator_id, reason)
                VALUES (?, ?, ?, ?)
            `;
            
            this.db.run(query, [userId, guildId, moderatorId, reason], function(err) {
                if (err) reject(err);
                else resolve({ id: this.lastID });
            });
        });
    }

    /**
     * Get active warnings for a user
     */
    async getWarnings(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                SELECT * FROM warnings 
                WHERE user_id = ? AND guild_id = ? AND active = 1
                ORDER BY created_at DESC
            `;
            
            this.db.all(query, [userId, guildId], (err, rows) => {
                if (err) reject(err);
                else resolve(rows || []);
            });
        });
    }

    /**
     * Get warning count for a user
     */
    async getWarningCount(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                SELECT COUNT(*) as count FROM warnings 
                WHERE user_id = ? AND guild_id = ? AND active = 1
            `;
            
            this.db.get(query, [userId, guildId], (err, row) => {
                if (err) reject(err);
                else resolve(row?.count || 0);
            });
        });
    }

    /**
     * Clear warnings for a user
     */
    async clearWarnings(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                UPDATE warnings SET active = 0 
                WHERE user_id = ? AND guild_id = ?
            `;
            
            this.db.run(query, [userId, guildId], function(err) {
                if (err) reject(err);
                else resolve({ cleared: this.changes });
            });
        });
    }

    /**
     * Remove a specific warning by ID
     */
    async removeWarning(warningId) {
        return new Promise((resolve, reject) => {
            const query = `UPDATE warnings SET active = 0 WHERE id = ?`;
            
            this.db.run(query, [warningId], function(err) {
                if (err) reject(err);
                else resolve({ success: this.changes > 0 });
            });
        });
    }

    /**
     * Log a kick
     */
    async addKick(userId, guildId, moderatorId, reason) {
        return new Promise((resolve, reject) => {
            const query = `
                INSERT INTO kicks (user_id, guild_id, moderator_id, reason)
                VALUES (?, ?, ?, ?)
            `;
            
            this.db.run(query, [userId, guildId, moderatorId, reason], function(err) {
                if (err) reject(err);
                else resolve({ id: this.lastID });
            });
        });
    }

    /**
     * Add a ban
     */
    async addBan(userId, guildId, moderatorId, reason, durationMinutes = null) {
        return new Promise((resolve, reject) => {
            let expiresAt = null;
            if (durationMinutes) {
                const expiry = new Date();
                expiry.setMinutes(expiry.getMinutes() + durationMinutes);
                expiresAt = expiry.toISOString();
            }
            
            const query = `
                INSERT INTO bans (user_id, guild_id, moderator_id, reason, duration, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
            `;
            
            this.db.run(query, [userId, guildId, moderatorId, reason, durationMinutes, expiresAt], function(err) {
                if (err) reject(err);
                else resolve({ id: this.lastID, expiresAt });
            });
        });
    }

    /**
     * Check if user is banned
     */
    async isUserBanned(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                SELECT * FROM bans 
                WHERE user_id = ? AND guild_id = ? AND active = 1
                AND (expires_at IS NULL OR expires_at > datetime('now'))
                ORDER BY created_at DESC
                LIMIT 1
            `;
            
            this.db.get(query, [userId, guildId], (err, row) => {
                if (err) reject(err);
                else resolve(row || null);
            });
        });
    }

    /**
     * Remove a ban (unban)
     */
    async removeBan(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                UPDATE bans SET active = 0 
                WHERE user_id = ? AND guild_id = ? AND active = 1
            `;
            
            this.db.run(query, [userId, guildId], function(err) {
                if (err) reject(err);
                else resolve({ success: this.changes > 0 });
            });
        });
    }

    /**
     * Get expired bans that need to be lifted
     */
    async getExpiredBans() {
        return new Promise((resolve, reject) => {
            const query = `
                SELECT * FROM bans 
                WHERE active = 1 
                AND expires_at IS NOT NULL 
                AND expires_at <= datetime('now')
            `;
            
            this.db.all(query, [], (err, rows) => {
                if (err) reject(err);
                else resolve(rows || []);
            });
        });
    }

    /**
     * Log a moderation action
     */
    async logAction(actionType, userId, guildId, moderatorId, reason, details = null) {
        return new Promise((resolve, reject) => {
            const query = `
                INSERT INTO mod_log (action_type, user_id, guild_id, moderator_id, reason, details)
                VALUES (?, ?, ?, ?, ?, ?)
            `;
            
            this.db.run(query, [actionType, userId, guildId, moderatorId, reason, details], function(err) {
                if (err) reject(err);
                else resolve({ id: this.lastID });
            });
        });
    }

    /**
     * Get moderation history for a user
     */
    async getUserHistory(userId, guildId) {
        return new Promise((resolve, reject) => {
            const query = `
                SELECT * FROM mod_log 
                WHERE user_id = ? AND guild_id = ?
                ORDER BY created_at DESC
                LIMIT 50
            `;
            
            this.db.all(query, [userId, guildId], (err, rows) => {
                if (err) reject(err);
                else resolve(rows || []);
            });
        });
    }

    /**
     * Close database connection
     */
    close() {
        return new Promise((resolve, reject) => {
            if (this.db) {
                this.db.close((err) => {
                    if (err) reject(err);
                    else {
                        console.log('📂 Moderation database connection closed');
                        resolve();
                    }
                });
            } else {
                resolve();
            }
        });
    }
}

module.exports = new ModerationDB();
