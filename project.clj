(defproject elevator-server "0.1.0"
  :description "FIXME: write description"
  :url "https://github.com/crosstyan/clj-mylive"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.0.0"]
                 [org.clojure/core.async "1.5.648"]
                 [aleph "0.4.7"]
                 [com.longyb/mylive "0.0.1" :exclusions [io.netty/netty-all
                                                         ch.qos.logback/logback-classic]]
                 [metosin/reitit "0.5.18"]
                 [org.clojure/spec.alpha "0.3.218"]
                 [metosin/muuntaja "0.6.8"]
                 [io.aviso/pretty "1.1.1"]
                 [ring "1.9.0"]
                 [com.novemberain/monger "3.1.0"]]
  :repl-options {:init-ns elevator-server.core})
