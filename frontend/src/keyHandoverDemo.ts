export type RequestStatus =
  | "Waiting for inspection"
  | "Clearance in progress"
  | "Authorized"
  | "Exception approval required"
  | "Exception rejected"
  | "On hold"
  | "Notification failed";
export type Outcome = "GREEN" | "AMBER" | "RED";
export type TaskStatus = "Blocked" | "Open" | "Claimed" | "Completed";
export type NotificationStatus = "Not started" | "Pending" | "Delivered" | "Failed";
export type DevelopmentIdentity =
  | "requester"
  | "handoverOfficer"
  | "financeOfficer"
  | "legalOfficer"
  | "teamHead"
  | "processOwner";

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
  assignedTo: string;
  outcome: Outcome | "";
}
export interface KeyHandoverRequest {
  requestNumber: string;
  property: string;
  owner: string;
  status: RequestStatus;
  stateVersion: number;
  inspection: "Available" | "Waiting";
  finalDecision: RequestStatus | "";
  notification: NotificationStatus;
  exceptionDecision: "Approve Exception" | "Reject Exception" | "";
  exceptionReason: string;
  authorizationId: string;
  lastUpdated: string;
  tasks: ClearanceTask[];
  audit: AuditEvent[];
}

const baseUrl = import.meta.env.VITE_KEY_HANDOVER_API_URL ?? "/api/key-handovers";

async function request<T>(path = "", options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: { "Content-Type": "application/x-www-form-urlencoded", ...(options.headers ?? {}) }
  });
  const payload = (await response.json()) as T & { error?: string };
  if (!response.ok)
    throw new Error(payload.error ?? "The local demo request could not be completed.");
  return payload;
}

export const keyHandoverApi = {
  list: () => request<KeyHandoverRequest[]>(),
  create: (propertyReference: string, ownerReference: string, actor: DevelopmentIdentity) =>
    request<KeyHandoverRequest>("", {
      method: "POST",
      body: new URLSearchParams({ propertyReference, ownerReference, actor }).toString()
    }),
  action: (requestNumber: string, path: string, parameters: Record<string, string> = {}) =>
    request<KeyHandoverRequest>(`/${encodeURIComponent(requestNumber)}/${path}`, {
      method: "POST",
      body: new URLSearchParams(parameters).toString()
    }),
  decideException: (
    requestNumber: string,
    decision: "approve" | "reject",
    parameters: Record<string, string>
  ) =>
    request<KeyHandoverRequest>(`/${encodeURIComponent(requestNumber)}/exception/${decision}`, {
      method: "POST",
      body: new URLSearchParams(parameters).toString()
    }),
  audit: (requestNumber: string) =>
    request<AuditEvent[]>(`/${encodeURIComponent(requestNumber)}/audit`)
};
