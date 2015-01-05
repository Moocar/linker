(defproject me.moocar/linker "0.1.0-SNAPSHOT"
  :description "An attempt at making it easier to work with leiningen sub projects"
  :url "https://github.com/Moocar/linker"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha4"]
                 [me.moocar/java.io "0.1.0"]]
  :main me.moocar.linker
  :profiles {:dev {:main ^:skip-aot me.moocar.linker}
             :uberjar {:aot [me.moocar.linker]
                       :main me.moocar.linker}})
