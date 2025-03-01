;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki com.grzm.awyeah.util
  "Impl, don't call directly."
  (:require
   [clojure.core.async :as a]
   [clojure.data.xml :as xml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.grzm.awyeah.json :as json])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.io InputStream)
   (java.net URLEncoder)
   (java.nio ByteBuffer)
   (java.security MessageDigest)
   (java.time ZonedDateTime ZoneId)
   (java.time.format DateTimeFormatter)
   (java.util Base64)
   (java.util Date)
   (java.util UUID)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

(set! *warn-on-reflection* true)

(defn date-format
  "Return a thread-safe GMT date format that can be used with `format-date` and `parse-date`."
  [^String fmt]
  (.withZone (DateTimeFormatter/ofPattern fmt) (ZoneId/of "GMT")))

(defn format-date
  ([formatter]
   (format-date formatter (Date.)))
  ([formatter ^Date inst]
   (.format (ZonedDateTime/ofInstant (.toInstant inst) (ZoneId/of "UTC"))
            ^DateTimeFormatter formatter)))

(defn format-timestamp
  "Format a timestamp in milliseconds."
  [inst]
  (str (long (/ (.getTime ^Date inst) 1000))))

(defn parse-date
  [formatter s]
  (Date/from (.toInstant (ZonedDateTime/parse s formatter))))

(def x-amz-date-format
  (date-format "yyyyMMdd'T'HHmmss'Z'"))

(def x-amz-date-only-format
  (date-format "yyyyMMdd"))

(def iso8601-date-format
  (date-format "yyyy-MM-dd'T'HH:mm:ssXXX"))

(def iso8601-msecs-date-format
  (date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

(def rfc822-date-format
  (date-format "EEE, dd MMM yyyy HH:mm:ss z"))

(let [hex-chars (char-array [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f])]
  (defn hex-encode
    [^bytes bytes]
    (let [bl (alength bytes)
          ca (char-array (* 2 bl))]
      (loop [i (int 0)
             c (int 0)]
        (if (< i bl)
          (let [b (long (bit-and (long (aget bytes i)) 255))]
            (aset ca c ^char (aget hex-chars (unsigned-bit-shift-right b 4)))
            (aset ca (unchecked-inc-int c) (aget hex-chars (bit-and b 15)))
            (recur (unchecked-inc-int i) (unchecked-add-int c 2)))
          (String. ca))))))

(defn sha-256
  "Returns the sha-256 digest (bytes) of data, which can be a
  byte-array, an input-stream, or nil, in which case returns the
  sha-256 of the empty string."
  [data]
  (cond (string? data)
        (sha-256 (.getBytes ^String data "UTF-8"))
        (instance? ByteBuffer data)
        (sha-256 (.array ^ByteBuffer data))
        :else
        (let [digest (MessageDigest/getInstance "SHA-256")]
          (when data
            (.update digest ^bytes data))
          (.digest digest))))

(defn hmac-sha-256
  [key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn input-stream->byte-array ^bytes [is]
  (let [os (ByteArrayOutputStream.)]
    (io/copy is os)
    (.toByteArray os)))

(defn bbuf->bytes
  [^ByteBuffer bbuf]
  (when bbuf
    (let [bytes (byte-array (.remaining bbuf))]
      (.get (.duplicate bbuf) bytes)
      bytes)))

(defn bbuf->str
  "Creates a string from java.nio.ByteBuffer object.
   The encoding is fixed to UTF-8."
  [^ByteBuffer bbuf]
  (when-let [bytes (bbuf->bytes bbuf)]
    (String. ^bytes bytes "UTF-8")))

(defn bbuf->input-stream
  [^ByteBuffer bbuf]
  (when bbuf
    (io/input-stream (bbuf->bytes bbuf))))

(defprotocol BBuffable
  (->bbuf [data]))

(extend-protocol BBuffable
  (Class/forName "[B")
  (->bbuf [bs] (ByteBuffer/wrap bs))

  String
  (->bbuf [s] (->bbuf (.getBytes s "UTF-8")))

  InputStream
  (->bbuf [is] (->bbuf (input-stream->byte-array is)))

  ByteBuffer
  (->bbuf [bb] bb)

  nil
  (->bbuf [_]))

(defn xml-read
  "Parse the UTF-8 XML string."
  [s]
  (xml/parse (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
             :namespace-aware false
             :skip-whitespace true))

(defn xml->map [element]
  (cond
    (nil? element) nil

    (string? element) element

    (sequential? element)
    (if (> (count element) 1)
      (into {} (map xml->map) element)
      (xml->map (first element)))

    (map? element)
    (cond
      (empty? element) {}
      (:attrs element) {(:tag element) (xml->map (:content element))
                        (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
      :else {(:tag element) (xml->map (:content element))})

    :else nil))

(defn xml-write
  [e]
  (if (instance? String e)
    (print e)
    (do
      (print (str "<" (name (:tag e))))
      (when (:attrs e)
        (doseq [attr (:attrs e)]
          (print (str " " (name (key attr)) "=\"" (val attr) "\""))))
      (if-not (empty? (:content e))
        (do
          (print ">")
          (doseq [c (:content e)]
            (xml-write c))
          (print (str "</" (name (:tag e)) ">")))
        (print " />")))))

(defn url-encode
  "Percent encode the string to put in a URL."
  [^String s]
  (-> s
      (URLEncoder/encode "UTF-8")
      (.replace "+" "%20")))

(defn query-string
  "Create a query string from a list of parameters. Values must all be
  strings."
  [params]
  (when-not (empty? params)
    (str/join "&" (map (fn [[k v]]
                         (str (url-encode (name k))
                              "="
                              (url-encode v)))
                       params))))

(defprotocol Base64Encodable
  (base64-encode [data]))

(extend-protocol Base64Encodable
  (Class/forName "[B")
  (base64-encode [ba] (.encodeToString (Base64/getEncoder) ba))

  ByteBuffer
  (base64-encode [bb] (base64-encode (.array bb)))

  java.lang.String
  (base64-encode [s] (base64-encode (.getBytes s))))

(defn base64-decode
  "base64 decode a base64-encoded string to an input stream"
  [s]
  (io/input-stream (.decode (Base64/getDecoder) ^String s)))

(defn encode-jsonvalue [data]
  (base64-encode (.getBytes ^String (json/write-str data))))

(defn parse-jsonvalue [data]
  (-> data
      base64-decode
      io/reader
      slurp
      (json/read-str)))

(defn md5
  "returns an MD5 hash of the content of bb as a byte array"
  ^bytes [^ByteBuffer bb]
  (let [ba (.array bb)
        hasher (MessageDigest/getInstance "MD5")]
    (.update hasher ^bytes ba)
    (.digest hasher)))

(defn uuid-string
  "returns a string representation of a randomly generated UUID"
  []
  (str (UUID/randomUUID)))

(defn with-defaults
  "Given a shape and data of that shape, add defaults for the
  following required keys if they are missing or bound to nil

      :idempotencyToken"
  [shape data]
  (reduce (fn [m [member-name member-spec]]
            (cond
              (not (nil? (get data member-name)))
              m

              (:idempotencyToken member-spec)
              (assoc m member-name (uuid-string))

              :else
              m))
          (or data {})
          (:members shape)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; used to fetch creds and region

(defn fetch-async
  "Internal use. Do not call directly."
  [fetch provider item]
  (a/thread
    (try
      ;; lock on the provider to avoid redundant concurrent requests
      ;; before the provider has a chance to cache the results of the
      ;; first fetch.
      (or (locking provider
            (fetch provider))
          {:cognitect.anomalies/category :cognitect.anomalies/fault
           :cognitect.anomalies/message (format "Unable to fetch %s. See log for more details." item)})
      (catch Throwable t
        {:cognitect.anomalies/category :cognitect.anomalies/fault
         ::throwable t
         :cognitect.anomalies/message (format "Unable to fetch %s." item)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Wrappers - here to support testing with-redefs since
;;;;            we can't redef static methods

(defn getenv
  ([] (System/getenv))
  ([k] (System/getenv k)))

(defn getProperty [k]
  (System/getProperty k))
