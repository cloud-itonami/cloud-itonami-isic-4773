(ns specialtyretailops.advisor
  "SpecialtyRetailAdvisor -- the *contained intelligence node* for the
  ISIC-4773 'Other retail sale of new goods in specialized stores'
  (a catch-all class for single-category specialty shops not covered by
  other 477x classes) operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: sales/inventory/return transaction logging, floor-staff
  scheduling, merchandise supply-order coordination, and quality-concern
  flagging. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a committed
  record and NEVER a direct actuation -- every proposal's `:effect` is
  always `:propose`. Every output is censored downstream by
  `specialtyretailops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct quality-dispute-resolution finalization
  (a refund decision, a replacement authorization, accepting liability for
  a defect, or otherwise closing a customer's quality complaint
  unilaterally) -- that is permanently out of scope for this actor, not
  merely un-implemented. `specialtyretailops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :store-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-sales-record
  "Draft a sales/inventory/return transaction log entry. Pure logging of
  observed transactions (units sold, returns processed, stock-count
  deltas) -- never a quality-dispute-resolution decision."
  [_db {:keys [store-id patch]}]
  {:op         :log-sales-record
   :store-id   store-id
   :summary    (str store-id " の販売/在庫/返品記録を記録: " (pr-str (keys patch)))
   :rationale  "販売数量・在庫カウント・返品処理の観察記録のみ。値付けや品質紛争解決の判断は含まない。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a floor-staff scheduling proposal (a roster/calendar entry,
  never a direct enforcement action)."
  [_db {:keys [store-id patch]}]
  {:op         :schedule-staffing-operation
   :store-id   store-id
   :summary    (str store-id " のフロアスタッフ配置予定を提案: " (pr-str (keys patch)))
   :rationale  "フロア/レジ/陳列棚出しのシフト調整提案のみ。人員の最終配置は人間が確定する。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft a specialty-merchandise procurement coordination request naming a
  registered vendor -- never a finalized purchase order; a human always
  confirms procurement."
  [_db {:keys [store-id patch]}]
  {:op         :coordinate-supply-order
   :store-id   store-id
   :summary    (str store-id " 向け専門小売商品の発注調整を提案: " (pr-str (keys patch)))
   :rationale  "専門小売商品・什器等の仕入先発注調整提案のみ。確定発注は人間が行う。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.90})

(defn- propose-quality-concern
  "Surface an observed quality concern (defective goods, a mis-shipment,
  a product-safety issue) for HUMAN triage. This op ALWAYS escalates in
  `specialtyretailops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  Deliberately reports the OBSERVATION only, never a quality-dispute
  RESOLUTION (no refund decision, no replacement authorization, no
  liability determination), so the default rationale never trips the
  governor's `scope-excluded-terms` (see that var's docstring)."
  [_db {:keys [store-id patch]}]
  {:op         :flag-quality-concern
   :store-id   store-id
   :summary    (str store-id " の品質懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "不良品・誤配送・製品安全上の懸念の観察事実の報告のみ。常に人間の確認・対応が必要。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-sales-record (propose-sales-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-quality-concern (propose-quality-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the refund decision and closed the dispute unilaterally")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :store-id (:store-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
