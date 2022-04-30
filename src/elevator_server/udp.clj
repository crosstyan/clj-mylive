(ns elevator-server.udp
  ;; https://stackoverflow.com/questions/14610957/how-to-rename-using-ns-require-refer/27122360#27122360
  ;(:refer-clojure :rename {udp-server server})
  (:require
    [manifold.stream :as ms]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [byte-streams :as bs]
    [clojure.core.match :refer [match]]
    [monger.collection :as mc]
    [elevator-server.global :refer [db udp-server devices]]
    [elevator-server.utils.udp :refer [raw-msg->msg rand-by-hash send-back! rand-hex-arr MsgSpec MsgType sMsgType ErrCode]]
    [byte-streams :as bs]
    [spec-tools.data-spec :as ds]
    [octet.core :as buf]
    [clojure.core.match :refer [match]])
  (:import
    (com.google.common.primitives Ints UnsignedInts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))

(def device
  {:id integer?
   :hash integer?
   (ds/opt :last) integer?                                  ; last seen
   (ds/opt :name) string?                                   ; name
   (ds/opt :e-chan) integer?
   (ds/opt :chan) integer?})

(def device-spec
  (ds/spec {:name :g/device
            :spec device}))

(defn rand-rtmp-emerg-chan []
  (let [int32-ba (byte-array (map unchecked-byte (rand-hex-arr 4)))
        int16 (bit-and   0x0000ffff (. Ints fromByteArray int32-ba))
        int16-c0 (bit-or 0x0000c000 int16)]
    int16-c0))

(defn rand-rtmp-stream-chan []
  (let [int32-ba (byte-array (map unchecked-byte (rand-hex-arr 4)))
        int16 (bit-and   0x00003fff (. Ints fromByteArray int32-ba))]
    int16))

(defn create-rtmp-emerg-resp [hash]
  "create a RTMP_EMERG msg
   hash is int32
   return [byte-array chan: int16]"
  (let [spec   (:RTMP_EMERG_SERVER MsgSpec)
        head   (:RTMP_EMERG MsgType)
        chan   (rand-rtmp-emerg-chan)
        buffer (buf/allocate (buf/size spec))]
    [(buf/write! buffer [head hash chan] spec) chan]))

(defn handle-msg
  "`recv-msg` raw msg received by `manifold.stream`. return byte-array
   will produce a lot of side effects"
  [recv-msg]
  ;; msg is a vector of bytes
  (let [conv (raw-msg->msg recv-msg)
        ;; vector of bytes which is singed
        vmsg  (vector (:message conv))
        buffer (buf/allocate (count vmsg))
        INIT (:INIT sMsgType)
        RTMP_EMERG (:RTMP_EMERG sMsgType)
        RTMP_STREAM (:RTMP_STREAM sMsgType)
        HEARTBEAT (:HEARTBEAT sMsgType)]
    (match [(first vmsg)]
           [INIT] (let [[head id] (buf/read buffer (:INIT_CLIENT MsgSpec))
                        hash    (rand-by-hash id)]
                                (if (not (mc/find-one db "device" {:id id}))
                                  (do (swap! devices #(assoc % hash {:id id :hash hash})))
                                    (buf/write! buffer [head hash] (:INIT_SERVER MsgSpec)))
                                  (byte-array [(:INIT sMsgType) (:ERR ErrCode)])))
           [RTMP_EMERG] (let [[_head hash] (buf/read buffer (:RTMP_EMERG_CLIENT MsgSpec))
                              [resp e-chan] (create-rtmp-emerg-resp hash)])))


(defn app-handler [m]
  ;(dosync (alter global-msg (constantly m)))
  (send-back! @udp-server m (handle-msg m)))

(defn start []
  "start udp server"
  (ms/consume #'app-handler @udp-server))
