package com.keel.samples.dbdemo

import com.keel.db.database.KeelDatabase
import com.keel.db.repository.BaseRepository
import com.keel.db.repository.CrudRepository
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class NoteRepository(database: KeelDatabase) : BaseRepository<NotesTable>(database, NotesTable), CrudRepository<Int, Note> {
    override fun findById(id: Int): Note? = transaction {
        NotesTable.selectAll().where { NotesTable.id eq id }.firstOrNull()?.let { row ->
            Note(
                id = row[NotesTable.id],
                title = row[NotesTable.title],
                content = row[NotesTable.content],
                author = row[NotesTable.author],
                pinned = row[NotesTable.pinned],
                createdAt = row[NotesTable.createdAt].toString(),
                updatedAt = row[NotesTable.updatedAt].toString(),
                deletedAt = row[NotesTable.deletedAt]?.toString(),
                createdBy = row[NotesTable.createdBy],
                updatedBy = row[NotesTable.updatedBy]
            )
        }
    }

    override fun findAll(): List<Note> = transaction {
        NotesTable.selectAll().where { NotesTable.deletedAt.isNull() }.map { row ->
            Note(
                id = row[NotesTable.id],
                title = row[NotesTable.title],
                content = row[NotesTable.content],
                author = row[NotesTable.author],
                pinned = row[NotesTable.pinned],
                createdAt = row[NotesTable.createdAt].toString(),
                updatedAt = row[NotesTable.updatedAt].toString(),
                deletedAt = row[NotesTable.deletedAt]?.toString(),
                createdBy = row[NotesTable.createdBy],
                updatedBy = row[NotesTable.updatedBy]
            )
        }
    }

    fun findAllIncludingDeleted(): List<Note> = transaction {
        NotesTable.selectAll().map { row ->
            Note(
                id = row[NotesTable.id],
                title = row[NotesTable.title],
                content = row[NotesTable.content],
                author = row[NotesTable.author],
                pinned = row[NotesTable.pinned],
                createdAt = row[NotesTable.createdAt].toString(),
                updatedAt = row[NotesTable.updatedAt].toString(),
                deletedAt = row[NotesTable.deletedAt]?.toString(),
                createdBy = row[NotesTable.createdBy],
                updatedBy = row[NotesTable.updatedBy]
            )
        }
    }

    override fun save(entity: Note): Note = transaction {
        val now = Clock.System.now()
        val id = NotesTable.insert {
            it[title] = entity.title
            it[content] = entity.content
            it[author] = entity.author
            it[pinned] = entity.pinned
            it[createdAt] = now
            it[updatedAt] = now
            it[createdBy] = entity.author
            it[updatedBy] = entity.author
        } get NotesTable.id

        entity.copy(
            id = id,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            createdBy = entity.author,
            updatedBy = entity.author
        )
    }

    fun update(id: Int, request: UpdateNoteRequest): Note? = transaction {
        val now = Clock.System.now()
        val updated = NotesTable.update({ NotesTable.id eq id }) {
            request.title?.let { value -> it[title] = value }
            request.content?.let { value -> it[content] = value }
            request.author?.let { value -> it[author] = value }
            request.pinned?.let { value -> it[pinned] = value }
            it[updatedAt] = now
            request.author?.let { value -> it[updatedBy] = value }
        }
        if (updated > 0) findById(id) else null
    }

    override fun deleteById(id: Int): Boolean = transaction {
        NotesTable.deleteWhere { NotesTable.id eq id } > 0
    }

    fun softDelete(id: Int): Boolean = transaction {
        val now = Clock.System.now()
        NotesTable.update({ NotesTable.id eq id }) {
            it[deletedAt] = now
        } > 0
    }

    fun restore(id: Int): Boolean = transaction {
        NotesTable.update({ NotesTable.id eq id }) {
            it[deletedAt] = null
        } > 0
    }

    override fun existsById(id: Int): Boolean = transaction {
        NotesTable.selectAll().where { NotesTable.id eq id }.count() > 0
    }

    fun countActive(): Int = transaction {
        NotesTable.selectAll().where { NotesTable.deletedAt.isNull() }.count().toInt()
    }

    fun countDeleted(): Int = transaction {
        NotesTable.selectAll().where { NotesTable.deletedAt.isNotNull() }.count().toInt()
    }

    fun countPinned(): Int = transaction {
        NotesTable.selectAll().where {
            (NotesTable.pinned eq true) and NotesTable.deletedAt.isNull()
        }.count().toInt()
    }
}
