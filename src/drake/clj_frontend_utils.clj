(ns drake.clj-frontend-utils
  (:require [clojure.string :as str]
            [drake.parser :as parse]
            [drake.parser_utils :refer [illegal-syntax-error-fn
                                        throw-parse-error
                                        state-s]]
            [drake.steps :as d-steps]
            [drake.utils :as d-utils]
            [name.choi.joshua.fnparse :as p]
            [slingshot.slingshot :refer [throw+]]
            [clojure.pprint :refer [pprint]]))

(defn tprn
  "Transparent prn"
  [x]
  (prn x)
  x)

(defn tpprint
  "Transparent pretty print"
  [x]
  (pprint x)
  x)

(def var-re
  "Regular Expression for variable subsitution.  re-groups on the
  match should return a two element vector, where the first element is
  $[XXX] and the second is just XXX"
  #"\$\[([^\]]*)\]")

(defn var-check
  "Check if var-name is a key in vars.  If it isn't raise an error."
  [vars var-name]
  (when-not (contains? vars var-name)
    (throw+
     {:msg (format "variable \"%s\" undefined at this point." var-name)})))

(defn var-sub
  "Substitute the matches to $[XXX] with the value of XXX in the vars
  map. Throw an error if XXX is not found.  Return an array of characters"
  [vars s]
  (let [sub-fn (fn [var-match]
                 (let [var-name (second var-match)]
                   (var-check vars var-name)
                   (vars var-name)))]
    (concat (str/replace s var-re sub-fn))))

;; (var-sub {"xxx" "value1" "yyy" "value2"} "test$[xxx]sdf $[yyy] sdf")
;; (var-sub {"xxx" "value1"} "test$[xxx]sdf $[yyy] sdf")
;; (var-sub {} "test no var")
;; (var-sub {"test_var" "test_value"} "$[test_var]")

(defn var-sub->str
  [vars s]
  (apply str (var-sub vars s)))

(def var-split-re
  "Regular expression to split a string at variables while also
  returning the strings before and after each variable.

  Given this string, \"XXX$[var]YYY$[var2]\"

  The first match will be a re-group with four elements as follows
  [\"XXX$[var]YYY\" \"XXX\" \"var\" \"YYY\"]

  The first element is the entire match, the second element is the
  string before the variable, the third element is the variable name
  and the forth elment is the string after the variable up to the next
  variable."
  #"((?:(?!\$\[).)*)\$\[([^\]]*)\]((?:(?!\$\[).)*)")

(defn var-place
  "Replace all $[XXX] in a string with #{XXX}. Return an array of
  characters and #{XXX} placeholders.  To check that the var-names
  exist, pass in a vars map."
  ([s]
     (var-place false s))
  ([vars s]
     (if-let [raw-matches (re-seq var-split-re s)]
       (let [var-matches (map (comp vec (partial drop 1)) raw-matches)
             var-names (map second var-matches)
             with-placeholders (map
                                #(update-in % [1] hash-set)
                                var-matches)]
         (when vars (dorun (map (partial var-check vars) var-names)))
         (flatten
          (map #(if (string? %) (vec %) %)
               (flatten with-placeholders))))
       (concat s))))

(defn add-placeholder-vars
  [vars infiles outfiles]
  (merge vars
         (into {}
               (map #(vector (first %1) "*placeholder*")
                    (merge (parse/inouts-map infiles
                                             "INPUT")
                           (parse/inouts-map outfiles
                                             "OUTPUT"))))))

(defn remove-prefix
  "Remove ! and ? prefixes from the file f"
  [f]
  (if (#{\! \?} (first f))
    (d-utils/clip f)
    f))

(defn remove-tag-symbol
  "Revove % from start of tags"
  [tags]
  (let [%-remover (fn [t] (if (#{\%} (first t))
                              (d-utils/clip t)
                              t))]
    (map %-remover tags)))

(defn split-tags-from-files
  [tags-and-files]
  (parse/demix tags-and-files #(= \% (first %))))

;; To Do.  These will all need tests.
(defn check-step-validity
  "Make sure a step is valid.  Possible problems: Undefined method,
  invalid method-mode, method-mode is set without method, commands
  with method-mode set to \"use\""
  [parse-tree step-map]
  (let [step-method (get-in step-map [:opts :method])
        method-mode (get-in step-map [:opts :method-mode])
        methods (set (keys (:methods parse-tree)))
        commands (:cmds step-map)
        state nil]
    (cond
     (not (or (empty? step-method) (methods step-method)))
     (throw-parse-error state
                        (format "method '%s' undefined at this point." step-method)
                        nil)

     (not (or (empty? method-mode) (#{"use" "append" "replace"} method-mode)))
     (throw-parse-error state
                        (format
                         (str "%s is not a valid method-mode, valid values are: "
                              "use (default), append, and replace.")
                         method-mode)
                        nil)

     (not (or step-method (empty? method-mode)))
     (throw-parse-error state
                        "method-mode specified but method name not given"
                        nil)

     (and step-method (not (#{"append" "replace"} method-mode))
          (not (empty? commands)))
     (throw-parse-error state
                        (str "commands not allowed for method calls "
                             "(use method-mode:append or method-mode:replace "
                             "to allow)")
                        nil))))
(defn add-step-ids
  "Add Unique ID to each step in parse-tree"
  [parse-tree]
  (let [steps (map (fn [step]
                     (assoc step :id (str (java.util.UUID/randomUUID))))
                   (:steps parse-tree)) ; add unique ID to each step
        steps (into [] steps)]
    (assoc parse-tree :steps steps)))

(defn compile-parse-tree
  "add-dependencies, calc-step-dirs and add-step-ids to
  parse-tree"
  [parse-tree]
  (-> parse-tree
      d-steps/add-dependencies
      d-steps/calc-step-dirs
      add-step-ids))

;; Below here are functions for testing

(defn ensure-final-newline
  "Make a the string ends with a newline"
  [s]
  (if (.endsWith s "\n")
    s
    (str s "\n")))

(defn str->parse-tree
  "Take a string s and makes a raw parse-tree."
  ([s]
     (let [state (struct state-s
                         (ensure-final-newline s)
                         {}
                         #{}
                         1 1)]
       (p/rule-match parse/workflow
                     #((illegal-syntax-error-fn "start of workflow")
                       (:remainder %2) %2) ;; fail
                     #((illegal-syntax-error-fn "workflow")
                       (:remainder %2) %2) ;; incomplete match
                     state))))


(defn file->parse-tree
  "Just a function to help during development.  Take a file and
  convert it into a raw parse-tree"
  ([file-name]
     (let [d-file (slurp file-name)]
       (str->parse-tree d-file))))

;; (def tree (file->parse-tree "test.drake.txt" {}))
;; (pprint tree)
