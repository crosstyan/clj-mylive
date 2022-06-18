(ns elevator-server.mylive
  (:import [com.longyb.mylive.server ConfigUtils HttpFlvServer RTMPServer]
           [com.longyb.mylive.server.manager StreamManager]
           [com.longyb.mylive.server.cfg MyLiveConfig])
  (:require [elevator-server.global :refer [config]]))

(defn start
  "Reading config from `config-path` and start MyLive
  See https://github.com/crosstyan/MyLive/blob/deps/src/main/java/com/longyb/mylive/server/MyLiveServer.java
  and https://github.com/crosstyan/MyLive/blob/deps/mylive.yaml"
  []
  (let [stream-man (StreamManager.)
        cfg (ConfigUtils/readConfigFrom (:mylive-config config))
        pool-size (. cfg getHandlerThreadPoolSize)
        rtmp-server (RTMPServer. (. cfg getRtmpPort) stream-man pool-size)
        http-flv-server (HttpFlvServer. (. cfg getHttpFlvPort) stream-man pool-size)]
    (do
      (. rtmp-server run)
      (. http-flv-server run))))

;{:saveFlvFile true,
; :saveFlVFilePath "/home/crosstyan/Code/MyLive/flv",
; :requestFileNameApi "http://127.0.0.1:3001/rtmp/chan/{chan}/filename",
; :rtmpPort 1935,
; :handlerThreadPoolSize 6,
; :sendRtmpCmd true,
; :requestFileName true,
; :enableHttpFlv true,
; :httpFlvPort 8080,
; :rtmpCmdPubApi "http://127.0.0.1:3001/rtmp"}
(defn get-config
  "Get the content of `mylive.yaml`.
  do this after MyLive is runing"
  []
  (let [cfg (. MyLiveConfig INSTANCE)]
    (if (= cfg nil) (bean (ConfigUtils/readConfigFrom (:mylive-config config)))
                    (bean cfg))))

(defn get-video-path [] (:saveFlVFilePath (get-config)))
