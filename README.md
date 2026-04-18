# Ometh

> The outer shell is decomposing, releasing a new upgraded self

Ometh is a little library for doing controlled mutations. It is inspired by [re-frame](https://github.com/day8/re-frame) and [hifi-crud](https://github.com/Ramblurr/hifi-crud). The pattern this library encourages is commonly called [Functional Core, Imperative Shell](https://functional-architecture.org/functional_core_imperative_shell/).

The core constructs of this library are:
- queries: impure reads
- effects: impure writes
- events:  things which we react to by doing some combination of reads, writes, and pure logic.

Let's say you've got a booking system that allows users to schedule appointments if their account payments are up to date. You might write that code like this:

```clojure
(defn request-booking [env {:keys [user-id appointment-window]}]
  (if (user-payments-up-to-date? (:db env) user-id)
    (if (valid-appointment-window? appointment-window)
      (if (appointment-window-open? (:db env) appointment-window)
        (try (book-appointment! (:db env) user-id appointment-window)
             (notify-user-of-successful-booking (:response-channel env))
             (catch Exception e
               (notify-user-of-failed-booking (:response-channel env))))
        (notify-user-of-unavailable-booking (:response-channel env)))
      (signal-request-error (:response-channel env) {:type :invalid}))
    (signal-request-error (:response-channel env) {:type :forbidden})))
```

The first thing you probably notice about this code is that it gets very nested. Also, there's unhappy path code throughout.

The solution to those problems is not in the usage of this library, but the use of [railway-oriented programming](https://fsharpforfunandprofit.com/rop/). My preferred Clojure library for that is [flow](https://github.com/fmnoise/flow). With that detail out of the way, our code can look much cleaner:

```clojure
(require '[fmnoise.flow :as flow])

(defn handle-booking-failure [env failure]
  (let [{:keys [type]} (ex-data failure)]
    (case type
      :forbidden (signal-request-error (:response-channel env) {:type :forbidden})
      :invalid   (signal-request-error (:response-channel env) {:type :forbidden})
      :unavailable-booking (notify-user-of-unavailable-booking (:response-channel env))
      :failed-booking      (notify-user-of-failed-booking (:response-channel env)))))

(defn request-booking [env {:keys [user-id appointment-window]}]
  (-> (flow/flet [;; unhappy path checks produce flow "Fail" instances, which are
                  ;; handled once at the end of this function.
                  _ (when-not (user-payments-up-to-date? (:db env) user-id)
                      (flow/fail-with {:data {:type :forbidden}}))
                  _ (when-not (valid-appointment-window? appointment-window)
                      (flow/fail-with {:data {:type :invalid}}))
                  _ (when-not (appointment-window-open? (:db env) appointment-window)
                      (flow/fail-with {:data {:type :unavailable-booking}}))
                  _ (try (book-appointment! (:db env) user-id appointment-window)
                         (catch Exception e
                           (flow/fail-with {:data {:type :failed-booking
                                                   :cause e}})))]
         (notify-user-of-successful-booking (:response-channel env)))
      (flow/else #(handle-booking-failure env %))))
```
