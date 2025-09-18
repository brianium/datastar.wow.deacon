(ns datastar.wow.deacon.interceptors
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

(defn update-nexus
  ([]
   (update-nexus (deacon.atom/store)))
  ([store]
   (update-nexus store {}))
  ([store opts]
   (fn [nexus]
     (update nexus :nexus/interceptors (fnil conj []) (create-interceptor store opts)))))
