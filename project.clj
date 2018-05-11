(defproject aluminium "0.2-SNAPSHOT"
  :description "A web application showcasing image recognition using deep learning"
  :url "https://deeplearning.magnet.coop/"

  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.json "0.2.6"]

                 ;; Duct
                 [duct/core "0.6.2"]
                 [duct/module.logging "0.3.1"]
                 [duct/module.web "0.6.4"]
                 [duct/module.cljs "0.3.2" :exclusions [org.clojure/tools.nrepl]]
                 [duct/compiler.sass "0.2.0"]

                 ;; ClojureScript
                 [org.clojure/clojurescript "1.9.946"]
                 [cljs-ajax "0.5.8"]

                 ;; Re-frame
                 [re-frame "0.10.4"]
                 [reagent "0.7.0"]
                 [day8.re-frame/http-fx "0.1.2"]

                 ;; tus.io
                 [org.akvo/resumed "1.27.7406c5e5be3ccd6d9e1f99d29f05ab1251682de7"]

                 ;; Datomic peer library
                 [com.datomic/datomic-free "0.9.5697"]

                 ;; Override Datomic peer library dependency to match Duct's cljs module.
                 [com.google.guava/guava "20.0"]

                 ;; SQLite
                 [duct/module.sql "0.4.2"]
                 [org.xerial/sqlite-jdbc "3.21.0.1"]
                 [duct/migrator.ragtime "0.2.2"]

                 ;; Cortex deep learning library
                 [thinktopic/experiment "0.9.22"]
                 ;; To manipulate images (resize, crop, colour-reduction, etc.)
                 [thinktopic/think.image "0.4.16"]
                 [net.mikera/imagez "0.12.0"]
                 ;; Cortex needs CUDA 8.0.x and CUDNN 5.x as of now (Jan 2018). Adjust accordingly
                 [org.bytedeco.javacpp-presets/cuda "8.0-1.2"]]
  :plugins [[duct/lein-duct "0.10.6"]
            [jonase/eastwood "0.2.5"]]
  :main ^:skip-aot aluminium.main
  :omit-source true
  :uberjar-name "aluminium-standalone.jar"
  :resource-paths ["resources" "target/resources"]
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :repl {:prep-tasks   ^:replace ["javac" "compile"]
          :repl-options {:init-ns user
                         :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]
                         :host "0.0.0.0"
                         :port 4001}}
   :uberjar {:aot        :all
             :prep-tasks ["javac" "compile" ["run" ":duct/compiler"]]}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :dependencies   [[integrant/repl "0.2.0"]
                                   [duct/server.figwheel "0.2.1" :exclusions [org.clojure/tools.nrepl]]
                                   [eftest "0.4.1"]
                                   [kerodon "0.9.0" :exclusions [clj-time commons-codec]]]}})
