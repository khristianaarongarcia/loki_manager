/**
 * GitHub Auto-Update Checker
 * Periodically checks for new commits and can auto-pull updates
 */

const { exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const config = require('./config');

class Updater {
    constructor() {
        this.checkInterval = null;
        this.repoPath = null;
        this.lastChecked = null;
        this.updateAvailable = false;
        this.onUpdateCallback = null;
        this.enabled = true;
    }

    /**
     * Initialize the updater with optional callback
     * @param {Function} onUpdate - Callback when update is available (receives commit info)
     */
    async initialize(onUpdate = null) {
        this.onUpdateCallback = onUpdate;
        
        // Determine repo path
        if (config.git?.repoPath) {
            this.repoPath = config.git.repoPath;
        } else {
            this.repoPath = path.join(__dirname, '..');
        }
        
        // Check if git repo exists
        const isGitRepo = await this.verifyGitRepo();
        
        if (!isGitRepo) {
            console.log('⚠️ Git repository not found, attempting to initialize...');
            const initialized = await this.initializeGitRepo();
            
            if (initialized) {
                this.enabled = true;
                console.log(`✅ Git repository initialized successfully`);
            } else {
                this.enabled = false;
                console.log('⚠️ Could not initialize git repository - update checker disabled');
            }
        } else {
            this.enabled = true;
            console.log(`🔄 GitHub updater initialized (repo: ${this.repoPath})`);
        }
    }

    /**
     * Initialize git repo by cloning or setting up remote
     */
    async initializeGitRepo() {
        const repoUrl = config.git?.repoUrl || 'https://github.com/khristianaarongarcia/loki_manager.git';
        
        try {
            // Check if .git folder exists but is corrupted/incomplete
            const gitDir = path.join(this.repoPath, '.git');
            
            if (fs.existsSync(gitDir)) {
                // .git exists but repo check failed - try to fix
                console.log('🔧 Found .git folder, attempting to repair...');
                try {
                    await this.execCommand(`git remote set-url origin ${repoUrl}`);
                    await this.execCommand('git fetch origin');
                    return true;
                } catch (e) {
                    console.log('   Repair failed, will try git init...');
                }
            }
            
            // Initialize git in the directory
            console.log('📥 Initializing git repository...');
            await this.execCommand('git init');
            
            // Add remote
            console.log(`🔗 Adding remote: ${repoUrl}`);
            await this.execCommand(`git remote add origin ${repoUrl}`);
            
            // Fetch from remote
            console.log('📥 Fetching from remote...');
            await this.execCommand('git fetch origin');
            
            // Reset to match remote main branch
            console.log('🔄 Syncing with remote main branch...');
            await this.execCommand('git reset --hard origin/main');
            
            // Set tracking branch
            await this.execCommand('git branch --set-upstream-to=origin/main main');
            
            return true;
        } catch (error) {
            console.error('❌ Failed to initialize git repo:', error.message);
            return false;
        }
    }

    /**
     * Execute a shell command
     */
    execCommand(command) {
        return new Promise((resolve, reject) => {
            exec(command, { cwd: this.repoPath }, (error, stdout, stderr) => {
                if (error) {
                    reject(new Error(stderr || error.message));
                } else {
                    resolve(stdout.trim());
                }
            });
        });
    }

    /**
     * Verify the path is a git repository
     */
    async verifyGitRepo() {
        try {
            await this.execGit('rev-parse --git-dir');
            return true;
        } catch (error) {
            return false;
        }
    }

    /**
     * Execute a git command
     * @param {string} command - Git command to run
     * @returns {Promise<string>} - Command output
     */
    execGit(command) {
        return new Promise((resolve, reject) => {
            exec(`git ${command}`, { cwd: this.repoPath }, (error, stdout, stderr) => {
                if (error) {
                    reject(new Error(stderr || error.message));
                } else {
                    resolve(stdout.trim());
                }
            });
        });
    }

    /**
     * Fetch latest changes from remote
     */
    async fetchUpdates() {
        if (!this.enabled) return false;
        
        try {
            await this.execGit('fetch origin');
            return true;
        } catch (error) {
            console.error('❌ Failed to fetch updates:', error.message);
            return false;
        }
    }

    /**
     * Check if there are new commits available
     * @returns {Promise<{available: boolean, commits: number, latestMessage: string}>}
     */
    async checkForUpdates() {
        if (!this.enabled) {
            return { available: false, commits: 0, latestMessage: null, disabled: true };
        }
        
        try {
            // Fetch latest from remote
            await this.fetchUpdates();

            // Get current branch
            const branch = await this.execGit('rev-parse --abbrev-ref HEAD');

            // Count commits behind
            const behindCount = await this.execGit(`rev-list --count HEAD..origin/${branch}`);
            const commitsBehind = parseInt(behindCount, 10);

            this.lastChecked = new Date();
            this.updateAvailable = commitsBehind > 0;

            let result = {
                available: commitsBehind > 0,
                commits: commitsBehind,
                latestMessage: null,
                latestAuthor: null,
                latestDate: null
            };

            // Get latest commit info if updates available
            if (commitsBehind > 0) {
                try {
                    const latestCommit = await this.execGit(`log origin/${branch} -1 --format="%s|||%an|||%ar"`);
                    const [message, author, date] = latestCommit.split('|||');
                    result.latestMessage = message;
                    result.latestAuthor = author;
                    result.latestDate = date;
                } catch (e) {
                    // Ignore if can't get commit details
                }

                console.log(`📦 Update available! ${commitsBehind} new commit(s)`);
                
                // Trigger callback if set
                if (this.onUpdateCallback) {
                    this.onUpdateCallback(result);
                }
            }

            return result;
        } catch (error) {
            console.error('❌ Failed to check for updates:', error.message);
            return { available: false, commits: 0, latestMessage: null };
        }
    }

    /**
     * Pull the latest updates
     * @returns {Promise<{success: boolean, message: string}>}
     */
    async pullUpdates() {
        if (!this.enabled) {
            return { success: false, message: 'Update checker is disabled - git repo not found' };
        }
        
        try {
            const output = await this.execGit('pull origin main');
            console.log('✅ Updates pulled successfully');
            this.updateAvailable = false;
            return { success: true, message: output };
        } catch (error) {
            console.error('❌ Failed to pull updates:', error.message);
            return { success: false, message: error.message };
        }
    }

    /**
     * Get current local commit info
     */
    async getCurrentVersion() {
        if (!this.enabled) {
            return { hash: 'N/A', message: 'Git not configured', date: 'N/A' };
        }
        
        try {
            const hash = await this.execGit('rev-parse --short HEAD');
            const message = await this.execGit('log -1 --format="%s"');
            const date = await this.execGit('log -1 --format="%ar"');
            return { hash, message, date };
        } catch (error) {
            return { hash: 'unknown', message: 'unknown', date: 'unknown' };
        }
    }

    /**
     * Start periodic update checking
     * @param {number} intervalMinutes - Check interval in minutes (default: 30)
     */
    startAutoCheck(intervalMinutes = 30) {
        if (!this.enabled) {
            console.log('⚠️ Auto-update checker skipped - git repo not configured');
            return;
        }
        
        if (this.checkInterval) {
            clearInterval(this.checkInterval);
        }

        // Check immediately on start
        this.checkForUpdates();

        // Then check periodically
        this.checkInterval = setInterval(() => {
            this.checkForUpdates();
        }, intervalMinutes * 60 * 1000);

        console.log(`🔄 Auto-update checker started (checking every ${intervalMinutes} minutes)`);
    }

    /**
     * Check if updater is enabled
     */
    isEnabled() {
        return this.enabled;
    }

    /**
     * Stop periodic update checking
     */
    stopAutoCheck() {
        if (this.checkInterval) {
            clearInterval(this.checkInterval);
            this.checkInterval = null;
            console.log('🔄 Auto-update checker stopped');
        }
    }

    /**
     * Check if updates are currently available
     */
    hasUpdate() {
        return this.updateAvailable;
    }

    /**
     * Get last check time
     */
    getLastChecked() {
        return this.lastChecked;
    }
}

module.exports = new Updater();
