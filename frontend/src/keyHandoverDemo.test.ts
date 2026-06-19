import { expect, it, vi } from "vitest";
import { keyHandoverApi } from "./keyHandoverDemo";

it("uses the local API for request creation and task actions", async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue({ ok: true, json: async () => ({ requestNumber: "KH-201" }) });
  vi.stubGlobal("fetch", fetchMock);
  await keyHandoverApi.create("Demo Property 201", "Demo Owner 201");
  await keyHandoverApi.action("KH-201", "tasks/handover/complete", { outcome: "GREEN" });
  expect(fetchMock.mock.calls[0]?.[0]).toBe("http://localhost:8080/api/key-handovers");
  expect(fetchMock.mock.calls[1]?.[0]).toContain("KH-201/tasks/handover/complete");
});
