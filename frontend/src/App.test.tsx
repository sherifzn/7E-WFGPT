import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { App } from "./App";

const select = (requestNumber: string) => fireEvent.click(screen.getByRole("listitem", { name: new RegExp(requestNumber) }));

describe("Key Handover local demo", () => {
  it("keeps clearance actions blocked while inspection is waiting", () => {
    render(<App />);
    expect(screen.getByText("Inspection barrier active")).toBeVisible();
    expect(screen.getAllByRole("button", { name: "Claim task" })[0]).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Resume after inspection" }));
    expect(screen.queryByText("Inspection barrier active")).not.toBeInTheDocument();
  });

  it("claims and reassigns a clearance task", () => {
    render(<App />);
    select("KH-102");
    fireEvent.click(screen.getAllByRole("button", { name: "Claim task" })[0]);
    expect(screen.getByText("Assigned to:", { exact: false })).toHaveTextContent("Current demo user");
    fireEvent.click(screen.getByRole("button", { name: "Reassign" }));
    expect(screen.getByText("Assigned to:", { exact: false })).toHaveTextContent("Demo reviewer");
  });

  it("shows GREEN, AMBER, and RED outcome choices", () => {
    render(<App />);
    select("KH-102");
    expect(screen.getAllByText("GREEN").length).toBeGreaterThan(0);
    expect(screen.getAllByText("AMBER").length).toBeGreaterThan(0);
    expect(screen.getAllByText("RED").length).toBeGreaterThan(0);
  });

  it("retries a failed notification", () => {
    render(<App />);
    select("KH-103");
    fireEvent.click(screen.getByRole("button", { name: "Simulate delivery issue" }));
    expect(screen.getByText("Notification needs retry")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Retry notification" }));
    expect(screen.getByText("Authorization notification delivered")).toBeVisible();
  });
});
