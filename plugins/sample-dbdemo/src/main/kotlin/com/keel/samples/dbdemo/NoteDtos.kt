package com.keel.samples.dbdemo

import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable

@KeelApiSchema
@Serializable
data class Note(
    @KeelApiField(description = "The unique identifier of the note", example = "1")
    val id: Int = 0,
    @KeelApiField(description = "The title of the note", example = "My Note")
    val title: String,
    @KeelApiField(description = "The content of the note", example = "This is a sample note.")
    val content: String = "",
    @KeelApiField(description = "The author of the note", example = "johndoe")
    val author: String = "anonymous",
    @KeelApiField(description = "Whether the note is pinned", example = "false")
    val pinned: Boolean = false,
    @KeelApiField(description = "Creation timestamp", example = "2023-01-01T12:00:00Z")
    val createdAt: String? = null,
    @KeelApiField(description = "Last update timestamp", example = "2023-01-01T12:00:00Z")
    val updatedAt: String? = null,
    @KeelApiField(description = "Deletion timestamp if soft deleted", example = "null")
    val deletedAt: String? = null,
    @KeelApiField(description = "User who created the note", example = "johndoe")
    val createdBy: String? = null,
    @KeelApiField(description = "User who last updated the note", example = "johndoe")
    val updatedBy: String? = null
)

@KeelApiSchema
@Serializable
data class CreateNoteRequest(
    @KeelApiField(description = "The title of the new note", example = "New Note")
    val title: String,
    @KeelApiField(description = "The content of the new note", example = "This is a new note.")
    val content: String = "",
    @KeelApiField(description = "The author of the note", example = "johndoe")
    val author: String = "anonymous",
    @KeelApiField(description = "Whether to pin the new note", example = "true")
    val pinned: Boolean = false
)

@KeelApiSchema
@Serializable
data class UpdateNoteRequest(
    @KeelApiField(description = "The new title", example = "Updated Note")
    val title: String? = null,
    @KeelApiField(description = "The new content", example = "Updated content.")
    val content: String? = null,
    @KeelApiField(description = "The new author", example = "janedoe")
    val author: String? = null,
    @KeelApiField(description = "Whether the note is pinned", example = "false")
    val pinned: Boolean? = null
)

@KeelApiSchema
@Serializable
data class NoteListData(
    @KeelApiField(description = "List of notes", example = "[]")
    val notes: List<Note>,
    @KeelApiField(description = "Total number of notes", example = "10")
    val total: Int
)

@KeelApiSchema
@Serializable
data class DbStatsData(
    @KeelApiField(description = "Total number of notes", example = "20")
    val totalNotes: Int,
    @KeelApiField(description = "Number of active notes", example = "15")
    val activeNotes: Int,
    @KeelApiField(description = "Number of deleted notes", example = "5")
    val deletedNotes: Int,
    @KeelApiField(description = "Number of pinned notes", example = "3")
    val pinnedNotes: Int,
    @KeelApiField(description = "Database table name", example = "dbdemo_notes")
    val tableName: String,
    @KeelApiField(description = "Database server type", example = "H2")
    val databaseType: String
)
