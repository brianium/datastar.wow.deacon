(ns demo.app
  (:require [charred.api :as json]
            [datastar.wow :as d*]
            [datastar.wow.deacon :as d*conn]
            [demo.server :as demo.server]
            [dev.onionpancakes.chassis.core :as c]
            [dev.onionpancakes.chassis.compiler :as cc]
            [integrant.core :as ig]
            [reitit.coercion.malli :as co]
            [reitit.core :as r]
            [reitit.ring :as rr]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.parameters :as rmp]
            [starfederation.datastar.clojure.adapter.http-kit :as hk]
            [starfederation.datastar.clojure.adapter.http-kit2 :as hk2]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Demo components for integrant
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn connections
  "Connection store component"
  [deps]
  (d*conn/store deps))

(defonce *state
  (atom
   {:running  false
    :counter  0}))

(def effects
  {::subscribe
   (fn [{:keys [dispatch]} _ k]
     (add-watch
      *state k
      (fn [_ _ _ state]
        (dispatch [[::d*/patch-signals state]]))))
   ::start-timer
   (fn [_ _]
     (when-not (:running @*state)
       (swap! *state assoc :running true)
       (while (:running @*state)
         (swap! *state update :counter inc)
         (Thread/sleep 1000))))})

(defn update-nexus
  "Adds some application specific effects to demonstrate reusing connections.

   Effects:

   - ::subscribe - adds a watch to the atom to update signals. currently only supports a start then refresh scenario or start then open new tab scenario
   - ::start-timer - increments state every second and dispatches the changes"
  [n]
  (update n :nexus/effects merge effects))

(defn with-datastar
  "datastar.wow/with-datastar component so we can tweak config in the demo app. If using
   :httpkit2 be sure to include the start-responding-middleware from the offical sdk."
  [{:keys [type store]
    :or {type :httpkit}
    :as deps}]
  (d*/with-datastar
    (case type
      :httpkit  hk/->sse-response
      :httpkit2 hk2/->sse-response)
    (-> deps
        (dissoc :type :store)
        (assoc  :datastar.wow/update-nexus (comp (d*conn/update-nexus store) ;;; we can comp update-nexus functions - so here we are adding connection storage via deacon and some demo specific effects
                                                 update-nexus)))))

(defn handler
  [{:keys [router middleware]}]
  (rr/ring-handler
   router
   (rr/routes
    (rr/create-resource-handler {:path "/"})
    (rr/create-default-handler))
   {:middleware middleware}))

(defn router
  [{:keys [routes middleware]
    :or   {middleware []}}]
  (let [middleware (into [rmp/parameters-middleware
                          rrc/coerce-request-middleware] middleware)]
    (rr/router routes {:data {:coercion co/coercion
                              :middleware middleware}})))

(defn server
  [{:keys [type]
    :or {type :httpkit}
    :as deps}]
  (case type
    :httpkit (demo.server/httpkit-server (dissoc deps :type))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers and routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod c/resolve-alias ::counter
  [_ attrs _]
  (cc/compile
   [:p (merge
        attrs
        {:class ["text-white text-7xl my-4"]})]))

(defn app
  [running?]
  (cc/compile
   [c/doctype-html5
    [:html {:class "bg-slate-900 text-white text-lg" :lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "Datastar! Wow!"]
      [:script {:src d*/CDN-url :type "module"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"}]]
     [:body (cond-> {:class ["p-8"]
                     :data-signals (json/write-json-str @*state)}
              running? (assoc :data-on-load (d*/sse-get "/subscribe")))
      [:div {:class ["mx-auto max-w-7xl sm:px-6 lg:px-8"]}
       [:fieldset {:class "text-center"}
        [:legend "Demo"]
        [:div {:class "flex flex-col items-center"}
         [::counter#counter {:data-text "$counter"}]
         [:div {:class "flex gap-x-2"}
          [:button {:class "bg-green-500 p-2 cursor-pointer disabled:bg-green-300"
                    :data-on-click (d*/sse-put "/")
                    :data-attr-disabled "$running"} "start timer"]
          [:button {:class "bg-orange-500 p-2 cursor-pointer disabled:bg-orange-300"
                    :data-on-click (d*/sse-post "/jump")
                    :data-attr-disabled "!$running"} "add ten"]
          [:button {:class "bg-red-500 p-2 cursor-pointer disabled:bg-red-300"
                    :data-on-click (d*/sse-delete "/")
                    :data-attr-disabled "!$running"} "stop timer"]]]]]]]]))

(defn index
  "Renders the initial page and handles app state management (reset and starting the timer). Loading
   the index with a running timer will subscribe to those changes"
  [{:keys [request-method]}]
  (case request-method
    :get (let [{:keys [running]} @*state]
           {:body (app running)})
    :delete {:🚀 [[::d*/patch-signals (reset! *state {:running false :counter 0})]]}
    :put {::d*conn/name ::counter
          ::d*/with-open-sse? false
          :🚀 [[::subscribe ::index]
               [::start-timer]]}))

(defn jump
  "Adds a whopping 10 to the counter state - using the same connection established via index"
  [{{{:keys [store]} :data} ::r/match}]
  {::d*/connection (d*conn/connection store [::d*conn/id ::counter])
   :🚀 [[::d*/patch-signals (swap! *state update :counter #(+ % 10))]]})

(defn subscribe
  "Subscribe to state changes on a new connection"
  [_]
  {:🚀 [[::subscribe ::subscribe]]})

(def routes
  ["" {:store (ig/ref ::connections)}
   ["/" {:name   ::index
         :get    index
         :delete index
         :put    index}]
   ["/jump"
    {:name ::jump
     :post jump}]
   ["/subscribe"
    {:name ::subscribe
     :get  subscribe}]])
