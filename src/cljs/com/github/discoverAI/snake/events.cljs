(ns com.github.discoverAI.snake.events
  (:require [re-frame.core :as re-frame]
            [com.github.discoverAI.snake.db :as db]
            [com.github.discoverAI.snake.core :as core]))

(def ARROW_KEY_CODES [37 38 39 40])

(defn key-pressed [event]
  (if (some #{(.-keyCode event)} ARROW_KEY_CODES)
    (core/chsk-send! [::key-pressed {:id (.-keyCode event)}])))

(re-frame/reg-event-db
  ::initialize-db
  (fn [_ _]
    (set! (.-onkeydown js/window) key-pressed)
    db/default-db))

(defn start-state [_db _event]
  db/mock-start-state)

(re-frame/reg-event-db
  ::start-game
  start-state)
