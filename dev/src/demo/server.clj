(ns demo.server
  (:require [org.httpkit.server :as hk-server]))

(defn httpkit-server
  [{:keys [handler]}]
  (hk-server/run-server handler {:port 3000}))
