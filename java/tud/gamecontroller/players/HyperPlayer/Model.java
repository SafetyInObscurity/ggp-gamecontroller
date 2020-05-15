/*
    Copyright (C) 2020 Michael Dorrell <michael.dorrell97@gmail.com>

    This file is part of GameController.

    GameController is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GameController is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with GameController.  If not, see <http://www.gnu.org/licenses/>.
*/

package tud.gamecontroller.players.HyperPlayer;

import tud.gamecontroller.game.MoveInterface;
import tud.gamecontroller.game.RoleInterface;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import tud.gamecontroller.game.StateInterface;
import tud.gamecontroller.game.impl.JointMove;
import tud.gamecontroller.term.TermInterface;

/**
 * Holds a model of how the true state of the game may look given the percepts seen so far
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class Model<TermType extends TermInterface> {

    /*
        gameplayTracker tracks the state of the game as it progresses by maintaining a collection of:
            Percepts seen at each step
            Joint Move made to get to state
            State at step
        And is hashed according to stepNum
     */
    private HashMap<Integer, StateTrackerTriple<TermType>> gameplayTracker;
    private LikelihoodTracker likelihoodTracker; // Tracks the likelihood of the model representing the true state

    public Model() {
        this.gameplayTracker = new HashMap<Integer, StateTrackerTriple<TermType>>();
        this.likelihoodTracker = new LikelihoodTracker();
    }

    public HashMap<Integer, StateTrackerTriple<TermType>> getGameplayTracker() { return gameplayTracker; }
    public LikelihoodTracker getLikelihoodTracker() {
        return likelihoodTracker;
    }



    public void updateGameplayTracker(int stepNum, Collection<TermType> percepts, JointMove<TermType> jointAction, StateInterface<TermType, ?> state) {
        if(this.gameplayTracker.containsKey(stepNum)) System.err.println("Key already contained");
        else {
            StateTrackerTriple<TermType> gameplay = new StateTrackerTriple<TermType>(percepts, jointAction, state);
            this.gameplayTracker.put(stepNum, gameplay);
        }
    }

    public StateInterface<TermType, ?> getCurrentState() {
        return this.gameplayTracker.get(gameplayTracker.size()-1).getState();
    }

    /**
     * @return moves that are legal in all of the current possible states
     */
    public Collection<? extends MoveInterface<TermType>> computeLegalMoves(RoleInterface<TermType> role) {
        // Get current state
        StateInterface<TermType, ?> state = getCurrentState();

        // Compute legal moves
        Collection<? extends MoveInterface<TermType>> stateLegalMoves = state.getLegalMoves(role);

        Collection<? extends MoveInterface<TermType>> legalMoves = null;
        legalMoves = new HashSet<MoveInterface<TermType>>(stateLegalMoves);
        legalMoves.retainAll(stateLegalMoves);

        // Return legal moves
        return legalMoves;
    }
}
