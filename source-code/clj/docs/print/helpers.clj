
(ns docs.print.helpers
    (:require [mid-fruits.vector :as vector]))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn sort-functions
  ; @param (maps in vector) functions
  ;
  ; @return (maps in vector)
  [functions]
  (vector/sort-items-by functions #(get % "name")))
