(defproject elevator-server "0.1.0"
  :description "FIXME: write description"
  :url "https://github.com/crosstyan/clj-mylive"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.0.0"]
                 [org.clojure/core.async "1.5.648"]
                 [io.netty/netty-all "4.1.76.Final"]
                 [aleph "0.4.7" :exclusions [org.clojure/tools.logging
                                             io.netty/netty-transport
                                             io.netty/netty-transport-native-epoll
                                             io.netty/netty-codec
                                             io.netty/netty-codec-http
                                             io.netty/netty-handler
                                             io.netty/netty-handler-proxy
                                             io.netty/netty-resolver]]
                 [com.longyb/mylive "0.0.1" :exclusions [io.netty/netty-all]]
                 [metosin/reitit "0.5.18"]
                 [org.clojure/spec.alpha "0.3.218"]
                 [metosin/muuntaja "0.6.8"]
                 [org.clojure/tools.logging "1.2.4"]
                 [ring "1.9.5"]
                 [com.novemberain/monger "3.5.0"]]
  :repl-options {:init-ns elevator-server.core})
