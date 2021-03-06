(ns ziggurat.mapper
  (:require [sentry-clj.async :as sentry]
            [ziggurat.metrics :as metrics]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.new-relic :as nr]
            [ziggurat.sentry :refer [sentry-reporter]])
  (:import (java.time Instant)))

(defn- send-msg-to-channel [channels message topic-entity return-code]
  (when-not (contains? (set channels) return-code)
    (throw (ex-info "Invalid mapper return code" {:code return-code})))
  (producer/publish-to-channel-instant-queue topic-entity return-code message))

(defn mapper-func [mapper-fn topic-entity channels]
  (fn [message]
    (let [topic-entity-name          (name topic-entity)
          new-relic-transaction-name (str topic-entity-name ".handler-fn")
          metric-namespace           (str topic-entity-name ".message-processing")]
      (nr/with-tracing "job" new-relic-transaction-name
        (try
          (let [start-time  (.toEpochMilli (Instant/now))
                return-code (mapper-fn message)
                end-time    (.toEpochMilli (Instant/now))]
            (metrics/report-time (str topic-entity-name ".handler-fn-execution-time") (- end-time start-time))
            (case return-code
              :success (metrics/increment-count metric-namespace "success")
              :retry (do (metrics/increment-count metric-namespace "retry")
                         (producer/retry message topic-entity))
              :skip (metrics/increment-count metric-namespace "skip")
              :block 'TODO
              (do
                (send-msg-to-channel channels message topic-entity return-code)
                (metrics/increment-count metric-namespace "success"))))
          (catch Throwable e
            (producer/retry message topic-entity)
            (sentry/report-error sentry-reporter e (str "Actor execution failed for " topic-entity-name))
            (metrics/increment-count metric-namespace "failure")))))))

(defn channel-mapper-func [mapper-fn topic-entity channel]
  (fn [message]
    (let [topic-entity-name            (name topic-entity)
          channel-name                 (name channel)
          metric-namespace             (str topic-entity-name "." channel-name)
          message-processing-namespace (str metric-namespace ".message-processing")]
      (nr/with-tracing "job" metric-namespace
        (try
          (let [start-time  (.toEpochMilli (Instant/now))
                return-code (mapper-fn message)
                end-time    (.toEpochMilli (Instant/now))]
            (metrics/report-time (str metric-namespace ".execution-time") (- end-time start-time))
            (case return-code
              :success (metrics/increment-count message-processing-namespace "success")
              :retry (do (metrics/increment-count message-processing-namespace "retry")
                         (producer/retry-for-channel message topic-entity channel))
              :skip (metrics/increment-count message-processing-namespace "skip")
              :block 'TODO
              (throw (ex-info "Invalid mapper return code" {:code return-code}))))
          (catch Throwable e
            (producer/retry-for-channel message topic-entity channel)
            (sentry/report-error sentry-reporter e (str "Channel execution failed for " topic-entity-name " and for channel " channel-name))
            (metrics/increment-count message-processing-namespace "failure")))))))