package com.keel.samples.dbdemo

import com.keel.db.table.AuditPluginTable

object NotesTable : AuditPluginTable("dbdemo", "notes") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 200)
    val content = text("content").default("")
    val author = varchar("author", 100).default("anonymous")
    val pinned = bool("pinned").default(false)

    override val primaryKey = PrimaryKey(id)
}
