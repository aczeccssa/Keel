# H2 to PostgreSQL or SQLite Migration Notes

## Current State

The AI Gateway sample currently persists data into three local H2 file databases under
`~/.keel/keel-data/` by default:

- `aigateway_airelay.mv.db`
- `aigateway_token.mv.db`
- `customer_portal.mv.db`

Historically the plugin boot paths were hardcoded to H2 file mode, but this is no longer true.
The AI Gateway plugins now resolve their backend through a shared config resolver:

- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/token/TokenPlugin.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalPlugin.kt`

The resolver lives in:

- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/GatewayDatabaseFactoryResolver.kt`

The lower-level DB abstraction is already multi-database capable:

- `DatabaseConfig.postgresql(...)`
- `DatabaseConfig.sqlite(...)`
- `DatabaseFactory.postgresql(...)`
- `DatabaseFactory.sqlite(...)`

Those live in:

- `keel-exposed-starter/src/main/kotlin/com/keel/db/database/DatabaseFactory.kt`

## Important Findings

### 1. PostgreSQL and SQLite are now wired into the sample runtime

The sample now includes runtime JDBC dependencies for:

- PostgreSQL
- SQLite

Those are wired through:

- `gradle/libs.versions.toml`
- `keel-samples/build.gradle.kts`

This means the sample can now switch backend by configuration rather than code edits.

### 1.1 Implemented backend config keys

The resolver currently supports:

- `keel.aigateway.db.kind=h2|sqlite|postgresql`
- `keel.aigateway.db.dir=/path/to/data`
- `keel.aigateway.db.poolSize=5`

H2-specific:

- `keel.aigateway.db.h2.file.<logicalName>`
- `keel.aigateway.db.h2.username`
- `keel.aigateway.db.h2.password`

SQLite-specific:

- `keel.aigateway.db.sqlite.dir=/path/to/sqlite-files`
- `keel.aigateway.db.sqlite.file.<logicalName>`

PostgreSQL-specific:

- `keel.aigateway.db.postgresql.host`
- `keel.aigateway.db.postgresql.port`
- `keel.aigateway.db.postgresql.username`
- `keel.aigateway.db.postgresql.password`
- `keel.aigateway.db.postgresql.databasePrefix`
- `keel.aigateway.db.postgresql.database.<logicalName>`

Environment variable fallbacks follow the same names uppercased with `.` replaced by `_`, e.g.:

- `KEEL_AIGATEWAY_DB_KIND`
- `KEEL_AIGATEWAY_DB_POSTGRESQL_USERNAME`
- `KEEL_AIGATEWAY_DB_POSTGRESQL_PASSWORD`

### 2. Runtime schema migration logic is still the main portability risk

Several repositories run manual `ALTER TABLE` statements during startup. This is workable for
incremental H2 evolution, but it is the main portability risk for PostgreSQL and SQLite.

Known hotspots:

- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/token/TokenRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelRepository.kt`

The most obvious blockers were H2-style `ADD COLUMN IF NOT EXISTS` migrations in:

- `TokenRepository`
- `ChannelRepository`

Those have now been converted to "check whether the column exists, then run a plain `ADD COLUMN`"
so fresh SQLite databases can boot.

The remaining syntax risk is statements like:

- `ALTER TABLE ... ALTER COLUMN ...`

That syntax is H2-friendly but not generally portable:

- PostgreSQL needs `ALTER TABLE ... ALTER COLUMN ... TYPE ...`
- SQLite does not support this kind of `ALTER COLUMN`

So older databases with historical schema drift still need careful testing per backend, especially
for PostgreSQL and for any SQLite database created from an older schema snapshot.

### 3. Existing export tooling is incomplete

There is already one export task:

- `./gradlew :keel-samples:exportH2Data`

But it only exports AI relay configuration tables from `aigateway_airelay`. It does not export
the `token` or `customer_portal` databases, which currently hold the important operational data:

- API keys
- usage records
- customer accounts
- credit ledger

### 4. SQLite is viable for local single-user use, but is still a poor default for gateway traffic

The framework already forces SQLite pool size to `1`, which is the right instinct:

- `DatabaseConfig.sqlite(...)` returns `poolSize = 1`

That means SQLite is reasonable for:

- local development
- debugging
- portable snapshots
- lightweight demos

It is not a strong target for sustained concurrent writes from:

- usage recording
- customer credit ledger updates
- admin mutations happening at the same time as traffic

For a real replacement of the current H2 setup, PostgreSQL is the better target.

## Recommended Migration Strategy

### Preferred target

Use PostgreSQL for the actual migration target.

Use SQLite only if the goal is:

- local portability
- offline inspection
- lightweight personal usage

### Recommended data migration method

Do not try to replay H2 DDL into PostgreSQL or SQLite.

That path is brittle because:

- H2 SQL dialect differences will leak into the export
- the codebase already contains H2-oriented migration statements
- quoted table naming is awkward for `customer-portal_*` tables

Instead, use a two-connection row-copy migration:

1. Open the source H2 database read-only.
2. Boot an empty target PostgreSQL or SQLite database with the current Exposed table models.
3. Let the target create its own schema via `initializeSchema()`.
4. Copy rows table-by-table from H2 into the target.
5. Validate row counts and a few spot-check queries.

This is much safer than converting raw SQL dumps.

## Concrete Next Steps

### Phase 1: Keep hardening startup migrations

Audit and fix every raw SQL migration that assumes H2 syntax.

Priority order for remaining portability review:

1. `CustomerPortalRepository.migrateCustomerUsageColumns()`
2. any `ALTER COLUMN` usage across the AI Gateway repositories
3. any raw DDL that still assumes H2 naming or type semantics

The target state should be one of:

- dialect-aware SQL branches
- or fully explicit versioned migrations

### Phase 2: Expand export/import tooling

The existing `exportH2Data` task should be expanded or replaced with a proper migration tool that
handles all three databases:

- `aigateway_airelay`
- `aigateway_token`
- `customer_portal`

The best tool shape is a Kotlin task that:

- opens source H2
- opens target PostgreSQL or SQLite
- initializes target schema
- copies rows in dependency order
- prints row counts before exit

### Phase 3: Cutover procedure

1. Stop the sample service so the H2 files are not locked.
2. Back up `~/.keel/keel-data/`.
3. Run migration into a fresh target database.
4. Point the sample at the new backend.
5. Run smoke tests:
   - admin login
   - list providers/channels
   - create or update API key
   - read usage records
   - customer login and balance read

## Short-Term Recommendation

If the goal is to move quickly with the lowest risk:

1. Target PostgreSQL first.
2. Do not attempt raw SQL dump conversion.
3. Implement a dedicated H2-to-PostgreSQL row-copy migration tool.
4. Treat SQLite as a separate local-only target after PostgreSQL works.

That ordering minimizes production-style risk and avoids spending time on SQLite-specific write
limitations before the main portability problems are solved.

## Verified So Far

The following are now verified in tests:

- config resolver returns H2 / SQLite / PostgreSQL `DatabaseConfig` values correctly
- SQLite driver is on the sample runtime classpath
- PostgreSQL driver is on the sample runtime classpath
- default AI Gateway H2 path still boots for a login -> create key -> chat round trip
- AI Gateway core path can boot with SQLite for login -> create key -> chat
- Customer Portal can boot with SQLite for register -> login

This does **not** yet mean there is a finished data migration tool. It means the runtime is now
materially closer to accepting PostgreSQL or SQLite as a real backend target.
