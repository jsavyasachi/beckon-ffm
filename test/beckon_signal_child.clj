(ns beckon-signal-child
  (:gen-class)
  (:require [beckon :as beckon]))

(defn -main [signal]
  (reset! (beckon/signal-atom signal)
          [(fn [] (println "HANDLED") (flush))])
  (println "READY")
  (flush)
  (when (= "true" (System/getProperty "beckon.signal.debug"))
    (future
      (Thread/sleep 1000)
      (println "DEBUG"
               (->> (clojure.string/split-lines
                     (slurp "/proc/self/status"))
                    (filter #(or (.startsWith % "SigBlk:")
                                 (.startsWith % "SigPnd:")
                                 (.startsWith % "ShdPnd:")))
                    (clojure.string/join "|"))
               (->> (.listFiles (java.io.File. "/proc/self/fdinfo"))
                    (map slurp)
                    (filter #(.contains % "sig-mask:"))
                    (clojure.string/join "|"))
        (flush)))
  @(promise))
