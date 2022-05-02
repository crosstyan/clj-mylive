(ns elevator-server.utils.udp
  ;; https://stackoverflow.com/questions/14610957/how-to-rename-using-ns-require-refer/27122360#27122360
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as ms]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [byte-streams :as bs]
    [clojure.core.match :refer [match]]
    [monger.collection :as mc]
    [elevator-server.global :refer [db-udp udp-server devices] :rename {db-udp db}]
    [byte-streams :as bs]
    [clojure.spec.alpha :as s]
    [spec-tools.data-spec :as ds]
    [tick.core :as t]
    [octet.core :as buf]
    [clojure.core.match :refer [match]])
  (:import
    (com.google.common.primitives Ints UnsignedInts Shorts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))

(def ByteArray
  "ByteArray is the class name of a byte array."
  (class (byte-array [0x00 0xff])))

(defn byte-array? [ba]
  ;; create a byte array and get it type
  (instance? ByteArray ba))

(def stored-msg
  {:message       string?
   :port          integer?
   :host          string?
   (ds/opt :time) string?})

(def stored-msg-spec
  (ds/spec {:name ::stored-msg
            :spec stored-msg
            :time string?}))

(defn stored-msg? [msg]
  (s/valid? stored-msg-spec msg))

(defn raw-msg?
  [msg] (instance? aleph.udp.UdpPacket msg))

;; device info in global variables
;; not in database
(def device
  {:id              integer?
   :hash            integer?
   (ds/opt :name)   string?                                 ; name
   (ds/opt :e-chan) string?                                 ; hex representation of emergency channel
   :last-msg        stored-msg-spec
   (ds/opt :chan)   integer?})

(def device-spec
  (ds/spec {:name ::device
            :spec device}))

(def MsgType {:INIT        0x70
              :RTMP_EMERG  0x77
              :RTMP_STREAM 0x75
              :RTMP_STOP   0x76
              :HEARTBEAT   0x64})

(def ErrCode {:OK   0xff
              :BUSY 0x10
              :BUSY_EMERG 0x11
              :BUSY_STREAM 0x12
              :ERR  0x00})

;; http://funcool.github.io/octet/latest/
(def MsgSpec {:INIT_CLIENT        (buf/spec buf/byte buf/int16) ;; type id
              :INIT_SERVER        (buf/spec buf/byte buf/int32) ;; type hash
              :INIT_ERROR         (buf/spec buf/byte buf/byte) ;; type hash

              :RTMP_EMERG_CLIENT  (buf/spec buf/byte buf/int32) ;; type hash
              :RTMP_EMERG_SERVER  (buf/spec buf/byte buf/int32 buf/uint16) ;; type hash chn (note chn is unsigned)

              :RTMP_STREAM_SERVER (buf/spec buf/byte buf/int32 buf/uint16) ;; type hash chn (note chn is unsigned)
              :RTMP_STREAM_CLIENT (buf/spec buf/byte buf/int32 buf/uint16 buf/byte) ;; type hash err

              :RTMP_STOP_SERVER (buf/spec buf/byte buf/int32) ;; type hash
              :RTMP_STOP_CLIENT (buf/spec buf/byte buf/int32 buf/byte) ;; type hash err

              :HEARTBEAT          (buf/spec buf/byte buf/int32) ;; type hash
              })

(defn map-value [f map]
  (reduce (fn [m k] (assoc m k (f (k map))))
          {} (keys map)))

(def sMsgType (map-value unchecked-byte MsgType))

(def sErrCode (map-value unchecked-byte ErrCode))

(defn int16->to-bytes-array
  "Convert a short (int16) to a byte array.
  equivalent to ByteBuffer.allocate(2).putShort(value).array()"
  [x] (.. ByteBuffer (allocate 2) (putShort x) (array)))

;; https://guava.dev/releases/22.0/api/docs/com/google/common/primitives/Ints.html
(defn int32->bytes-array
  "Convert an integer (int32) to a byte array.
  equivalent to ByteBuffer.allocate(4).putInt(value).array()"
  [x] (. Ints toByteArray x))



(defn buf->byte-array [^ByteBuffer buf]
  (.array buf))

(defn byte-array->buf [arr]
  (. ByteBuffer wrap arr))

(defn concat-bytes-array [xs & yss]
  (let [stream (ByteArrayOutputStream.)]
    (.write stream xs)
    (run! (fn [ys] (.write stream ys)) yss)
    (.toByteArray stream)))

(defn as-unsigned
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

;; TODO: use protocol or multimethod
(defn hex->str
  "convert a byte to hex"
  [x] (format "%02x" x))

(defn byte-array->str
  "convert byte array to hex string"
  [ba] (str/join (map hex->str (vec ba))))

(defn int32->hex-str [x] (byte-array->str (int32->bytes-array x)))

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
        port (-> msg (:sender) (bean) (:port))
        timef (t/format :iso-offset-date-time (t/zoned-date-time))]
    {:host    addr
     :port    port
     :message (:message msg)
     :time    timef}))

(defn send-back!
  "send msg to the sender of recv-msg by server
   @param `server` a `aleph.udp/socket` or any `manifold.stream`
   @param `recv-msg` raw msg received by `manifold.stream` or a map follow `stored-msg-spec`
   @param `msg` string or bytes-array"
  [server recv-msg msg]
  (cond
    (stored-msg? recv-msg)
    (ms/put! server {:host    (:host recv-msg)
                     :port    (:port recv-msg)
                     :message msg})
    (raw-msg? recv-msg)
    (let [conv (raw-msg->msg recv-msg)]
      (ms/put! server {:host    (:host conv)
                       :port    (:port conv)
                       :message msg}))
    :else
    (throw (Exception.
             (format "send-back! expects a stored-msg or raw-msg
                      (aleph.udp.UdpPacket). Get %s" (class recv-msg))))))

(defn send!
  "send msg to the specified host and port
   @param `server` a `aleph.udp/socket` or any `manifold.stream`
   @param `msg` string or bytes-array
   @param `host` hostname or ip
   @param `port` port number"
  [server msg host port]
  (ms/put! server {:host    host
                   :port    port
                   :message msg}))

;; make a stream pipeline
(defn msg-recv
  [server]
  (ms/map raw-msg->msg @server))

(defn uint16->hex-str
  "drop the first byte"
  [x] (str/join (map hex->str (drop 2 (int32->bytes-array x)))))

(defn revoke-hash
  "remove {hash:int device:device} from global devices list"
  [m id]
  (let [devices (vals m)
        devs-with-id (filter (fn [d] (= (:id d) id)) devices)
        hashes (map #(:hash %) devs-with-id)]
    (if (not (empty? hashes))
      (apply (partial dissoc m) hashes)
      m)))

(defn rand-rtmp-emerg-chan []
  (let [int16-ba (byte-array (map unchecked-byte (rand-hex-arr 2)))
        int16 (. Shorts fromByteArray int16-ba)
        int16-c0 (bit-or 0xc000 int16)]
    int16-c0))

(defn rand-rtmp-stream-chan []
  (let [int16-ba (byte-array (map unchecked-byte (rand-hex-arr 2)))
        int16 (bit-and 0x3fff (. Shorts fromByteArray int16-ba))]
    int16))

(defn create-rtmp-stop-req
  [hash]
  (let [spec (:RTMP_STOP_SERVER MsgSpec)
        head (:RTMP_STOP sMsgType)
        buffer (buf/allocate (buf/size spec))]
    (do (buf/write! buffer [head hash] spec)
        buffer)))

(defn create-rtmp-stream-req
  "create a RTMP_EMERG msg
   hash is int32
   return [ByteBuffer chan: int16]"
  ([hash chan]
   (let [spec (:RTMP_STREAM_SERVER MsgSpec)
         head (:RTMP_STREAM sMsgType)
         buffer (buf/allocate (buf/size spec))]
     (do
       (buf/write! buffer [head hash chan] spec)
       [buffer chan])))
  ([hash] (create-rtmp-stream-req hash (rand-rtmp-stream-chan))))

(defn create-rtmp-emerg-resp
  "create a RTMP_EMERG msg
   hash is int32
   return [byte-array chan: int16]"
  ([hash chan]
   (let [spec (:RTMP_EMERG_SERVER MsgSpec)
         head (:RTMP_EMERG sMsgType)
         buffer (buf/allocate (buf/size spec))]
     (do
       (buf/write! buffer [head hash chan] spec)
       [buffer chan])))
  ([hash] (create-rtmp-emerg-resp hash (rand-rtmp-emerg-chan))))
