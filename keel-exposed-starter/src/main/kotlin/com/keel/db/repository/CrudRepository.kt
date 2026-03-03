package com.keel.db.repository

/**
 * Generic CRUD repository interface.
 * Provides standard Create, Read, Update, Delete operations.
 *
 * @param ID The type of the primary key
 * @param E The entity type
 */
interface CrudRepository<ID, E : Any> {
    /**
     * Find a record by its ID.
     *
     * @param id The primary key value
     * @return The record if found, null otherwise
     */
    fun findById(id: ID): E?

    /**
     * Find all records.
     *
     * @return List of all records
     */
    fun findAll(): List<E>

    /**
     * Save (insert or update) a record.
     *
     * @param entity The entity to save
     * @return The saved entity (with generated ID if inserted)
     */
    fun save(entity: E): E

    /**
     * Delete a record by its ID.
     *
     * @param id The primary key value
     * @return true if the record was deleted, false if it didn't exist
     */
    fun deleteById(id: ID): Boolean

    /**
     * Check if a record exists with the given ID.
     *
     * @param id The primary key value
     * @return true if the record exists
     */
    fun existsById(id: ID): Boolean

    /**
     * Count all records.
     *
     * @return The total number of records
     */
    fun count(): Int
}

/**
 * Extension function to delete an entity.
 *
 * @param entity The entity to delete
 * @return true if the entity was deleted
 */
fun <ID, E : Any> CrudRepository<ID, E>.delete(entity: E): Boolean {
    throw NotImplementedError(
        "CrudRepository.delete(entity) is undefined. " +
            "Implement deletion in the concrete repository or remove this extension."
    )
}
