; Copyright 2010 Hubert Iwaniuk
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;   http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns http.async.client.test
  "Testing of http.async"
  {:author "Hubert Iwaniuk"}
  (:refer-clojure :exclude [promise await])
  (:use clojure.test
        http.async.client
        [http.async.client request util]
        [clojure.stacktrace :only [print-stack-trace]]
        [clojure.contrib.str-utils2 :only [split]]
        [clojure.java.io :only [input-stream]]
        [clojure.contrib.profile :only [prof profile]])
  (:require [clojure.contrib.io :as duck])
  (:import (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (org.eclipse.jetty.continuation Continuation ContinuationSupport)
           (javax.servlet.http HttpServletRequest HttpServletResponse Cookie)
           (java.io ByteArrayOutputStream)
           (java.util.concurrent TimeoutException)))
(set! *warn-on-reflection* true)

;; test suite setup
(def default-handler
     (proxy [AbstractHandler] []
       (handle [target #^Request req #^HttpServletRequest hReq #^HttpServletResponse hResp]
         (do
           (.setHeader hResp "test-header" "test-value")
           (let [hdrs (enumeration-seq (.getHeaderNames hReq))]
             (doseq [k hdrs :when (not (contains? #{"Server"
                                                    "Connection"
                                                    "Content-Length"
                                                    "Host"
                                                    "User-Agent"
                                                    "Content-Type"
                                                    "Cookie"} k))]
               (.setHeader hResp k (.getHeader hReq k))))
           (.setContentType hResp "text/plain;charset=utf-8")
           (.setStatus hResp 200)
                                        ; process params
           (condp = target
               "/body" (.write (.getWriter hResp) "Remember to checkout #clojure@freenode")
               "/body-str" (when-let [line (.readLine (.getReader hReq))]
                             (.write (.getWriter hResp) line))
               "/put" (.setHeader hResp "Method" (.getMethod hReq))
               "/delete" (.setHeader hResp "Method" (.getMethod hReq))
               "/head" (.setHeader hResp "Method" (.getMethod hReq))
               "/options" (.setHeader hResp "Method" (.getMethod hReq))
               "/stream" (do
                           (let [cont (ContinuationSupport/getContinuation hReq)
                                 writer (.getWriter hResp)
                                 prom (promise)]
                             (.suspend cont)
                             (future
                              (Thread/sleep 100)
                              (doto writer
                                (.write "part1")
                                (.flush)))
                             (future
                              (Thread/sleep 200)
                              (doto writer
                                (.write "part2")
                                (.flush))
                              (deliver prom true))
                             (future
                              (if @prom
                                (.complete cont)))))
               "/issue-1" (let [writer (.getWriter hResp)]
                            (doto writer
                              (.write "глава")
                              (.flush)))
               "/proxy-req" (.setHeader hResp "Target" (.. req (getUri) (toString)))
               "/cookie" (do
                           (.addCookie hResp (Cookie. "foo" "bar"))
                           (doseq [c (.getCookies hReq)]
                             (.addCookie hResp c)))
               "/branding" (.setHeader hResp "X-User-Agent" (.getHeader hReq "User-Agent"))
               "/basic-auth" (let [auth (.getHeader hReq "Authorization")]
                               (.setStatus
                                hResp
                                (if (= auth "Basic YmVhc3RpZTpib3lz")
                                  200
                                  401)))
               "/timeout" (Thread/sleep 200)
               (doseq [n (enumeration-seq (.getParameterNames hReq))]
                 (doseq [v (.getParameterValues hReq n)]
                   (.addHeader hResp n v))))
           (when-let [q (.getQueryString hReq)]
             (doseq [p (split q #"\&")]
               (let [[k v] (split p #"=")]
                 (.setHeader hResp k v))))
           (.setHandled req true)))))

(defn- start-jetty
  ([handler]
     (start-jetty handler {:port 8123}))
  ([handler {port :port :as opts :or {:port 8123}}]
     (let [srv (Server. port)]
       (doto srv
         (.setHandler handler)
         (.start))
       srv)))

(defn- once-fixture [f]
  "Configures Logger before test here are executed, and closes AHC after tests are done."
  (def srv (start-jetty default-handler))
  (binding [*ahc* (create-client)]
    (try (f)
         (finally
          (do
            (.close *ahc*)
            (.stop srv))))))

(use-fixtures :once once-fixture)

;; testing
(deftest test-status
  (let [status# (promise)
	_ (apply execute-request
                 (prepare-request :get "http://localhost:8123/")
                 (apply concat (merge *default-callbacks*
                                      {:status (fn [_ st] (do (deliver status# st) [st :abort]))})))
	status @status#]
    (are [k v] (= (k status) v)
         :code 200
         :msg "OK"
         :protocol "HTTP/1.1"
         :major 1
         :minor 1)))

(deftest test-receive-headers
  (let [headers# (promise)
        _ (apply execute-request
                 (prepare-request :get "http://localhost:8123/")
                 (apply concat (merge *default-callbacks*
                                      {:headers (fn [_ hds] (do (deliver headers# hds) [hds :abort]))})))
        headers @headers#]
    (is (= (:test-header headers) "test-value"))))

(deftest test-send-headers
  (let [resp (GET "http://localhost:8123/" :headers {:a 1 :b 2})
        headers (headers resp)]
    (if (delivered? (:error resp))
      (print-stack-trace @(:error resp)))
    (is (not (delivered? (:error resp))))
    (is (not (empty? headers)))
    (are [k v] (= (k headers) (str v))
         :a 1
         :b 2)))

(deftest test-body
  (let [resp (GET "http://localhost:8123/body")
        headers (headers resp)
        body (body resp)]
    (is (not (nil? body)))
    (is (= "Remember to checkout #clojure@freenode" (string resp)))
    (if (contains? headers :content-length)
      (is (= (count (string resp)) (Integer/parseInt (:content-length headers)))))))

(deftest test-query-params
  (let [resp (GET "http://localhost:8123/" :query {:a 3 :b 4})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= (x headers) (str y))
         :a 3
         :b 4)))

(deftest test-get-params-not-allowed
  (is (thrown?
       IllegalArgumentException
       (GET "http://localhost:8123/" :body {:a 5 :b 6}))))

(deftest test-post-params
  (let [resp (POST "http://localhost:8123/" :body {:a 5 :b 6})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= (x headers) (str y))
         :a 5
         :b 6)))

(deftest test-post-string-body
  (let [resp (POST "http://localhost:8123/body-str" :body "TestBody")
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "TestBody" (string resp)))))

(deftest test-post-map-body
  (let [resp (POST "http://localhost:8123/" :body {:u "user" :p "s3cr3t"})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= x (y headers))
         "user" :u
         "s3cr3t" :p)))

(deftest test-post-input-stream-body
  (let [resp (POST "http://localhost:8123/body-str" :body (input-stream (.getBytes "TestContent" "UTF-8")))
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "TestContent" (string resp)))))

(deftest test-put
  (let [resp (PUT "http://localhost:8123/put" :body "TestContent")
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
         status
         headers)
    (is (= 200 (:code status)))
    (is (= "PUT" (:method headers)))))

(deftest test-delete
  (let [resp (DELETE "http://localhost:8123/delete")
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
         status
         headers)
    (is (= 200 (:code status)))
    (is (= "DELETE" (:method headers)))))

(deftest test-head
  (let [resp (HEAD "http://localhost:8123/head")
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
         status
         headers)
    (is (= 200 (:code status)))
    (is (= "HEAD" (:method headers)))))

(deftest test-options
  (let [resp (OPTIONS "http://localhost:8123/options")
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
         status
         headers)
    (is (= 200 (:code status)))
    (is (= "OPTIONS" (:method headers)))))

(deftest test-stream
  (let [stream (ref #{})
        resp (request-stream :get "http://localhost:8123/stream"
                     (fn [_ baos]
                       (dosync (alter stream conj (.toString baos duck/*default-encoding*)))
                       [baos :continue]))
        status (status resp)]
    (await resp)
    (are [x] (not (empty? x))
         status
         @stream)
    (is (= 200 (:code status)))
    (doseq [s @stream]
      (let [part s]
        (is (contains? #{"part1" "part2"} part))))))

(deftest test-get-stream
  (let [resp (GET "http://localhost:8123/stream")]
    (await resp)
    (is (= "part1part2" (string resp)))))

(deftest test-stream-seq
  (let [resp (stream-seq :get "http://localhost:8123/stream")
        status (status resp)
        headers (headers resp)
        body (body resp)]
    (are [e p] (= e p)
         200 (:code status)
         "test-value" (:test-header headers)
         2 (count body))
    (doseq [s (string resp)]
      (is (or (= "part1" s) (= "part2" s))))))

(deftest issue-1
  (is (= "глава" (string (GET "http://localhost:8123/issue-1")))))

(deftest get-via-proxy
  (let [target "http://localhost:8123/proxy-req"
        resp (GET target :proxy {:host "localhost" :port 8123})
        headers (headers resp)]
    (is (= target (:target headers)))))

(deftest get-with-cookie
  (let [cv "sample-value"
        resp (GET "http://localhost:8123/cookie"
                  :cookies #{{:domain "http://localhost:8123/"
                              :name "sample-name"
                              :value cv
                              :path "/cookie"
                              :max-age 10
                              :secure false}})
        headers (headers resp)]
    (is (contains? headers :set-cookie))
    (let [cookies (cookies resp)]
      (is (not (empty? cookies)))
      (doseq [cookie cookies]
        (is (or (= (:name cookie) "sample-name")
                (= (:name cookie) "foo")))
        (is (= (:value cookie)
               (if (= (:name cookie) "sample-name")
                 cv
                 "bar")))))))

(deftest get-with-user-agent-branding
  (let [ua-brand "Branded User Agent/1.0"]
    (with-ahc {:user-agent ua-brand}
      (let [headers (headers (GET "http://localhost:8123/branding"))]
        (is (contains? headers :x-user-agent))
        (is (= (:x-user-agent headers) ua-brand))))))

(deftest await-string
  (let [resp (GET "http://localhost:8123/stream")
        body (string (await resp))]
    (is (= body "part1part2"))))

(deftest no-host
  (let [resp (GET "http://notexisting/")]
    (await resp)
    (is (= (.getMessage (error resp)) "Connection refused to http://notexisting/"))
    (is (true? (failed? resp)))))

(deftest no-realm-for-digest
  (is (thrown-with-msg? IllegalArgumentException #"For DIGEST authentication realm is required"
        (GET "http://not-important/"
             :auth {:type :digest
                    :user "user"
                    :password "secret"}))))

(deftest authentication-without-user-or-password
  (is (thrown-with-msg? IllegalArgumentException #"For authentication user is required"
        (GET "http://not-important/"
             :auth {:password "secret"})))
  (is (thrown-with-msg? IllegalArgumentException #"For authentication password is required"
        (GET "http://not-important/"
             :auth {:user "user"})))
  (is (thrown-with-msg? IllegalArgumentException #"For authentication user and password is required"
        (GET "http://not-important/"
             :auth {:type :basic}))))

(deftest basic-authentication
  (is (=
       (:code (status (GET "http://localhost:8123/basic-auth"
                           :auth {:user "beastie"
                                  :password "boys"})))
       200)))

(deftest canceling-request
  (let [resp (GET "http://localhost:8123/")]
    (is (false? (cancelled? resp)))
    (is (true? (cancel resp)))
    (is (true? (cancelled? resp)))))

(deftest reqeust-timeout
  (let [resp (GET "http://localhost:8123/timeout" :timeout 100)]
    (await resp)
    (is (true? (failed? resp)))
    (is (instance? TimeoutException (error resp)))))

;;(deftest profile-get-stream
;;  (let [gets (repeat (GET "http://localhost:8123/stream"))
;;        seqs (repeat (stream-seq :get "http://localhost:8123/stream"))
;;        f (fn [resps] (doseq [resp resps] (is (= "part1part2" (prof :get-stream (string resp))))))
;;        g (fn [resps] (doseq [resp resps] (doseq [s (prof :seq-stream (doall (string resp)))]
;;                                                (is (or (= "part1" s) (= "part2 s"))))))]
;;    (profile (dotimes [i 10]
;;               (f (take 1000 (nthnext gets (* i 1000))))
;;               (g (take 1000 (nthnext seqs (* i 1000))))))))
