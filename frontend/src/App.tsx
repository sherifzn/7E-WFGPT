import "./styles.css";

const productAreas = [
  "AI Process Studio",
  "Workflow Control Plane",
  "Workflow Runtime",
  "Work Management Layer",
  "Connector Gateway",
  "Governance and Monitoring Center"
];

export function App() {
  return (
    <main className="shell" aria-labelledby="page-title">
      <section className="status-panel">
        <p className="eyebrow">Local bootstrap</p>
        <h1 id="page-title">Enterprise Workflow Platform</h1>
        <p className="summary">
          Repository foundation for secure workflow delivery. This shell uses synthetic data and
          mocked integrations only.
        </p>
        <dl className="health-grid" aria-label="Bootstrap status">
          <div>
            <dt>Backend</dt>
            <dd>Health endpoint scaffolded</dd>
          </div>
          <div>
            <dt>Integrations</dt>
            <dd>Mocked only</dd>
          </div>
          <div>
            <dt>Data</dt>
            <dd>Synthetic only</dd>
          </div>
        </dl>
      </section>

      <section className="module-list" aria-label="Product areas">
        {productAreas.map((area) => (
          <article key={area} className="module-card">
            <h2>{area}</h2>
            <p>Foundation placeholder</p>
          </article>
        ))}
      </section>
    </main>
  );
}
