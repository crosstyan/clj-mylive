(ns elevator-server.core
  (:require [elevator-server.udp-server :as u]
            [elevator-server.mylive :as mylive]
            [monger.core :as mg]
            [elevator-server.http :as http]))

;; prefer aleph
;; https://aleph.io/examples/literate.html#aleph.examples.http
;; https://cljdoc.org/d/http-kit/http-kit/2.6.0-alpha1/doc/readme

(defn init [] (let [udp-port 12345
                    http-api-port 3001
                    server (u/start udp-port)]
                (do
                  ;(u/start-handle-msg server u/global-msg)
                  ;(mylive/start "./mylive.yaml")
                  (http/start http-api-port))))

(init)
