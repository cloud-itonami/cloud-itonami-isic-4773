(ns specialtyretailops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave 8): this repo previously had no demo page and no generator at all.

  Nothing on the generated page is hand-written. Every store, vendor,
  proposal, verdict, disposition, hold reason, threshold and ledger row
  is produced by actually running THIS repo's actor stack --
  `specialtyretailops.operation` (the langgraph StateGraph) ->
  `specialtyretailops.governor` (the independent censor) ->
  `specialtyretailops.phase` (the rollout gate) ->
  `specialtyretailops.store` (the append-only SSoT). The rule/threshold
  tables are read out of the governor's and phase's own vars
  (`allowed-ops`, `always-escalate-ops`, `confidence-floor`,
  `supply-cost-threshold`, `scope-excluded-terms`, `phases`), never
  re-typed, so the page cannot drift away from the code it documents.

  `clojure -M:dev:run` (this repo's own `specialtyretailops.sim`) was run
  BEFORE this file was written to confirm the seeded ids are real:
  `store-1`/`store-2`/`store-3` and `vendor-1`/`vendor-2` all exist in
  `specialtyretailops.store/demo-data`, so the scenario below is built
  against ground truth rather than copied blind.

  The scenario deliberately reaches EVERY disposition this actor can
  produce, including all five of the governor's HARD rules and the
  rollout gate's own phase-disabled hold -- see `run-demo!`. `-main`
  refuses to write a console that does not exercise all of them (see
  `expected-hard-rules`), so a page showing no real hold, or a governor
  rule silently added without a demo, is a build failure rather than a
  quietly weaker sample.

  Deterministic: no timestamps, no random ids, every set and map
  iterated in a sorted order, so two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [specialtyretailops.advisor :as advisor]
            [specialtyretailops.governor :as governor]
            [specialtyretailops.operation :as op]
            [specialtyretailops.phase :as phase]
            [specialtyretailops.store :as store]))

;; ----------------------------- actors under test -----------------------------

(def ^:private coordinator-id "coord-1")
(def ^:private human-approver "quality-coordinator-1")
(def ^:private human-rejector "quality-coordinator-2")

(defn- ctx
  "The injected actor context for one run, at rollout phase `ph`."
  [ph]
  {:actor-id coordinator-id :actor-role :retail-store-coordinator :phase ph})

(defn- direct-actuation-advisor
  "A compromised advisor that claims a DIRECT actuation instead of a
  proposal. Exercises the governor's `:effect-not-propose` HARD rule --
  the same injection `specialtyretailops.sim` uses."
  []
  (reify advisor/Advisor
    (-advise [_ _ req] (assoc (advisor/infer nil req) :effect :commit))))

(defn- low-confidence-advisor
  "An advisor that is honestly unsure (0.35, below
  `governor/confidence-floor`). Exercises the SOFT `:low-confidence`
  escalation, which no always-escalate op could show on its own (the
  governor reports `:always-escalate` first for those)."
  []
  (reify advisor/Advisor
    (-advise [_ _ req] (assoc (advisor/infer nil req) :confidence 0.35))))

(defn- out-of-allowlist-advisor
  "An advisor that has drifted OUTSIDE the closed four-op allowlist and
  proposes a shelf-price override -- an authority this actor's charter
  never granted it. Every other field is impeccable (`:effect :propose`,
  confidence 0.95, a verified store), so the resulting hold isolates the
  governor's `:op-not-allowed` rule on its own."
  []
  (reify advisor/Advisor
    (-advise [_ _ {:keys [store-id patch]}]
      {:op         :set-shelf-price
       :store-id   store-id
       :summary    (str store-id " の棚札価格の改定を提案: " (pr-str (sort (keys patch))))
       :rationale  "近隣競合店の実勢価格に合わせた値付け調整。"
       :cites      [store-id]
       :effect     :propose
       :value      (merge {:store-id store-id} patch)
       :confidence 0.95})))

;; ----------------------------- driving one run -----------------------------

(defn- exec! [actor tid request ph]
  (:state (g/run* actor {:request request :context (ctx ph)} {:thread-id tid})))

(defn- resume! [actor tid {:keys [status by]}]
  (:state (g/run* actor {:approval {:status status :by by}}
                  {:thread-id tid :resume? true})))

(defn- audit-fact [state t]
  (some #(when (= t (:t %)) %) (:audit state)))

(defn- step
  "Drive ONE coordination request through the real graph and record what
  actually happened. If the actor pauses for human sign-off
  (`interrupt-before #{:request-approval}`) and the spec carries an
  `:approval`, resume with it. Returns `runs` with one entry appended --
  every field read from the graph's own returned state, never asserted
  by this file."
  [runs actor {:keys [tid label phase request approval]}]
  (let [gated    (exec! actor tid request phase)
        paused?  (= :escalate (:disposition gated))
        resumed  (when (and paused? approval) (resume! actor tid approval))
        final    (or resumed gated)
        hold     (audit-fact final :governor-hold)]
    (conj runs
          {:tid               tid
           :label             label
           :phase             phase
           :request           request
           :op                (:op request)
           :store-id          (:store-id request)
           :proposal          (:proposal gated)
           :verdict           (:verdict gated)
           :gate-disposition  (:disposition gated)
           :escalation-reason (:reason (audit-fact gated :approval-requested))
           :phase-reason      (:phase-reason hold)
           :hard-rules        (mapv :rule (:violations (:verdict gated)))
           :approval          (when resumed approval)
           :final-disposition (:disposition final)
           :record            (:record final)})))

;; ----------------------------- the scenario -----------------------------

(defn run-demo!
  "Runs a fresh `store/seed-db` through fifteen real coordination
  requests covering every path this actor can take:

    auto-commit (phase 3, governor-clean)        -- r01 r02 r03
    escalate -> human APPROVES -> commit         -- r04 (:phase-approval)
                                                    r05 (:always-escalate,
                                                         cost above the
                                                         supply threshold)
                                                    r06 (:always-escalate,
                                                         quality concern)
                                                    r08 (:low-confidence)
    escalate -> human REJECTS -> hold            -- r07
    rollout gate holds a not-yet-enabled write   -- r09 (:phase-disabled,
                                                    NOT a governor hold:
                                                    it lifts as the phase
                                                    advances)
    governor HARD hold, un-overridable           -- r10 r11 :store-unverified
                                                    r12 :vendor-unverified
                                                    r13 :effect-not-propose
                                                    r14 :scope-excluded
                                                    r15 :op-not-allowed

  Every subject id is one this repo actually seeds (`store-1`
  `store-2` verified, `store-3` registered-but-unverified, `vendor-1`
  verified, `vendor-2` unverified) plus `store-99`, which is deliberately
  absent so the missing-record branch is real rather than simulated.

  Returns `{:db <store after the run> :runs [<what each run did>]}`."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        direct  (op/build db {:advisor (direct-actuation-advisor)})
        unsure  (op/build db {:advisor (low-confidence-advisor)})
        outside (op/build db {:advisor (out-of-allowlist-advisor)})
        runs
        (-> []
            ;; --- clean, auto-committed at phase 3 -------------------------
            (step actor
                  {:tid "r01" :phase 3
                   :label "販売/在庫/返品記録 — governor clean、phase 3 の auto 対象"
                   :request {:op :log-sales-record :store-id "store-1"
                             :patch {:units-sold 42 :returns 3 :stock-count-delta -45}}})
            (step actor
                  {:tid "r02" :phase 3
                   :label "フロアスタッフ配置予定 — governor clean、phase 3 の auto 対象"
                   :request {:op :schedule-staffing-operation :store-id "store-1"
                             :patch {:shift "weekend-floor" :date "2026-07-20"
                                     :window "10:00-18:00"}}})
            (step actor
                  {:tid "r03" :phase 3
                   :label "低額発注調整 / vendor-1 検証済 — 閾値未満なので auto"
                   :request {:op :coordinate-supply-order :store-id "store-2"
                             :patch {:item "specialty hardware restock" :quantity 200
                                     :estimated-cost 480.0 :vendor-id "vendor-1"}}})

            ;; --- escalated, then resolved by a human ----------------------
            (step actor
                  {:tid "r04" :phase 1
                   :label "販売記録を phase 1 で — 書き込みは許可、auto は未開放"
                   :request {:op :log-sales-record :store-id "store-2"
                             :patch {:units-sold 18 :returns 0 :stock-count-delta -18}}
                   :approval {:status :approved :by human-approver}})
            (step actor
                  {:tid "r05" :phase 3
                   :label "高額発注調整 — 発注額が supply-cost-threshold を超過"
                   :request {:op :coordinate-supply-order :store-id "store-1"
                             :patch {:item "seasonal specialty display fixtures"
                                     :quantity 20 :estimated-cost 3600.0
                                     :vendor-id "vendor-1"}}
                   :approval {:status :approved :by human-approver}})
            (step actor
                  {:tid "r06" :phase 3
                   :label "品質懸念フラグ — どの phase でも必ず人間へ"
                   :request {:op :flag-quality-concern :store-id "store-1"
                             :patch {:concern (str "defective batch of hand tools received, "
                                                   "packaging shows mis-shipment from vendor-1")
                                     :confidence 0.92}}
                   :approval {:status :approved :by human-approver}})
            (step actor
                  {:tid "r07" :phase 3
                   :label "品質懸念フラグ — 人間が却下 (承認は自動ではない)"
                   :request {:op :flag-quality-concern :store-id "store-2"
                             :patch {:concern (str "customer reports a cracked display case unit; "
                                                   "awaiting vendor inspection")
                                     :confidence 0.71}}
                   :approval {:status :rejected :by human-rejector}})
            (step unsure
                  {:tid "r08" :phase 3
                   :label "販売記録だが advisor の確信度が floor 未満"
                   :request {:op :log-sales-record :store-id "store-1"
                             :patch {:units-sold 7 :returns 2 :stock-count-delta -9}}
                   :approval {:status :approved :by human-approver}})

            ;; --- rollout gate hold (recoverable, not a governor hold) -----
            (step actor
                  {:tid "r09" :phase 1
                   :label "発注調整を phase 1 で要求 — rollout がこの op を未開放"
                   :request {:op :coordinate-supply-order :store-id "store-1"
                             :patch {:item "gift-wrap consumables" :quantity 40
                                     :estimated-cost 120.0 :vendor-id "vendor-1"}}})

            ;; --- governor HARD holds (permanent, never reach a human) -----
            (step actor
                  {:tid "r10" :phase 3
                   :label "存在しない店舗 store-99 への提案"
                   :request {:op :log-sales-record :store-id "store-99"
                             :patch {:units-sold 0}}})
            (step actor
                  {:tid "r11" :phase 3
                   :label "登録済みだが未検証の店舗 store-3 への提案"
                   :request {:op :log-sales-record :store-id "store-3"
                             :patch {:units-sold 10}}})
            (step actor
                  {:tid "r12" :phase 3
                   :label "未検証の仕入先 vendor-2 を名指しした発注調整"
                   :request {:op :coordinate-supply-order :store-id "store-1"
                             :patch {:item "import specialty goods" :quantity 50
                                     :estimated-cost 300.0 :vendor-id "vendor-2"}}})
            (step direct
                  {:tid "r13" :phase 3
                   :label "advisor が :effect :commit で直接実行を主張"
                   :request {:op :schedule-staffing-operation :store-id "store-1"
                             :patch {:shift "weekday-floor" :date "2026-07-22"}}})
            (step actor
                  {:tid "r14" :phase 3
                   :label "advisor が品質紛争の確定行為へ drift"
                   :request {:op :log-sales-record :store-id "store-1"
                             :out-of-scope? true :patch {}}})
            (step outside
                  {:tid "r15" :phase 3
                   :label "allowlist 外の op :set-shelf-price を提案"
                   :request {:op :set-shelf-price :store-id "store-1"
                             :patch {:sku "SKU-7781" :new-price 2480.0}}}))]
    {:db db :runs runs}))

;; ----------------------------- build-time invariants -----------------------------

(def expected-hard-rules
  "Every HARD rule `specialtyretailops.governor` can emit. A console that
  does not exercise all of them is not evidence of a governor, so
  `-main` refuses to write one -- which also means a rule added to the
  governor without a demo run here fails the build instead of silently
  going undemonstrated."
  #{:store-unverified :vendor-unverified :effect-not-propose
    :scope-excluded :op-not-allowed})

(defn hard-holds
  "Ledger facts that are real governor HARD holds. A `:governor-hold`
  fact with an EMPTY `:basis` is the rollout gate's `:phase-disabled`
  hold, not a governor violation -- it lifts as the phase advances, so it
  must not be counted as evidence of an un-overridable block."
  [db]
  (filterv #(and (= :governor-hold (:t %)) (seq (:basis %))) (store/ledger db)))

(defn- phase-holds [db]
  (filterv #(and (= :governor-hold (:t %)) (empty? (:basis %))) (store/ledger db)))

(defn- committed-facts [db]
  (filterv #(= :committed (:t %)) (store/ledger db)))

(defn- rejections [db]
  (filterv #(= :approval-rejected (:t %)) (store/ledger db)))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kw-name [k] (if (keyword? k) (name k) (str k)))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- fmt-val [v]
  (cond (string? v) v
        (keyword? v) (name v)
        :else (pr-str v)))

(defn- fmt-map
  "A map rendered as sorted `key=value` pairs -- sorted so the page is
  byte-identical across runs regardless of map implementation."
  [m]
  (if (seq m)
    (str/join ", " (for [k (sort-by str (keys m))]
                     (str (kw-name k) "=" (fmt-val (get m k)))))
    "—"))

(defn- kws [coll]
  (if (seq coll)
    (str/join " " (map #(code (str ":" (kw-name %))) (sort-by kw-name coll)))
    "<span class=\"muted\">（なし）</span>"))

(defn- row [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- cells -----------------------------

(defn- disposition-cell [{:keys [final-disposition gate-disposition phase-reason hard-rules approval]}]
  (cond
    (seq hard-rules)
    (str "<span class=\"critical\">HARD hold</span> · " (kws hard-rules))

    (and (= :hold final-disposition) phase-reason)
    (str "<span class=\"warn\">hold</span> · " (code (str ":" (kw-name phase-reason))))

    (= :hold final-disposition)
    (str "<span class=\"warn\">hold</span> · " (code ":approver-rejected"))

    (and (= :commit final-disposition) (= :escalate gate-disposition))
    (str "<span class=\"ok\">committed</span> · 人間承認後 (" (esc (:by approval)) ")")

    (= :commit final-disposition)
    "<span class=\"ok\">committed</span> · auto"

    :else (str "<span class=\"muted\">" (esc (kw-name final-disposition)) "</span>")))

(defn- verdict-cell [{:keys [hard? escalate? high-stakes? confidence]}]
  (str "<span class=\"num\">" (esc confidence) "</span>"
       (when hard? " · <span class=\"critical\">hard?</span>")
       (when escalate? " · <span class=\"warn\">escalate?</span>")
       (when high-stakes? " · <span class=\"warn\">high-stakes?</span>")
       (when-not (or hard? escalate?) " · <span class=\"ok\">clean</span>")))

(defn- run-row [{:keys [tid label phase op store-id verdict escalation-reason] :as r}]
  (row (code tid)
       (str "<span class=\"num\">" phase "</span>")
       (code (str ":" (kw-name op)))
       (code store-id)
       (esc label)
       (verdict-cell verdict)
       (if escalation-reason (code (str ":" (kw-name escalation-reason)))
           "<span class=\"muted\">—</span>")
       (disposition-cell r)))

;; ----------------------------- sections -----------------------------

(defn- runs-section [runs]
  (section
   "この実行で走った 15 件のコーディネーション要求"
   (str "各行は <code>specialtyretailops.operation</code> のグラフを実際に 1 回走らせた結果。"
        "confidence・verdict フラグ・escalate 理由・最終 disposition は全て "
        "graph が返した state から読んでおり、この画面が主張した値ではない。"
        "phase 列は要求時に注入した rollout フェーズ。")
   (table ["run" "phase" "op" "store" "シナリオ" "governor verdict" "escalate 理由" "最終 disposition"]
          (map run-row runs))))

(defn- store-section [db runs]
  (let [ledger (store/ledger db)
        by-store (group-by :store-id ledger)]
    (section
     "店舗ディレクトリ（SSoT）"
     (str "<code>specialtyretailops.store/all-store-records</code> が返す登録簿そのもの。"
          "governor の <code>store-unverified-violations</code> は提案の自己申告ではなく "
          "この 2 列（<code>:registered?</code> / <code>:verified?</code>）を毎回引き直す。"
          "右 2 列は台帳に実際に残った結果であって、この画面の再判定ではない。")
     (table ["store-id" "名称" "registered?" "verified?" "台帳の記録数" "commit" "HARD hold"]
            (concat
             (for [{:keys [store-id name registered? verified?]} (store/all-store-records db)
                   :let [fs (get by-store store-id [])]]
               (row (code store-id) (esc name) (yes-no registered?) (yes-no verified?)
                    (str "<span class=\"num\">" (count fs) "</span>")
                    (str "<span class=\"num\">" (count (filter #(= :committed (:t %)) fs)) "</span>")
                    (let [n (count (filter #(and (= :governor-hold (:t %)) (seq (:basis %))) fs))]
                      (if (pos? n) (str "<span class=\"critical\">" n "</span>") "0"))))
             ;; store-99 is deliberately NOT in the directory -- shown so the
             ;; missing-record branch is visible as a real gap, not an omission.
             (let [ghost (->> runs (filter #(nil? (store/store-record db (:store-id %))))
                              (map :store-id) distinct sort)]
               (for [sid ghost]
                 (row (code sid)
                      "<span class=\"muted\">（登録簿に存在しない）</span>"
                      (yes-no false) (yes-no false)
                      (str "<span class=\"num\">" (count (get by-store sid [])) "</span>")
                      "0"
                      (let [n (count (filter #(and (= :governor-hold (:t %)) (seq (:basis %)))
                                             (get by-store sid [])))]
                        (str "<span class=\"critical\">" n "</span>"))))))))))

(defn- vendor-section [db runs]
  (let [log (store/coordination-log db)]
    (section
     "仕入先ディレクトリ（供給側カウンターパーティ検証）"
     (str "<code>coordinate-supply-order</code> だけに掛かる 2 本目の ground-truth ゲート。"
          "提案が名乗った <code>:vendor-id</code> をこの登録簿で引き直し、"
          "未登録・未検証なら HARD ブロックする。"
          "「発注 commit」列は <code>coordination-log</code> に実際に積まれた発注件数。")
     (table ["vendor-id" "名称" "registered?" "verified?" "発注 commit" "HARD ブロック"]
            (for [{:keys [vendor-id name registered? verified?]} (store/all-vendor-records db)]
              (row (code vendor-id) (esc name) (yes-no registered?) (yes-no verified?)
                   (str "<span class=\"num\">"
                        (count (filter #(= vendor-id (get-in % [:value :vendor-id])) log))
                        "</span>")
                   (let [n (count (filter #(and (some #{:vendor-unverified} (:hard-rules %))
                                                (= vendor-id (get-in % [:request :patch :vendor-id])))
                                          runs))]
                     (if (pos? n) (str "<span class=\"critical\">" n "</span>") "0"))))))))

(defn- op-gate-section [runs]
  (let [auto3 (get-in phase/phases [3 :auto])
        observed (group-by :op runs)]
    (section
     "操作ゲート（SpecialtyRetailGovernor × rollout phase 3）"
     (str "この表は手書きではなく <code>governor/allowed-ops</code>・"
          "<code>governor/always-escalate-ops</code>・<code>phase/phases</code> を"
          "そのまま読み出したもの。closed allowlist は "
          (count governor/allowed-ops) " op、確信度フロアは <span class=\"num\">"
          governor/confidence-floor "</span>、発注の人手確認しきい値は <span class=\"num\">"
          governor/supply-cost-threshold "</span>、"
          "品質紛争の確定行為を検出する除外語は " (count governor/scope-excluded-terms)
          " 語（日英）。")
     (table ["op" "phase 3 で auto?" "常に人間へ?" "phase 3 の posture" "この実行での観測"]
            (for [o (sort-by kw-name governor/allowed-ops)]
              (row (code (str ":" (kw-name o)))
                   (yes-no (contains? auto3 o))
                   (if (contains? governor/always-escalate-ops o)
                     "<span class=\"warn\">yes</span>" "<span class=\"muted\">no</span>")
                   (cond
                     (contains? governor/always-escalate-ops o)
                     "<span class=\"warn\">clean でも必ず人間の承認</span>"
                     (contains? auto3 o)
                     "<span class=\"ok\">governor clean かつ高確信なら auto-commit</span>"
                     :else "<span class=\"warn\">人間の承認</span>")
                   (let [rs (get observed o [])]
                     (if (seq rs)
                       (str "<span class=\"num\">" (count rs) "</span> 件 · "
                            (str/join " " (map #(code (:tid %)) rs)))
                       "<span class=\"muted\">—</span>"))))))))

(defn- hard-rule-section [db runs]
  (let [holds (hard-holds db)
        by-rule (group-by #(-> % :violations first :rule) holds)]
    (section
     "HARD ルールの網羅（恒久・人間でも上書き不能）"
     (str "governor が出しうる HARD ルールは " (count expected-hard-rules)
          " 本あり、このシナリオは全部を実際に踏んでいる。"
          "<code>-main</code> は 1 本でも未実施なら書き出しを拒否するので、"
          "この表が埋まっていること自体がビルド時の不変条件。"
          "詳細文はこの画面の作文ではなく、governor が台帳へ書いた文字列そのもの。")
     (table ["rule" "発火数" "run" "governor が台帳に残した理由"]
            (for [r (sort-by kw-name expected-hard-rules)
                  :let [fs (get by-rule r [])
                        rs (filter #(some #{r} (:hard-rules %)) runs)]]
              (row (code (str ":" (kw-name r)))
                   (if (seq fs)
                     (str "<span class=\"critical num\">" (count fs) "</span>")
                     "<span class=\"warn num\">0</span>")
                   (if (seq rs) (str/join " " (map #(code (:tid %)) rs))
                       "<span class=\"muted\">—</span>")
                   (esc (or (-> fs first :violations first :detail) "—"))))))))

(defn- phase-section [db runs]
  (let [ph-holds (phase-holds db)]
    (section
     "rollout フェーズゲート（compliance とは別レイヤ）"
     (str "<code>specialtyretailops.phase/phases</code> をそのまま表にしたもの。"
          "既定フェーズは <span class=\"num\">" phase/default-phase "</span>。"
          "governor の HARD hold と違い、ここで止まった提案は"
          "フェーズが進めば通る — この実行では "
          "<span class=\"num\">" (count ph-holds) "</span> 件が "
          (code ":phase-disabled") " で止まっており、"
          "台帳上は <code>:basis</code> が空なので HARD hold とは区別できる。"
          "<code>:flag-quality-concern</code> はどのフェーズの auto 集合にも属さない —— "
          "これは未実装ではなく恒久的な構造。")
     (table ["phase" "label" "書き込み可能な op" "auto-commit 可能な op" "この実行で使用"]
            (for [p (sort (keys phase/phases))
                  :let [{:keys [label writes auto]} (get phase/phases p)]]
              (row (str "<span class=\"num\">" p "</span>"
                        (when (= p phase/default-phase) " <span class=\"badge\">default</span>"))
                   (esc label)
                   (kws writes)
                   (kws auto)
                   (let [rs (filter #(= p (:phase %)) runs)]
                     (if (seq rs)
                       (str "<span class=\"num\">" (count rs) "</span> 件 · "
                            (str/join " " (map #(code (:tid %)) rs)))
                       "<span class=\"muted\">—</span>"))))))))

(defn- coordination-section [db]
  (let [log (store/coordination-log db)]
    (section
     "コミット済みコーディネーション記録（SSoT への書き込み）"
     (str "<code>specialtyretailops.store/coordination-log</code> の中身。"
          "SSoT に触れるのはグラフの <code>:commit</code> ノードだけで、"
          "HARD hold と却下はここに 1 行も残らない。"
          "「承認者」列は記録の <code>:payload</code> に <code>:approved-by</code> が"
          "実在するかを毎回引いて出しており、無い場合は auto-commit だったことを意味する。")
     (table ["#" "op" "store" "コミットされた値" "承認者"]
            (map-indexed
             (fn [i {:keys [op store-id value payload]}]
               (row (str "<span class=\"num\">" (inc i) "</span>")
                    (code (str ":" (kw-name op)))
                    (code store-id)
                    (esc (fmt-map (dissoc value :store-id)))
                    (if-let [by (:approved-by payload)]
                      (str "<span class=\"ok\">" (esc by) "</span>")
                      "<span class=\"muted\">—（auto-commit）</span>")))
             log)))))

(defn- ledger-section [db]
  (let [ledger (store/ledger db)
        committed (committed-facts db)
        log (vec (store/coordination-log db))
        ;; The :commit node writes the SSoT record and the ledger fact in
        ;; the same step, so the k-th :committed fact is the k-th
        ;; coordination record. -main asserts the counts match before this
        ;; join is ever rendered.
        approver (zipmap committed (map #(get-in % [:payload :approved-by]) log))]
    (section
     "監査台帳（append-only）"
     (str "<code>specialtyretailops.store/ledger</code> の全 "
          (count ledger) " 件。commit・hold・却下が同じ 1 本の不変列に載る。"
          "<code>:basis</code> が空の <code>:governor-hold</code> は"
          "フェーズ由来の hold（回復可能）、空でないものが governor の HARD hold（恒久）。")
     (table ["#" "fact" "op" "store" "disposition" "basis" "confidence" "承認者"]
            (map-indexed
             (fn [i {:keys [t op store-id disposition basis confidence phase-reason] :as f}]
               (row (str "<span class=\"num\">" (inc i) "</span>")
                    (case t
                      :committed "<span class=\"ok\">committed</span>"
                      :approval-rejected "<span class=\"warn\">approval-rejected</span>"
                      :governor-hold (if (seq basis)
                                       "<span class=\"critical\">governor-hold</span>"
                                       "<span class=\"warn\">governor-hold</span>")
                      (esc (kw-name t)))
                    (code (str ":" (kw-name op)))
                    (code store-id)
                    (esc (kw-name disposition))
                    (cond
                      (seq basis) (kws basis)
                      phase-reason (str (code (str ":" (kw-name phase-reason)))
                                        " <span class=\"muted\">(rollout)</span>")
                      :else "<span class=\"muted\">—</span>")
                    (if confidence (str "<span class=\"num\">" (esc confidence) "</span>")
                        "<span class=\"muted\">—</span>")
                    (if-let [by (get approver f)]
                      (str "<span class=\"ok\">" (esc by) "</span>")
                      "<span class=\"muted\">—</span>")))
             ledger)))))

(defn- attribution-note
  "Derived at render time by looking at what the store ACTUALLY kept, so
  this paragraph self-corrects if the store is later changed. It does not
  assert a defect it has not measured."
  [db]
  (let [log       (store/coordination-log db)
        kept      (filter #(get-in % [:payload :approved-by]) log)
        rejects   (rejections db)
        reject-by (filter :by rejects)]
    (str
     "<strong>承認者の帰属について（実測）:</strong> "
     (if (seq kept)
       (str "承認を経てコミットされた " (count kept) " 件は、"
            "<code>commit-record!</code> が受け取る記録の <code>:payload</code> に "
            "<code>:approved-by</code> を保持しており、上の表はそれを表示している。")
       (str "このストアの <code>commit-record!</code> は承認者を保持していない —— "
            "承認済みコミットが " (count log) " 件ある一方で "
            "<code>:payload</code> に <code>:approved-by</code> を持つ記録は 0 件だった。"))
     " "
     (if (seq rejects)
       (if (seq reject-by)
         (str "却下 " (count rejects) " 件についても却下者が台帳に残っている。")
         (str "一方、却下された " (count rejects) " 件には却下者が残っていない —— "
              "<code>governor/hold-fact</code> は要求元の <code>:actor-id</code> しか"
              "書かず、resume で渡された <code>:by</code> を読まないため。"
              "つまり台帳だけを見ても「誰も承認しなかった」のか"
              "「誰が却下したかを保存していない」のかは区別できない。"
              "これは仕様ではなくこのストアの現在地であり、直れば上の表に自動的に現れる。"))
       "この実行には却下が含まれていない。"))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a completed `run-demo!` result. Reads
  only the store and the recorded run outcomes -- nothing is invented
  here."
  [{:keys [db runs]}]
  (str
   "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-4773 · specialtyretailops オペレーターコンソール</title>\n"
   "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
   "</head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>専門店における新品小売（ISIC 4773） — オペレーターコンソール</h1>\n"
   "</header>\n"
   "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
   "<span class=\"badge\">governor-gated</span> "
   "<span class=\"badge\">品質紛争の確定行為は恒久的に対象外</span></p>\n"
   "<p>この actor は専門店のバックオフィス業務（販売・在庫・返品記録、フロアスタッフ配置、"
   "仕入先発注の調整、品質懸念のフラグ）を<strong>調整</strong>するだけで、"
   "値付けも、返金・交換・賠償責任といった品質紛争の確定も決して行わない。"
   "後者は rollout の未実装項目ではなく、governor の HARD ルールとして構造的に禁止されている。</p>\n"
   "<main>\n"
   (runs-section runs)
   (store-section db runs)
   (vendor-section db runs)
   (op-gate-section runs)
   (hard-rule-section db runs)
   (phase-section db runs)
   (coordination-section db)
   (ledger-section db)
   "</main>\n"
   "<footer>\n"
   "<p>" (attribution-note db) "</p>\n"
   "<p>このページはビルド時に <code>clojure -M:dev:render-html</code> が "
   "<code>specialtyretailops.render-html</code> を実行して生成している。"
   "表示されている店舗・仕入先・提案・判定・保留理由・台帳行は、"
   "<code>specialtyretailops.operation</code> の langgraph StateGraph を実際に "
   (count runs) " 回走らせた結果であり、手書きの値は 1 つも含まない。"
   "タイムスタンプや乱数を含まないため、同じシードに対する再生成は byte 単位で同一になる。</p>\n"
   "<p class=\"muted\">cloud-itonami-isic-4773 · AGPL-3.0-or-later · "
   "flagship checklist item 2 (ADR-2607189300)</p>\n"
   "</footer>\n"
   "</body></html>\n"))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out       (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as demo} (run-demo!)
        holds     (hard-holds db)
        rules     (set (mapcat :basis holds))
        committed (committed-facts db)
        log       (store/coordination-log db)]

    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? holds)
      (throw (ex-info (str "no HARD :governor-hold fact on the ledger -- refusing to write "
                           "a console that shows no real hold")
                      {:ledger-facts (count (store/ledger db))})))

    ;; ...and one that shows only SOME of them quietly under-reports the
    ;; governor. Fail the build instead.
    (when (not= expected-hard-rules rules)
      (throw (ex-info (str "the demo scenario does not exercise every governor HARD rule -- "
                           "refusing to write a console that under-reports the governor")
                      {:expected expected-hard-rules
                       :observed rules
                       :missing (remove rules expected-hard-rules)
                       :unexpected (remove expected-hard-rules rules)})))

    ;; The ledger/coordination-log positional join the console renders is
    ;; only sound while the :commit node writes both in lockstep.
    (when (not= (count committed) (count log))
      (throw (ex-info (str "committed ledger facts and coordination records are out of step -- "
                           "the approver join in the audit-ledger table would be wrong")
                      {:committed (count committed) :coordination-records (count log)})))

    ;; A human sign-off that never happens is not a human-in-the-loop demo.
    (when (empty? (rejections db))
      (throw (ex-info "no :approval-rejected fact -- the demo never shows a human saying no"
                      {:ledger-facts (count (store/ledger db))})))

    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render demo)))

    (println "wrote" out
             (str "(" (count runs) " runs, "
                  (count (store/ledger db)) " ledger facts, "
                  (count holds) " HARD holds over "
                  (count rules) "/" (count expected-hard-rules) " rules, "
                  (count (phase-holds db)) " phase-disabled holds, "
                  (count log) " committed records, "
                  (count (rejections db)) " rejections)"))))
