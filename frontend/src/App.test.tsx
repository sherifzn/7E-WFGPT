import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, vi } from "vitest";
import { App } from "./App";

const request = (overrides: Record<string, unknown> = {}) => ({
  requestNumber: "KH-101",
  property: "Demo Property 101",
  owner: "Demo Owner 101",
  status: "Waiting For Inspection",
  stateVersion: 1,
  inspection: "Waiting",
  finalDecision: "",
  notification: "Not Started",
  notificationAttempts: 0,
  notificationFailure: "",
  exceptionDecision: "",
  exceptionReason: "",
  authorizationId: "",
  lastUpdated: "2026-06-20T10:00:00Z",
  tasks: [
    { id: "handover", title: "Handover check", status: "Blocked", assignedTo: "", outcome: "" },
    { id: "finance", title: "Finance clearance", status: "Blocked", assignedTo: "", outcome: "" },
    { id: "legal", title: "Legal clearance", status: "Blocked", assignedTo: "", outcome: "" }
  ],
  audit: [],
  hold: null,
  ...overrides
});

afterEach(() => vi.restoreAllMocks());

describe("Key Handover API demo", () => {
  it("keeps clearance gated until the backend resumes inspection", async () => {
    const waiting = request();
    const resumed = request({
      inspection: "Available",
      status: "Clearance In Progress",
      stateVersion: 2,
      tasks: waiting.tasks.map((task) => ({ ...task, status: "Open" }))
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [waiting] })
      .mockResolvedValueOnce({ ok: true, json: async () => resumed });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);
    expect(await screen.findByText("Inspection barrier active")).toBeVisible();
    expect(screen.getAllByRole("button", { name: "Claim task" })[0]).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Testing as"), { target: { value: "processOwner" } });
    fireEvent.click(screen.getByRole("button", { name: "Resume after inspection (recovery)" }));
    await waitFor(() =>
      expect(screen.queryByText("Inspection barrier active")).not.toBeInTheDocument()
    );
    expect(fetchMock.mock.calls[1]?.[0]).toContain("inspection/resume");
  });

  it("shows an empty state when the local API has no requests", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [] }));
    render(<App />);
    expect(await screen.findByText("No requests yet")).toBeVisible();
  });

  it("shows exception controls only to the Process Owner and requires a reason", async () => {
    const exception = request({
      status: "Exception approval required",
      finalDecision: "Exception approval required",
      inspection: "Available",
      stateVersion: 7,
      tasks: request().tasks.map((task) => ({ ...task, status: "Completed", outcome: "AMBER" }))
    });
    const approved = request({
      ...exception,
      status: "Authorized",
      notification: "Delivered",
      exceptionDecision: "Approve Exception",
      exceptionReason: "Synthetic exception review",
      authorizationId: "release-khr-KH-101"
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [exception] })
      .mockResolvedValueOnce({ ok: true, json: async () => approved });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    expect(await screen.findByText(/only an authorized Process Owner/)).toBeVisible();
    expect(screen.queryByRole("button", { name: "Approve exception" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Testing as"), { target: { value: "processOwner" } });
    expect(await screen.findByRole("button", { name: "Approve exception" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Approve exception" }));
    expect(
      await screen.findByText("A reason is required for an exception decision.")
    ).toBeVisible();
    fireEvent.change(screen.getByLabelText("Decision reason"), {
      target: { value: "Synthetic exception review" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Approve exception" }));
    await waitFor(() => expect(screen.getAllByText("Authorized")).toHaveLength(5));
    expect(screen.queryByText("Exception approval required")).not.toBeInTheDocument();
    expect(fetchMock.mock.calls[1]?.[0]).toContain("/KH-101/exception/approve");
  });

  it("synchronizes every final-state location after exception rejection", async () => {
    const exception = request({
      status: "Exception approval required",
      finalDecision: "Exception approval required",
      inspection: "Available",
      stateVersion: 7
    });
    const rejected = request({
      ...exception,
      status: "Exception rejected",
      exceptionDecision: "Reject Exception",
      exceptionReason: "Synthetic rejection reason"
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [exception] })
      .mockResolvedValueOnce({ ok: true, json: async () => rejected });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);

    await screen.findByText(/only an authorized Process Owner/);
    fireEvent.change(screen.getByLabelText("Testing as"), { target: { value: "processOwner" } });
    fireEvent.change(await screen.findByLabelText("Decision reason"), {
      target: { value: "Synthetic rejection reason" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Reject exception" }));

    await waitFor(() => expect(screen.getAllByText("Exception rejected")).toHaveLength(5));
    expect(screen.queryByText("Exception approval required")).not.toBeInTheDocument();
    expect(fetchMock.mock.calls[1]?.[0]).toContain("/KH-101/exception/reject");
  });

  it("keeps selected and disabled completed outcome labels visible", async () => {
    const completed = request({
      inspection: "Available",
      status: "Clearance in progress",
      tasks: request().tasks.map((task, index) => ({
        ...task,
        status: "Completed",
        outcome: ["GREEN", "AMBER", "RED"][index]
      }))
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [completed] }));
    render(<App />);
    for (const outcome of ["GREEN", "AMBER", "RED"]) {
      const selected = (await screen.findAllByText(outcome)).find((element) =>
        element.className.includes("selected-outcome")
      );
      expect(selected).toHaveTextContent(outcome);
      expect(selected).toHaveClass("completed-outcome");
      expect(selected).toHaveAttribute("aria-disabled", "true");
      expect(selected).toHaveAccessibleName(`${outcome}, selected, completed`);
      const unselected = Array.from(selected?.parentElement?.children ?? []).filter(
        (element) => !element.className.includes("selected-outcome")
      );
      expect(unselected).toHaveLength(2);
      unselected.forEach((element) => {
        expect(element).toHaveTextContent(/GREEN|AMBER|RED/);
        expect(element).toHaveAttribute("aria-disabled", "true");
      });
    }
  });

  it("shows hold details read-only, then exposes Process Owner controls and updates remediation", async () => {
    const hold = {
      id: "hold-101",
      cycleNumber: 1,
      policyVersion: "key-handover-hold-policy-v1-local",
      status: "Active",
      owner: "synthetic-process-owner",
      reason: "Legal clearance returned RED",
      affectedBranches: ["LEGAL"],
      startedAt: "2026-06-20T10:00:00Z",
      reviewAt: "2026-06-24T10:00:00Z",
      expiresAt: "2026-06-30T10:00:00Z",
      extensionCount: 0,
      remediations: []
    };
    const held = request({ status: "On hold", finalDecision: "On hold", hold, stateVersion: 8 });
    const remediated = request({
      ...held,
      stateVersion: 9,
      hold: {
        ...hold,
        status: "Resolution recorded",
        remediations: [
          {
            branch: "LEGAL",
            summary: "Resolved",
            supportingReference: "ref-1",
            recordedBy: "synthetic-process-owner",
            recordedAt: "2026-06-20T11:00:00Z"
          }
        ]
      },
      audit: [
        {
          id: "hold-resolution",
          actor: "owner",
          eventType: "HoldResolutionRecorded",
          timestamp: "2026-06-20T11:00:00Z",
          correlationId: "corr",
          causationId: "cause"
        }
      ]
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [held] })
      .mockResolvedValueOnce({ ok: true, json: async () => remediated });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);
    expect(await screen.findByText("Hold cycle 1")).toBeVisible();
    expect(screen.getByText("Hold information is read-only for this role.")).toBeVisible();
    expect(screen.queryByRole("button", { name: "Resume workflow" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Testing as"), { target: { value: "processOwner" } });
    fireEvent.change(screen.getByLabelText("Affected branch"), { target: { value: "LEGAL" } });
    fireEvent.change(screen.getByLabelText("Remediation summary"), {
      target: { value: "Resolved" }
    });
    fireEvent.change(screen.getByLabelText("Supporting reference"), { target: { value: "ref-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Record resolution" }));
    await waitFor(() => expect(screen.getByText("Resolved: Resolved")).toBeVisible());
    expect(screen.getByRole("button", { name: "Resume workflow" })).toBeEnabled();
    expect(fetchMock.mock.calls[1]?.[0]).toContain("/KH-101/hold/remediation");
  });

  it("keeps Activity History expanded by default and toggles it accessibly without losing events", async () => {
    const withAudit = request({
      audit: [
        {
          id: "audit-1",
          actor: "synthetic-owner",
          eventType: "NotificationDelivered",
          timestamp: "2026-06-20T10:00:00Z",
          correlationId: "corr-1",
          causationId: "cause-1"
        }
      ]
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [withAudit] }));
    render(<App />);
    expect(await screen.findByText("NotificationDelivered")).toBeVisible();
    expect(screen.getByText("1 events")).toBeVisible();
    const toggle = screen.getByRole("button", { name: "Collapse history" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(toggle, { key: "Enter" });
    fireEvent.click(toggle);
    expect(screen.queryByText("NotificationDelivered")).not.toBeInTheDocument();
    expect(screen.getByText("1 events")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Expand history" }));
    expect(await screen.findByText("NotificationDelivered")).toBeVisible();
  });

  it("shows the local notification simulation only to the Process Owner", async () => {
    const authorized = request({
      status: "Authorized",
      notification: "Delivered",
      notificationAttempts: 1,
      authorizationId: "release-khr-KH-101"
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [authorized] }));
    render(<App />);
    await screen.findByText("Notification recovery");
    expect(
      screen.queryByRole("button", { name: "Fail next notification (local test)" })
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Testing as"), { target: { value: "processOwner" } });
    expect(
      screen.getByRole("button", { name: "Fail next notification (local test)" })
    ).toBeVisible();
  });
});

describe("Inspection workspace", () => {
  const inspectionList = [
    {
      id: "inspection-test",
      status: "REQUESTED",
      parentRequestId: "khr-test",
      propertyReference: "Test Property"
    }
  ];

  const inspectionDetail = (overrides: Record<string, unknown> = {}) => ({
    id: "inspection-test",
    businessKey: "Test Property|synthetic|khr-test",
    parentRequestId: "khr-test",
    propertyReference: "Test Property",
    inspectionType: "synthetic",
    status: "REQUESTED",
    version: 1,
    attempts: [],
    remediationCycles: [],
    tasks: [
      {
        id: "inspection-test-inspection-1",
        type: "INSPECTION",
        status: "OPEN",
        requiredRole: "INSPECTION_OFFICER",
        createdAt: "2026-06-20T10:00:00Z",
        version: 1
      }
    ],
    correlationId: "corr-test",
    causationId: "cause-test",
    updatedAt: "2026-06-20T10:00:00Z",
    ...overrides
  });

  const remediationDetail = inspectionDetail({
    status: "WAITING_FOR_REMEDIATION",
    version: 3,
    attempts: [
      {
        number: 1,
        result: "FAILED",
        findings: "Issues found",
        evidenceReference: "evidence-failed",
        completedAt: "2026-06-20T10:05:00Z"
      }
    ],
    remediationCycles: [{ number: 1, status: "REQUIRED" }],
    tasks: [
      {
        id: "inspection-test-inspection-1",
        type: "INSPECTION",
        status: "COMPLETED",
        requiredRole: "INSPECTION_OFFICER",
        createdAt: "2026-06-20T10:00:00Z",
        completedAt: "2026-06-20T10:05:00Z",
        outcome: "FAILED",
        version: 2
      },
      {
        id: "inspection-test-remediation-1",
        type: "REMEDIATION",
        status: "OPEN",
        requiredRole: "REMEDIATION_OFFICER",
        createdAt: "2026-06-20T10:06:00Z",
        version: 1
      }
    ]
  });

  const mockFetch = (detail: unknown, history: unknown[] = []) => {
    return vi.fn().mockImplementation((url: string) => {
      if (url === "/api/key-handovers") {
        return Promise.resolve({ ok: true, json: async () => [] });
      }
      if (url === "/api/inspections") {
        return Promise.resolve({ ok: true, json: async () => ({ inspections: inspectionList }) });
      }
      if (url.includes("/inspections/") && url.includes("/history")) {
        return Promise.resolve({ ok: true, json: async () => history });
      }
      if (url.includes("/api/inspections/")) {
        return Promise.resolve({ ok: true, json: async () => detail });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    });
  };

  it("hides inspection controls from Process Owner", async () => {
    vi.stubGlobal("fetch", mockFetch(inspectionDetail()));
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Inspections" }));
    fireEvent.click(await screen.findByText("inspection-test"));
    await screen.findByText("Tasks");
    expect(screen.queryByRole("button", { name: "Claim inspection" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Complete PASSED" })).not.toBeInTheDocument();
  });

  it("shows inspection controls to Inspection Officer", async () => {
    vi.stubGlobal("fetch", mockFetch(inspectionDetail()));
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Inspections" }));
    fireEvent.click(await screen.findByText("inspection-test"));
    await screen.findByText("Tasks");
    fireEvent.change(screen.getByLabelText("Testing as"), {
      target: { value: "inspectionOfficer" }
    });
    expect(await screen.findByRole("button", { name: "Claim inspection" })).toBeVisible();
  });

  it("shows remediation controls only to Remediation Officer", async () => {
    vi.stubGlobal("fetch", mockFetch(remediationDetail));
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Inspections" }));
    fireEvent.click(await screen.findByText("inspection-test"));
    await screen.findByText("Remediation cycles");
    expect(screen.queryByRole("button", { name: "Claim remediation" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Testing as"), {
      target: { value: "remediationOfficer" }
    });
    expect(await screen.findByRole("button", { name: "Claim remediation" })).toBeVisible();
  });

  it("renders the full inspection audit history", async () => {
    const history = [
      {
        eventType: "InspectionRequested",
        actor: "system",
        timestamp: "2026-06-20T10:00:00Z",
        detail: "Process created"
      },
      {
        eventType: "InspectionTaskCreated",
        actor: "system",
        timestamp: "2026-06-20T10:00:00Z",
        detail: "inspection-test-inspection-1"
      },
      {
        eventType: "InspectionTaskClaimed",
        actor: "inspectionOfficer",
        timestamp: "2026-06-20T10:01:00Z",
        detail: "inspection-test-inspection-1"
      },
      {
        eventType: "InspectionPassed",
        actor: "system",
        timestamp: "2026-06-20T10:02:00Z",
        detail: "All clear"
      },
      {
        eventType: "ParentWorkflowResumeRequested",
        actor: "system",
        timestamp: "2026-06-20T10:02:00Z",
        detail: "Attempt 1"
      }
    ];
    vi.stubGlobal("fetch", mockFetch(inspectionDetail({ status: "COMPLETED" }), history));
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Inspections" }));
    fireEvent.click(await screen.findByText("inspection-test"));
    await screen.findByText("Activity history");
    for (const event of history) {
      expect(await screen.findByText(event.eventType)).toBeVisible();
    }
  });
});
