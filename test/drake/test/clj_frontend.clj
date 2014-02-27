(ns drake.test.clj-frontend
  (:require [clojure.test :refer :all]
            [drake.clj-frontend :refer :all]
            [drake.clj-frontend-utils :as utils :refer
                                      [tpprint]]))


(deftest var-check-test
  (is (= (utils/var-check {"a" 1 "b" 2} "a") nil))
  (is (thrown? Exception
               (utils/var-check {"a" 1 "b" 2} "c"))))

(deftest var-re-test
  (are [s] (= (re-find utils/var-re s) ["$[var]" "var"])
       "$[var]"
       "aaa$[var]"
       "aaa$[var]bbb"
       "$other_var $[var]"
       "$[var] $other_var"))

(deftest var-split-re-test
  (are [s m] (= (re-find utils/var-split-re s) m)
       "no-var" nil
       "$no_sub" nil
       "$[bare_var]" ["$[bare_var]" "" "bare_var" ""]
       "a$[var]" ["a$[var]" "a" "var" ""]
       "$[var]b" ["$[var]b" "" "var" "b"]
       "a$[var]b" ["a$[var]b" "a" "var" "b"]
       "a$[var1]b$[var2]" ["a$[var1]b" "a" "var1" "b"]
       "a$[var1]b$var2" ["a$[var1]b$var2" "a" "var1" "b$var2"]))

(deftest var-place-test
  (is (= (utils/var-place "test") '(\t \e \s \t)))
  (is (= (utils/var-place "aaa$[var1]") '(\a \a \a #{"var1"})))
  (is (= (utils/var-place "aaa$[var1]bbb$OUTPUT")
         '(\a \a \a #{"var1"} \b \b \b \$ \O \U \T \P \U \T)))
  (is (= (utils/var-place "test$OUTPUT")
         '(\t \e \s \t \$ \O \U \T \P \U \T)))
  (is (= (utils/var-place {"var" "var-val"}
                          "aaa$[var]")
         '(\a \a \a #{"var"})))
  (is (thrown? Exception
               (utils/var-place {"not-var" "val"}
                                "aaa$[var]"))))

(deftest check-step-validity-test
  (is (thrown-with-msg?
       Exception
       #"method 'future' undefined at this point."
       (-> (workflow {})
           (method-step ["A"] ["B"] "future")
           (method "future" ["cmd"]))))
  (is (thrown-with-msg?
    Exception
    #"bad-method-mode is not a valid method-mode, valid values are: .*"
    (-> (workflow {})
        (method "a-method" ["cmd"])
        (method-step ["out"] ["in"] "a-method"
                     :method-mode "bad-method-mode"))))
  (is (thrown-with-msg?
       Exception
       #"method-mode specified but method name not given"
       (-> (workflow {})
           (step [] [] [] :method-mode "append")
           )))
  (is (thrown-with-msg?
       Exception
       #"commands not allowed for method calls .*"
       (-> (workflow {})
           (method "test-method" ["cmd"])
           (step [] [] ["bad-commands"] :method "test-method")))))

(deftest basic-wf-test
  (is (=
       (-> (workflow {})
           (cmd-step ["out"] ["in"] ["  cat $[INPUT] > $OUTPUT"])) ;note
                                                                   ;cmd
                                                                   ;space

       (utils/str->parse-tree "
out <- in
  cat $[INPUT] > $OUTPUT"))))

(deftest subsitution-in-file-test
  (is (=
       (-> (workflow {})
           (set-var "var" "val")
           (cmd-step ["$[var]"] [] ["  cmd"]))
       (utils/str->parse-tree "
var=val
$[var] <-
  cmd"))))

(deftest two-commands-test
  (is (=
       (-> (workflow {})
             (cmd-step ["out"] ["in"]
                       ["  echo \"first command\""
                        "  echo \"second command\""]))

       (utils/str->parse-tree "
out <- in
  echo \"first command\"
  echo \"second command\""))))

(deftest template-test
  (is (=
       (tpprint (-> (workflow {})
            (template [".sorted$"] ["."]
                      ["  cat $INPUT | sort | uniq > $OUTPUT"])
            (template-step ["output1.sorted"] ["input1"])))

       (tpprint (utils/str->parse-tree "
.sorted$ <- . [+template]
  cat $INPUT | sort | uniq > $OUTPUT
output1.sorted <- input1")))))

(deftest tag-test
  (is (=
       (-> (workflow {})
           (cmd-step ["%A"] [] ["  echo Step A"])
           (cmd-step ["%B"] [] ["  echo Step B"])
           (cmd-step ["%C"] ["%A" "%B"] ["  echo Step C"])
           (cmd-step ["%D"] ["%C"] ["  echo Step D"]))

       (utils/str->parse-tree "
%A <-
  echo Step A

%B <-
  echo Step B

%C <- %A, %B
  echo Step C

%D <- %C
  echo Step D"))))

(deftest base-in-out-test
  (is (=
       (-> (workflow {})
           (base "/tmp")
           (cmd-step
            ["words" "lines"]
            ["cpg.csv"]
            ["  echo INPUTS=$[INPUTS]"
             "  echo INPUTN=$[INPUTN]"
             "  echo INPUT=$[INPUT]"
             "  echo INPUT0=$[INPUT0]"
             "  echo OUTPUTS=$[OUTPUTS]"
             "  echo OUTPUTN=$[OUTPUTN]"
             "  echo OUTPUT=$[OUTPUT]"
             "  echo OUTPUT0=$[OUTPUT0]"
             "  echo OUTPUT1=$[OUTPUT1]"]))
       (utils/str->parse-tree "
BASE=/tmp

words, lines <- cpg.csv
  echo INPUTS=$[INPUTS]
  echo INPUTN=$[INPUTN]
  echo INPUT=$[INPUT]
  echo INPUT0=$[INPUT0]
  echo OUTPUTS=$[OUTPUTS]
  echo OUTPUTN=$[OUTPUTN]
  echo OUTPUT=$[OUTPUT]
  echo OUTPUT0=$[OUTPUT0]
  echo OUTPUT1=$[OUTPUT1]"))))

;; add base test!!!
;; (run-tests)
