(ns nightweb.router
  (:use [nightweb.crypto :only [create-user-keys
                                create-signature]]
        [nightweb.io :only [file-exists?
                            make-dir
                            iterate-dir
                            base32-encode
                            base32-decode
                            write-link-file]]
        [nightweb.constants :only [base-dir
                                   set-base-dir
                                   my-hash-bytes
                                   set-my-hash-bytes
                                   my-hash-str
                                   set-my-hash-str
                                   slash
                                   torrent-ext
                                   get-users-dir
                                   get-user-dir
                                   get-user-pub-file
                                   get-meta-dir]]
        [nightweb.torrent :only [start-torrent-manager
                                 add-hash
                                 add-torrent
                                 remove-torrent]]
        [nightweb.torrent-dht :only [send-meta-link
                                     send-meta-link-periodically
                                     init-dht]]))

(defn add-user-hash
  [their-hash-bytes]
  (let [their-hash-str (base32-encode their-hash-bytes)
        path (get-user-dir their-hash-str)]
    (when-not (file-exists? path)
      (make-dir path)
      (add-hash path their-hash-str send-meta-link))))

(defn add-user-torrents
  []
  (iterate-dir (get-users-dir)
               (fn [their-hash-str]
                 (let [user-dir (get-user-dir their-hash-str)
                       pub-path (get-user-pub-file their-hash-str)
                       pub-torrent-path (str pub-path torrent-ext)
                       meta-path (get-meta-dir their-hash-str)
                       meta-torrent-path (str meta-path torrent-ext)]
                   (if (not= their-hash-str my-hash-str)
                     (if (file-exists? pub-torrent-path)
                       (add-torrent pub-path send-meta-link)
                       (add-hash user-dir their-hash-str send-meta-link)))
                   (if (file-exists? meta-torrent-path)
                     (add-torrent meta-path nil))))))

(defn create-user-torrent
  []
  (let [pub-key-path (create-user-keys)]
    (add-torrent pub-key-path send-meta-link)))

(defn create-meta-torrent
  []
  (let [path (get-meta-dir my-hash-str)
        _ (remove-torrent path)
        link-hash-bytes (add-torrent path nil)]
    (write-link-file path link-hash-bytes create-signature)))

(defn parse-url
  [url]
  (let [url-str (subs url (+ 1 (.indexOf url "#")))
        url-vec (clojure.string/split url-str #"[&=]")
        url-map (if (even? (count url-vec))
                  (apply hash-map url-vec)
                  {})
        {type-val "type" hash-val "hash"} url-map]
    {:type (if type-val (keyword type-val) nil)
     :hash (if hash-val (base32-decode hash-val) nil)}))

(defn start-router
  [dir]
  (set-base-dir dir)
  (java.lang.System/setProperty "i2p.dir.base" dir)
  (java.lang.System/setProperty "i2p.dir.config" dir)
  (java.lang.System/setProperty "wrapper.logfile" (str dir slash "wrapper.log"))
  (net.i2p.router.RouterLaunch/main nil)
  (start-torrent-manager)
  (init-dht)
  (java.lang.Thread/sleep 3000)
  (set-my-hash-bytes (create-user-torrent))
  (set-my-hash-str (base32-encode my-hash-bytes))
  (add-user-torrents)
  (send-meta-link-periodically 30))

(defn stop-router
  []
  (if-let [contexts (net.i2p.router.RouterContext/listContexts)]
    (if-not (.isEmpty contexts)
      (if-let [context (.get contexts 0)]
        (.shutdown (.router context) net.i2p.router.Router/EXIT_HARD)))))
