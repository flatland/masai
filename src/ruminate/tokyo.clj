(ns ruminate.tokyo
  (:use [useful :only [into-map]])
  (:require ruminate.db retro.core)
  (:import [tokyocabinet HDB]))

(def compress
  {:deflate HDB/TDEFLATE
   :bzip    HDB/TBZIP
   :tcbs    HDB/TTCBS})

(defn- tflags [opts]
  (bit-or
   (if (:large opts) HDB/TLARGE 0)
   (or (compress (:compress opts)) 0)))

(defn- oflags [opts]
  (reduce bit-or 0
    (list (if (:readonly opts) HDB/OREADER HDB/OWRITER)
          (if (:create   opts) HDB/OCREAT  0)
          (if (:truncate opts) HDB/OTRUNC  0)
          (if (:tsync    opts) HDB/OTSYNC  0)
          (if (:nolock   opts) HDB/ONOLCK  0)
          (if (:noblock  opts) HDB/OLCKNB  0))))

(defmacro check [form]
  `(or ~form
       (case (.ecode ~'hdb)
         ~HDB/EKEEP  false
         ~HDB/ENOREC false
         (throw (java.io.IOException. (.errmsg ~'hdb) )))))

(defn- key-seq* [hdb]
  (lazy-seq
   (if-let [key (.iternext2 hdb)]
     (cons key (key-seq* hdb))
     nil)))

(deftype DB [#^HDB hdb opts key-format]
  ruminate.db/DB

  (open [db]
    (let [path (:path opts)
          bnum (or (:bnum opts)  0)
          apow (or (:apow opts) -1)
          fpow (or (:fpow opts) -1)]
      (.mkdirs (.getParentFile (java.io.File. path)))
      (check (.tune hdb bnum apow fpow (tflags opts)))
      (when-let [rcnum (:cache opts)]
        (check (.setcache hdb rcnum)))
      (when-let [xmsiz (:xmsiz opts)]
        (check (.setxmsiz hdb xmsiz)))
      (check (.open hdb path (oflags opts)))))

  (close     [db] (.close hdb))
  (sync!     [db] (.sync  hdb))
  (optimize! [db] (.optimize hdb))

  (get [db key] (.get  hdb (key-format key)))
  (len [db key] (.vsiz hdb (key-format key)))

  (key-seq [db]
    (.iterinit hdb)
    (key-seq* hdb))

  (add!    [db key val] (check (.putkeep hdb (key-format key) (bytes val))))
  (put!    [db key val] (check (.put     hdb (key-format key) (bytes val))))
  (append! [db key val] (check (.putcat  hdb (key-format key) (bytes val))))
  (inc!    [db key i]   (.addint hdb (key-format key) i))

  (delete!   [db key] (check (.out    hdb (key-format key))))
  (truncate! [db]     (check (.vanish hdb)))

  retro.core/Transactional

  (txn-begin    [db] (.tranbegin  hdb))
  (txn-commit   [db] (.trancommit hdb))
  (txn-rollback [db] (.tranabort  hdb)))

(defn make [& opts]
  (let [opts (into-map opts)]
    (DB.
     (HDB.) opts
     (or (:key-format opts) #(bytes (.getBytes (str %)))))))