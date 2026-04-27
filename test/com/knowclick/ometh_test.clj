(ns com.knowclick.ometh-test
  (:require
   [com.knowclick.ometh :as o]
   [clojure.test :as t :refer [is deftest testing]]))

(defn- fresh-env [] (o/make-default-env))

;; ---------------------------------------------------------------------------
;; Env construction
;; ---------------------------------------------------------------------------

(deftest make-default-env-test
  (let [env (o/make-default-env)]
    (is (contains? (::o/effect env) ::o/event))
    (is (contains? (::o/effect env) ::o/noop))))

;; ---------------------------------------------------------------------------
;; Convenience constructors
;; ---------------------------------------------------------------------------

(deftest ->query-test
  (is (= {::o/query ::foo ::o/params {:x 1}} (o/->query ::foo {:x 1}))))

(deftest ->effect-test
  (is (= {::o/effect ::foo ::o/params {:x 1}} (o/->effect ::foo {:x 1})))
  (is (= {::o/effect ::foo ::o/params {:x 1} ::o/on-success {::o/effect ::bar}}
         (o/->effect ::foo {:x 1} :on-success {::o/effect ::bar}))))

(deftest ->event-test
  (is (= {::o/event ::foo ::o/params {:x 1}} (o/->event ::foo {:x 1}))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(deftest register-query-test
  (let [env (o/register-query (fresh-env) ::my-query {::o/impl (fn [_ _ _] :ok)})]
    (is (contains? (::o/query env) ::my-query))))

(deftest register-query!-test
  (let [env* (atom (fresh-env))]
    (o/register-query! env* ::my-query {::o/impl (fn [_ _ _] :ok)})
    (is (contains? (::o/query @env*) ::my-query))))

(deftest q1-basic-test
  (let [env (o/register-query (fresh-env) ::get-k
                              {::o/impl (fn [env {:keys [k]} _] (get env k))})]
    (is (= 42 (o/q1 (assoc env :answer 42)
                    {::o/query ::get-k ::o/params {:k :answer}})))))

(deftest q1-unknown-query-throws-test
  (is (thrown-with-msg? Exception #"Unrecognized"
                        (o/q1 (fresh-env) {::o/query ::nonexistent}))))

(deftest q1-without-env-throws-outside-impl-test
  (is (thrown? Exception (o/q1 {::o/query ::anything}))))

(deftest q-multiple-queries-test
  (let [env (-> (fresh-env)
                (o/register-query ::double {::o/impl (fn [_ {:keys [n]} _] (* 2 n))})
                (o/register-query ::triple {::o/impl (fn [_ {:keys [n]} _] (* 3 n))}))]
    (is (= {:d 4 :t 6}
           (o/q env {:d {::o/query ::double ::o/params {:n 2}}
                     :t {::o/query ::triple ::o/params {:n 2}}})))))

(deftest query-with-declared-dependencies-test
  (let [env (-> (fresh-env)
                (o/register-query ::base {::o/impl (fn [_ {:keys [n]} _] n)})
                (o/register-query ::doubled
                                  {::o/queries {:base (fn [{:keys [n]}]
                                                        {::o/query ::base ::o/params {:n n}})}
                                   ::o/impl (fn [_ _ {:keys [base]}]
                                              (* 2 base))}))]
    (is (= 10 (o/q1 env {::o/query ::doubled ::o/params {:n 5}})))))

(comment
  (query-with-declared-dependencies-test))

(deftest q1-inside-event-impl-uses-bound-env-test
  (testing "q1 with no env arg works inside an event impl where *env* is bound"
    (let [log (atom nil)
          env (-> (fresh-env)
                  (o/register-query ::get-val {::o/impl (fn [env _ _] (:val env))})
                  (o/register-effect ::noop-log {::o/impl (fn [_ v _] (reset! log v))})
                  (o/register-event ::use-q1
                                    {::o/impl (fn [_ _ _]
                                                (let [v (o/q1 {::o/query ::get-val})]
                                                  {::o/effect ::noop-log ::o/params v}))}))]
      (o/handle-event! (assoc env :val :found-it) {::o/event ::use-q1})
      (is (= :found-it @log)))))

(deftest query-dedupe
  (testing "Queries are deduplicated."
    (let [log (atom {})
          env (-> (fresh-env)
                  (o/register-query ::base {::o/impl (fn [_ {:keys [n]} _]
                                                       (swap! log update n (fnil inc 0))
                                                       n)})
                  (o/register-query ::doubled
                                    {::o/queries {:base (fn [{:keys [n]}]
                                                          {::o/query ::base ::o/params {:n n}})}
                                     ::o/impl (fn [_ _ {:keys [base]}]
                                                (* 2 base))}))]
      (is (= (o/q env {:first {::o/query ::doubled
                               ::o/params {:n 1}}
                       :second {::o/query ::doubled
                                ::o/params {:n 1}}
                       :third {::o/query ::doubled
                               ::o/params {:n 2}}})
             {:first 2
              :second 2
              :third 4}))
      (is (= @log {1 1 2 1})))))

;; ---------------------------------------------------------------------------
;; Effects
;; ---------------------------------------------------------------------------

(deftest register-effect-test
  (let [env (o/register-effect (fresh-env) ::my-effect {::o/impl (fn [_ _ _] :done)})]
    (is (contains? (::o/effect env) ::my-effect))))

(deftest register-effect!-test
  (let [env* (atom (fresh-env))]
    (o/register-effect! env* ::my-effect {::o/impl (fn [_ _ _] :done)})
    (is (contains? (::o/effect @env*) ::my-effect))))

(deftest effect-step!-test
  (let [state (atom nil)
        env (o/register-effect (fresh-env) ::set-state
                               {::o/impl (fn [_ {:keys [v]} _] (reset! state v))})]
    (o/effect-step! env {::o/effect ::set-state ::o/params {:v 99}})
    (is (= 99 @state))))

(deftest effect!-returns-map-with-result-test
  (let [env (o/register-effect (fresh-env) ::produce {::o/impl (fn [_ _ _] :value)})
        ret (o/effect! env {::o/effect ::produce})]
    (is (= :value (::o/result ret)))
    (is (= ::produce (::o/effect ret)))))

(deftest effect!-on-success-invocation-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-effect ::main {::o/impl (fn [_ _ _] :ok)})
                (o/register-effect ::success-effect {::o/impl (fn [_ _ _] (swap! log conj :success))}))]
    (o/effect! env {::o/effect ::main ::o/on-success {::o/effect ::success-effect}})
    (is (= [:success] @log))))

(deftest effect!-on-success-fn-handler-test
  (testing "on-success as a function receives the effect result"
    (let [captured (atom nil)
          env (-> (fresh-env)
                  (o/register-effect ::produce {::o/impl (fn [_ _ _] 42)})
                  (o/register-effect ::consume {::o/impl (fn [_ {:keys [v]} _] (reset! captured v))}))]
      (o/effect! env {::o/effect ::produce
                      ::o/on-success (fn [result] {::o/effect ::consume ::o/params {:v result}})})
      (is (= 42 @captured)))))

(deftest effect!-on-success-result-stored-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-effect ::main {::o/impl (fn [_ _ _] :ok)})
                (o/register-effect ::after {::o/impl (fn [_ _ _] (swap! log conj :after) :after-result)}))
        ret (o/effect! env {::o/effect ::main ::o/on-success {::o/effect ::after}})]
    (is (contains? ret ::o/on-success-result))
    (is (= [:after] @log))))

(deftest effect!-on-error-invocation-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-effect ::failing {::o/impl (fn [_ _ _] (throw (ex-info "oops" {})))})
                (o/register-effect ::error-handler {::o/impl (fn [_ _ _] (swap! log conj :error))}))]
    (o/effect! env {::o/effect ::failing ::o/on-error {::o/effect ::error-handler}})
    (is (= [:error] @log))))

(deftest effect!-on-error-fn-handler-test
  (testing "on-error as a function receives the exception"
    (let [captured (atom nil)
          env (-> (fresh-env)
                  (o/register-effect ::failing {::o/impl (fn [_ _ _] (throw (ex-info "oops" {:code 99})))})
                  (o/register-effect ::capture {::o/impl (fn [_ {:keys [v]} _] (reset! captured v))}))]
      (o/effect! env {::o/effect ::failing
                      ::o/on-error (fn [ex]
                                     {::o/effect ::capture
                                      ::o/params {:v (:code (ex-data ex))}})})
      (is (= 99 @captured)))))

(deftest effect!-throws-without-on-error-test
  (let [env (o/register-effect (fresh-env) ::failing
                               {::o/impl (fn [_ _ _] (throw (ex-info "oops" {})))})]
    (is (thrown? Exception (o/effect! env {::o/effect ::failing})))))

(deftest effects!-single-invocation-test
  (let [log (atom [])
        env (o/register-effect (fresh-env) ::log
                               {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})]
    (o/effects! env {::o/effect ::log ::o/params {:v :a}})
    (is (= [:a] @log))))

(deftest effects!-sequence-test
  (let [log (atom [])
        env (o/register-effect (fresh-env) ::log
                               {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})]
    (o/effects! env [{::o/effect ::log ::o/params {:v :a}}
                     {::o/effect ::log ::o/params {:v :b}}])
    (is (= [:a :b] @log))))

(deftest effect-with-query-dependencies-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-query ::get-multiplier {::o/impl (fn [_ _ _] 3)})
                (o/register-effect ::multiply
                                   {::o/queries {:mult (fn [_] {::o/query ::get-multiplier})}
                                    ::o/impl (fn [_ {:keys [n]} {:keys [mult]}]
                                               (swap! log conj (* n mult)))}))]
    (o/effect! env {::o/effect ::multiply ::o/params {:n 5}})
    (is (= [15] @log))))

(deftest built-in-noop-effect-test
  (let [ret (o/effect! (fresh-env) {::o/effect ::o/noop})]
    (is (nil? (::o/result ret)))))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(deftest register-event-test
  (let [env (o/register-event (fresh-env) ::my-event {::o/impl (fn [_ _ _] nil)})]
    (is (contains? (::o/event env) ::my-event))))

(deftest register-event!-test
  (let [env* (atom (fresh-env))]
    (o/register-event! env* ::my-event {::o/impl (fn [_ _ _] nil)})
    (is (contains? (::o/event @env*) ::my-event))))

(deftest handle-event!-basic-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-effect ::log {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})
                (o/register-event ::my-event
                                  {::o/impl (fn [_ {:keys [v]} _]
                                              {::o/effect ::log ::o/params {:v v}})}))]
    (o/handle-event! env {::o/event ::my-event ::o/params {:v :hello}})
    (is (= [:hello] @log))))

(deftest handle-event!-with-declared-queries-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-query ::get-name {::o/impl (fn [env _ _] (:name env))})
                (o/register-effect ::log {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})
                (o/register-event ::greet
                                  {::o/queries {:name (fn [_] {::o/query ::get-name})}
                                   ::o/impl (fn [_ _ {:keys [name]}]
                                              {::o/effect ::log
                                               ::o/params {:v (str "hello " name)}})}))]
    (o/handle-event! (assoc env :name "world") {::o/event ::greet})
    (is (= ["hello world"] @log))))

(deftest handle-event!-nil-return-test
  (let [env (o/register-event (fresh-env) ::noop-event {::o/impl (fn [_ _ _] nil)})]
    (is (= [] (o/handle-event! env {::o/event ::noop-event})))))

(deftest handle-event!-multiple-effects-test
  (let [log (atom [])
        env (-> (fresh-env)
                (o/register-effect ::log {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})
                (o/register-event ::multi
                                  {::o/impl (fn [_ _ _]
                                              [{::o/effect ::log ::o/params {:v :a}}
                                               {::o/effect ::log ::o/params {:v :b}}])}))]
    (o/handle-event! env {::o/event ::multi})
    (is (= [:a :b] @log))))

(deftest handle-event!-returns-event-invocation-test
  (testing "an event impl can return an event invocation which gets handled"
    (let [log (atom [])
          env (-> (fresh-env)
                  (o/register-effect ::log {::o/impl (fn [_ {:keys [v]} _] (swap! log conj v))})
                  (o/register-event ::inner
                                    {::o/impl (fn [_ {:keys [v]} _]
                                                {::o/effect ::log ::o/params {:v v}})})
                  (o/register-event ::outer
                                    {::o/impl (fn [_ {:keys [v]} _]
                                                {::o/event ::inner ::o/params {:v v}})}))]
      (o/handle-event! env {::o/event ::outer ::o/params {:v :delegated}})
      (is (= [:delegated] @log)))))

(deftest handle-event!-unknown-event-throws-test
  (is (thrown-with-msg? Exception #"Unrecognized"
                        (o/handle-event! (fresh-env) {::o/event ::does-not-exist}))))

;; ---------------------------------------------------------------------------
;; normalize-effect-invocations
;; ---------------------------------------------------------------------------

(deftest normalize-effect-invocations-test
  (testing "nil stays nil"
    (is (nil? (o/normalize-effect-invocations nil))))

  (testing "single effect wrapped in vector"
    (is (= [{::o/effect ::foo}]
           (o/normalize-effect-invocations {::o/effect ::foo}))))

  (testing "event invocation converted to ::o/event effect"
    (let [[item] (o/normalize-effect-invocations {::o/event ::bar ::o/params {:x 1}})]
      (is (= ::o/event (::o/effect item)))
      (is (= {::o/event ::bar ::o/params {:x 1}} (::o/params item)))))

  (testing "sequence of effects returned as-is"
    (is (= 2 (count (o/normalize-effect-invocations
                     [{::o/effect ::a} {::o/effect ::b}])))))

  (testing "nils in sequence are stripped"
    (is (= 2 (count (o/normalize-effect-invocations
                     [{::o/effect ::a} nil {::o/effect ::b}]))))))

;; ---------------------------------------------------------------------------
;; Invalid definitions throw
;; ---------------------------------------------------------------------------

(deftest invalid-effect-definition-throws-test
  (is (thrown? Exception (o/register-effect (fresh-env) ::bad {}))))

(deftest invalid-event-definition-throws-test
  (is (thrown? Exception (o/register-event (fresh-env) ::bad {}))))

(deftest invalid-query-definition-throws-test
  (is (thrown? Exception (o/register-query (fresh-env) ::bad {}))))

;; ---------------------------------------------------------------------------
;; defquery / defeffect / defevent macros
;; ---------------------------------------------------------------------------

(def ^:private macro-env* (atom (o/make-default-env)))

(o/defquery macro-double
  {::o/env* macro-env*}
  [_env {:keys [n]} _]
  (* 2 n))

(o/defquery macro-add
  {::o/env* macro-env*}
  [_env {:keys [a b]}]
  (+ a b))

(o/defeffect macro-log
  {::o/env* macro-env*}
  [_env {:keys [v]} _]
  v)

(o/defevent macro-event
  {::o/env* macro-env*}
  [_env {:keys [n]} _]
  {::o/effect :com.knowclick.ometh-test/macro-log
   ::o/params {:v (* n 10)}})

(deftest defquery-defines-fn-test
  (is (= 10 (macro-double nil {:n 5} nil))))

(deftest defquery-2-arg-impl-test
  (is (= 7 (macro-add nil {:a 3 :b 4}))))

(deftest defquery-defines-constructor-test
  (is (= {::o/query :com.knowclick.ometh-test/macro-double ::o/params {:n 3}}
         (->macro-double {:n 3})))
  (is (= {::o/query :com.knowclick.ometh-test/macro-double}
         (->macro-double))))

(deftest defquery-registers-in-env-test
  (is (contains? (::o/query @macro-env*) :com.knowclick.ometh-test/macro-double)))

(deftest defquery-executable-via-q1-test
  (is (= 8 (o/q1 @macro-env* (->macro-double {:n 4})))))

(deftest defeffect-defines-fn-test
  (is (= :hello (macro-log nil {:v :hello} nil))))

(deftest defeffect-defines-constructor-test
  (is (= {::o/effect :com.knowclick.ometh-test/macro-log ::o/params {:v :x}}
         (->macro-log {:v :x})))
  (is (= {::o/effect :com.knowclick.ometh-test/macro-log}
         (->macro-log))))

(deftest defeffect-registers-in-env-test
  (is (contains? (::o/effect @macro-env*) :com.knowclick.ometh-test/macro-log)))

(deftest defeffect-executable-via-effect!-test
  (let [ret (o/effect! @macro-env* (->macro-log {:v :world}))]
    (is (= :world (::o/result ret)))))

(deftest defeffect-constructor-with-on-success-test
  (let [inv (->macro-log {:v :x} :on-success {::o/effect ::o/noop})]
    (is (= {::o/effect ::o/noop} (::o/on-success inv)))))

(deftest defevent-defines-fn-test
  (let [result (macro-event nil {:n 3} nil)]
    (is (= :com.knowclick.ometh-test/macro-log (::o/effect result)))
    (is (= {:v 30} (::o/params result)))))

(deftest defevent-defines-constructor-test
  (is (= {::o/event :com.knowclick.ometh-test/macro-event ::o/params {:n 5}}
         (->macro-event {:n 5})))
  (is (= {::o/event :com.knowclick.ometh-test/macro-event}
         (->macro-event))))

(deftest defevent-registers-in-env-test
  (is (contains? (::o/event @macro-env*) :com.knowclick.ometh-test/macro-event)))

(deftest defevent-handled-via-handle-event!-test
  (let [results (o/handle-event! @macro-env* (->macro-event {:n 7}))]
    (is (= 1 (count results)))
    (is (= 70 (::o/result (first results))))))

(comment
  (t/run-tests)
  )
