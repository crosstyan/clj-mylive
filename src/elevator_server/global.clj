(ns elevator-server.global
  (:require
    [monger.core :as mg]
    [aleph.udp :as udp]
    [mount.core :refer [defstate]]))

(defn start-udp
  "start an udp server at `port`"
  [port]
  ;; server is a manifold.deferred
  ;; need to use (deref) to convert it to manifold.stream
  (let [server (udp/socket {:port port})]
    server))

(def config {:http-api-port 3001
             :udp-port 12345})

(defstate conn
          "monger connection"
          :start (mg/connect)
          :stop  (mg/disconnect conn))

(defstate db :start (mg/get-db conn "app"))

(defstate udp-server :start (start-udp (:udp-port config)))

(defstate devices :start (atom {}))
