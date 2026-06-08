import java.io.File
import java.sql.DriverManager

/**
 * Export H2 database tables to CSV files for migration.
 * Connects to H2 TCP server on localhost:9092.
 * 
 * Usage: ./gradlew :keel-samples:exportH2Data -PdbFolder=~/.keel/keel-data/
 */
fun main(args: Array<String>) {
    val dbFolder = System.getProperty("dbFolder") 
        ?: "${System.getProperty("user.home")}/.keel/keel-data/"
    
    val outputDir = File("build/exports")
    outputDir.mkdirs()
    
    Class.forName("org.h2.Driver")
    
    val dbPath = File(dbFolder).canonicalPath
    val jdbcUrl = "jdbc:h2:tcp://localhost:9092/$dbPath/aigateway_airelay"
    println("Connecting to: $jdbcUrl")
    
    val queries = mapOf(
        "channel" to """
            SELECT channel_id, name, protocol, base_url, api_key_encrypted, api_key_env,
                   enabled, priority, weight, max_concurrency, timeout_ms, status
            FROM airelay_channel
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "group" to """
            SELECT group_id, name, description, enabled, exposure_mode
            FROM airelay_group
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "channel_membership" to """
            SELECT channel_id, group_id, priority, weight, enabled
            FROM airelay_channel_membership
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "model" to """
            SELECT model_id, channel_id, public_model_name, upstream_model_name,
                   input_cost_per_mtok, output_cost_per_mtok, cache_creation_cost_per_mtok,
                   cache_read_cost_per_mtok, cached_input_discount, reasoning_output_cost_per_mtok,
                   credit_multiplier, enabled
            FROM airelay_model
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "model_pricing" to """
            SELECT pricing_id, model, variant_key, label, billing_unit_tokens,
                   input_cost_per_mtok, output_cost_per_mtok, cache_creation_cost_per_mtok,
                   cache_read_cost_per_mtok, cached_input_discount, reasoning_output_cost_per_mtok,
                   credit_multiplier, notes
            FROM airelay_model_pricing
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "model_pricing_tier" to """
            SELECT tier_id, pricing_id, start_tokens_inclusive, end_tokens_exclusive,
                   billing_unit_tokens, input_cost_per_unit, output_cost_per_unit,
                   cache_creation_cost_per_unit, cache_read_cost_per_unit, reasoning_output_cost_per_unit
            FROM airelay_model_pricing_tier
            WHERE deleted_at IS NULL
        """.trimIndent(),
        
        "group_alias" to """
            SELECT alias_id, group_id, alias_name, target_models_json, enabled, credit_multiplier
            FROM airelay_group_alias
            WHERE deleted_at IS NULL
        """.trimIndent()
    )
    
    DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
        queries.forEach { (name, sql) ->
            val outputFile = File(outputDir, "airelay_${name}.csv")
            println("Exporting $name -> ${outputFile.name}")
            
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    val colCount = meta.columnCount
                    
                    outputFile.bufferedWriter().use { writer ->
                        val headers = (1..colCount).joinToString(",") { 
                            "\"${meta.getColumnName(it)}\"" 
                        }
                        writer.write(headers)
                        writer.newLine()
                        
                        var rowCount = 0
                        while (rs.next()) {
                            val row = (1..colCount).joinToString(",") { col ->
                                val value = rs.getString(col)
                                if (value == null) "" 
                                else "\"${value.replace("\"", "\"\"")}\""
                            }
                            writer.write(row)
                            writer.newLine()
                            rowCount++
                        }
                        println("  $rowCount rows")
                    }
                }
            }
        }
    }
    
    println("\nDone! Files: ${outputDir.absolutePath}")
}
