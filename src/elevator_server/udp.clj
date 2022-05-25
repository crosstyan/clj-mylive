(ns elevator-server.udp
  ;; https://stackoverflow.com/questions/14610957/how-to-rename-using-ns-require-refer/27122360#27122360
  ;(:refer-clojure :rename {udp-server server})
  (:require
    [manifold.stream :as ms]
    [clojure.string :as str]
    [monger.collection :as mc]
    [elevator-server.global :refer [db-udp udp-server devices app-bus] :rename {db-udp db}]
    [elevator-server.utils.udp :refer :all]
    [octet.core :as buf]
    [clojure.tools.logging :as log]
    [manifold.bus :as bus]))


(defn send-rtmp-emerg-resp
  ([hash server e-chan]
   (let [[buffer-w _chan] (create-rtmp-emerg-resp hash e-chan)
         dev (get @devices hash)
         l-msg (:last-msg dev)]
     (if-not (nil? dev)
       (do
         (log/debugf "RTMP_EMERG %s from Server" (byte-array->str (.array buffer-w)))
         (send-back! server l-msg buffer-w)
         nil)
       (log/info "RTMP_EMERG: device not found"))))
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
      (:INIT MsgType) (let [[_head id] (buf/read buffer-r (:INIT_CLIENT MsgSpec))
                            buffer-w (buf/allocate (buf/size (:INIT_SERVER MsgSpec)))
                            hash (rand-by-hash id)
                            _ (buf/write! buffer-w [(:INIT MsgType) hash] (:INIT_SERVER MsgSpec))
                            e (byte-array [(:INIT MsgType) (:ERR ErrCode)])
                            e-chan (rand-rtmp-emerg-chan)
                            dev-db (mc/find-one-as-map db "device" {:id id})
                            dev {:name (:name dev-db) :id id :hash hash :last-msg stored :e-chan (uint16->hex-str e-chan)}]
                        (log/debugf "INIT %s" h-msg)
                        (if-not (nil? dev-db)
                          (do (swap! devices #(revoke-hash % id))
                              (log/info "INIT" ":id" id)
                              (log/debugf "INIT %s from Server" (byte-array->str (.array buffer-w)))
                              (send-back! @udp-server recv buffer-w)
                              (log/debug "INIT e-chan" (uint16->hex-str e-chan) e-chan)
                              (swap! devices #(assoc % hash dev))
                              (send-rtmp-emerg-resp hash @udp-server e-chan)
                              (bus/publish! app-bus :dev-online dev)) ;; send-emerg-key-without requesting
                          (send-back! @udp-server recv e)))
      (:RTMP_EMERG MsgType) (let [[_head hash] (buf/read buffer-r (:RTMP_EMERG_CLIENT MsgSpec))
                                  dev (get @devices hash)
                                  e-chan (rand-rtmp-emerg-chan)]
                              (if (not (nil? dev))
                                (do (swap! devices #(assoc % hash (assoc dev :e-chan (uint16->hex-str e-chan) :last-msg stored))) ;swap e-chan and last msg
                                    (log/debugf "RTMP_EMERG %s" h-msg)
                                    (send-rtmp-emerg-resp hash @udp-server e-chan))))
      (:RTMP_STREAM MsgType) (let [[_head hash chan code] (buf/read buffer-r (:RTMP_STREAM_CLIENT MsgSpec))
                                   chan-hex (uint16->hex-str chan)
                                   dev (get @devices hash)]
                               (if (not (nil? dev))
                                 (do (swap! devices #(assoc % hash (assoc dev :last-msg stored)))
                                     (log/debugf "RTMP_STREAM %s" h-msg)
                                     (bus/publish! app-bus (keyword (str/join "RTMP_STREAM" (int32->hex-str hash)))
                                                   (condp = code
                                                     (:OK ErrCode) (do (swap! devices #(assoc % hash (assoc dev :chan chan-hex)))
                                                                       :ok)
                                                     (:BUSY ErrCode) :busy
                                                     (:BUSY_EMERG ErrCode) :busy
                                                     (:BUSY_STREAM ErrCode) :busy
                                                     ;; https://stackoverflow.com/questions/1242819/how-do-i-write-else-in-condp-in-clojure
                                                     :err)))))

      (:RTMP_STOP MsgType) (let [[_head hash code] (buf/read buffer-r (:RTMP_STOP_CLIENT MsgSpec))
                                 dev (get @devices hash)]
                             (if-not (nil? dev)
                               (do (swap! devices #(assoc % hash (assoc dev :last-msg stored)))
                                   (log/debugf "RTMP_STOP %s" h-msg)
                                   (bus/publish! app-bus (keyword (str/join "RTMP_STOP" (int32->hex-str hash)))
                                                 (condp = code
                                                   (:OK ErrCode) :ok
                                                   ;; https://stackoverflow.com/questions/1242819/how-do-i-write-else-in-condp-in-clojure
                                                   :err)))))
      (:HEARTBEAT MsgType) (let [spec (:HEARTBEAT MsgSpec)
                                 [_head hash] (buf/read buffer-r spec)
                                 dev (get @devices hash)]
                             (log/debugf "HEARTBEAT %s" h-msg)
                             (if (not (nil? dev))
                               (swap! devices #(assoc % hash (assoc dev :last-msg stored)))))

      (:PRESSURE MsgType) (let [spec (:PRESSURE_CLIENT MsgSpec)
                                [_head hash val] (buf/read buffer-r spec)
                                dev (get @devices hash)
                                kw (keyword (str/join "PRESSURE" (str (:id dev))))]
                            (log/debugf "PRESSURE %s" h-msg)
                            (if (not (nil? dev))
                              (do (bus/publish! app-bus kw {:id (:id dev) :pressure val})
                                  (swap! devices #(assoc % hash (assoc dev :last-msg stored))))))

      (:ACC MsgType) (let [spec (:ACC_CLIENT MsgSpec)
                           [_head hash x y z] (buf/read buffer-r spec)
                           dev (get @devices hash)
                           kw (keyword (str/join "ACC" (str (:id dev))))]
                       (log/debugf "ACC %s" h-msg)
                       (if (not (nil? dev))
                         (do (bus/publish! app-bus kw {:id (:id dev) :x x :y y :z z})
                             (swap! devices #(assoc % hash (assoc dev :last-msg stored))))))
      (log/info h-msg))))

(defn start []
  "start udp server"
  (ms/consume #'app-handler @udp-server))
