(ns elevator-server.http
  (:require [aleph.http :as a-http]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [manifold.bus :as bus]
            [clojure.data.json :as json]
            [elevator-server.global :refer [conn db-http app-bus devices udp-server] :rename {db-http db}]
            [elevator-server.utils.core :refer [nil?-or]]
            [elevator-server.utils.http :refer [opts]]
            [elevator-server.utils.udp :as u-udp :refer [int32->hex-str send-back!
                                                         create-rtmp-stream-req
                                                         create-rtmp-stop-req
                                                         device-spec
                                                         device-spec-example]]
            [clojure.core.match :refer [match]]
            [monger.collection :as mc]
            [reitit.ring :as ring]
            [reitit.coercion.spec]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [spec-tools.swagger.core :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [elevator-server.mylive :refer [get-config get-video-path]]
            [spec-tools.core :as st]
            [simple-cors.aleph.middleware :as cors]
            [reitit.coercion.spec :as rcs]
            [monger.query :as mq]
            [clojure.string :as str]
            [tick.core :as t]
            [clojure.java.io :as io]))

(def cors-config {:cors-config {:allowed-request-methods [:post :get :put :delete]
                                :allowed-request-headers ["Authorization" "Content-Type"]
                                :origins                 "*"}})

;; example from
;; https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

;(def rtmp-events (ms/buffered-stream 20))
(def rtmp-events (ms/stream 10))


(defn two-bytes? [x] (and (< x 65535) (< 0 x)))
(s/def :dev/name string?)
(s/def :dev/id (s/and int? two-bytes?))
(s/def :s/device (s/keys :req-un [:dev/name :dev/id]))
(s/def :s/page nat-int?)
(s/def :s/elems (s/and pos-int? #(< % 100)))

;; Alt + Shift + L reload current file to repl
;; Alt + Shift + P eval current expression from top
;; Alt + Shift + R replace to current workspace


(defn device-get-handler [req db]
  (let [{{{:keys [page elems]} :query} :parameters} req
        page (nil?-or page 0)
        elems (nil?-or elems 10)
        docs (->> (mq/with-collection db "device"
                                      ;; macro first threading is called
                                      (mq/find {})
                                      (mq/paginate :page page :per-page elems))
                  (map #(dissoc % :_id)))]
    {:status 200 :body docs}))

(defn get-dev-by-id [m id]
  (first (filter #(= (:id %) id) (vals m))))

(defn get-dev-by-chan
  ;; chan can be emerg channel or stream channel
  ;; if both found (should not happen) return nil
  ;; found none, return [:err nil]
  [m chan]
  (let [e-chan-dev (first (filter #(= (:e-chan %) chan) (vals m)))
        chan-dev (first (filter #(= (:chan %) chan) (vals m)))]
    (match [e-chan-dev chan-dev]
           [nil nil] [:err nil]
           [nil dev] [:stream dev]
           [dev nil] [:emerg dev]
           :else [:err nil])))

(defn rtmp-events->with-meta
  "add {:id id :type 'emerg'/'stream'} to each event"
  [m]
  (let [[status dev] (get-dev-by-chan @devices (:chan m))
        wrap (fn [e] {:type "rtmp" :content e})]
    (condp = status
      :err (wrap m)
      (wrap (assoc m :id (:id dev) :type (name status))))))

(def non-websocket-request
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})


;; see https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/websocket.clj
;;
(defn rtmp-ws-handler
  "spec {:type string :content string | map}"
  [req]
  (md/let-flow [conn (md/catch
                       (a-http/websocket-connection req)
                       (constantly nil))]
               (if-not conn
                 ;; if it wasn't a valid websocket handshake, return an error
                 non-websocket-request
                 ;; TODO: use threading
                 (md/let-flow [rtmp (ms/map rtmp-events->with-meta rtmp-events)
                               rtmp-str (ms/map json/write-str rtmp)
                               online (ms/map #((constantly {:type "online" :content %})) (bus/subscribe app-bus :dev-online))
                               online-str (ms/map json/write-str online)
                               pressure (ms/map #((constantly {:type "pressure" :content %})) (bus/subscribe app-bus :pressure))
                               pressure-str (ms/map json/write-str pressure)
                               acc (ms/map #((constantly {:type "acceleration" :content %})) (bus/subscribe app-bus :acceleration))
                               acc-str (ms/map json/write-str acc)]
                              ;; take all messages from the rtmp, and feed them to the client
                              (ms/connect rtmp-str conn)
                              (ms/connect online-str conn)
                              (ms/connect acc-str conn)
                              (ms/connect pressure-str conn)
                              nil))))


(defn device-ws-handler
  "spec {:type string :content string | map}"
  [req]
  (md/let-flow [conn (md/catch
                       (a-http/websocket-connection req)
                       (constantly nil))]
               (if-not conn
                 non-websocket-request
                 (md/let-flow [{{id :id} :path-params} req
                               p-kw (keyword (str/join "PRESSURE" id))
                               acc-kw (keyword (str/join "ACC" id))
                               pressure (ms/map #((constantly {:type "pressure" :content %})) (bus/subscribe app-bus p-kw))
                               pressure-str (ms/map json/write-str pressure)
                               acc (ms/map #((constantly {:type "acceleration" :content %})) (bus/subscribe app-bus acc-kw))
                               acc-str (ms/map json/write-str acc)]
                              ;; take all messages from the rtmp, and feed them to the client
                              (ms/connect acc-str conn)
                              (ms/connect pressure-str conn)
                              nil))))

(defn device-post-handler [req db]
  (let [{{b :body} :parameters} req
        ;; TODO purify the input before insert
        {id :id} b]
    (if (not (mc/find-one-as-map db "device" {:id id}))
      (do (mc/insert db "device" b)
          {:status 200 :body {:result "success"}})
      {:status 400 :body {:result (format "id %d existed" id)}})))

;; https://cljdoc.org/d/metosin/spec-tools/0.10.5/doc/spec-coercion
;; https://cljdoc.org/d/metosin/reitit/0.5.18/doc/coercion/clojure-spec
;; https://github.com/metosin/reitit/blob/master/doc/coercion/coercion.md
;; https://github.com/metosin/reitit/blob/master/doc/ring/coercion.md
;; https://github.com/ring-clojure/ring/wiki/Concepts

(def spec-404 {404 {:body {:result string?}}})

(defn merge-404 [& rest]
  (apply #(merge spec-404 %) rest))

(def app
  "db is mongo db"
  (ring/ring-handler
    (ring/router
      [["/swagger.json"
        {:get {:no-doc  true
               :swagger {:info {:title       "API for device manipulation"
                                :description "with reitit-ring"}}
               :handler (reitit.swagger/create-swagger-handler)}}]

       ["/devices"
        {:swagger {:tags ["devices"]}
         :get     {:summary    "get all available devices with mongo"
                   :coercion   rcs/coercion
                   :parameters {:query (s/keys :opt-un [:s/page :s/elems])}
                   :responses  {200 {:body (s/* :s/device)}}
                   :handler    #(device-get-handler % db)}
         :post    {:summary    "post a device"
                   :coercion   rcs/coercion
                   :parameters {:body :s/device}
                   :responses  (merge-404 {200 {:body {:result string?}}})
                   :handler    #(device-post-handler % db)}}]
       ["/devices/{id}"
        {:swagger {:tags ["devices"]}
         :get     {:summary    "get certain device"
                   :coercion   rcs/coercion
                   :parameters {:path (s/keys :req-un [:dev/id])}
                   :responses  (merge-404 {200 {:body :s/device}})
                   :handler    (fn [req]
                                 (let [{{{:keys [id]} :path} :parameters} req
                                       doc (dissoc (mc/find-one-as-map db "device" {:id id}) :_id)]
                                   (if doc {:status 200 :body doc}
                                           {:status 404 :body {:result "not found"}})))}}]
       ["/ws"
        {:get {:summary "websocket"
               :no-doc  true
               :handler rtmp-ws-handler}}]
       ["/ws/device/{id}"
        {:get {:summary    "websocket device events for acc and pressure"
               :no-doc     true
               :parameters {:path {:id string?}}            ;; id should be number
               :handler    device-ws-handler}}]
       ["/rtmp"
        {:swagger {:tags ["RTMP MyLive"]}
         :post    {:summary    "get realtime rtmp message from mylive"
                   :coercion   rcs/coercion
                   :parameters {:body {:chan string? :cmd string?}}
                   :handler    (fn [req]
                                 (let [{{b :body} :parameters} req]
                                   (do (ms/put! rtmp-events b)
                                       {:status 200})))}}]

       ["/rtmp/chan/{chan}/filename"
        {:swagger {:tags ["RTMP MyLive"]}
         :get     {:summary    "get filename for a channel"
                   :coercion   rcs/coercion
                   :parameters {:path {:chan string?}}
                   :responses  (merge-404 {200 {:body {:filename string?}}})
                   :handler    (fn [{{{:keys [chan]} :path} :parameters}]
                                 (let [[status dev] (get-dev-by-chan @devices chan)
                                       time (t/format "yyyy-MM-dd'T'HH:mm:ss" (t/zoned-date-time))]
                                   (match [status]
                                          [:emerg] {:status 200 :body {:filename (str/join "-" [(:id dev) "EMERG" time])}}
                                          [:stream] {:status 200 :body {:filename (str/join "-" [(:id dev) "STREAM" time])}}
                                          :else {:status 404 :body {:result "not found"}})))}}]
       ["/rtmp/devices"
        {:swagger {:tags ["RTMP"]}
         :get     {:swagger {:responses {200 {:schema {:type "array" :items (merge (swagger/transform device-spec) {:example device-spec-example})}}}}
                   :summary "get online devices. "
                   ;; TODO https://clojuredocs.org/clojure.core/subvec
                   :handler (fn [_req]
                              (let [devs (vals @devices)
                                    res (if (nil? devs) [] devs)]
                                {:status 200 :body res}))}}]
       ["/rtmp/devices/{id}"
        {:swagger {:tags ["RTMP"]}
         :get     {:swagger    {:responses {200 {:schema {:example device-spec-example}}}}
                   :summary    "get online devices of id"
                   :coercion   rcs/coercion
                   :parameters {:path (s/keys :req-un [:dev/id])}
                   :responses  (merge-404 {200 {:body device-spec}})
                   :handler    (fn [{{{:keys [id]} :path} :parameters}]
                                 (let [dev (get-dev-by-id @devices id)]
                                   (if (not (nil? dev))
                                     {:status 200 :body dev}
                                     {:status 404 :body {:result "not found"}})))}}]
       ["/rtmp/devices/{id}/start"
        {:swagger {:tags ["RTMP"]}
         :get     {:swagger    {:responses {200 {:schema {:example {:chan "0fda"}}}}}
                   :summary    "start stream on certain device"
                   :coercion   rcs/coercion
                   :parameters {:path (s/keys :req-un [:dev/id])}
                   :responses  (merge-404 {200 {:body {:chan string?}}})
                   :handler    (fn [{{{:keys [id]} :path} :parameters}]
                                 (let [dev (get-dev-by-id @devices id)]
                                   (if (not (nil? dev))
                                     (let [hash (:hash dev)
                                           l-msg (:last-msg dev)
                                           [req chan] (create-rtmp-stream-req hash)
                                           chan-hex (u-udp/uint16->hex-str chan)
                                           topic (keyword (str/join "RTMP_STREAM" (int32->hex-str hash)))
                                           eb (bus/subscribe app-bus topic)]
                                       ;; TODO: use multimethod to support both byte-array and ByteBuffer
                                       (log/debugf "RTMP_STREAM %s from Server" (u-udp/byte-array->str (.array req)))
                                       (send-back! @udp-server l-msg (.array req))
                                       (let [val @(ms/try-take! eb :err 20000 :timeout)]
                                         (log/debug "From UDP to HTTP" (name val))
                                         (condp = val
                                           :ok {:status 200 :body {:chan chan-hex}}
                                           :err {:status 500 :body {:result "error"}}
                                           :busy {:status 409 :body {:result "busy"}}
                                           :timeout {:status 504 :body {:result "timeout"}}
                                           {:status 500 :body {:result "error"}})))
                                     {:status 404 :body {:result "not found"}})))}}]

       ["/rtmp/devices/{id}/stop"
        {:swagger {:tags ["RTMP"]}
         :get     {:swagger    {:responses {200 {:schema {:example {:result "ok"}}}
                                            404 {:schema {:example {:result "not found"}}}}}
                   :summary    "stop stream on certain device"
                   :coercion   rcs/coercion
                   :parameters {:path (s/keys :req-un [:dev/id])}
                   :responses  (merge-404 {200 {:body {:result string?}}})
                   :handler    (fn [{{{:keys [id]} :path} :parameters}]
                                 (let [dev (get-dev-by-id @devices id)]
                                   (if-not (nil? dev)
                                     (let [hash (:hash dev)
                                           l-msg (:last-msg dev)
                                           req (create-rtmp-stop-req hash)
                                           topic (keyword (str/join "RTMP_STOP" (int32->hex-str hash)))
                                           eb (bus/subscribe app-bus topic)]
                                       ;; TODO: use multimethod to support both byte-array and ByteBuffer
                                       (log/debugf "RTMP_STOP %s from Server" (u-udp/byte-array->str (.array req)))
                                       (send-back! @udp-server l-msg (.array req))
                                       (let [val @(ms/try-take! eb :err 20000 :timeout)]
                                         (log/debug "From UDP to HTTP" (name val))
                                         (condp = val
                                           :ok {:status 200 :body {:result "ok"}}
                                           :timeout {:status 504 :body {:result "timeout"}}
                                           {:status 500 :body {:result "error"}})))
                                     {:status 404 :body {:result "not found"}})))}}]
       ["/videos"
        {:swagger {:tags ["RTMP"]}
         :get     {:summary "get a list of videos"
                   :handler (fn [_req]
                              (let [files (io/file (get-video-path))
                                    file-names (map str (.list files))]
                                {:status 200 :body {:result file-names}}))}}]
       ] opts)
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path   "/swagger"
         :config {:validatorUrl     nil
                  :operationsSorter "alpha"}})
      (ring/create-file-handler
        {:path "/videos"
         :root (get-video-path)})
      (ring/create-default-handler))))

(def app-cors (cors/wrap #'app cors-config))

(defn start [port]
  ;; used for repl
  ;; https://stackoverflow.com/questions/17792084/what-is-i-see-in-ring-app
  ;; https://stackoverflow.com/questions/39550513/when-to-use-a-var-instead-of-a-function
  (a-http/start-server app-cors {:port port})
  (log/info "API HTTP server runing in port" port))

