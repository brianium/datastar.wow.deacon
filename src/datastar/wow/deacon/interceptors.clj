(ns datastar.wow.deacon.interceptors
  (:require [datastar.wow.deacon.atom :as deacon.atom]
            [datastar.wow.deacon.protocols :as impl]))

(defn create-interceptor
  [store {:keys [id-fn on-purge]
          :or   {id-fn    (constantly :datastar.wow.deacon/id)
                 on-purge (constantly nil)}}]
  (assert (impl/store? store) "Interceptor store must satisfy ConnectionStore protocol")
  {:id :datastar.wow.deacon/connections
   
   :before-dispatch
   (fn [{:keys [system dispatch-data] :as ctx}]
     (let [{:keys [sse]} system
           store? (not (:datastar.wow/with-open-sse? dispatch-data))
           id     (id-fn ctx)
           cname  (get-in dispatch-data [:datastar.wow/response :datastar.wow.deacon/name])]
       (when (and store? id cname (some? sse)) ;;; sse will be nil on close effects
         (impl/store! store [id cname] sse)))
     ctx)

   :after-effect
   (fn [{:keys [effect system dispatch-data] :as ctx}]
     (let [{:keys [request]} system]
       (when (and effect (= :datastar.wow/sse-closed (first effect)))
         (when-some [cname (get-in dispatch-data [:datastar.wow/response :datastar.wow.deacon/name])]
           (on-purge ctx)
           (impl/purge! store [(id-fn request) cname]))))
     ctx)})

(defn update-nexus
  ([]
   (update-nexus (deacon.atom/store)))
  ([store]
   (update-nexus store {}))
  ([store opts]
   (fn [nexus]
     (update nexus :nexus/interceptors (fnil conj []) (create-interceptor store opts)))))
