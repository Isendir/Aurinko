(set! *warn-on-reflection* true)
(ns Aurinko.skiplist
  (:import (java.io File RandomAccessFile))
  (:import (java.nio.channels FileChannel FileChannel$MapMode)
           (java.nio MappedByteBuffer)))

(def ^:const FILE-HDR (int 12))  ; file header: next insert pos, number of levels, chance
(def ^:const PTR-SIZE (int 4))   ; every pointer in the file is an integer
(def ^:const NODE     (int 8))   ; every node has integer validity (0 - invalid, 1 - valid) and node value
(def ^:const NIL      (int -1))  ; nil pointer
(def ^:const GROW (int 4194304)) ; grow index by 4MB when necessary

(defprotocol SkipListP
  (at      [this node-num] "Put file handle at the specified node's position")
  (node-at [this node-num] "Return the node's number, level pointers and value")
  (cut-lvl [this v lvl begin-from cmp-fun] "Cut a level from the specified node number, look for value matches")
  (insert [this v] "Put a value into list")
  (lookup [this v cmp-fun] "Find all nodes which contain the value")
  (findv  [this v] "Find all nodes which cmp-docval thinks that they contain the value")
  (x      [this v] "Remove a value")
  (scan<  [this v] "Scan for values less than v")
  (scan<= [this v] "Scan for values less or equal to v")
  (scan>  [this v] "Scan for values greater than v")
  (scan>= [this v] "Scan for values greater or equal to v")
  (scan<> [this v] "Scan for values not equal to v")
  (scan>< [this v1 v2] "Scan for values within range")
  (all    [this] "Return all nodes")
  (save   [this])
  (close  [this]))

; cmp-docs - compare indexed values inside two documents ; cmp-docval - compare one document with an indexed value
; see documentation for more details on their usage in this skiplist implementation
(deftype SkipList [path levels P fc ^{:unsynchronized-mutable true} file cmp-docs cmp-docval] SkipListP
  (at [this node-num]
      (.position ^MappedByteBuffer file (+ FILE-HDR (* node-num (+ NODE (* PTR-SIZE levels))))))
  (node-at [this node-num]
           (at this node-num)
           {:n node-num :valid (= (.getInt ^MappedByteBuffer file) 1) :v (.getInt ^MappedByteBuffer file)
            :lvls (vec (map (fn [_] (.getInt ^MappedByteBuffer file)) (range levels)))})
  (cut-lvl [this v lvl begin-from cmp-fun]
           (loop [curr-node-num (int begin-from)
                  prev-node     (node-at this begin-from)
                  matches       (transient [])]
             (if (= curr-node-num NIL)
               {:node prev-node :matches (persistent! matches)}
               (let [curr-node     (node-at this curr-node-num)
                     next-node-num (int (nth (:lvls curr-node) lvl))
                     cmp-result    (cmp-fun (:v curr-node) v)]
                 (cond
                   (= cmp-result -1) ; continue next if node value is less
                   (recur next-node-num curr-node matches)
                   (= cmp-result 0)  ; keep the node if matching 
                   (recur next-node-num curr-node (conj! matches curr-node))
                   (= cmp-result 1)  ; return when node value is greater
                   {:node prev-node :matches (persistent! matches)})))))
  (insert [this v]
          (let [file-limit   (int (.limit ^MappedByteBuffer file))
                node-size    (int (+ NODE (* levels PTR-SIZE)))
                new-node-pos (int (do (.position ^MappedByteBuffer file 0) (.getInt ^MappedByteBuffer file)))
                empty-list?  (= new-node-pos FILE-HDR)
                new-node-num (int (quot (- new-node-pos FILE-HDR) node-size))
                top-lvl      (int (loop [lvl (int 0)]
                                    (if (and (< lvl (dec levels)) (< (Math/random) P))
                                      (recur (inc lvl))
                                      lvl)))]
            (when (< file-limit (+ new-node-pos node-size))
              (set! file (.map ^FileChannel fc FileChannel$MapMode/READ_WRITE
                           0 (+ file-limit GROW))))
            (if empty-list? ; the new node is the only node
              (do ; write node value, all levels point to NIL
                (.position ^MappedByteBuffer file FILE-HDR)
                (.putInt   ^MappedByteBuffer file 1)
                (.putInt   ^MappedByteBuffer file v)
                (doseq [i (range levels)]
                  (.putInt ^MappedByteBuffer file NIL)))
              (let [first-node (node-at this 0)]
                (if (= (cmp-docs (:v first-node) v) 1) ; replace first node by the new node
                  (let [equal-top (int (- levels (count (filter #(= % NIL) (:lvls first-node)))))]
                    (.position ^MappedByteBuffer file FILE-HDR)
                    (.putInt   ^MappedByteBuffer file 1)
                    (.putInt   ^MappedByteBuffer file v)
                    (doseq [v (range (max equal-top 1))]
                      (.putInt ^MappedByteBuffer file new-node-num))
                    (doseq [v (range (- levels (max equal-top 1)))]
                      (.putInt ^MappedByteBuffer file NIL))
                    (.position ^MappedByteBuffer file new-node-pos)
                    (.putInt   ^MappedByteBuffer file 1)
                    (.putInt   ^MappedByteBuffer file (:v first-node))
                    (doseq [v (range equal-top)]
                      (.putInt ^MappedByteBuffer file (nth (:lvls first-node) v)))
                    (doseq [v (range (- levels equal-top))]
                      (.putInt ^MappedByteBuffer file NIL)))
                  (do ; insert the new node after another node
                    (.position ^MappedByteBuffer file new-node-pos)
                    (.putInt   ^MappedByteBuffer file 1)
                    (.putInt   ^MappedByteBuffer file v)
                    (doseq [v (range levels)]
                      (.putInt ^MappedByteBuffer file NIL))
                    (let [match (lookup this v cmp-docs)]
                      ; If new node value already exists, the new node must reach as tall as the match reaches
                      (loop [lvl      (if (empty? match) top-lvl (int (max 0 (dec (count (filter #(not= % -1) (:lvls (last match))))))))
                             node-num (if (empty? match) (int 0) (int (:n (last match))))]
                        (when (> lvl -1)
                          (let [lvl-cut       (cut-lvl this v lvl node-num cmp-docs)
                                last-lvl-node (int (:n (:node lvl-cut)))]
                            (do
                              (at this last-lvl-node)
                              (let [ptr-pos (int (+ (.position ^MappedByteBuffer file) NODE (* PTR-SIZE lvl)))
                                    old-node-num (int (do (.position ^MappedByteBuffer file ptr-pos) (.getInt ^MappedByteBuffer file)))]
                                (.position ^MappedByteBuffer file ptr-pos)       ; at existing node's level pointer
                                (.putInt   ^MappedByteBuffer file new-node-num)  ; point it to the new node
                                (.position ^MappedByteBuffer file (+ new-node-pos NODE (* PTR-SIZE lvl))) ; at new node's level pointer
                                (.putInt   ^MappedByteBuffer file old-node-num)) ; point it to the old node's pointer value
                              (recur (dec lvl) last-lvl-node))))))))))
            (.position ^MappedByteBuffer file 0) ; write next insert position
            (.putInt   ^MappedByteBuffer file (+ new-node-pos node-size))))
  (lookup [this v cmp-fun]
          (loop [lvl (dec levels)
                 node-num (int 0)
                 matches nil]
            (if (> lvl -1)
              (let [lvl-cut     (cut-lvl this v lvl node-num cmp-fun)
                    lvl-matches (:matches lvl-cut)]
                (recur (dec lvl)
                       (int (if (empty? lvl-matches)
                              (:n (:node lvl-cut))
                              ; Found match? Descend a level and scan from the first matching node
                              (:n (first lvl-matches))))
                       (if (> (count lvl-matches) (count matches))
                         lvl-matches
                         matches)))
              matches)))
  (findv [this v]
         (lookup this v cmp-docval))
  (x [this v]
     (doseq [match (filter #(:valid %) (lookup this v cmp-docval))]
       (at this (:n match))
       (.putInt ^MappedByteBuffer file 0)))
  (scan< [this v]
         (loop [nodes (transient [])
                next   (int 0)]
           (if (= next NIL)
             (persistent! nodes)
             (let [node (node-at this next)]
               (if (= -1 (cmp-docval (:v node) v))
                 (recur (conj! nodes node) (int (first (:lvls node))))
                 (persistent! nodes))))))
  (scan<= [this v]
         (loop [nodes (transient [])
                next   (int 0)]
           (if (= next NIL)
             (persistent! nodes)
             (let [node (node-at this next)]
               (if (<= (cmp-docval (:v node) v) 0)
                 (recur (conj! nodes node) (int (first (:lvls node))))
                 (persistent! nodes))))))
  (scan> [this v]
         (let [start-from (lookup this v cmp-docval)]
           (if (or (nil? start-from) (empty? start-from))
             []
             (loop [nodes (transient [])
                    next  (int (first (:lvls (first start-from))))]
               (if (= next NIL)
                 (persistent! nodes)
                 (let [node (node-at this next)]
                   (recur (conj! nodes node) (int (first (:lvls node))))))))))
  (scan>= [this v]
          (let [start-from (lookup this v cmp-docval)]
            (if (or (nil? start-from) (empty? start-from))
              []
              (loop [nodes (transient [(first start-from)])
                     next  (int (first (:lvls (first start-from))))]
                (if (= next NIL)
                  (persistent! nodes)
                  (let [node (node-at this next)]
                    (recur (conj! nodes node) (int (first (:lvls node))))))))))
  (scan<> [this v]
          (loop [nodes (transient [])
                 next   (int 0)]
            (if (= next NIL)
              (persistent! nodes)
              (let [node (node-at this next)]
                (recur (if (= 0 (cmp-docval (:v node) v))
                         nodes
                         (conj! nodes node))
                       (int (first (:lvls node))))))))
  (scan>< [this v1 v2]
          (let [start-from (lookup this v1 cmp-docval)]
            (if (or (nil? start-from) (empty? start-from))
              []
              (loop [nodes (transient [(first start-from)])
                     next  (int (first (:lvls (first start-from))))]
                (if (= next NIL)
                  (persistent! nodes)
                  (let [node (node-at this next)]
                    (if (= -1 (cmp-docval (:v node) v2))
                      (recur (conj! nodes node) (int (first (:lvls node))))
                      (persistent! nodes))))))))
  (all [this]
       (loop [curr-node-num 0
              everything    (transient [])]
         (if (= curr-node-num NIL)
           (persistent! everything)
           (let [curr-node     (node-at this curr-node-num)
                 next-node-num (int (nth (:lvls curr-node) 0))]
             (conj! everything curr-node)
             (recur next-node-num everything)))))
  (save  [this] (.force ^FileChannel fc false))
  (close [this] (save this) (.close ^FileChannel fc)))

(defn open [path cmp-docs cmp-docval]
  (let [fc   (.getChannel (RandomAccessFile. ^String path "rw"))
        file (.map fc FileChannel$MapMode/READ_WRITE 0 (.size fc))]
    (SkipList. path (int (.getInt file))
              (/ (.getInt file)) fc file cmp-docs cmp-docval)))

(defn new [path levels chance cmp-docs cmp-docval]
  (let [fc   (.getChannel (RandomAccessFile. ^String path "rw"))
        file (.map fc FileChannel$MapMode/READ_WRITE 0 FILE-HDR)]
    (.putInt file FILE-HDR) ; next insert position
    (.putInt file levels)
    (.putInt file chance)
    (open path cmp-docs cmp-docval)))