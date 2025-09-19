(ns datastar.wow.deacon.protocols)

(defprotocol ConnectionStore
  (store! [_ k sse-gen] "store an sse connection identified by key")
  (connection [_ k] "retrieve the connection identified by k if exists, otherwise nil")
  (purge! [_ k] "remove the connection identified by k if it exists")
  (list-keys [_] "return a sequence of keys contained in the store"))

(defn store?
  [x]
  (satisfies? ConnectionStore x))
