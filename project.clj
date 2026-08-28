(defproject net.clojars.savya/beckon-ffm "0.5.0"
  :plugins [[lein-tools-deps "0.4.5"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files [:install :user :project]
                           :aliases [:test]}
  :jvm-opts ["-Dbeckon.signal.backend=ffm" "--enable-native-access=ALL-UNNAMED"])
