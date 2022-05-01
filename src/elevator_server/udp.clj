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
    [elevator-server.utils.udp :as u-utils :refer [raw-msg->msg uint16->hex-str rand-by-hash byte-array->buf buf->byte-array send-back! byte-array->str rand-hex-arr MsgSpec MsgType sMsgType sErrCode]]
    [byte-streams :as bs]
    [spec-tools.data-spec :as ds]
    [octet.core :as buf]
    [clojure.core.match :refer [match]]
    [clojure.tools.logging :as log])
  (:import
    (com.google.common.primitives Ints UnsignedInts Shorts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))




(defn revoke-hash
  "remove {hash:int device:device} from global devices list"
  [m id]
  (let [devices (vals m)
        devs-with-id (filter (fn [d] (= (:id d) 123)) devices)
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


(defn send-rtmp-emerg-resp
  ([hash server e-chan]
   (let [[buffer-w _chan] (create-rtmp-emerg-resp hash e-chan)
         dev (get @devices hash)
         l-msg (:last-msg dev)]
     (if (not (nil? dev))
       (do
         (log/debugf "RTMP_EMERG %s from Server" (byte-array->str (.array buffer-w)))
         (u-utils/send! server buffer-w (:host l-msg) (:port l-msg))
         nil))))
  ([hash server] (send-rtmp-emerg-resp hash server (rand-rtmp-emerg-chan))))

(defn app-handler
  "`recv-msg` raw msg received by `manifold.stream`. return byte-array
   will produce a lot of side effects"
  [recv]
  ;; msg is a vector of bytes
  (let [conv (raw-msg->msg recv)
        ;; https://stackoverflow.com/questions/31062973/java-byte-array-to-bytebuffer-or-bytebuffer-to-byte-array-convertion-without-co
        buffer-r (byte-array->buf (:message conv))
        v-msg (vec (:message conv))
        h-msg (byte-array->str (:message conv))
        stored (update conv :message byte-array->str)
        INIT (:INIT sMsgType)
        RTMP_EMERG (:RTMP_EMERG sMsgType)
        RTMP_STREAM (:RTMP_STREAM sMsgType)
        HEARTBEAT (:HEARTBEAT sMsgType)]
    (match [(first v-msg)]
           [INIT] (let [[_head id] (buf/read buffer-r (:INIT_CLIENT MsgSpec))
                        buffer-w (buf/allocate (buf/size (:INIT_SERVER MsgSpec)))
                        hash (rand-by-hash id)
                        _ (buf/write! buffer-w [INIT hash] (:INIT_SERVER MsgSpec))
                        e (byte-array [(:INIT sMsgType) (:ERR sErrCode)])
                        e-chan (rand-rtmp-emerg-chan)
                        ]
                    (log/debugf "INIT %s" h-msg)
                    (if (not (nil? (mc/find-one db "device" {:id id})))
                      (do (swap! devices #(revoke-hash % id))
                          (swap! devices #(assoc % hash {:id id :hash hash :last-msg stored :e-chan (uint16->hex-str e-chan)}))
                          (log/info "INIT" ":id" id)
                          (log/debugf "INIT %s from Server" (byte-array->str (.array buffer-w)))
                          (send-back! @udp-server recv buffer-w)
                          (log/debug "INIT e-chan" (uint16->hex-str e-chan) e-chan)
                          (send-rtmp-emerg-resp hash @udp-server e-chan)) ;; send-emerg-key-without requesting
                      (send-back! @udp-server recv e)))
           [RTMP_EMERG] (let [[_head hash] (buf/read buffer-r (:RTMP_EMERG_CLIENT MsgSpec))
                              dev (get @devices hash)
                              e-chan (rand-rtmp-emerg-chan)]
                          (if (not (nil? dev))
                            (do (swap! devices #(assoc % hash (assoc dev :e-chan (uint16->hex-str e-chan) :last-msg stored))) ;swap e-chan and last msg
                                (log/debugf "RTMP_EMERG %s" h-msg)
                                (send-rtmp-emerg-resp hash @udp-server e-chan))))
           [HEARTBEAT] (let [spec (:HEARTBEAT MsgSpec)
                             [_head hash] (buf/read buffer-r spec)
                             dev (get @devices hash)]
                         (log/infof "HEARTBEAT %s" h-msg)
                         (if (not (nil? dev))
                           (do (swap! devices #(assoc % hash (assoc dev :last-msg stored))))))
           :else       nil)))

(defn start []
  "start udp server"
  (ms/consume #'app-handler @udp-server))
