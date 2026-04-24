# Advanced Database Optimization and Tuning

## How to Diagnose, Fix, and Prevent PostgreSQL Performance Problems

- Audience: DBAs, backend engineers, technical leads
- Focus: real incidents, not theory only
- Outcome: faster systems, fewer surprises, repeatable diagnosis

---

# Why This Workshop Exists

- Slow screens and APIs are often database-driven
- Teams usually see symptoms before they see causes
- The same PostgreSQL server can be overloaded by many small mistakes
- Good teams still struggle when SQL, ORM, pooling, and operations are not aligned

---

# What the Team Is Probably Feeling

- "The screen takes too long to load"
- "The database is slow again"
- "We added indexes but the problem is still there"
- "The SQL returns 3 rows in PostgreSQL, but the endpoint returns 10"
- "We do not know where to start"

---

# The Promise of This Session

- We will connect symptoms to root causes
- We will use PostgreSQL tools that expose what is really happening
- We will separate database issues from backend-query-path issues
- We will leave with a practical method to improve performance

---

# Shared-Server Reality

```text
PostgreSQL server
├── customer_db
├── billing_db
├── inventory_db
├── reporting_db
└── audit_db
```

- Separate databases do not mean separate server resources
- CPU, memory, disk I/O, WAL, connections, and autovacuum are shared
- One noisy workload can degrade other systems

---

# How a Slow Request Happens

```text
Frontend
  -> Backend service
    -> ORM or query builder
      -> PostgreSQL
    -> External system call
    -> PostgreSQL update
  -> Response
```

- Small inefficiencies stack together
- Optimization is a full request-path exercise

---

# Root Cause Families

- Weak or missing indexes
- Bad query shape
- N+1 behavior
- Oversized or poorly tuned connection pools
- Long transactions and blocking
- Large-table scan pressure
- Poor visibility into slow statements
- Backend and database returning different result sets

---

# Start with Evidence, Not Guessing

- What query is actually running
- How often it runs
- How many rows it reads
- How many rows it returns
- Which plan PostgreSQL chose
- Whether the backend changed the result after SQL execution

---

# Tool 1: `EXPLAIN`

Use `EXPLAIN` when you need to see the planned path before running a risky query.

```sql
EXPLAIN
SELECT id, customer_id, total_amount, created_at
FROM orders
WHERE customer_id = 42
ORDER BY created_at DESC
LIMIT 20;
```

- Shows the intended execution path
- Good first step before `EXPLAIN ANALYZE`

---

# Tool 2: `EXPLAIN ANALYZE`

```sql
EXPLAIN ANALYZE
SELECT id, customer_id, total_amount, created_at
FROM orders
WHERE customer_id = 42
ORDER BY created_at DESC
LIMIT 20;
```

Look for:

- Seq scans on large tables
- Big estimate vs actual row gaps
- Expensive sorts
- Nested loops over large sets
- Row explosion after joins

---

# Bad Plan vs Better Plan

Bad pattern:

```text
Seq Scan on orders
  Filter: (customer_id = 42)
  Rows Removed by Filter: 950000
Sort
  Sort Key: created_at DESC
```

Better pattern:

```text
Index Scan using idx_orders_customer_created_at on orders
  Index Cond: (customer_id = 42)
```

---

# Index Strategy: The Core Rule

- Build indexes from real query patterns
- Do not index columns just because they look important
- Match index order to filter and sort behavior

```text
equality filters -> range filters -> sort columns
```

---

# Composite Indexes

Query:

```sql
SELECT id, total_amount, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
ORDER BY created_at DESC
LIMIT 50;
```

Useful index:

```sql
CREATE INDEX idx_orders_customer_status_created_at
ON orders (customer_id, status, created_at DESC);
```

---

# Partial Indexes

When a query repeatedly targets a small active subset:

```sql
SELECT id, external_reference, created_at
FROM payments
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT 100;
```

```sql
CREATE INDEX idx_payments_pending_created_at
ON payments (created_at)
WHERE status = 'PENDING';
```

- Smaller index
- Lower maintenance cost than indexing all rows

---

# Foreign Keys Must Be Reviewed for Indexing

- PostgreSQL does not auto-index foreign key columns
- Unindexed foreign keys hurt joins, parent lookups, deletes, and updates

```sql
CREATE INDEX idx_order_items_order_id
ON order_items (order_id);
```

---

# Avoid Over-Indexing

Every index increases:

- Insert cost
- Update cost
- Delete cost
- WAL volume
- Disk usage
- Vacuum work

The best index is the one that solves a real query at acceptable cost.

---

# Pagination Matters

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

---

# N+1: A Silent Performance Killer

Bad pattern:

```text
1 query to load 100 orders
100 queries to load each order's customer
```

Better:

- Fetch needed data in fewer deliberate queries
- Join or project only the fields you need
- Verify what the ORM actually emits

---

# ORM and Query Builder Risks

- Hidden filters
- Unexpected joins
- Eager loading side effects
- Count query differs from data query
- In-memory merging after SQL execution
- Duplicate parent rows from one-to-many joins

Do not assume the repository method name describes the real SQL.

---

# Tool 3: `pg_stat_statements`

```sql
SELECT
  query,
  calls,
  total_exec_time,
  mean_exec_time,
  rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
```

Use it to answer:

- What is slow
- What is frequent
- What is expensive overall

---

# Tool 4: `auto_explain`

Use it when manual testing is not enough.

Example settings to discuss:

```sql
LOAD 'auto_explain';
SET auto_explain.log_min_duration = '500ms';
SET auto_explain.log_analyze = on;
SET auto_explain.log_buffers = on;
SET auto_explain.log_nested_statements = on;
```

- Especially useful when ORM-generated SQL is part of the problem

---

# Connection Pool Tuning

- Too few connections can starve the application
- Too many connections can overwhelm PostgreSQL
- Pool size should come from measured concurrency, not user count

For an 8-CPU backend host, many teams start testing around 16 to 32 pooled connections per service, then adjust from measurements.

---

# How to Tune a Pool Safely

Measure:

- Request latency
- Pool wait time
- Active sessions
- PostgreSQL CPU saturation
- Slow-query frequency

If a larger pool increases contention without improving latency, the pool is already too large.

---

# Long Transactions and Lock Pressure

Bad pattern:

```text
BEGIN
  update local row
  call external system
  wait
  update again
COMMIT
```

- Long transactions hold resources open
- They increase blocking risk
- They slow vacuum progress

---

# Partitioning Large History Tables

Best fit:

- Large date-based history or event tables
- Queries usually filter by time range
- Retention is time-based

Example:

```sql
CREATE TABLE event_history (
  id bigint NOT NULL,
  entity_id bigint NOT NULL,
  status text NOT NULL,
  submission_date date NOT NULL,
  payload jsonb NOT NULL
) PARTITION BY RANGE (submission_date);
```

---

# Partitioning Helps Only When Queries Prune

- Partitioning is not a substitute for missing indexes
- If queries do not filter by the partition key, scan volume may stay high
- Good partitioning starts with real access patterns

---

# The Incident That Changes the Room

## "The SQL returns 3 rows in PostgreSQL, but the backend endpoint returns 10"

- This is not rare
- This is not always a database problem
- This is often a query-path verification problem

---

# Result Mismatch: Most Likely Causes

- Different SQL than expected
- Different bind parameters
- Different database, schema, or role
- Hidden ORM predicates
- One-to-many join duplication
- Multiple queries merged in memory
- Response mapping bug
- Cache or stale code path

---

# Result Mismatch Runbook

1. Reproduce the endpoint with fixed inputs
2. Capture the exact emitted SQL
3. Capture bind parameter values and types
4. Run that SQL manually as the same database user
5. Compare session context
6. Check joins, pagination, and mapping
7. Run `EXPLAIN ANALYZE` if safe

---

# Session Context Checks

```sql
SELECT current_database(), current_schema, current_user;
SHOW search_path;
SHOW TimeZone;
SHOW transaction_isolation;
```

Ask:

- Same database?
- Same schema?
- Same role?
- Same timezone?
- Same transaction visibility?

---

# Join Duplication Example

```sql
SELECT o.id, o.customer_id, i.id AS item_id
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.status = 'PAID';
```

- One order with many items becomes many rows
- The endpoint may show duplicates unless the query or mapping handles this intentionally

---

# Practical Team Ownership

- Frontend: request volume, retries, pagination
- Backend: query shape, ORM behavior, pool settings, transaction boundaries
- DBA: visibility, shared capacity, indexes, monitoring, slow-query analysis

Performance improves fastest when ownership is explicit.

---

# What Good Looks Like After This Workshop

- Slow queries are found quickly
- Indexes are created from real access patterns
- Query plans are reviewed before release
- Pools are tuned from measurements
- Large tables are partitioned only where it helps
- Backend-vs-database mismatches are diagnosed methodically

---

# First Actions After the Session

- Identify top slow queries with `pg_stat_statements`
- Review one critical endpoint with `EXPLAIN ANALYZE`
- Audit foreign key indexes on hot tables
- Review connection pool size against real concurrency
- Choose one real incident and run the mismatch checklist

---

# Closing Message

- Database performance problems are diagnosable
- Many major wins come from disciplined fundamentals
- The goal is not only to make PostgreSQL faster
- The goal is to make the whole request path predictable, observable, and correct
