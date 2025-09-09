(ns datastar.wow.deacon.atom
  "A simple in-memory, atom backed connection store"
  (:require [datastar.wow.deacon.protocols :refer [ConnectionStore]]))

(defrecord AtomConnectionStore [*atom]
  ConnectionStore
  (store! [_ k connection]
    (swap! *atom assoc k connection))
  (connection [_ k]
    (@*atom k))
  (purge! [_ k]
    (swap! *atom dissoc k)))

(defn store
  ([]
   (store {}))
  ([deps]
   (AtomConnectionStore. (or (:atom deps) (atom {})))))
