# Security and Privacy Constraints

## Identity

- Federate with enterprise identity using OIDC or SAML.
- Require MFA and conditional access for privileged roles.
- Use separate service identities per connector and service.
- Do not use shared broad service accounts.

## Authorization

- Combine RBAC with scoped attribute rules.
- Recheck authorization at assignment and completion time.
- Enforce segregation of duties.
- Team-head emergency powers must be scoped, time-bound, and audited.
- Delegation must never increase authority.

## Data protection

- Classify data as Public, Internal, Confidential, Restricted, or Highly Restricted.
- Encrypt data in transit and at rest.
- Support field-level protection for identity, legal, and financial data.
- Store evidence outside the workflow state store and keep references.
- Apply retention, legal hold, and deletion policies.
- Use synthetic or masked data outside production.

## AI

- Route all model calls through an AI Gateway.
- Redact or tokenize sensitive data before model calls when possible.
- Maintain model allowlists by data classification.
- Do not use organizational prompts or outputs for model training.
- Record model route, prompt version, tool calls, evidence references, and output validation.
- Never let a model directly execute unrestricted database or infrastructure commands.
- Treat retrieved content and model output as untrusted.

## Logging

Never log:

- passwords or access tokens
- full identity documents
- complete legal evidence
- full financial records
- raw prompts containing restricted data
- complete Oracle payloads

## Audit

Produce immutable events for:

- process publication
- rule publication
- permission changes
- assignment and reassignment
- delegation
- emergency override
- evidence access
- connector calls
- AI calls
- recommendations
- automated decisions
- human overrides
