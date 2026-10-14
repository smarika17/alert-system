# Heartbeat Monitor

This Spring Boot application monitors background jobs and websites. A heartbeat check expects a successful job to POST a private URL on schedule. If no POST arrives by the expected time, the check becomes `LATE`; after its grace period, it becomes `DOWN` and creates an alert. A later successful heartbeat restores `UP` and creates a recovery alert. Website checks instead make their own HTTP GET requests to a target URL.

## 1. Install requirements and start the application

Use a macOS or Linux terminal. Install **JDK 21**, **Docker with Docker Compose**, **curl**, and **Python 3**. Start Docker before running the commands. The Maven wrapper (`./mvnw`) downloads Maven and project dependencies on first use, so the first build needs internet access.

Open a terminal in the project directory and run:

```bash
docker compose up -d
until docker exec heartbeat-postgres pg_isready -U heartbeat1 -d heartbeat1 >/dev/null 2>&1; do sleep 1; done
./mvnw spring-boot:run
```

Leave this terminal running. Flyway applies database migrations V1, V2, and V3 when the application starts. Once the log says the application has started, open `http://localhost:8080/` for the dashboard. Use a **second terminal** in the same project directory for the remaining commands. They use shell variables set by earlier steps:

```bash
BASE_URL=http://localhost:8080
```

## 2. Database: PostgreSQL

The [Docker Compose file](docker-compose.yml) starts PostgreSQL **16** on `localhost:5432`. The local development database, user, and password are all `heartbeat1`; they match [application.yml](src/main/resources/application.yml). Docker keeps database data in the `heartbeat_postgres_data` volume. Check that the database is ready or inspect its tables with:

```bash
docker compose ps
docker exec -it heartbeat-postgres psql -U heartbeat1 -d heartbeat1
```

Inside `psql`, run `\dt` to list tables and `\q` to leave. The application uses Flyway for schema changes and validates the schema through JPA.

## 3. Register a heartbeat check and ping it

Run these commands **together in the second terminal**. This is a passive check: your job or you must POST its returned private URL after successful work. The default alert destination is the application's local receiver, so no other service is needed.

```bash
CREATE_RESPONSE=$(curl -fsS -X POST "$BASE_URL/api/checks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"manual-job","type":"HEARTBEAT","scheduleType":"INTERVAL","scheduleExpression":"60","gracePeriodSeconds":10}')
printf '%s\n' "$CREATE_RESPONSE"
CHECK_ID=$(printf '%s' "$CREATE_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
PING_URL=$(printf '%s' "$CREATE_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["pingUrl"])')
curl -fsS -X POST "$PING_URL"
curl -fsS "$BASE_URL/api/checks/$CHECK_ID"
```

The create response contains `id`, `pingUrl`, `nextExpectedAt`, and `runAutomatically: false`. The POST records a `PING` event and updates `lastSeenAt` and the next expected time. `PING_URL` is a private credential: use the returned URL itself, not the check ID. This check expects another ping every 60 seconds; you can pause it when finished:

```bash
curl -fsS -X POST "$BASE_URL/api/checks/$CHECK_ID/pause"
```

## 4. Switch SQL logging on or off

SQL logging is controlled by the `debug-sql` Spring profile in [application-debug-sql.yml](src/main/resources/application-debug-sql.yml). Stop the running application with **Ctrl+C** and restart it with:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=debug-sql
```

Repeat a `curl` request from section 3. The application terminal shows SQL statements and parameter values. To switch logging off, stop the application and start it normally again:

```bash
./mvnw spring-boot:run
```

The `debug-sql` profile can print private values, so use it with local test data. Restarting the application keeps registered checks and alerts because PostgreSQL uses a Docker volume.

## 5. See LATE, DOWN, and automatic job recovery quickly

The sweeper already runs every **one second** (`monitoring.sweeper-delay-ms: 1000`). No clock change or extra startup flag is needed. Register a passive check expecting a heartbeat after 10 seconds, with a 5-second grace period, and deliberately do **not** ping it:

```bash
FAST_RESPONSE=$(curl -fsS -X POST "$BASE_URL/api/checks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"missed-job","type":"HEARTBEAT","scheduleType":"INTERVAL","scheduleExpression":"10","gracePeriodSeconds":5}')
FAST_ID=$(printf '%s' "$FAST_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
sleep 11
curl -fsS "$BASE_URL/api/checks/$FAST_ID"
sleep 6
curl -fsS "$BASE_URL/api/checks/$FAST_ID"
curl -fsS "$BASE_URL/api/alerts?page=0&size=20"
```

The first check response should show `LATE` and the second `DOWN`. The DOWN transition creates one outage and one alert. Alert delivery runs separately, so allow another few seconds for `deliveryStatus` to become `DELIVERED`. The local alert receiver is `POST /api/alerts/receive`; the application calls it automatically. To recover this passive check, POST its `pingUrl` from `FAST_RESPONSE`:

```bash
FAST_PING_URL=$(printf '%s' "$FAST_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["pingUrl"])')
curl -fsS -X POST "$FAST_PING_URL"
curl -fsS "$BASE_URL/api/checks/$FAST_ID"
curl -fsS -X POST "$BASE_URL/api/checks/$FAST_ID/pause"
```

The last command pauses this short-lived example so it does not generate more alerts while you demonstrate the automatic job.

The main application can also **run a Java job itself**. The problem statement does not define a business task, so the built-in job reads the database and writes `job-reports/<CHECK_ID>.txt`. It POSTs its private URL only after the report succeeds. Register one with `runAutomatically: true`:

```bash
AUTO_RESPONSE=$(curl -fsS -X POST "$BASE_URL/api/checks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"scheduled-report","type":"HEARTBEAT","scheduleType":"INTERVAL","scheduleExpression":"10","gracePeriodSeconds":5,"runAutomatically":true}')
printf '%s\n' "$AUTO_RESPONSE"
AUTO_ID=$(printf '%s' "$AUTO_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
AUTO_PING_URL=$(printf '%s' "$AUTO_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["pingUrl"])')
sleep 12
curl -fsS "$BASE_URL/api/checks/$AUTO_ID/job"
curl -fsS "$BASE_URL/api/checks/$AUTO_ID"
```

The job response should show `successfulRuns` above zero, `heartbeatDelivered: true`, and a `reportPath`. To make the **next runs fail**, switch the job to failure mode. They will skip their heartbeat; the monitor will then mark the check DOWN and create a DOWN alert:

```bash
curl -fsS -X POST "$BASE_URL/api/checks/$AUTO_ID/job/mode" \
  -H 'Content-Type: application/json' -d '{"mode":"FAILURE"}'
sleep 25
curl -fsS "$BASE_URL/api/checks/$AUTO_ID/job"
curl -fsS "$BASE_URL/api/checks/$AUTO_ID"
curl -fsS "$BASE_URL/api/alerts?page=0&size=20"
```

Restore success. The next run writes the report, POSTs the private URL, restores `UP`, and produces a `RECOVERY` alert:

```bash
curl -fsS -X POST "$BASE_URL/api/checks/$AUTO_ID/job/mode" \
  -H 'Content-Type: application/json' -d '{"mode":"SUCCESS"}'
sleep 15
curl -fsS "$BASE_URL/api/checks/$AUTO_ID/job"
curl -fsS "$BASE_URL/api/checks/$AUTO_ID"
curl -fsS "$BASE_URL/api/alerts?page=0&size=20"
```

The dashboard at `http://localhost:8080/` also offers **Register a Java job**, **Failure**, and **Success** buttons for this walkthrough. The private URL is shown at registration and retained by the internal job; you do not need to copy it into configuration.

## 6. Point a website check at a server you control

In a **third terminal**, run a local website and leave it open:

```bash
python3 -m http.server 8081
```

Back in the second terminal, verify the site and register a WEBSITE check. The application sends HTTP GET probes to `targetUrl`; a website check does not call `/api/ping/{token}`.

```bash
curl -fsS 'http://localhost:8081/'
SITE_RESPONSE=$(curl -fsS -X POST "$BASE_URL/api/checks" \
  -H 'Content-Type: application/json' \
  -d '{"name":"local-website","type":"WEBSITE","scheduleType":"INTERVAL","scheduleExpression":"5","gracePeriodSeconds":3,"targetUrl":"http://localhost:8081/"}')
SITE_ID=$(printf '%s' "$SITE_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
sleep 7
curl -fsS "$BASE_URL/api/checks/$SITE_ID"
```

Stop the Python server with **Ctrl+C** in its terminal. Back in the second terminal:

```bash
sleep 12
curl -fsS "$BASE_URL/api/checks/$SITE_ID"
curl -fsS "$BASE_URL/api/alerts?page=0&size=20"
```

The check should become DOWN and generate an alert. Restart `python3 -m http.server 8081` to demonstrate recovery on a later probe. Non-2xx responses and requests taking longer than five seconds also count as failures.

## 7. Endpoints

These examples use `BASE_URL`, `CHECK_ID`, and `AUTO_ID` set in earlier sections. Pagination uses zero-based `page` and `size` parameters.

| Method and path | Purpose | Example request |
|---|---|---|
| `POST /api/checks` | Register a check. | `curl -fsS -X POST "$BASE_URL/api/checks" -H 'Content-Type: application/json' -d '{"name":"another-job","type":"HEARTBEAT","scheduleType":"INTERVAL","scheduleExpression":"60","gracePeriodSeconds":10}'` |
| `POST /api/ping/{token}` | Record a successful heartbeat using its private URL. | `curl -fsS -X POST "$AUTO_PING_URL"` |
| `GET /api/checks` | List checks. | `curl -fsS "$BASE_URL/api/checks?page=0&size=20"` |
| `GET /api/checks?status=DOWN` | Filter by status. | `curl -fsS "$BASE_URL/api/checks?status=DOWN&page=0&size=20"` |
| `GET /api/checks/{id}` | Check status and next expected time. | `curl -fsS "$BASE_URL/api/checks/$CHECK_ID"` |
| `GET /api/checks/{id}/history` | Ping, probe, and status history. | `curl -fsS "$BASE_URL/api/checks/$CHECK_ID/history?page=0&size=20"` |
| `GET /api/checks/{id}/job` | Internal job mode, run counts, and report path. | `curl -fsS "$BASE_URL/api/checks/$AUTO_ID/job"` |
| `POST /api/checks/{id}/job/mode` | Control the next internal job runs. | `curl -fsS -X POST "$BASE_URL/api/checks/$AUTO_ID/job/mode" -H 'Content-Type: application/json' -d '{"mode":"FAILURE"}'` |
| `POST /api/checks/{id}/pause` | Pause monitoring and internal execution. | `curl -fsS -X POST "$BASE_URL/api/checks/$CHECK_ID/pause"` |
| `POST /api/checks/{id}/resume` | Resume monitoring and internal execution. | `curl -fsS -X POST "$BASE_URL/api/checks/$CHECK_ID/resume"` |
| `GET /api/alerts` | Inspect DOWN/RECOVERY alerts and delivery status. | `curl -fsS "$BASE_URL/api/alerts?page=0&size=20"` |
| `GET /actuator/health` | Check application health. | `curl -fsS "$BASE_URL/actuator/health"` |

The application's alert dispatcher POSTs to `/api/alerts/receive` when a check uses the default local webhook. It validates the alert against its database record. `GET /api/alerts` is the request to use when inspecting its delivery.

## 8. What to know before opening the code

- Start with `CheckController.create()` and `CheckService.createCheck()`: these store the schedule and return a private heartbeat URL. Passive checks leave `runAutomatically` false; only checks registered with `runAutomatically: true` attach the built-in Java job.
- `Sweeper.startDueJobs()` dispatches internal jobs. `ReportJob.execute()` writes the report, then `Sweeper.runJob()` POSTs the private URL **only after success**. `PingController` and `PingService` receive the POST and renew the heartbeat deadline.
- The same `Sweeper` finds overdue checks. `MonitoringService.processHeartbeat()` handles `LATE` and `DOWN`; `AlertService` saves alerts; `AlertRetryService` and `AlertDispatcher` deliver them. A later ping closes an outage and creates a recovery alert.
- Website checks follow `WebsiteChecker` and `WebsiteMonitoringService`. They actively GET the target URL. Cron schedules use UTC; interval expressions are seconds. A check starts as UP when registered, before its first successful run or probe.
- `scheduled_jobs.ping_url` stores the internal job's private callback URL so the job can resume after restart. Normal checks store only its token hash. Keep this database table private. Internal execution assumes one application instance; the worker pool prevents long jobs from blocking the monitoring sweep.
- `alertUrl` is optional. When omitted, alerts are delivered to the local HTTP receiver and can be inspected through `GET /api/alerts`. When supplied, it must be a webhook URL reachable by the application.

## 9. Tests and unfinished work

With Docker running, execute the PostgreSQL Testcontainers suite:

```bash
./mvnw test
```

The suite covers heartbeat timing, recovery, concurrent ping/sweep behavior, website failures, and an end-to-end internal job that writes a report, calls its private URL, fails, raises a delivered alert, and recovers.

The built-in report is a concrete example job because the task description does not specify other business work. To use a real job, replace the work inside `ReportJob.execute()` while keeping the success-only callback in `Sweeper.runJob()`. Before production use, add authentication for check management and job-mode endpoints; validate website and alert destinations against server-side request forgery; add coordination so multiple application instances cannot run the same job; and set up history retention, metrics, backups, and load testing. These are not implemented. The local receiver records alert delivery in the database and logs it; it does not send email or SMS.
