# Heartbeat Monitor

A Spring Boot service for monitoring background-job heartbeats and website availability. It stores state in PostgreSQL and uses scheduled workers to detect missed runs, record outages, and deliver down/recovery alerts.

## Requirements and startup

Install Java 21 and Docker with the Compose plugin. From the project directory:

```bash
docker compose up -d
docker compose ps
./mvnw spring-boot:run
```

PostgreSQL 16 runs on `localhost:5432`; the development database/user/password are `heartbeat1`. Flyway applies V1, V2, and the additive V3 internal-job migration on application startup, and the API listens on port `8080`. The checked-in configuration is for local development; change the database credentials before using it outside a local machine.

To inspect the database:

```bash
docker exec -it heartbeat-postgres psql -U heartbeat1 -d heartbeat1
```

In `psql`, use `\dt` to list tables. To remove the local database and **all its data**, run `docker compose down -v`.

## Run a Java job end to end

Start PostgreSQL and the application normally, then open `http://localhost:8080/`. No script, external job command, or special profile is required.

1. Use **Register a Java job**. Keep the interval at 10 seconds and grace at 5 seconds. Registration displays the private URL, which the job retains automatically.
2. Wait about 10 seconds. `Sweeper` dispatches the job. `ReportJob.execute()` reads the database and writes `job-reports/<CHECK_ID>.txt`, then `Sweeper.runJob()` makes a real POST to the private URL. The dashboard shows run counters, the report path, and heartbeat delivery. The check stays UP and `lastSeenAt` updates.
3. Click **Failure**. Subsequent runs throw an exception before writing the report and send no heartbeat. The existing `MonitoringService` changes the check to LATE at its expected deadline, then DOWN after grace. Allow about 16 seconds from the last successful heartbeat for DOWN detection.
4. **Recent Alerts** shows a DOWN alert. The default local HTTP webhook at `/api/alerts/receive` logs receipt, and delivery becomes DELIVERED after another few seconds. This delivers to the local application; it does not send email or SMS.
5. Click **Success**. The next scheduled run writes its report and POSTs the private URL. `PingService` restores UP, closes the outage, and generates one RECOVERY alert.

The statement specifies heartbeat behavior but no business task. Report generation is the built-in Java task, and `ReportJob.execute()` is where another actual task can be implemented. Failure mode intentionally throws; real database/file errors follow the same no-heartbeat path. Mode changes apply to the next claimed execution.

The equivalent API commands:

```bash
curl -X POST http://localhost:8080/api/checks \
  -H 'Content-Type: application/json' \
  -d '{"name":"scheduled-report","type":"HEARTBEAT","scheduleType":"INTERVAL","scheduleExpression":"10","gracePeriodSeconds":5,"runAutomatically":true}'

# Use the id returned above as <CHECK_ID>.
curl http://localhost:8080/api/checks/<CHECK_ID>/job
curl http://localhost:8080/api/checks/<CHECK_ID>

curl -X POST http://localhost:8080/api/checks/<CHECK_ID>/job/mode \
  -H 'Content-Type: application/json' -d '{"mode":"FAILURE"}'

# After the last heartbeat's deadline plus grace:
curl http://localhost:8080/api/alerts
curl http://localhost:8080/api/checks/<CHECK_ID>/history

curl -X POST http://localhost:8080/api/checks/<CHECK_ID>/job/mode \
  -H 'Content-Type: application/json' -d '{"mode":"SUCCESS"}'
```

Leave `alertUrl` out to use the local receiver, or supply your own webhook. You do not need to copy the private URL into settings. Flyway applies the additive V3 migration automatically; do not edit the existing V1/V2 migrations or delete the database.

The execution schedule remains active when monitoring is DOWN, permitting recovery. Pause stops future executions and expectations; resume schedules from resume time. PostgreSQL retains the next-run deadline, selected mode, counters, and last outcome across restarts. Each successful run replaces the report file. `monitoring.job-report-directory` defaults to `./job-reports`. `PUBLIC_BASE_URL` must be reachable from the application itself for callbacks.

Checks store token hashes. The internal job acts as a heartbeat client and additionally retains its private URL in `scheduled_jobs.ping_url` to resume after restart. This URL is stored in plaintext in that table and excluded from job status, lists, history, and application log messages. Internal jobs use a separate bounded worker pool and assume a single application instance; multi-instance execution requires a lease covering the entire run.

## Create and ping a passive heartbeat

Create a heartbeat check; the response includes the private `pingUrl`. Keep it secret and use the returned value exactly as provided:

```bash
curl -i -X POST http://localhost:8080/api/checks \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "nightly-backup",
    "type": "HEARTBEAT",
    "scheduleType": "INTERVAL",
    "scheduleExpression": "3600",
    "gracePeriodSeconds": 300,
    "alertUrl": "http://localhost:9000/alerts"
  }'
```

Omitting `runAutomatically` (or setting it to false) creates a passive check: its job must POST the private URL after successful work. Use `"runAutomatically": true` for the main application's Java job to execute and send the callback automatically. Do not use the public check UUID as a ping credential.

```bash
curl -i -X POST 'http://localhost:8080/api/ping/<PRIVATE_TOKEN>'
```

The `pingUrl` response is a complete URL. Set `PUBLIC_BASE_URL` when deploying behind a different origin. Checks store a hash of a randomly generated 256-bit token; internal jobs also retain their callback credential as described above. A check ID is not a valid ping credential. The server records its own arrival time.

## Website check

Register a URL for the service to poll. For a local test, start a server in another terminal with `python3 -m http.server 8081`:

```bash
curl -i -X POST http://localhost:8080/api/checks \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "local-site",
    "type": "WEBSITE",
    "scheduleType": "INTERVAL",
    "scheduleExpression": "60",
    "gracePeriodSeconds": 0,
    "targetUrl": "http://localhost:8081/",
    "alertUrl": "http://localhost:9000/alerts"
  }'
```

Stop the Python server to cause connection failures. Non-2xx responses and requests taking longer than five seconds fail. A website first becomes `LATE` without an alert during grace; the monitor makes an additional probe at the grace deadline if necessary, and a continued failure becomes `DOWN` with one alert. Successful probes restore `UP`; a recovery alert is sent only when returning from `DOWN`. For a container deployment, replace `localhost` with a hostname reachable by the container.

## Make late/down happen quickly

Run with a one-second sweeper interval:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--monitoring.sweeper-delay-ms=1000
```

Register an interval heartbeat with `"scheduleExpression": "10"` and `"gracePeriodSeconds": 5`, then do not ping it. Check status after its next expected time to see `LATE`; after the grace deadline it becomes `DOWN`. The one-second sweeper cadence is needed to observe the short late window. **Do not send a heartbeat ping** during this demonstration; to observe recovery, POST the original private URL *after* DOWN. The default sweeper period is one second. There is no simulated clock or time-acceleration feature.

## API

All paths are relative to `http://localhost:8080`. Pagination uses Spring's `page` (zero-based) and `size` query parameters; list responses are Spring `Page` JSON objects.

| Method and path | Purpose | Example |
|---|---|---|
| `POST /api/checks` | Register heartbeat or website check; response includes its ID and (for heartbeats) private ping URL. | See the create examples above. |
| `GET /api/checks/{id}/job` | Inspect internal execution and report results. | Requires `runAutomatically: true`. |
| `POST /api/checks/{id}/job/mode` | Control subsequent Java job runs. | Body: `{"mode":"FAILURE"}` or `{"mode":"SUCCESS"}`. |
| `POST /api/alerts/receive` | Default local HTTP webhook receiver. | Used when registration omits `alertUrl`. |
| `POST /api/ping/{token}` | Record heartbeat using the returned private token. | `curl -i -X POST http://localhost:8080/api/ping/<PRIVATE_TOKEN>` |
| `GET /api/checks` | List checks. | `curl -i 'http://localhost:8080/api/checks?page=0&size=20'` |
| `GET /api/checks?status=DOWN` | Filter checks by `UP`, `LATE`, `DOWN` or `PAUSED`. | `curl -i 'http://localhost:8080/api/checks?status=DOWN&page=0&size=20'` |
| `GET /api/checks/{id}` | Get check details including next expected time. | `curl -i http://localhost:8080/api/checks/<CHECK_ID>` |
| `GET /api/checks/{id}/history` | Paginated ping, website-result and status history. | `curl -i 'http://localhost:8080/api/checks/<CHECK_ID>/history?page=0&size=20'` |
| `POST /api/checks/{id}/pause` | Pause monitoring. | `curl -i -X POST http://localhost:8080/api/checks/<CHECK_ID>/pause` |
| `POST /api/checks/{id}/resume` | Resume monitoring. | `curl -i -X POST http://localhost:8080/api/checks/<CHECK_ID>/resume` |
| `GET /api/alerts` | Inspect alert deliveries, including failed terminal alerts. | `curl -i 'http://localhost:8080/api/alerts?page=0&size=20'` |

Create request fields are `name`, `type`, `scheduleType`, `scheduleExpression`, `gracePeriodSeconds`, optional `alertUrl` (defaults to the local receiver), optional `runAutomatically` (defaults to false), and `targetUrl` for website checks. `type` is `HEARTBEAT` or `WEBSITE`; `scheduleType` is `INTERVAL` (seconds in `scheduleExpression`) or Spring cron `CRON`. Creation and ping both return JSON with HTTP `200`; list/detail/history use HTTP `200`; pause and resume return `200` with an empty body. Request validation and missing IDs currently return HTTP `400` with fields `timestamp`, `status`, `error`, and `message`. The `pingUrl` is an absolute URL (`PUBLIC_BASE_URL` can override the local default). Example create response excerpt:

```json
{"id":"<CHECK_UUID>","type":"HEARTBEAT","status":"UP","pingUrl":"http://localhost:8080/api/ping/<PRIVATE_TOKEN>","nextExpectedAt":"<UTC_TIMESTAMP>"}
```

Example invalid-token error shape: `{"timestamp":"<UTC_TIMESTAMP>","status":400,"error":"Bad Request","message":"Invalid ping token"}`.

## SQL logging

SQL and bind-value logging is switchable through the `debug-sql` Spring profile. Restart the app with:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=debug-sql
```

Disable it by omitting that profile. Bind logging can include sensitive values, so use only with local/test data.

## Tests

Tests use a real PostgreSQL 16 Testcontainers database (not H2). Docker must be running:

```bash
./mvnw test
```

The real-PostgreSQL suite covers repeated sweeps and recovery, concurrent ping/sweep locking, late versus down, Friday-to-Monday cron, dead versus fast alert destinations, website timeouts, website grace and recovery, stale website results, passive heartbeats, pause/resume across outages, and real HTTP internal-job success/failure/recovery with report writing and local webhook delivery. Tests fail rather than silently skip when Docker is missing.

## Implementation notes

- Due checks are read in batches of 100 using an index on the internal `next_action_at` deadline (LATE heartbeat checks do not monopolize the batch); alert deliveries use a separate bounded pool so a slow destination does not block the sweep or other delivery workers.
- Alert delivery retries five times after the initial attempt, with delays of 10, 30, 60, 120 and 300 seconds. After six total failures the alert remains inspectable as `FAILED`. Worker jobs start immediately or release their DB claim; no long queue of claimed deliveries. The `X-Alert-Id` header permits recipient-side deduplication on retries (delivery is at-least-once, not exactly-once).
- Cron schedules use UTC. Both check types support `UP`, `LATE`, and `DOWN` with grace; the website failure clock starts with its first failed scheduled probe.
- On restart, due checks and pending alert deliveries are reconciled from PostgreSQL. A backlog can produce a burst of alerts; global throttling and multi-instance coordination are not implemented. Single-process website polls are deduplicated in memory; stale poll results are rejected using the expected poll time under the DB row lock.
- Before production: add management API authentication, SSRF-safe destination/target validation, durable multi-instance worker claims, history retention, operational metrics, backups, and load tests.

## Design choices and known limits

- A heartbeat check starts expecting its first run from **creation time**, even before the first ping. Resume restarts expectations from resume time. A pause during an existing outage closes that outage **without a recovery alert**; the next future outage is distinct.
- If a website flaps (DOWN/UP repeatedly), it can produce repeated down/recovery pairs. No hysteresis or consecutive-failure requirement has been added; these would be valuable in production.
- Ping/probe and status history is currently retained indefinitely. Production would add time-based retention or archived partitions.
- API authentication, safe URL validation (SSRF prevention), multi-instance distributed poll leases, global catch-up throttling, and operational metrics are not built. Avoid deploying with untrusted target/alert URLs until SSRF protections are in place.
- V2 is an **additional** Flyway migration; do not alter V1 on a database that has already applied it. Existing inconsistent duplicate outage/alert data must be cleaned before the V2 unique indexes can be applied.
