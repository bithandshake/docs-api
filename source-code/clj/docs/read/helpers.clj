
(ns docs.read.helpers
    (:require [candy.api           :refer [return]]
              [docs.detect.helpers :as detect.helpers]
              [docs.import.state   :as import.state]
              [io.api              :as io]
              [regex.api           :as regex]
              [string.api          :as string]
              [syntax.api          :as syntax]))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn alter-filepath
  ; @param (map) options
  ; @param (string) layer-name
  ; @param (string) api-filepath
  ; @param (string) alias
  ;
  ; @return (string)
  [{:keys [abs-path code-dirs] :as options} layer-name api-filepath alias]
  ; If the reader cannot find a namespace in the same code-dir with the api file,
  ; it tries to detect it as a .cljc file and looks up every code-dir to find.
  (letfn [(f0 [[namespace %]] (if (= % alias) namespace))
          (f1 [code-dir namespace] (str abs-path "/" code-dir "/" (-> namespace (string/replace-part "." "/")
                                                                                (string/replace-part "-" "_"))
                                                                  ".cljc"))]
         (let [namespace (some f0 (get-in @import.state/LAYERS [layer-name api-filepath "aliases"]))]
              (letfn [(f3 [code-dir] (let [alter-filepath (f1 code-dir namespace)]
                                          (if (io/file-exists? alter-filepath)
                                              (return          alter-filepath))))]
                     (some f3 code-dirs)))))

(defn code-filepath
  ; @param (map) options
  ; @param (string) layer-name
  ; @param (string) api-filepath
  ; @param (string) alias
  ;
  ; @example
  ; (code-filepath {...} "clj" "my-repository/source-code/my_directory/api.clj" "my-directory.my-file")
  ; =>
  ; "my-repository/source-code/my_directory/my_file.clj"
  ;
  ; @return (string)
  [{:keys [abs-path] :as options} layer-name api-filepath alias]
  (letfn [(f [[namespace %]] (if (= % alias) namespace))]
         (let [code-dir  (detect.helpers/code-dir options layer-name api-filepath alias)
               namespace (some f (get-in @import.state/LAYERS [layer-name api-filepath "aliases"]))]
              (str abs-path "/" code-dir "/" (-> namespace (string/replace-part "." "/")
                                                           (string/replace-part "-" "_"))
                                         "." (name layer-name)))))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-content
  ; @param (string) file-content
  ; @param (string) name
  ;
  ; @return (string)
  [file-content name]
  ; open-pattern: "(defn my-function"
  ;
  ; BUG#7710
  ; El??fordulhat, hogy az end-pos ??rt??ke nil!
  ; Pl.: Ha a f??ggv??nyben l??v?? valamelyik comment nem egyenl?? sz??m?? nyit?? ??s z??r??
  ;     z??r??jelet tartalmaz, akkor ...
  ;
  ; Ha a f??ggv??ny neve k??rd??jelre v??gz??dik, akkor a regex megs??rt??dik a "function-name?\n"
  ; kifejez??sre, mert a k??rd??jel k??l??nleges karakter, ez??rt azt k??l??n kell kezelni!
  (let [open-pattern (-> (str "[(]defn[-]{0,}[ ]{1,}"name"\n")
                         (string/replace-part "?" "[?]"))]
       (if-let [start-pos (regex/first-dex-of file-content (re-pattern open-pattern))]
               (if-let [end-pos (-> file-content (string/part start-pos)
                                                 (syntax/close-paren-position))]
                       (let [end-pos (+ end-pos start-pos 1)]
                            (string/part file-content start-pos end-pos))))))

(defn function-header
  ; @param (string) file-content
  ; @param (string) name
  ;
  ; @return (string)
  [file-content name]
  ; A letfn t??rben defini??lt f??ggv??nyek is rendelkezhetnek param??ter dokument??ci??val,
  ; ez??rt sz??ks??ges csak a f??ggv??ny fejl??c??ben olvasni!
  (if-let [function-content (function-content file-content name)]
          (-> function-content (string/from-first-occurence   "\n  ;"  {:return? false})
                               (string/before-first-occurence "\n  ([" {:return? true})
                               (string/before-first-occurence "\n  ["  {:return? true}))))

(defn function-code
  ; @param (string) file-content
  ; @param (string) name
  ;
  ; @return (string)
  [file-content name]
  (let [comment-pattern "[ ]{0,};"]
       (if-let [function-content (function-content file-content name)]
               (letfn [(f [function-content lap]
                          (if (= lap 512)
                              (do ; Ha az f f??ggv??ny v??gtelen ciklusba ker??l, akkor
                                  ; val??sz??n??leg valamelyik kommentben egyenl??tlen??l
                                  ; vannak elhelyezve a z??r??jelek vagy esetleg egy string
                                  ; tartalmaz ";", amit a f??ggv??ny kommentnek ??rz??kel!
                                  (println "Ooops! It looks like there is a syntax error in the function \"" name "\"")
                                  (return function-content))

                              (if-let [start-pos (regex/first-dex-of function-content (re-pattern comment-pattern))]
                                      (let [comment (-> function-content (string/part start-pos)
                                                                         (string/before-first-occurence "\n" {:return? true}))]
                                           (f (string/remove-first-occurence function-content (str comment "\n"))
                                              (inc lap)))
                                      (return function-content))))]
                      (f function-content 0)))))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-first-param
  ; @param (string) n
  ;
  ; @example
  ; (function-first-param "... ; @param (*) my-param ...")
  ; =>
  ; {"name" "my-param" "optional?" false "types" "*"}
  ;
  ; @example
  ; (function-first-param "... ; @param (*)(opt) my-param ...")
  ; =>
  ; {"name" "my-param" "optional?" true "types" "*"}
  ;
  ; @example
  ; (function-first-param "... ; @param (map) my-param {...} ...")
  ; =>
  ; {"name" "my-param" "optional?" false "sample" "{...}" "types" "map"}
  ;
  ; @return (map)
  ; {"name" (string)
  ;  "optional?" (boolean)
  ;  "sample" (string)
  ;  "types" (string)}}
  [n]
  (let [param-name (-> n (string/after-first-occurence  "  ; @param" {:return? false})
                         (string/before-first-occurence "\n"         {:return? true})
                         (string/after-last-occurence   ")"          {:return? false})
                         (string/trim))
        optional?  (-> n (string/before-first-occurence param-name   {:return? false})
                         (string/contains-part?         "(opt)"))
        types      (-> n (string/after-first-occurence  "("          {:return? false})
                         (string/before-first-occurence ")"          {:return? false}))
        sample     (-> n (string/after-first-occurence  "  ; @param" {:return? false})
                         (string/before-first-occurence "  ; @"      {:return? true})
                         (string/after-first-occurence  "\n"         {:return? false})
                         (string/remove-part            "  ; ")
                         (string/remove-part            "  ;\n")
                         (string/not-ends-with!         "\n")
                         (string/use-nil))]
      {"name" param-name "optional?" optional? "sample" sample "types" types}))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-first-usage
  ; @param (string) n
  ;
  ; @example
  ; (function-first-usage "...")
  ; =>
  ; {"call" "..."}
  ;
  ; @return (map)
  ; {"call" (string)}
  [n]
  ; 1.
  ; 2.
  ; 3. A tartalommal rendelkez?? sorok elej??r??l elt??vol??tja a "  ; " r??szt
  ; 4. T??rli a k??vetkez?? bekezd??s el??tti ??res sorokat ("  ;")
  ; 5. Ha m??r nem k??vetkezik ut??na t??bb bekezd??s, akkor lemaradna a v??g??r??l a sort??r??s,
  ;   ez??rt sz??k??sges biztos??tani, hogy sort??r??sre v??gz??dj??n!
  (let [call (-> n (string/after-first-occurence  "  ; @usage" {:return? false})
                   (string/before-first-occurence "  ; @"      {:return? true})
                   (string/remove-part            "  ; ")
                   (string/remove-part            "  ;\n")
                   (string/ends-with!             "\n"))]
       {"call" call}))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-first-example
  ; @param (string) n
  ;
  ; @example
  ; (function-first-example "... ; @example (my-function ...) => 123 ...")
  ; =>
  ; {"call" "(my-function ...)" "result" 123}
  ;
  ; @return (map)
  ; {"call" (string)
  ;  "result" (string)}
  [n]
  (let [call   (-> n (string/after-first-occurence  "  ; @example" {:return? false})
                     (string/before-first-occurence "  ; =>"       {:return? false})
                     (string/remove-part            "  ; "))
        result (-> n (string/after-first-occurence  "  ; =>"       {:return? false})
                     (string/before-first-occurence "  ; @"        {:return? true})
                     (string/remove-part            "  ; ")
                     (string/remove-part            "  ;\n")
                     (string/ends-with!             "\n"))]
       {"call" call "result" result}))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-warning
  ; @param (string) n
  ;
  ; @example
  ; (function-warning "; @warning ...")
  ; =>
  ; "..."
  ;
  ; @return (string)
  [n]
  (-> n (string/after-first-occurence  "  ; @warning" {:return? false})
        (string/before-first-occurence "  ; @"        {:return? true})
        (string/remove-part            "  ; ")
        (string/remove-part            "  ;\n")
        (string/ends-with!             "\n")))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-description
  ; @param (string) n
  ;
  ; @example
  ; (function-description "; @description ...")
  ; =>
  ; "..."
  ;
  ; @return (string)
  [n]
  (-> n (string/after-first-occurence  "  ; @description" {:return? false})
        (string/before-first-occurence "  ; @"            {:return? true})
        (string/remove-part            "  ; ")
        (string/remove-part            "  ;\n")
        (string/ends-with!             "\n")))

;; ----------------------------------------------------------------------------
;; ----------------------------------------------------------------------------

(defn function-return
  ; @param (string) n
  ;
  ; @example
  ; (function-return "... ; @return (map) {...} ...")
  ; =>
  ; {"sample" "{...}" "types" "map"}
  ;
  ; @return (map)
  ; {"sample" (string)
  ;  "types" (string)}
  [n]
  (let [types  (-> n (string/after-first-occurence  "  ; @return" {:return? false})
                     (string/after-first-occurence  "("           {:return? false})
                     (string/before-first-occurence ")"           {:return? false}))
        sample (-> n (string/after-first-occurence  "  ; @return" {:return? false})
                     (string/after-first-occurence  ")"           {:return? false})
                     (string/remove-part            "  ; ")
                     (string/remove-part            "  ;\n"))]
       {"sample" sample "types" types}))
