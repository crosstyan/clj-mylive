(ns elevator-server.utils.udp
  ;; https://stackoverflow.com/questions/14610957/how-to-rename-using-ns-require-refer/27122360#27122360
  ;(:refer-clojure :rename {udp-server server})
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as ms]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [byte-streams :as bs]
    [clojure.core.match :refer [match]]
    [monger.collection :as mc]
    [elevator-server.global :refer [db udp-server devices]]
    [byte-streams :as bs]
    [spec-tools.data-spec :as ds]
    [octet.core :as buf]
    [clojure.core.match :refer [match]])
  (:import
    (com.google.common.primitives Ints UnsignedInts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))

;(do (swap! sMsgType (constantly {}))
;    (doseq
;      [k (keys MsgType)]
;      (let [v (k MsgType)]
;        (swap! sMsgType #(assoc % k (unchecked-byte v))))))

(def MsgType {:INIT        0x70
              :RTMP_EMERG  0x77
              :RTMP_STREAM 0x75
              :HEARTBEAT   0x64})

(def ErrCode {:OK   0xff
              :BUSY 0x01
              :ERR  0x00})

;; http://funcool.github.io/octet/latest/
(def MsgSpec {:INIT_CLIENT        (buf/spec buf/ubyte buf/uint16) ;; type id
              :INIT_SERVER        (buf/spec buf/ubyte buf/uint32) ;; type hash
              :INIT_ERROR         (buf/spec buf/ubyte buf/ubyte) ;; type hash
              :RTMP_EMERG_CLIENT  (buf/spec buf/ubyte buf/uint32) ;; type hash
              :RTMP_EMERG_SERVER  (buf/spec buf/ubyte buf/uint32 buf/uint16) ;; type hash chn
              :RTMP_STREAM_SERVER (buf/spec buf/ubyte buf/uint32 buf/uint16) ;; type hash chn
              :RTMP_STREAM_CLIENT (buf/spec buf/ubyte buf/uint32 buf/ubyte) ;; type hash err
              :HEARTBEAT          (buf/spec buf/ubyte buf/uint32) ;; type hash
              })

(defn map-value [f map]
  (reduce (fn [m k] (assoc m k (f (k MsgType))))
          {} (keys map)))

(def sMsgType (map-value unchecked-byte MsgType))

(def sErrCode (map-value unchecked-byte ErrCode))

(defn short-to-bytes-array
  "Convert a short (int16) to a byte array.
  equivalent to ByteBuffer.allocate(2).putShort(value).array()"
  [x] (.. ByteBuffer (allocate 2) (putShort x) (array)))

;; https://guava.dev/releases/22.0/api/docs/com/google/common/primitives/Ints.html
(defn int-to-bytes-array
  "Convert an integer (int32) to a byte array.
  equivalent to ByteBuffer.allocate(4).putInt(value).array()"
  [x] (. Ints toByteArray x))

(defn concat-bytes-array [xs & yss]
  (let [stream (ByteArrayOutputStream.)]
    (.write stream xs)
    (run! (fn [ys] (.write stream ys)) yss)
    (.toByteArray stream)))

(defn as-unsigned [x]
  "Interpret an int32 as unsigned."
  [x] (BigInteger. (. UnsignedInts toString x)))

(defn now-unix-time-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn rand-by-hash
  ([k salt]
   (hash [k (now-unix-time-seconds) salt]))
  ([k]
   (hash [k (now-unix-time-seconds) "salt"]))
  ([]
   (hash [(now-unix-time-seconds) "salt"])))

(defn hex->str
  "convert hex number to human-readable string"
  [x] (format "%02x" x))

(defn byte-array->str [ba]
  (str/join (map hex->str (vec ba))))

;; 0 to 255. 256 is exclusive
(defn rand-hex-arr
  "make a hex array of length `n`"
  [n] (take n (repeatedly #(rand-int 256))))

(defn rand-byte-array
  "make a hex array of length `n`"
  [n]
  (byte-array (map unchecked-byte (rand-hex-arr n))))

;(let [arr (vec (map unchecked-byte (rand-hex-arr 2)))
;      int16-ba (byte-array (concat [0 0] (assoc arr 0 (bit-or 0xc0 (nth arr 0)))))]
;  (. Ints fromByteArray (byte-array [0 0 0x12 0x34])))

(defn raw-msg->msg
  "convert raw msg received by `manifold.stream` to
  {:host, :port, :message, :vertor, :string}"
  [msg]
  (let [addr (-> msg (:sender) (bean) (:address) (bean) (:hostAddress))
        port (-> msg (:sender) (bean) (:port))]
    {:host    addr
     :port    port
     :message (:message msg)}))

(defn send-back!
  "send msg to the sender of recv-msg by server
   @param `server` a `aleph.udp/socket` or any `manifold.stream`
   @param `recv-msg` raw msg received by `manifold.stream`
   @param `msg` string or bytes-array"
  [server raw-msg msg]
  (let [stored-msg (raw-msg->msg raw-msg)]
    (ms/put! server {:host    (:host stored-msg)
                     :port    (:port stored-msg)
                     :message msg})))

(defn send!
  "send msg to the sender of recv-msg by server
   @param `server` a `aleph.udp/socket` or any `manifold.stream`
   @param `stored-msg` a map with {:host, :port, :message}
   @param `msg` string or bytes-array"
  [server stored-msg msg]
  (ms/put! server {:host    (:host stored-msg)
                   :port    (:port stored-msg)
                   :message msg}))

;; make a stream pipeline
(defn msg-recv
  [server]
  (ms/map raw-msg->msg @server))
