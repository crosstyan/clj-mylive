(ns elevator-server.http
  (:require  [aleph.http :as a-http]
             [manifold.deferred :as md]
             [manifold.stream :as ms]
             [elevator-server.global :refer [conn db]]
             [elevator-server.utils.core :refer [nil?-or]]
             [elevator-server.utils.http :refer [opts coercion-error-handler]]
             [monger.core :as mg]
             [monger.collection :as mc]
             [reitit.ring :as ring]
             [reitit.coercion.spec]
             [clojure.spec.alpha :as s]
             [reitit.swagger :as swagger]
             [reitit.swagger-ui :as swagger-ui]
             [reitit.coercion.spec :as rcs]
             [monger.query :as mq]
             [reitit.ring.coercion :as coercion]
             [reitit.dev.pretty :as pretty]
             [spec-tools.core :as st]
             [spec-tools.swagger.core :as swag]
             [ring.middleware.reload :refer [wrap-reload]]
             [monger.conversion :refer [from-db-object]]))


;; example from
;; https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

;; see https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/websocket.clj
(defn echo-handler
  "The previous handler blocks until the websocket handshake completes, which unnecessarily
   takes up a thread.  This accomplishes the same as above, but asynchronously. "
  [req]
  (-> (a-http/websocket-connection req)
      (md/chain
        (fn [socket]
          (ms/connect socket socket)))
      (md/catch
        (fn [_]
          non-websocket-request))))

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

(defn create-app
  "db is mongo db"
  [db]
  (ring/ring-handler
    (ring/router
      [["/swagger.json"
        {:get {:no-doc true
               :swagger {:info {:title "API for device manipulation"
                                :description "with reitit-ring"}}
               :handler (swagger/create-swagger-handler)}}]

       ["/hello"
        {:swagger {:tags ["hello"]}
         ;; hand writing swagger
         ;; https://github.com/metosin/spec-tools/blob/master/docs/05_swagger.md
         :get {:swagger
                 {:parameters [{:in "query"
                                :schema (swag/transform int? {:type :parameter :in :query})
                                :name "some number"
                                :description "it's an int, with no use"
                                :example 42
                                :default 42}]}
               :summary "say hello"
               :responses {200 {:body {:hello string?}}}
               :handler (constantly {:status 200 :body {:hello "world!"}})}}]
       ["/device"
        {:swagger {:tags ["device"]}
         :get {:summary "get all available devices with mongo"
               :coercion rcs/coercion
               :parameters {:query (s/keys :opt-un [:s/page :s/elems])}
               :responses {200 {:body (s/* :s/device)}}
               :handler #(device-get-handler % db)}
         :post {:summary "post a device"
                :coercion rcs/coercion
                :parameters {:body :s/device}
                :responses {200 {:body {:result string?}}}
                :handler #(device-post-handler % db)}}]
       ["/device/{id}"
        {:swagger {:tags ["device"]}
         :get {:summary "get certain device"
               :parameters {:path (s/keys :req-un [:dev/id])}
               :responses {200 {:body :s/device}}
               :handler (fn [req]
                          (let [{{{:keys [id]} :path} :parameters} req
                                doc (dissoc (mc/find-one-as-map db "device" {:id id} ) :_id)]
                            (if doc {:status 200 :body doc}
                                    {:status 404 :body {:result "not found"}})))}}]
       ["/rtmp"
        {:swagger {:tags ["RTMP"]}
         :post {:summary "get realtime rtmp message from mylive"
                :parameters {:body {:name string? :cmd string?}}
                :handler (fn [req]
                           (let [{{b :body} :parameters} req]
                             {:status 200}))}}]]
      opts)
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path "/swagger"
         :config {:validatorUrl nil
                  :operationsSorter "alpha"}})
      (ring/create-default-handler))))

(def app (create-app db))

(defn start [port]
  ;; used for repl
  ;; https://stackoverflow.com/questions/17792084/what-is-i-see-in-ring-app
  ;; https://stackoverflow.com/questions/39550513/when-to-use-a-var-instead-of-a-function
  (a-http/start-server #'app {:port port})
  (println "API HTTP server runing in port" port))
