(ns elevator-server.global
  (:require
    [monger.core :as mg]
    [mount.core :refer [defstate]]))

(defstate conn
          "monger connection"
          :start (mg/connect)
          :stop  (mg/disconnect conn))

(defstate db :start (mg/get-db conn "app"))


