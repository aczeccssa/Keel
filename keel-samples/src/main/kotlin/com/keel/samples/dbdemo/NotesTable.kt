package com.keel.samples.dbdemo

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column

object NotesTable : AuditPluginTable("dbdemo", "notes") {
    val id: Column<Int> = integer("id").autoIncrement()
    val title: Column<String> = varchar("title", 200)
    val content: Column<String> = text("content").default("")
    val author: Column<String> = varchar("author", 100).default("anonymous")
    val pinned: Column<Boolean> = bool("pinned").default(false)

    override val primaryKey = PrimaryKey(id)
}
