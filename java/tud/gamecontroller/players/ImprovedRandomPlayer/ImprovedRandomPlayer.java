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

package tud.gamecontroller.players.ImprovedRandomPlayer;

import java.io.FileWriter;   // Import the FileWriter class
import java.io.IOException;  // Import the IOException class to handle errors

import tud.auxiliary.CrossProductMap;
import tud.gamecontroller.ConnectionEstablishedNotifier;
import tud.gamecontroller.GDLVersion;
import tud.gamecontroller.game.*;
import tud.gamecontroller.game.impl.JointMove;
import tud.gamecontroller.players.LocalPlayer;
import tud.gamecontroller.term.TermInterface;

import java.util.*;

/*
	How it works:

	At game start:
		Prepares the data structures

	On first move:
		Makes an inital model with:
			null joint action
			initial percepts
			initial state
		Uses this model to generate all legal moves and select one

	On all subsequent moves:
		Update percepts
		Add last move to action tracker
		For each model:
			Update model by choosing a random move such that:
				The player's move matches the last move actually performed
			Ensure the percepts of this update match the actual percepts received after the last move. If they do not:
				Add move to 'bad moves' tracker for state
				Remove model from hypergame set
			Branch the model by:
				Cloning the original model and update such that:
					Player's move matches the last move actually performed
					The move chosen isn't the same move as was chosen for a previoud update/branch from this state
				Ensure percepts match, then add to hypergame set up to limit
			For all updates and branches, add the legal moves to a set
		Select a move from the legal moves set

	Move Selection:
		Random amongst legal hypergames
 */

/**
 * ImprovedRandomPlayer is an agent that can play imperfect information and non-deterministic two player extensive-form
 * games with perfect recall by holding many 'hypergames' as models that may represent the true state of the game in
 * perfect information representation. It then chooses a random move from the legal moves available to it in any of these
 * hypergames.
 *
 * Mostly implements the algorithm described in Michael Schofield, Timothy Cerexhe and Michael Thielscher's HyperPlay paper
 * @see "https://staff.cdms.westernsydney.edu.au/~dongmo/GTLW/Michael_Tim.pdf"
 *
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class ImprovedRandomPlayer<
		TermType extends TermInterface,
		StateType extends StateInterface<TermType, ? extends StateType>> extends LocalPlayer<TermType, StateType>  {

	// Logging variables
	private String matchID;
	private String gameName;
	private String roleName;

	// Hyperplay variables
	private Random random;
	/* FOR BASIC TESTING:
		numHyperGames = 50
		numHyperBranches = 25 (16 also works for 4x4)
		numProbes = 4 (although this shouldn't matter since it isn't used)
	 */
	private int numHyperGames = 16; // The maximum number of hypergames allowable
	private int numHyperBranches = 2; // The amount of branches allowed
	private HashMap<Integer, Collection<JointMove<TermType>>> currentlyInUseMoves; // Tracks all of the moves that are currently in use
	private int numProbes = -1; // The number of simulations to run for each possible move for each hypergame
	private int stepNum; // Tracks the steps taken
	private HashMap<Integer, MoveInterface<TermType>> actionTracker; // Tracks the action taken at each step by the player (from 0)
	private HashMap<Integer, Collection<TermType>> perceptTracker; // Tracks the percepts seen at each step by the player (from 0)
	private HashMap<Integer, Collection<JointMove<TermType>>> badMovesTracker; // Tracks the invalid moves from each perfect-information state
	private ArrayList<Model<TermType>> hypergames; // Holds a set of possible models for the hypergame

	public ImprovedRandomPlayer(String name, GDLVersion gdlVersion) {
		super(name, gdlVersion);
		random = new Random();
	}

	/**
	 * Runs at game start to set-up the player
	 *
	 * @param match - The match being played
	 * @param role - The role of the player
	 * @param notifier - Indicates the player's intentions to the gamecontroller
	 */
	@Override
	public void gameStart(RunnableMatchInterface<TermType, StateType> match, RoleInterface<TermType> role, ConnectionEstablishedNotifier notifier) {
		super.gameStart(match, role, notifier);

		// Instantiate globals
		actionTracker = new HashMap<Integer, MoveInterface<TermType>>();
		perceptTracker = new HashMap<Integer, Collection<TermType>>();
		badMovesTracker = new HashMap<Integer, Collection<JointMove<TermType>>>();
		currentlyInUseMoves = new HashMap<Integer, Collection<JointMove<TermType>>>();
		hypergames = new ArrayList<Model<TermType>>();
		stepNum = 0;

		// Instantiate logging variables
		matchID = match.getMatchID();
		gameName = match.getGame().getName();
		roleName = role.toString();
	}

	/**
	 * Runs at the start of each player's turn to update imperfect information state and get the next move of the player
	 *
	 * @param seesTerms - The percepts seen by the player after the last turn
	 * @param priorMove - The move performed by the player at the last turn
	 * @param notifier - Indicates the player's intentions to the gamecontroller
	 * @return The move the player has selected
	 */
	@SuppressWarnings("unchecked")
	@Override
	public MoveInterface<TermType> gamePlay(Object seesTerms, Object priorMove, ConnectionEstablishedNotifier notifier) {
		notifyStartRunning();
		notifier.connectionEstablished();
		perceptTracker.put(stepNum, (Collection<TermType>) seesTerms); // Puts the percepts in the map at the current step
		if(stepNum >= 0) {
			actionTracker.put(stepNum - 1, (MoveInterface<TermType>) priorMove); // Note: This won't get the final move made
		}
		MoveInterface<TermType> move = getNextMove();

		notifyStopRunning();
		stepNum++;
		return move;
	}

	/**
	 * Returns the agent's next move by first updating and branching each hypergame and using these to calculate the
	 * move with the greatest probability of a good outcome
	 *
	 * @return A legal move
	 */
	public MoveInterface<TermType> getNextMove() {
		long startTime =  System.currentTimeMillis();
		HashSet<MoveInterface<TermType>> legalMoves = new HashSet<MoveInterface<TermType>>();
		HashSet<MoveInterface<TermType>> legalMovesInState = null;

		// If it is the first step, then create the first hypergame with the initial state
		if(stepNum == 0) {
			// Create first model to represent the empty state
			Model<TermType> model = new Model<TermType>();
			Collection<TermType> initialPercepts = perceptTracker.get(stepNum);
			StateInterface<TermType, ?> initialState = match.getGame().getInitialState();
			model.updateGameplayTracker(stepNum, initialPercepts, null, initialState, role, 1);

			hypergames.add(model);

			// Get legal moves from this model
			legalMoves = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role));
		} else {
			// For each model in the the current hypergames set, update it with a random joint action that matches player's last action and branch by the branching factor
			ArrayList<Model<TermType>> currentHypergames = new ArrayList<Model<TermType>>(hypergames);
			for (Model<TermType> model : currentHypergames) {
				// Save a copy of the model
				Model<TermType> cloneModel = new Model<TermType>(model);
				int previousActionPathHash = model.getPreviousActionPathHash();
				int currActionPathHash = model.getActionPathHash();
				JointMove<TermType> previousAction = model.getLastAction();

				// Forward the model
				int step = model.getActionPath().size();
				while(step < stepNum + 1) {
					step = forwardHypergame(model, step);
					if(step == 0) break;
				}
				// If the hypergame has gone all the way back to the initial state and there are no valid moves here, then remove it from the set of hypergames
				if(step == 0) {
					hypergames.remove(model);
					continue;
				}

				updateCurrentlyInUseMoves(model, currActionPathHash, previousActionPathHash, previousAction);

				// Get legal moves
				legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role));
				legalMoves.addAll(legalMovesInState);

				// Branch the clone of the model
				boolean keepBranching = true;
				for(int i = 0 ; i < numHyperBranches - 1; i++) {
					if(hypergames.size() < numHyperGames && keepBranching) {
						// Clone the model
						Model<TermType> newModel = new Model<TermType>(cloneModel);
						previousActionPathHash = newModel.getPreviousActionPathHash();
						currActionPathHash = newModel.getActionPathHash();
						previousAction = newModel.getLastAction();

						// Forward the new model
						step = newModel.getActionPath().size();
						while(step < stepNum + 1) {
							step = forwardHypergame(newModel, step);
							if(step == 0) break;
						}
						// If the hypergame has gone all the way back to the initial state and there are no valid moves here, then break and don't add it to the hyperset
						if(step == 0) {
							keepBranching = false;
							break;
						}

						// Add to hypergames set and get legal moves
						hypergames.add(newModel);

						// Keep track of moves in use
						updateCurrentlyInUseMoves(newModel, currActionPathHash, previousActionPathHash, previousAction);

						// Get legal moves
						legalMovesInState = new HashSet<MoveInterface<TermType>>(newModel.computeLegalMoves(role));
						legalMoves.addAll(legalMovesInState);
					} else break;
				}
			}
		}
		System.out.println("UPDATED STATE IMPRANDOM");

		//Calculate how long the update took
		long endTime =  System.currentTimeMillis();
		long updateTime = endTime - startTime;

		// Print all models
//		printHypergames();

		// Select a move
		startTime =  System.currentTimeMillis();
		MoveInterface<TermType> bestMove = randomMoveSelection(legalMoves);
		endTime =  System.currentTimeMillis();
		long selectTime = endTime - startTime;
		System.out.println("CHOSE MOVE IMPRANDOM");

		// Print move to file
		try {
			FileWriter myWriter = new FileWriter("matches/" + matchID + ".csv", true);
			myWriter.write(matchID + "," + gameName + "," + stepNum + "," + roleName + "," + name + "," + hypergames.size() + "," + numProbes + "," + updateTime + "," + selectTime + "," + bestMove + "\n");
			myWriter.close();
		} catch (IOException e) {
			System.err.println("An error occurred.");
			e.printStackTrace();
		}

		return bestMove;
	}

	/**
	 * Updates the currentlyInUseMoves hashmap that tracks the moves that should not be added to the hypergames set due
	 * to redundancy
	 *
	 * @param model - The model of the hypergame to update
	 * @param currActionPathHash - The current hash of the current action path to identify the action path as in-use
	 * @param previousActionPathHash - The hash of the current action path without the final move to be removed from in-use
	 * @param previousAction - The final move to be removed from the action path map
	 */
	public void updateCurrentlyInUseMoves(Model<TermType> model, int currActionPathHash, int previousActionPathHash, JointMove<TermType> previousAction){
		HashSet<JointMove<TermType>> inuse = new HashSet<JointMove<TermType>>();
		// First add the new move to the currently inuse
		if(currentlyInUseMoves.containsKey(currActionPathHash)){
			inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(currActionPathHash);
			inuse.add(model.getLastAction());
		} else {
			inuse.add(model.getLastAction());
			currentlyInUseMoves.put(currActionPathHash, inuse);
		}
		// Remove the previous state-action pair from inuse
		if(currentlyInUseMoves.containsKey(previousActionPathHash)) {
			inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(previousActionPathHash);
			inuse.remove(previousAction);
		}
	}

	/**
	 * Prints the details about each hypergame in the set of hypergames
	 */
	public void printHypergames() {
		// Print model info
		System.out.println("TRUE PERCEPTS:");
		System.out.println(perceptTracker.get(stepNum));
		System.out.println("MODELS:");
		for (int i = 0 ; i < hypergames.size() ; i++) {
			Model<TermType> mod = hypergames.get(i);
			System.out.println("\tModel Hash: " + mod.getActionPathHash());
			System.out.println("\tAction Path: ");
			for (JointMove<TermType> jointActionInPath : mod.getActionPath()) {
				System.out.println("\t\t" + jointActionInPath);
			}
			System.out.println("\tPercept Path: ");
			for (Collection<TermType> perceptsInPath : mod.getPerceptPath()) {
				System.out.println("\t\t" + perceptsInPath);
			}
			System.out.println("\tState Path: ");
			for (StateInterface<TermType, ?> statesInPath : mod.getStatePath()) {
				System.out.println("\t\t" + statesInPath);
			}
			System.out.println();
		}
		System.out.println("Num hypergames: " + hypergames.size());
	}

	/**
	 * Select a move amongst the possible moves randomly
	 *
	 * @param possibleMoves - A set of the possible moves
	 * @return The chosen move
	 */
	public MoveInterface<TermType> randomMoveSelection(HashSet<MoveInterface<TermType>> possibleMoves) {
		int rand = random.nextInt(possibleMoves.size());
		MoveInterface<TermType> chosenMove = null;
		int i = 0;
		for(MoveInterface<TermType> move : possibleMoves) {
			if(i == rand) {
				chosenMove = move;
			}
			i++;
		}
		return chosenMove;
	}

	/**
	 * Get the expected result of a move from a given state using monte carlo simulation
	 *
	 * @param state - The current state of the game
	 * @param move - The first move to be tried
	 * @param numProbes - The number of simulations to run
	 * @return The statistical expected result of a move
	 */
	public float simulateMove(StateInterface<TermType, ?> state, MoveInterface<TermType> move, int numProbes) {
		int expectedOutcome = 0;
		// Repeatedly select random joint moves until a terminal state is reached
		for (int i = 0; i < numProbes; i++) {
			StateInterface<TermType, ?> currState = state;
			JointMoveInterface<TermType> randJointMove;
			boolean isFirstMove = true;
			while(!currState.isTerminal()) {
				if(isFirstMove) {
					try {
						randJointMove = getRandomJointMove(currState, move);
					} catch(Exception e) {
						return 0;
					}
					isFirstMove = false;
				} else {
					randJointMove = getRandomJointMove(currState);
				}
				currState = currState.getSuccessor(randJointMove);
			}
			expectedOutcome += currState.getGoalValue(role);
		}
		return (float)expectedOutcome/(float)numProbes;
	}


	/**
	 * Forwards the hypergame by trying a random joint move such that:
	 * 		The action taken by the player is the same action that was taken in reality last round
	 * 		The action-path generated is not already in use
	 * 		The percepts generated by this action match the percepts actually seen at this step
	 *
	 * 	If these conditions are met, it will return (step + 1)
	 * 	If the percepts do not match, then it will mark the action as 'BAD' and return (step) to attempt a different action at the same state
	 * 	If no such actions are found, then it will mark this state as 'BAD' and will return (step - 1) to attempt a different action at the previous state
	 *
	 * @param model - The model to forward
	 * @param step - The current step of the model
	 * @return The step of the model
	 */
	public int forwardHypergame(Model<TermType> model, int step) {
		// Update the model using a random joint move
		StateInterface<TermType, ?> state = model.getCurrentState();
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, actionTracker.get(step - 1)));
		int numPossibleJointMoves = possibleJointMoves.size();
		cleanJointMoves(possibleJointMoves, model.getActionPathHash());
		JointMove<TermType> jointAction = null;
		int numCleanJointMoves = possibleJointMoves.size();
		if(numCleanJointMoves > 0) {
			int i = random.nextInt(numCleanJointMoves);
			jointAction = (JointMove<TermType>) possibleJointMoves.get(i);
		}

		// If there are no valid moves from this state, then backtrack and try again
		if (jointAction == null) {
			// Get move that got to this state and add to bad move set
			JointMove<TermType> lastAction = model.getLastAction();
			model.backtrack();

			// Add move to bad move set if there are no other active moves from this point
			updateBadMoveTracker(model.getActionPathHash(), lastAction);

			return step - 1;
		} else {
			// If a valid move could be found, update the state
			model.updateGameplayTracker(step, null, jointAction, state, role, numPossibleJointMoves);

			// Check if new model does not match expected percepts
			if (!model.getLatestExpectedPercepts().equals(perceptTracker.get(step))) {
				// Backtrack
				model.backtrack();

				// Add move to bad move set
				updateBadMoveTracker(model.getActionPathHash(), jointAction);

				// Try again
				return step;
			} else {
				// Else this is a valid move
				return step + 1;
			}
		}
	}

	/**
	 * Updates the bad move tracker at the action-path hash for the last action
	 *
	 * @param backtrackedModelHash - The action-path hash to add the bad move to
	 * @param badMove - The bad move to add to the tracker
	 */
	public void updateBadMoveTracker(int backtrackedModelHash, JointMove<TermType> badMove) {
		if (badMovesTracker.containsKey(backtrackedModelHash)) {
			Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
			badJointActions.add(badMove);
		} else {
			Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
			badJointActions.add(badMove);
			badMovesTracker.put(backtrackedModelHash, badJointActions);
		}
	}

	/**
	 * Gets a random joint move given the action matches the action taken in the last step
	 *
	 * @param state - The current state
	 * @param action - The action that the player will take
	 * @return A random joint move
	 */
	public JointMoveInterface<TermType> getRandomJointMove(StateInterface<TermType, ?> state, MoveInterface<TermType> action) {
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, action));
		JointMoveInterface<TermType> jointMove = null;
		if(possibleJointMoves.size() > 0) {
			int i = random.nextInt(possibleJointMoves.size());
			jointMove = possibleJointMoves.get(i);
		}
		return jointMove;
	}
	public JointMoveInterface<TermType> getRandomJointMove(StateInterface<TermType, ?> state) {
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(statesTracker.computeJointMoves((StateType) state));
		JointMoveInterface<TermType> jointMove = null;
		if(possibleJointMoves.size() > 0) {
			int i = random.nextInt(possibleJointMoves.size());
			jointMove = possibleJointMoves.get(i);
		}
		return jointMove;
	}

	/**
	 * computeJointMoves computes all joint moves possible from a state such that the action taken by the player is the action input
	 *
	 * @param state - The state to compute action from
	 * @param action - The action to be taken by the player
	 * @return A set of all possible moves
	 */
	public Collection<JointMoveInterface<TermType>> computeJointMoves(StateType state, MoveInterface<TermType> action) {
		// compute legal moves for all roles such that the action matches for the player's role
		HashMap<RoleInterface<TermType>, Collection<? extends MoveInterface<TermType>>> legalMovesMap = new HashMap<RoleInterface<TermType>, Collection<? extends MoveInterface<TermType>>>();
		for(RoleInterface<TermType> role: match.getGame().getOrderedRoles()) {
			if(role == this.role) {
				Collection<MoveInterface<TermType>> lastMoveMap = new ArrayList<MoveInterface<TermType>>();
				lastMoveMap.add(action);
				legalMovesMap.put(role, lastMoveMap);
			} else {
				legalMovesMap.put(role, state.getLegalMoves(role));
			}
		}
		// build the cross product
		final CrossProductMap<RoleInterface<TermType>, MoveInterface<TermType>> jointMovesMap = new CrossProductMap<RoleInterface<TermType>, MoveInterface<TermType>>(legalMovesMap);
		// wrap the elements of the cross product in JointMove<TermType>
		// the following is an on-the-fly collection that just refers to "jointMoves" above
		Collection<JointMoveInterface<TermType>> jointMoves = new AbstractCollection<JointMoveInterface<TermType>>(){
			@Override
			public Iterator<JointMoveInterface<TermType>> iterator() {
				final Iterator<Map<RoleInterface<TermType>, MoveInterface<TermType>>> iterator = jointMovesMap.iterator();
				return new Iterator<JointMoveInterface<TermType>>(){
					@Override public boolean hasNext() { return iterator.hasNext(); }

					@Override public JointMoveInterface<TermType> next() { return new JointMove<TermType>(match.getGame().getOrderedRoles(), iterator.next()); }

					@Override public void remove() { iterator.remove();	}
				};
			}

			@Override
			public int size() {
				return jointMovesMap.size();
			}
		};
		return jointMoves;
	}

	/**
	 * Clean a set of joint moves by removing all BAD move and in-use moves
	 *
	 * @param jointMoves - A list of joint moves
	 * @param actionPathHash - The action-path hash from which to consider which moves are invalid
	 */
	public void cleanJointMoves(ArrayList<JointMoveInterface<TermType>> jointMoves, int actionPathHash) {
		if(badMovesTracker.containsKey(actionPathHash)) {
			jointMoves.removeAll(badMovesTracker.get(actionPathHash));
		}
		if(currentlyInUseMoves.containsKey(actionPathHash)) {
			jointMoves.removeAll(currentlyInUseMoves.get(actionPathHash));
		}
	}
}
