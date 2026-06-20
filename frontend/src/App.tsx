import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  ClearanceTask,
  DevelopmentIdentity,
  keyHandoverApi,
  KeyHandoverRequest,
  Outcome
} from "./keyHandoverDemo";
import "./styles.css";

type View = "requests" | "new-request";
const statusClass = (status: string) => status.toLowerCase().replaceAll(" ", "-");
const identities: { value: DevelopmentIdentity; label: string }[] = [
  { value: "requester", label: "Requester" },
  { value: "handoverOfficer", label: "Handover officer" },
  { value: "financeOfficer", label: "Finance officer" },
  { value: "legalOfficer", label: "Legal officer" },
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
        {view === "new-request" ? (
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
            <RequestDetail request={selected} busy={busy} onAction={perform} />
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
            <span>{request.finalDecision || "—"}</span>
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
  onAction
}: {
  request: KeyHandoverRequest;
  busy: boolean;
  onAction: (path: string, parameters?: Record<string, string>) => Promise<void>;
}) {
  const blocked = request.inspection === "Waiting";
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
          {blocked && (
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void onAction("inspection/resume")}
            >
              Resume after inspection
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
          <strong>{request.finalDecision || "Pending clearance"}</strong>
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
          <h2 id="result-title">{request.finalDecision || "Awaiting all checks"}</h2>
          <p>
            {request.notification === "Delivered"
              ? "Authorization notification delivered"
              : request.notification === "Failed"
                ? "Notification needs retry"
                : "Notification starts after authorization"}
          </p>
        </div>
        <div className="result-actions">
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
      <AuditTimeline request={request} />
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
        {(["GREEN", "AMBER", "RED"] as Outcome[]).map((outcome) => (
          <span
            key={outcome}
            className={
              task.outcome === outcome
                ? `outcome ${outcome.toLowerCase()} selected-outcome`
                : `outcome ${outcome.toLowerCase()}`
            }
          >
            {outcome}
          </span>
        ))}
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
  return (
    <section className="audit-panel" aria-labelledby="audit-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Audit timeline</p>
          <h2 id="audit-title">Activity history</h2>
        </div>
        <span>Immutable local events</span>
      </div>
      <ol>
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
    </section>
  );
}
