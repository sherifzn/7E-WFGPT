# Data Classification

| Class | Examples | AI Use | Development Use |
|---|---|---|---|
| Public | Published service information | Approved models | Allowed |
| Internal | Workflow metadata, generic templates | Approved models | Allowed |
| Confidential | Employee assignment, SLA data | Private endpoint | Synthetic or masked |
| Restricted | Legal, finance, identity evidence | Dedicated/private model or no AI | Prohibited |
| Highly Restricted | Legally prohibited cloud data | On-premises model or no AI | Prohibited |

## Rules

- Classification must travel with documents, events, knowledge chunks, and connector responses.
- Retrieval must filter by user authorization and classification.
- A higher-classification object must not be copied into a lower-classification store.
- Vector indexes must preserve security metadata.
- Completed workflows do not automatically become approved knowledge.
