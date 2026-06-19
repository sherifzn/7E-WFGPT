import "@testing-library/jest-dom/vitest";
import { render, screen } from "@testing-library/react";
import { App } from "./App";

describe("App", () => {
  it("renders the bootstrap shell with synthetic-data guardrails", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: "Enterprise Workflow Platform" })).toBeVisible();
    expect(screen.getByText("Synthetic only")).toBeVisible();
    expect(screen.getByText("Mocked only")).toBeVisible();
  });
});
