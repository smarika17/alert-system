let allChecks = [];
let selectedJobId = null;

document.getElementById("registerJobForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = document.getElementById("registerJobButton");
    button.disabled = true;
    try {
        const response = await fetch("/api/checks", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                name: document.getElementById("jobName").value,
                type: "HEARTBEAT",
                scheduleType: "INTERVAL",
                scheduleExpression: document.getElementById("jobInterval").value,
                gracePeriodSeconds: Number(document.getElementById("jobGrace").value),
                runAutomatically: true
            })
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || "Registration failed");
        selectedJobId = result.id;
        document.getElementById("privatePingUrl").value = result.pingUrl;
        document.getElementById("jobRegistration").hidden = false;
        document.getElementById("jobMessage").textContent = `Registered ${result.name}. Its first run is scheduled for ${formatDate(result.nextExpectedAt)}.`;
        await loadDashboard();
    } catch (error) {
        document.getElementById("jobMessage").textContent = error.message;
    } finally {
        button.disabled = false;
    }
});

async function setJobMode(id, mode) {
    try {
        const response = await fetch(`/api/checks/${id}/job/mode`, {
            method: "POST", headers: {"Content-Type": "application/json"},
            body: JSON.stringify({mode})
        });
        const result = await response.json();
        if (!response.ok) throw new Error(result.message || "Could not change job mode");
        selectedJobId = id;
        document.getElementById("jobMessage").textContent = mode === "FAILURE"
            ? "The next runs will fail and skip their heartbeats. DOWN follows the heartbeat deadline plus grace."
            : "The next run will write its report and send a heartbeat. A DOWN job will recover.";
        await loadDashboard();
    } catch (error) {
        document.getElementById("jobMessage").textContent = error.message;
    }
}

async function loadJobDetails() {
    if (!selectedJobId) return;
    const response = await fetch(`/api/checks/${selectedJobId}/job`);
    if (!response.ok) return;
    const job = await response.json();
    document.getElementById("jobDetails").textContent =
        `Job: ${job.checkId}\nMode: ${job.mode}\nNext run: ${formatDate(job.nextRunAt)}` +
        `\nLast outcome: ${job.lastOutcome || "Waiting for first run"}` +
        `\nHeartbeat delivered: ${job.heartbeatDelivered === null ? "-" : job.heartbeatDelivered}` +
        `\nSuccessful runs: ${job.successfulRuns} | Failed runs: ${job.failedRuns}` +
        (job.reportPath ? `\nReport: ${job.reportPath}` : "") +
        (job.lastError ? `\nError: ${job.lastError}` : "");
}


/* =========================
   LOAD DASHBOARD
========================= */

async function loadDashboard() {

    try {

        const response =
            await fetch(
                "/api/checks?page=0&size=100"
            );


        if (!response.ok) {

            throw new Error(
                "Failed to load checks"
            );
        }


        const data =
            await response.json();


        allChecks =
            data.content || data;


        updateStatistics();

        renderChecks();

        populateHistorySelect();

        await loadAlerts();
        await loadJobDetails();
        if (document.getElementById("historyCheck").value) await loadHistory();


        setConnection(true);


    } catch (error) {

        console.error(error);

        setConnection(false);
    }
}


/* =========================
   CONNECTION
========================= */

function setConnection(
    connected
) {

    const element =
        document.getElementById(
            "connectionStatus"
        );


    if (connected) {

        element.textContent =
            "● Connected";

        element.style.color =
            "#16a34a";

    } else {

        element.textContent =
            "● Backend Offline";

        element.style.color =
            "#dc2626";
    }
}


/* =========================
   STATISTICS
========================= */

function updateStatistics() {

    const total =
        allChecks.length;


    const up =
        allChecks.filter(
            check =>
                check.status === "UP"
        ).length;


    const late =
        allChecks.filter(
            check =>
                check.status === "LATE"
        ).length;


    const down =
        allChecks.filter(
            check =>
                check.status === "DOWN"
        ).length;


    const paused =
        allChecks.filter(
            check =>
                check.status === "PAUSED"
        ).length;


    document.getElementById(
        "totalChecks"
    ).textContent = total;


    document.getElementById(
        "upChecks"
    ).textContent = up;


    document.getElementById(
        "lateChecks"
    ).textContent = late;


    document.getElementById(
        "downChecks"
    ).textContent = down;


    document.getElementById(
        "pausedChecks"
    ).textContent = paused;
}


/* =========================
   CHECK TABLE
========================= */

function renderChecks(
    checks = allChecks
) {

    const table =
        document.getElementById(
            "checksTable"
        );


    table.innerHTML = "";


    if (checks.length === 0) {

        table.innerHTML = `
            <tr>
                <td colspan="6">
                    No checks found.
                </td>
            </tr>
        `;

        return;
    }


    checks.forEach(
        check => {

            const row =
                document.createElement(
                    "tr"
                );


            row.innerHTML = `

                <td>
                    <strong>
                        ${escapeHtml(
                check.name
            )}
                    </strong>
                </td>

                <td>
                    ${escapeHtml(
                check.type
            )}
                </td>

                <td>

                    <span
                        class="status status-${check.status}"
                    >
                        ${check.status}
                    </span>

                </td>

                <td>
                    ${formatDate(
                check.lastSeenAt
            )}
                </td>

                <td>
                    ${formatDate(
                check.nextExpectedAt
            )}
                </td>

                <td>

                    <button
                        class="view-button"
                        onclick="
                            selectHistory(
                                '${check.id}'
                            )
                        "
                    >
                        History
                    </button>

                    ${check.runAutomatically ? `
                    <button class="view-button" onclick="setJobMode('${check.id}', 'FAILURE')">Failure</button>
                    <button class="view-button" onclick="setJobMode('${check.id}', 'SUCCESS')">Success</button>
                    ` : ""}

                </td>
            `;


            table.appendChild(row);
        }
    );
}


/* =========================
   SEARCH
========================= */

function filterChecks() {

    const search =
        document
            .getElementById(
                "searchInput"
            )
            .value
            .toLowerCase();


    const filtered =
        allChecks.filter(
            check =>
                check.name
                    .toLowerCase()
                    .includes(search)
        );


    renderChecks(filtered);
}


/* =========================
   HISTORY SELECT
========================= */

function populateHistorySelect() {

    const select =
        document.getElementById(
            "historyCheck"
        );

    const previousSelection = select.value;


    select.innerHTML = `
        <option value="">
            Select a check
        </option>
    `;


    allChecks.forEach(
        check => {

            const option =
                document.createElement(
                    "option"
                );


            option.value =
                check.id;


            option.textContent =
                check.name;


            select.appendChild(
                option
            );
        }
    );
    if (allChecks.some(check => check.id === previousSelection)) select.value = previousSelection;
}


function selectHistory(
    checkId
) {

    const select =
        document.getElementById(
            "historyCheck"
        );


    select.value =
        checkId;


    loadHistory();
}


/* =========================
   HISTORY
========================= */

async function loadHistory() {

    const checkId =
        document.getElementById(
            "historyCheck"
        ).value;


    const container =
        document.getElementById(
            "historyContainer"
        );


    if (!checkId) {

        container.innerHTML = `
            <p class="empty">
                Select a check to see history.
            </p>
        `;

        return;
    }


    try {

        const response =
            await fetch(
                `/api/checks/${checkId}/history?page=0&size=50`
            );


        if (!response.ok) {

            throw new Error(
                "History request failed"
            );
        }


        const data =
            await response.json();


        const history =
            data.content || data;


        if (history.length === 0) {

            container.innerHTML = `
                <p class="empty">
                    No history available.
                </p>
            `;

            return;
        }


        container.innerHTML = "";


        history.forEach(
            event => {

                const element =
                    document.createElement(
                        "div"
                    );


                element.className =
                    "history-item";


                element.innerHTML = `

                    <div class="history-event">

                        ${escapeHtml(
                    event.eventType
                )}

                        ${
                    event.status
                        ? ` → ${escapeHtml(
                            event.status
                        )}`
                        : ""
                }

                    </div>


                    <div class="history-time">

                        ${formatDate(
                    event.occurredAt
                )}

                    </div>


                    ${
                    event.details
                        ? `<div>
                                ${escapeHtml(
                            event.details
                        )}
                               </div>`
                        : ""
                }

                `;


                container.appendChild(
                    element
                );
            }
        );


    } catch (error) {

        console.error(error);


        container.innerHTML = `
            <p class="empty">
                Failed to load history.
            </p>
        `;
    }
}


/* =========================
   ALERTS
========================= */

async function loadAlerts() {

    const container =
        document.getElementById(
            "alertsContainer"
        );


    try {

        const response =
            await fetch(
                "/api/alerts?page=0&size=20"
            );


        if (!response.ok) {

            throw new Error(
                "Failed to load alerts"
            );
        }


        const data =
            await response.json();


        const alerts =
            data.content || data;


        if (alerts.length === 0) {

            container.innerHTML = `
                <p class="empty">
                    No alerts found.
                </p>
            `;

            return;
        }


        container.innerHTML = "";


        alerts.forEach(
            alert => {

                const element =
                    document.createElement(
                        "div"
                    );


                element.className =
                    "alert " +
                    (
                        alert.alertType ===
                        "DOWN"
                            ? "alert-down"
                            : "alert-recovery"
                    );


                element.innerHTML = `

                    <div>

                        <div class="alert-title">

                            ${
                    alert.alertType
                    === "DOWN"
                        ? "🔴 DOWN"
                        : "🟢 RECOVERY"
                }

                        </div>


                        <div>

                            ${escapeHtml(
                    alert.checkName
                )}

                        </div>


                        <div>

                            Delivery:
                            ${escapeHtml(
                    alert.deliveryStatus
                )}

                        </div>


                        <div>

                            Attempts:
                            ${alert.attemptCount}

                        </div>

                    </div>


                    <div class="alert-time">

                        ${formatDate(
                    alert.createdAt
                )}

                    </div>
                `;


                container.appendChild(
                    element
                );
            }
        );


    } catch (error) {

        console.error(error);


        container.innerHTML = `
            <p class="empty">
                Could not load alerts.
            </p>
        `;
    }
}


/* =========================
   DATE FORMAT
========================= */

function formatDate(
    value
) {

    if (!value) {

        return "-";
    }


    return new Date(
        value
    ).toLocaleString();
}


/* =========================
   ESCAPE HTML
========================= */

function escapeHtml(
    value
) {

    if (
        value === null ||
        value === undefined
    ) {

        return "";
    }


    return String(value)
        .replaceAll(
            "&",
            "&amp;"
        )
        .replaceAll(
            "<",
            "&lt;"
        )
        .replaceAll(
            ">",
            "&gt;"
        )
        .replaceAll(
            '"',
            "&quot;"
        )
        .replaceAll(
            "'",
            "&#039;"
        );
}


/* =========================
   INITIAL LOAD
========================= */

loadDashboard();


/*
 * Refresh every 5 seconds.
 */

setInterval(
    loadDashboard,
    5000
);
