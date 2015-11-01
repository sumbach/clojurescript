(ns cljs.analyzer-tests
  (:require [clojure.java.io :as io]
            [cljs.analyzer :as a]
            [cljs.env :as e]
            [cljs.env :as env]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler :as comp]
            [cljs.tagged-literals :as tags]
            [clojure.pprint :refer [pprint]])
  (:use clojure.test))

;;******************************************************************************
;;  cljs-warnings tests
;;******************************************************************************

(def warning-forms
  {:undeclared-var (let [v (gensym)] `(~v 1 2 3))
   :fn-arity '(do (defn x [a b] (+ a b))
                  (x 1 2 3 4))
   :keyword-arity '(do (:argumentless-keyword-invocation))})

(defn warn-count* [f forms]
  (let [counter (atom 0)
        tracker (fn [warning-type env & [extra]]
                  (when (warning-type a/*cljs-warnings*)
                    (let [err (a/error-message warning-type extra)
                          msg (a/message env (str "WARNING: " err))]
                      (binding [*out* *err*] (println msg)))
                    (swap! counter inc)))]
    (a/with-warning-handlers [tracker]
      (doseq [form forms] (f form)))
    @counter))

(defn warn-count [form]
  (warn-count* #(a/analyze (a/empty-env) %) [form]))

(defn warn-count-forms [forms]
  (let [env (env/default-compiler-env)
        f (fn [form]
            #_(pprint form)
            (let [env-before @env
                  ast (a/analyze @env form)
                  env-after @env
                  printer (fn [x] (-> x
                                      (dissoc :js-dependency-index)
                                      (update-in [::a/namespaces] #(map (fn [[k v]] [k (count v)]) %))
                                      (update-in [::a/constant-table] count)
                                      (update-in [:ns] :name)
                                      pprint))
                  new-env (-> (:env ast)
                              (update-in [::a/namespaces] merge (::a/namespaces env-after))
                              (assoc ::a/constant-table (::a/constant-table env-after))
                              (assoc :ns (a/get-namespace a/*cljs-ns*)))]
              (def ebefore env-before)
              (def eafter env-after)
              (def myast ast)
              (def east (:env ast))
              (def enew new-env)
              #_(printer env-before)
              #_(printer env-after)
              #_(printer (:env ast))
              #_(printer new-env)
              (with-out-str (comp/emit (assoc ast :env new-env)))
              (reset! env new-env)))]
    (binding [env/*compiler* env
              a/*cljs-ns* 'cljs.user
              a/*cljs-static-fns* true]
      (warn-count* f forms))))

(deftest no-warn
  (is (every? zero? (map (fn [[name form]] (a/no-warn (warn-count form))) warning-forms))))

(deftest all-warn
  (is (every? #(= 1 %) (map (fn [[name form]] (a/all-warn (warn-count form))) warning-forms))))

(def core-tags-and-example-values
  {nil [nil 'js/undefined]
   'number [0]
   'string [""]
   'boolean [true false]
   'function ['identity '(fn [x] x)]
   'object [(tags/->JSValue {})]
   'array [(tags/->JSValue [])]})

(def implement-protocol-on-core-tags-forms
  (for [[tag examples] core-tags-and-example-values]
    (concat
     (list '(ns cljs.user (:require cljs.core) (:require-macros cljs.core))
           '(defprotocol Foo (foo [x]))
           (list 'extend-type tag 'Foo '(foo [x] x)))
     (for [example examples]
       (list 'foo example)))))

(deftest no-warn-on-implement-protocol-on-core-tag
  (is (every? zero? (map (fn [forms] (warn-count-forms forms)) implement-protocol-on-core-tags-forms))))

;; =============================================================================
;; NS parsing

(def ns-env (assoc-in (a/empty-env) [:ns :name] 'cljs.user))

(deftest spec-validation
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require {:foo :bar})))
          (catch Exception e
            (.getMessage e)))
        "Only [lib.ns & options] and lib.ns specs supported in :require / :require-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [:foo :bar])))
          (catch Exception e
            (.getMessage e)))
        "Library name must be specified as a symbol in :require / :require-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [baz.woz :as woz :refer [] :plop])))
          (catch Exception e
            (.getMessage e)))
        "Only :as alias and :refer (names) options supported in :require"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [baz.woz :as woz :refer [] :plop true])))
          (catch Exception e
            (.getMessage e)))
        "Only :as and :refer options supported in :require / :require-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [baz.woz :as woz :refer [] :as boz :refer []])))
          (catch Exception e
            (.getMessage e)))
        "Each of :as and :refer options may only be specified once in :require / :require-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:refer-clojure :refer [])))
          (catch Exception e
            (.getMessage e)))
        "Only [:refer-clojure :exclude (names)] form supported"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:use [baz.woz :exclude []])))
          (catch Exception e
            (.getMessage e)))
        "Only [lib.ns :only (names)] specs supported in :use / :use-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [baz.woz :as []])))
          (catch Exception e
            (.getMessage e)))
        ":as must be followed by a symbol in :require / :require-macros"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require [baz.woz :as woz] [noz.goz :as woz])))
          (catch Exception e
            (.getMessage e)))
        ":as alias must be unique"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:unless [])))
          (catch Exception e
            (.getMessage e)))
        "Only :refer-clojure, :require, :require-macros, :use, :use-macros, and :import libspecs supported"))
  (is (.startsWith
        (try
          (a/analyze ns-env '(ns foo.bar (:require baz.woz) (:require noz.goz)))
          (catch Exception e
            (.getMessage e)))
        "Only one ")))

;; =============================================================================
;; Inference tests

(def test-cenv (atom {}))
(def test-env (assoc-in (a/empty-env) [:ns :name] 'cljs.core))

(a/no-warn
  (e/with-compiler-env test-cenv
    (binding [a/*analyze-deps* false]
      (a/analyze-file (io/file "src/main/cljs/cljs/core.cljs")))))

(deftest basic-inference
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '1)))
         'number))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '"foo")))
         'string))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(make-array 10))))
         'array))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(js-obj))))
         'object))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '[])))
         'cljs.core/IVector))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '{})))
         'cljs.core/IMap))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '#{})))
         'cljs.core/ISet))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env ())))
         'cljs.core/IList))
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(fn [x] x))))
         'function)))

(deftest if-inference
  (is (= (a/no-warn
           (e/with-compiler-env test-cenv
             (:tag (a/analyze test-env '(if x "foo" 1)))))
         '#{number string})))

(deftest method-inference
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(.foo js/bar))))
         'any)))

(deftest fn-inference
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env
  ;                 '(let [x (fn ([a] 1) ([a b] "foo") ([a b & r] ()))]
  ;                    (x :one)))))
  ;      'number))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env
  ;                 '(let [x (fn ([a] 1) ([a b] "foo") ([a b & r] ()))]
  ;                    (x :one :two)))))
  ;      'string))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env
  ;                 '(let [x (fn ([a] 1) ([a b] "foo") ([a b & r] ()))]
  ;                    (x :one :two :three)))))
  ;      'cljs.core/IList))
  )

(deftest lib-inference
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(+ 1 2))))
         'number))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(alength (array)))))
  ;       'number))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(aclone (array)))))
  ;       'array))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(-count [1 2 3]))))
  ;      'number))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(count [1 2 3]))))
  ;       'number))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(into-array [1 2 3]))))
  ;       'array))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(js-obj))))
  ;       'object))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(-conj [] 1))))
  ;       'clj))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(conj [] 1))))
  ;       'clj))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(assoc nil :foo :bar))))
  ;       'clj))
  ;(is (= (e/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(dissoc {:foo :bar} :foo))))
  ;       '#{clj clj-nil}))
  )

(deftest test-always-true-if
  (is (= (e/with-compiler-env test-cenv
           (:tag (a/analyze test-env '(if 1 2 "foo"))))
         'number)))

;; will only work if the previous test works
(deftest test-count
  ;(is (= (cljs.env/with-compiler-env test-cenv
  ;         (:tag (a/analyze test-env '(count []))))
  ;       'number))
  )

(deftest test-numeric
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(dec x)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(int x)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(unchecked-int x)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(mod x y)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(quot x y)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(rem x y)))))
  ;       'number))
  ;(is (= (a/no-warn
  ;         (cljs.env/with-compiler-env test-cenv
  ;           (:tag (a/analyze test-env '(bit-count n)))))
  ;       'number))
  )

;; =============================================================================
;; Catching errors during macroexpansion

(deftest test-defn-error
  (is (.startsWith
        (try
          (a/analyze test-env '(defn foo 123))
          (catch Exception e
            (.getMessage e)))
        "Parameter declaration \"123\" should be a vector")))

;; =============================================================================
;; ns desugaring

(deftest test-cljs-975
  (let [spec '((:require [bar :refer [baz] :refer-macros [quux]] :reload))]
    (is (= (set (a/desugar-ns-specs spec))
           (set '((:require-macros (bar :refer [quux]) :reload)
                  (:require (bar :refer [baz]) :reload)))))))

;; =============================================================================
;; Namespace metadata

(deftest test-namespace-metadata
  (binding [a/*cljs-ns* a/*cljs-ns*]
    (is (= (do (a/analyze ns-env '(ns weeble {:foo bar}))
               (meta a/*cljs-ns*))
           {:foo 'bar}))

    (is (= (do (a/analyze ns-env '(ns ^{:foo bar} weeble))
               (meta a/*cljs-ns*))
           {:foo 'bar}))

    (is (= (do (a/analyze ns-env '(ns ^{:foo bar} weeble {:baz quux}))
               (meta a/*cljs-ns*))
           {:foo 'bar :baz 'quux}))

    (is (= (do (a/analyze ns-env '(ns ^{:foo bar} weeble {:foo baz}))
               (meta a/*cljs-ns*))
           {:foo 'baz}))

    (is (= (meta (:name (a/analyze ns-env '(ns weeble {:foo bar}))))
           {:foo 'bar}))

    (is (= (meta (:name (a/analyze ns-env '(ns ^{:foo bar} weeble))))
           {:foo 'bar}))

    (is (= (meta (:name (a/analyze ns-env '(ns ^{:foo bar} weeble {:baz quux}))))
           {:foo 'bar :baz 'quux}))

    (is (= (meta (:name (a/analyze ns-env '(ns ^{:foo bar} weeble {:foo baz}))))
           {:foo 'baz}))))

(def test-deps-env (atom
                    {:cljs.analyzer/namespaces
                     {'file-reloading {:requires {'utils 'utils}}
                      'core           {:requires {'file-reloading 'file-reloading}}
                      'dev            {:requires {'client 'client
                                                  'core 'core}}
                      'client         {:requires {'utils 'utils}}
                      'utils          {:requires {}}}}))

(deftest test-ns-dependents
  (binding [env/*compiler* test-deps-env]
    (is (= (set (a/ns-dependents 'client))
           #{'dev}))
    (is (= (set (a/ns-dependents 'core))
           #{'dev}))
    (is (= (set (a/ns-dependents 'dev))
           #{}))

    ;; if 'file-reloading returns 'core
    (is (= (set (a/ns-dependents 'file-reloading))
           #{'dev 'core}))

    ;; how can 'utils include 'file-reloading but not 'core
    ;; FAILS with
    ;; actual: (not (= #{file-reloading dev client} #{file-reloading dev client core}))
    (is (= (set (a/ns-dependents 'utils))
           #{'file-reloading 'dev 'client 'core}))))

(deftest test-ns-dependents-custom-ns-map
  (let [ns-map '{bar {:requires #{cljs.core}}
                 foo {:requires #{cljs.core bar}}}]
    (is (= (set (a/ns-dependents 'bar ns-map)) #{'foo}))))

(deftest test-cljs-1105
  ;; munge turns - into _, must preserve the dash first
  (is (not= (a/gen-constant-id :test-kw)
            (a/gen-constant-id :test_kw))))

(deftest test-symbols-munge-cljs-1432
  (is (not= (a/gen-constant-id :$)
            (a/gen-constant-id :.)))
  (is (not= (a/gen-constant-id '$)
            (a/gen-constant-id '.))))

(deftest test-unicode-munging-cljs-1457
  (is (= (a/gen-constant-id :C♯) 'cst$kw$C_u266f_)
      (= (a/gen-constant-id 'C♯) 'cst$sym$C_u266f_)))

;; Constants

(deftest test-constants
 (is (.startsWith
        (try
          (a/analyze test-env '(do (def ^:const foo 123)  (def foo 246)))
          (catch Exception e
            (.getMessage e)))
        "Can't redefine a constant"))
  (is (.startsWith
        (try
          (a/analyze test-env '(do (def ^:const foo 123)  (set! foo 246)))
          (catch Exception e
            (.getMessage e)))
        "Can't set! a constant")))
