(ns com.knowclick.ometh
  "Controlled reads and writes."
  (:require
   [fmnoise.flow :as flow]
   [malli.core :as m]
   [malli.util :as mu]
   [exoscale.interceptor :as ei]))

(def malli-registry
  "Defines schemas for all the data types relevant to Ometh"
  {::QueryDefinition [:map
                      [::queries
                       {:optional true
                        :doc "Queries on which this query depends"}
                       [:ref ::QueryDependencies]]
                      [::impl
                       {:doc "Function which implements this query"}
                       [:=> [:cat ::Env ::Params ::QueryResults] :any]]
                      [::params-schema
                       {:optional true
                        :doc "Malli schema for the parameters of this query"}
                       :any]]
   ::QueryDependencies [:map-of :any [:orn
                                      [:direct-invocation [:ref ::QueryInvocation]]
                                      [:fn [:=> [:cat ::Params] ::QueryInvocation]]]]
   ::Env [:map
          [::effect [:map-of :keyword [:ref ::EffectDefinition]]]
          [::event  [:map-of :keyword [:ref ::EventDefinition]]]
          [::query  [:map-of :keyword [:ref ::QueryDefinition]]]]
   ::QueryResults [:map-of :any :any]
   ::Params :any
   ::QueryInvocation [:map
                      [::query :keyword]
                      [::params {:optional true} [:ref ::Params]]]
   ::EffectDefinition [:map
                       [::queries
                        {:optional true
                         :doc "Queries on which this effect depends"}
                        [:ref ::QueryDependencies]]
                       [::impl
                        {:doc "Function which performs the effect"}
                        [:=> [:cat [:ref ::Env] [:ref ::Params] [:ref ::QueryResults]] :any]]
                       [::params-schema
                        {:optional true
                         :doc "Malli schema for the parameters of this effect"}
                        :any]]
   ::EffectInvocation [:map
                       [::effect :keyword]
                       [::params {:optional true} [:ref ::Params]]
                       [::on-success {:optional true} [:ref ::EffectResultHandler]]
                       [::on-error {:optional true} [:ref ::EffectResultHandler]]]
   ::EffectResult :any
   ::EffectResultHandler [:orn
                          [:effect [:ref ::EffectInvocation]]
                          [:event  [:ref ::EventInvocation]]
                          [:nil    nil?]
                          [:seq    [:sequential
                                    [:orn
                                     [:effect [:ref ::EffectInvocation]]
                                     [:event  [:ref ::EventInvocation]]
                                     [:nil    nil?]]]]
                          [:fn     [:=> [:cat [:ref ::EffectResult]]
                                    [:orn
                                     [:effect [:ref ::EffectInvocation]]
                                     [:event  [:ref ::EventInvocation]]
                                     [:nil    nil?]
                                     [:sequential
                                      [:orn
                                       [:effect [:ref ::EffectInvocation]]
                                       [:event  [:ref ::EventInvocation]]
                                       [:nil    nil?]]]]]]]
   ;; effects produced by an event
   ::ProducedEffects :any
   ::EventDefinition [:map
                      [::queries
                       {:optional true
                        :doc "Queries on which this event depends"}
                       [:ref ::QueryDependencies]]
                      [::impl
                       {:doc "Function which decides what to do when the event occurs"}
                       [:=> [:cat [:ref ::Env] [:ref ::Params] [:ref ::QueryResults]] :any]]
                      [::params-schema
                       {:optional true
                        :doc "Malli schema for the parameters of this event"}
                       [:ref ::ProducedEffects]]
                      [::interceptors
                       {:optional true}
                       [:vector
                        [:orn
                         [:map [:ref ::InterceptorDefinition]]
                         [:fn  [:=> [:cat ::Params]
                                [:or
                                 :nil
                                 [:ref ::InterceptorDefinition]
                                 [:sequential [:ref ::InterceptorDefinition]]]]]]]]]
   ::EventInvocation [:map
                      [::event :keyword]
                      [::params {:optional true} [:ref ::Params]]]
   ::InterceptorContext
   [:map {:doc "Keys included in the `ctx` passed to interceptors"}
    [::env [:ref ::Env]]
    [::event-invocation [:ref ::EventInvocation]]
    [::params {:optional true} [:ref ::Params]]
    [::query-results [:ref ::QueryResults]]
    [::ei/error {:optional true} :any]
    [::effects {:optional true} [:ref ::ProducedEffects]]]
   ::InterceptorPhaseImpl [:=> [:cat [:ref ::InterceptorContext]]
                           [:ref ::InterceptorContext]]
   ::InterceptorDefinition
   [:map
    [::enter {:optional true}
     [:map
      [::queries
       {:optional true
        :doc "Queries on which the :enter phase of this interceptor depends"}
       [:ref ::QueryDependencies]]
      [::impl
       {:doc "Function which implements the :enter phase of this interceptor"}
       [:ref ::InterceptorPhaseImpl]]]]
    [::leave {:optional true}
     [:map
      [::queries
       {:optional true
        :doc "Queries on which the :leave phase of this interceptor depends"}
       [:ref ::QueryDependencies]]
      [::impl
       {:doc "Function which implements the :leave phase of this interceptor"}
       [:ref ::InterceptorPhaseImpl]]]]
    [::error {:optional true}
     [:map
      [::queries
       {:optional true
        :doc "Queries on which the :error phase of this interceptor depends"}
       [:ref ::QueryDependencies]]
      [::impl
       {:doc "Function which implements the :error phase of this interceptor"}
       [:=> [:cat [:ref ::InterceptorContext] :any] ;; second arg is the error.
        [:ref ::InterceptorContext]]]]]]
   })

(def full-malli-registry (merge (m/default-schemas) malli-registry))

(def explain-query-definition
  (m/explainer (m/schema ::QueryDefinition {:registry full-malli-registry})))

(def explain-effect-definition
  (m/explainer (m/schema ::EffectDefinition {:registry full-malli-registry})))

(def explain-event-definition
  (m/explainer (m/schema ::EventDefinition {:registry full-malli-registry})))

(defn- register [env kind name m]
  (assoc-in env [kind name] m))

(defn- lookup [env kind name]
  (let [item (get-in env [kind name])]
    (when-not item
      (throw
       (ex-info (str "Unrecognized " (clojure.core/name kind))
                {:name name
                 :kind kind
                 ::category :not-found})))
    item))

(defn- lookup-from-invocation [env invocation]
  (cond
    (::query invocation) (lookup env ::query (::query invocation))
    (::event invocation) (lookup env ::event (::event invocation))
    (::effect invocation) (lookup env ::effect (::effect invocation))
    :else (throw (ex-info "Unrecognized invocation" {:invocation invocation}))))

(def ^:private ^:dynamic *env* nil)

(def ^:dynamic *check-definition-schemas*
  "Whether to check the schema of effect, query, and event definitions"
  true)

(def ^:dynamic *check-invocation-schemas*
  "Whether to check the schema of effect, query, and event invocations"
  true)

(def ^:private ^:dynamic *pending-invocations* #{})

(declare default-env*)

;; -- Queries --

(defn- add-query-invocation-explainer [query-def]
  (let [invocation-schema (m/schema ::QueryInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema query-def)]
                                (mu/merge invocation-schema [:map [::params (m/schema p)]])
                                invocation-schema))]
    (assoc query-def ::explainer invocation-explainer)))

(defn register-query
  "Add a query to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-query-definition m)]
      (throw (ex-info "Invalid query definition" explain))))
  (register env ::query name (-> m (add-query-invocation-explainer))))

(defn register-query!
  "Add a query to `env*`."
  ([name m] (register-query! default-env* name m))
  ([env* name m]
   (swap! env* register-query name m))  )

(defn get-query [env id]
  (lookup env ::query id))

(declare q)

(defn- query-groups
  "Given a seq of query invocations (NOT functions returning query invocations)
  return a list where item 0 is the set of all queries which depend on no other queries,
  item 1 is the set of all queries which depend on queries in item 0 or nothing,
  item n is the set of all queries which depend on queries in item n-1 or nothing.

  Gathers all transitive dependency queries of `invocations`"
  [env invocations]
  (loop [out (list (set invocations))
         invocations invocations]
    (let [next-group (->> invocations
                          (mapcat (fn [invocation]
                                    (let [item (lookup-from-invocation env invocation)
                                          queries (::queries item)
                                          params  (::params invocation)]
                                      (->> queries
                                           vals
                                           (map (fn [query-invocation-or-fn]
                                                  (if (fn? query-invocation-or-fn)
                                                    (query-invocation-or-fn params)
                                                    query-invocation-or-fn)))))))
                          (set))]
      (if (seq next-group)
        (if (some #(= % next-group) out)
          (throw (ex-info "Infinite query loop detected" {:groups out :repeat next-group}))
          (recur (conj out next-group)
                 next-group))
        out))))

(defn- execute-query-invocation
  "Execute a query invocation, given the results of all its dependencies as a mapping from query invocation to result."
  [env query-invocation dependency-results]
  (let [query-id (::query query-invocation)
        query-def (get-query env query-id)
        _ (when *check-invocation-schemas*
            (when-let [explain (when-let [f (::explainer query-def)] (f query-invocation))]
              (throw (ex-info "Invalid query invocation"
                              (assoc explain
                                     ::kind :invalid-query-invocation
                                     ::category :incorrect)))))
        impl      (::impl query-def)
        params    (::params query-invocation)]
    (when (contains? *pending-invocations* query-invocation)
      (throw (ex-info "Recursive query invocation detected"
                      {:invocation query-invocation
                       ::category :fault})))
    (binding [*pending-invocations* (conj *pending-invocations* query-invocation)]
      (let [;; TODO: we redundantly convert fn-form query invocations to data-form query
            ;; invocations.
            query-results (reduce-kv
                           (fn [m k query-invocation]
                             (let [query-invocation (if (fn? query-invocation)
                                                      (query-invocation params)
                                                      query-invocation)]
                               (assoc m k (get dependency-results query-invocation))))
                           {}
                           (::queries query-def))]
        (impl env params query-results)))))

(defn- execute-query-groups
  "Given query invocation groups where each query invocation in each group depends only on queries in earlier groups, returns a mapping from query invocation to result."
  [env groups]
  (reduce
   (fn [prev-group-results group]
     ;; TODO: could execute all queries in the group in parallel.
     (into prev-group-results
           (comp
            (remove (fn [query-invocation] (contains? prev-group-results query-invocation)))
            (map (fn [query-invocation]
                   [query-invocation
                    (execute-query-invocation
                     env query-invocation prev-group-results)])))
           group))
   {}
   groups))

(defn- execute-queries [env invocations]
  (let [groups (query-groups env invocations)
        invocation->result (execute-query-groups env groups)]
    invocation->result))

(defn q1
  "Execute 1 query, returning its result.
  When called in an effect, query, or event function, `env` is optional."
  ([query-invocation]
   (when-not *env*
     (throw (ex-info "Cannot execute query without env" {:query query-invocation})))
   (q1 *env* query-invocation))
  ([env query-invocation]
   (let [results (execute-queries env [query-invocation])]
     (get results query-invocation))))

(defn q
  "Execute any number of queries, returning a map from alias to query result.
  When called in an effect, query, or event function, `env` is optional.
  `queries` should be a map from alias to query invocation."
  ([queries]
   (when-not *env*
     (throw (ex-info "Cannot execute queries without env" {:queries queries})))
   (q *env* queries))
  ([env queries]
   (let [invocations (vals queries)
         results (execute-queries env invocations)]
     (into {}
           (map (fn [[k invocation]] [k (get results invocation)]))
           queries))))

(defn- execute-dependency-queries
  "Execute the queries that are dependencies of an effect, interceptor, or event."
  [env invocation-params queries]
  (->> queries
       (into {}
             (map (fn [[k query-invocation-or-fn]]
                    [k (if (fn? query-invocation-or-fn)
                         (query-invocation-or-fn invocation-params)
                         query-invocation-or-fn)])))
       (q env)))

;; -- Effects --

(defn- add-effect-invocation-explainer [effect-def]
  (let [invocation-schema (m/schema ::EffectInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema effect-def)]
                                (mu/merge invocation-schema [:map [::params (m/schema p)]])
                                invocation-schema))]
    (assoc effect-def ::explainer invocation-explainer)))

(defn register-effect
  "Add an effect to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-effect-definition m)]
      (throw (ex-info "Invalid effect definition" explain))))
  (register env ::effect name (-> m (add-effect-invocation-explainer))))

(defn register-effect!
  "Add an effect to `env*`"
  ([name m] (register-effect! default-env* name m))
  ([env* name m]
   (swap! env* register-effect name m)))

(defn get-effect [env id]
  (lookup env ::effect id))

(defn- event->effect [x]
  (if (::event x)
    {::effect ::event
     ::params x}
    x))

(defn- get-result-effect-invocations [x previous-effect-result]
  (cond
    (nil? x) x
    (fn? x) (let [x2 (x previous-effect-result)]
              (cond
                (nil? x2) x2
                (::effect x2) [x2]
                (::event  x2) [(event->effect x2)]
                (sequential? x2) (->> x2 (remove nil?) (mapv event->effect))
                :else (throw (ex-info "Unrecognized effects" {:effects x2}))))
    (::effect x) [x]
    (::event  x) [(event->effect x)]
    (sequential? x) (->> x (remove nil?) (mapv event->effect))
    :else (throw (ex-info "Unrecognized effects" {:effects x}))))

(defn normalize-effect-invocations
  "Events can return their effects in various forms - this normalizes those forms to a vector of event invocations."
  [x]
  (cond
    (nil? x) []
    (::effect x) [x]
    (::event  x) [(event->effect x)]
    (sequential? x) (->> x (remove nil?) (mapv event->effect))
    :else (throw (ex-info "Unrecognized effects" {:effects x}))))

(declare effects-seq!)

(defn effect-step!
  "Execute an effect invocation, returning its result.
  Does not execute on-success or on-error."
  [env effect-invocation]
  (let [effect-id (::effect effect-invocation)
        effect-def (get-effect env effect-id)
        _ (when *check-invocation-schemas*
            (when-let [explain (when-let [f (::explainer effect-def)] (f effect-invocation))]
              (throw (ex-info "Invalid effect invocation" explain))))
        impl      (::impl effect-def)
        params    (::params effect-invocation)]
    (when (contains? *pending-invocations* effect-invocation)
      (throw (ex-info "Recursive effect invocation detected"
                      {:invocation effect-invocation})))
    (binding [*pending-invocations* (conj *pending-invocations* effect-invocation)]
      (let [query-results (execute-dependency-queries env params (::queries effect-def))
            result (try (impl env params query-results)
                        (catch Throwable t t))]
        result))))

(defn effect!
  "Execute an effect invocation, and any provided on-success or on-error as appropriate.
  Returns effect invocation with associated ::result and
  ::on-success-result or ::on-error-result as appropriate."
  [env effect-invocation]
  (let [result (effect-step! env effect-invocation)
        more (if (flow/fail? result)
               (if-let [fx (get-result-effect-invocations (::on-error effect-invocation)
                                                          result)]
                 {::on-error-result (effects-seq! env fx)}
                 (throw (ex-info "Effect failed with no on-error provided"
                                 {:failure result})))
               (if-let [fx (get-result-effect-invocations (::on-success effect-invocation)
                                                          result)]
                 {::on-success-result (effects-seq! env fx)}
                 nil))]
    (-> effect-invocation
        (merge more)
        (assoc ::result result))))

(defn- effects-seq! [env effect-invocations]
  (mapv
   (fn [effect-invocation]
     (effect! env effect-invocation))
   effect-invocations))

(defn effects!
  "Normalizes `effects` to a sequence of effect invocations and executes them."
  ([effects]
   (when-not *env*
     (throw (ex-info "Cannot execute effects without env" {:effects effects})))
   (effects! *env* effects))
  ([env effects]
   (let [effects (normalize-effect-invocations effects)]
     (effects-seq! env effects))))

;; -- Events --

(defn- add-event-invocation-explainer [event-def]
  (let [invocation-schema (m/schema ::EventInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema event-def)]
                                (mu/merge invocation-schema [:map [::params (m/schema p)]])
                                invocation-schema))]
    (assoc event-def ::explainer invocation-explainer)))

(defn register-event
  "Add an event to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-event-definition m)]
      (throw (ex-info "Invalid event definition" explain))))
  (register env ::event name (-> m (add-event-invocation-explainer))))

(defn register-event!
  "Add an event to `env*`"
  ([name m] (register-event! default-env* name m))
  ([env* name m]
   (swap! env* register-event name m)))

(defn get-event [env event-id]
  (lookup env ::event event-id))

(defn- make-interceptor-ctx-handler [phase]
  (let [{::keys [impl queries]} phase]
    (fn [{::keys [env event-invocation] :as ctx}]
      (let [params (::params event-invocation)
            ctx (assoc ctx ::query-results (execute-dependency-queries env params queries))]
        (impl ctx)))))

(defn- make-interceptor-error-handler [phase]
  (let [{::keys [impl queries]} phase]
    (fn [{::keys [env event-invocation] :as ctx} err]
      (let [params (::params event-invocation)
            ctx (assoc ctx ::query-results (execute-dependency-queries env params queries))]
        (impl ctx err)))))

(defn- event-def->interceptor [event-def]
  (let [impl    (::impl event-def)
        queries (::queries event-def)
        enter (cond-> {::impl (fn [{::keys [env query-results event-invocation]
                                    :as ctx}]
                                (let [params (::params event-invocation)
                                      fx (impl env params query-results)]
                                  (update ctx ::effects
                                          (fn [xs]
                                            (into (normalize-effect-invocations xs)
                                                  (normalize-effect-invocations fx))))))}
                queries (assoc ::queries queries))]
    {:enter (make-interceptor-ctx-handler enter)}))

(defn lower-interceptor
  "Given an interceptor definition as understood by Ometh, return a regular interceptor
  as understood by exoscale.interceptor"
  [def]
  (cond-> {}
    (::enter def) (assoc :enter (make-interceptor-ctx-handler (::enter def)))
    (::leave def) (assoc :leave (make-interceptor-ctx-handler (::leave def)))
    (::error def) (assoc :error (make-interceptor-error-handler (::error def)))))

(defn lift-interceptor
  "Given a regular interceptor as understood by exoscale.interceptor, return an interceptor
  definition as understood by Ometh."
  [i]
  (cond-> {}
    (:enter i) (assoc ::enter {::impl (:enter i)})
    (:leave i) (assoc ::leave {::impl (:leave i)})
    (:error i) (assoc ::error {::impl (:error i)})))

(defn- make-event-interceptor-chain [env event-invocation]
  (let [event-id (::event event-invocation)
        event-def (get-event env event-id)
        params    (::params event-invocation)
        event-interceptors (->> (or (::interceptors event-def) [])
                                (mapcat (fn [def-or-fn]
                                          ;; If a function of the event's parameters is
                                          ;; provided, the return value of that fn is
                                          ;; nil (no interceptors)
                                          ;; a map (1 interceptor)
                                          ;; or a seq (n interceptors)
                                          (if (fn? def-or-fn)
                                            (let [x (def-or-fn params)]
                                              (cond
                                                (nil? x) []
                                                (map? x) [x]
                                                (sequential? x) x
                                                :else (throw (ex-info "Invalid interceptors"
                                                                      {:interceptors x}))))
                                            [def-or-fn])))
                                (mapv lower-interceptor))
        event-impl-as-interceptor (event-def->interceptor event-def)]
    (conj event-interceptors event-impl-as-interceptor)))

(defn handle-event!
  "Handle an event invocation by:
  - executing its ::queries
  - executing its ::impl
  - executing any effects returned by the ::impl
  Returns a sequence of effect results."
  ([event-invocation]
   (handle-event! @default-env* event-invocation))
  ([env event-invocation]
   (binding [*env* env]
     (let [event-id (::event event-invocation)
           event-def (get-event env event-id)
           _ (when *check-invocation-schemas*
               (when-let [explain (when-let [f (::explainer event-def)] (f event-invocation))]
                 (throw (ex-info "Invalid event invocation"
                                 (assoc explain
                                        ::kind :invalid-event-invocation
                                        ::category :incorrect)))))
           interceptor-chain (make-event-interceptor-chain env event-invocation)]
       (when (contains? *pending-invocations* event-invocation)
         (throw (ex-info "Recursive event invocation detected"
                         {:invocation event-invocation})))
       (binding [*pending-invocations* (conj *pending-invocations* event-invocation)]
         (let [ctx-in {::env env
                       ::event-invocation event-invocation}
               ctx-out (ei/execute ctx-in interceptor-chain)
               fx (-> ctx-out
                      ::effects
                      (normalize-effect-invocations))
               fx-result (effects-seq! env fx)]
           fx-result))))))

;; -- Envs

(defn make-env
  "Construct an environment."
  []
  {::effect {}
   ::query  {}
   ::event  {}})

(defn make-default-env
  "Construct an environment containing the default effects, queries, and events."
  []
  (-> (make-env)
      (register-effect ::event
                       {:params-schema (m/schema ::EventInvocation
                                                 {:registry full-malli-registry})
                        ::impl (fn [env event _] (handle-event! env event))})
      (register-effect ::noop
                       {::impl (constantly nil)})))

(def default-env*
  "An atom containing the default environment.
  This is the atom mutated by default when registering effects, queries, and events."
  (atom (make-default-env)))

;; -- Interceptor Helpers

(defn terminate
  "Terminate an interceptor chain, optionally with some effects."
  ([ctx] (-> ctx (ei/terminate)))
  ([ctx effects]
   (-> ctx
       (assoc ::effects effects)
       (ei/terminate))))

;; -- Convenience Constructors

(defn ->query
  "Construct a query invocation"
  ([query-name] {::query query-name})
  ([query-name params] {::query query-name ::params params}))

(defn ->effect
  "Construct an effect invocation"
  ([name] {::effect name})
  ([name params] {::effect name ::params params})
  ([name params & {:keys [on-success on-error]}]
   (cond-> {::effect name}
     params (assoc ::params params)
     on-success (assoc ::on-success on-success)
     on-error (assoc ::on-error on-error))))

(defn ->event
  "Construct an event invocation"
  ([name] {::event name})
  ([name params] {::event name ::params params}))

;; -- Convenience Register-ers.

(def defhandler-args-schema
  [:catn
   [:docstring [:? :string]]
   [:attr-map  [:? :map]]
   [:impl-params [:vector :any]]
   [:body [:* :any]]])

(defmacro defquery
  "Define a query.
  Registers the query in the default or given ::env* atom.
  Defines a function ->{query-name} to construct invocations of it.
  Defines a function {query-name} that implements the query (the ::impl function)."
  [query-name & args]
  (let [handler-name query-name
        parse-result (m/parse defhandler-args-schema args)
        _ (when (= ::m/invalid parse-result)
            (throw (ex-info "Invalid defquery args" (m/explain defhandler-args-schema args))))
        {:keys [docstring attr-map impl-params body]} (:values parse-result)
        handler-name-kw (keyword (name (ns-name *ns*)) (name handler-name))]
    `(do
       (defn ~(-> (str "->" handler-name) symbol)
         ~@(when docstring [docstring])
         ([] (com.knowclick.ometh/->query ~handler-name-kw))
         ([params#] (com.knowclick.ometh/->query ~handler-name-kw params#)))
       (defn ~handler-name ~@(when docstring [docstring]) ~impl-params ~@body)
       (com.knowclick.ometh/register-query!
        ~(or (:com.knowclick.ometh/env* attr-map)
             'com.knowclick.ometh/default-env*)
        ~handler-name-kw
        ~(assoc attr-map :com.knowclick.ometh/impl
                (case (count impl-params)
                  1 `(fn [_env# params# _query-results#]
                       (~handler-name params#))
                  2 `(fn [env# params# _query-results#]
                       (~handler-name env# params#))
                  3 `(var ~handler-name)
                  (throw (ex-info (str "Invalid arglist for defquery impl function."
                                       " Must provide 2 or 3 args.")
                                  {:arglist impl-params}))))))))

(defmacro defeffect
  "Define an effect.
  Registers the effect in the default or given ::env* atom.
  Defines a function ->{effect-name} to construct invocations of it.
  Defines a function {effect-name} that implements the effect (the ::impl function)."
  [effect-name & args]
  (let [handler-name effect-name
        parse-result (m/parse defhandler-args-schema args)
        _ (when (= ::m/invalid parse-result)
            (throw (ex-info "Invalid defeffect args" (m/explain defhandler-args-schema args))))
        {:keys [docstring attr-map impl-params body]} (:values parse-result)
        handler-name-kw (keyword (name (ns-name *ns*)) (name handler-name))]
    `(do
       (defn ~(-> (str "->" handler-name) symbol)
         ~@(when docstring [docstring])
         ([] (com.knowclick.ometh/->effect ~handler-name-kw))
         ([params#] (com.knowclick.ometh/->effect ~handler-name-kw params#))
         ([params# & more#] (apply com.knowclick.ometh/->effect ~handler-name-kw params# more#)))
       (defn ~handler-name ~@(when docstring [docstring]) ~impl-params ~@body)
       (com.knowclick.ometh/register-effect!
        ~(or (:com.knowclick.ometh/env* attr-map)
             'com.knowclick.ometh/default-env*)
        ~handler-name-kw
        ~(assoc attr-map :com.knowclick.ometh/impl
                (case (count impl-params)
                  2 `(fn [env# params# _query-results#]
                       (~handler-name env# params#))
                  3 `(var ~handler-name)
                  (throw (ex-info (str "Invalid arglist for defeffect impl function."
                                       " Must provide 2 or 3 args.")
                                  {:arglist impl-params}))))))))

(defmacro defevent
  "Define an event.
  Registers the event in the default or given ::env* atom.
  Defines a function ->{event-name} to construct invocations of it.
  Defines a function {event-name} that implements the event (the ::impl function)."
  [event-name & args]
  (let [handler-name event-name
        parse-result (m/parse defhandler-args-schema args)
        _ (when (= ::m/invalid parse-result)
            (throw (ex-info "Invalid defevent args" (m/explain defhandler-args-schema args))))
        {:keys [docstring attr-map impl-params body]} (:values parse-result)
        handler-name-kw (keyword (name (ns-name *ns*)) (name handler-name))]
    `(do
       (defn ~(-> (str "->" handler-name) symbol)
         ~@(when docstring [docstring])
         ([] (com.knowclick.ometh/->event ~handler-name-kw))
         ([params#] (com.knowclick.ometh/->event ~handler-name-kw params#)))
       (defn ~handler-name ~@(when docstring [docstring]) ~impl-params ~@body)
       (com.knowclick.ometh/register-event!
        ~(or (:com.knowclick.ometh/env* attr-map)
             'com.knowclick.ometh/default-env*)
        ~handler-name-kw
        ~(assoc attr-map :com.knowclick.ometh/impl
                (case (count impl-params)
                  2 `(fn [env# params# _query-results#]
                       (~handler-name env# params#))
                  3 `(var ~handler-name)
                  (throw (ex-info (str "Invalid arglist for defevent impl function."
                                       " Must provide 2 or 3 args.")
                                  {:arglist impl-params}))))))))
