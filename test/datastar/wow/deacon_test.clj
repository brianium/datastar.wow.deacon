(ns datastar.wow.deacon-test
  (:require [clojure.test :refer [deftest is testing use-fixtures are]]
            [datastar.wow.deacon :as d*conn]
            [nexus.core :as nexus]))

(def *conns (atom {}))

(defn reset-refs
  [f]
  (reset! *conns {})
  (f))

(use-fixtures :each reset-refs)

(deftest atom-store
  (let [s (d*conn/store {:type :atom :atom *conns})]
    (testing "storing a connection"
      (let [conn (d*conn/store! s :test ::conn)]
        (is (and (= conn ::conn)
                 (= conn (@*conns :test))))))
    (testing "getting stored connections"
      (are [stored fetched] (= stored fetched)
        (d*conn/store! s :test ::conn) (d*conn/connection s :test)
        nil (d*conn/connection s :oh-no-doesnt-exist)))
    (testing "purging a connection"
      (d*conn/store! s :purge ::conn)
      (d*conn/purge! s :purge)
      (is (nil? (d*conn/connection s :purge))))))

(deftest caffeine-store
  (let [s (d*conn/store {:type :caffeine})]
    (testing "storing a connection"
      (let [conn (d*conn/store! s :test ::conn)]
        (is (= conn ::conn))))
    (testing "getting stored connections"
      (are [stored fetched] (= stored fetched)
        (d*conn/store! s :test ::conn) (d*conn/connection s :test)
        nil (d*conn/connection s :oh-no-doesnt-exist)))
    (testing "purging a connection"
      (d*conn/store! s :purge ::conn)
      (d*conn/purge! s :purge)
      (is (nil? (d*conn/connection s :purge))))
    (testing "exceeding maximum-size"
      (let [sz       5
            sleep-ms 50
            timeout  2000
            s        (d*conn/store {:type :caffeine :maximum-size sz})
            n        (* 3 sz)]
        (dotimes [n n]
          (d*conn/store! s [:test n] ::conn))
        ;;; Not going to care too much about testing Caffeine's claims, just want to confirm
        ;;; that the size DOES eventually decrease when max is exceeded
        (loop [time 0]
          (let [[_ size] (re-find #"size=([\d]+)" (str s))
                actual   (parse-long size)]
            (if (>= time timeout)
              (throw (Exception. "Timeout"))
              (if (< actual n)
                (is (true? true))
                (do (Thread/sleep sleep-ms)
                    (recur (+ time sleep-ms)))))))))
    (testing "exceeding idle-ms"
      (let [sleep-ms 50
            s        (d*conn/store {:type :caffeine :idle-ms (- sleep-ms 10)})]
        (d*conn/store! s :idle ::conn)
        (Thread/sleep sleep-ms)
        (is (nil? (d*conn/connection s :idle)))))))

(defn dispatch
  "Simulate a datastar.wow dispatch with the given store"
  ([store fx]
   (dispatch store fx {}))
  ([store fx opts]
   (dispatch store fx opts false))
  ([store fx opts with-open-sse?]
   (let [update-nexus (d*conn/update-nexus store opts)
         sse      (atom ::test-conn)
         request  {:session-id ::test-id}
         response {::d*conn/key ::test-name}
         dispatch-data
         {:datastar.wow/response response
          :datastar.wow/request  request
          :datastar.wow/with-open-sse? with-open-sse?}
         n (update-nexus
            {:nexus/effects
             {:datastar.wow/sse-closed (constantly ::closed)
              :datastar.wow/send (constantly ::send)}})]
     (fn [& [sse-override]]
       (nexus/dispatch n {:sse (or sse-override sse) :request request} dispatch-data fx)))))

(deftest dispatch-with-connection-interceptor
  (let [store (d*conn/store {:type :atom :atom *conns})]
    (testing "default storage"
      ((dispatch store [[:datastar.wow/send :arg1 :arg2]]))
      (is (some? (d*conn/connection store [::d*conn/id ::test-name]))))
    (testing "using an id-fn"
      (let [id-fn (fn [{{:keys [request]} :system}]
                    (:session-id request))]
        ((dispatch store [[:datastar.wow/send :arg1 :arg2]] {:id-fn id-fn}))
        (is (some? (d*conn/connection store [::test-id ::test-name])))))
    (testing "purging on sse-closed"
      ((dispatch store [[:datastar.wow/send :arg1 :arg2]]))
      ((dispatch store [[:datastar.wow/sse-closed]]))
      (is (nil? (d*conn/connection store [::d*conn/id]))))
    (testing "using an on-purge fn"
      (let [*purge   (atom nil)
            on-purge (fn [{{:keys [request]} :system}]
                       (reset! *purge (:session-id request)))]
        ((dispatch store [[:datastar.wow/send :arg1 :arg2]]))
        ((dispatch store [[:datastar.wow/sse-closed]] {:on-purge on-purge}))
        (is (= ::test-id @*purge))))))
