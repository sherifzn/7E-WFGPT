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
});
