(ns elevator-server.utils.core)

(defn nil?-or
  "return default if elem is nil. or return elem if not so"
  [elem default] (if (nil? elem) default elem))
