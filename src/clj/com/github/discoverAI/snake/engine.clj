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
  (let [game-state (b/place-food (b/initial-state width height snake-length))]
    {(game-id game-state) game-state}))

(defn- vector-add [v1 v2]
  (map + v1 v2))

(defn new-direction-vector [old new]
  (if (= (vector-add old new) [0 0])
    old
    new))

(defn modulo-vector [position-vector modulos]
  (map mod position-vector modulos))

(defn concat-to-snake-head [snake-head direction board]
  (-> (vector-add snake-head direction)
      (modulo-vector board)))

(def MOVE_UPDATE_INTERVAL 100)

(defn game-over? [game-state]
  (not (apply distinct?
              (get-in game-state [:tokens :snake :position]))))

(defn end-game [game-state]
  (assoc game-state :game-over true))

(defn snake-on-food? [game-state]
  (= (first (get-in game-state [:tokens :snake :position]))
     (first (get-in game-state [:tokens :food :position]))))

(defn drag-tail [snake-position]
  (drop-last snake-position))

(defn expand-head [direction board snake-position]
  (concat [(concat-to-snake-head (first snake-position) direction board)]
          snake-position))

(defn move-snake [{:keys [tokens]} update-fn]
  (let [snake (:snake tokens)]
    (update snake :position update-fn)))

(defn increase-score [game-state]
  (update-in game-state [:score] inc))

(defn moved-snake [game-state update-fn]
  (assoc-in game-state [:tokens :snake] (move-snake game-state update-fn)))

(defn make-move [game-state]
  (let [next-game-state
        (moved-snake game-state
                     (partial expand-head
                              (get-in game-state [:tokens :snake :direction])
                              (:board game-state)))]
    (if (game-over? next-game-state)
      next-game-state
      (if (snake-on-food? next-game-state)
        (-> next-game-state
            (b/place-food)
            (increase-score))
        (moved-snake next-game-state drag-tail)))))

(defn change-direction [games-state game-id direction]
  (let [direction-path [game-id :tokens :snake :direction]
        current-dir (get-in @games-state direction-path)]
    (swap! games-state assoc-in direction-path
           (new-direction-vector current-dir direction))))

(defn update-game-state! [games-atom game-id->scheduled-job-id-atom game-id callback-fn]
  (let [new-game-state (if (game-over? (game-id @games-atom))
                         (do
                           (at-at/stop (game-id @game-id->scheduled-job-id-atom))
                           (swap! game-id->scheduled-job-id-atom dissoc game-id)
                           (swap! games-atom update game-id end-game))
                         (swap! games-atom update game-id make-move))]
    (callback-fn (merge {:game-id game-id} (game-id @games-atom)))
    (game-id new-game-state)))

(defn- schedule-game-update [games-atom scheduler game-id game-id->scheduled-job-id-atom callback-fn]
  (at-at/every MOVE_UPDATE_INTERVAL
               #(update-game-state! games-atom game-id->scheduled-job-id-atom game-id callback-fn)
               (scheduler/pool scheduler)
               :desc (str "Update game " game-id)))

(defn- register-scheduled-job [scheduled-job game-id game-id->scheduled-job-id]
  (swap! game-id->scheduled-job-id assoc game-id scheduled-job))

(defn register-game-without-timer [games width height snake-length]
  (let [game (new-game width height snake-length)
        game-id (first (keys game))]
    (swap! games merge game)
    game-id))

(defn register-new-game [{:keys [games scheduler game-id->scheduled-job-id]} width height snake-length callback-fn]
  (let [game-id (register-game-without-timer games width height snake-length)]
    (-> (schedule-game-update games scheduler game-id game-id->scheduled-job-id callback-fn)
        (register-scheduled-job game-id game-id->scheduled-job-id))
    game-id))

(defn add-spectator [game-id->spectators uid game-id]
  (assoc game-id->spectators
    game-id
    (conj (game-id game-id->spectators) uid)))

(defn register-spectator [{:keys [game-id->spectators]} uid game-id]
  (swap! game-id->spectators add-spectator uid (keyword game-id)))

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
      (assoc self
        :games games-state
        :game-id->scheduled-job-id (atom {})
        :game-id->spectators (atom {}))))
  (stop [_]
    (log/info "<- stopping Engine")))

(defn new-engine []
  (map->Engine {}))
