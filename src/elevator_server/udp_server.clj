(ns elevator-server.udp-server
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [byte-streams :as bs]
    [clojure.core.match :refer [match]]))

(defn start-server
  "start an udp server at `port`"
  [port]
  ;; server is a manifold.deferred
  ;; need to use (deref) to convert it to manifold.stream
  (let [server (udp/socket {:port port})]
    server))

(defn raw-msg->msg
  "convert raw msg received by `manifold.stream` to
  {:host, :port, :message, :vertor, :string}"
  [msg]
  (let [addr (-> msg (:sender) (bean) (:address) (bean) (:hostAddress))
        port (-> msg (:sender) (bean) (:port))
        vector-msg (-> msg (:message) (vec))
        string-msg (bs/to-string (:message msg))]
    {:host    addr
     :port    port
     :message (:message msg)
     :vector  vector-msg
     :string  string-msg}))

; (send-back! @server @global-msg (byte-array [0x01 0x02]))
(defn send-back!
  "send msg to the sender of recv-msg by server
   @param `server` a `aleph.udp/socket` or any `manifold.stream`
   @param `recv-msg` raw msg received by `manifold.stream`
   @param `msg` string or array-bytes"
  [server raw-msg msg]
  (let [converted (raw-msg->msg raw-msg)]
    (s/put! server {:host    (:host converted)
                    :port    (:port converted)
                    :message msg})))

(defn hex->str
  "convert hex number to human-readable string"
  [x] (format "0x%02x" x))

;; 0 to 255. 256 is exclusive
(defn rand-hex-arr
  "make a hex array of length `n`"
  [n] (take n (repeatedly #(rand-int 256))))

(defn gen-msg
  "`recv-msg` raw msg received by `manifold.stream`. return byte-array"
  [recv-msg]
  ;; msg is a vector of bytes
  (let [msg (vec (:message (raw-msg->msg recv-msg)))
        head (first msg)
        heq #(= head (unchecked-byte %1))]
    (cond
      ;; See https://clojuredocs.org/clojure.core/unchecked-byte
      ;; (byte 0x80) is illegal, because 0x80 = 128
      ;; but byte in clojure is [-128, 128)
      ;; (byte -128) = 0x80 wired!
      (heq 0x70) (byte-array (vec (concat [0x70] (rand-hex-arr 16))))
      (heq 0x78) (byte-array [0x78 0x00])
      (heq 0x80) (byte-array (vec (concat [0x80 0x00] (rand-hex-arr 4))))
      :else (byte-array [0x70 0x01]))))

;; make a stream pipeline
(defn msg-recv
  [server]
  (s/map raw-msg->msg @server))

(def global-msg
  "a reference ready to be passed to `start-handle-msg`
  init value is nil
  expect to store the latest message received by server"
  (ref nil))

(defn start-handle-msg
  "`global-ref` is a reference of global message
   any new message will be written to it"
  [server global-ref]
  (s/consume (fn [m]
               (dosync (alter global-ref (constantly m)))
               (send-back! @server m (gen-msg m))) @server))

; (def server (start-server server-port))
