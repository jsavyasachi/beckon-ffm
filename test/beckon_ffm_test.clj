(ns beckon-ffm-test
  "Tests beckon's behavior with the FFM backend that this platform selects:
  signalfd on Linux and kqueue on macOS. project.clj sets
  -Dbeckon.signal.backend=ffm and --enable-native-access."
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [beckon :as beckon]
            [beckon-ffm :as beckon-ffm])
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

(defn- backend-instance []
  (let [backend-class (Class/forName
                       (str "com.hypirion.beckon."
                            (SignalRegistererHelper/backendName)))]
    (.newInstance (.getDeclaredConstructor backend-class
                                             (make-array Class 0))
                  (object-array 0))))

(defn- dispatcher-thread [backend]
  (let [field (.getDeclaredField (.getClass backend) "dispatcherThread")]
    (.setAccessible field true)
    (.get field backend)))

(defn- backend-signal-names []
  (let [backend-class (Class/forName
                       (str "com.hypirion.beckon."
                            (SignalRegistererHelper/backendName)))
        field (.getDeclaredField backend-class "SIGNOS")]
    (.setAccessible field true)
    (set (keys (.get field nil)))))

(defn- invoke-static [class-name method-name args]
  (let [klass (Class/forName class-name)
        method (.getDeclaredMethod klass method-name
                                   (into-array Class
                                               (map #(if (integer? %)
                                                       Integer/TYPE
                                                       (class %))
                                                    args)))]
    (.setAccessible method true)
    (try
      (.invoke method nil (object-array (map #(if (integer? %) (int %) %)
                                             args)))
      (catch InvocationTargetException e
        (.getMessage (.getCause e))))))

(use-fixtures :each (fn [run] (try (run) (finally (beckon/reinit-all!)))))

(deftest ffm-backend-is-active
  (testing "this platform loads an FFM backend"
    (is (contains? #{"FfmSignalfdBackend" "FfmKqueueBackend"}
                   (SignalRegistererHelper/backendName)))))

(deftest backend-signal-map-covers-catchable-platform-signals
  (let [expected (if (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName))
                   #{"HUP" "INT" "QUIT" "USR1" "TERM" "CHLD" "CONT"
                     "TSTP" "WINCH" "ALRM" "TTIN" "TTOU" "URG" "XCPU"
                     "VTALRM" "PROF" "IO" "PWR"}
                   #{"HUP" "INT" "QUIT" "ILL" "TRAP" "ABRT" "EMT"
                     "FPE" "BUS" "SEGV" "SYS" "PIPE" "ALRM" "TERM"
                     "URG" "TSTP" "CONT" "CHLD" "TTIN" "TTOU" "IO"
                     "XCPU" "XFSZ" "VTALRM" "PROF" "WINCH" "INFO" "USR1"})]
    (is (= expected (backend-signal-names))
        "the backend map must include every safe, catchable platform signal")))

(deftest linux-abi-validation-reports-the-actual-size
  (testing "a Linux ABI size mismatch names the struct, expected size, actual size, and platform"
    (let [message (invoke-static "com.hypirion.beckon.LinuxAbi" "validate"
                                ["Linux" "x86_64" 64 128 8])]
      (is (= "expected sigset_t of 128 bytes, got 64 on Linux/x86_64"
             message)))))

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
    (is (identical? (beckon/signal-atom "USR1") (beckon/signal-atom "USR1"))))
  (testing "different signals give different atoms"
    (is (not (identical? (beckon/signal-atom "USR1") (beckon/signal-atom "WINCH"))))))

(deftest handler-runs-on-raise
  (testing "a handler in the atom runs when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR1") [(fn [] (deliver ran true))])
      (beckon/raise! "USR1")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "one raise invokes every Runnable in the collection"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR1") [bump bump bump])
      (beckon/raise! "USR1")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "a raise with no handlers does not throw"
    (reset! (beckon/signal-atom "USR1") [])
    (is (nil? (beckon/raise! "USR1")))))

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
  (let [expected (if (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName)) 0 1)
        prior (set-native-disposition 15 1)]
    (try
      (reset! (beckon/signal-atom "TERM") [identity])
      (is (= expected (set-native-disposition 15 expected))
          "managed registration must use the backend's safe default disposition")
      (finally
        (set-native-disposition 15 prior)))))

(deftest jvm-reserved-signal-is-rejected
  (let [backend (backend-instance)]
    (try
      (is (thrown-with-msg? IllegalArgumentException
                            #"Unsupported signal.*USR2"
                            (.register backend "USR2" [identity])))
      (finally
        (beckon-ffm/close! backend)))))

(deftest backend-close-stops-dispatcher-restores-dispositions-and-releases-resources
  (let [signo (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName)) 30 10)
        prior (set-native-disposition signo 1)
        backend (backend-instance)]
    (try
      (.register backend "USR1" [identity])
      (beckon-ffm/close! backend)
      (.join (dispatcher-thread backend) 2000)
      (is (not (.isAlive (dispatcher-thread backend)))
          "close must terminate the dispatcher thread")
      (is (= 1 (set-native-disposition signo 1))
          "close must restore the disposition from before registration")
      (is (nil? (beckon-ffm/close! backend)) "close must be idempotent")
      (finally
        (set-native-disposition signo prior)))))

(deftest backend-can-be-reinitialized-after-close
  (let [backend (backend-instance)
        fresh (backend-instance)
        ran (promise)]
    (try
      (beckon-ffm/close! backend)
      (.register fresh "USR1" [(fn [] (deliver ran true))])
      (.raise fresh "USR1")
      (is (true? (deref ran 2000 :timed-out)))
      (finally
        (beckon-ffm/close! backend)
        (beckon-ffm/close! fresh)))))

(deftest signalfd-dispatcher-survives-repeated-thread-directed-raises
  (if (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName))
    (let [backend (backend-instance)
          hits (atom 0)]
      (try
        (.register backend "USR1" [(fn [] (swap! hits inc))])
        (dotimes [_ 8]
          (.raise backend "USR1"))
        (is (= 8 @hits)
            "every thread-directed signal must be consumed by signalfd")
        (is (.isAlive (dispatcher-thread backend))
            "the poll-based dispatcher must remain alive after a raise")
        (finally
          (beckon-ffm/close! backend))))
    (is true "Linux signalfd-only regression test")))

(defn- linux? [] (= "Linux" (System/getProperty "os.name")))
(defn- linux? [] (= "Linux" (System/getProperty "os.name")))
(defn- macos? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- launcher-path []
  (str (System/getProperty "user.dir") "/target/beckon-signal-launcher"))

(defn- child-command []
  ["java" "-Xrs" "--enable-native-access=ALL-UNNAMED"
   "-Dbeckon.signal.backend=ffm"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "beckon-signal-child" "TERM"])

(defn- wait-for-line
  "Reads lines from reader until expected is seen, EOF, or timeout-ms elapses.
  Runs the blocking read on a daemon future so a hung subprocess can never
  wedge the test suite indefinitely."
  [^java.io.BufferedReader reader expected timeout-ms]
  (let [f (future
            (loop [line (.readLine reader)]
              (cond
                (= line expected) true
                (nil? line) false
                :else (recur (.readLine reader)))))
        result (deref f timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do (future-cancel f) false)
      result)))

(deftest external-term-works-through-prelaunch-shim
  (if (linux?)
    (let [process (-> (ProcessBuilder.
                       (into-array String
                                   (concat [(launcher-path) "--signals" "TERM" "--"]
                                           (child-command))))
                      (.redirectErrorStream true)
                      (.start))
          reader (io/reader (.getInputStream process))]
      (try
        (is (wait-for-line reader "READY" 5000))
        (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
        (is (wait-for-line reader "HANDLED" 5000))
        (is (.isAlive process))
        (finally
          (.destroyForcibly process))))
    (is true "Linux-only subprocess test")))

(deftest external-term-works-on-kqueue
  (if (macos?)
    (let [process (-> (ProcessBuilder. (into-array String (child-command)))
                      (.redirectErrorStream true)
                      (.start))
          reader (io/reader (.getInputStream process))]
      (try
        (is (wait-for-line reader "READY" 5000))
        (let [killer (-> (ProcessBuilder.
                          (into-array String ["kill" "-TERM" (str (.pid process))]))
                         (.start))]
          (.waitFor killer 5 java.util.concurrent.TimeUnit/SECONDS))
        (is (wait-for-line reader "HANDLED" 5000))
        (is (.isAlive process)
            "kqueue must handle an external TERM without terminating the child")
        (finally
          (.destroyForcibly process))))
    (is true "macOS-only subprocess test")))

(deftest external-term-without-shim-retains-known-limitation
  (if (linux?)
    (let [process (-> (ProcessBuilder.
                       (into-array String (child-command)))
                      (.redirectErrorStream true)
                      (.start))
          reader (io/reader (.getInputStream process))]
      (try
        (is (wait-for-line reader "READY" 5000))
        (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
        (let [exited? (.waitFor process 15 java.util.concurrent.TimeUnit/SECONDS)]
          (is exited? "process should exit under the default (unmasked) disposition")
          (when exited? (is (not= 0 (.exitValue process)))))
        (finally
          (.destroyForcibly process))))
    (is true "Linux-only subprocess test")))
