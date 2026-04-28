# Ometh

> The outer shell is decomposing, releasing a new upgraded self

Ometh is a little library for doing controlled mutations. It is inspired by [re-frame](https://github.com/day8/re-frame) and [hifi-crud](https://github.com/Ramblurr/hifi-crud). The pattern this library encourages is commonly called [Functional Core, Imperative Shell](https://functional-architecture.org/functional_core_imperative_shell/).

## Status

Alpha, breaking changes guaranteed or your money back.

## Usage

The core constructs of this library are:
- queries: impure reads
- effects: impure writes
- events:  things which we react to by doing some combination of reads, writes, and pure logic.

Let's say you've got a booking system that allows users to schedule appointments if their account payments are up to date. You might write that code like this:

```clojure
(defn user-payments-up-to-date? [env user-id]
  ;; db read here
  )

(defn valid-appointment-window? [appointment-window]
  ;; pure logic
  )

(defn appointment-window-open? [env appointment-window]
  ;; db read here
  )

(defn book-appointment! [env user-id appointment-window]
  ;; db write here
  )

(defn notify-user! [response-channel kind]
  ;; channel write here
  )

(defn request-booking [env {:keys [user-id appointment-window]}]
  (cond
    (not (user-payments-up-to-date? (:db env) user-id))
    (notify-user! (:response-channel env) :forbidden)

    (not (valid-appointment-window? appointment-window))
    (notify-user! (:response-channel env) :invalid)

    (not (appointment-window-open? (:db env) appointment-window))
    (notify-user! (:response-channel env) :unavailable)

    :else
    (try (book-appointment! (:db env) user-id appointment-window)
         (notify-user! (:response-channel env) :success)
         (catch Exception e
           (notify-user! (:response-channel env) :failed)))))

(request-booking env {:user-id 1 :appointment-window 'whatever})
```

This code gets the job done, but testing requires either creating the DB connection (with whatever schema and seed data) and response channel, or redefining the relevant functions with `with-redefs`.

`request-booking` combines impure reads (`user-payments-up-to-date?`, `appointment-window-open?`), pure logic (`valid-appointment-window?`), and impure writes (`book-appointment!`, `notify-user!`). It's not a pure function, and it has the problems associated with impure functions: it's harder to understand, harder to interact with at the repl, and more work to test.

"Functional core, imperative shell" would encourage us to do our impure reads in an outer layer, then, in the inner layer, decide what effects are necessary, then, back in the outer layer, run those effects. That model is a bit too simplistic when we consider that code like this is often not strictly `read -> decide -> write`, but instead each of these any number of times in any order.

### Clean Writes

We can get much of the benefit of pure functions by only rearranging the writes (we'll look at options for dealing with reads in a bit). Instead of doing the writes directly, we'll return data describing the writes. We'll then separately interpret that data to perform the actual mutations.

```clojure
(require '[com.knowclick.ometh :as o])

;; The `env*` is just an atom containing the defined events, queries, and effects.
(def env* (atom (o/make-default-env)))

;; Make a new effect available in the env:
(o/register-effect!
 env* ::notify-user
 {::o/impl (fn [env params query-results]
             (let [{:keys [response-channel]} env
                   {:keys [kind]} params]
               ;; channel write here
               ))})

(defn valid-appointment-window? [appointment-window]
  ;; still just pure logic
  )

(o/register-effect!
 env* ::book-appointment
 {::o/impl (fn [{:keys [db-conn]} {:keys [user-id appointment-window]} _]
             ;; db write here
             )})

(defn request-booking [env {:keys [user-id appointment-window]}]
  (cond
    (not (user-payments-up-to-date? (:db env) user-id))
    {::o/effect ::notify-user
     ::o/params {:kind :forbidden}}

    (not (valid-appointment-window? appointment-window))
    {::o/effect ::notify-user
     ::o/params {:kind :invalid}}

    (not (appointment-window-open? (:db env) appointment-window))
    {::o/effect ::notify-user
     ::o/params {:kind :unavailable}}

    :else
    {::o/effect ::book-appointment
     ::o/params {:user-id user-id
                 :appointment-window appointment-window}
     ::o/on-success {::o/effect ::notify-user
                     ::o/params {:kind :success}}
     ::o/on-error   {::o/effect ::notify-user
                     ::o/params {:kind :failed}}}))

(let [effects (request-booking @env* {:user-id user-id :appointment-window 'whatever})]
  (o/effects! @env* effects))

```

`request-booking` is now pure in terms of its writes. It returns "effect invocations", maps structured as Ometh expects and which describe effects to perform. `o/effects!` finds the effects by name in the `env*` and executes them, following `::o/on-success` and `::o/on-error` as appropriate.

### Registered Events

The above definition of `request-booking` requires callers to call `o/effects!`. Also, there's no data describing the event itself, which might be useful for logging. We can improve the situation by registering an event:


```clojure
;; unchanged
(o/register-effect!
 env* ::notify-user
 {::o/impl (fn [env params query-results]
             (let [{:keys [response-channel]} env
                   {:keys [kind]} params]
               ;; channel write here
               ))})

;; unchanged
(defn valid-appointment-window? [appointment-window]
  ;; still just pure logic
  )


;; unchanged
(o/register-effect!
 env* ::book-appointment
 {::o/impl (fn [{:keys [db-conn]} {:keys [user-id appointment-window]} _]
             ;; db write here
             )})

(defn request-booking [env {:keys [user-id appointment-window]} _] ;; note ignored 3rd parameter
  (cond
    (not (user-payments-up-to-date? (:db env) user-id))
    {::o/effect ::notify-user
     ::o/params {:kind :forbidden}}

    (not (valid-appointment-window? appointment-window))
    {::o/effect ::notify-user
     ::o/params {:kind :invalid}}

    (not (appointment-window-open? (:db env) appointment-window))
    {::o/effect ::notify-user
     ::o/params {:kind :unavailable}}

    :else
    {::o/effect ::book-appointment
     ::o/params {:user-id user-id
                 :appointment-window appointment-window}
     ::o/on-success {::o/effect ::notify-user
                     ::o/params {:kind :success}}
     ::o/on-error   {::o/effect ::notify-user
                     ::o/params {:kind :failed}}}))

(o/register-event! env* ::request-booking {::o/impl #'request-booking})

(o/handle-event! @env* {::o/event ::request-booking
                        ::o/params {:user-id user-id :appointment-window 'whatever}})

```

We still have `request-booking` available for testing, but we use `::request-booking` to describe the event with data.

### Convenience

So far we've been invoking and defining events and effects the verbose way. There are convenience functions for constructing invocations and macros for defining events, effects, and queries. The above could instead be defined as:

```clojure
(o/defeffect notify-user
  [{:keys [response-channel]} {:keys [kind]} _query-results]
  ;; db write here
  )

;; unchanged
(defn valid-appointment-window? [appointment-window]
  ;; still just pure logic
  )

(o/defeffect book-appointment
  [{:keys [db-conn]} {:keys [user-id appointment-window]} _query-results]
  ;; db write here
  )

(o/defevent request-booking
  [env {:keys [user-id appointment-window]} _query-results]
  (cond
    (not (user-payments-up-to-date? (:db env) user-id))
    (->notify-user {:kind :forbidden})

    (not (valid-appointment-window? appointment-window))
    (->notify-user {:kind :invalid})

    (not (appointment-window-open? (:db env) appointment-window))
    (->notify-user {:kind :unavailable})

    :else
    (->book-appointment {:user-id user-id
                         :appointment-window appointment-window}
                        {:on-success (->notify-user {:kind :success})
                         :on-error   (->notify-user {:kind :failed})})))

(o/handle-event! @env* (->request-booking {:user-id user-id :appointment-window 'whatever}))

```

`defeffect` and `defevent` (and `defquery`) all do the same 3 things:
- define a `->{name}` function to construct invocations of the effect/event/query
- define a `{name}` function which is used as the ::o/impl for the effect/event/query
  - this means that we can call `request-booking` exactly like we could when it was a normal function; it is still the same normal function.
- add to the env the effect/event/query, identified by the namespaced keyword name.

Note that in the above, the `env` is implicit; Ometh defines a `default-env*` that will probably be sufficient for most use cases. You can also pass `::o/env*` to these macros to use a different env atom.


### Clean Reads

We've mostly ignored impure reads until now for the simple reason that impure writes are more problematic than impure reads. If you only clean up your impure writes you will have made a significant improvement to your code.

Ometh supports an `::o/queries` attribute of event, effect, and query definitions. You can use this to declare the data dependencies of any of those. This can help keep the `::o/impl` function pure.

Here's `request-booking` using Ometh queries:

```clojure
;; unchanged
(o/defeffect notify-user
  [{:keys [response-channel]} {:keys [kind]} query-results]
  ;; db write here
  )

;; unchanged
(defn valid-appointment-window? [appointment-window]
  ;; still just pure logic
  )

;; unchanged
(o/defeffect book-appointment
  [{:keys [db-conn]} {:keys [user-id appointment-window]} _]
  ;; db write here
  )

(o/defquery user-payments-up-to-date?
  [env user-id _]
  ;; db read here
  )

(o/defquery appointment-window-open?
  [env appointment-window _]
  ;; db read here
  )

(o/defevent request-booking
  {::o/queries {:payments-up-to-date (fn [{:keys [user-id]}]
                                       (->user-payments-up-to-date? user-id))}}
  [env {:keys [user-id appointment-window]} {:keys [payments-up-to-date]}]
  (cond
    (not payments-up-to-date)
    (->notify-user {:kind :forbidden})

    (not (valid-appointment-window? appointment-window))
    (->notify-user {:kind :invalid})

    (not (o/q1 (->appointment-window-open? appointment-window)))
    (->notify-user {:kind :unavailable})

    :else
    (->book-appointment {:user-id user-id
                         :appointment-window appointment-window}
                        {:on-success (->notify-user {:kind :success})
                         :on-error   (->notify-user {:kind :failed})})))

(o/handle-event! @env* (->request-booking {:user-id user-id :appointment-window 'whatever}))

```

Our database read functions are now Ometh queries. We list `user-payments-up-to-date?` in the `::o/queries` under the alias `:payments-up-to-date`, then use the third parameter of the impl function to access the result of that query.

We also see a shortcoming of this approach. There's one remaining impurity in `request-booking`. We don't know that we need to or that we can call `appointment-window-open?` until after we have checked that it's a valid appointment window, so we're using `(o/q1 (->appointment-window-open? appointment-window))` to execute that query directly. `o/q1` is a helper function which executes a query, providing it the results of all the queries on which it depends via its `::o/queries`. It also allows callers to omit the `env` parameter when called from an `::o/impl` function.

So what can we do about this impurity? One option is to leave well enough alone. You can test the above easily enough by redefining `::appointment-window-open?` in a local env for the test.

Another option is to return an event invocation anywhere we would otherwise have to do something impure, including reads. Event invocations are permitted as effects; they are converted to the built-in effect `::o/event`.

```clojure
(o/defevent request-booking-2
  {::o/queries {:open (fn [{:keys [appointment-window]}]
                        (->appointment-window-open? appointment-window))}}
  [env {:keys [user-id appointment-window]} {:keys [open]}]
  (cond
    (not open)
    (->notify-user {:kind :unavailable})

    :else
    (->book-appointment {:user-id user-id
                         :appointment-window appointment-window}
                        {:on-success (->notify-user {:kind :success})
                         :on-error   (->notify-user {:kind :failed})})))

(o/defevent request-booking
  {::o/queries {:payments-up-to-date (fn [{:keys [user-id]}]
                                       (->user-payments-up-to-date? user-id))}}
  [env {:keys [user-id appointment-window]} {:keys [payments-up-to-date]}]
  (cond
    (not payments-up-to-date)
    (->notify-user {:kind :forbidden})

    (not (valid-appointment-window? appointment-window))
    (->notify-user {:kind :invalid})

    :else (->request-booking-2 {:user-id user-id :appointment-window appointment-window})))

```

Our event is now defined in terms of 2 pure functions. With this model we can define flows consisting of "read", "decide", and "write", any number of times, in any order, using only pure "decide" functions.

We could also use interceptors to manage flows like this.

### Interceptors

Clojure libraries typically define interceptors as maps containing keys (all optional) `:enter`, `:leave`, and `:error`. An interceptor executor applies a sequence of interceptors to a `ctx` map, eventually returning a probably-updated `ctx` map. Ometh uses [`exoscale/interceptor`](https://github.com/exoscale/interceptor) as its interceptor executor.

With Ometh, interceptors are defined a bit differently and are "compiled down" to that `{:enter, :leave, :error}` form. See the `o/lift-interceptor` and `o/lower-interceptor` functions for translations between Ometh's interceptor structure and the one used by `exoscale/interceptor`.

Interceptors in Ometh wrap event `::o/impl` functions. The `ctx` of the interceptor chain contains:

- `::o/env`: the Ometh env
- `::o/event-invocation`: the invocation for the event currently being handled
- `::o/query-results`: the current interceptor's query results
- `:exoscale.interceptor/error` (only in :error): the error
- `::o/effects`: added somewhere in the interceptor chain; the effects to do.

The purpose of executing the interceptor chain is to produce that `::o/effects` value; everything else in the result `ctx` is ignored.

The event's `::o/impl` function is itself converted to an interceptor and is the last one in the chain.

Here's an example interceptor:

```clojure
(def check-authz
  {::o/enter {::o/impl (fn [ctx]
                         (if (authorized? ctx)
                           ctx
                           (-> ctx
                               (assoc ::o/effects {::o/effect ::scold-user
                                                   ::o/params {:msg "You can't do that"}})
                               ;; Don't process rest of interceptor chain, instead turn around
                               ;; and start running :leave handlers:
                               (exoscale.interceptor/terminate))))}})
```

That could also be defined as:

```clojure
(def check-authz
  (-> {:enter (fn [ctx]
                ;; same as above:
                (if (authorized? ctx)
                  ctx
                  (-> ctx
                      (assoc ::o/effects {::o/effect ::scold-user
                                          ::o/params {:msg "You can't do that"}})
                      ;; Don't process rest of interceptor chain, instead turn around
                      ;; and start running :leave handlers:
                      (exoscale.interceptor/terminate))))}
      (o/lift-interceptor)))
```

But we're sneaking back to the land of impure reads if we just call `(authorized? ctx)`, so interceptors can use queries like other Ometh constructs:

```clojure
(def check-authz
  {::o/enter {::o/queries {:authz? {::o/query ::authorized}}
              ::o/impl (fn [{::o/keys [query-results] :as ctx}]
                         (if (:authz? query-results)
                           ctx
                           (-> ctx
                               (assoc ::o/effects {::o/effect ::scold-user
                                                   ::o/params {:msg "You can't do that"}})
                               (exoscale.interceptor/terminate))))}})
```

Note that each phase (`:enter`, `:leave`, `:error`) defines its own queries.


Interceptors are not registered. You just use them in event definitions under `::o/interceptors`:

```clojure
(o/defevent launch-rockets
  {::o/interceptors [check-authz]}
  [env params query-results]
  ;; if we got here, we know we're authorized to launch rockets.
  )
```

If the interceptor or interceptors to use depends upon the event's parameters, you can provide a function which returns 0 or more interceptors:

```clojure
(o/defevent launch-rockets
  {::o/interceptors [(fn [params] [(check-authz)])]}
  [env params query-results]
  ...
  )
```

Finally, Ometh provides `terminate` for the common interceptor use case of terminating a chain with some effects. You'll need to use `exoscale.interceptor` directly for more advanced use cases.

```clojure
(def check-authz
  {::o/enter {::o/queries {:authz? {::o/query ::authorized}}
              ::o/impl (fn [{::o/keys [query-results] :as ctx}]
                         (if (:authz? query-results)
                           ctx
                           (o/terminate ctx {::o/effect ::scold-user
                                             ::o/params {:msg "You can't do that"}})))}})
```


### Tips

- Include retry logic when necessary in effect definitions, rather than trying to dispatch effects/events for retries.
- Keep effects minimal and rely on `::o/on-success` for chaining effects which must happen in sequence.
- Queries should return as little data as possible. This makes testing easier.
