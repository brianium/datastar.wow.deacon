(ns datastar.wow.deacon-test
  (:require [clojure.test :refer [deftest is testing use-fixtures are]]
            [datastar.wow :as d*]
            [datastar.wow.deacon :as d*conn]))

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
                 (= conn (@*conns :test))
                 (= (d*conn/list-keys s) (list :test))))))
    (testing "getting stored connections"
      (are [stored fetched] (= stored fetched)
        (d*conn/store! s :test ::conn) (d*conn/connection s :test)
        nil (d*conn/connection s :oh-no-doesnt-exist)))
    (testing "purging a connection"
      (d*conn/store! s :purge ::conn)
      (d*conn/purge! s :purge)
      (is (nil? (d*conn/connection s :purge))))))

(deftest store-predicate
  (testing "recognizes valid stores"
    (is (true? (d*conn/store? (d*conn/store {:type :atom :atom *conns}))))
    (is (true? (d*conn/store? (d*conn/store {:type :caffeine})))))
  (testing "rejects non-stores"
    (are [x] (false? (d*conn/store? x))
      nil
      {}
      []
      ::not-a-store)))

(deftest caffeine-store
  (let [s (d*conn/store {:type :caffeine})]
    (testing "storing a connection"
      (let [conn (d*conn/store! s :test ::conn)]
        (is (and (= conn ::conn)
                 (= (d*conn/list-keys s) (list :test))))))
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
    (testing "exceeding duration-ms"
      (let [sleep-ms 50
            s        (d*conn/store {:type :caffeine :duration-ms (- sleep-ms 10)})]
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
   (let [defaults {::d*/effects
                   {::d*/connection (constantly nil)
                    ::d*/close-sse
                    (fn [{:keys [dispatch]} & _]
                      (dispatch [[:datastar.wow/sse-closed]]))
                    ::d*/sse-closed (constantly ::closed)
                    ::d*/send (constantly ::send)}}
         registry (d*conn/registry store opts)
         sse      ::test-conn
         request  {:session-id ::test-id}
         response {::d*conn/key ::test-name}
         dispatch-data
         {:datastar.wow/response response
          :datastar.wow/request  request
          :datastar.wow/with-open-sse? with-open-sse?}
         dispatch' (d*/dispatch {::d*/registries [defaults registry]})]
     (fn [& [sse-override]]
       (comment "This matches datastar.wow's dispatch strategy (separate dispatch for connection)")
       (dispatch' {:sse nil :request request} dispatch-data [[:datastar.wow/connection]])
       (dispatch' {:sse (or sse-override sse) :request request} dispatch-data (vec fx))))))

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
      ;;; sse-closed is dispatched by the datastar sdk's on-close handler
      ;;; we are simulating here in that, a close-sse will always be followed by an sse-closed
      ((dispatch store [[:datastar.wow/send :arg1 :arg2]
                        [:datastar.wow/close-sse]]))
      (is (nil? (d*conn/connection store [::d*conn/id ::test-name]))))
    (testing "purging when using an id-fn"
      (let [id-fn (fn [{{:keys [request]} :system}]
                    (:session-id request))]
        ((dispatch store [[:datastar.wow/send :arg1 :arg2]
                          [:datastar.wow/sse-closed]] {:id-fn id-fn}))
        (is (nil? (d*conn/connection store [::test-id ::test-name])))))
    (testing "using an on-purge fn"
      (let [*purge   (atom nil)
            on-purge (fn [{{:keys [request]} :system} k]
                       (reset! *purge {:session-id (:session-id request) :key k}))]
        ((dispatch store [[:datastar.wow/send :arg1 :arg2]]))
        ((dispatch store [[:datastar.wow/sse-closed]] {:on-purge on-purge}))
        (is (= {:session-id ::test-id :key [:datastar.wow.deacon/id ::test-name]} @*purge))))))
