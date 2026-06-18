# System Context

## Actors

- Front Desk Agent
- Workflow Designer
- Workflow Publisher
- Process Owner
- Team Member
- Team Head
- Auditor
- Security Administrator
- Knowledge Curator
- AI Governance Reviewer
- Property Owner

## External systems

- Oracle financial system
- Enterprise identity provider / Active Directory federation
- Document management system
- Email and notification service
- Legal case system
- Property and inspection systems
- Approved AI model endpoints

## Primary boundaries

### Control Plane

Owns:

- workflow templates and definitions
- process registry and versioning
- form definitions
- rules and decisions
- reusable subprocess catalog
- publishing and validation
- prerequisite dependency graph
- connector catalog
- AI configurations
- autonomy policies

### Runtime Plane

Owns:

- workflow instances
- durable state transitions
- human tasks
- timers
- parallel execution
- retries and compensation
- child workflow correlation
- SLA events
- task assignment
- audit emission

### Knowledge Plane

Owns:

- completed-case extraction
- approved decisions
- policy and procedure indexing
- evidence references
- similarity search
- knowledge quality and expiry
- decision-support outputs

### Integration Plane

Owns:

- Oracle connector
- identity connector
- email connector
- document connector
- legal and property connectors
- idempotency
- protocol translation
- external error normalization
