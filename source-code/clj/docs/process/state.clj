
(ns docs.process.state)

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

; @atom (map)
;  {"clj" (map)
;   "cljc" (map)
;   "cljs" (map)}
(def LAYERS (atom {}))

; @atom (map)
(def COVER (atom {}))

; @atom (map)
(def COMMON (atom {}))
