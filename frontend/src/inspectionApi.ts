export type InspectionStatus =
  | "REQUESTED"
  | "ASSIGNED"
  | "IN_PROGRESS"
  | "PASSED"
  | "FAILED"
  | "WAITING_FOR_REMEDIATION"
  | "WAITING_FOR_REINSPECTION"
  | "CANCELLED"
  | "COMPLETED";

export type InspectionTaskType = "INSPECTION" | "REMEDIATION";
export type InspectionTaskStatus = "OPEN" | "CLAIMED" | "COMPLETED" | "CANCELLED";
export type InspectionRole = "INSPECTION_OFFICER" | "REMEDIATION_OFFICER" | "TEAM_HEAD" | "PROCESS_OWNER";
export type InspectionResult = "PASSED" | "FAILED";
export type RemediationStatus = "REQUIRED" | "ASSIGNED" | "IN_PROGRESS" | "COMPLETED" | "REJECTED" | "CANCELLED";

export interface InspectionAttempt {
  number: number;
  result: InspectionResult;
  findings: string;
  evidenceReference: string;
  completedAt: string;
  validUntil?: string;
}

export interface RemediationCycle {
  number: number;
  status: RemediationStatus;
  resolutionSummary?: string;
  remediationReference?: string;
}

export interface InspectionTask {
  id: string;
  type: InspectionTaskType;
  status: InspectionTaskStatus;
  requiredRole: InspectionRole;
  assignee?: string;
  createdAt: string;
  claimedAt?: string;
  completedAt?: string;
  outcome?: string;
  version: number;
}

export interface InspectionProcess {
  id: string;
  businessKey: string;
  parentRequestId: string;
  propertyReference: string;
  inspectionType: string;
  status: InspectionStatus;
  version: number;
  cancellationReason?: string;
  attempts: InspectionAttempt[];
  remediationCycles: RemediationCycle[];
  tasks: InspectionTask[];
  correlationId: string;
  causationId: string;
  updatedAt: string;
}

export interface InspectionSummary {
  id: string;
  status: InspectionStatus;
  parentRequestId: string;
  propertyReference: string;
}

export interface InspectionHistoryEvent {
  type: string;
  number: number;
  result?: string;
  findings?: string;
  status?: string;
  completedAt?: string;
}

const baseUrl = "/api/inspections";

async function request<T>(path = "", options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      ...(options.headers ?? {}),
    },
  });
  const payload = (await response.json()) as T & { error?: string };
  if (!response.ok)
    throw new Error(payload.error ?? "The inspection request could not be completed.");
  return payload;
}

export const inspectionApi = {
  async list(): Promise<InspectionSummary[]> {
    const data = await request<{ inspections: InspectionSummary[] }>();
    return data.inspections ?? [];
  },

  async create(
    propertyReference: string,
    parentRequestId: string,
    actor: string,
    inspectionType = "synthetic"
  ): Promise<InspectionProcess> {
    const body = new URLSearchParams({
      propertyReference,
      parentRequestId,
      actor,
      inspectionType,
    });
    return request("", { method: "POST", body });
  },

  async get(id: string): Promise<InspectionProcess> {
    return request(`/${encodeURIComponent(id)}`);
  },

  async getHistory(id: string): Promise<InspectionHistoryEvent[]> {
    return request(`/${encodeURIComponent(id)}/history`);
  },

  async cancel(id: string, actor: string, reason: string): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor, reason });
    return request(`/${encodeURIComponent(id)}/cancel`, { method: "POST", body });
  },

  async claimTask(id: string, taskId: string, actor: string): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor });
    return request(`/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/claim`, {
      method: "POST",
      body,
    });
  },

  async claimRemediation(id: string, taskId: string, actor: string): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/claim-remediation`,
      { method: "POST", body }
    );
  },

  async claimReinspection(id: string, taskId: string, actor: string): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/claim-reinspection`,
      { method: "POST", body }
    );
  },

  async completePassed(
    id: string,
    taskId: string,
    actor: string,
    findings: string,
    evidenceReference: string
  ): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor, findings, evidenceReference });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/complete-passed`,
      { method: "POST", body }
    );
  },

  async completeFailed(
    id: string,
    taskId: string,
    actor: string,
    findings: string,
    evidenceReference: string
  ): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor, findings, evidenceReference });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/complete-failed`,
      { method: "POST", body }
    );
  },

  async completeRemediation(
    id: string,
    taskId: string,
    actor: string,
    resolutionSummary: string,
    evidenceReference: string
  ): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor, resolutionSummary, evidenceReference });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/complete-remediation`,
      { method: "POST", body }
    );
  },

  async completeReinspection(
    id: string,
    taskId: string,
    actor: string,
    findings: string,
    evidenceReference: string
  ): Promise<InspectionProcess> {
    const body = new URLSearchParams({ actor, findings, evidenceReference });
    return request(
      `/${encodeURIComponent(id)}/tasks/${encodeURIComponent(taskId)}/complete-reinspection`,
      { method: "POST", body }
    );
  },
};
