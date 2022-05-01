(ns elevator-server.global
  (:require
    [monger.core :as mg]
    [aleph.udp :as udp]
    [manifold.bus :as bus]
    [manifold.stream :as s]
    [mount.core :refer [defstate]]
    [clojure.tools.logging :as log]))

(defn start-udp
  "start an udp server at `port`"
  [port]
  ;; server is a manifold.deferred
  ;; need to use (deref) to convert it to manifold.stream
  (let [server (udp/socket {:port port})]
    server))

(def config {:http-api-port 3001
             :udp-port      12345})

;; https://github.com/tolitius/mount/issues/77
(defstate conn
          "monger connection"
          :start (mg/connect)
          :stop (mg/disconnect conn))

(defstate db-udp :start (mg/get-db conn "app"))
(defstate db-http :start (mg/get-db conn "app"))

(defstate udp-server :start (do (log/infof "start udp server at %d", (:udp-port config))
                                (start-udp (:udp-port config)))
          :stop (s/close! udp-server))

(defstate app-bus :start (bus/event-bus)
          :stop (s/close! app-bus))

(defstate devices :start (atom {}))
