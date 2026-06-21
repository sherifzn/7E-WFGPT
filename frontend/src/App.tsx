import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  inspectionApi,
  InspectionHistoryEvent,
  InspectionProcess,
  InspectionSummary,
  InspectionTask
} from "./inspectionApi";
import {
  ClearanceTask,
  DevelopmentIdentity,
  keyHandoverApi,
  KeyHandoverRequest,
  Outcome
} from "./keyHandoverDemo";
import "./styles.css";

type View = "requests" | "new-request" | "inspections";
const statusClass = (status: string) => status.toLowerCase().replaceAll(" ", "-");
const finalState = (request: KeyHandoverRequest) => request.status;
const identities: { value: DevelopmentIdentity; label: string }[] = [
  { value: "requester", label: "Requester" },
  { value: "handoverOfficer", label: "Handover officer" },
  { value: "financeOfficer", label: "Finance officer" },
  { value: "legalOfficer", label: "Legal officer" },
  { value: "inspectionOfficer", label: "Inspection officer" },
  { value: "remediationOfficer", label: "Remediation officer" },
  { value: "teamHead", label: "Team Head" },
  { value: "processOwner", label: "Process owner" }
];

export function App() {
  const [view, setView] = useState<View>("requests");
  const [requests, setRequests] = useState<KeyHandoverRequest[]>([]);
  const [selectedNumber, setSelectedNumber] = useState("");
  const [propertyReference, setPropertyReference] = useState("");
  const [ownerReference, setOwnerReference] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [identity, setIdentity] = useState<DevelopmentIdentity>("requester");
  const [inspections, setInspections] = useState<InspectionSummary[]>([]);
  const [selectedInspectionId, setSelectedInspectionId] = useState("");
  const [inspectionDetail, setInspectionDetail] = useState<InspectionProcess | null>(null);
  const [inspectionHistory, setInspectionHistory] = useState<InspectionHistoryEvent[]>([]);

  const selected = useMemo(
    () => requests.find((request) => request.requestNumber === selectedNumber) ?? requests[0],
    [requests, selectedNumber]
  );
  const replace = (updated: KeyHandoverRequest) => {
    setRequests((current) =>
      current.map((request) =>
        request.requestNumber === updated.requestNumber ? updated : request
      )
    );
    setSelectedNumber(updated.requestNumber);
  };
  const load = async () => {
    setLoading(true);
    try {
      const list = await keyHandoverApi.list();
      setRequests(list);
      setSelectedNumber((current) => current || list[0]?.requestNumber || "");
      setMessage("");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The local API is unavailable.");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    if (view === "inspections") void loadInspections();
  }, [view]);
  const loadInspections = async () => {
    try {
      const list = await inspectionApi.list();
      setInspections(list);
    } catch {
      setInspections([]);
    }
  };
  const selectInspection = async (id: string) => {
    setSelectedInspectionId(id);
    try {
      setInspectionDetail(await inspectionApi.get(id));
      setInspectionHistory(await inspectionApi.getHistory(id));
    } catch (e) {
      setInspectionDetail(null);
      setInspectionHistory([]);
      setMessage(e instanceof Error ? e.message : "Failed to load inspection.");
    }
  };
  const inspAction = async (fn: () => Promise<InspectionProcess>) => {
    setBusy(true);
    try {
      const updated = await fn();
      setInspectionDetail(updated);
      void loadInspections();
      if (selectedInspectionId) {
        try {
          const history = await inspectionApi.getHistory(selectedInspectionId);
          setInspectionHistory(history);
        } catch {
          setInspectionHistory([]);
        }
      }
      setMessage("");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Action failed.");
    } finally {
      setBusy(false);
    }
  };
  const perform = async (path: string, parameters: Record<string, string> = {}) => {
    if (!selected) return;
    setBusy(true);
    try {
      replace(
        await keyHandoverApi.action(selected.requestNumber, path, {
          ...parameters,
          actor: identity
        })
      );
      setMessage("");
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "The local action could not be completed."
      );
    } finally {
      setBusy(false);
    }
  };
  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!propertyReference.trim() || !ownerReference.trim()) {
      setMessage("Enter both references to create a synthetic request.");
      return;
    }
    setBusy(true);
    try {
      const created = await keyHandoverApi.create(propertyReference, ownerReference, identity);
      setRequests((current) => [created, ...current]);
      setSelectedNumber(created.requestNumber);
      setPropertyReference("");
      setOwnerReference("");
      setView("requests");
      setMessage(`${created.requestNumber} created and waiting for inspection.`);
      void loadInspections();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The request could not be created.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">KH</span>
          <span>Workflow Studio</span>
        </div>
        <nav aria-label="Primary navigation">
          <button
            className={view === "requests" ? "nav-link active" : "nav-link"}
            onClick={() => setView("requests")}
          >
            Key handovers
          </button>
          <button
            className={view === "new-request" ? "nav-link active" : "nav-link"}
            onClick={() => setView("new-request")}
          >
            New request
          </button>
          <button
            className={view === "inspections" ? "nav-link active" : "nav-link"}
            onClick={() => setView("inspections")}
          >
            Inspections
          </button>
        </nav>
        <div className="demo-notice">
          <strong>Local demo</strong>
          <span>Synthetic data only</span>
        </div>
      </aside>
      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Property services</p>
            <h1>Key Handover</h1>
          </div>
          <label className="identity-selector">
            <span>Testing as</span>
            <select
              value={identity}
              onChange={(event) => setIdentity(event.target.value as DevelopmentIdentity)}
            >
              {identities.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
        </header>
        {message && (
          <p className="form-message" role="alert">
            {message}
          </p>
        )}
        {view === "inspections" ? (
          <InspectionWorkspace
            inspections={inspections}
            selectedId={selectedInspectionId}
            detail={inspectionDetail}
            history={inspectionHistory}
            busy={busy}
            identity={identity}
            onSelect={selectInspection}
            onAction={inspAction}
          />
        ) : view === "new-request" ? (
          <NewRequest
            property={propertyReference}
            owner={ownerReference}
            busy={busy}
            onProperty={setPropertyReference}
            onOwner={setOwnerReference}
            onSubmit={handleSubmit}
          />
        ) : loading ? (
          <section className="new-request-panel">
            <h2>Loading local requests…</h2>
            <p>The synthetic workflow data is being retrieved.</p>
          </section>
        ) : !selected ? (
          <section className="new-request-panel">
            <h2>No requests yet</h2>
            <p>Create a synthetic Key Handover request to begin the local demo.</p>
          </section>
        ) : (
          <div className="request-layout">
            <RequestList
              requests={requests}
              selected={selected.requestNumber}
              onSelect={setSelectedNumber}
            />
            <RequestDetail
              request={selected}
              busy={busy}
              identity={identity}
              onAction={perform}
              onUpdate={replace}
              onNavInspections={() => void loadInspections().then(() => setView("inspections"))}
            />
          </div>
        )}
      </main>
    </div>
  );
}

function NewRequest({
  property,
  owner,
  busy,
  onProperty,
  onOwner,
  onSubmit
}: {
  property: string;
  owner: string;
  busy: boolean;
  onProperty: (value: string) => void;
  onOwner: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <section className="new-request-panel" aria-labelledby="new-request-title">
      <p className="eyebrow">Create a request</p>
      <h2 id="new-request-title">New Key Handover request</h2>
      <p>Use synthetic references to preview validation and the inspection barrier.</p>
      <form onSubmit={onSubmit} noValidate>
        <label>
          Property reference
          <input
            value={property}
            onChange={(event) => onProperty(event.target.value)}
            placeholder="e.g. Demo Property 104"
          />
        </label>
        <label>
          Owner reference
          <input
            value={owner}
            onChange={(event) => onOwner(event.target.value)}
            placeholder="e.g. Demo Owner 104"
          />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Submitting…" : "Submit request"}
        </button>
      </form>
    </section>
  );
}
function RequestList({
  requests,
  selected,
  onSelect
}: {
  requests: KeyHandoverRequest[];
  selected: string;
  onSelect: (number: string) => void;
}) {
  return (
    <section className="request-list-panel" aria-labelledby="request-list-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Work queue</p>
          <h2 id="request-list-title">Requests</h2>
        </div>
        <span>{requests.length} cases</span>
      </div>
      <div className="request-table" role="list">
        {requests.map((request) => (
          <button
            key={request.requestNumber}
            role="listitem"
            className={request.requestNumber === selected ? "request-row selected" : "request-row"}
            onClick={() => onSelect(request.requestNumber)}
          >
            <strong>{request.requestNumber}</strong>
            <span>{request.property}</span>
            <span>{request.owner}</span>
            <span className={`status ${statusClass(request.status)}`}>{request.status}</span>
            <span>{finalState(request)}</span>
            <time>{request.lastUpdated}</time>
          </button>
        ))}
      </div>
    </section>
  );
}
function RequestDetail({
  request,
  busy,
  identity,
  onAction,
  onUpdate,
  onNavInspections
}: {
  request: KeyHandoverRequest;
  busy: boolean;
  identity: DevelopmentIdentity;
  onAction: (path: string, parameters?: Record<string, string>) => Promise<void>;
  onUpdate: (updated: KeyHandoverRequest) => void;
  onNavInspections: () => void;
}) {
  const blocked = request.inspection === "Waiting";
  const displayedFinalState = finalState(request);
  return (
    <section className="detail-panel" aria-labelledby="detail-title">
      <div className="detail-heading">
        <div>
          <p className="eyebrow">{request.requestNumber}</p>
          <h2 id="detail-title">{request.property}</h2>
          <p>{request.owner}</p>
        </div>
        <span className={`status ${statusClass(request.status)}`}>{request.status}</span>
      </div>
      <div className="summary-grid">
        <article>
          <span>Inspection</span>
          <strong>{request.inspection}</strong>
          {request.inspectionProcessId && (
            <p style={{ fontSize: "0.75rem", color: "#5f7184", marginTop: "0.25rem" }}>
              Process: <code>{request.inspectionProcessId}</code>
              <button
                className="quiet"
                style={{ marginLeft: "0.5rem", fontSize: "0.75rem" }}
                onClick={onNavInspections}
              >
                View
              </button>
            </p>
          )}
          {blocked && identity === "processOwner" && (
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void onAction("inspection/resume")}
            >
              Resume after inspection (recovery)
            </button>
          )}
        </article>
        <article>
          <span>State version</span>
          <strong>v{request.stateVersion}</strong>
          <small>Local durable demo state</small>
        </article>
        <article>
          <span>Final decision</span>
          <strong>{displayedFinalState}</strong>
        </article>
      </div>
      <section className="workbench" aria-labelledby="workbench-title">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Clearance workbench</p>
            <h2 id="workbench-title">Parallel checks</h2>
          </div>
          {blocked && <span className="barrier">Inspection barrier active</span>}
        </div>
        <div className="task-grid">
          {request.tasks.map((task) => (
            <TaskCard key={task.id} task={task} blocked={blocked} busy={busy} onAction={onAction} />
          ))}
        </div>
      </section>
      <section className="final-result" aria-labelledby="result-title">
        <div>
          <p className="eyebrow">Final result</p>
          <h2 id="result-title">{displayedFinalState}</h2>
          <p>
            {request.status === "Exception rejected"
              ? `Exception rejected: ${request.exceptionReason}`
              : request.notification === "Delivered"
                ? "Authorization notification delivered"
                : request.notification === "Failed"
                  ? "Notification needs retry"
                  : "Notification starts after authorization"}
          </p>
        </div>
        <div className="result-actions">
          {identity === "processOwner" && (
            <button
              className="quiet"
              disabled={busy}
              onClick={() => void onAction("notification/fail-next")}
            >
              Fail next notification (local test)
            </button>
          )}
          {request.notification === "Failed" && (
            <button
              className="primary"
              disabled={busy}
              onClick={() => void onAction("notification/retry")}
            >
              Retry notification
            </button>
          )}
        </div>
      </section>
      <NotificationRecoveryPanel request={request} />
      <ExceptionDecisionPanel
        request={request}
        identity={identity}
        busy={busy}
        onUpdate={onUpdate}
      />
      <HoldManagementPanel request={request} identity={identity} busy={busy} onAction={onAction} />
      <AuditTimeline request={request} />
    </section>
  );
}
function NotificationRecoveryPanel({ request }: { request: KeyHandoverRequest }) {
  if (request.notification === "Not started") return null;
  return (
    <section className="notification-panel" aria-labelledby="notification-title">
      <div>
        <p className="eyebrow">Notification recovery</p>
        <h2 id="notification-title">{request.notification}</h2>
      </div>
      <p>Attempts: {request.notificationAttempts}</p>
      {request.notification === "Failed" && (
        <p>Failure reason: {request.notificationFailure || "Synthetic delivery failure"}</p>
      )}
      <p>Last attempt: {request.lastUpdated}</p>
    </section>
  );
}
function HoldManagementPanel({
  request,
  identity,
  busy,
  onAction
}: {
  request: KeyHandoverRequest;
  identity: DevelopmentIdentity;
  busy: boolean;
  onAction: (path: string, parameters?: Record<string, string>) => Promise<void>;
}) {
  const [branch, setBranch] = useState("");
  const [summary, setSummary] = useState("");
  const [reference, setReference] = useState("");
  const [extensionDays, setExtensionDays] = useState("5");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState("");
  const hold = request.hold;
  const canManage = identity === "processOwner" && request.status === "On hold";
  if (!hold) return null;
  const remediated = new Set(hold.remediations.map((item) => item.branch));
  const allResolved = hold.affectedBranches.every((item) => remediated.has(item));
  const act = async (path: string, parameters: Record<string, string> = {}) => {
    try {
      await onAction(path, {
        ...parameters,
        expectedStateVersion: String(request.stateVersion),
        correlationId: `corr-${request.requestNumber}-hold`,
        causationId: `cause-${request.requestNumber}-${path.replaceAll("/", "-")}`
      });
      setMessage("");
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "The hold action could not be completed."
      );
    }
  };
  const recordResolution = () => {
    if (!branch || !summary.trim() || !reference.trim()) {
      setMessage("Select an affected branch and provide a summary and supporting reference.");
      return;
    }
    void act("hold/remediation", {
      branch,
      summary: summary.trim(),
      supportingReference: reference.trim()
    });
  };
  const extend = () => {
    if (!reason.trim()) {
      setMessage("An extension reason is required.");
      return;
    }
    void act("hold/extend", {
      extensionBusinessDays: extensionDays,
      reason: reason.trim(),
      reviewAt: hold.reviewAt,
      expiresAt: hold.expiresAt
    });
  };
  return (
    <section className="hold-panel" aria-labelledby="hold-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Hold management</p>
          <h2 id="hold-title">Hold cycle {hold.cycleNumber}</h2>
        </div>
        <span className={`status ${statusClass(hold.status)}`}>{hold.status}</span>
      </div>
      <dl className="hold-details">
        <div>
          <dt>Policy</dt>
          <dd>{hold.policyVersion}</dd>
        </div>
        <div>
          <dt>Owner</dt>
          <dd>{hold.owner}</dd>
        </div>
        <div>
          <dt>Reason</dt>
          <dd>{hold.reason}</dd>
        </div>
        <div>
          <dt>Started</dt>
          <dd>{hold.startedAt}</dd>
        </div>
        <div>
          <dt>Review</dt>
          <dd>{hold.reviewAt}</dd>
        </div>
        <div>
          <dt>Expiry</dt>
          <dd>{hold.expiresAt}</dd>
        </div>
        <div>
          <dt>Extensions</dt>
          <dd>{hold.extensionCount}</dd>
        </div>
      </dl>
      <div className="remediation-list" aria-label="Affected branch remediation status">
        {hold.affectedBranches.map((affectedBranch) => {
          const remediation = hold.remediations.find((item) => item.branch === affectedBranch);
          return (
            <p key={affectedBranch}>
              <strong>{affectedBranch}</strong>{" "}
              {remediation ? `Resolved: ${remediation.summary}` : "Resolution required"}
            </p>
          );
        })}
      </div>
      {canManage ? (
        <div className="hold-actions">
          <div className="hold-form">
            <h3>Record resolution</h3>
            <select
              aria-label="Affected branch"
              value={branch}
              onChange={(event) => setBranch(event.target.value)}
            >
              <option value="">Select branch</option>
              {hold.affectedBranches.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
            <input
              aria-label="Remediation summary"
              value={summary}
              onChange={(event) => setSummary(event.target.value)}
              placeholder="Resolution summary"
            />
            <input
              aria-label="Supporting reference"
              value={reference}
              onChange={(event) => setReference(event.target.value)}
              placeholder="Supporting reference"
            />
            <button className="secondary" disabled={busy} onClick={recordResolution}>
              Record resolution
            </button>
          </div>
          <div className="hold-form">
            <h3>Extend hold</h3>
            <input
              aria-label="Extension business days"
              type="number"
              min="1"
              max="5"
              value={extensionDays}
              onChange={(event) => setExtensionDays(event.target.value)}
            />
            <input
              aria-label="Extension reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="Extension reason"
            />
            <button className="secondary" disabled={busy} onClick={extend}>
              Extend hold
            </button>
          </div>
          <div className="hold-terminal-actions">
            <button
              className="primary"
              disabled={busy || !allResolved}
              onClick={() => void act("hold/resume")}
            >
              Resume workflow
            </button>
            <button className="secondary" disabled={busy} onClick={() => void act("hold/reject")}>
              Reject case
            </button>
            <button className="quiet" disabled={busy} onClick={() => void act("hold/cancel")}>
              Cancel case
            </button>
            <button className="quiet" disabled={busy} onClick={() => void act("hold/evaluate")}>
              Evaluate hold timing
            </button>
          </div>
        </div>
      ) : (
        <p className="hold-read-only">Hold information is read-only for this role.</p>
      )}
      {message && (
        <p className="form-message" role="alert">
          {message}
        </p>
      )}
    </section>
  );
}
function ExceptionDecisionPanel({
  request,
  identity,
  busy,
  onUpdate
}: {
  request: KeyHandoverRequest;
  identity: DevelopmentIdentity;
  busy: boolean;
  onUpdate: (updated: KeyHandoverRequest) => void;
}) {
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState("");
  const [saving, setSaving] = useState(false);
  const awaitingDecision = request.status === "Exception approval required";
  const authorized = identity === "processOwner";
  const decide = async (decision: "approve" | "reject") => {
    if (!reason.trim()) {
      setMessage("A reason is required for an exception decision.");
      return;
    }
    setSaving(true);
    try {
      const updated = await keyHandoverApi.decideException(request.requestNumber, decision, {
        actor: identity,
        reason: reason.trim(),
        expectedStateVersion: String(request.stateVersion),
        correlationId: `corr-${request.requestNumber}-exception`,
        causationId: `cause-${request.requestNumber}-exception-${decision}`
      });
      onUpdate(updated);
      setMessage(
        decision === "approve"
          ? "Exception approved and notification started."
          : "Exception rejected."
      );
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "The exception decision could not be completed."
      );
    } finally {
      setSaving(false);
    }
  };
  if (!awaitingDecision) return null;
  return (
    <section className="exception-decision-panel" aria-labelledby="exception-decision-title">
      <p className="eyebrow">Exception decision</p>
      <h2 id="exception-decision-title">Exception approval required</h2>
      {authorized ? (
        <>
          <label>
            Decision reason
            <textarea value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          {message && (
            <p className="form-message" role="alert">
              {message}
            </p>
          )}
          <div className="exception-actions">
            <button
              className="primary"
              disabled={busy || saving}
              onClick={() => void decide("approve")}
            >
              {saving ? "Saving…" : "Approve exception"}
            </button>
            <button
              className="secondary"
              disabled={busy || saving}
              onClick={() => void decide("reject")}
            >
              Reject exception
            </button>
          </div>
        </>
      ) : (
        <p>You can view this exception, but only an authorized Process Owner can decide it.</p>
      )}
    </section>
  );
}
function TaskCard({
  task,
  blocked,
  busy,
  onAction
}: {
  task: ClearanceTask;
  blocked: boolean;
  busy: boolean;
  onAction: (path: string, parameters?: Record<string, string>) => Promise<void>;
}) {
  const completed = task.status === "Completed";
  const complete = (outcome: Outcome) => {
    const path =
      task.id === "finance"
        ? "tasks/finance/complete"
        : task.id === "legal"
          ? "tasks/legal/complete"
          : "tasks/handover/complete";
    void onAction(path, task.id === "finance" ? {} : { outcome });
  };
  return (
    <article className={`task-card ${blocked ? "blocked" : ""}`}>
      <div className="task-card-heading">
        <div>
          <span className="task-kicker">{task.id}</span>
          <h3>{task.title}</h3>
        </div>
        <span className={`status ${statusClass(blocked ? "Blocked" : task.status)}`}>
          {blocked ? "Blocked" : task.status}
        </span>
      </div>
      <p>
        Assigned to: <strong>{task.assignedTo || "Unassigned"}</strong>
      </p>
      <div className="outcomes" aria-label={`${task.title} outcome`}>
        {(["GREEN", "AMBER", "RED"] as Outcome[]).map((outcome) => {
          const selected = task.outcome === outcome;
          return (
            <span
              key={outcome}
              aria-disabled={completed || undefined}
              aria-label={`${outcome}${selected ? ", selected" : ""}${completed ? ", completed" : ""}`}
              className={`outcome ${outcome.toLowerCase()} ${selected ? "selected-outcome" : ""} ${completed ? "completed-outcome" : ""}`}
            >
              {outcome}
            </span>
          );
        })}
      </div>
      <div className="task-actions">
        {!completed && (
          <button
            className="secondary"
            disabled={blocked || busy || task.status === "Claimed"}
            onClick={() => void onAction(`tasks/${task.id}/claim`)}
          >
            Claim task
          </button>
        )}
        {!completed && (
          <button
            className="quiet"
            disabled={blocked || busy || !task.assignedTo}
            onClick={() => void onAction(`tasks/${task.id}/reassign`)}
          >
            Reassign
          </button>
        )}
        {!completed && (
          <div className="complete-actions">
            <button
              disabled={blocked || busy || !task.assignedTo}
              onClick={() => complete("GREEN")}
            >
              Complete GREEN
            </button>
            <button
              disabled={blocked || busy || !task.assignedTo}
              onClick={() => complete("AMBER")}
            >
              Complete AMBER
            </button>
            <button disabled={blocked || busy || !task.assignedTo} onClick={() => complete("RED")}>
              Complete RED
            </button>
          </div>
        )}
      </div>
    </article>
  );
}
function AuditTimeline({ request }: { request: KeyHandoverRequest }) {
  const [expanded, setExpanded] = useState(true);
  return (
    <section className="audit-panel" aria-labelledby="audit-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Audit timeline</p>
          <h2 id="audit-title">Activity history</h2>
        </div>
        <div className="history-controls">
          <span>{request.audit.length} events</span>
          <button
            className="quiet"
            aria-expanded={expanded}
            aria-controls={`activity-history-${request.requestNumber}`}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? "Collapse history" : "Expand history"}
          </button>
        </div>
      </div>
      {expanded && (
        <ol id={`activity-history-${request.requestNumber}`}>
          {request.audit
            .slice()
            .reverse()
            .map((audit) => (
              <li key={audit.id}>
                <span className="timeline-dot" />
                <div>
                  <strong>{audit.eventType}</strong>
                  <p>
                    {audit.actor} · {audit.timestamp}
                  </p>
                  <small>
                    {audit.correlationId} · {audit.causationId}
                  </small>
                </div>
              </li>
            ))}
        </ol>
      )}
    </section>
  );
}
function InspectionWorkspace({
  inspections,
  selectedId,
  detail,
  history,
  busy,
  identity,
  onSelect,
  onAction
}: {
  inspections: InspectionSummary[];
  selectedId: string;
  detail: InspectionProcess | null;
  history: InspectionHistoryEvent[];
  busy: boolean;
  identity: DevelopmentIdentity;
  onSelect: (id: string) => void;
  onAction: (fn: () => Promise<InspectionProcess>) => void;
}) {
  return detail ? (
    <div className="request-layout">
      <section className="request-list-panel" aria-label="Inspection processes">
        <p className="eyebrow">Inspection processes</p>
        <h2>Active and recent</h2>
        {inspections.length === 0 && <p>No inspection processes yet.</p>}
        {inspections.map((insp) => (
          <button
            key={insp.id}
            className={`request-row${insp.id === selectedId ? " selected" : ""}`}
            onClick={() => onSelect(insp.id)}
          >
            <span className="request-number">{insp.id}</span>
            <span className="request-property">{insp.parentRequestId}</span>
            <span className={statusClass(insp.status)}>{insp.status}</span>
          </button>
        ))}
      </section>
      <section className="detail-panel" aria-label="Inspection detail">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Inspection process</p>
            <h2>{detail.id}</h2>
          </div>
          <span className={`status ${statusClass(detail.status)}`}>{detail.status}</span>
        </div>
        <div className="summary-grid">
          <div>
            <strong>Parent</strong>
            <span>{detail.parentRequestId}</span>
          </div>
          <div>
            <strong>Property</strong>
            <span>{detail.propertyReference}</span>
          </div>
          <div>
            <strong>Type</strong>
            <span>{detail.inspectionType}</span>
          </div>
        </div>
        {detail.cancellationReason && (
          <p className="form-message" role="alert">
            Cancelled: {detail.cancellationReason}
          </p>
        )}
        {detail.attempts.length > 0 && (
          <section className="inspection-section">
            <h3>Inspection attempts</h3>
            {detail.attempts.map((a) => (
              <p key={a.number} className="inspection-action">
                Attempt {a.number}: {a.result} — {a.findings}
                {a.validUntil && <> (valid until {a.validUntil})</>}
              </p>
            ))}
          </section>
        )}
        {detail.remediationCycles.length > 0 && (
          <section className="inspection-section">
            <h3>Remediation cycles</h3>
            {detail.remediationCycles.map((c) => (
              <p key={c.number} className="inspection-action">
                Cycle {c.number}: {c.status}
                {c.resolutionSummary && <> — {c.resolutionSummary}</>}
              </p>
            ))}
          </section>
        )}
        {detail.tasks.length > 0 && (
          <section className="inspection-section">
            <h3>Tasks</h3>
            <div className="task-grid">
              {detail.tasks.map((task) => (
                <InspectionTaskCard
                  key={task.id}
                  task={task}
                  process={detail}
                  busy={busy}
                  identity={identity}
                  onAction={onAction}
                />
              ))}
            </div>
          </section>
        )}
        <AuditTimelineInspection history={history} />
        {identity === "processOwner" &&
          detail.status !== "COMPLETED" &&
          detail.status !== "CANCELLED" && (
            <button
              disabled={busy}
              onClick={() =>
                onAction(() =>
                  inspectionApi.cancel(detail.id, identity, "Cancelled by process owner")
                )
              }
            >
              Cancel inspection
            </button>
          )}
      </section>
    </div>
  ) : (
    <section className="detail-panel" aria-label="Inspection processes">
      <p className="eyebrow">Inspection processes</p>
      <h2>Active and recent</h2>
      {inspections.length === 0 && <p>No inspection processes yet.</p>}
      {inspections.map((insp) => (
        <button
          key={insp.id}
          className={`request-row${insp.id === selectedId ? " selected" : ""}`}
          onClick={() => onSelect(insp.id)}
        >
          <span className="request-number">{insp.id}</span>
          <span className="request-property">{insp.parentRequestId}</span>
          <span className={statusClass(insp.status)}>{insp.status}</span>
        </button>
      ))}
    </section>
  );
}
function InspectionTaskCard({
  task,
  process,
  busy,
  identity,
  onAction
}: {
  task: InspectionTask;
  process: InspectionProcess;
  busy: boolean;
  identity: DevelopmentIdentity;
  onAction: (fn: () => Promise<InspectionProcess>) => void;
}) {
  const open = task.status === "OPEN";
  const claimed = task.status === "CLAIMED";
  const isInspection = task.type === "INSPECTION";
  const actor = identity;
  const canDoInspection = identity === "inspectionOfficer";
  const canDoRemediation = identity === "remediationOfficer";
  return (
    <article className="task-card">
      <div className="task-card-heading">
        <span>{task.type}</span>
        <span className={`status ${statusClass(task.status)}`}>{task.status}</span>
      </div>
      <p className="task-id">{task.id}</p>
      {task.assignee && <p>Assignee: {task.assignee}</p>}
      {task.outcome && <p>Outcome: {task.outcome}</p>}
      {open && isInspection && canDoInspection && (
        <button
          disabled={busy}
          onClick={() => onAction(() => inspectionApi.claimTask(process.id, task.id, actor))}
        >
          Claim inspection
        </button>
      )}
      {open && !isInspection && canDoRemediation && (
        <button
          disabled={busy}
          onClick={() => onAction(() => inspectionApi.claimRemediation(process.id, task.id, actor))}
        >
          Claim remediation
        </button>
      )}
      {claimed && isInspection && canDoInspection && (
        <>
          <button
            disabled={busy}
            onClick={() =>
              onAction(() =>
                inspectionApi.completePassed(
                  process.id,
                  task.id,
                  actor,
                  "All clear",
                  "evidence-passed"
                )
              )
            }
          >
            Complete PASSED
          </button>
          <button
            disabled={busy}
            onClick={() =>
              onAction(() =>
                inspectionApi.completeFailed(
                  process.id,
                  task.id,
                  actor,
                  "Issues found",
                  "evidence-failed"
                )
              )
            }
          >
            Complete FAILED
          </button>
        </>
      )}
      {claimed && !isInspection && canDoRemediation && (
        <button
          disabled={busy}
          onClick={() =>
            onAction(() =>
              inspectionApi.completeRemediation(
                process.id,
                task.id,
                actor,
                "All issues resolved",
                "remediation-evid"
              )
            )
          }
        >
          Complete remediation
        </button>
      )}
      {open && isInspection && !canDoInspection && (
        <p className="form-hint">Only an Inspection Officer may claim this task.</p>
      )}
      {open && !isInspection && !canDoRemediation && (
        <p className="form-hint">Only a Remediation Officer may claim this task.</p>
      )}
    </article>
  );
}
function AuditTimelineInspection({ history }: { history: InspectionHistoryEvent[] }) {
  const [expanded, setExpanded] = useState(true);
  return (
    <section className="audit-panel" aria-labelledby="insp-audit-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Audit timeline</p>
          <h2 id="insp-audit-title">Activity history</h2>
        </div>
        <div className="history-controls">
          <span>{history.length} events</span>
          <button
            className="quiet"
            aria-expanded={expanded}
            onClick={() => setExpanded((cur) => !cur)}
          >
            {expanded ? "Collapse history" : "Expand history"}
          </button>
        </div>
      </div>
      {expanded && (
        <ol>
          {history.map((e, i) => (
            <li key={i}>
              <span className="timeline-dot" />
              <div>
                <strong>{e.eventType}</strong>
                <p>{e.detail}</p>
                {e.timestamp && <small>{e.timestamp}</small>}
                {e.actor && e.actor !== "system" && <small> — Actor: {e.actor}</small>}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
