(ns masai.redis
  (:use [useful :only [into-map]])
  (:require masai.db)
  (:import redis.clients.jedis.Jedis))

(defn i-to-b
  "If input is 0, returns false. Otherwise, true."
  [i] (not= i 0))

(deftype DB [#^Jedis rdb opts key-format]
  masai.db/EphemeralDB

  (add-expiry!
   [db key val exp]
   (masai.db/add! db key val)
   (.expire rdb key exp))
  
  (put-expiry! [db key val exp] (.setex rdb key exp val))
  
  masai.db/DB

  (open
   [db]
   (when-let [pass (:password opts)]
     (.auth rdb pass))
   (.connect rdb))
  
  (close [db] (.quit rdb))
  (sync! [db] (.save rdb))
  (get [db key] (.get rdb (key-format key)))
  (len
   [db key]
   (if-let [record (masai.db/get db key)]
     (count record)
     -1))
  (exists? [db key] (.exists rdb key))
  (key-seq [db] (set (.keys rdb "*")))
  (add! [db key val] (i-to-b (.setnx rdb (key-format key) (str val))))
  (put! [db key val] (i-to-b (.set rdb (key-format key) (str val))))
  (append! [db key val] (i-to-b (.append rdb (key-format key) (str val))))
  
  (inc!
   [db key i]
   (if (> 0 i)
     (.decrBy rdb (key-format key) (long (Math/abs i)))
     (.incrBy rdb (key-format key) (long i))))
  
  (delete! [db key] (i-to-b (.del rdb (into-array String [(key-format key)]))))
  (truncate! [db] (= "OK" (.flushDB rdb))))

(defn make [& opts]
  (let [{:keys [host port timeout key-format]
         :or {host "localhost" port 6379 key-format identity}
         :as opts}
         (into-map opts)]
    (DB.
     (if timeout
       (Jedis. host port timeout)
       (Jedis. host port))
     opts key-format)))