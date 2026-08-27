(ns beckon-ffm
  "Lifecycle helpers for beckon-ffm's opt-in native signal backend.")

(defn- current-backend []
  (let [field (.getDeclaredField com.hypirion.beckon.SignalRegistererHelper
                                  "BACKEND")]
    (.setAccessible field true)
    (.get field nil)))

(defn close!
  "Stops and closes the current beckon backend.

  Use this before REPL reloads, test-suite teardown, or supervised process
  restarts when the JVM will continue running. An optional backend argument is
  useful when managing a backend constructed directly from Java."
  ([] (close! (current-backend)))
  ([backend]
   (clojure.lang.Reflector/invokeInstanceMethod backend "close"
                                                (object-array 0))))
