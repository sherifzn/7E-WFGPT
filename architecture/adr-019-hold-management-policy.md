# ADR-019: Key Handover Hold Management Policy

## Status

Accepted

## Context

The Key Handover slice can enter `ON_HOLD` when a clearance branch returns `RED`. Hold policy is
versioned and supplied through configuration so operational thresholds and role routing remain out
of application-service branches.

## Decision Required

The accepted synthetic local-development policy is `key-handover-hold-policy-v1-local`. Production
deployments must provide the same contract through the approved BRE/DMN configuration.

The policy defines:

1. eligible hold owners and managers;
2. review and expiry durations, extension limits, and escalation routing;
3. which roles may view branch-specific remediation;
4. permitted transitions for resolution, extension, resume, rejection, cancellation, and expiry;
5. the rule for reopening only affected RED branches; and
6. whether an expiry is terminal or can be explicitly remediated by a subsequent governed action.

## Accepted Local Slice Contract

Process Owner owns and exclusively manages holds. Team Heads have read-only hold visibility and
branch officers may view remediation for their own branch. The policy uses a two-business-day
review period, ten-business-day initial maximum, two extensions, and a five-business-day maximum
per extension. Review due requires escalation; expiry blocks resume but can be extended, rejected,
or cancelled.

Resolution is required for every RED branch. Resume reopens only RED branches and preserves GREEN
and AMBER outcomes. All GREEN authorizes, AMBER routes to exception approval, and a subsequent RED
creates a new hold cycle. Rejection uses `HOLD_REJECTED`; cancellation uses `CANCELLED`; neither
creates authorization or a success notification.

## Consequences

Implementation of durable Hold Management and Controlled Resume is deferred until this policy
contract is approved. The existing `ON_HOLD` behavior remains unchanged.
