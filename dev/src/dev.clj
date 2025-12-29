(ns dev
  (:require [demo.config :refer [config]]
            [demo.system :as system]
            [datastar.wow.deacon :as d*conn]
            [malli.dev :as malli.dev]))

(defn start []
  (malli.dev/start!)
  (system/start config))

(defn stop []
  (malli.dev/stop!)
  (system/stop))

(defn before-ns-unload []
  (stop))

(start)

(comment
  (def s (d*conn/store {:type :caffeine :scheduler true :duration-ms 15000}))
  (d*conn/store! s ::test {:conn true})
  (d*conn/connection s ::test)
  )
