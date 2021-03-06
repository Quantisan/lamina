;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.query
  (:use
    [potemkin]
    [lamina core])
  (:require
    [lamina.time :as time]
    [lamina.query.core :as c]
    [lamina.query.parse :as p]
    [lamina.query.operators :as o]))

;;;

(import-vars
  [lamina.query.core

   def-trace-operator])

(defn parse-descriptor
  "Parses the query descriptor down to the canonical representation."
  ([x]
     (parse-descriptor x nil))
  ([x {:as options}]
     (let [descriptor (if (string? x)
                        (p/parse-stream x)
                        x)]
       descriptor)))

(defn query-streams
  "A variant of query-stream, wihch takes a map of multiple descriptors onto the source channels,
   and returns a map of those same descriptors onto the results."
  [descriptor->channel
   {:keys [task-queue
           timestamp
           payload
           period
           stream-generator]
    :or {payload identity}
    :as options}]
  (let [;; make sure inner-streams are properly deferred
        stream-generator (if timestamp
                           #(->> %
                              stream-generator
                              (map* identity)
                              (defer-onto-queue task-queue timestamp)
                              (map* payload))
                           stream-generator)

        ;; make sure input streams are properly deferred
        descriptor->channel (zipmap
                              (keys descriptor->channel)
                              (if timestamp
                                (->> descriptor->channel
                                  vals
                                  (map
                                    #(->> %
                                       (defer-onto-queue task-queue timestamp)
                                       (map* payload))))
                                (vals descriptor->channel)))

        ;; parse and apply descriptors
        f #(zipmap
             (keys descriptor->channel)
             (map
               (fn [[descriptor ch]]
                 (if (ifn? descriptor)
                   (descriptor ch)
                   (let [desc (parse-descriptor descriptor options)
                         ch (or ch (stream-generator (desc "pattern")))]
                     (c/transform-trace-stream desc ch))))
               descriptor->channel))

        ;; set up evaluation scopes
        f #(with-bindings
             (merge {}
               (when stream-generator
                 {#'c/*stream-generator* stream-generator}))
             (f))
        f (if period
            #(time/with-period period (f))
            f)
        f (if task-queue
            #(time/with-task-queue task-queue (f))
            f)]
    (f)))

(defn query-stream
  "A function which applies a transform to a stream, with some convenience methods for dealing with non-wall clock analysis."
  [descriptor
   {:keys [task-queue
           timestamp
           payload
           period
           stream-generator]
    :or {payload identity}
    :as options}
   ch]
  (->> (query-streams {descriptor ch} options)
    vals
    first))

(defn query-seqs
  "A variant of `query-seq` which allows for multiple seqs to be simultaneously processed.  Instead of taking a single
   seq, it takes a map of descriptors onto sequences.  If the sequence is nil, the descriptor is presumed to describe both
   the origin and transform (i.e. `{\"abc.rate()\" nil}` is valid, `{\".rate()\" nil} is not).

   Returns a map of descriptors onto the result sequences."
  [descriptor->seq
   {:keys [timestamp
           payload
           period
           seq-generator]
    :or {payload identity}
    :as options}]
  (assert timestamp)
  (let [q (time/non-realtime-task-queue 0 false)

        ;; lazily iterate over seqs
        enqueue-next
        (fn enqueue-next [s ch]
          (if-let [s (seq s)]
            (let [x (first s)]
              (time/invoke-at q (timestamp x)
                (with-meta
                  (fn []
                    (enqueue ch (payload x))
                    (enqueue-next (rest s) ch))
                  {:priority Integer/MAX_VALUE})))
            (time/invoke-at q (inc (time/now q))
              (with-meta
                #(close ch)
                {:priority -1}))))

        chs (repeatedly (count descriptor->seq) channel)

        ;; create output streams
        descriptor->ch
        (query-streams (zipmap
                         (keys descriptor->seq)
                         (map #(when %1 %2) (vals descriptor->seq) chs))
          {:task-queue q
           :period period
           :stream-generator (when seq-generator
                               (fn [descriptor]
                                 (let [ch (channel)]
                                   (enqueue-next
                                     (seq-generator descriptor)
                                     ch)
                                   ch)))})]

    ;; set up consumption of the incoming seqs
    (doseq [[s ch] (map list (vals descriptor->seq) chs)]
      (enqueue-next s ch))

    ;; set up production of the outgoing seq
    (let [advance-until-message
          (fn [ch]
            (let [latch (atom true)
                  result (run-pipeline
                           (read-channel* ch :on-drained ::drained)
                           (fn [val]
                             (reset! latch false)
                             val))]
              (loop []
                (if (and (time/advance q) @latch)
                  (recur)
                  @result))))]

      (zipmap
        (keys descriptor->ch)
        (map
          (fn [ch]
            (->> (repeatedly #(advance-until-message ch))
              (take-while #(not= ::drained %))
              (map #(hash-map :timestamp (time/now q) :value %))))
          (vals descriptor->ch))))))

(defn query-seq
  "Takes a sequence `s` and a `descriptor`, which may either be a function that takes a channel, or a query descriptor.

   Required parameters:

   `:timestamp` - a function that takes an element of the sequence and returns the associated time.
   
   Optional parameters:
   
   `:payload` - a function that takes an element of the sequence and returns the assocated value (defaults to identity)

   `:period` - the default period for periodic operators

   `:seq-generator` - a function that takes a pattern (i.e. in \"abc.rate()\" the pattern is \"abc\") and returns the base
                      sequence.  This is useful for merging together multiple streams in your query."
  [descriptor
   {:keys [timestamp
           payload
           period
           seq-generator]
    :or {payload identity}
    :as options}
   s]
  (->> (query-seqs {descriptor s} options)
    vals
    first))
