(ns datastar.wow.deacon.caffeine
  "In-memory connection storage backed by Caffeine."
  (:require [datastar.wow.deacon.protocols :refer [ConnectionStore]])
  (:import (com.github.benmanes.caffeine.cache Caffeine Cache)
           (java.time Duration)))

(defrecord CaffeineConnectionStore [^Cache cache]
  ConnectionStore
  (store!   [_ k conn] (.put cache k conn))
  (connection [_ k]    (.getIfPresent cache k))
  (purge!   [_ k]      (.invalidate cache k))
  (list-keys [_]       (-> (.asMap cache)
                           (keys)))

  Object
  (toString [_]
    (str "<CaffeineConnectionStore size=" (.estimatedSize cache) ">")))

(defn store
  "Return a `CaffeineConnectionStore`.

   Options (all optional):
   * `:idle-ms`      – how long a connection may sit idle before it is
                       auto-purged (default 10 minutes)
   * `:maximum-size` – hard cap on the total number of live connections
                       (default 10000)
   * `:cache`        – an already-built `com.github.benmanes.caffeine.cache.Cache`
                       (overrides every other option)"
  [{:keys [idle-ms maximum-size cache]
    :or   {idle-ms      (* 10 60 1000)   ;; 10 min
           maximum-size 10000}}]

  (if cache
    (->CaffeineConnectionStore cache)

    (let [builder (doto (Caffeine/newBuilder)
                    (.maximumSize (long maximum-size))
                    (.expireAfterAccess (Duration/ofMillis idle-ms)))
          cache   (.build builder)]
      (->CaffeineConnectionStore cache))))
