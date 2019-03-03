(ns jackdaw.test.commands.write-test
  (:require
   [jackdaw.test.commands.write :as write]
   [jackdaw.test.transports :as trns]
   [jackdaw.test.transports.kafka]
   [jackdaw.test.serde :as serde]
   [jackdaw.test :refer [test-machine]]
   [clojure.test :refer :all])
  (:import
    [clojure.lang ExceptionInfo]))

(def foo-topic
  (serde/resolver {:topic-name "foo"
                   :replication-factor 1
                   :partition-count 1
                   :key-serde :long
                   :value-serde :edn}))

(def bar-topic
  (serde/resolver {:topic-name "bar"
                   :replication-factor 1
                   :partition-count 1
                   :key-serde :long
                   :value-serde :long}))

(def baz-topic
  (serde/resolver {:topic-name "baz"
                   :replication-factor 1
                   :partition-count 5
                   :key-serde :long
                   :value-serde :json}))

(def baz2-topic
  (serde/resolver {:topic-name "baz2"
                   :replication-factor 1
                   :partition-count 5
                   :key-fn :id2
                   :partition-fn (constantly 100)
                   :key-serde :long
                   :value-serde :json}))

(def kafka-config {"bootstrap.servers" "localhost:9092"
                   "group.id" "kafka-write-test"})



(defn test-key-defaults []
  (let [opts {}
        foos (serde/resolver
              {:topic-name "foo"
               :partition-count 5
               :key-serde :long
               :value-serde :json})
        msg {:id 1 :a 2 :b 3 :payload "yolo"}]

    (testing "fallback to global default"
      (is (= 1 (-> (write/create-message foos msg {})
                   :key))))

    (testing "topic with :key-fn"
      (let [foos (assoc foos :key-fn :a)]
        (is (= 2 (-> (write/create-message foos msg {})
                     :key)))))

    (testing "opts with :key-fn"
      (let [opts (assoc opts :key-fn :b)]
        (is (= 3 (-> (write/create-message foos msg opts)
                     :key)))))

    (testing "opts with explicit :key"
      (let [opts (assoc opts :key 10)]
        (is (= 10 (-> (write/create-message foos msg opts)
                     :key)))))))

(defn test-partition-defaults []
  (let [foos (serde/resolver
              {:topic-name "foo"
               :partition-count 5
               :key-serde :long
               :value-serde :json})
        opts {}
        msg {:id 1 :a 2 :b 3 :payload "yolo"}]

    (testing "fallback to global default"
      (is (= (write/default-partition-fn foos 1)
             (-> (write/create-message foos msg opts)
                 :partition))))

    (testing "topic with :partition-fn"
      (let [foos (assoc foos :partition-fn (constantly 2))]
        (is (= 2 (-> (write/create-message foos msg opts)
                     :partition)))))

    (testing "opts with :partition-fn"
      (let [opts (assoc opts :partition-fn (constantly 3))]
        (is (= 3 (-> (write/create-message foos msg opts)
                     :partition)))))

    (testing "opts with explicit :partition"
      (let [opts (assoc opts :partition 4)]
        (is (= 4 (-> (write/create-message foos msg opts)
                     :partition)))))))

(defn test-bad-partition []
  (let [foos (serde/resolver
               {:topic-name "foo"
                :partition-count 5
                :key-serde :long
                :value-serde :json})
        opts {}
        msg {:id 1 :a 2 :b 3 :payload "yolo"}]

    (testing "partition must be >= 0"
      (is (thrown-with-msg? ExceptionInfo #"Invalid partition number for topic"
             (-> (write/create-message foos msg {:partition -1})
                 :partition))))

    (testing "partition must be < partition count"
        (is (thrown-with-msg? ExceptionInfo #"Invalid partition number for topic"
               (-> (write/create-message foos msg {:partition 5})
                     :partition))))))

(deftest test-create-message
  (test-key-defaults)
  (test-partition-defaults)
  (test-bad-partition))

(defn test-prefix
  []
  (-> (str (java.util.UUID/randomUUID))
      (.substring 0 8)))

(defn with-transport
  [{:keys [topic-config kafka-config]} f]
  (let [prefix (test-prefix)
        topic-config (reduce-kv (fn [m k v]
                                  (let [v (assoc v :topic-name
                                                 (str prefix "-" (:topic-name v)))]
                                    (assoc m k v)))
                                {}
                                topic-config)
        t (trns/transport {:type :kafka
                           :config kafka-config
                           :topics topic-config})]
        (try
          (f t topic-config)
          (finally
            (doseq [hook (:exit-hooks t)]
              (hook))))))

(deftest test-write!
  (with-transport {:topic-config {"foo" foo-topic
                                  "bar" bar-topic}
                   :kafka-config kafka-config}
    (fn [t topics]
      (testing "valid write"
        (let [[cmd & params] [:write! "foo" {:id 1 :payload "yolo"}]
              result (write/handle-write-cmd t cmd params)]

          (testing "returns the kafka record metadata"
            (is (= (:topic-name (get topics "foo"))
                   (:topic-name result)))
            (is (integer? (:offset result)))
            (is (contains? result :partition))
            (is (contains? result :serialized-key-size))
            (is (contains? result :serialized-value-size)))))

      (testing "valid write with explicit key"
        (let [[cmd & params] [:write! "foo" {:id 1 :payload "yolo"} {:key 101}]
              result (write/handle-write-cmd t cmd params)]

          (testing "returns the kafka record metadata"
            (is (= (:topic-name (get topics "foo"))
                   (:topic-name result)))
            (is (integer? (:offset result)))
            (is (contains? result :partition))
            (is (contains? result :serialized-key-size))
            (is (contains? result :serialized-value-size)))))

      (testing "invalid write"
        (testing "serialization failure"
          (let [[cmd & params] [:write! "bar" {:id 1 :payload "a map is not a number"}]
                result (write/handle-write-cmd t cmd params)]
            (is (= :serialization-error (:error result)))
            (is (= "Cannot cast clojure.lang.PersistentArrayMap to java.lang.Long" (:message result)))))))))
