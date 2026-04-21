(ns com.knowclick.ometh
  "Controlled reads and writes."
  (:require
   [fmnoise.flow :as flow]
   [malli.core :as m]
   [malli.util :as mu]))

(def malli-registry
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
                       :any]]
   ::EventInvocation [:map
                      [::event :keyword]
                      [::params {:optional true} [:ref ::Params]]]})

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
                {:name name})))
    item))

(def ^:private ^:dynamic *env* nil)

(def ^:dynamic *check-definition-schemas*
  "Whether to check the schema of effect, query, and event definitions"
  true)

(def ^:dynamic *check-invocation-schemas*
  "Whether to check the schema of effect, query, and event invocations"
  true)

(def ^:private ^:dynamic *pending-invocations* #{})

(defn make-env
  "Construct an environment containing the given effects, queries, and events."
  [& {:keys [effects queries events]}]
  {::effect (or effects {})
   ::query  (or queries  {})
   ::event  (or events {})})

(declare handle-event! effect!)

(def default-effects
  {::event {;; ::params-schema EventInvocation ;; todo
            ::impl (fn [env event _] (handle-event! env event))}
   ::noop  {::impl (constantly nil)}})

(defn make-default-env
  "Construct an environment containing the default effects, queries, and events."
  [& {:keys [effects queries events] :as opts}]
  (make-env (update opts :effects merge default-effects)))

(def default-env*
  "An atom containing the default environment.
  This is the atom mutated by default when registering effects, queries, and events."
  (atom (make-default-env)))

;; -- Queries --

(defn register-query
  "Add a query to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-query-definition m)]
      (throw (ex-info "Invalid query definition" explain))))
  (let [invocation-schema (m/schema ::QueryInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema m)]
                                (mu/merge invocation-schema (m/schema p))
                                invocation-schema))]
    (register env ::query name
              (assoc m ::explainer invocation-explainer))))

(defn register-query!
  "Add a query to `env*`."
  ([name m] (register-query! default-env* name m))
  ([env* name m]
   (swap! env* register-query name m))  )

(defn get-query [env id]
  (lookup env ::query id))

(declare q)

(defn- execute-dependency-queries
  "Execute the queries that are dependencies of an effect, query, or event."
  [env invocation-params queries]
  (->> queries
       (into {}
             (map (fn [[k query-invocation-or-fn]]
                    [k (if (fn? query-invocation-or-fn)
                         (query-invocation-or-fn invocation-params)
                         query-invocation-or-fn)])))
       (q env)))

(defn q1
  "Execute 1 query, returning its result.
  When called in an effect, query, or event function, `env` is optional."
  ([query-invocation]
   (when-not *env*
     (throw (ex-info "Cannot execute query without env" {:query query-invocation})))
   (q1 *env* query-invocation))
  ([env query-invocation]
   (let [query-id (::query query-invocation)
         query-def (get-query env query-id)
         _ (when *check-invocation-schemas*
             (when-let [explain ((::explainer query-def) query-invocation)]
               (throw (ex-info "Invalid query invocation" explain))))
         impl      (::impl query-def)
         params    (::params query-invocation)]
     (when (contains? *pending-invocations* query-invocation)
       (throw (ex-info "Recursive query invocation detected"
                       {:invocation query-invocation})))
     (binding [*pending-invocations* (conj *pending-invocations* query-invocation)]
       (let [query-results (execute-dependency-queries env params (::queries query-def))]
         (impl env params query-results))))))

(defn q
  "Execute any number of queries, returning a map from alias to query result.
  When called in an effect, query, or event function, `env` is optional.
  `queries` should be a map from alias to query invocation."
  ([queries]
   (when-not *env*
     (throw (ex-info "Cannot execute queries without env" {:queries queries})))
   (q *env* queries))
  ([env queries]
   ;; TODO: could parallelize this:
   (reduce-kv
    (fn [memo query-key query-invocation]
      (assoc memo query-key (q1 env query-invocation)))
    {}
    queries)))

;; -- Effects --

(defn register-effect
  "Add an effect to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-effect-definition m)]
      (throw (ex-info "Invalid effect definition" explain))))
  (let [invocation-schema (m/schema ::EffectInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema m)]
                                (mu/merge invocation-schema (m/schema p))
                                invocation-schema))]
    (register env ::effect name
              (assoc m ::explainer invocation-explainer))))

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
     ::params (::event  x)}
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
    (nil? x) x
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
            (when-let [explain ((::explainer effect-def) effect-invocation)]
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

(defn register-event
  "Add an event to `env`"
  [env name m]
  (when *check-definition-schemas*
    (when-let [explain (explain-event-definition m)]
      (throw (ex-info "Invalid event definition" explain))))
  (let [invocation-schema (m/schema ::EventInvocation {:registry full-malli-registry})
        invocation-explainer (m/explainer
                              (if-let [p (::params-schema m)]
                                (mu/merge invocation-schema (m/schema p))
                                invocation-schema))]
    (register env ::event name
              (assoc m ::explainer invocation-explainer))))

(defn register-event!
  "Add an event to `env*`"
  ([name m] (register-event! default-env* name m))
  ([env* name m]
   (swap! env* register-event name m)))

(defn get-event [env event-id]
  (lookup env ::event event-id))

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
               (when-let [explain ((::explainer event-def) event-invocation)]
                 (throw (ex-info "Invalid event invocation" explain))))
           impl      (::impl event-def)
           params    (::params event-invocation)]
       (when (contains? *pending-invocations* event-invocation)
         (throw (ex-info "Recursive event invocation detected"
                         {:invocation event-invocation})))
       (binding [*pending-invocations* (conj *pending-invocations* event-invocation)]
         (let [query-results (execute-dependency-queries env params (::queries event-def))
               fx (-> (impl env params query-results)
                      (normalize-effect-invocations))
               fx-result (effects-seq! env fx)]
           fx-result))))))

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
        ~(assoc attr-map :com.knowclick.ometh/impl `(var ~handler-name))))))

(comment

  (macroexpand-1 '(defquery foo
                    "Does something cool."
                    {::params-schema [:map [:bar :int]]}
                    [env _ {:keys [bar]}]
                    (get-in (:db env) [:bar bar])))

  (macroexpand-1 '(defeffect foo
                    "Does something cool."
                    {::params-schema [:map [:bar :int]]}
                    [env _ {:keys [bar]}]
                    (get-in (:db env) [:bar bar])))

  (macroexpand-1 '(defevent foo
                    [env _ {:keys [bar]}]
                    (get-in (:db env) [:bar bar])))
  )

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
        ~(assoc attr-map :com.knowclick.ometh/impl `(var ~handler-name))))))

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
        ~(assoc attr-map :com.knowclick.ometh/impl `(var ~handler-name))))))

;; -- Usage --

(comment

  (def db* (atom {}))

  (register-query!
   ::access-mode
   {::impl (fn [env {:keys [user-id]} _] (get-in @db* [user-id :access-mode] :read-only))})

  (q1 @default-env* {::query ::access-mode
                     ::params {:user-id 1}})
  (q  @default-env* {:access {::query ::access-mode
                              ::params {:user-id 2}}})

  (register-effect!
   ::set-access-mode
   {::impl (fn [env {:keys [user-id mode]} _]
             (swap! db* assoc-in [user-id :access-mode] mode))})

  (effect! @default-env* {::effect ::set-access-mode
                          ::params {:user-id 1
                                    :mode :write}})

  (q1 @default-env* {::query ::access-mode
                     ::params {:user-id 1}})




  (register-effect!
   ::inc-success-count
   {::impl (fn [env {:keys [user-id]} _]
             (swap! db* update-in [user-id :success-count] (fnil inc 0)))})

  (effect! @default-env* {::effect ::set-access-mode
                          ::params {:user-id 1
                                    :mode :shark}
                          ::on-success {::effect ::inc-success-count
                                        ::params {:user-id 1}}})

  @db*

  (effect! @default-env* {::effect ::set-access-mode
                          ::params {:user-id 1
                                    :mode :whale}
                          ::on-success {::effect ::inc-success-count
                                        ::params {:user-id 1}}})

  (register-effect!
   ::println
   {::impl (fn [env msg _]
             (println msg))})

  (register-event!
   ::request-write-access
   {::queries {:current (fn [{:keys [user-id]}]
                          {::query ::access-mode
                           ::params {:user-id user-id}})}
    ::impl (fn [_ {:keys [user-id]} {:keys [current]}]
             (if (= current :write)
               {::effect ::println
                ::params "You can already write."}
               {::effect ::set-access-mode
                ::params {:user-id user-id
                          :mode :write}
                ::on-success {::effect ::inc-success-count
                              ::params {:user-id user-id}}}))})

  (register-event!
   ::request-read-only-access
   {::impl (fn [env {:keys [user-id]} _]
             {::effect ::set-access-mode
              ::params {:user-id user-id
                        :mode :read-only}
              ::on-success {::effect ::inc-success-count
                            ::params {:user-id user-id}}})})

  (handle-event! {::event ::request-write-access
                  ::params {:user-id 1}})

  (handle-event! {::event ::request-read-only-access
                  ::params {:user-id 1}})



  )
