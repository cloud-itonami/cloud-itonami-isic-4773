# cloud-itonami-isic-4773

Open Business Blueprint for **ISIC Rev.5 4773**: other retail sale of new
goods in specialized stores -- a catch-all class for single-category
specialty shops not covered by other 477x classes (e.g. books/stationery,
sporting goods, toys and games, hardware/paint/glass, furniture/lighting/
household articles, electrical appliances, jewelry/watches, photographic/
optical equipment, flowers/plants/pets, and similar specialty retail).

This repository publishes a specialized-retail
operations-COORDINATION actor -- sales/inventory/return transaction
logging, floor-staff scheduling, specialty-merchandise supply-order
coordination with registered vendors, and quality-concern flagging -- as
an OSS business that any qualified operator can fork, deploy, run,
improve and sell, so an independent specialty store never surrenders its
operations data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **SpecialtyRetailAdvisor
⊣ SpecialtyRetailGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:specialty-retail-governor`, is a
distinct, independent build (no naming-collision precedent question --
distinct from ISIC 4719's own `:merchandise-retail-governor` and ISIC
4721's own food-retail governor).

> **Why an actor layer at all?** An LLM is great at drafting a sales-
> record summary, a staffing proposal, or a supply-order request -- but
> it has no license to actually finalize a quality-dispute resolution
> against a customer (a refund decision, a replacement authorization, a
> liability determination), no way to independently confirm a store or a
> supply-order vendor is actually a registered/verified counterparty, and
> no notion of when a "flag this concern" op quietly turns into a claim
> to have already resolved it. Letting it act directly invites an
> unverified store's data entering the ledger, an unverified vendor
> receiving a merchandise order, or a fabricated claim to have finalized
> a refund or replacement without any human review, exposing the shop and
> its staff to real liability. This project seals the
> SpecialtyRetailAdvisor into a single node and wraps it with an
> independent **SpecialtyRetailGovernor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Scope: coordination only, not dispute resolution

This actor is **operations coordination only**. It never performs or
authorizes:

- setting or overriding a shelf/unit price
- directly finalizing a quality-dispute resolution (issuing a refund
  decision, authorizing a replacement, accepting liability for a defect,
  or otherwise closing a customer's complaint unilaterally)
- any other direct customer-dispute-resolution authority

The governor's `scope-exclusion-violations` check re-scans every proposal
for this failure mode independently of the advisor's own framing, and
treats it as a HARD, permanent block regardless of confidence or how
clean everything else is. Flagging a quality concern for a human to
triage is exactly this actor's job -- `:flag-quality-concern` is never
excluded by this check, only FINALIZING/resolving/acting-on that concern
unilaterally is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`specialtyretailops.governor`'s `effect-not-propose-violations` HARD
check and `specialtyretailops.phase`'s phase table, which never puts
`:flag-quality-concern` in any phase's `:auto` set). A human store
operator/quality coordinator is always the one who actually acts on a
flagged concern or confirms a high-cost supply order.

## The core contract

```
store/vendor registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ SpecialtyRetail-      │ ─────────────▶ │ SpecialtyRetailGovernor      │  (independent system)
   │ Advisor (sealed)      │  + citations    │ store-unverified ·          │
   └───────────────────────┘                 │ vendor-unverified ·         │
          │                 commit ◀┼ effect-not-propose ·               │
          │                         │ scope-excluded (quality-dispute-    │
    record + ledger        escalate ┼ resolution finalization) ·          │
          │              (ALWAYS for│ op-not-allowed                      │
          │       :flag-quality-    │                                      │
          │       concern/high-cost │                                      │
          │       supply-order)     └────────────────────────────┘
          ▼
      human approval
```

**The SpecialtyRetailAdvisor never commits a proposal the
SpecialtyRetailGovernor would reject, and a quality-concern flag or a
high-cost supply order never commits without a human sign-off.** Hard
violations (an unregistered/unverified store; an unregistered/unverified
supply-order vendor; a non-`:propose` effect; content touching
quality-dispute-resolution finalization; an op outside the closed
allowlist) force **hold** and *cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: shelfing, picking, restocking,
point-of-sale handling) under human/robot floor operations gated by store
policy. This actor itself does not dispatch robot/hardware actions -- it
is strictly the operations-coordination layer (sales-record logging,
staffing scheduling, supply-order coordination, quality-concern flagging)
any physical-dispatch layer could eventually feed proposals into, always
gated the same way by the independent SpecialtyRetailGovernor.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`,
  `schedule-staffing-operation`, `coordinate-supply-order`,
  `flag-quality-concern` (all `:effect :propose`).
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** -- the target store's business registration
     must exist AND be independently registered/verified in the store.
  2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
     named vendor must exist AND be independently registered/verified --
     a supply-chain counterparty-verification gate.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a quality-dispute
     resolution (a refund decision, a replacement authorization, a
     liability determination) and an op outside the closed allowlist are
     both permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-quality-concern` -- ALWAYS escalates, regardless of confidence
    or phase. A "flag a concern" op is never auto-commit eligible and
    never finalizes a quality-dispute-resolution decision itself -- it
    only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (quality concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run
```

### Test suite

- `test/specialtyretailops/governor_test.cljk` -- unit tests of governor
  hard checks, scope exclusion, and the self-trip regression test
- `test/specialtyretailops/advisor_test.cljk` -- advisor proposal shape
  and consistency
- `test/specialtyretailops/phase_test.cljk` -- rollout phase logic
- `test/specialtyretailops/governor_contract_test.cljk` -- full graph
  integration, audit trail
- `test/specialtyretailops/store_contract_test.cljk` -- Store protocol and
  MemStore implementation

### Modules

- `specialtyretailops.store` -- SSoT (MemStore, String-keyed store/vendor
  directories, append-only ledger)
- `specialtyretailops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `specialtyretailops.governor` -- independent compliance layer
- `specialtyretailops.phase` -- staged rollout (0→3)
- `specialtyretailops.operation` -- langgraph-clj StateGraph
- `specialtyretailops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4773`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Sales/inventory/return transaction logging (`:log-sales-record`) | Real POS/inventory-system integration |
| Floor-staff scheduling coordination (`:schedule-staffing-operation`) | Direct staff time-clock/payroll integration |
| Specialty-merchandise supply-order coordination with a registered, verified vendor, HARD-gated on vendor verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Quality-concern flagging, ALWAYS human-gated (`:flag-quality-concern`) | Directly finalizing any quality-dispute resolution (refund/replacement/liability) -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Daily reconciliation/cash-up -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a
return-authorization-intake or a warranty-claim-intake check) as its own
governed op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's flagship checks already
establish.

## Maturity

`:implemented` -- `SpecialtyRetailAdvisor` + `SpecialtyRetailGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own
supply-chain vendor-verification check.

## License

Code and implementation templates are AGPL-3.0-or-later.
