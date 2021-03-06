(ns com.github.discoverAI.snake.endpoint-test
  (:require [clojure.test :refer :all]
            [com.github.discoverAI.snake.core :as co]
            [de.otto.tesla.util.test-utils :as tu]
            [com.github.discoverAI.snake.engine :as eg]
            [com.github.discoverAI.snake.endpoint :as ep]
            [com.github.discoverAI.snake.websocket-api :as ws-api]))

(def fake-game-state
  {:board  [20 20]
   :score  0
   :tokens {:snake {:position  [[0 0]]
                    :direction [1 0]
                    :speed     1.0}
            :food  {:position [[1 2]]}}})

(def fake-spectator-id
  "spectator-id-from-websockets-library")

(def system
  {:games               (atom {:foo fake-game-state})
   :game-id->spectators (atom {:foo [fake-spectator-id]})})

(deftest get-game-handler-test
  (testing "should get existing game"
    (is (= {:body    (ep/transform-state-map-to-board-map fake-game-state false)
            :headers {}
            :status  200}
           (ep/get-game-handler system {:params {:id :foo}}))))

  (testing "should return 404 when game not existing"
    (is (= {:body    nil
            :headers {}
            :status  404}
           (ep/get-game-handler system "asd")))))

(deftest post-game-handler-test
  (testing "should create new game"
    (with-redefs [eg/register-new-game (fn [engine width height snake-length _]
                                         (is (= system engine))
                                         (is (= 10 width))
                                         (is (= 5 height))
                                         (is (= 4 snake-length))
                                         :foo)]
      (let [add-game-result (ep/add-game-handler system {:body-params {:width 10 :height 5 :snakeLength 4}})]
        (is (= 201 (:status add-game-result)))))))

(deftest test-change-dir-handler
  (testing "should change direction"
    (tu/with-started
      [mock-system (co/snake-system {})]
      (let [{:keys [gameId]} (:body (ep/add-game-handler (:engine mock-system)
                                                         {:body-params {:width 20 :height 20 :snakeLength 4}}))
            created-game (gameId @(get-in mock-system [:engine :games]))
            direction-changed (assoc-in created-game [:tokens :snake :direction] [0 1])
            expected-state (eg/make-move direction-changed)]
        (is (= (ep/transform-state-map-to-board-map expected-state false)
               (ep/change-dir-handler (:engine mock-system) {:direction :down} gameId)))
        (with-redefs [eg/update-game-state! (fn [_ _ _ _] (assoc-in expected-state [:score] 1))]
         (is (:ate-food (ep/change-dir-handler (:engine mock-system) {:direction :up} gameId))))))))

(deftest test-transform-state-map-to-boardstate
  (testing "should take a state map and return a board state"
    (is (= {:board     [[1]]
            :game-over false
            :ate-food  true
            :score     0}
           (ep/transform-state-map-to-board-map {:game-over false
                                                 :board     [1 1]
                                                 :score     0
                                                 :tokens    {:snake {:position [[0 0]]}
                                                             :food  {:position [[0 0]]}}} true)))

    (is (= {:board     [[1 3]]
            :game-over false
            :ate-food  false
            :score     0}
           (ep/transform-state-map-to-board-map {:game-over false
                                                 :board     [2 1]
                                                 :score     0
                                                 :tokens    {:snake {:position [[0 0]]}
                                                             :food  {:position [[1 0]]}}} false)))
    (is (= {:board     [[1 3] [0 0]]
            :game-over false
            :ate-food  false
            :score     12}
           (ep/transform-state-map-to-board-map {:game-over false
                                                 :board     [2 2]
                                                 :score     12
                                                 :tokens    {:snake {:position [[0 0]]}
                                                             :food  {:position [[1 0]]}}} false)))
    (is (= {:board     [[0 0 0]
                        [0 1 0]
                        [0 0 0]]
            :game-over true
            :ate-food  true
            :score     42}
           (ep/transform-state-map-to-board-map {:game-over true
                                                 :board     [3 3]
                                                 :score     42
                                                 :tokens    {:snake {:position [[1 1]]}
                                                             :food  {:position [[1 1]]}}} true)))
    (is (= {:board     [[0 3 0]
                        [0 1 2]
                        [0 0 0]]
            :game-over true
            :ate-food  false
            :score     33}
           (ep/transform-state-map-to-board-map {:game-over true
                                                 :board     [3 3]
                                                 :score     33
                                                 :tokens    {:snake {:position [[1 1] [2 1]]}
                                                             :food  {:position [[1 0]]}}} false)))
    (is (= {:board     [[2 2 2]
                        [0 1 2]
                        [0 0 0]]
            :game-over true
            :ate-food  true
            :score     12}
           (ep/transform-state-map-to-board-map {:game-over true
                                                 :board     [3 3]
                                                 :score     12
                                                 :tokens    {:snake {:position [[1 1] [2 1] [2 0] [1 0] [0 0]]}
                                                             :food  {:position [[1 1]]}}} true)))))


(deftest test-notify-spectators
  (testing "if spectator notify works"
    (with-redefs [ws-api/push-game-state-to-client
                  (fn [client-id game-state]
                    (is (= fake-spectator-id client-id))
                    (is (= fake-game-state game-state)))]
      (ep/notify-spectators system {:game-id :foo}))))
