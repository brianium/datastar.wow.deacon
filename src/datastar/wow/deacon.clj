(ns datastar.wow.deacon
  (:require [datastar.wow.deacon.protocols :as impl]
            [datastar.wow.deacon.atom :as deacon.atom]
            [datastar.wow.deacon.caffeine :as deacon.caff]
            [datastar.wow.deacon.registry :as deacon.registry]))

(defmulti store
  "Create a connection store via an options map containing a :type key.
   Supports the following defaults:
     - :atom
     - :caffeine"
  :type)

(defmethod store :atom [opts]
  (deacon.atom/store opts))

(defmethod store :caffeine [opts]
  (deacon.caff/store opts))

(defmethod store :default [{:keys [type]
                            :or   {type :undefined}
                            :as   opts}]
  (throw (ex-info (str "Invalid connection store of type %s" type) opts)))

(defn store!
  "Store connection `sse-gen` identified by key `k`. Returns the connection that was stored"
  [s k sse-gen]
  (impl/store! s k sse-gen)
  sse-gen)

(defn connection
  "Fetch the connection identifed by key `k` from storage. Returns
  nil if connection does not exist"
  [s k]
  (impl/connection s k))

(defn purge!
  "Remove the connection identified by key `k` from storage"
  [s k]
  (impl/purge! s k))

(defn list-keys
  "Return a sequence of keys in the store. A key may be present even when a connection is
   no longer available in storage - always check the result of (connection s k)"
  [s]
  (impl/list-keys s))

(defn registry
  "A registry function for datastar.wow. The following (optional lol) options
   are supported:

  | key         | description                                                                                                                 |
  | ----------- | --------------------------------------------------------------------------------------------------------------------------- |
  | `:id-fn`    | Function that receives context and returns a unique per-user id. A typical case might be returning a user id from session.  |
  | `:on-purge` | Function that receives context and is called when a :datastar.wow/sse-closed effect is dispatched. Performs additional fx.  |"
  ([]
   (registry (store {:type :atom})))
  ([store]
   (registry store {}))
  ([store opts]
   (deacon.registry/registry store opts)))
