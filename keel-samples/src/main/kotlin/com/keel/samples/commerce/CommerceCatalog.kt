package com.keel.samples.commerce

data class CommerceCatalogProduct(
    val productId: String,
    val title: String,
    val description: String,
    val priceCents: Int,
    val badge: String,
    val collection: String
)

data class CommerceCatalogCollection(
    val name: String,
    val copy: String
)

data class CommerceCatalogSnapshot(
    val products: List<CommerceCatalogProduct>,
    val collections: List<CommerceCatalogCollection>
)

data class CommerceCatalogUpsert(
    val productId: String?,
    val title: String,
    val description: String,
    val priceCents: Int,
    val badge: String,
    val collection: String
)

object CommerceCatalog {
    private val collectionCopy = linkedMapOf(
        "Workspace" to "Desk essentials for focused building sessions.",
        "Audio" to "Quiet, private, and call-ready equipment.",
        "Ergonomics" to "Setup upgrades that keep long work blocks sustainable."
    )

    private val productsById = linkedMapOf(
        "sku-desk-lamp" to CommerceCatalogProduct(
            productId = "sku-desk-lamp",
            title = "Desk Lamp",
            description = "Warm lighting for late-night coding sessions.",
            priceCents = 5_900,
            badge = "Workspace",
            collection = "Workspace"
        ),
        "sku-mech-keyboard" to CommerceCatalogProduct(
            productId = "sku-mech-keyboard",
            title = "Mechanical Keyboard",
            description = "Compact tactile keyboard for a focused desk setup.",
            priceCents = 12_900,
            badge = "Input",
            collection = "Workspace"
        ),
        "sku-monitor-arm" to CommerceCatalogProduct(
            productId = "sku-monitor-arm",
            title = "Monitor Arm",
            description = "Clamp-on arm to free desk space and improve posture.",
            priceCents = 8_400,
            badge = "Ergonomics",
            collection = "Ergonomics"
        ),
        "sku-headphones" to CommerceCatalogProduct(
            productId = "sku-headphones",
            title = "Studio Headphones",
            description = "Closed-back pair for calls, edits, and quiet work.",
            priceCents = 9_900,
            badge = "Audio",
            collection = "Audio"
        )
    )

    @Synchronized
    fun snapshot(): CommerceCatalogSnapshot {
        val products = productsById.values.toList()
        val collections = collectionCopy.entries.map { (name, copy) ->
            CommerceCatalogCollection(name = name, copy = copy)
        }
        return CommerceCatalogSnapshot(products = products, collections = collections)
    }

    @Synchronized
    fun products(): List<CommerceCatalogProduct> = productsById.values.toList()

    @Synchronized
    fun collections(): List<CommerceCatalogCollection> {
        return collectionCopy.entries.map { (name, copy) ->
            CommerceCatalogCollection(name = name, copy = copy)
        }
    }

    @Synchronized
    fun product(productId: String): CommerceCatalogProduct? = productsById[productId]

    @Synchronized
    fun upsert(input: CommerceCatalogUpsert): CommerceCatalogProduct {
        val normalizedCollection = input.collection.trim().ifBlank { "Workspace" }
        val normalizedTitle = input.title.trim()
        val normalizedDescription = input.description.trim()
        val normalizedBadge = input.badge.trim().ifBlank { normalizedCollection }
        val productId = input.productId?.trim()?.takeIf { it.isNotEmpty() } ?: nextProductId(normalizedTitle)

        collectionCopy.putIfAbsent(
            normalizedCollection,
            defaultCollectionCopy(normalizedCollection)
        )

        val product = CommerceCatalogProduct(
            productId = productId,
            title = normalizedTitle,
            description = normalizedDescription,
            priceCents = input.priceCents,
            badge = normalizedBadge,
            collection = normalizedCollection
        )
        productsById[productId] = product
        return product
    }

    @Synchronized
    fun remove(productId: String): CommerceCatalogProduct? {
        return productsById.remove(productId)
    }

    private fun defaultCollectionCopy(collection: String): String {
        return "$collection picks curated for the commerce sample."
    }

    private fun nextProductId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "custom-product" }
        var candidate = "sku-$slug"
        var suffix = 2
        while (productsById.containsKey(candidate)) {
            candidate = "sku-$slug-$suffix"
            suffix += 1
        }
        return candidate
    }
}
