# Learner Tasks: Advanced Database Optimization and Tuning

These tasks let you practice the concepts from the workshop on your own. Each task is self-contained and tells you what tools you need.

## Exercise Modes

- **Live Gateway** — requires the running Spring Boot project. Start it with `docker-compose up --build` or the four `mvn spring-boot:run` commands from the README.
- **Local PostgreSQL** — requires a local PostgreSQL instance (any version 14+). Tasks provide all DDL and seed data.
- **Code Analysis** — requires only a text editor and the project source code.

## Quick Reference

| Task | Concept | Mode | Difficulty |
|------|---------|------|------------|
| 1 | Gateway auth + request pipeline | Live Gateway | Beginner |
| 2 | Rate limiting + shared-server protection | Live Gateway | Beginner |
| 3 | Circuit breaker, timeout, fallback | Live Gateway | Intermediate |
| 4 | EXPLAIN and EXPLAIN ANALYZE | Local PostgreSQL | Beginner–Intermediate |
| 5 | Composite and partial index design | Local PostgreSQL | Intermediate |
| 6 | Foreign key index audit | Local PostgreSQL | Intermediate |
| 7 | N+1 query pattern and code analysis | Code Analysis | Intermediate |
| 8 | Offset vs keyset pagination | Local PostgreSQL | Intermediate |
| 9 | Pool sizing and long transaction design | Code Analysis | Intermediate–Advanced |
| 10 | Row-count mismatch runbook | Local PostgreSQL | Advanced |
| 11A | Partitioning design and pruning | Local PostgreSQL | Advanced |
| 11B | pg_stat_statements and auto_explain | Local PostgreSQL | Advanced |
| 11C | Frontend responsibility | Live Gateway + Reflection | Beginner–Advanced |

---

## Task 1: Gateway Authentication and the Request Pipeline

**Mode:** Live Gateway | **Difficulty:** Beginner

### Objective

Verify that JWT enforcement happens at the gateway layer and that the backend services are unprotected by themselves. Connect this to the shared-server principle: the gateway is the single enforcement point before traffic reaches downstream systems.

### Your Tasks

1. Start all services. Confirm each is running by calling its actuator health endpoint directly:

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8080/actuator/health
```

2. Get a JWT token from the gateway:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"learner01","roles":["ROLE_USER"]}' | jq -r '.token')
echo $TOKEN
```

3. Call all three routes through the gateway with the token:

```bash
curl -s http://localhost:8080/api/claims -H "Authorization: Bearer $TOKEN" | jq
curl -s http://localhost:8080/api/members -H "Authorization: Bearer $TOKEN" | jq
curl -s http://localhost:8080/api/cards -H "Authorization: Bearer $TOKEN" | jq
```

4. Now call the same routes **without** the Authorization header. Record the HTTP status code returned by the gateway:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/claims
```

5. Call the backend claim service **directly** without any token:

```bash
curl -s http://localhost:8081/claims | jq
```

Record the response. Compare it to step 4.

### Expected Outcome

Step 4 returns `401` from the gateway. Step 5 returns data successfully — the backend services have no authentication of their own. JWT validation is enforced only at the gateway.

### Debrief Points

- The backend services trust all traffic that arrives. In production they would typically be on a private network with no public exposure.
- The gateway is the shared enforcement point for auth, rate limiting, and routing. This is analogous to a database connection pool being the shared enforcement point for connection limits — both protect downstream resources from uncontrolled access.

---

## Task 2: Rate Limiting and Shared-Server Resource Protection

**Mode:** Live Gateway | **Difficulty:** Beginner

### Objective

Observe the gateway rate limiter firing under rapid requests, and connect rate limiting to the broader concept of protecting a shared server from burst traffic.

### Your Tasks

1. Get a token for `user-a`:

```bash
TOKEN_A=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"user-a","roles":["ROLE_USER"]}' | jq -r '.token')
```

2. Fire 15 sequential requests with `user-a` and record the HTTP status of each:

```bash
for i in $(seq 1 15); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/api/cards \
    -H "Authorization: Bearer $TOKEN_A")
  echo "Request $i: $STATUS"
done
```

Note which request number first returns `429`.

3. Get a second token for `user-b`. Fire 15 requests with `user-b` immediately after. Observe that `user-b` gets its own fresh burst budget and does not inherit `user-a`'s consumed quota.

4. Open `api-gateway/src/main/resources/application.yml`. Find the `redis-rate-limiter` block. Answer:
   - What is `replenishRate`?
   - What is `burstCapacity`?
   - How do these two values explain when you started seeing `429` in step 2?

5. Open `api-gateway/src/main/java/com/example/gateway/config/GatewayRateLimitConfig.java`. Answer: what Redis key is used when the request carries a valid JWT? What key is used when there is no authentication?

### Expected Outcome

`429` appears starting around request 11 — after the burst capacity of 10 is exhausted. `user-b` starts fresh. The key resolver uses the JWT subject (`username`) as the Redis key, falling back to the remote IP when no token is present.

### Debrief Points

- Rate limiting is a direct operational parallel to connection pool limits. Both prevent one caller's burst from consuming all available shared-server capacity.
- Per-user rate buckets mean that a single heavy caller does not degrade experience for everyone else — the same principle behind per-application connection pool limits in PostgreSQL.

---

## Task 3: Circuit Breaker, Timeout, and the Long-Call Analogy

**Mode:** Live Gateway | **Difficulty:** Intermediate

### Objective

Observe the difference between a single-call timeout and a circuit breaker state machine. Connect the slow-endpoint behavior to the lecture's long-transaction pattern.

### Your Tasks

1. Get a token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"learner01","roles":["ROLE_USER"]}' | jq -r '.token')
```

2. Call the slow endpoint **under** the circuit breaker timeout:

```bash
time curl -s http://localhost:8080/api/claims/slow?delayMs=1000 \
  -H "Authorization: Bearer $TOKEN" | jq
```

Record the response and wall-clock time.

3. Call the slow endpoint **over** the timeout:

```bash
time curl -s http://localhost:8080/api/claims/slow?delayMs=3000 \
  -H "Authorization: Bearer $TOKEN" | jq
```

Record what response you receive and how long the call took. Open `application.yml` and find `timeout-duration` under `timelimiter`. Which component fired — the time limiter or the circuit breaker?

4. Check the circuit breaker state before the next step:

```bash
curl -s http://localhost:8080/actuator/circuitbreakers | jq
```

5. Stop the claim-service process. Make **3 consecutive** calls to a normal claims route:

```bash
for i in 1 2 3; do
  curl -s -o /dev/null -w "Request $i: %{http_code}\n" \
    http://localhost:8080/api/claims/CLM-777 \
    -H "Authorization: Bearer $TOKEN"
done
```

Check the circuit breaker state again. Restart claim-service. Wait 10 seconds and make one more call. Check state a third time.

6. Open `application.yml` and find the `claimServiceCB` configuration block. In your own words, explain what each of these does: `sliding-window-size`, `minimum-number-of-calls`, `failure-rate-threshold`, `wait-duration-in-open-state`.

### Expected Outcome

Step 3 returns a fallback response quickly (around 2s, not 3s) — the time limiter cuts the call short. Steps 4–5 show the circuit moving from `CLOSED` to `OPEN` after enough failures, then recovering to `HALF_OPEN` and back to `CLOSED`.

### Debrief Points

- The `delayMs` endpoint is the in-memory equivalent of a database transaction held open while waiting for an external HTTP response. The gateway behaves the same way PostgreSQL does when a connection is occupied for longer than expected.
- A circuit breaker that opens under sustained failure protects all downstream connections from being blocked by a single failing service — the same logic behind `idle_in_transaction_session_timeout` in PostgreSQL.

---

## Task 4: Reading EXPLAIN and EXPLAIN ANALYZE

**Mode:** Local PostgreSQL | **Difficulty:** Beginner–Intermediate

### Objective

Read real execution plans from PostgreSQL and identify a sequential scan, row estimation error, and actual execution time. All later SQL tasks build on the table you create here.

### Setup

Run this in psql or any SQL client:

```sql
CREATE TABLE claims (
  id         bigserial PRIMARY KEY,
  member_id  bigint      NOT NULL,
  status     text        NOT NULL,
  claim_date date        NOT NULL,
  amount     numeric(12,2) NOT NULL
);

INSERT INTO claims (member_id, status, claim_date, amount)
SELECT
  (random() * 10000 + 1)::bigint,
  (ARRAY['PENDING','APPROVED','REJECTED','PAID'])[ceil(random() * 4)::int],
  current_date - (random() * 730)::int,
  (random() * 5000)::numeric(12,2)
FROM generate_series(1, 100000);

ANALYZE claims;
```

### Your Tasks

1. Run `EXPLAIN` (without `ANALYZE`) on the query below. Record the top-level node type and the estimated row count:

```sql
EXPLAIN
SELECT id, member_id, status, amount
FROM claims
WHERE member_id = 42
  AND status = 'PENDING'
ORDER BY claim_date DESC
LIMIT 20;
```

2. Run `EXPLAIN ANALYZE` on the same query. Record:
   - The actual row count returned
   - The number of rows removed by filter
   - The total execution time

3. Compare estimated rows from step 1 to actual rows from step 2. Is there a large difference? What causes estimation errors in PostgreSQL?

4. Is PostgreSQL using a sequential scan or an index scan? Why is no index available for this query?

5. Match your plan output to one of the two patterns from the lecture:

```text
-- Bad pattern
Seq Scan on claims
  Filter: (...)
  Rows Removed by Filter: <large number>
Sort
  Sort Key: claim_date DESC

-- Better pattern
Index Scan using <index_name> on claims
  Index Cond: (...)
```

### Expected Outcome

The plan shows a sequential scan with many rows removed by the filter, followed by a sort. Estimated rows may differ significantly from actual rows because PostgreSQL cannot estimate multi-column predicate selectivity without a matching index or extended statistics.

### Debrief Points

- `EXPLAIN` is safe in production — it does not execute the query. `EXPLAIN ANALYZE` executes it, so use it carefully on expensive queries.
- The gap between estimated and actual rows is the most important signal. A large gap means the planner chose a plan based on wrong assumptions.

---

## Task 5: Composite and Partial Index Design

**Mode:** Local PostgreSQL | **Difficulty:** Intermediate

### Objective

Design indexes that match real query patterns, compare composite vs single-column indexes, and create a partial index for an active subset.

### Prerequisite

Complete Task 4 (the `claims` table must exist with 100,000 rows).

### Your Tasks

1. Write a `CREATE INDEX` statement for the query from Task 4 **before** creating it. Use the column-order rule from the lecture: `equality filters → range filters → sort columns`. Justify your column order in a comment.

2. Create the index and re-run `EXPLAIN ANALYZE`. Record the new plan node type and execution time. How much did execution time improve?

3. Drop your composite index. Create three separate single-column indexes instead:

```sql
CREATE INDEX idx_claims_member_id  ON claims (member_id);
CREATE INDEX idx_claims_status     ON claims (status);
CREATE INDEX idx_claims_claim_date ON claims (claim_date DESC);
```

Run `EXPLAIN ANALYZE` again. Compare:
   - Which index(es) does PostgreSQL actually use?
   - How does execution time compare to the composite index?

4. Drop the three single-column indexes. Create a partial index for pending claims only:

```sql
CREATE INDEX idx_claims_pending_date
ON claims (claim_date DESC)
WHERE status = 'PENDING';
```

Run `EXPLAIN ANALYZE` with `status = 'PENDING'` in the filter. Then change the filter to `status = 'APPROVED'` and run again. When does PostgreSQL use the partial index and when does it not?

5. Design an index for this different query shape. Write the `CREATE INDEX` DDL and explain why the composite index from step 2 would or would not help this query:

```sql
SELECT id, member_id, amount
FROM claims
WHERE claim_date >= '2025-01-01'
  AND claim_date < '2026-01-01'
ORDER BY claim_date DESC
LIMIT 100;
```

### Expected Outcome

The composite index eliminates the sequential scan and sort. Three single-column indexes typically lead to worse plans than one well-designed composite index — PostgreSQL may pick only one of them or merge them at extra cost. The partial index is smaller and faster but only fires when the query predicate matches its `WHERE` clause exactly.

### Debrief Points

- Column order in a composite index is not arbitrary. Putting a range or sort column before an equality column makes the index less useful.
- A partial index trades coverage for size and write performance. It is ideal for queries that always target a small active subset.

---

## Task 6: Foreign Key Index Audit

**Mode:** Local PostgreSQL | **Difficulty:** Intermediate

### Objective

Experience the join-performance impact of an unindexed foreign key and practice the index-usage monitoring query used after a release.

### Prerequisite

Complete Task 4 (the `claims` table must exist with 100,000 rows).

### Setup

```sql
CREATE TABLE claim_items (
  id           bigserial PRIMARY KEY,
  claim_id     bigint        NOT NULL REFERENCES claims(id),
  service_code text          NOT NULL,
  line_amount  numeric(12,2) NOT NULL
);

INSERT INTO claim_items (claim_id, service_code, line_amount)
SELECT
  (random() * 99999 + 1)::bigint,
  'SVC-' || (random() * 100)::int::text,
  (random() * 1000)::numeric(12,2)
FROM generate_series(1, 300000);

ANALYZE claim_items;
```

### Your Tasks

1. Run `EXPLAIN ANALYZE` on this join **before** adding any index on `claim_items.claim_id`. Record the join strategy (Nested Loop, Hash Join, or Merge Join) and execution time:

```sql
EXPLAIN ANALYZE
SELECT c.id, c.status, ci.service_code, ci.line_amount
FROM claims c
JOIN claim_items ci ON ci.claim_id = c.id
WHERE c.member_id = 42
  AND c.status = 'PENDING';
```

2. Add the missing foreign key index:

```sql
CREATE INDEX idx_claim_items_claim_id ON claim_items (claim_id);
```

Run `EXPLAIN ANALYZE` again. Record the new join strategy and execution time.

3. Query index usage statistics:

```sql
SELECT
  schemaname,
  relname,
  indexrelname,
  idx_scan
FROM pg_stat_user_indexes
WHERE relname = 'claim_items'
ORDER BY idx_scan DESC;
```

What does `idx_scan = 0` mean for an index? In production, when would a consistently zero `idx_scan` count be a reason to drop an index?

4. Answer this question: PostgreSQL enforces the foreign key constraint on `claim_items.claim_id`. Does that mean the column is indexed? Now explain what happens to PostgreSQL's performance when you run `DELETE FROM claims WHERE id = 42` on an unindexed foreign key. Why?

### Expected Outcome

Before the index, PostgreSQL uses a sequential scan on `claim_items` and a Hash Join. After the index, it can use an index scan and a more selective Nested Loop. Performance on the filtered join improves significantly. The `DELETE` question reveals that PostgreSQL must verify no child rows exist before deleting a parent row — without an index on the FK column, it scans the entire child table for every parent delete.

### Debrief Points

- PostgreSQL documents that foreign keys are not automatically indexed. This is one of the most common sources of hidden slowness in growing systems.
- Adding a FK index improves joins, parent-record lookups, and parent deletes. The cost is a small increase in insert and update overhead on the child table.

---

## Task 7: N+1 Query Pattern and Code Analysis

**Mode:** Code Analysis | **Difficulty:** Intermediate

### Objective

Identify the N+1 pattern by reading code, write the corrected SQL, and explain why HTTP-level fan-out in microservices is a more severe form of the same problem.

### Your Tasks

1. Read the following pseudocode. Identify the N+1 problem and write the single SQL query that replaces the loop:

```java
// Pseudocode — not real project code
List<Member> members = memberRepository.findAll();   // 1 query → 100 members
List<MemberSummary> result = new ArrayList<>();

for (Member m : members) {
    // 1 query per member → 100 additional queries
    List<Claim> claims = claimRepository.findByMemberId(m.getId());
    result.add(new MemberSummary(m, claims));
}
return result;
```

Use the `claims` table from Task 4 and this hypothetical `members` table structure:

```sql
-- hypothetical table
CREATE TABLE members (
  id   bigserial PRIMARY KEY,
  name text NOT NULL,
  tier text NOT NULL
);
```

Write the single JOIN query that loads all members and their claims in one database round-trip.

2. The real project uses separate microservices: member-service (port 8082) and claim-service (port 8081). Explain why an HTTP-level N+1 (one HTTP call to member-service per member, then N HTTP calls to claim-service) is more damaging than a database N+1.

   Consider: network round-trip time, connection pool consumption, downstream service load, and error amplification.

3. Run this shell script to simulate HTTP N+1 vs a single batch call. Record total wall-clock time for each approach:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"learner01","roles":["ROLE_USER"]}' | jq -r '.token')

# Approach A: N individual calls
time for ID in CLM-1001 CLM-1002 CLM-1003 CLM-1004 CLM-1005; do
  curl -s http://localhost:8080/api/claims/$ID \
    -H "Authorization: Bearer $TOKEN" > /dev/null
done

# Approach B: one list call
time curl -s http://localhost:8080/api/claims \
  -H "Authorization: Bearer $TOKEN" > /dev/null
```

4. If you were adding a `/api/member-summary` endpoint to this gateway project, which service should own the aggregation logic? What would be the trade-off between doing the JOIN at the database level vs calling two services and merging in application code?

### Expected Outcome

The corrected SQL uses a single `JOIN` or a `LEFT JOIN` to load all members and their claims. Approach A in step 3 is measurably slower than Approach B even with tiny mock payloads because each HTTP call has fixed network and connection overhead. HTTP-level N+1 combines database N+1 costs with service-to-service latency, connection pool consumption in two services, and multiplied retry risk on failures.

### Debrief Points

- N+1 often hides behind clean-looking object graphs in ORM code. The database sees the traffic even if the application code looks innocent.
- In microservices, aggregation is a design decision. Doing it in SQL is efficient but creates coupling. Doing it in an API Gateway or backend-for-frontend layer preserves service independence at the cost of extra network calls.

---

## Task 8: Offset vs Keyset Pagination

**Mode:** Local PostgreSQL | **Difficulty:** Intermediate

### Objective

Observe the performance degradation of deep `OFFSET` pagination and design a keyset replacement that stays fast regardless of page depth.

### Prerequisite

Complete Task 4 (the `claims` table must exist with 100,000 rows).

### Your Tasks

1. Run both pagination approaches and compare execution plans and times:

```sql
-- Offset approach: page 400 of 20
EXPLAIN ANALYZE
SELECT id, member_id, status, claim_date, amount
FROM claims
ORDER BY claim_date DESC
OFFSET 8000
LIMIT 20;

-- Keyset approach: using a cursor value from the last row of the previous page
EXPLAIN ANALYZE
SELECT id, member_id, status, claim_date, amount
FROM claims
WHERE claim_date < '2025-06-01'
ORDER BY claim_date DESC
LIMIT 20;
```

Record the plan node, rows scanned, and execution time for each.

2. Create an index on `claim_date DESC`. Re-run both queries. Does the index help the offset query equally as much as the keyset query? Why or why not?

3. Increase the offset to 50,000 and run `EXPLAIN ANALYZE` again. Explain in one sentence why the offset query gets slower as the offset value grows, even with an index.

4. Design the API contract for a keyset-paginated claims endpoint. Write:
   - The URL for the first page
   - The URL for the second page, given that the last row on page one had `claim_date = '2025-09-14'` and `id = 72341`
   - The JSON response structure that includes the cursor the frontend needs for the next request

5. Name one scenario where offset pagination is still the right choice despite its performance cost.

### Expected Outcome

The offset query scans and discards all rows before the offset. The keyset query jumps directly to the cursor row using the index. Deep offsets get progressively slower while keyset queries remain constant-time because they translate to an indexed `WHERE` predicate.

### Debrief Points

- Offset pagination is simple to implement but creates a performance cliff at scale. The cliff is invisible in development where tables are small.
- A stable sort key is required for keyset pagination. Ties on the cursor column must be broken by a secondary key (usually the primary key).

---

## Task 9: Connection Pool Sizing and Long Transaction Design

**Mode:** Code Analysis | **Difficulty:** Intermediate–Advanced

### Objective

Apply the measurement-based pool-sizing principle and identify the exact resources held by a long transaction pattern.

### Your Tasks

1. A backend service runs on an 8-CPU host with HikariCP configured at `maximumPoolSize: 200`. Using the reasoning from the lecture, answer:
   - What problems can a pool of 200 cause on a PostgreSQL server shared with other applications?
   - What four metrics would you measure before deciding whether to increase or decrease the pool?
   - What would a healthy starting range look like for an 8-CPU host?

2. Analyse this pseudocode transaction:

```java
@Transactional
public ClaimResult submitClaim(ClaimRequest req) {
    Claim claim = claimRepo.save(new Claim(req));          // DB insert
    ProviderResponse resp = providerClient.authorize(req); // External HTTP — up to 10s
    claim.setAuthCode(resp.getAuthCode());
    claimRepo.save(claim);                                 // DB update
    return ClaimResult.from(claim);
}
```

Answer:
   - What database resource is held during the `providerClient.authorize()` call?
   - Name three categories of damage this causes on the shared PostgreSQL server.
   - Rewrite the method signature and transaction boundaries in pseudocode to eliminate the risk.

3. Open `ClaimController.java` in the real project. The `/slow` endpoint calls `Thread.sleep(delayMs)`. If this sleep were inside a real `@Transactional` method using JPA, what PostgreSQL resources would remain held during the sleep? What would happen to other requests trying to write to the same rows?

4. The gateway rate limiter allows a burst of 10 requests. Each request hits the slow endpoint with `delayMs=10000`. Calculate:
   - Maximum number of claim-service threads occupied simultaneously
   - If each thread holds a database connection, how many connections does that consume from the pool?
   - Compare this to the `maximumPoolSize: 200` from step 1. Does the pool size matter here?

### Expected Outcome

The `@Transactional` method holds a database connection from the pool for the entire duration of the external call. The three damage categories are: connection starvation (other requests wait), lock pressure (rows locked prevent other transactions), and vacuum delay (open transactions prevent dead-tuple cleanup). The fix is to commit the initial insert, make the external call outside any transaction, then open a new transaction for the update.

### Debrief Points

- A connection held during an external call is not idle — it is occupied and cannot serve other requests. Pool size does not fix a design that holds connections unnecessarily long.
- The circuit breaker in this project protects other callers from a slow claim-service, but it does not fix the underlying connection-holding problem inside the service.

---

## Task 10: Row-Count Mismatch Runbook

**Mode:** Local PostgreSQL | **Difficulty:** Advanced

### Objective

Follow the mismatch verification runbook step by step on a controlled scenario where you already know the answer. Build the habit of comparing exact SQL before blaming any layer.

### Prerequisite

Complete Tasks 4 and 6 (both `claims` and `claim_items` tables must exist).

### Setup

Mark a small subset of claims as PAID with a known member:

```sql
UPDATE claims
SET status = 'PAID'
WHERE member_id = 42
  AND id IN (
    SELECT id FROM claims WHERE member_id = 42 ORDER BY id LIMIT 5
  );
```

### Your Tasks

1. Run the developer's manual test query. Record the row count:

```sql
SELECT id, status
FROM claims
WHERE status = 'PAID'
  AND member_id = 42;
```

2. Run the actual backend SQL (the query the endpoint is generating). Record the row count:

```sql
SELECT c.id, c.status, ci.id AS item_id, ci.service_code
FROM claims c
JOIN claim_items ci ON ci.claim_id = c.id
WHERE c.status = 'PAID'
  AND c.member_id = 42;
```

3. List every difference between the two queries that could explain the row-count difference. Be specific: column list, joined tables, predicate values, bind parameters.

4. Run the session-context verification queries from the runbook:

```sql
SELECT current_database(), current_schema, current_user;
SHOW search_path;
SHOW TimeZone;
SHOW transaction_isolation;
```

In a multi-tenant application where the backend connects as a different role than the DBA, which of these values would be most likely to produce a silent row-count difference?

5. Write three alternative versions of the backend query that each return **one row per claim** (not one row per claim item). For each version, explain the trade-off:

   - Version A: remove the join entirely
   - Version B: aggregate items per claim using a subquery or `json_agg`
   - Version C: use `DISTINCT ON (c.id)`

6. In a real Spring Boot service, what two configuration lines would you add to `application.properties` to log the exact SQL emitted by Hibernate, including bind parameter values?

### Expected Outcome

Query 1 returns 5 rows. Query 2 returns more rows (one per claim item for each of the 5 claims). The join multiplies parent rows — this is the same pattern as Lab 3 in the workshop, now reproduced on real data. The three SQL fixes each have a different correctness implication depending on whether the endpoint should return claim-level or item-level data.

### Debrief Points

- The first rule of mismatch debugging is to capture the exact SQL with exact bind parameters, not a guessed equivalent.
- `DISTINCT ON` is a common quick fix but may silently discard data. Understand the business rule before choosing between aggregation and deduplication.

---

## Task 11A: Partitioning Design and Partition Pruning

**Mode:** Local PostgreSQL | **Difficulty:** Advanced

### Objective

Create a range-partitioned table, verify partition pruning with `EXPLAIN`, and identify the query pattern that defeats partitioning.

### Your Tasks

1. Create a partitioned history table with quarterly child partitions for 2025:

```sql
CREATE TABLE claim_history (
  id              bigserial NOT NULL,
  claim_id        bigint    NOT NULL,
  status          text      NOT NULL,
  submission_date date      NOT NULL,
  payload         jsonb     NOT NULL
) PARTITION BY RANGE (submission_date);

CREATE TABLE claim_history_2025_q1
  PARTITION OF claim_history
  FOR VALUES FROM ('2025-01-01') TO ('2025-04-01');

CREATE TABLE claim_history_2025_q2
  PARTITION OF claim_history
  FOR VALUES FROM ('2025-04-01') TO ('2025-07-01');

CREATE TABLE claim_history_2025_q3
  PARTITION OF claim_history
  FOR VALUES FROM ('2025-07-01') TO ('2025-10-01');

CREATE TABLE claim_history_2025_q4
  PARTITION OF claim_history
  FOR VALUES FROM ('2025-10-01') TO ('2026-01-01');

INSERT INTO claim_history (claim_id, status, submission_date, payload)
SELECT
  (random() * 99999 + 1)::bigint,
  (ARRAY['SUBMITTED','REVIEWED','CLOSED'])[ceil(random() * 3)::int],
  date '2025-01-01' + (random() * 364)::int,
  '{}'::jsonb
FROM generate_series(1, 200000);
```

2. Run `EXPLAIN` (not `ANALYZE`) on a query that filters by `submission_date`. Verify that only one or two partitions appear in the plan:

```sql
EXPLAIN
SELECT id, claim_id, status
FROM claim_history
WHERE submission_date >= '2025-04-01'
  AND submission_date < '2025-07-01';
```

3. Now run `EXPLAIN` on a query that does **not** filter by `submission_date`. Count how many partitions appear in the plan:

```sql
EXPLAIN
SELECT id, claim_id, status
FROM claim_history
WHERE status = 'SUBMITTED';
```

4. Answer: a team proposes partitioning their slow `claim_history` table to fix a `SELECT *` query that has no date filter. Is this the right fix? What should they do instead?

5. Beyond query performance, name two operational benefits of partitioning a time-based history table.

### Expected Outcome

Step 2's plan scans only `claim_history_2025_q2`. Step 3's plan scans all four partitions. Partitioning helps only when queries filter on the partition key — without pruning, PostgreSQL must scan every partition.

### Debrief Points

- Partitioning is a maintenance and scale tool, not a replacement for indexes.
- The most common mistake is partitioning a table and then writing queries that do not filter on the partition key.

---

## Task 11B: pg_stat_statements and auto_explain

**Mode:** Local PostgreSQL | **Difficulty:** Advanced

### Objective

Use the two most important PostgreSQL observability tools to identify expensive queries and understand how to capture slow production plans automatically.

### Your Tasks

1. Enable `pg_stat_statements` if it is not already loaded:

```sql
-- Run as superuser
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

Run a mix of queries from Tasks 4–6 to generate some statistics. Then run the top-10-by-total-time query:

```sql
SELECT
  LEFT(query, 80)     AS query_snippet,
  calls,
  total_exec_time::int AS total_ms,
  mean_exec_time::int  AS mean_ms,
  rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
```

2. Answer: a query appears with `total_exec_time = 120000 ms` and `mean_exec_time = 2 ms`. Another query has `total_exec_time = 3000 ms` and `mean_exec_time = 3000 ms`. Which is more urgent to fix first and why? What does `calls` tell you about each?

3. Answer: what is the difference between `auto_explain.log_min_duration` and `log_min_duration_statement` at the `postgresql.conf` level? When would you need `auto_explain` specifically rather than just slow-query logging?

4. Write the session-level `auto_explain` setup commands from the lecture notes. Explain why `auto_explain.log_nested_statements = on` is critical when diagnosing N+1 queries generated by an ORM.

5. A DBA team is concerned about `auto_explain` overhead. What two configuration choices would you recommend to minimize the impact while still capturing useful plans?

### Expected Outcome

The high-`calls` / low-`mean` query dominates `total_exec_time` by volume — it is the most important fix because it runs constantly. The single slow query (high `mean`, low `calls`) is urgent only if it is user-facing. `auto_explain` is needed because `log_min_duration_statement` logs the query text but not the plan; you need the plan to know whether a slow query could be fixed with an index.

### Debrief Points

- `pg_stat_statements` is the first tool to reach for when investigating which queries the server spends the most time on. It works across sessions and persists through query resets.
- `auto_explain` with `log_nested_statements = on` can reveal 100+ individual statements inside what looks like a single ORM method call.

---

## Task 11C: Frontend Responsibility

**Mode:** Live Gateway + Reflection | **Difficulty:** Beginner–Advanced

### Objective

Demonstrate that frontend behavior creates measurable database load, design a safe retry strategy, and assign ownership across the full stack.

### Your Tasks

1. Get a token and send a burst of 12 requests to `/api/members`:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"learner01","roles":["ROLE_USER"]}' | jq -r '.token')

for i in $(seq 1 12); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    http://localhost:8080/api/members \
    -H "Authorization: Bearer $TOKEN")
  echo "$i: $STATUS"
done
```

Count how many `429` responses you receive.

2. A search box sends a request on every keystroke. A user types the word "submission" (10 characters). How many requests does that generate? With a rate limit of 5 req/s and burst of 10, how many of those requests would be rate-limited?

3. The circuit breaker for claims is open and the gateway is returning `503` from the fallback. Design a safe frontend retry strategy. Specify:
   - Maximum retry attempts
   - Backoff formula for the delay between attempts (write the formula, not just the word "exponential")
   - The condition under which the frontend should stop retrying permanently and show a user-facing error

4. Reflection — no code required: The backend currently uses offset pagination (`OFFSET N LIMIT 50`). The DBA team says queries are getting slower as the table grows. The frontend team says they did not design the API. The backend team says the DBA should add more indexes. Assign the correct responsibility to each team:
   - What should the **frontend** change?
   - What should the **backend** change?
   - What should the **DBA** do?

### Expected Outcome

Step 1 shows `429` starting around request 11. Step 2 generates 10 requests, of which the burst absorbs the first 10 and the 11th would be rate-limited — demonstrating that a fast typist with no debounce can exhaust a per-user quota with a single word. Step 3 produces a retry formula such as `delay = min(base * 2^attempt + jitter, maxDelay)`.

### Debrief Points

- Frontend teams directly control the request rate that reaches the database path. Debounce, cancellation, and minimum-length guards are not cosmetic — they are load-control mechanisms.
- Performance ownership is shared. Indexing cannot fix a query design problem. Keyset pagination requires collaboration between the API contract (backend), the cursor handling (frontend), and the index strategy (DBA).
