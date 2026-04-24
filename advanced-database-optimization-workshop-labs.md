# Workshop Labs: Advanced Database Optimization and Tuning

## Lab 1: Diagnose a Slow Query and Choose an Index

### Objective

Use query shape and `EXPLAIN ANALYZE` findings to identify a likely bottleneck and propose an index that matches the real access pattern.

### Scenario

Users report that the order-history page is slow.

Application query:

```sql
SELECT id, customer_id, status, total_amount, created_at
FROM orders
WHERE customer_id = 42
  AND status = 'PAID'
ORDER BY created_at DESC
LIMIT 50;
```

Observed plan:

```text
Limit
  -> Sort
       Sort Key: created_at DESC
       -> Seq Scan on orders
            Filter: ((customer_id = 42) AND (status = 'PAID'))
            Rows Removed by Filter: 980000
```

### Participant Prompts

- What is the main performance problem in this plan?
- Why is PostgreSQL sorting here?
- What index would you propose?
- Why is that index better than three separate single-column indexes?

### Expected Diagnosis Path

- The query is scanning too much of the table.
- PostgreSQL is filtering after reading many rows.
- PostgreSQL is then sorting rows because the access path does not match the `ORDER BY`.
- The best candidate is a composite index aligned to the query.

### Expected Answer

```sql
CREATE INDEX idx_orders_customer_status_created_at
ON orders (customer_id, status, created_at DESC);
```

### Debrief Points

- Equality filters come first, then sort support.
- The goal is to reduce work, not only reduce latency.
- A good index should match how the endpoint actually reads data.

## Lab 2: Pool Pressure, Long Transactions, and Waiting Requests

### Objective

Recognize the difference between application waiting and database saturation, then choose safer tuning and transaction changes.

### Scenario

The backend team increased the connection pool because users complained about slow responses. After the change:

- PostgreSQL CPU increased sharply
- Active sessions increased
- More requests are waiting on database work
- Slow-query count increased

The endpoint flow:

```text
BEGIN
  update payment row
  call external provider
  wait for response
  update payment row
COMMIT
```

### Participant Prompts

- Is the larger pool definitely helping?
- What signs suggest the pool may now be too large?
- What is risky about the current transaction design?
- What two changes would you test first?

### Expected Diagnosis Path

- A bigger pool can increase contention instead of throughput.
- The application may have turned waiting in the app into waiting inside PostgreSQL.
- The transaction is held open during an external dependency call.
- The first fixes should target both concurrency and transaction length.

### Expected Answer

- Reassess pool size using measured latency, pool wait time, active sessions, and CPU.
- Break the transaction so the external call is outside the database transaction.

### Debrief Points

- Pool tuning is not a guess.
- Long transactions multiply the damage of slow external calls.
- Throughput improves when the database does less competing work.

## Lab 3: PostgreSQL Returns 3 Rows but the Backend Returns 10

### Objective

Use a strict verification sequence to diagnose row-count mismatches between direct SQL and backend endpoint results.

### Scenario

A team runs what they believe is the same query in PostgreSQL and gets 3 rows. The backend endpoint returns 10 items.

Captured backend SQL:

```sql
SELECT o.id, o.customer_id, i.id AS item_id
FROM orders o
JOIN order_items i ON i.order_id = o.id
WHERE o.status = 'PAID'
  AND o.customer_id = $1;
```

Manual SQL the team tested earlier:

```sql
SELECT id, customer_id
FROM orders
WHERE status = 'PAID'
  AND customer_id = 42;
```

### Participant Prompts

- Are these actually the same query?
- What new source of duplication appears in the backend SQL?
- What must the team verify before blaming PostgreSQL?
- What are three possible fixes depending on the business requirement?

### Expected Diagnosis Path

- The SQL is not the same.
- The backend query joins `order_items`, which can multiply rows.
- The team must compare emitted SQL, bind values, user, schema, and session context.
- The correct response depends on whether the endpoint wants one row per order or one row per item.

### Expected Answer

Possible fixes:

- Remove the join if item data is not needed
- Aggregate item data per order
- Use `DISTINCT` or `DISTINCT ON` only if one row per order is the requirement

### Debrief Points

- Never compare endpoint output to a guessed query.
- One-to-many joins are a common reason for wrong counts.
- A backend-result mismatch can be a SQL design issue, an ORM issue, or a mapping issue.
