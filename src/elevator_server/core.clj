(ns elevator-server.core
  (:require [elevator-server.udp-server :as u])
  (:import [com.longyb.mylive.server ConfigUtils HttpFlvServer RTMPServer]
           [com.longyb.mylive.server.manager StreamManager]))

;; http://www.slf4j.org/codes.html#multiple_bindings
;; don't actually care about it
(defn start-mylive
  "Reading config from `config-path` and start MyLive
  See https://github.com/crosstyan/MyLive/blob/deps/src/main/java/com/longyb/mylive/server/MyLiveServer.java
  and https://github.com/crosstyan/MyLive/blob/deps/mylive.yaml"
  [config-path]
  (let [stream-man (StreamManager.)
        cfg (ConfigUtils/readConfigFrom config-path)
        pool-size (. cfg getHandlerThreadPoolSize)
        rtmp-server (RTMPServer. (. cfg getRtmpPort) stream-man pool-size)
        http-flv-server (HttpFlvServer. (. cfg getHttpFlvPort) stream-man pool-size)]
    (do
      (. rtmp-server run)
      (. http-flv-server run))))

(defn init [] (let [server (u/start-server 12345)]
                (do
                  (u/start-handle-msg server u/global-msg)
                  (start-mylive "./mylive.yaml"))))
