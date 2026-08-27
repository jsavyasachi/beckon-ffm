(ns beckon-signal-child
  (:gen-class)
  (:require [beckon :as beckon]))

(defn -main [signal]
  (reset! (beckon/signal-atom signal)
          [(fn [] (println "HANDLED") (flush))])
  (println "READY")
  (flush)
  @(promise))
