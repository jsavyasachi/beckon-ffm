(ns beckon-ffm-test
  "Runs beckon's behavioral spec against whichever FFM backend this platform
  selects (signalfd on Linux, kqueue on macOS). project.clj sets
  -Dbeckon.signal.backend=ffm and --enable-native-access."
  (:require [clojure.test :refer :all]
            [beckon :as beckon])
  (:import (com.hypirion.beckon SignalRegistererHelper)
           (java.lang.foreign FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment
                              ValueLayout)))

(def ^:private signal-fn
  (delay
    (let [linker (Linker/nativeLinker)
          libc   (.defaultLookup linker)]
      (.downcallHandle
       linker
       (.orElseThrow (.find libc "signal"))
       (FunctionDescriptor/of
        ValueLayout/ADDRESS
        (into-array MemoryLayout [ValueLayout/JAVA_INT ValueLayout/ADDRESS]))
       (make-array Linker$Option 0)))))

(defn- set-native-disposition [signo handler]
  (-> ^java.lang.invoke.MethodHandle @signal-fn
      (.invokeWithArguments
       (object-array [(int signo) (MemorySegment/ofAddress (long handler))]))
      ^MemorySegment
      (.address)))

(use-fixtures :each (fn [run] (try (run) (finally (beckon/reinit-all!)))))

(deftest ffm-backend-is-active
  (testing "an FFM backend loaded for this platform"
    (is (contains? #{"FfmSignalfdBackend" "FfmKqueueBackend"}
                   (SignalRegistererHelper/backendName)))))

(deftest signal-atom-identity
  (testing "the same signal name yields the identical atom"
    (is (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR2"))))
  (testing "different signals yield different atoms"
    (is (not (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "WINCH"))))))

(deftest handler-runs-on-raise
  (testing "a handler set in the atom is invoked when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR2") [(fn [] (deliver ran true))])
      (beckon/raise! "USR2")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "every Runnable in the collection is invoked on a single raise"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR2") [bump bump bump])
      (beckon/raise! "USR2")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "raising with no handlers installed does not throw"
    (reset! (beckon/signal-atom "USR2") [])
    (is (nil? (beckon/raise! "USR2")))))

(deftest reset-restores-prior-native-disposition
  (testing "reset restores the disposition installed before registration"
    (let [signo (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName)) 30 10)
          prior (set-native-disposition signo 1)]
      (try
        (reset! (beckon/signal-atom "USR1") [identity])
        (beckon/reinit-all!)
        (is (= 1 (set-native-disposition signo 1)))
        (finally
          (set-native-disposition signo prior))))))
