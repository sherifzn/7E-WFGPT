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
  lastUpdated: "2026-06-20T10:00:00Z",
  tasks: [
    { id: "handover", title: "Handover check", status: "Blocked", assignedTo: "", outcome: "" },
    { id: "finance", title: "Finance clearance", status: "Blocked", assignedTo: "", outcome: "" },
    { id: "legal", title: "Legal clearance", status: "Blocked", assignedTo: "", outcome: "" }
  ],
  audit: [],
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
    fireEvent.click(screen.getByRole("button", { name: "Resume after inspection" }));
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
});
