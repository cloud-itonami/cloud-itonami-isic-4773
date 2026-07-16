# Governance

`cloud-itonami-isic-4773` is an OSS open-business blueprint for
specialized-retail operations coordination (ISIC Rev.5 4773 -- other
retail sale of new goods in specialized stores).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered store, or a supply order
  naming an unverified/unregistered vendor, can never commit.
- the SpecialtyRetailGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, quality-dispute-
  resolution-finalization content, an op outside the closed allowlist)
  cannot be overridden by human approval.
- every sales-record log, staffing-operation schedule, supply-order
  coordination and quality-concern flag is auditable.
- customer, employee and supplier data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review.

Certified operators can lose certification for:
- bypassing sale-record, staffing, supply-order or quality-concern
  policy checks
- mishandling customer, employee or supplier data
- misrepresenting certification status
- failing to respond to security or quality-dispute incidents
