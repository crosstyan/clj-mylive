(ns elevator-server.http
  (:require  [aleph.http :as a-http]
             [elevator-server.global :refer [conn]]
             [monger.core :as mg]
             [reitit.ring :as ring]
             [reitit.coercion.spec]
             [mount.core :as mnt :refer [defstate]]
             [clojure.spec.alpha :as s]
             [reitit.swagger :as swagger]
             [reitit.swagger-ui :as swagger-ui]
             [reitit.coercion.spec :as rcs]
             [reitit.ring.coercion :as coercion]
             [reitit.dev.pretty :as pretty]
             [spec-tools.core :as st]
             [spec-tools.swagger.core :as swag]
             [reitit.ring.middleware.muuntaja :as muuntaja]
             [reitit.ring.middleware.exception :as exception]
             [reitit.ring.middleware.multipart :as multipart]
             [reitit.ring.middleware.parameters :as parameters]
             [ring.middleware.reload :refer [wrap-reload]]
             [muuntaja.core :as m]
             [expound.alpha :as expound]
             [clojure.java.io :as io]))

(defstate db :start (mg/get-db conn "app"))

;; example from
;; https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

(defn two-bytes? [x] (and (< x 65535) (< 0 x)))
(s/def :dev/name string?)
(s/def :dev/id (s/and int? two-bytes?))
(s/def :s/device (s/keys :req-un [:dev/name :dev/id]))

;; Alt + Shift + L reload current file to repl
;; Alt + Shift + P eval current expression from top
;; Alt + Shift + R replace to current workspace

(defn device-get-handler [req db]
  (let [{{{:keys [id]} :query} :parameters} req
        _nil (println id)
        res [{:id 25 :name "test"}]]
    {:status 200 :body res}))

(defn device-post-handler [req db]
  (let [{{b :body} :parameters} req
        _nil (println b)
        res {:result "sucess"}
        ]
    {:status 200 :body res}))

;; https://cljdoc.org/d/metosin/spec-tools/0.10.5/doc/spec-coercion
;; https://cljdoc.org/d/metosin/reitit/0.5.18/doc/coercion/clojure-spec
;; https://github.com/metosin/reitit/blob/master/doc/coercion/coercion.md
;; https://github.com/metosin/reitit/blob/master/doc/ring/coercion.md
;; https://github.com/ring-clojure/ring/wiki/Concepts


;; https://cljdoc.org/d/metosin/reitit/0.5.18/doc/ring/pluggable-coercion
(defn coercion-error-handler [status]
  (let [printer (expound/custom-printer {:theme :figwheel-theme, :print-specs? false})
        handler (exception/create-coercion-handler status)]
    (fn [exception request]
      (printer (-> exception ex-data :problems))
      (handler exception request))))

(def opts
  {:exception pretty/exception
   :data {:coercion reitit.coercion.spec/coercion
          :muuntaja m/instance
          :middleware [;; swagger feature
                       swagger/swagger-feature
                       ;; query-params & form-params
                       parameters/parameters-middleware
                       ;; content-negotiation
                       muuntaja/format-negotiate-middleware
                       ;; encoding response body
                       muuntaja/format-response-middleware
                       ;; exception handling
                       ;(exception/create-exception-middleware
                       ;  {::exception/default (partial exception/wrap-log-to-console exception/default-handler)})
                       (exception/create-exception-middleware
                         (merge
                           exception/default-handlers
                           {:reitit.coercion/request-coercion (coercion-error-handler 400)
                            :reitit.coercion/response-coercion (coercion-error-handler 500)}))
                       ;; decoding request body
                       ;; https://cljdoc.org/d/metosin/reitit/0.5.15/doc/ring/content-negotiation
                       muuntaja/format-request-middleware
                       ;; coercing response bodys
                       coercion/coerce-response-middleware
                       ;; coercing request parameters
                       coercion/coerce-request-middleware
                       ;; hot reload
                       ;; https://stackoverflow.com/questions/59379314/how-to-make-a-ring-server-reload-on-file-change
                       wrap-reload
                       ;; multipart
                       multipart/multipart-middleware]}})

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
               :parameters {:query (s/keys :req-un [:dev/id])}
               :responses {200 {:body (s/* :s/device)}}
               :handler #(device-get-handler % db)}
         :post {:summary "post a device"
                :coercion rcs/coercion
                :parameters {:body :s/device}
                :responses {200 {:body {:result string?}}}
                :handler #(device-post-handler % db)}}
        ]] opts)
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path "/swagger"
         :config {:validatorUrl nil
                  :operationsSorter "alpha"}})
      (ring/create-default-handler))))

(def app (create-app db))

(defn start [port]
  (mnt/start conn)
  ;; used for repl
  ;; https://stackoverflow.com/questions/17792084/what-is-i-see-in-ring-app
  ;; https://stackoverflow.com/questions/39550513/when-to-use-a-var-instead-of-a-function
  (a-http/start-server #'app {:port port})
  (println "API HTTP server runing in port" port))
