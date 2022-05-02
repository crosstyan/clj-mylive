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
    [elevator-server.global :refer [db-udp udp-server devices app-bus] :rename {db-udp db}]
    [elevator-server.utils.udp :refer :all]
    [byte-streams :as bs]
    [spec-tools.data-spec :as ds]
    [octet.core :as buf]
    [clojure.core.match :refer [match]]
    [clojure.tools.logging :as log]
    [manifold.bus :as bus])
  (:import
    (com.google.common.primitives Ints UnsignedInts Shorts)
    (java.io ByteArrayOutputStream)
    (java.nio ByteBuffer)))


(defn send-rtmp-emerg-resp
  ([hash server e-chan]
   (let [[buffer-w _chan] (create-rtmp-emerg-resp hash e-chan)
         dev (get @devices hash)
         l-msg (:last-msg dev)]
     (if (not (nil? dev))
       (do
         (log/debugf "RTMP_EMERG %s from Server" (byte-array->str (.array buffer-w)))
         (send-back! server l-msg buffer-w)
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
        stored (update conv :message byte-array->str)]
    (condp = (first v-msg)
      (:INIT sMsgType) (let [[_head id] (buf/read buffer-r (:INIT_CLIENT MsgSpec))
                             buffer-w (buf/allocate (buf/size (:INIT_SERVER MsgSpec)))
                             hash (rand-by-hash id)
                             _ (buf/write! buffer-w [(:INIT sMsgType) hash] (:INIT_SERVER MsgSpec))
                             e (byte-array [(:INIT sMsgType) (:ERR sErrCode)])
                             e-chan (rand-rtmp-emerg-chan)]
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
      (:RTMP_EMERG sMsgType) (let [[_head hash] (buf/read buffer-r (:RTMP_EMERG_CLIENT MsgSpec))
                             dev (get @devices hash)
                             e-chan (rand-rtmp-emerg-chan)]
                         (if (not (nil? dev))
                           (do (swap! devices #(assoc % hash (assoc dev :e-chan (uint16->hex-str e-chan) :last-msg stored))) ;swap e-chan and last msg
                               (log/debugf "RTMP_EMERG %s" h-msg)
                               (send-rtmp-emerg-resp hash @udp-server e-chan))))
      (:RTMP_STREAM sMsgType) (let [[_head hash chan code] (buf/read buffer-r (:RTMP_STREAM_CLIENT MsgSpec))
                             chan-hex (uint16->hex-str chan)
                             dev (get @devices hash)]
                         (if (not (nil? dev))
                           (do (swap! devices #(assoc % hash (assoc dev :last-msg stored)))
                               (log/debugf "RTMP_STREAM %s" h-msg)
                               (bus/publish! app-bus (keyword (str/join "RTMP_STREAM" (int32->hex-str hash)))
                                             (condp = code
                                               (:OK sErrCode) (do (swap! devices #(assoc % hash (assoc dev :chan chan-hex)))
                                                                  :ok)
                                               (:BUSY sErrCode) :busy
                                               (:BUSY_EMERG sErrCode) :busy
                                               (:BUSY_STREAM sErrCode) :busy
                                               ;; https://stackoverflow.com/questions/1242819/how-do-i-write-else-in-condp-in-clojure
                                               :err)))))

      (:RTMP_STOP sMsgType) (let [[_head hash code] (buf/read buffer-r (:RTMP_STOP_CLIENT MsgSpec))
                                    dev (get @devices hash)]
                                (if-not (nil? dev)
                                  (do (swap! devices #(assoc % hash (assoc dev :last-msg stored)))
                                      (log/debugf "RTMP_STOP %s" h-msg)
                                      (bus/publish! app-bus (keyword (str/join "RTMP_STOP" (int32->hex-str hash)))
                                                    (condp = code
                                                      (:OK sErrCode) :ok
                                                      ;; https://stackoverflow.com/questions/1242819/how-do-i-write-else-in-condp-in-clojure
                                                      :err)))))
      (:HEARTBEAT sMsgType) (let [spec (:HEARTBEAT MsgSpec)
                             [_head hash] (buf/read buffer-r spec)
                             dev (get @devices hash)]
                         (log/infof "HEARTBEAT %s" h-msg)
                         (if (not (nil? dev))
                           (do (swap! devices #(assoc % hash (assoc dev :last-msg stored))))))
      nil)))

(defn start []
  "start udp server"
  (ms/consume #'app-handler @udp-server))
