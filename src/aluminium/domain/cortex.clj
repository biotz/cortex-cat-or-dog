(ns aluminium.domain.cortex
  (:require [clojure.java.io :as io]
            [cortex.compute.cpu.driver :as cpu-driver]
            [cortex.compute.cpu.tensor-math :as cpu-tm]
            [cortex.nn.execute :as execute]
            [cortex.nn.layers :as layers]
            [cortex.nn.network :as network]
            [cortex.tensor :as ct]
            [cortex.util :as util]
            [duct.logger :as logger]
            [integrant.core :as ig]
            [mikera.image.core :as imagez]
            [think.datatype.core :as dtype]
            [think.image.core]
            [think.image.image :as image]))

;; This file heavily borrows from
;; https://github.com/gigasquid/cats-dogs-cortex-redux/blob/master/src/cats_dogs_cortex_redux/core.clj

(def ^:const image-size
  "Image size used for training and classification"
  224)

(def ^:const default-network-file
  "Default network configuration file, used if no network file is
  passed to setup-netork."
  "trained-network.nippy")

(def classes
  "A vector with all the classes (labels) used for classification.
  The order *is* important (it has to be the same order used during
  the training phase)."
  ["cat" "dog"])

(defn get-class
  "A convienence function to get the class name from the index IDX."
  [idx]
  (get classes idx))

(defn- load-network
  "Load the definition and configuration of the neural network model
  from `network-file`. `chop-layers` and `top-layers` are optional layers to
  be removed (choped) from the network and added to the end of the
  compute-graph of the network, after it's been loaded. `chop-layers`
  and `top-layers` are commonly used to fine-tune the network during the
  training phase.

  If `network-file` doesn't point to a valid network model definition,
  NIL is returned, and an error message is logged to `logger`."
  [network-file logger & [chop-layer top-layers]]
  (try
    (if-let [network (util/read-nippy-file network-file)]
      (let [modified-net (if chop-layer
                           (let [;;remove last layer(s)
                                 chopped-net (network/dissoc-layers-from-network network chop-layer)
                                 ;; set layers to non-trainable
                                 nodes (get-in chopped-net [:compute-graph :nodes]) ;;=> {:linear-1 {<params>}
                                 frozen-nodes (into {} (mapv (fn [[k v]] [k (assoc v :non-trainable? true)]) nodes))
                                 modified-net (assoc-in chopped-net [:compute-graph :nodes] frozen-nodes)]
                             modified-net)
                           network)
            modified-net (if top-layers
                           ;; add top layers
                           (network/assoc-layers-to-network modified-net (flatten top-layers))
                           modified-net)]
        modified-net))
    (catch Throwable e
      ;; The network file doesn't point to valid network model (or the path doesn't exist)
      (let [cause (or (.getMessage e) "Unknown error")
            details (or (ex-data e) {})]
        (logger/log logger :error ::invalid-network-file {:network-file network-file
                                                          :exception-details {:cause cause
                                                                              :additional-details details}})
        nil))))

(defn setup-network
  "Retrieve the network model and settings from `network-file`,
  and set it up. We decouple this step from the image
  labeling/classification to be able to setup the network once, and
  use it as many times as needed without incurring in the setup
  overhead each time.

  It logs a report message to `logger` about the file being used. If
  `network-file` doesn't point to a valid network model definition, NIL
  is returned."
  [network-file logger]
  (logger/log logger :report ::setting-up-network {:network-file network-file})
  (load-network network-file logger))

(defn- image-file->net-input
  "Convert `src-image` into the data format required for the neural
  network model, and return it."
  [src-image]
  (let [width (image/width src-image)
        height (image/height src-image)
        ;;Ensure image is correct size
        src-image (if-not (= (image/width src-image) (image/height src-image) image-size)
                    (imagez/resize src-image image-size image-size)
                    src-image)
        ary-data (image/->array src-image)
        ;;mask out the b-g-r channels
        mask-tensor (-> (ct/->tensor [(bit-shift-left 0xFF 16)
                                      (bit-shift-left 0xFF 8)
                                      0xFF]
                                     :datatype :int)
                        (ct/in-place-reshape [3 1 1]))
        ;;Divide to get back to range of 0-255
        div-tensor (-> (ct/->tensor [(bit-shift-left 1 16)
                                     (bit-shift-left 1 8)
                                     1]
                                    :datatype :int)
                       (ct/in-place-reshape [3 1 1]))
        ;;Use the normalization the network expects
        subtrack-tensor (-> (ct/->tensor [123.68 116.779 103.939])
                            (ct/in-place-reshape [3 1 1]))
        ;;Array of packed integer data
        img-tensor (-> (cpu-tm/as-tensor ary-data)
                       (ct/in-place-reshape [image-size image-size]))
        ;;Result will be b-g-r planar data
        intermediate (ct/new-tensor [3 image-size image-size] :datatype :int)
        result (ct/new-tensor [3 image-size image-size])]
    (ct/binary-op! intermediate 1.0 img-tensor 1.0 mask-tensor :bit-and)
    (ct/binary-op! intermediate 1.0 intermediate 1.0 div-tensor :/)
    (ct/assign! result intermediate)
    ;;Switch to floating point for final subtract
    (ct/binary-op! result 1.0 result 1.0 subtrack-tensor :-)
    {:data (cpu-tm/as-java-array result)}))

(defn- prepare-image
  "Load the image contained in `image-path` file and convert it to the
  format used with the neural network classification functions. Once
  converted, return it. If `image-path` points to a file that is not a
  valid image, an error message is logged to `logger` and NIL is returned."
  [image-path logger]
  (try
    (if-let [image (imagez/load-image (io/file image-path))]
      (ct/with-stream (cpu-driver/main-thread-cpu-stream)
        (ct/with-datatype :float (image-file->net-input image))))
    (catch Throwable e
      ;; The path doesn't point to an image file, or the image uses a
      ;; format not recognised by the library.
      (let [cause (or (.getMessage e) "Unknown error")
            details (or (ex-data e) {})]
        (logger/log logger :error ::invalid-image-file {:image-file image-path
                                                        :exception-details {:cause cause
                                                                            :additional-details details}})
        nil))))

(defn label-image
  "Load an image from `image-path` and classify it, using `network-model`.
  Returns a map with a `:guess` key. The associated value is a map with
  keys `:class` for the guessed class label, and `:prob` for the guess
  probability (float value).

  If a problem is encountered processing the image (e.g., the path
  doesn't exist, the file is not valid image, etc.) NIL is returned."
  [image-path network-model logger]
  (if-let [data-item (prepare-image image-path logger)]
    {:guess (let [[result] (execute/run network-model [data-item])
                  r-idx (util/max-index (:labels result))]
              {:prob (get-in result [:labels r-idx]) :class (get-class r-idx)})}
    nil))

;; Sets up a Cortex neural network model.
(defmethod ig/init-key :aluminium.domain/cortex [_ {:keys [network-file logger] :as options}]
  (let [network-file (or network-file default-network-file)
        nn (setup-network network-file logger)]
    (if nn
      {:nn nn}
      (throw (ex-info "Invalid neural network model file" {:network-file network-file} )))))
