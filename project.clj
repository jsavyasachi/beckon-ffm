(defproject net.clojars.savya/beckon-ffm "0.1.1"
  :description "Experimental Foreign Function & Memory signal backends for beckon: Linux signalfd and macOS/BSD kqueue."
  :url "https://github.com/jsavyasachi/beckon-ffm"
  :license {:name "Eclipse Public License 1.0"
            :url "https://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [net.clojars.savya/beckon "0.4.0"]]
  :java-source-paths ["src/java"]
  ;; FFM (java.lang.foreign) is final in JDK 22; these backends require it.
  :javac-options ["-source" "22" "-target" "22"]
  ;; The whole point of this artifact is the FFM backend, so tests select it and
  ;; enable native access by default.
  :jvm-opts ["-Dbeckon.signal.backend=ffm" "--enable-native-access=ALL-UNNAMED"])
