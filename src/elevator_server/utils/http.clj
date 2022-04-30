(ns elevator-server.utils.http
  (:require
    [elevator-server.global :refer [conn db-http]]
    [reitit.coercion.spec]
    [reitit.swagger :as swagger]
    [reitit.ring.coercion :as coercion]
    [reitit.dev.pretty :as pretty]
    [reitit.ring.middleware.muuntaja :as muuntaja]
    [reitit.ring.middleware.exception :as exception]
    [reitit.ring.middleware.multipart :as multipart]
    [reitit.ring.middleware.parameters :as parameters]
    [ring.middleware.reload :refer [wrap-reload]]
    [monger.conversion :refer [from-db-object]]
    [muuntaja.core :as m]
    [expound.alpha :as expound]))

;; https://cljdoc.org/d/metosin/reitit/0.5.18/doc/ring/pluggable-coercion
(defn coercion-error-handler [status]
  (let [printer (expound/custom-printer {:theme :figwheel-theme, :print-specs? false})
        handler (exception/create-coercion-handler status)]
    (fn [exception request]
      (printer (-> exception ex-data :problems))
      (handler exception request))))


(defn default-ex-handler
  "Default safe handler for any exception."
  [^Exception e _]
  {:status 500
   :body   {:type      "exception"
            :class     (.getName (.getClass e))
            :message   (.getMessage e)
            :exception (ex-data e)}})

(def opts
  {:exception pretty/exception
   :data      {:coercion   reitit.coercion.spec/coercion
               :muuntaja   m/instance
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
                                {::exception/default                (partial exception/wrap-log-to-console default-ex-handler)
                                 :ring.util.http-response/response  (partial exception/wrap-log-to-console default-ex-handler)
                                 :muuntaja/decode                   (partial exception/wrap-log-to-console default-ex-handler)
                                 :reitit.coercion/request-coercion  (coercion-error-handler 400)
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
