(ns com.github.discoverAI.snake.engine
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [com.github.discoverAI.snake.board :as b]
            [de.otto.status :as st]
            [de.otto.tesla.stateful.app-status :as as]
            [overtone.at-at :as at-at]
            [de.otto.tesla.stateful.scheduler :as scheduler]))

(defn game-id [game-state]
  (->> (hash game-state)
       (+ (System/nanoTime))
       (str "G_")
       (keyword)))

(defn new-game [width height snake-length]
  (let [game-state (b/initial-state width height snake-length)]
    {(game-id game-state) game-state}))

(defn vector-addition
  [first second]
  (vec (map + first second)))

(def MOVE_UPDATE_INTERVAL 1000)

(defn move [game-state]
  (let [snake-path [:tokens :snake :position]
        snake (get-in game-state snake-path)
        direction (get-in game-state [:tokens :snake :direction])
        new-head (vector-addition direction (first snake))]
    (cond
      ;TODO check if we run out of bounds or snake eats itself (seperate task)
      :else (assoc-in game-state snake-path
                      (into
                        [new-head]
                        (vec (butlast snake)))))))

(defn register-new-game [{:keys [games scheduler]} width height snake-length]
  (let [game (new-game width height snake-length)
        id (first (keys game))]
    (swap! games merge game)
    (at-at/every MOVE_UPDATE_INTERVAL
                 #(swap! games update id move)
                 (scheduler/pool scheduler)
                 :desc "UpdateGameStateTask")
    id))

(defn games-state-status [games-state-atom]
  (if (and (map? @games-state-atom) (<= 0 (count @games-state-atom)))
    (st/status-detail :engine :ok (str (count @games-state-atom) " games registered"))
    (st/status-detail :engine :error "Engines games are corrupt")))

(defrecord Engine [app-status scheduler]
  c/Lifecycle
  (start [self]
    (log/info "-> starting Engine")
    (let [games-state (atom {})]
      (as/register-status-fun app-status (partial games-state-status games-state))
      (assoc self :games games-state)))
  (stop [_]
    (log/info "<- stopping Engine")))

(defn new-engine []
  (map->Engine {}))