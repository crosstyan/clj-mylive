(ns elevator-server.core
  (:require [elevator-server.udp :as u]
            [elevator-server.global :refer [config]]
            [elevator-server.mylive :as mylive]
            [mount.core :as mnt :refer [defstate]]
            [elevator-server.http :as http]))

;; prefer aleph
;; https://aleph.io/examples/literate.html#aleph.examples.http
;; https://cljdoc.org/d/http-kit/http-kit/2.6.0-alpha1/doc/readme

(defn init [] (do (mnt/start)
                  (u/start)
                  (mylive/start)
                  (http/start (:http-api-port config))))

(init)
