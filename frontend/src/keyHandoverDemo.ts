export type RequestStatus =
  | "Waiting for inspection"
  | "Clearance in progress"
  | "Authorized"
  | "Exception approval required"
  | "On hold";

export type Outcome = "GREEN" | "AMBER" | "RED";
export type TaskStatus = "Blocked" | "Open" | "Claimed" | "Completed";
export type NotificationStatus = "Not started" | "Delivered" | "Failed";

export interface AuditEvent {
  id: string;
  actor: string;
  eventType: string;
  timestamp: string;
  correlationId: string;
  causationId: string;
}

export interface ClearanceTask {
  id: "handover" | "finance" | "legal";
  title: string;
  status: TaskStatus;
  assignedTo?: string;
  outcome?: Outcome;
}

export interface KeyHandoverRequest {
  requestNumber: string;
  property: string;
  owner: string;
  status: RequestStatus;
  stateVersion: number;
  inspection: "Available" | "Waiting";
  finalDecision?: RequestStatus;
  notification: NotificationStatus;
  lastUpdated: string;
  tasks: ClearanceTask[];
  audit: AuditEvent[];
}

const event = (
  id: string,
  actor: string,
  eventType: string,
  timestamp: string,
  correlationId: string,
  causationId: string
): AuditEvent => ({ id, actor, eventType, timestamp, correlationId, causationId });

const tasks = (status: TaskStatus): ClearanceTask[] => [
  { id: "handover", title: "Handover check", status },
  { id: "finance", title: "Finance clearance", status },
  { id: "legal", title: "Legal clearance", status }
];

export const demoRequests: KeyHandoverRequest[] = [
  {
    requestNumber: "KH-101",
    property: "Demo Property 101",
    owner: "Demo Owner 101",
    status: "Waiting for inspection",
    stateVersion: 3,
    inspection: "Waiting",
    notification: "Not started",
    lastUpdated: "20 Jun 2026, 09:10",
    tasks: tasks("Blocked"),
    audit: [
      event("a-101-1", "Front desk", "Request submitted", "20 Jun 2026, 09:05", "corr-101", "cause-101-submit"),
      event("a-101-2", "Workflow service", "Inspection requested", "20 Jun 2026, 09:10", "corr-101", "cause-101-inspection")
    ]
  },
  {
    requestNumber: "KH-102",
    property: "Demo Property 102",
    owner: "Demo Owner 102",
    status: "Clearance in progress",
    stateVersion: 8,
    inspection: "Available",
    notification: "Not started",
    lastUpdated: "20 Jun 2026, 10:32",
    tasks: [
      { id: "handover", title: "Handover check", status: "Open" },
      { id: "finance", title: "Finance clearance", status: "Claimed", assignedTo: "Demo finance reviewer" },
      { id: "legal", title: "Legal clearance", status: "Completed", assignedTo: "Demo legal reviewer", outcome: "GREEN" }
    ],
    audit: [
      event("a-102-1", "Workflow service", "Inspection confirmed", "20 Jun 2026, 10:10", "corr-102", "cause-102-inspection"),
      event("a-102-2", "Demo legal reviewer", "Legal clearance completed", "20 Jun 2026, 10:32", "corr-102", "cause-102-legal")
    ]
  },
  {
    requestNumber: "KH-103",
    property: "Demo Property 103",
    owner: "Demo Owner 103",
    status: "Authorized",
    stateVersion: 14,
    inspection: "Available",
    finalDecision: "Authorized",
    notification: "Delivered",
    lastUpdated: "20 Jun 2026, 11:45",
    tasks: [
      { id: "handover", title: "Handover check", status: "Completed", assignedTo: "Demo handover reviewer", outcome: "GREEN" },
      { id: "finance", title: "Finance clearance", status: "Completed", assignedTo: "Demo finance reviewer", outcome: "GREEN" },
      { id: "legal", title: "Legal clearance", status: "Completed", assignedTo: "Demo legal reviewer", outcome: "GREEN" }
    ],
    audit: [
      event("a-103-1", "Decision service", "Release authorized", "20 Jun 2026, 11:40", "corr-103", "cause-103-decision"),
      event("a-103-2", "Notification service", "Authorization notification delivered", "20 Jun 2026, 11:45", "corr-103", "cause-103-notification")
    ]
  }
];

export const decisionFor = (tasksToEvaluate: ClearanceTask[]): RequestStatus | undefined => {
  if (tasksToEvaluate.some((task) => task.status !== "Completed")) return undefined;
  const outcomes = tasksToEvaluate.map((task) => task.outcome);
  if (outcomes.includes("RED")) return "On hold";
  if (outcomes.includes("AMBER")) return "Exception approval required";
  return "Authorized";
};
