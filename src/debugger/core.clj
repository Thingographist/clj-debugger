(ns debugger.core
  (:require [clojure.reflect]
            [clojure.repl]))

(declare ^:dynamic *locals*)
(def ^:dynamic *code-context-lines* 5)

(defmacro dbg
  [x]
  `(let [x# ~x]
     (do
       (println '~x "->" x#)
       x#)))

(defn prompt-fn [signal-val]
  (if-not @signal-val
    (printf "break %s=> " (ns-name *ns*))))

(defn print-table
  ([ks rows]
     (when (seq rows)
       (let [widths (map
                     (fn [k]
                       (apply max (count (str k)) (map #(count (str (get % k))) rows)))
                     ks)
             spacers (map #(apply str (repeat % "-")) widths)
             fmts (map #(str "%-" % "s") widths)
             fmt-row (fn [leader divider trailer row]
                       (str leader
                            (apply str (interpose divider
                                                  (for [[col fmt] (map vector (map #(get row %) ks) fmts)]
                                                    (format fmt (str col)))))
                            trailer))]
         (println)
         (doseq [row rows]
           (println (fmt-row " " " " " " row))))))
  ([rows] (print-table (keys (first rows)) rows)))

(defn no-sources-found [fn-symbol]
  (str "No source found for " fn-symbol))

(defn help-message []
  (print-table
    [:cmd :long :help]
    [{:cmd "(h)" :long "(help)"      :help "prints this help"}
     {:cmd "(w)" :long ""            :help "prints short code of breakpointed function"}
     {:cmd ""    :long "(whereami)"  :help "prints full code of breakpointed function"}
     {:cmd "(l)" :long "(locals)"    :help "prints locals"}
     {:cmd "(c)" :long "(continue)"  :help "continues execution and preserves the result"}
     {:cmd "(q)" :long "(quit)"      :help "or type Ctrl-D to exit break-repl and pass last result further"}
     {:cmd ""    :long ""            :help ""}
     {:cmd ""    :long ""            :help "you can also access locals directly and build sexp with them"}
     {:cmd ""    :long ""            :help "any last execution result (but `nil`) before exit will be passed further"}
     {:cmd ""    :long ""            :help "if last result is `nil` execution will continue normally"}]))

(defn- format-line-with-line-numbers [short? break-line line line-number]
  (cond
    (= break-line line-number) (str "=> " line-number ": " line)
    (and short? (<= line-number (- break-line *code-context-lines*))) nil
    (and short? (>= line-number (+ break-line *code-context-lines*))) nil
    :else (str "   " line-number ": " line)))


(defn- map-numbered-source-lines [f fn-symbol]
  (let [fn-source (clojure.repl/source-fn fn-symbol)
        fn-source-lines (or (clojure.string/split fn-source #"\n") [])
        fn-meta (-> fn-symbol find-var meta)
        fn-source-start-line (or (:line fn-meta) 0)
        line-numbers (map (partial + fn-source-start-line) (range))]
    (mapv f fn-source-lines line-numbers)))


(defn print-full-source [break-line fn-symbol]
  (let [fn-meta (-> fn-symbol find-var meta)
        format-fn (partial format-line-with-line-numbers false break-line)
        lines (map-numbered-source-lines format-fn fn-symbol)]
    (if (empty? lines)
      (println (no-sources-found fn-symbol))
      (do
        (println)
        (println (clojure.string/join "\n" lines) "\n")))))


(defn print-short-source [break-line fn-symbol]
  (let [fn-meta (-> fn-symbol find-var meta)
        head (str "\nin: " (:ns fn-meta) "/" (:name fn-meta) " " (pr-str (:arglists fn-meta)) "\n")
        format-fn (partial format-line-with-line-numbers true break-line)
        lines (map-numbered-source-lines format-fn fn-symbol)
        ;; _ (println "!!!" (count lines))
        ]
    (cond
      (= 0 (count lines))
        (println (no-sources-found fn-symbol))
      (> (* 2 *code-context-lines*) (count lines))
        (print-full-source break-line fn-symbol)
      :else
        (do
          (println head)
          (println (clojure.string/join "\n" (filter some? lines)))
          (println)))))


(defn eval-fn [break-ns break-line fn-symbol signal-val return-val cached-cont-val locals-fn cont-fn form]
  (do
    (condp re-find (clojure.string/trim (str form))
      #"\(h\)|\(help\)" (do
                          (help-message)
                          (println))

      #"\(w\)" (do
                 (print-short-source break-line fn-symbol))

      #"\(whereami\)" (do
                        (print-full-source break-line fn-symbol))

      #"\(l\)|\(locals\)" (do
                            (println (locals-fn)))

      #"\(c\)|\(continue\)" (do
                              (reset! cached-cont-val (cont-fn)))

      #"\(q\)|\(quit\)" (do
                          (reset! signal-val :stream-end)
                          (println "Quitting debugger..."))

      ;; TODO: require & use from ns + in *ns*
      (do
        (reset!
          return-val
          (let [orig-ns (ns-name *ns*)]
            (try
              (in-ns break-ns)
              (binding [*locals* (locals-fn)]
                (eval
                  `(let ~(vec (mapcat #(list % `(*locals* '~%)) (keys *locals*)))
                     ~form)))
              (finally (in-ns orig-ns)))))))))


;; TODO use readline/jline
(defn read-fn [signal-val request-prompt request-exit]
  ;; (println "> Read-fn with" (pr-str request-prompt) "and" (pr-str request-exit))
  (or ({:line-start request-prompt :stream-end request-exit}
       (or (deref signal-val)
           (clojure.main/skip-whitespace *in*)))
      (let [input (read)]
        (clojure.main/skip-if-eol *in*)
        input)))


(defn caught-fn [e]
  (println "> Start caught-fn")
  (let [ex (clojure.main/repl-exception e)
        tr (.getStackTrace ex)
        el (when-not (zero? (count tr)) (aget tr 0))]
    (binding [*out* *err*]
      (println (str (-> ex class .getSimpleName)
                    " " (.getMessage ex) " "
                    (when-not (instance? clojure.lang.Compiler$CompilerException ex)
                      (str " " (if el (clojure.main/stack-element-str el) "[trace missing]"))))))))


(defn unmangle [s]
  (let [[ns-name fn-name & tail] (clojure.string/split s #"\$")]
    (if (and ns-name fn-name)
      (clojure.string/replace (str ns-name "/" fn-name) "_" "-"))))


(defmacro break [& body]
  (let [env (into {} (map (fn [[sym bind]] [`(quote ~sym) (.sym bind)]) &env))
        ;; _ (println "form=" &form "meta" (meta &form))
        break-line (:line (meta &form))]
    `(let [return-val# (atom nil)
           cached-cont-val# (atom nil)
           signal-val# (atom nil)
           cont-fn# #(identity ~@body)
           locals-fn# #(identity ~env)

           path-to-src# (-> (java.io.File. ".") .getCanonicalPath)
           outer-fn-symbol# (-> (Throwable.) .getStackTrace first .getClassName unmangle symbol)
           s# (println "!!!" outer-fn-symbol#)
           outer-fn-meta# (-> outer-fn-symbol# find-var meta)
           outer-fn-path# (if outer-fn-meta#
                            (str path-to-src# "/src/" (:file outer-fn-meta#) ":" (:line outer-fn-meta#))
                            (no-sources-found outer-fn-symbol#))

           macro-eval-fn# (partial eval-fn (ns-name (or (:ns outer-fn-meta#) *ns*)) ~break-line outer-fn-symbol# signal-val# return-val# cached-cont-val# locals-fn# cont-fn#)
           macro-read-fn# (partial read-fn signal-val#)
           macro-prompt-fn# (partial prompt-fn signal-val#)]

       (println "\nBreak from:" outer-fn-path# "(type \"(help)\" for help)")
       (print-short-source ~break-line outer-fn-symbol#)
       (clojure.main/repl
         :prompt macro-prompt-fn#
         :eval macro-eval-fn#
         :read macro-read-fn#
         :caught caught-fn)
       (or
         (deref return-val#)
         (deref cached-cont-val#)
         (cont-fn#)))))

