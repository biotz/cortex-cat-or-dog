(ns aluminium.views
  (:require [re-frame.core :as re-frame]
            [aluminium.subs :as subs]
            [reagent.core :as reagent]
            [aluminium.events :as events]))

;; Randomize background color
(defn- get-random-rgb-with-alpha [alpha-val]
  (let [combinations (range 0 255)
        [r g b] (take 3 (repeatedly #(rand-nth combinations)))]
    (str "rgba(" r ", " g ", " b ", " alpha-val ")")))


;; Landing-view elements
(defn toolbar []
  [:div.header {:style {:display "flex"
                        :justify-content "space-between"
                        :flex-direction "row"
                        :flex-wrap "nowrap"}}
   [:div.logo
    [:a.logo--link {:href "https://www.magnet.coop" :target "_blank"}
     [:img.logo__img {:src "/assets/logo-magnet-white.png"}]]]
   [:ul.main-nav {:style {:display "flex"
                          :justify-content "space-between"
                          :flex-direction "row"
                          :flex-wrap "nowrap"}}
    [:li [:a {:href "https://github.com/magnetcoop/cortex-cat-or-dog"}
          [:img.main-nav__ribbon
           {:src "https://s3.amazonaws.com/github/ribbons/forkme_right_white_ffffff.png"
            :alt "Fork me on GitHub"}]] #_[:a {:href "#"}
          "Fork me"]]
    #_[:li [:a {:href "#"}
          "Optional link 2"]]
    #_[:li [:a {:href "#"}
          "Optional link 3"]]]])

(defn footer []
  [:div.footer {:style {:flex-shrink "0"}}
   [:ul.main-nav
    [:li
     [:span.main-nav__intro "Built with care using"]
     [:br]
     [:span.main-nav__link-group
      [:span [:a {:href "https://clojure.org/" :target "_blank"} "Clojure"] " | "]
      [:span [:a {:href "https://clojurescript.org/" :target "_blank"} "ClojureScript"] " | "]
      [:span [:a {:href "https://github.com/duct-framework/duct" :target "_blank"} "Duct"] " | "]
      [:span [:a {:href "https://github.com/thinktopic/cortex" :target "_blank"} "Cortex"] " | "]
      [:span [:a {:href "https://github.com/Day8/re-frame" :target "_blank"} "Re-frame"] " | "]
      [:span [:a {:href "https://www.docker.com/" :target "_blank"} "Docker"]]
      ]]
    [:li [:a {:href "https://medium.com/magnetcoop" :target "_blank"}
          "Blog"]]]])

(defn loading-spinner []
  [:div.spinner
   [:div.spinner__double-bounce-1]
   [:div.spinner__double-bounce-2]])

;; TODO: Probably is more coherent if we refactor these two ratoms to be part of app-db
(def upload-not-done? (reagent/atom true))
(def preview-file-url (reagent/atom nil))

(defn cleanup-prev-state []
  (reset! preview-file-url nil)
  (reset! upload-not-done? true)
  (re-frame/dispatch [::events/reset-classification-result]))

(defn- start-classification [file-path]
  (re-frame/dispatch [::events/start-classification {:file-path file-path}]))

(defn- show-uploaded-image [file-url]
  (reset! preview-file-url file-url)
  (swap! upload-not-done? not))

(defn- upload-and-launch-classification
  [event]
  (let [file (aget (-> event .-target .-files) 0)
        uploader (new js/tus.Upload file #js {:endpoint (str (.. js/window -location -origin) "/files")
                                              :retryDelays #js [0 1000 3000 5000]
                                              :chunkSize 512000
                                              :metadata #js {:filename (.-name file)}
                                              :onError #(.log js/console %)})]
    ;; We need to set the onSuccess event handler separately because
    ;; we don't know how to reference the "uploader" object itself
    ;; from inside it's creation.
    (set! (.-options.onSuccess uploader) (fn []
                                           (start-classification (.-url uploader))
                                           (show-uploaded-image (.createObjectURL js/URL file))))
    (.start uploader)))

(defn upload-form []
  [:div
   [:div.usage-warning "I am obsessed: I can only recognize cats and dogs!"]
   [:div
    [:label.upload-button {:for "upload"}
     "Upload image"
     [:input.file-upload_input {:type "file"
                                :id "upload"
                                :accept "image/*"
                                :on-change (fn [event]
                                             (cleanup-prev-state)
                                             (re-frame/dispatch [::events/set-loading true])
                                             (upload-and-launch-classification event))}]]]
   [:br]])

(defn classification-details [status probability]
  (case status
    :ok [:div.main-panel__results.main-panel__results--header
         (str (if (>= probability 0.9)
                (str "Probability: " (/ (Math/trunc (* probability 10000)) 100) "%. ")
                "")
              "Try it again!")]
    :error [:div.main-panel__results "Classification error! Plase, try it again."]
    [:div]))

(defn classification-handler []
  (let [loading? @(re-frame/subscribe [::subs/loading?])
        classification-job @(re-frame/subscribe [::subs/classification-job])
        classification-result @(re-frame/subscribe [::subs/classification-result])
        {:keys [class probability status]} classification-result]
    [:div.main-panel
     [:div
      [:div.main-panel__title
       (if class
         (if (>= probability 0.9)
           [:span (str "It's a " class "!")]
           [:div.main-panel__unsure  "Cat? Dog? Unsure..."])
         [:span "Cat or dog?"])]]
     (if loading?
       [:div.main-panel__loading-text (if-not classification-job "Uploading image..." "Calculating...")])
     (if loading?
       [loading-spinner])
     [classification-details status probability]
     (if-not (or loading? classification-job)
       [upload-form])]))

(defn main-panel []
  (let [classification-result @(re-frame/subscribe [::subs/classification-result])]
    [:div {:style {:background-repeat "no-repeat"
                   :background-size "cover"
                   :background-image (if (= @upload-not-done? true)
                                       "none"
                                       (str "url(" @preview-file-url ")"))}}
     [:div {:style
            {:display "flex"
             :flex-flow "column wrap"
             :flex-direction "column"
             :background-color (if-not (= @upload-not-done? true)
                                 "none"
                                 (get-random-rgb-with-alpha ;; It does not get updated
                                   (if (= (:status classification-result) :ok) "0.4" "0.6")))}}
      [toolbar]
      [classification-handler]
      [footer]]]))