# Backend API Query and Indexing Standard for PostgreSQL

## Purpose

This standard guides backend developers when building API-based systems backed by PostgreSQL. Its goal is to make query shape, result correctness, and index decisions part of endpoint design, not an emergency fix after users report slow screens or wrong results.

Every API endpoint that reads, writes, searches, paginates, reconciles, or receives external callbacks should have:

- A clear database access pattern
- A matching index strategy
- A way to verify the actual SQL sent to PostgreSQL
- A way to explain differences between database results and API results

This standard applies to:

- New API endpoints
- New database tables
- New filters, sorts, and search features
- Background jobs and reconciliation jobs
- External callback handlers
- Migrations that add, remove, or change indexes
- Production incidents where query results or counts do not match expectations

## Core Principle

Indexes should be created from real query patterns.

Do not add indexes only because a column looks important. Add an index when there is a known query, constraint, or workflow that needs it.

For every important query, backend developers should be able to answer:

- Which endpoint or job runs this query?
- How often will it run?
- How many rows can the table contain?
- Which columns are used for filtering?
- Which columns are used for sorting?
- Is the result bounded by `LIMIT`?
- Does the query join to other tables?
- Which index supports this query?
- What is the write cost of that index?
- How will the team verify the SQL that the backend actually executes?

## API Design Must Include Query Design

Before implementing an endpoint, define its expected database behavior.

Example endpoint:

```text
GET /orders?customer_id=42&status=PAID&limit=50&cursor=2026-04-01T10:00:00Z
```

Expected query shape:

```sql
SELECT id, customer_id, status, total_amount, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
  AND created_at < '2026-04-01T10:00:00Z'
ORDER BY created_at DESC
LIMIT 50;
```

Useful index:

```sql
CREATE INDEX idx_orders_customer_status_created_at
ON orders (customer_id, status, created_at DESC);
```

This index matches the API because:

- `customer_id` and `status` are equality filters
- `created_at` is used for cursor pagination and sorting
- The query is bounded by `LIMIT`

## Verify Queries at Three Layers

Every important API query must be verifiable at these layers:

1. Application input
2. Actual SQL sent to PostgreSQL
3. PostgreSQL execution plan and result count

If the team can verify only the endpoint input but not the emitted SQL, they do not fully understand the query path.

## Column Order in Composite Indexes

Column order matters.

Use this general order:

```text
equality filters -> range filters -> sort columns
```

Good:

```sql
CREATE INDEX idx_orders_customer_status_created_at
ON orders (customer_id, status, created_at DESC);
```

For this query:

```sql
SELECT id, total_amount, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
ORDER BY created_at DESC
LIMIT 50;
```

Usually poor:

```sql
CREATE INDEX idx_orders_created_at_customer_status
ON orders (created_at DESC, customer_id, status);
```

The poor version starts with the sort column and makes it harder for PostgreSQL to narrow the search by `customer_id` and `status`.

## Index Common API Filters Together

If an API always filters by multiple columns together, prefer one composite index over several single-column indexes.

Query:

```sql
SELECT id, reference_number, status, created_at
FROM invoices
WHERE tenant_id = 10
  AND status = 'OVERDUE'
ORDER BY created_at DESC
LIMIT 100;
```

Useful index:

```sql
CREATE INDEX idx_invoices_tenant_status_created_at
ON invoices (tenant_id, status, created_at DESC);
```

Less useful for this endpoint:

```sql
CREATE INDEX idx_invoices_tenant_id ON invoices (tenant_id);
CREATE INDEX idx_invoices_status ON invoices (status);
CREATE INDEX idx_invoices_created_at ON invoices (created_at DESC);
```

PostgreSQL can sometimes combine indexes, but a composite index that matches the query is usually better for high-value API paths.

## Design for Pagination

API endpoints must avoid unbounded pagination on large tables.

Avoid:

```sql
SELECT id, created_at, total_amount
FROM orders
ORDER BY created_at DESC
OFFSET 500000
LIMIT 50;
```

Prefer keyset pagination:

```sql
SELECT id, created_at, total_amount
FROM orders
WHERE created_at < '2026-04-01T10:00:00Z'
ORDER BY created_at DESC
LIMIT 50;
```

Useful index:

```sql
CREATE INDEX idx_orders_created_at
ON orders (created_at DESC);
```

If pagination is scoped to a customer:

```sql
SELECT id, created_at, total_amount
FROM orders
WHERE customer_id = 42
  AND created_at < '2026-04-01T10:00:00Z'
ORDER BY created_at DESC
LIMIT 50;
```

Useful index:

```sql
CREATE INDEX idx_orders_customer_created_at
ON orders (customer_id, created_at DESC);
```

## Use Partial Indexes for Small Active Subsets

Partial indexes are useful when an API repeatedly queries a small subset of a large table.

Endpoint:

```text
GET /payments/pending?limit=100
```

Query:

```sql
SELECT id, external_reference, created_at
FROM payments
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT 100;
```

Useful partial index:

```sql
CREATE INDEX idx_payments_pending_created_at
ON payments (created_at)
WHERE status = 'PENDING';
```

This is better than indexing all statuses when `PENDING` is a small subset.

Good candidates for partial indexes:

- `status = 'PENDING'`
- `status = 'ACTIVE'`
- `processed = false`
- `deleted_at IS NULL`
- `synced_at IS NULL`

Avoid partial indexes when the condition matches most rows.

## Use Unique Indexes for Business Safety

Some indexes are not primarily for speed. They protect correctness.

External callback endpoint:

```text
POST /external-callbacks/payment-provider
```

Table:

```sql
CREATE TABLE external_callbacks (
  id bigserial PRIMARY KEY,
  provider_name text NOT NULL,
  provider_event_id text NOT NULL,
  payload jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider_name, provider_event_id)
);
```

The unique constraint prevents duplicate callback processing.

Use unique constraints or unique indexes for:

- Idempotency keys
- External transaction references
- Provider event IDs
- Natural business identifiers
- One-active-record rules where appropriate

## Index Foreign Keys Used in Joins

PostgreSQL does not automatically create indexes for foreign key columns.

If a foreign key is used in joins, lookups, deletes, or updates, add an index unless there is a clear reason not to.

Example:

```sql
CREATE TABLE order_items (
  id bigserial PRIMARY KEY,
  order_id bigint NOT NULL REFERENCES orders (id),
  product_id bigint NOT NULL,
  quantity integer NOT NULL
);
```

Useful index:

```sql
CREATE INDEX idx_order_items_order_id
ON order_items (order_id);
```

This helps joins, parent-child lookups, and parent updates or deletes.

## Use Covering Indexes Carefully

PostgreSQL supports included columns using `INCLUDE`.

Query:

```sql
SELECT id, status, total_amount
FROM orders
WHERE customer_id = 42
ORDER BY created_at DESC
LIMIT 20;
```

Possible covering index:

```sql
CREATE INDEX idx_orders_customer_created_at_include_summary
ON orders (customer_id, created_at DESC)
INCLUDE (status, total_amount);
```

Use covering indexes only when:

- The query is high volume or latency sensitive
- The included columns are small
- The index will not become too wide
- `EXPLAIN ANALYZE` shows a real benefit

## Search Endpoints Need Special Design

Do not assume a normal B-tree index will make every search fast.

Prefix search can use a suitable B-tree pattern index:

```sql
SELECT id, name, email
FROM users
WHERE name LIKE 'ann%'
ORDER BY name
LIMIT 20;
```

Possible index:

```sql
CREATE INDEX idx_users_name_pattern
ON users (name text_pattern_ops);
```

Case-insensitive search often needs a functional index:

```sql
SELECT id, name, email
FROM users
WHERE lower(email) = lower('Person@example.com');
```

Useful index:

```sql
CREATE INDEX idx_users_lower_email
ON users (lower(email));
```

Contains search often needs `pg_trgm` with GIN:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_users_name_trgm
ON users USING gin (name gin_trgm_ops);
```

## JSONB APIs Need Explicit Indexing Decisions

If an API filters inside a `jsonb` column, document the expected predicates and choose the index deliberately.

Example:

```sql
SELECT id, payload
FROM integration_events
WHERE payload @> '{"event_type": "payment.confirmed"}'
ORDER BY received_at DESC
LIMIT 100;
```

Possible GIN index:

```sql
CREATE INDEX idx_integration_events_payload_gin
ON integration_events USING gin (payload);
```

If one JSON field is queried frequently, an expression index may be better:

```sql
CREATE INDEX idx_integration_events_event_type
ON integration_events ((payload->>'event_type'));
```

## ORM and Query Builder Risks

Backend engineers must not assume that the logical repository method or endpoint description matches the final SQL.

Common causes of wrong counts, wrong rows, or slow queries:

- Hidden tenant predicates
- Soft-delete filters
- Security or row-level predicates
- Eager fetching that adds joins
- N+1 queries
- Count queries and result queries using different filters
- Duplicate parent rows from one-to-many joins
- In-memory deduplication or expansion after SQL execution
- Multiple repository calls merged into one API response

A query can be logically correct in code review and still be wrong in the database.

## Diagnose: PostgreSQL Returns 3 Rows but the Backend Returns 10

This is a standard backend troubleshooting scenario.

### The Team Must Verify

- The exact SQL text sent by the backend
- Every bind parameter value and type
- The connected database, schema, and role
- Whether one endpoint call triggers one SQL statement or many
- Whether response mapping changes row cardinality after query execution

### Investigation Checklist

1. Reproduce the endpoint call with fixed request inputs.
2. Capture the exact SQL emitted by the backend.
3. Capture bind parameter values and types.
4. Run that exact SQL manually in PostgreSQL under the same user.
5. Compare result count and actual rows.
6. Check session context:
   current database, current schema, `search_path`, role, timezone, isolation level
7. Check whether the endpoint runs multiple queries and merges the results.
8. Check whether joins multiply rows.
9. Check whether the mapper, serializer, or aggregation layer duplicates records.
10. Run `EXPLAIN ANALYZE` on the exact emitted SQL if safe.

### Session Context Checks

Useful commands:

```sql
SELECT current_database(), current_schema, current_user;
SHOW search_path;
SHOW TimeZone;
SHOW transaction_isolation;
```

### Join Duplication Example

```sql
SELECT o.id, o.customer_id, i.id AS item_id
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.status = 'PAID';
```

If one order has many items, the order appears many times.

Possible fixes:

- Remove the join if it is not required
- Aggregate child rows
- Use `DISTINCT` carefully
- Use `DISTINCT ON` when one row per parent is the business requirement

Example:

```sql
SELECT DISTINCT ON (o.id)
  o.id,
  o.customer_id,
  o.created_at
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.status = 'PAID'
ORDER BY o.id, o.created_at DESC;
```

### SQL Logging for Troubleshooting

During controlled troubleshooting:

- Enable SQL logging in the backend
- Include bind parameter values where safe
- Record all SQL statements triggered by one endpoint call
- Compare backend SQL to the manually tested SQL

The rule is simple: never compare PostgreSQL results to a guessed query. Compare them to the exact emitted SQL.

## Validate With `EXPLAIN`

Every important query should be checked with `EXPLAIN`.

Use:

```sql
EXPLAIN
SELECT id, customer_id, status, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
ORDER BY created_at DESC
LIMIT 50;
```

Use `EXPLAIN ANALYZE` when it is safe:

```sql
EXPLAIN ANALYZE
SELECT id, customer_id, status, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
ORDER BY created_at DESC
LIMIT 50;
```

Look for:

- Sequential scans on large tables
- Sorts over many rows
- High actual time
- Large differences between estimated and actual rows
- Nested loops over large result sets
- Queries returning far more rows than the API needs
- Row multiplication after joins

Use production-like data where possible.

## Large Table Migration Rules

Index creation on large tables can block or overload systems if done carelessly.

For large or busy tables, prefer:

```sql
CREATE INDEX CONCURRENTLY idx_orders_customer_status_created_at
ON orders (customer_id, status, created_at DESC);
```

Important rules:

- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block
- It takes longer than normal index creation
- It reduces blocking, but still consumes CPU, I/O, and disk
- It should be scheduled with the database team for very large tables
- Failed concurrent index builds may leave invalid indexes that need cleanup

## Post-Release Review

After releasing a new endpoint or index, verify that the database behaves as expected.

Review slow and frequent queries:

```sql
SELECT
  query,
  calls,
  total_exec_time,
  mean_exec_time,
  rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

Review index usage:

```sql
SELECT
  schemaname,
  relname,
  indexrelname,
  idx_scan
FROM pg_stat_user_indexes
ORDER BY idx_scan ASC
LIMIT 20;
```

Review whether the endpoint still returns the expected number of rows after ORM mapping, serialization, and pagination.

## Pull Request Checklist

Backend pull requests that add or change database-backed APIs must answer these questions:

- What endpoint, job, or integration uses the query?
- What filters can the caller send?
- What sort order is used?
- Is pagination bounded with `LIMIT`?
- Does the endpoint use keyset pagination for large result sets?
- What is the expected table size now and in one year?
- Which index supports the query?
- Does the migration explain why the index exists?
- Does the query avoid `SELECT *`?
- Does the query avoid N+1 behavior?
- Does the query run inside a transaction?
- Could the query run many times per page load?
- Are retries protected by idempotency keys or unique constraints?
- Was `EXPLAIN` or `EXPLAIN ANALYZE` checked for non-trivial tables?
- Does the index add unacceptable write, storage, or WAL cost?
- Does a large-table index use `CREATE INDEX CONCURRENTLY` where appropriate?
- How will the team capture the actual SQL if the endpoint returns unexpected rows?
- Could joins, mappers, or aggregation logic duplicate records?

## Minimum Standard for New API Endpoints

A new API endpoint that reads from PostgreSQL should not be considered ready until it has:

- A defined query shape
- A bounded result size
- A documented sort order
- A pagination strategy for list endpoints
- An index decision, even if the decision is not to add one
- An `EXPLAIN` review for non-trivial tables
- A way to capture the actual emitted SQL during troubleshooting

A new API endpoint that writes to PostgreSQL should not be considered ready until it has:

- Unique constraints for business identities where needed
- Idempotency protection for retryable operations
- Indexed foreign keys for expected joins and parent-child lookups
- A plan for write volume and index maintenance cost

## Summary

Backend developers should treat indexes and query verification as part of API design.

Good backend database design means:

- Build indexes from real query patterns
- Prefer composite indexes that match filters, sorting, and pagination
- Use partial indexes for small active subsets
- Use unique constraints to protect correctness
- Validate important queries with `EXPLAIN`
- Avoid over-indexing because every index has a write and maintenance cost
- Coordinate heavy index creation on shared PostgreSQL servers
- Capture actual emitted SQL when database and API results do not match

The best index is not the one that exists on every column. The best index is the one that supports a real query, at acceptable cost, with behavior the team can prove end to end.
