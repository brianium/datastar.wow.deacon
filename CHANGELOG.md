# Change Log

## [1.3.0] - 2025-09-18

1.3.0 will only work with datastar.wow version [1.0.0-RC1-wow-2](https://clojars.org/com.github.brianium/datastar.wow) and higher.

### Changed

The `:datastar.wow/connection` key is no longer used. `:datastar.wow.deacon/key` is used for writing AND reading connections.

Previously:

```clojure
(defn handler-1 [_]
  {::d*conn/key ::counter
   ::d*/with-open-sse? false
   ::d*/fx [[::subscribe ::index]
            [::start-timer]]})
			
(defn handler-2 [_]
  {::d*/connection ::counter})
```

Now:

```clojure
(defn handler-1 [_]
  {::d*conn/key ::counter
   ::d*/with-open-sse? false
   ::d*/fx [[::subscribe ::index]
            [::start-timer]]})
			
(defn handler-2 [_]
  {::d*conn/key ::counter})
```
