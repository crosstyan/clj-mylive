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
    [elevator-server.global :refer [db-udp udp-server devices] :rename {db-udp db}]
    [elevator-server.utils.udp :refer [raw-msg->msg rand-by-hash send-back! byte-array->str rand-hex-arr MsgSpec MsgType sMsgType sErrCode]]
    [byte-streams :as bs]
    [spec-tools.data-spec :as ds]
    [octet.core :as buf]
    [clojure.core.match :refer [match]]
    [clojure.tools.logging :as log])
  (:import
    (com.google.common.primitives Ints UnsignedInts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))

(def stored-msg
  {:message       seq?                                      ;; byte-array
   :port          integer?
   :host          string?
   (ds/opt :time) integer?})

(def device
  {:id              integer?
   :hash            integer?
   (ds/opt :name)   string?                                 ; name
   (ds/opt :e-chan) integer?
   ;:last-msg stored-msg
   (ds/opt :chan)   integer?})

(def device-spec
  (ds/spec {:name :g/device
            :spec device}))



(defn revoke-hash
  "remove {hash:int device:device} from global devices list"
  [m id]
  (let [devices (vals m)
        devs-with-id (filter (fn [d] (= (:id d) 123)) devices)
        hashes (map #(:hash %) devs-with-id)]
    (if (not (empty? hashes))
      (apply (partial dissoc m) hashes)
      m))
)

(defn rand-rtmp-emerg-chan []
  (let [int32-ba (byte-array (map unchecked-byte (rand-hex-arr 4)))
        int16 (bit-and 0x0000ffff (. Ints fromByteArray int32-ba))
        int16-c0 (bit-or 0x0000c000 int16)]
    int16-c0))

(defn rand-rtmp-stream-chan []
  (let [int32-ba (byte-array (map unchecked-byte (rand-hex-arr 4)))
        int16 (bit-and 0x00003fff (. Ints fromByteArray int32-ba))]
    int16))

(defn create-rtmp-emerg-resp [hash]
  "create a RTMP_EMERG msg
   hash is int32
   return [byte-array chan: int16]"
  (let [spec (:RTMP_EMERG_SERVER MsgSpec)
        head (:RTMP_EMERG MsgType)
        chan (rand-rtmp-emerg-chan)
        buffer (buf/allocate (buf/size spec))]
    (do
      (buf/write! buffer [head hash chan] spec)
      [buffer chan])))

(defn handle-msg
  "`recv-msg` raw msg received by `manifold.stream`. return byte-array
   will produce a lot of side effects"
  [recv]
  ;; msg is a vector of bytes
  (let [conv (raw-msg->msg recv)
        ;; vector of bytes which is singed
        buffer-r (. ByteBuffer wrap  (:message conv))
        vmsg (vec (:message conv))
        msg-hex (byte-array->str (:message conv))
        ;_nil (do (log/info (first vmsg))
        ;       (log/infof "UDP recv %s" msg-hex))
        stored (update conv :message byte-array->str)
        INIT (:INIT sMsgType)
        RTMP_EMERG (:RTMP_EMERG sMsgType)
        RTMP_STREAM (:RTMP_STREAM sMsgType)
        HEARTBEAT (:HEARTBEAT sMsgType)]
    (match [(first vmsg)]
           [INIT] (let [spec (:INIT_CLIENT MsgSpec)
                        [_head id] (buf/read buffer-r (:INIT_CLIENT MsgSpec))
                        _nil (log/info ":head" _head ":id" id)
                        buffer-w (buf/allocate (buf/size (:INIT_SERVER MsgSpec)))
                        hash (rand-by-hash id)
                        _ (buf/write! buffer-w [INIT hash] (:INIT_SERVER MsgSpec))
                        e (byte-array [(:INIT sMsgType) (:ERR sErrCode)])]
                    (log/infof "INIT %s" msg-hex)
                    (if (not (nil? (mc/find-one db "device" {:id id})))
                      (do (swap! devices #(revoke-hash % id))
                          (swap! devices #(assoc % hash {:id id :hash hash :last-msg stored}))
                          ;(log/infof "INIT %s from Server" (byte-array->str buffer-w))
                          (send-back! @udp-server recv buffer-w)) ; device existed in db
                      (send-back! @udp-server recv e)))
           [RTMP_EMERG] (let [spec (:RTMP_EMERG_CLIENT MsgSpec)
                              [_head hash] (buf/read buffer-r (:RTMP_EMERG_CLIENT MsgSpec))
                              [buffer-w e-chan] (create-rtmp-emerg-resp hash)
                              dev (get @devices hash)]
                          (log/infof "RTMP_EMERG %s" msg-hex)
                          (if (not (nil? dev))
                            (do (swap! devices #(assoc % hash (assoc dev :e-chan e-chan :last-msg stored))) ;swap e-chan and last msg
                                ;(log/infof "RTMP_EMERG %s from Server" (byte-array->str resp))
                                (send-back! @udp-server recv buffer-w))
                            nil))
           [HEARTBEAT] (let [spec (:HEARTBEAT MsgSpec)
                             [_head hash] (buf/read buffer-r spec)
                             dev (get @devices hash)]
                         (log/infof "HEARTBEAT %s" msg-hex)
                         (if (not (nil? dev))
                           (do (swap! devices #(assoc % hash (assoc dev :last-msg stored))))))
           :else nil)))


(defn app-handler [m]
  ;(dosync (alter global-msg (constantly m)))
  (handle-msg m))

(defn start []
  "start udp server"
  (ms/consume #'app-handler @udp-server))
