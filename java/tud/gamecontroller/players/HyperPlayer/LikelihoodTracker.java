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

import java.util.ArrayList;

/**
 * Tracks the likelihood of a Model representing the true state of the game
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class LikelihoodTracker {

    private double likelihoodScore; // The relative likelihood of a model representing the true state at the current step
    private ArrayList<Integer> optionsList; // Holds the number of total valid moves at each step

    public LikelihoodTracker(){
        this.likelihoodScore = 0.0;
        this.optionsList = new ArrayList<Integer>();
    }
    public LikelihoodTracker(LikelihoodTracker likelihoodTracker){
        this.likelihoodScore = likelihoodTracker.getLikelihoodScore();
        this.optionsList = new ArrayList<Integer>(likelihoodTracker.getOptionsList());
    }

    public double getLikelihoodScore() {
        return likelihoodScore;
    }
    public ArrayList<Integer> getOptionsList() {
        return optionsList;
    }
}
