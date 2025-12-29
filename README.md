<p align="center">
  <br><br>
  <img src="datastar.wow.png" />
  <br><br>
  🕊
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="deacon-light.png">
    <img alt="Deacon" src="deacon-dark.png">
  </picture>
  🕊
</p>

[![Clojars Project](https://img.shields.io/clojars/v/com.github.brianium/datastar.wow.deacon.svg)](https://clojars.org/com.github.brianium/datastar.wow.deacon)
[![cljdoc badge](https://cljdoc.org/badge/com.github.brianium/datastar.wow.deacon)](https://cljdoc.org/d/com.github.brianium/datastar.wow.deacon)

Adds declarative connection management to a [datastar.wow](https://github.com/brianium/datastar.wow) application. Supports automatic connection storage scoped to global or user contexts.

## Table of Contents

- [Quick Example](#quick-example)
- [Installation](#installation)
- [Usage](#usage)
- [Options](#options)
- [Extending](#extending)
- [Demo](#demo)
- [Change Log](CHANGELOG.md)

## Quick Example

``` clojure
(ns myapp.web
  (:require [datastar.wow :as d*]
            [datastar.wow.deacon :as d*conn]))

(defn index
  "Renders the initial page and handles app state management (reset counter and start the timer)"
  [{:keys [request-method]}]
  (case request-method
    :get (let [{:keys [running]} @*state]
           {:body (app running)})
    :delete {::d*/fx [[::d*/patch-signals (reset! *state {:running false :counter 0})]]}
    :put    {::d*conn/key ::counter ;;; unique connection key signals deacon to store
             ::d*/with-open-sse? false
             ::d*/fx [[::subscribe ::index]
                      [::start-timer]]}))
					  
(defn jump
  "Adds a whopping 10 to the counter state - using the same connection established via index"
  [_]
  {::d*/connection ::counter ;;; specify the connection to use
   :🚀 [[::d*/patch-signals (swap! *state update :counter #(+ % 10))]]})
```
## Installation

`datastar.wow.deacon` is a companion to [datastar.wow](https://github.com/brianium/datastar.wow) and both must be installed in order to enjoy the power.

## Usage

### Creating a connection store

A store can be created by calling `datastar.wow.deacon/store` with an appropriate options map. There are two implementations included. A simple atom backed storage as well as one
powered by [Caffeine](https://github.com/ben-manes/caffeine). The Caffeine implementation is great for automatically evicting connections after a maxium size is reached or after
a period of inactivity.

``` clojure
(require '[datastar.wow.deacon :as d*conn])

;;; A new atom backed connection storage
(d*conn/store {:type :atom})

;;; Create an atom backed storage with an existing atom
(d*conn/store {:type :atom :atom (atom {})})

;;; Create a Caffeine backed connection storage
;;; All keys other than :type are optional (defaults shown)
(d*conn/store {:type :caffeine
               :idle-ms (* 10 60 1000)
			   :maximum-size 10000})
			   
;;; A caffeine backed store can be created with a :cache key overriding all other settings
(d*conn/store {:type :caffeine
               :cache (create-a-caffeine-cache-somehow)})
			   
;;; Working with stores 

(def store (d*conn/store {:type :atom)))

;;; Look up a connection by key
(d*conn/connection store :my-key)

;;; List all keys in store
(d*conn/list-keys store)
```

### Installing the interceptor

Use `datastar.wow.deacon/registry` in `:datastar.wow/registries`.

``` clojure
(require '[datastar.wow :as d*])
(require '[datastar.wow.deacon :as d*conn])
(require '[starfederation.datastar.clojure.adapter.http-kit :as hk])

(def store (d*conn/store {:type :atom}))

(def middleware
  "A middleware that gives the power"
  (d*/with-datastar hk/->sse-response {::d*/registries [[d*conn/registry store]]}))
  
;;; OR without a predefined store

(def middleware
  "A middleware that gives the power"
  (d*/with-datastar hk/->sse-response {::d*/registries [d*conn/registry]}))
```

See [datastar.wow docs](https://github.com/brianium/datastar.wow?tab=readme-ov-file#extending) on extending via `:datastar.wow/registries`.

Once the interceptor has been added, datstar.wow handlers can contain a `:datastar.wow.deacon/key` key in the response indicating the connection should be stored:

``` clojure
{::d*conn/key ::counter   ;;; unique connection key signals deacon to store
 ::d*/with-open-sse? false
 ::d*/fx [[::subscribe ::index]
          [::start-timer]]}
```

The key can be any type that would be a valid key in a Clojure map.

Any handler using the same key will re-use the stored connection.

``` clojure
(defn jump
  "Reference an explicit connection scoped by the results of :id-fn"
  [_]
  {::d*conn/key ::counter
   :🚀 [[::d*/patch-signals (swap! *state update :counter #(+ % 10))]]})
```

The `:datastar.wow.deacon/key` can contain any value that would be appropriate as a key in a Clojure map

## Options

The second argument to `datastar.wow.deacon/registry` is an options map that can be used to customize behavior.

| key         | description                                                                                                                                                        |
| ------------| ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `:id-fn`    | A function that is given the dispatch context and is expected to return a unique id for the session. IDs are used to scope connection keys to a particular context |
| `:on-purge` | Function (arity 2) called with context and the storage key. Called when a :datastar.wow/sse-closed effect is dispatched.                                           |

### Note on ids and `:id-fn`

Connection keys are always scoped to a unique session ID. This id defaults to `:datastar.wow.deacon/id`. However, it is useful to be able to store a connection key per session/user.
The `:id-fn` is the way to support this. The dispatch context contains useful information (like the request) for constructing such a key.

``` clojure
(require '[datastar.wow.deacon :as d*conn])

(def store (d*conn/store {:type :atom}))

(defn user-id
  "scope connection keys to individual users"
  [{{:keys [request]} :system}]
  (:user-id request))

(d*conn/registry store {:id-fn user-id})

;;; Fetching a connection with the default scope
(d*conn/connection store [::d*conn/id ::counter])

;;; Fetching a connection with a scope provided by :id-fn
(d*conn/connection store [(:user-id request) ::counter])
```

## Extending

A connection store just needs to implement `datastar.wow.deacon.protocols/ConnectionStore`. It is helpful, but not required, to also extend the `datastar.wow.deacon/store` multimethod.

``` clojure
(require '[my.store :refer [make-store]])
(require '[datastar.wow.deacon :as d*conn])

(defmethod d*conn/store :my-store [opts]
  (make-store opts))
  
(def store (d*conn/store {:type :my-store :opt1 1 :opt2 2}))
```

## Demo

See the [demo](dev/src/demo) namespace for a demo reitit application using datastar.wow with deacon. The [tests](test/src/datastar/wow/deacon_test.clj) are also a great resource for seeing things in action.

``` bash
$ clj -A:dev
user => (dev) ;;; after this hit localhost:3000
```
