/*
    Copyright (C) 2008-2010 Stephan Schiffel <stephan.schiffel@gmx.de>
                  2010-2020 Nicolas JEAN <njean42@gmail.com>
                  2020 Michael Dorrell <michael.dorrell97@gmail.com>

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

import tud.gamecontroller.ConnectionEstablishedNotifier;
import tud.gamecontroller.GDLVersion;
import tud.gamecontroller.game.*;
import tud.gamecontroller.players.LocalPlayer;
import tud.gamecontroller.term.TermInterface;

import java.util.*;

/**
 * HyperPlayer is an agent that can play imperfect information and non-deterministic two player extensive-form games
 * with perfect recall by holding many 'hypergames' as models that may represent the true state of the game in perfect
 * information representation. It then calculates the best move for each hypergame weighted against the likelihood of
 * it representing the true state of the game and returns the moves with the greatest weighted expected payoff.
 * Implements the algorithm descrived in Michael Schofield, Timothy Cerexhe and Michael Thielscher's HyperPlay paper
 * @see "https://staff.cdms.westernsydney.edu.au/~dongmo/GTLW/Michael_Tim.pdf"
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class HyperPlayer<
	TermType extends TermInterface,
	StateType extends StateInterface<TermType, ? extends StateType>> extends LocalPlayer<TermType, StateType>  {

	private Random random;
	private int numHyperGames = 10;

	private int stepNum; // Tracks the steps taken
	private HashMap<Integer, MoveInterface<TermType>> actionTracker; // Tracks the action taken at each step by the player (from 0)
	private HashMap<Integer, Collection<TermType>> perceptTracker; // Tracks the percepts seen at each step by the player (from 0)
	private HashMap<String, Collection<MoveInterface<TermType>>> badMovesTracker; // Tracks the invalid moves from each perfect-information state
	private Collection<Model<TermType>> hypergames; // Holds a set of possible models for the hypergame

	public HyperPlayer(String name, GDLVersion gdlVersion) {
		super(name, gdlVersion);
		random = new Random();
	}

	/**
	 * Run when the game is start to perform basic set-up
	 */
	@Override
	public void gameStart(RunnableMatchInterface<TermType, StateType> match, RoleInterface<TermType> role, ConnectionEstablishedNotifier notifier) {
		super.gameStart(match, role, notifier);

		// Instantiate globals
		actionTracker = new HashMap<Integer, MoveInterface<TermType>>();
		perceptTracker = new HashMap<Integer, Collection<TermType>>();
		badMovesTracker = new HashMap<String, Collection<MoveInterface<TermType>>>();
		hypergames = new ArrayList<Model<TermType>>();
		stepNum = 0;
	}

	/*
	 * @param seesFluents is either:
	 * - with regular GDL, a "JointMoveInterface<TermType>" object that is the jointMove previously done by players
	 * - or with GDL-II, a "Collection<? extends FluentInterface<TermType>>" object that really are SeesTerms
	 */
	@SuppressWarnings("unchecked")
	@Override
	public MoveInterface<TermType> gamePlay(Object seesTerms, ConnectionEstablishedNotifier notifier) {
		notifyStartRunning();
		notifier.connectionEstablished();
		if(seesTerms != null) {
			// calculate the successor(s) of current state(s)
			statesTracker.statesUpdate((Collection<TermType>) seesTerms);
		}
		perceptTracker.put(stepNum, (Collection<TermType>) seesTerms); // Puts the percepts in the map at the current step
		MoveInterface<TermType> move = getNextMove();
		actionTracker.put(stepNum, move); // Puts the action taken in the map at the current step

		System.out.println(perceptTracker.toString());
		System.out.println(actionTracker.toString());

		notifyStopRunning();
		stepNum++;
		return move;
	}

	/**
	 * Returns the agent's next move
	 *
	 * @return A legal move
	 */
	public MoveInterface<TermType> getNextMove() {
		ArrayList<MoveInterface<TermType>> legalMoves = new ArrayList<MoveInterface<TermType>>(getLegalMoves());



		int i = random.nextInt(legalMoves.size());
		return legalMoves.get(i);
	}
}
