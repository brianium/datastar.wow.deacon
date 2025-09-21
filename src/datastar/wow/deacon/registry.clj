(ns datastar.wow.deacon.registry
  (:require [datastar.wow.deacon.atom :as deacon.atom]
            [datastar.wow.deacon.protocols :as impl]))

(defn- scoped-key
  "Creates a scoped key. Allows connection keys to be implicitly scoped
   to details in the context - for example a session id from the request"
  [{{:datastar.wow/keys [response]} :dispatch-data
    :as ctx} id-fn]
  (let [id (id-fn ctx)]
    (when-some [k (:datastar.wow.deacon/key response)]
      [id k])))

(defn store!
  "Store the given sse connection and set data for future dispatches"
  [ctx store k sse]
  (impl/store! store k sse)
  (update ctx :dispatch-data assoc :datastar.wow/connection sse))

(defn with-connection
  "If a connection exists in storage, use it"
  [ctx store k]
  (if-some [connection (impl/connection store k)]
    (assoc-in ctx [:dispatch-data :datastar.wow/connection] connection)
    ctx))

(defn purge!
  "Remove a connection from storage and from future dispatches"
  [ctx store k on-purge]
  (on-purge ctx)
  (impl/purge! store k)
  (update ctx :dispatch-data dissoc :datastar.wow/connection))

(defn registry
  "An interceptor that allows opt-in/managed connection persistence and reuse by adding
   a :datastar.wow.deacon/key value to a datastar.wow response."
  [store {:keys [id-fn on-purge]
          :or   {id-fn    (constantly :datastar.wow.deacon/id)
                 on-purge (constantly nil)}}]
  (assert (impl/store? store) "Interceptor store must satisfy ConnectionStore protocol")
  {:datastar.wow/interceptors
   [{:id :datastar.wow.deacon/connections

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
         ctx))}]})
