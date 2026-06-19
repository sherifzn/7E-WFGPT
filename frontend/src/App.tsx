import { FormEvent, useMemo, useState } from "react";
import {
  ClearanceTask,
  demoRequests,
  KeyHandoverRequest,
  NotificationStatus,
  Outcome,
  RequestStatus,
  decisionFor
} from "./keyHandoverDemo";
import "./styles.css";

type View = "requests" | "new-request";

const statusClass = (status: string) => status.toLowerCase().replaceAll(" ", "-");

const nextTimestamp = () => "20 Jun 2026, 12:00";

export function App() {
  const [view, setView] = useState<View>("requests");
  const [requests, setRequests] = useState<KeyHandoverRequest[]>(demoRequests);
  const [selectedNumber, setSelectedNumber] = useState(demoRequests[0].requestNumber);
  const [propertyReference, setPropertyReference] = useState("");
  const [ownerReference, setOwnerReference] = useState("");
  const [formMessage, setFormMessage] = useState<string>();

  const selected = useMemo(
    () => requests.find((request) => request.requestNumber === selectedNumber) ?? requests[0],
    [requests, selectedNumber]
  );

  const updateRequest = (requestNumber: string, change: (request: KeyHandoverRequest) => KeyHandoverRequest) => {
    setRequests((current) => current.map((request) => (request.requestNumber === requestNumber ? change(request) : request)));
  };

  const appendAudit = (request: KeyHandoverRequest, actor: string, eventType: string): KeyHandoverRequest => ({
    ...request,
    stateVersion: request.stateVersion + 1,
    lastUpdated: nextTimestamp(),
    audit: [
      ...request.audit,
      {
        id: `${request.requestNumber}-${request.audit.length + 1}`,
        actor,
        eventType,
        timestamp: nextTimestamp(),
        correlationId: `corr-${request.requestNumber.toLowerCase()}`,
        causationId: `cause-${request.requestNumber.toLowerCase()}-${request.audit.length + 1}`
      }
    ]
  });

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!propertyReference.trim() || !ownerReference.trim()) {
      setFormMessage("Enter both references to create a synthetic request.");
      return;
    }
    const requestNumber = `KH-${104 + requests.length}`;
    const created: KeyHandoverRequest = {
      requestNumber,
      property: propertyReference.trim(),
      owner: ownerReference.trim(),
      status: "Waiting for inspection",
      stateVersion: 1,
      inspection: "Waiting",
      notification: "Not started",
      lastUpdated: nextTimestamp(),
      tasks: [
        { id: "handover", title: "Handover check", status: "Blocked" },
        { id: "finance", title: "Finance clearance", status: "Blocked" },
        { id: "legal", title: "Legal clearance", status: "Blocked" }
      ],
      audit: [
        {
          id: `${requestNumber}-1`,
          actor: "Front desk",
          eventType: "Synthetic request created",
          timestamp: nextTimestamp(),
          correlationId: `corr-${requestNumber.toLowerCase()}`,
          causationId: `cause-${requestNumber.toLowerCase()}-submit`
        }
      ]
    };
    setRequests((current) => [created, ...current]);
    setSelectedNumber(requestNumber);
    setPropertyReference("");
    setOwnerReference("");
    setFormMessage(`${requestNumber} created. It is waiting for inspection.`);
  };

  const resumeInspection = () => {
    if (!selected) return;
    updateRequest(selected.requestNumber, (request) =>
      appendAudit(
        {
          ...request,
          inspection: "Available",
          status: "Clearance in progress",
          tasks: request.tasks.map((task) => ({ ...task, status: "Open" as const }))
        },
        "Workflow service",
        "Inspection confirmed; clearance opened"
      )
    );
  };

  const updateTask = (taskId: ClearanceTask["id"], change: (task: ClearanceTask) => ClearanceTask, eventType: string) => {
    if (!selected || selected.inspection === "Waiting") return;
    updateRequest(selected.requestNumber, (request) => {
      const tasks = request.tasks.map((task) => (task.id === taskId ? change(task) : task));
      const decision = decisionFor(tasks);
      const status = decision ?? "Clearance in progress";
      return appendAudit(
        { ...request, tasks, status, finalDecision: decision },
        "Current demo user",
        eventType
      );
    });
  };

  const setNotification = (notification: NotificationStatus, eventType: string) => {
    if (!selected) return;
    updateRequest(selected.requestNumber, (request) => appendAudit({ ...request, notification }, "Notification service", eventType));
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><span className="brand-mark">KH</span><span>Workflow Studio</span></div>
        <nav aria-label="Primary navigation">
          <button className={view === "requests" ? "nav-link active" : "nav-link"} onClick={() => setView("requests")}>Key handovers</button>
          <button className={view === "new-request" ? "nav-link active" : "nav-link"} onClick={() => setView("new-request")}>New request</button>
        </nav>
        <div className="demo-notice"><strong>Local demo</strong><span>Synthetic data only</span></div>
      </aside>
      <main className="workspace">
        <header className="topbar"><div><p className="eyebrow">Property services</p><h1>Key Handover</h1></div><span className="local-pill">Local-only demo</span></header>
        {view === "new-request" ? (
          <section className="new-request-panel" aria-labelledby="new-request-title">
            <p className="eyebrow">Create a request</p><h2 id="new-request-title">New Key Handover request</h2>
            <p>Use synthetic references to preview validation and the inspection barrier.</p>
            <form onSubmit={handleSubmit} noValidate>
              <label>Property reference<input value={propertyReference} onChange={(event) => setPropertyReference(event.target.value)} placeholder="e.g. Demo Property 104" /></label>
              <label>Owner reference<input value={ownerReference} onChange={(event) => setOwnerReference(event.target.value)} placeholder="e.g. Demo Owner 104" /></label>
              <button className="primary" type="submit">Submit request</button>
            </form>
            {formMessage && <p className="form-message" role="status">{formMessage}</p>}
          </section>
        ) : selected ? (
          <div className="request-layout">
            <section className="request-list-panel" aria-labelledby="request-list-title">
              <div className="section-heading"><div><p className="eyebrow">Work queue</p><h2 id="request-list-title">Requests</h2></div><span>{requests.length} cases</span></div>
              <div className="request-table" role="list">
                {requests.map((request) => <button key={request.requestNumber} role="listitem" className={request.requestNumber === selected.requestNumber ? "request-row selected" : "request-row"} onClick={() => setSelectedNumber(request.requestNumber)}>
                  <strong>{request.requestNumber}</strong><span>{request.property}</span><span>{request.owner}</span><span className={`status ${statusClass(request.status)}`}>{request.status}</span><span>{request.finalDecision ?? "—"}</span><time>{request.lastUpdated}</time>
                </button>)}
              </div>
            </section>
            <section className="detail-panel" aria-labelledby="detail-title">
              <div className="detail-heading"><div><p className="eyebrow">{selected.requestNumber}</p><h2 id="detail-title">{selected.property}</h2><p>{selected.owner}</p></div><span className={`status ${statusClass(selected.status)}`}>{selected.status}</span></div>
              <div className="summary-grid"><article><span>Inspection</span><strong>{selected.inspection}</strong>{selected.inspection === "Waiting" && <button className="secondary" onClick={resumeInspection}>Resume after inspection</button>}</article><article><span>State version</span><strong>v{selected.stateVersion}</strong><small>Versioned local demo state</small></article><article><span>Final decision</span><strong>{selected.finalDecision ?? "Pending clearance"}</strong></article></div>
              <section className="workbench" aria-labelledby="workbench-title"><div className="section-heading"><div><p className="eyebrow">Clearance workbench</p><h2 id="workbench-title">Parallel checks</h2></div>{selected.inspection === "Waiting" && <span className="barrier">Inspection barrier active</span>}</div><div className="task-grid">{selected.tasks.map((task) => <TaskCard key={task.id} task={task} blocked={selected.inspection === "Waiting"} onClaim={() => updateTask(task.id, (current) => ({ ...current, status: "Claimed", assignedTo: "Current demo user" }), `${task.title} claimed`)} onReassign={() => updateTask(task.id, (current) => ({ ...current, status: "Claimed", assignedTo: "Demo reviewer" }), `${task.title} reassigned`)} onComplete={(outcome) => updateTask(task.id, (current) => ({ ...current, status: "Completed", outcome, assignedTo: current.assignedTo ?? "Current demo user" }), `${task.title} completed: ${outcome}`)} />)}</div></section>
              <section className="final-result" aria-labelledby="result-title"><div><p className="eyebrow">Final result</p><h2 id="result-title">{selected.finalDecision ?? "Awaiting all checks"}</h2><p>{selected.notification === "Delivered" ? "Authorization notification delivered" : selected.notification === "Failed" ? "Notification needs retry" : "Notification starts after authorization"}</p></div><div className="result-actions">{selected.finalDecision === "Authorized" && selected.notification === "Delivered" && <button className="secondary" onClick={() => setNotification("Failed", "Notification delivery issue recorded")}>Simulate delivery issue</button>}{selected.notification === "Failed" && <button className="primary" onClick={() => setNotification("Delivered", "Notification delivery retried successfully")}>Retry notification</button>}</div></section>
              <section className="audit-panel" aria-labelledby="audit-title"><div className="section-heading"><div><p className="eyebrow">Audit timeline</p><h2 id="audit-title">Activity history</h2></div><span>Immutable local events</span></div><ol>{selected.audit.slice().reverse().map((audit) => <li key={audit.id}><span className="timeline-dot" /><div><strong>{audit.eventType}</strong><p>{audit.actor} · {audit.timestamp}</p><small>{audit.correlationId} · {audit.causationId}</small></div></li>)}</ol></section>
            </section>
          </div>
        ) : null}
      </main>
    </div>
  );
}

interface TaskCardProps { task: ClearanceTask; blocked: boolean; onClaim: () => void; onReassign: () => void; onComplete: (outcome: Outcome) => void; }

function TaskCard({ task, blocked, onClaim, onReassign, onComplete }: TaskCardProps) {
  const completed = task.status === "Completed";
  return <article className={`task-card ${blocked ? "blocked" : ""}`}><div className="task-card-heading"><div><span className="task-kicker">{task.id}</span><h3>{task.title}</h3></div><span className={`status ${statusClass(blocked ? "Blocked" : task.status)}`}>{blocked ? "Blocked" : task.status}</span></div><p>Assigned to: <strong>{task.assignedTo ?? "Unassigned"}</strong></p><div className="outcomes" aria-label={`${task.title} outcome`}>{(["GREEN", "AMBER", "RED"] as Outcome[]).map((outcome) => <span key={outcome} className={task.outcome === outcome ? `outcome ${outcome.toLowerCase()} selected-outcome` : `outcome ${outcome.toLowerCase()}`}>{outcome}</span>)}</div><div className="task-actions">{!completed && <button className="secondary" disabled={blocked || task.status === "Claimed"} onClick={onClaim}>Claim task</button>}{!completed && <button className="quiet" disabled={blocked || !task.assignedTo} onClick={onReassign}>Reassign</button>}{!completed && <div className="complete-actions"><button disabled={blocked || !task.assignedTo} onClick={() => onComplete("GREEN")}>Complete GREEN</button><button disabled={blocked || !task.assignedTo} onClick={() => onComplete("AMBER")}>Complete AMBER</button><button disabled={blocked || !task.assignedTo} onClick={() => onComplete("RED")}>Complete RED</button></div>}</div></article>;
}
