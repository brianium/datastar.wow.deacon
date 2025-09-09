(ns demo.config
  (:require [demo.app :as app]
            [integrant.core :as ig]))

(defmethod ig/init-key ::constant [_ x] x)
(derive ::initial-state ::constant)

(defmethod ig/halt-key! ::app/server [_ stop-fn]
  (stop-fn))

(def config
  {::app/connections   {:type :atom}
   ::app/with-datastar {:type :httpkit
                        :store (ig/ref ::app/connections)}
   ::app/router        {:routes     app/routes
                        :middleware [(ig/ref ::app/with-datastar)]}
   ::app/handler       {:router     (ig/ref ::app/router)
                        :middleware []} 
   ::app/server        {:handler (ig/ref ::app/handler)
                        :type :httpkit}})
