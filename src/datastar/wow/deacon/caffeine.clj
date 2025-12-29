(ns datastar.wow.deacon.caffeine
  "In-memory connection storage backed by Caffeine."
  (:require [datastar.wow.deacon.protocols :refer [ConnectionStore]])
  (:import (com.github.benmanes.caffeine.cache Caffeine Cache Expiry Scheduler)
           (java.time Duration Instant)))

(defrecord CaffeineConnectionStore [^Cache cache]
  ConnectionStore
  (store!   [_ k conn] (let [existing   (.getIfPresent cache k)
                             created-at (:created-at existing (Instant/now))
                             entry      {:conn conn :created-at created-at}]
                         (.put cache k entry)))
  (connection [_ k]    (some-> (.getIfPresent cache k) :conn))
  (purge!   [_ k]      (.invalidate cache k))
  (list-keys [_]       (-> (.asMap cache)
                           (keys)))

  Object
  (toString [_]
    (str "<CaffeineConnectionStore size=" (.estimatedSize cache) ">")))

(defn to-nanos
  [ms]
  (-> (Duration/ofMillis ms)
      (.toNanos)))

(defn- fixed-expiry
  [ms]
  (let [idle-ns (to-nanos ms)]
    (reify Expiry
      (expireAfterCreate [_ _ _ _]
        idle-ns)
      ;; Expiry is fixed based on `created-at` regardless of updates
      (expireAfterUpdate [_ _ new-value _ _]
        (let [created-at   (:created-at new-value)
              elapsed-ns   (.toNanos (Duration/between created-at (Instant/now)))
              remaining-ns (max 0 (- idle-ns elapsed-ns))] ;; Elapsed in nanoseconds
          remaining-ns)) ;; Remaining time to expire

      (expireAfterRead [_ _ _ _ old-expiry-ns]
        old-expiry-ns))))

(defn store
  "Return a `CaffeineConnectionStore`.

   Options (all optional):
   * `:duration-ms`  – how long a connection may sit idle before it is
                       auto-purged (default 10 minutes). If using a scheduler, this is the fixed time before
                       eviction
   * `:maximum-size` – hard cap on the total number of live connections
                       (default 10000)
   * `:scheduler`    - optional Scheduler instance. If `true` is given, the
                       dedicated, system-wide scheduling thread will be used - i.e (Scheduler/systemScheduler).
                       Otherwise, must be an instance of Scheduler - all other values are ignored.
                       If a scheduler is used, all entries will expire after a fixed time of `duration-ms`
   * `:cache`        – an already-built `com.github.benmanes.caffeine.cache.Cache`
                       (overrides every other option)"
  [{:keys [duration-ms maximum-size scheduler cache]
    :or   {duration-ms  (* 10 60 1000) ;; 10 min
           maximum-size 10000}}]

  (if cache
    (->CaffeineConnectionStore cache)

    (let [builder (doto (Caffeine/newBuilder)
                    (.maximumSize (long maximum-size)))
          
          scheduler' (cond
                       (true? scheduler) (Scheduler/systemScheduler)
                       (instance? Scheduler scheduler) scheduler
                       :else nil)
          
          builder (if scheduler'
                    (.expireAfter builder (fixed-expiry duration-ms))
                    (.expireAfterAccess builder (Duration/ofMillis duration-ms)))

          builder (if scheduler'
                    (.scheduler builder scheduler')
                    builder)
          
          cache   (.build builder)]
      (->CaffeineConnectionStore cache))))
