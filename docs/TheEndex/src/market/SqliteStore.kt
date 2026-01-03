package org.lokixcz.theendex.market

import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import org.lokixcz.theendex.util.EndexLogger
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class SqliteStore(private val plugin: JavaPlugin) {
    private val dbFile = File(plugin.dataFolder, "market.db")
    private val logger = EndexLogger(plugin)
    
    private fun connect(): Connection {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
    }

    fun init() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS items (" +
                        "material TEXT PRIMARY KEY, base REAL, min REAL, max REAL, current REAL, demand REAL, supply REAL, last_demand REAL, last_supply REAL" +
                    ")"
                )
                // Lightweight migration for older installs without last_demand/last_supply
                runCatching { st.executeUpdate("ALTER TABLE items ADD COLUMN last_demand REAL DEFAULT 0") }
                runCatching { st.executeUpdate("ALTER TABLE items ADD COLUMN last_supply REAL DEFAULT 0") }
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, material TEXT, time TEXT, price REAL" +
                    ")"
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_mat_time ON history(material, time)")
                // Virtual Holdings per player per material (actual item storage, not just cost tracking)
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS holdings (" +
                        "owner TEXT, material TEXT, quantity INTEGER, avg_cost REAL, PRIMARY KEY(owner, material)" +
                    ")"
                )
                // Trade receipts
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS trades (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, material TEXT, type TEXT, amount INTEGER, unit_price REAL, total REAL, time TEXT" +
                    ")"
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trades_owner_time ON trades(owner, time)")
            }
        }
        // Migrate any pending deliveries to holdings
        migrateDeliveriesToHoldings()
    }
    
    /**
     * Migrate existing pending deliveries from deliveries.db into holdings.
     * This is a one-time migration when upgrading to the virtual holdings system.
     */
    private fun migrateDeliveriesToHoldings() {
        val deliveriesDb = File(plugin.dataFolder, "deliveries.db")
        if (!deliveriesDb.exists()) return
        
        try {
            var migratedCount = 0
            DriverManager.getConnection("jdbc:sqlite:${deliveriesDb.absolutePath}").use { deliveryConn ->
                deliveryConn.createStatement().use { st ->
                    val rs = st.executeQuery(
                        "SELECT player_uuid, material, SUM(amount) as total FROM pending_deliveries GROUP BY player_uuid, material"
                    )
                    while (rs.next()) {
                        val uuid = rs.getString("player_uuid")
                        val matName = rs.getString("material")
                        val amount = rs.getInt("total")
                        val mat = Material.matchMaterial(matName) ?: continue
                        
                        // Add to holdings with avg_cost = 0 (migrated items don't have cost tracking)
                        addToHoldings(uuid, mat, amount, 0.0)
                        migratedCount += amount
                    }
                }
                // Clear deliveries after migration
                deliveryConn.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM pending_deliveries")
                }
            }
            if (migratedCount > 0) {
                logger.info("Migrated $migratedCount pending delivery items to virtual holdings system")
            }
        } catch (e: Exception) {
            logger.warn("Failed to migrate deliveries to holdings: ${e.message}")
        }
    }

    fun loadAll(): Map<Material, MarketItem> {
        val map = mutableMapOf<Material, MarketItem>()
        connect().use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT material, base, min, max, current, demand, supply, last_demand, last_supply FROM items")
                while (rs.next()) {
                    val mat = Material.matchMaterial(rs.getString("material")) ?: continue
                    val item = MarketItem(
                        material = mat,
                        basePrice = rs.getDouble("base"),
                        minPrice = rs.getDouble("min"),
                        maxPrice = rs.getDouble("max"),
                        currentPrice = rs.getDouble("current"),
                        demand = rs.getDouble("demand"),
                        supply = rs.getDouble("supply"),
                        lastDemand = rs.getDouble("last_demand"),
                        lastSupply = rs.getDouble("last_supply"),
                        history = ArrayDeque()
                    )
                    map[mat] = item
                }
                rs.close()
            }
            // history: last N points per item (we'll let MarketManager cap it)
            map.forEach { (mat, item) ->
                conn.prepareStatement("SELECT time, price FROM history WHERE material=? ORDER BY time ASC").use { ps ->
                    ps.setString(1, mat.name)
                    val rs = ps.executeQuery()
                    while (rs.next()) {
                        val time = runCatching { Instant.parse(rs.getString("time")) }.getOrNull() ?: continue
                        item.history.addLast(PricePoint(time, rs.getDouble("price")))
                    }
                }
            }
        }
        return map
    }

    fun upsertItem(item: MarketItem) {
        connect().use { conn ->
            conn.prepareStatement(
                "INSERT INTO items(material, base, min, max, current, demand, supply, last_demand, last_supply) VALUES(?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(material) DO UPDATE SET base=excluded.base, min=excluded.min, max=excluded.max, current=excluded.current, demand=excluded.demand, supply=excluded.supply, last_demand=excluded.last_demand, last_supply=excluded.last_supply"
            ).use { ps ->
                ps.setString(1, item.material.name)
                ps.setDouble(2, item.basePrice)
                ps.setDouble(3, item.minPrice)
                ps.setDouble(4, item.maxPrice)
                ps.setDouble(5, item.currentPrice)
                ps.setDouble(6, item.demand)
                ps.setDouble(7, item.supply)
                ps.setDouble(8, item.lastDemand)
                ps.setDouble(9, item.lastSupply)
                ps.executeUpdate()
            }
        }
    }

    fun appendHistory(material: Material, point: PricePoint) {
        connect().use { conn ->
            conn.prepareStatement("INSERT INTO history(material, time, price) VALUES(?,?,?)").use { ps ->
                ps.setString(1, material.name)
                ps.setString(2, point.time.toString())
                ps.setDouble(3, point.price)
                ps.executeUpdate()
            }
        }
    }

    // Holdings helpers - Virtual Storage System
    fun getHolding(ownerUuid: String, material: Material): Pair<Int, Double>? {
        connect().use { conn ->
            conn.prepareStatement("SELECT quantity, avg_cost FROM holdings WHERE owner=? AND material=?").use { ps ->
                ps.setString(1, ownerUuid)
                ps.setString(2, material.name)
                val rs = ps.executeQuery()
                if (rs.next()) return rs.getInt(1) to rs.getDouble(2)
            }
        }
        return null
    }

    fun listHoldings(ownerUuid: String): Map<Material, Pair<Int, Double>> {
        val map = mutableMapOf<Material, Pair<Int, Double>>()
        connect().use { conn ->
            conn.prepareStatement("SELECT material, quantity, avg_cost FROM holdings WHERE owner=?").use { ps ->
                ps.setString(1, ownerUuid)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val mat = Material.matchMaterial(rs.getString(1)) ?: continue
                    map[mat] = rs.getInt(2) to rs.getDouble(3)
                }
            }
        }
        return map
    }

    /**
     * Get all players' holdings for leaderboard.
     * Returns map of UUID string -> map of material name -> quantity
     */
    fun getAllPlayersHoldings(): Map<String, Map<String, Int>> {
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        connect().use { conn ->
            conn.prepareStatement("SELECT owner, material, quantity FROM holdings WHERE quantity > 0").use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val owner = rs.getString(1)
                    val material = rs.getString(2)
                    val quantity = rs.getInt(3)
                    result.getOrPut(owner) { mutableMapOf() }[material] = quantity
                }
            }
        }
        return result
    }
    
    /**
     * Get total quantity of all items in holdings for a player.
     */
    fun getTotalHoldingsCount(ownerUuid: String): Int {
        connect().use { conn ->
            conn.prepareStatement("SELECT COALESCE(SUM(quantity), 0) FROM holdings WHERE owner=?").use { ps ->
                ps.setString(1, ownerUuid)
                val rs = ps.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }
    
    /**
     * Add items to virtual holdings. Used when buying items.
     * Returns true if successful, false if would exceed limit.
     */
    fun addToHoldings(ownerUuid: String, material: Material, amount: Int, unitPrice: Double): Boolean {
        if (amount <= 0) return false
        
        val maxHoldings = plugin.config.getInt("holdings.max-total-per-player", 100000)
        
        connect().use { conn ->
            conn.autoCommit = false
            try {
                // Check current total holdings
                val currentTotal = conn.prepareStatement(
                    "SELECT COALESCE(SUM(quantity), 0) FROM holdings WHERE owner=?"
                ).use { ps ->
                    ps.setString(1, ownerUuid)
                    val rs = ps.executeQuery()
                    if (rs.next()) rs.getInt(1) else 0
                }
                
                // Check if adding would exceed limit
                if (maxHoldings > 0 && currentTotal + amount > maxHoldings) {
                    conn.rollback()
                    return false
                }
                
                // Get current holding for this material
                var qty = 0
                var avg = 0.0
                conn.prepareStatement("SELECT quantity, avg_cost FROM holdings WHERE owner=? AND material=?").use { ps ->
                    ps.setString(1, ownerUuid)
                    ps.setString(2, material.name)
                    val rs = ps.executeQuery()
                    if (rs.next()) { qty = rs.getInt(1); avg = rs.getDouble(2) }
                }
                
                val newQty = qty + amount
                // Calculate weighted average cost
                val newAvg = if (newQty > 0) {
                    val totalCost = avg * qty + unitPrice * amount
                    totalCost / newQty
                } else 0.0
                
                conn.prepareStatement(
                    "INSERT INTO holdings(owner, material, quantity, avg_cost) VALUES(?,?,?,?) " +
                        "ON CONFLICT(owner, material) DO UPDATE SET quantity=excluded.quantity, avg_cost=excluded.avg_cost"
                ).use { ps ->
                    ps.setString(1, ownerUuid)
                    ps.setString(2, material.name)
                    ps.setInt(3, newQty)
                    ps.setDouble(4, newAvg)
                    ps.executeUpdate()
                }
                
                conn.commit()
                return true
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }
    
    /**
     * Remove items from virtual holdings. Used when withdrawing or selling from holdings.
     * Returns the actual amount removed (may be less if not enough in holdings).
     */
    fun removeFromHoldings(ownerUuid: String, material: Material, amount: Int): Int {
        if (amount <= 0) return 0
        
        connect().use { conn ->
            conn.autoCommit = false
            try {
                var qty = 0
                var avg = 0.0
                conn.prepareStatement("SELECT quantity, avg_cost FROM holdings WHERE owner=? AND material=?").use { ps ->
                    ps.setString(1, ownerUuid)
                    ps.setString(2, material.name)
                    val rs = ps.executeQuery()
                    if (rs.next()) { qty = rs.getInt(1); avg = rs.getDouble(2) }
                }
                
                if (qty <= 0) {
                    conn.rollback()
                    return 0
                }
                
                val toRemove = minOf(amount, qty)
                val newQty = qty - toRemove
                
                if (newQty <= 0) {
                    conn.prepareStatement("DELETE FROM holdings WHERE owner=? AND material=?").use { ps ->
                        ps.setString(1, ownerUuid)
                        ps.setString(2, material.name)
                        ps.executeUpdate()
                    }
                } else {
                    // Keep avg cost unchanged when withdrawing
                    conn.prepareStatement(
                        "UPDATE holdings SET quantity=? WHERE owner=? AND material=?"
                    ).use { ps ->
                        ps.setInt(1, newQty)
                        ps.setString(2, ownerUuid)
                        ps.setString(3, material.name)
                        ps.executeUpdate()
                    }
                }
                
                conn.commit()
                return toRemove
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    @Deprecated("Use addToHoldings/removeFromHoldings instead for virtual holdings")
    fun upsertHolding(ownerUuid: String, material: Material, deltaQty: Int, unitPrice: Double) {
        // Update quantity and weighted average cost when buying (deltaQty>0) or selling (deltaQty<0).
        connect().use { conn ->
            conn.autoCommit = false
            try {
                var qty = 0
                var avg = 0.0
                conn.prepareStatement("SELECT quantity, avg_cost FROM holdings WHERE owner=? AND material=?").use { ps ->
                    ps.setString(1, ownerUuid)
                    ps.setString(2, material.name)
                    val rs = ps.executeQuery()
                    if (rs.next()) { qty = rs.getInt(1); avg = rs.getDouble(2) }
                }
                val newQty = (qty + deltaQty).coerceAtLeast(0)
                val newAvg = if (deltaQty > 0) {
                    val totalCost = avg * qty + unitPrice * deltaQty
                    if (newQty > 0) totalCost / newQty else 0.0
                } else avg // On sell, keep avg cost unchanged
                if (newQty == 0) {
                    conn.prepareStatement("DELETE FROM holdings WHERE owner=? AND material=?").use { ps ->
                        ps.setString(1, ownerUuid)
                        ps.setString(2, material.name)
                        ps.executeUpdate()
                    }
                } else {
                    conn.prepareStatement(
                        "INSERT INTO holdings(owner, material, quantity, avg_cost) VALUES(?,?,?,?) " +
                            "ON CONFLICT(owner, material) DO UPDATE SET quantity=excluded.quantity, avg_cost=excluded.avg_cost"
                    ).use { ps ->
                        ps.setString(1, ownerUuid)
                        ps.setString(2, material.name)
                        ps.setInt(3, newQty)
                        ps.setDouble(4, newAvg)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    fun insertTrade(ownerUuid: String, material: Material, type: String, amount: Int, unitPrice: Double, total: Double) {
        connect().use { conn ->
            conn.prepareStatement("INSERT INTO trades(owner, material, type, amount, unit_price, total, time) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, ownerUuid)
                ps.setString(2, material.name)
                ps.setString(3, type)
                ps.setInt(4, amount)
                ps.setDouble(5, unitPrice)
                ps.setDouble(6, total)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    data class TradeRow(
        val time: Instant,
        val material: Material,
        val type: String,
        val amount: Int,
        val unitPrice: Double,
        val total: Double
    )

    fun listTrades(ownerUuid: String, limit: Int = 50): List<TradeRow> {
        val list = mutableListOf<TradeRow>()
        connect().use { conn ->
            conn.prepareStatement("SELECT time, material, type, amount, unit_price, total FROM trades WHERE owner=? ORDER BY time DESC LIMIT ?").use { ps ->
                ps.setString(1, ownerUuid)
                ps.setInt(2, limit.coerceAtLeast(1))
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val t = runCatching { Instant.parse(rs.getString(1)) }.getOrNull() ?: continue
                    val mat = Material.matchMaterial(rs.getString(2)) ?: continue
                    list.add(TradeRow(t, mat, rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getDouble(6)))
                }
            }
        }
        return list
    }

    fun backupDb() {
        // Simple file copy backup; SQLite has locking, but for light usage this is OK
        if (!dbFile.exists()) return
        val backup = File(plugin.dataFolder, "market_backup.db")
        dbFile.copyTo(backup, overwrite = true)
    }
}
