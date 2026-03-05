package com.keel.samples.dbdemo

import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.kernel.api.KeelApi
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.openapi.annotations.KeelApiPlugin

@KeelApiPlugin(
    pluginId = "dbdemo",
    title = "Database Demo Plugin",
    description = "CRUD operations for notes with soft-delete support using H2 in-memory database",
    version = "1.0.0"
)
class DbDemoPlugin : KeelPlugin {
    private val logger = KeelLoggerService.getLogger("DbDemoPlugin")

    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "dbdemo",
        version = "1.0.0",
        displayName = "Database Demo Plugin",
        defaultRuntimeMode = PluginRuntimeMode.EXTERNAL_JVM
    )

    private lateinit var database: KeelDatabase
    private lateinit var repository: NoteRepository
    private lateinit var dbFactory: DatabaseFactory

    override suspend fun onInit(context: PluginInitContext) {
        logger.info("Initializing dbdemo plugin in ${context.config.runtimeMode}")
        dbFactory = DatabaseFactory.h2Memory(name = "dbdemo", poolSize = 5)
        database = dbFactory.init()
        database.createTables(NotesTable)
        repository = NoteRepository(database)
        seedData()
    }

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        route("/notes") {
            @KeelApi("List all active notes", tags = ["notes"], responseEnvelope = true)
            get<NoteListData> {
                PluginResult(body = NoteListData(notes = repository.findAll(), total = repository.countActive()))
            }

            @KeelApi("List all notes including soft-deleted", tags = ["notes"], responseEnvelope = true)
            get<NoteListData>("all") {
                PluginResult(body = NoteListData(notes = repository.findAllIncludingDeleted(), total = repository.count()))
            }

            @KeelApi("Create a new note", tags = ["notes"], errorStatuses = [400], responseEnvelope = true)
            post<CreateNoteRequest, Note> { request ->
                val note = repository.save(Note(title = request.title, content = request.content, author = request.author, pinned = request.pinned))
                PluginResult(body = note)
            }

            route("/{id}") {
                @KeelApi("Get a single note by ID", tags = ["notes"], errorStatuses = [400, 404], responseEnvelope = true)
                get<Note> {
                    val id = pathParameters["id"]?.toIntOrNull() ?: throw PluginApiException(400, "Invalid id")
                    val note = repository.findById(id) ?: throw PluginApiException(404, "Note not found")
                    PluginResult(body = note)
                }

                @KeelApi("Update an existing note", tags = ["notes"], errorStatuses = [400, 404], responseEnvelope = true)
                put<UpdateNoteRequest, Note> { request ->
                    val id = pathParameters["id"]?.toIntOrNull() ?: throw PluginApiException(400, "Invalid id")
                    val note = repository.update(id, request) ?: throw PluginApiException(404, "Note not found")
                    PluginResult(body = note)
                }

                @KeelApi("Soft-delete a note", tags = ["notes"], errorStatuses = [400, 404], responseEnvelope = true)
                post<String>("delete") {
                    val id = pathParameters["id"]?.toIntOrNull() ?: throw PluginApiException(400, "Invalid id")
                    if (!repository.softDelete(id)) {
                        throw PluginApiException(404, "Note not found")
                    }
                    PluginResult(body = "Note soft-deleted")
                }

                @KeelApi("Restore a soft-deleted note", tags = ["notes"], errorStatuses = [400, 404], responseEnvelope = true)
                post<String>("restore") {
                    val id = pathParameters["id"]?.toIntOrNull() ?: throw PluginApiException(400, "Invalid id")
                    if (!repository.restore(id)) {
                        throw PluginApiException(404, "Note not found")
                    }
                    PluginResult(body = "Note restored")
                }

                @KeelApi("Permanently delete a note", tags = ["notes"], errorStatuses = [400, 404], responseEnvelope = true)
                delete<String> {
                    val id = pathParameters["id"]?.toIntOrNull() ?: throw PluginApiException(400, "Invalid id")
                    if (!repository.deleteById(id)) {
                        throw PluginApiException(404, "Note not found")
                    }
                    PluginResult(body = "Note permanently deleted")
                }
            }
        }

        @KeelApi("Get database statistics", tags = ["notes", "stats"], responseEnvelope = true)
        get<DbStatsData>("/stats") {
            PluginResult(
                body = DbStatsData(
                    totalNotes = repository.count(),
                    activeNotes = repository.countActive(),
                    deletedNotes = repository.countDeleted(),
                    pinnedNotes = repository.countPinned(),
                    tableName = NotesTable.physicalName(),
                    databaseType = "H2 In-Memory"
                )
            )
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        dbFactory.close()
        logger.info("Stopped dbdemo plugin")
    }

    private fun seedData() {
        if (repository.count() > 0) return
        val samples = listOf(
            CreateNoteRequest("Welcome to Keel", "This note was auto-generated to demonstrate keel-exposed-starter capabilities.", "system", true),
            CreateNoteRequest("PluginTable Demo", "All tables use ${descriptor.pluginId}_ prefix for isolation. This table is physically named '${NotesTable.physicalName()}'.", "system"),
            CreateNoteRequest("AuditTable Demo", "This table tracks createdAt, updatedAt, deletedAt, createdBy, and updatedBy automatically.", "system")
        )
        samples.forEach { request ->
            repository.save(Note(title = request.title, content = request.content, author = request.author, pinned = request.pinned))
        }
        logger.info("Seeded ${samples.size} sample notes")
    }
}
