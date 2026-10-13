# Heartbeat Monitor — Design Document

Backend Take-Home Assignment | Java, Spring Boot, PostgreSQL

### 1. Show us your database tables.

I used PostgreSQL with four main tables, managed through Flyway migrations.

| Table           | Purpose                                                                                                                            |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `checks`        | Stores check type, schedule, grace period, current status, next expected time, `next_action_at`, target URL and hashed ping token. |
| `outages`       | Tracks when a check goes DOWN and when it recovers.                                                                                |
| `alerts`        | Stores DOWN/RECOVERY alerts, delivery status, attempts, retry time and errors.                                                     |
| `check_history` | Records heartbeat pings, website responses and status transitions with timestamps.                                                 |

The database uses indexes on deadlines and alert retry times for efficient processing. Unique indexes prevent duplicate open outages and duplicate alert records.

### 2. How does your sweeper find what is due without reading all 10,000 checks?

I use deadline-based polling with database indexing, instead of scanning all checks.

The `Sweeper` runs every 10 seconds using Spring Boot's `@Scheduled`.

It queries only non-paused checks whose `next_action_at <= now`, ordered by the earliest deadline, in batches of 100.

The `next_action_at` column is indexed in PostgreSQL, so the database can efficiently locate due checks instead of reading all 10,000 rows.

For example, if only 5 of 10,000 checks are due, the sweeper processes those 5.

### 3. What stops the second, third and thousandth alert during one long outage? Name the exact thing.

I use outage-based alert deduplication, enforced through PostgreSQL unique indexes.

When a check becomes DOWN:

1. The system creates an entry in `outages`.
2. It creates one DOWN alert linked to that outage.
3. Subsequent sweeps detect that an open outage already exists, so no new DOWN alert is created.
4. When the check recovers, the outage is closed and one RECOVERY alert is generated.

The exact database protections are:

- `idx_outages_one_open_per_check` — prevents multiple open outages for the same check.
- `idx_alerts_outage_type` — prevents duplicate alerts of the same type for an outage.

Result: Whether the sweeper runs 10 times or 1,000 times during an outage, only one DOWN alert record is generated.

### 4. A ping and the sweeper land on the same check at the same instant. Which wins, and what makes that reliable?

I use pessimistic database locking and transactions to handle this race condition.

Both `PingService` and `MonitoringService` acquire a `PESSIMISTIC_WRITE` lock on the check row before updating its state.

Only one transaction can modify that check at a time.

Case 1 — Ping gets the lock first: The check is updated to UP, and its next expected time is recalculated. The sweeper then reads the updated state and does not mark an on-time check DOWN.

Case 2 — Sweeper gets the lock first: It marks the check DOWN and creates the outage alert. The subsequent ping recovers the check to UP and creates a recovery alert.

The database lock ensures both operations execute in a consistent order and prevents conflicting updates. The server determines timestamps rather than trusting client-provided times.

### 5. Heartbeat checks and website checks share almost everything and differ in one thing. Where does that difference live in your code?

I implemented passive monitoring for heartbeat checks and active monitoring for website checks.

Heartbeat — Passive Monitoring

The external background job calls our service after completing successfully.

- `PingController` receives the POST request.
- `PingService` records the heartbeat and updates the next expected time.
- `MonitoringService` detects missing heartbeats through the sweeper.

Website — Active Monitoring

Our service initiates the request to check whether a website is available.

- `WebsiteChecker` makes an HTTP GET request.
- A 2xx response within five seconds means success.
- A timeout, connection error or non-2xx response means a failed check.
- `WebsiteMonitoringService` processes the result and updates the check's status.

The `Sweeper` branches based on `CheckType.HEARTBEAT` or `CheckType.WEBSITE`.

What is shared? Both check types use the same `Check` entity, `CheckStatus` enum (UP/LATE/DOWN), outage records, alert creation through `AlertService`, and history storage.

I separated health detection into dedicated services while reusing the common data model and alert infrastructure, rather than building two completely independent monitoring systems.

### 6. A weekday job pings at 09:05 on Friday. How do you work out that the next one is due on Monday?

I use cron-based scheduling with Spring's `CronExpression`.

For example:

```
0 0 9 * * MON-FRI
```

This represents 9:00 AM, Monday through Friday.

When the job pings at 09:05 on Friday, `ScheduleCalculator` uses the cron expression to calculate the next valid execution time.

Because Saturday and Sunday are excluded, the next expected heartbeat is Monday at 09:00, not Saturday.

The implementation supports both cron schedules and fixed intervals. Cron expressions are evaluated in UTC, so scheduling behavior is consistent with the documented configuration.

### 7. One team's alert URL has been dead for six hours. What is your system doing, and why does it not delay others?

I use asynchronous alert delivery with retries, exponential-style backoff and persistent failure tracking.

When an outage occurs, `AlertService` stores the alert in PostgreSQL. `AlertRetryService` then handles delivery using a dedicated worker pool with 10 threads.

If the destination fails:

1. The HTTP request times out after five seconds or returns an error.
2. The failed attempt is recorded.
3. The alert is retried after 10, 30, 60, 120 and 300 seconds.
4. After six unsuccessful delivery attempts in total, its status becomes `FAILED`.
5. The failed alert remains in PostgreSQL and can be inspected through the alerts API.

Why doesn't it block others? Alert delivery happens in separate workers, not inside the sweeper or the main request thread.

One failed webhook does not block other alerts from being processed. A failed alert is also not retried continuously in a tight loop.

After six hours, that destination's alert would already be marked FAILED, rather than being retried indefinitely.

### 8. What breaks at 100,000 checks instead of 10,000?

The primary challenge becomes processing throughput rather than finding due checks.

The indexed `next_action_at` query can still efficiently find due records, but other bottlenecks become more significant:

- Sweeper throughput: The current batch size of 100 may not handle large spikes in simultaneous deadlines quickly enough.
- Website checks: The 30-thread worker pool can become saturated if many websites respond slowly.
- Alert delivery: The 10-worker alert pool may develop backlogs during large outages.
- Database growth: Ping history, outages and alerts continue accumulating.
- Multiple instances: In-memory website in-flight tracking does not coordinate across different application instances.

To support 100,000+ checks reliably, I would introduce distributed work claiming, configurable worker pools and batch sizes, database partitioning/retention, and monitoring for queue depth and processing delays.

### 9. What is not finished, what is still wrong, and what would you change before real jobs depend on this?

The core functionality is implemented, but the system still requires improvements before production deployment.

Security: There is no user authentication because the assignment explicitly excludes accounts. For production, I would add authentication, API rate limiting and SSRF protection for user-provided website and webhook URLs.

Distributed scalability: Website in-flight tracking is currently instance-local. I would implement database-backed leases or distributed job claiming before deploying multiple monitoring instances.

Website flapping: A website repeatedly going UP and DOWN can produce repeated alerts. I would add configurable consecutive-failure thresholds or hysteresis to reduce alert noise.

History retention: Check history is retained indefinitely. I would introduce configurable retention periods, archival and automatic cleanup.

Observability and reliability: I would add Prometheus/Grafana metrics, structured logging, load testing, database backups and deployment health checks.

The main limitations are production hardening and high-scale coordination, not missing basic monitoring functionality.

### 10. What happens if the service itself goes down and starts again?

I use persistent scheduling state and restart recovery through PostgreSQL.

The service stores each check's status, deadlines, outages and pending alerts in the database.

When the monitoring service is offline, it cannot receive pings, perform website checks or deliver alerts.

After restart:

1. The scheduled sweeper starts again.
2. It finds checks whose persisted `next_action_at` is already due.
3. Overdue heartbeat checks are evaluated and moved to LATE or DOWN as appropriate.
4. Website monitoring resumes.
5. Pending alert deliveries are picked up by the retry worker.

This means missed deadlines are not simply forgotten when the process restarts.

However, if 50 checks became overdue during downtime, they may generate a burst of alerts after restart. The current implementation does not provide global catch-up rate limiting. I would add controlled catch-up processing and throttling for production.

This is the complete set of answers. It includes the exact implementation classes, the active/passive monitoring distinction, locking, database indexes, retry behavior, scaling limitations and unfinished work.