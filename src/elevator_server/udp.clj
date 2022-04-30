(ns elevator-server.udp
  ;; https://stackoverflow.com/questions/14610957/how-to-rename-using-ns-require-refer/27122360#27122360
  ;(:refer-clojure :rename {udp-server server})
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [clojure.core.match :refer [match]]
    [elevator-server.global :refer [db udp-server devices]]
    [byte-streams :as bs]
    [octet.core :as buf]
    [clojure.core.match :refer [match]]))

(def MsgType {:INIT        (unchecked-byte 0x70)
              :RTMP_EMERG  (unchecked-byte 0x77)
              :RTMP_STREAM (unchecked-byte 0x75)
              :HEARTBEAT   (unchecked-byte 0x64)})
(def ElemLen {:ID       2
              :HASH     4
              :RTMP_CHN 2})

(def ErrCode {:OK   (unchecked-byte 0xff)
              :BUSY (unchecked-byte 0x01)
              :ERR  (unchecked-byte 0x00)})

(def MsgSpec {:INIT_CLIENT (buf/spec buf/ubyte buf/uint16)  ;; type id
              :INIT_SERVER (buf/spec buf/ubyte buf/uint32)  ;; type hash
              :RTMP_EMERG_CLIENT (buf/spec buf/ubyte buf/uint32) ;; type hash
              :RTMP_EMERG_SERVER (buf/spec buf/ubyte buf/uint32 buf/uint16) ;; type hash chn
              :RTMP_STREAM_SERVER (buf/spec buf/ubyte buf/uint32 buf/uint16) ;; type hash chn
              :RTMP_STREAM_CLIENT (buf/spec buf/ubyte buf/uint32 buf/ubyte) ;; type hash err
              :HEARTBEAT (buf/spec buf/ubyte buf/uint32) ;; type hash
              })

;; http://funcool.github.io/octet/latest/

(defn raw-msg->msg
  "convert raw msg received by `manifold.stream` to
  {:host, :port, :message, :vertor, :string}"
  [msg]
  (let [addr (-> msg (:sender) (bean) (:address) (bean) (:hostAddress))
        port (-> msg (:sender) (bean) (:port))]
    {:host    addr
     :port    port
     :message (:message msg)}))

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
  (let [conv (raw-msg->msg recv-msg)
        msg  (:message conv)
        heq  #(= head (unchecked-byte %1))]
    (match [(first msg)]
           [(:INIT MsgType)] (let [id ]))
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

(defn app-handler [m]
  ;(dosync (alter global-msg (constantly m)))
  (send-back! @udp-server m (gen-msg m)))

(defn start []
  "start udp server"
  (s/consume #'app-handler @udp-server))
