(ns elevator-server.http
  (:require  [aleph.http :as http]
             [reitit.ring :as ring]
             [reitit.coercion.spec]
             [reitit.swagger :as swagger]
             [reitit.swagger-ui :as swagger-ui]
             [reitit.ring.coercion :as coercion]
             [reitit.dev.pretty :as pretty]
             [reitit.ring.middleware.muuntaja :as muuntaja]
             [reitit.ring.middleware.exception :as exception]
             [reitit.ring.middleware.multipart :as multipart]
             [reitit.ring.middleware.parameters :as parameters]
             [ring.middleware.reload :refer [wrap-reload]]
             [muuntaja.core :as m]
             [clojure.java.io :as io]))

;; example from
;; https://github.com/metosin/reitit/blob/master/examples/ring-swagger/src/example/server.clj

(defn device-get-handler [req]
  (let [res [{:id 0}]]
    {:status 200 :body res}))

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
                       (exception/create-exception-middleware
                         {::exception/default (partial exception/wrap-log-to-console exception/default-handler)})
                       ;; decoding request body
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

(def app
  (ring/ring-handler
    (ring/router
      [["/swagger.json"
        {:get {:no-doc true
               :swagger {:info {:title "API for device manipulation"
                                :description "with reitit-ring"}}
               :handler (swagger/create-swagger-handler)}}]
       ["/device"
        {:get {:summary "get available devices with mongo"
               :response {200 {:body [{:id int?}]}}
               :handler device-get-handler}}]] opts)
    (ring/routes
      (swagger-ui/create-swagger-ui-handler
        {:path "/swagger"
         :config {:validatorUrl nil
                  :operationsSorter "alpha"}})
      (ring/create-default-handler))))

(defn start [port]
  (http/start-server #'app {:port port, :join? false})
  (println "API HTTP server running in port" port))
