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

import tud.gamecontroller.game.JointMoveInterface;
import tud.gamecontroller.game.MoveInterface;
import tud.gamecontroller.game.RoleInterface;

import java.util.ArrayList;
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
public class Model<TermType extends TermInterface> implements Cloneable{

    private LikelihoodTracker likelihoodTracker; // Tracks the likelihood of the model representing the true state
    private ArrayList<JointMove<TermType>> actionPath;
    private ArrayList<StateInterface<TermType, ?>> statePath;
    private ArrayList<Collection<TermType>> perceptPath;
    private int actionPathHash = -1;

    public Model() {
        this.likelihoodTracker = new LikelihoodTracker();
        this.actionPath = new ArrayList<JointMove<TermType>>();
        this.statePath = new ArrayList<StateInterface<TermType, ?>>();
        this.perceptPath = new ArrayList<Collection<TermType>>();
    }
    public Model(Model<TermType> model) {
        this.likelihoodTracker = new LikelihoodTracker(model.getLikelihoodTracker());
        this.actionPath = new ArrayList<JointMove<TermType>>(model.getActionPath());
        this.statePath = new ArrayList<StateInterface<TermType, ?>>(model.getStatePath());
        this.perceptPath = new ArrayList<Collection<TermType>>(model.getPerceptPath());
        this.actionPathHash = model.getActionPathHash();
    }

    public LikelihoodTracker getLikelihoodTracker() { return this.likelihoodTracker; }
    public ArrayList<JointMove<TermType>> getActionPath() { return this.actionPath; }
    public ArrayList<StateInterface<TermType, ?>> getStatePath() { return statePath; }
    public ArrayList<Collection<TermType>> getPerceptPath() { return perceptPath; }
    public int getActionPathHash() { return this.actionPathHash; }


    public void updateGameplayTracker(int stepNum, Collection<TermType> initialPercepts, JointMove<TermType> jointAction, StateInterface<TermType, ?> currState, RoleInterface<TermType> role) {
        if(this.actionPath.size() > stepNum) System.err.println("Key already contained");
        else {
            // Calculate next state from joint action, state pair
            StateInterface<TermType, ?> newState = null;
            Collection<TermType> expectedPercepts = null;
            if(jointAction != null) {
                newState = currState.getSuccessor(jointAction);
                expectedPercepts = currState.getSeesTerms(role, jointAction);
            } else {
                newState = currState;
                expectedPercepts = initialPercepts;
            }

            // Add all to the action pairs
            this.actionPath.add(jointAction);
            this.statePath.add(newState);
            this.perceptPath.add(expectedPercepts);
            this.actionPathHash = this.actionPath.hashCode();
        }
    }

    public StateInterface<TermType, ?> getCurrentState() {
        return this.statePath.get(this.statePath.size() - 1);
    }

    public Collection<TermType> getLatestExpectedPercepts() {
        return this.perceptPath.get(this.perceptPath.size() - 1);
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
