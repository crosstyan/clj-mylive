(defproject elevator-server "0.1.0"
  :description "FIXME: write description"
  :url "https://github.com/crosstyan/clj-mylive"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.0.0"]
                 [org.clojure/core.async "1.5.648"]
                 [metosin/spec-tools "0.10.5"]
                 [expound "0.9.0"]
                 [org.clojure/core.async "1.5.648"]
                 [org.clojure/data.json "2.4.0"]
                 [io.netty/netty-all "4.1.76.Final"]
                 [ch.qos.logback/logback-classic "1.2.11"]
                 [funcool/octet "1.1.2"]
                 [aleph "0.4.7" :exclusions [org.clojure/tools.logging
                                             io.netty/netty-transport
                                             io.netty/netty-transport-native-epoll
                                             io.netty/netty-codec
                                             io.netty/netty-codec-http
                                             io.netty/netty-handler
                                             io.netty/netty-handler-proxy
                                             io.netty/netty-resolver]]
                 [com.longyb/mylive "0.0.1" :exclusions [io.netty/netty-all
                                                         ch.qos.logback/logback-classic]]
                 [metosin/reitit "0.5.18"]
                 [com.google.guava/guava "31.1-jre" :exclusions [org.slf4j/slf4j-api
                                                             org.slf4j/slf4j-log4j12
                                                             org.slf4j/slf4j-simple
                                                             org.slf4j/slf4j-nop
                                                             org.slf4j/slf4j-jdk14]]
                 [org.clojure/spec.alpha "0.3.218"]
                 [metosin/muuntaja "0.6.8"]
                 [org.clojure/tools.logging "1.2.4"]
                 [ring "1.9.5"]
                 [mount "0.1.16"]
                 [com.novemberain/monger "3.6.0"]]
  :repl-options {:init-ns elevator-server.core})
