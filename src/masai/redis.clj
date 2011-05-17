(ns masai.redis
  (:use [useful :only [into-map]]
        [clojure.stacktrace :only [root-cause]])
  (:require masai.db)
  (:import redis.clients.jedis.BinaryJedis))

(defn i-to-b
  "If input is 0, returns false. Otherwise, true."
  [i] (not= i 0))

(defn key-format [^String s] (bytes (.getBytes s)))

(defmacro if-connected [db & body]
  `(if (.isConnected ~db) ~@body))

(deftype DB [^BinaryJedis rdb opts]
  masai.db/EphemeralDB

  (add-expiry! [db key val exp]
    (masai.db/add! db key val)
    (.expire rdb key exp))

  (put-expiry! [db key val exp]
    (.setex rdb key exp (bytes val)))

  masai.db/DB

  (open [db]
    (when-let [pass (:password opts)]
      (.auth rdb pass))
    (.disconnect rdb)
    (.connect rdb))

  (close [db]
    (.quit rdb)
    (.disconnect rdb))

  (sync! [db]
    (.save rdb))

  (get [db key]
    (if-connected rdb
      (.get rdb (key-format key))))

  (len [db key]
    (if-connected rdb   
      (if-let [record (masai.db/get db key)]
        (count record)
        -1)
      -1))

  (exists? [db key]
    (if-connected rdb
      (.exists rdb (key-format key))
      false))

  (key-seq [db]
    (set (.keys rdb "*")))

  (add!    [db key val] (i-to-b (.setnx  rdb (key-format key) (bytes val))))
  (put!    [db key val] (i-to-b (.set    rdb (key-format key) (bytes val))))
  (append! [db key val] (i-to-b (.append rdb (key-format key) (bytes val))))

  (inc! [db key i]
    (if (> 0 i)
      (.decrBy rdb (key-format key) (long (Math/abs ^Integer i)))
      (.incrBy rdb (key-format key) (long i))))

  (delete! [db key]
    (i-to-b (.del rdb (into-array [(key-format key)]))))

  (truncate! [db]
    (= "OK" (.flushDB rdb))))

(defn make [& opts]
  (let [{:keys [host port timeout]
         :or {host "localhost" port 6379}
         :as opts}
         (into-map opts)]
    (DB.
     (if timeout
       (BinaryJedis. host port timeout)
       (BinaryJedis. host port))
     opts)))