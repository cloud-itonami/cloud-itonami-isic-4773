(ns specialtyretailops.governor
  "SpecialtyRetailGovernor -- the independent compliance layer that earns
  the SpecialtyRetailAdvisor the right to commit. The advisor has no
  notion of whether a store is actually registered and license-verified,
  whether a named supply-order vendor is itself a registered/verified
  counterparty, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently drifted
  into a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (sales/inventory/return transaction logging, floor-staff scheduling,
  specialty-merchandise supply-order coordination, quality-concern
  flagging). It NEVER performs or authorizes:
    - setting or overriding a shelf/unit price
    - directly finalizing a quality-dispute resolution (issuing a refund
      decision, authorizing a replacement, accepting liability for a
      defect, or otherwise closing a customer's complaint unilaterally)
    - any other direct customer-dispute-resolution authority

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Store unverified           -- the target store record must exist
                                     AND be independently confirmed
                                     `:registered?`/`:verified?` in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     store -- re-derived from the store's
                                     own record, the same 'ground truth,
                                     not self-report' discipline every
                                     sibling actor's governor uses.
    2. Vendor unverified          -- for `:coordinate-supply-order` ONLY,
                                     the proposal's own drafted `:value`
                                     must name a `:vendor-id` that
                                     resolves to an independently
                                     `:registered?`/`:verified?` vendor
                                     record. A missing vendor-id, or one
                                     that resolves to an unregistered or
                                     unverified vendor, is a HARD block --
                                     a supply-chain counterparty-
                                     verification gate this actor shares
                                     with its sibling 47xx procurement
                                     checks.
    3. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    4. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, summary, rationale, cites
                                     or draft value touches directly
                                     finalizing a quality-dispute
                                     resolution (a refund decision, a
                                     replacement authorization, a
                                     liability determination, or otherwise
                                     closing a customer's complaint
                                     unilaterally) is a HARD, PERMANENT
                                     block -- this actor's charter
                                     excludes that territory structurally,
                                     not as a rollout milestone. Evaluated
                                     UNCONDITIONALLY on every proposal. An
                                     op outside the closed four-op
                                     allowlist is the SAME failure mode
                                     (an advisor proposing something it
                                     was never authorized to propose) and
                                     is folded into this same check.
                                     `:flag-quality-concern` itself is
                                     never excluded by this check --
                                     surfacing a defective-goods/
                                     mis-shipment/product-safety concern
                                     for a human is exactly this actor's
                                     job; only FINALIZING/resolving/
                                     acting-on that concern unilaterally
                                     is excluded (see
                                     `scope-excluded-terms` below --
                                     phrased as the finalization/execution
                                     ACTION, never a bare noun like
                                     'refund' or 'dispute', so the default
                                     mock advisor's own
                                     `:flag-quality-concern` rationale
                                     never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-quality-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `specialtyretailops.phase` independently agrees:
      `:flag-quality-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      merchandise procurement proposal always needs a human sign-off,
      even when the governor and phase would otherwise allow
      auto-commit."
  (:require [kotoba.lang.text :as str]
            [specialtyretailops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-store specialty-merchandise procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing an
  `:estimated-cost` above this value ALWAYS escalates to human sign-off,
  regardless of confidence or rollout phase."
  1500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`)."
  #{:log-sales-record :schedule-staffing-operation
    :coordinate-supply-order :flag-quality-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-quality-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  quality-dispute resolution (a refund decision, a replacement
  authorization, a liability determination) or otherwise closing a
  customer's complaint unilaterally rather than merely flagging it for a
  human. Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'finalize the refund decision', 'close the dispute
  unilaterally'), never a bare noun like 'refund', 'dispute', 'defect' or
  'complaint' -- a bare noun would accidentally match inside this actor's
  own legitimate `:flag-quality-concern` default proposal text (whose
  whole job is to talk about defective-goods/mis-shipment/quality
  concerns, and whose own printed `:op` keyword literally contains the
  substring 'quality') and self-block the happy path. See
  `specialtyretailops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the refund decision" "finalized the refund decision" "finalizing the refund decision"
   "process the refund without review" "processed the refund without review" "processing the refund without review"
   "issue a final resolution to the dispute" "issued a final resolution to the dispute" "issuing a final resolution to the dispute"
   "authorize the replacement unilaterally" "authorized the replacement unilaterally" "authorizing the replacement unilaterally"
   "close the dispute unilaterally" "closed the dispute unilaterally" "closing the dispute unilaterally"
   "determine fault and refund the customer" "determined fault and refunded the customer" "determining fault and refunding the customer"
   "accept liability for the defect" "accepted liability for the defect" "accepting liability for the defect"
   "settle the quality dispute directly" "settled the quality dispute directly" "settling the quality dispute directly"
   "grant the customer a final refund" "granted the customer a final refund" "granting the customer a final refund"
   "reject the customer's claim outright" "rejected the customer's claim outright" "rejecting the customer's claim outright"
   "finalize the quality-dispute resolution" "finalized the quality-dispute resolution" "finalizing the quality-dispute resolution"
   "execute the replacement without approval" "executed the replacement without approval" "executing the replacement without approval"
   "返金を確定した" "返金を確定させた" "返金を独断で実行した"
   "交換を独断で承認した" "交換対応を独断で確定した"
   "クレームを一方的に解決した" "苦情を一方的に解決した"
   "品質紛争を独断で解決した" "品質紛争の最終判断を下した"
   "苦情対応を最終決定した" "返品対応を独断で確定した"
   "損害賠償を確定した" "賠償責任を認めて確定した"])

;; ----------------------------- checks -----------------------------

(defn- store-unverified-violations
  "The target store must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:store-id` claim without a store lookup."
  [{:keys [store-id]} st]
  (let [s (store/store-record st store-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :store-unverified
        :detail (str store-id " は未登録または未検証の店舗 -- いかなる提案も進められない")}])))

(defn- vendor-unverified-violations
  "For `:coordinate-supply-order` ONLY, the proposal's own drafted
  `:value` must name a `:vendor-id` that resolves to an independently
  `:registered?`/`:verified?` vendor record. A missing vendor-id, or one
  that resolves to an unregistered/unverified vendor, is a HARD block --
  never trust the proposal's own vendor claim without a store lookup, the
  SAME 'ground truth, not self-report' discipline as
  `store-unverified-violations`, reapplied to the supply-chain
  counterparty."
  [proposal st]
  (when (= :coordinate-supply-order (:op proposal))
    (let [vendor-id (get-in proposal [:value :vendor-id])
          v (and vendor-id (store/vendor-record st vendor-id))]
      (when-not (and v (:registered? v) (:verified? v))
        [{:rule :vendor-unverified
          :detail (str (or vendor-id "(vendor-id missing)")
                        " は未登録または未検証の仕入先 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a quality-dispute
  resolution (a refund decision, a replacement authorization, a liability
  determination), regardless of confidence or how clean every other check
  is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "返金確定・交換独断承認・賠償責任確定など品質紛争確定行為(quality-dispute-resolution finalization)に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  `supply-cost-threshold` -- always needs human sign-off (SOFT escalate,
  not a hard block: the order itself is in scope, only its size requires
  a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a SpecialtyRetailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [store-id (or (:store-id proposal) (:store-id request))
        hard (into []
                   (concat (store-unverified-violations {:store-id store-id} store)
                           (vendor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :store-id   (:store-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
