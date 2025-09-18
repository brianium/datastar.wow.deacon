# Change Log

## [1.4.0] - 2025-09-18

Interceptor has fixed some issues and been made more performant. Connection closing and resolution is much more reliable with a single :before-dispatch interceptor.

### Changed

The previous interceptors ran more frequently and had lifecycle issues that made manually closing
connections cumbersome.

Previously:

```clojure
(defn create-interceptor
  [store {:keys [id-fn on-purge]
          :or   {id-fn    (constantly :datastar.wow.deacon/id)
                 on-purge (constantly nil)}}]
  (assert (impl/store? store) "Interceptor store must satisfy ConnectionStore protocol")
  {:id :datastar.wow.deacon/connections

   :before-dispatch
   (fn [{:keys [actions] :as ctx}]
     (if (= :datastar.wow/connection (ffirst actions))
       (let [k (scoped-key ctx id-fn)]
         (if-some [connection
                   (when k
                     (impl/connection store k))]
           (assoc-in ctx [:dispatch-data :datastar.wow/connection] connection)
           ctx))
       ctx))

   :after-effect
   (fn [{:keys [effect system dispatch-data] :as ctx}]
     (let [{:keys [request]} system]
       (when (and effect (= :datastar.wow/sse-closed (first effect)))
         (when-some [cname (get-in dispatch-data [:datastar.wow/response :datastar.wow.deacon/key])]
           (on-purge ctx)
           (impl/purge! store [(id-fn request) cname]))))
     ctx)

   :after-dispatch
   (fn [{{:keys [sse]} :system
         {:datastar.wow/keys [connection]
          {:datastar.wow/keys [with-open-sse?]} :datastar.wow/response} :dispatch-data
         :as ctx}]
     (let [k      (scoped-key ctx id-fn)
           store? (and (some? k)
                       (nil? connection)
                       (not with-open-sse?))]
       (when store?
         (impl/store! store k sse))
       ctx))})
```

Now:

```clojure
(defn create-interceptor
  "An interceptor that allows opt-in/managed connection persistence and reuse by adding
   a :datastar.wow.deacon/key value to a datastar.wow response."
  [store {:keys [id-fn on-purge]
          :or   {id-fn    (constantly :datastar.wow.deacon/id)
                 on-purge (constantly nil)}}]
  (assert (impl/store? store) "Interceptor store must satisfy ConnectionStore protocol")
  {:id :datastar.wow.deacon/connections

   :before-dispatch
   (fn [{:keys [actions]
         {:keys [sse]} :system
         {:datastar.wow/keys [connection]
          {:datastar.wow/keys [with-open-sse?]} :datastar.wow/response} :dispatch-data
         :as ctx}]
     (if-some [k (scoped-key ctx id-fn)]
       (let [action-ids (->> actions (map first) set)
             conn?      (action-ids :datastar.wow/connection)
             purge?     (action-ids :datastar.wow/sse-closed)
             store?     (and (some? sse)
                             (nil? connection)
                             (not with-open-sse?))]
         (cond
           conn?    (with-connection ctx store k)
           purge?   (purge! ctx store k on-purge)
           store?   (store! ctx store k sse)
           :else    ctx))
       ctx))})
```

This allows the following pattern:

```clojure
(defn index
  "Renders the initial page and handles app state management (reset and starting the timer). Loading
   the index with a running timer will subscribe to those changes"
  [{:keys [request-method]}]
  (case request-method
    :get {:body (app)}
    :delete {::d*conn/key [::counter 1]
             :🚀 [[::reset
                   [::d*/patch-signals [:timer/state]]
                   [::d*/close-sse]]]}
    :put {::d*conn/key [::counter 1]
          :🚀 [[::start-timer
                [::d*/patch-signals [:timer/state]]]]}))
```

Where one handler can initiate a connection (`:put` in this example) and another may close it (`:delete`). So in this case we keep the `[::counter 1]` connection open until it is explicitly closed in the `:delete` handler.

### Demo

The demo has been simplified (hopefully) in order to better highlight provided functionality. Check it out!

## [1.3.0] - 2025-09-18

1.3.0 will only work with datastar.wow version [1.0.0-RC1-wow-2](https://clojars.org/com.github.brianium/datastar.wow) and higher.

### Changed

The `:datastar.wow/connection` key is no longer used. `:datastar.wow.deacon/key` is used for writing AND reading connections.

Previously:

```clojure
(defn handler-1 [_]
  {::d*conn/key ::counter
   ::d*/with-open-sse? false
   ::d*/fx [[::subscribe ::index]
            [::start-timer]]})
			
(defn handler-2 [_]
  {::d*/connection ::counter})
```

Now:

```clojure
(defn handler-1 [_]
  {::d*conn/key ::counter
   ::d*/with-open-sse? false
   ::d*/fx [[::subscribe ::index]
            [::start-timer]]})
			
(defn handler-2 [_]
  {::d*conn/key ::counter})
```
