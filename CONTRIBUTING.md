# Contributing

`cloud-itonami-isic-4773` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real customer, employee, supplier or quality-dispute-
  incident data.
- Keep sales-record logging, staffing-operation scheduling, supply-order
  coordination and quality-concern flagging behind the
  SpecialtyRetailGovernor.
- Treat retail-store-operations workflows as high-risk: add tests for
  store/vendor verification, effect discipline, scope exclusion,
  escalation and audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "refund", "dispute") -- phrase it as the finalization/execution ACTION
  (e.g. "finalize the refund decision"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip this
  actor's own legitimate `:flag-quality-concern` happy path -- see
  `specialtyretailops.governor/scope-excluded-terms`'s docstring.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
