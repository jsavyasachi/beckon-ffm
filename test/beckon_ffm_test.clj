(ns beckon-ffm-test
  "Tests beckon's behavior with the FFM backend that this platform selects:
  signalfd on Linux and kqueue on macOS. project.clj sets
  -Dbeckon.signal.backend=ffm and --enable-native-access."
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [beckon :as beckon])
  (:import (com.hypirion.beckon SignalRegistererHelper)
           (java.lang.reflect InvocationTargetException)
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
  (testing "this platform loads an FFM backend"
    (is (contains? #{"FfmSignalfdBackend" "FfmKqueueBackend"}
                   (SignalRegistererHelper/backendName)))))

(deftest native-signal-failure-surfaces-return-and-errno
  (testing "a failed signal(2) disposition change is not silently accepted"
    (let [backend-class (Class/forName
                         (str "com.hypirion.beckon."
                              (SignalRegistererHelper/backendName)))
          backend       (.newInstance (.getDeclaredConstructor backend-class
                                                               (make-array Class 0))
                                      (object-array 0))
          set-disposition (.getDeclaredMethod backend-class "setDisposition"
                                              (into-array Class [Integer/TYPE Long/TYPE]))]
      (.setAccessible set-disposition true)
      (try
        (.invoke set-disposition backend (object-array [(int 0) (long 0)]))
        (is false "signal(0, SIG_DFL) should throw instead of returning SIG_ERR")
        (catch InvocationTargetException e
          (let [failure (.getCause e)]
            (is (instance? clojure.lang.ExceptionInfo failure))
            (is (= -1 (:return (ex-data failure))))
            (is (integer? (:errno (ex-data failure))))))))))

(deftest signal-atom-identity
  (testing "the same signal name gives the same atom"
    (is (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "USR2"))))
  (testing "different signals give different atoms"
    (is (not (identical? (beckon/signal-atom "USR2") (beckon/signal-atom "WINCH"))))))

(deftest handler-runs-on-raise
  (testing "a handler in the atom runs when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR2") [(fn [] (deliver ran true))])
      (beckon/raise! "USR2")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "one raise invokes every Runnable in the collection"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR2") [bump bump bump])
      (beckon/raise! "USR2")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "a raise with no handlers does not throw"
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

(deftest registration-does-not-install-default-disposition
  (let [prior (set-native-disposition 15 1)]
    (try
      (reset! (beckon/signal-atom "TERM") [identity])
      (is (= 1 (set-native-disposition 15 1))
          "managed registration must leave SIG_IGN installed, never SIG_DFL")
      (finally
        (set-native-disposition 15 prior)))))

(defn- linux? [] (= "Linux" (System/getProperty "os.name")))

(defn- launcher-path []
  (str (System/getProperty "user.dir") "/target/beckon-signal-launcher"))

(defn- child-command []
  ["java" "-Xrs" "--enable-native-access=ALL-UNNAMED"
   "-Dbeckon.signal.backend=ffm"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "beckon-signal-child" "TERM"])

(defn- wait-for-line [^java.io.BufferedReader reader expected]
  (loop [line (.readLine reader)]
    (cond
      (= line expected) true
      (nil? line) false
      :else (recur (.readLine reader)))))

(deftest external-term-works-through-prelaunch-shim
  (if (linux?)
    (let [process (-> (ProcessBuilder.
                       (into-array String
                                   (concat [(launcher-path) "--signals" "TERM" "--"]
                                           (child-command))))
                      (.redirectErrorStream true)
                      (.start))
          reader (.bufferedReader (io/reader (.getInputStream process)))]
      (try
        (is (wait-for-line reader "READY"))
        (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
        (is (wait-for-line reader "HANDLED"))
        (is (.isAlive process))
        (finally
          (.destroyForcibly process))))
    (is true "Linux-only subprocess test")))

(deftest external-term-without-shim-retains-known-limitation
  (if (linux?)
    (let [process (-> (ProcessBuilder.
                       (into-array String (child-command)))
                      (.redirectErrorStream true)
                      (.start))
          reader (.bufferedReader (io/reader (.getInputStream process)))]
      (try
        (is (wait-for-line reader "READY"))
        (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
        (is (not= 0 (.waitFor process)))
        (finally
          (.destroyForcibly process))))
    (is true "Linux-only subprocess test")))
