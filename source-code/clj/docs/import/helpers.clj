
(ns docs.import.helpers
    (:require [string.api :as string]
              [syntax.api :as syntax]))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn first-refer
  ; @param (string) n
  ;
  ; @example
  ; (first-refer "... [my-namespace :refer [my-refer your-refer]] ...")
  ; =>
  ; {"my-refer" "my-namespace"}
  ;
  ; @return (map)
  [n]
  (let [open-bracket-position  (syntax/open-bracket-position  n)
        close-bracket-position (syntax/close-bracket-position n)
        bracket-content        (string/part n (inc open-bracket-position) close-bracket-position)]
       (if (string/contains-part? bracket-content " :refer ")
           (let [namespace (-> bracket-content (string/before-first-occurence " "        {:return? false}))
                 refer     (-> bracket-content (string/after-first-occurence  " :refer " {:return? false})
                                               (string/after-first-occurence  "["        {:return? false})
                                               (string/before-first-occurence "]"        {:return? false})
                                               (string/split                  #" "))]
                (letfn [(f [refer name] (assoc refer name namespace))]
                       (reduce f {} refer))))))

(defn first-alias
  ; @param (string) n
  ;
  ; @example
  ; (first-alias "... [my-namespace :as my-alias] ...")
  ; =>
  ; ["my-namespace" "my-alias"]
  ;
  ; @return (strings in vector)
  ; [(string) namespace
  ;  (string) alias]
  [n]
  (let [open-bracket-position  (syntax/open-bracket-position  n)
        close-bracket-position (syntax/close-bracket-position n)
        bracket-content        (string/part n (inc open-bracket-position) close-bracket-position)]
       (if (string/contains-part? bracket-content " :as ")
           (let [namespace (-> bracket-content (string/before-first-occurence " "     {:return? false}))
                 alias     (-> bracket-content (string/after-first-occurence  " :as " {:return? false})
                                               (string/before-first-occurence " "     {:return? true}))]
                [namespace alias])
           (let [namespace (-> bracket-content (string/before-first-occurence " "     {:return? true}))
                 alias namespace]
                [namespace alias]))))

(defn first-def
  ; @param (string) n
  ;
  ; @example
  ; (first-def "... (def my-name my-value) (def ...")
  ; =>
  ; ["my-name" "my-value"]
  ;
  ; @return (strings in vector)
  ; [(string) name
  ;  (string) value]
  [n]
  (let [name  (-> n (string/after-first-occurence  " " {:return? false})
                    (string/trim)
                    (string/before-first-occurence " " {:return? false}))
        value (-> n (string/after-first-occurence name {:return? false})
                    (string/trim)
                    (string/before-first-occurence ")" {:return? false}))]
       [name value]))
