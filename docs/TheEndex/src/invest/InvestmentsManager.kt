package org.lokixcz.theendex.invest

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.lokixcz.theendex.market.SqliteStore
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.*

class InvestmentsManager(private val plugin: JavaPlugin) {
    private val useSqlite = plugin.config.getBoolean("storage.sqlite", false)
    private val file = File(plugin.dataFolder, "investments.yml")

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (useSqlite) initDb()
    }

    private fun conn(): Connection = DriverManager.getConnection("jdbc:sqlite:${File(plugin.dataFolder, "market.db").absolutePath}")

    private fun initDb() {
        conn().use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS investments (" +
                        "id TEXT PRIMARY KEY, owner TEXT, material TEXT, principal REAL, apr REAL, created_at TEXT, last_accrued TEXT" +
                    ")"
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inv_owner ON investments(owner)")
            }
        }
    }

    fun enabled(): Boolean = plugin.config.getBoolean("investments.enabled", true)
    fun defaultApr(): Double = plugin.config.getDouble("investments.apr-percent", 5.0)

    fun buy(player: UUID, material: String, principal: Double, aprPercent: Double = defaultApr()): Investment {
        val now = Instant.now()
        val inv = Investment(UUID.randomUUID().toString(), player, material, principal, aprPercent, now, now)
        if (useSqlite) saveSql(inv) else saveYaml(inv)
        return inv
    }

    fun list(player: UUID): List<InvestmentSummary> {
        val items = if (useSqlite) loadSql(player) else loadYaml(player)
        return items.map { it.toSummary() }
    }

    fun redeemAll(player: UUID): Pair<Double, Int> {
        val all = if (useSqlite) loadSql(player) else loadYaml(player)
        var total = 0.0
        all.forEach { total += (it.principal + accruedFor(it)) }
        if (useSqlite) deleteAllSql(player) else deleteAllYaml(player)
        return total to all.size
    }

    private fun Investment.toSummary(): InvestmentSummary = InvestmentSummary(id, material, principal, accruedFor(this))

    private fun accruedFor(inv: Investment): Double {
        // Simple continuous compounding approximation per second at APR
        val seconds = Duration.between(inv.lastAccruedAt, Instant.now()).seconds.coerceAtLeast(0)
        if (seconds == 0L) return 0.0
        val ratePerSec = inv.aprPercent / 100.0 / (365.0 * 24.0 * 3600.0)
        val accrued = inv.principal * (Math.exp(ratePerSec * seconds) - 1.0)
        return accrued
    }

    // YAML storage
    private fun saveYaml(inv: Investment) {
        val yaml = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        val path = "investments.${inv.owner}.${inv.id}"
        yaml.set("$path.material", inv.material)
        yaml.set("$path.principal", inv.principal)
        yaml.set("$path.apr", inv.aprPercent)
        yaml.set("$path.created_at", inv.createdAt.toString())
        yaml.set("$path.last_accrued", inv.lastAccruedAt.toString())
        yaml.save(file)
    }

    private fun loadYaml(player: UUID): List<Investment> {
        if (!file.exists()) return emptyList()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("investments.${player}") ?: return emptyList()
        return section.getKeys(false).mapNotNull { id ->
            val p = "investments.${player}.${id}"
            val mat = yaml.getString("$p.material") ?: return@mapNotNull null
            val principal = yaml.getDouble("$p.principal")
            val apr = yaml.getDouble("$p.apr")
            val created = runCatching { Instant.parse(yaml.getString("$p.created_at")) }.getOrNull() ?: Instant.now()
            val last = runCatching { Instant.parse(yaml.getString("$p.last_accrued")) }.getOrNull() ?: created
            Investment(id, player, mat, principal, apr, created, last)
        }
    }

    private fun deleteAllYaml(player: UUID) {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.set("investments.${player}", null)
        yaml.save(file)
    }

    // SQLite storage
    private fun saveSql(inv: Investment) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO investments(id, owner, material, principal, apr, created_at, last_accrued) VALUES (?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, inv.id)
                ps.setString(2, inv.owner.toString())
                ps.setString(3, inv.material)
                ps.setDouble(4, inv.principal)
                ps.setDouble(5, inv.aprPercent)
                ps.setString(6, inv.createdAt.toString())
                ps.setString(7, inv.lastAccruedAt.toString())
                ps.executeUpdate()
            }
        }
    }

    private fun loadSql(player: UUID): List<Investment> {
        val list = mutableListOf<Investment>()
        conn().use { c ->
            c.prepareStatement("SELECT id, material, principal, apr, created_at, last_accrued FROM investments WHERE owner=?").use { ps ->
                ps.setString(1, player.toString())
                val rs = ps.executeQuery()
                while (rs.next()) {
                    val id = rs.getString(1)
                    val mat = rs.getString(2)
                    val principal = rs.getDouble(3)
                    val apr = rs.getDouble(4)
                    val created = runCatching { Instant.parse(rs.getString(5)) }.getOrNull() ?: Instant.now()
                    val last = runCatching { Instant.parse(rs.getString(6)) }.getOrNull() ?: created
                    list.add(Investment(id, player, mat, principal, apr, created, last))
                }
            }
        }
        return list
    }

    private fun deleteAllSql(player: UUID) {
        conn().use { c ->
            c.prepareStatement("DELETE FROM investments WHERE owner=?").use { ps ->
                ps.setString(1, player.toString())
                ps.executeUpdate()
            }
        }
    }
}
