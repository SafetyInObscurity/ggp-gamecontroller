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
		Random
		@todo: add a MCS player to this for each hypergame
 */

/**
 * HyperPlayer is an agent that can play imperfect information and non-deterministic two player extensive-form games
 * with perfect recall by holding many 'hypergames' as models that may represent the true state of the game in perfect
 * information representation. It then calculates the best move for each hypergame weighted against the likelihood of
 * it representing the true state of the game and returns the moves with the greatest weighted expected payoff.
 * Implements the algorithm descrived in Michael Schofield, Timothy Cerexhe and Michael Thielscher's HyperPlay paper
 * @see "https://staff.cdms.westernsydney.edu.au/~dongmo/GTLW/Michael_Tim.pdf"
 *
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
	private int numHyperBranches = 20;
	private HashMap<Integer, Collection<JointMove<TermType>>> currentlyInUseMoves;

	private int stepNum; // Tracks the steps taken
	private HashMap<Integer, MoveInterface<TermType>> actionTracker; // Tracks the action taken at each step by the player (from 0)
	private HashMap<Integer, Collection<TermType>> perceptTracker; // Tracks the percepts seen at each step by the player (from 0)
	private HashMap<Integer, Collection<JointMove<TermType>>> badMovesTracker; // Tracks the invalid moves from each perfect-information state
	private ArrayList<Model<TermType>> hypergames; // Holds a set of possible models for the hypergame

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
		badMovesTracker = new HashMap<Integer, Collection<JointMove<TermType>>>();
		currentlyInUseMoves = new HashMap<Integer, Collection<JointMove<TermType>>>();
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
	 * Returns the agent's next move
	 *
	 * @return A legal move
	 */
	public MoveInterface<TermType> getNextMove() {
		HashSet<MoveInterface<TermType>> legalMoves = new HashSet<MoveInterface<TermType>>();
		HashSet<MoveInterface<TermType>> legalMovesInState = null;
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

				// Forward the model
				int step = model.getActionPath().size();
				while(step < stepNum + 1) {
					step = forwardHypergame(model, step);
				}

				// Keep track of moves in use
				if(currentlyInUseMoves.containsKey(model.getActionPathHash())){
					HashSet<JointMove<TermType>> inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(model.getActionPathHash());
					inuse.add(model.getLastAction());
				} else {
					HashSet<JointMove<TermType>> inuse = new HashSet<JointMove<TermType>>();
					inuse.add(model.getLastAction());
					currentlyInUseMoves.put(model.getActionPathHash(), inuse);
				}

				// Get legal moves
				legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role));
				legalMoves.addAll(legalMovesInState);

				// Branch the clone of the model
				for(int i = 0 ; i < numHyperBranches - 1; i++) {
					if(hypergames.size() < numHyperGames) {
						// Clone the model
						Model<TermType> newModel = new Model<TermType>(cloneModel);

						// Forward the new model
						step = newModel.getActionPath().size();
						while(step < stepNum + 1) {
							step = forwardHypergame(newModel, step);
						}

						// Add to hypergames set and get legal moves
						hypergames.add(newModel);
						legalMovesInState = new HashSet<MoveInterface<TermType>>(newModel.computeLegalMoves(role));
						legalMoves.addAll(legalMovesInState);

						// Keep track of moves in use
						if(currentlyInUseMoves.containsKey(model.getActionPathHash())){
							HashSet<JointMove<TermType>> inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(model.getActionPathHash());
							inuse.add(model.getLastAction());
						} else {
							HashSet<JointMove<TermType>> inuse = new HashSet<JointMove<TermType>>();
							inuse.add(model.getLastAction());
							currentlyInUseMoves.put(model.getActionPathHash(), inuse);
						}
					} else break;
				}
			}
		}

		// Print all models
//		printHypergames();

		// Select a move
		return moveSelection(legalMoves);
	}

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
	 * moveSelection selects a move from the list of possibleMoves
	 * It does this by considering:
	 * 		The expected value for that move given a hypergame
	 * 		The probability that that hypergame is the true game
	 *
	 * 	Assumptions:
	 * 		P(HG | Percepts) ~ P(HG) : HG = Hypergame is the true Game
	 * 		The opponent's move preferences will be approximated as being uniformly distributed over all possible moves
	 *
	 * 	The P(HG) = 1/ChoiceFactor[i] / SUM(1/ChoiceFactor[i])
	 * 	The expected payoff will be calculated using MCS
	 *
	 * 	Therefore, the weighted expected value of a move j is the sum of all (expectations for that move multiplied by the probability of that hypergame) in all hypergames
	 *
	 * @param possibleMoves
	 * @return
	 */
	public MoveInterface<TermType> moveSelection(HashSet<MoveInterface<TermType>> possibleMoves) {
		// Calculate P(HG)
			// Calculate choice factors
		HashMap<Integer, Integer> choiceFactors = new HashMap<Integer, Integer>();
		int choiceFactor;
		float invChoiceFactorSum = 0;
		for(Model<TermType> model : hypergames) {
			choiceFactor = model.getNumberOfPossibleActions();
			choiceFactors.put(model.getActionPathHash(), choiceFactor);
			invChoiceFactorSum += (float)(1.0/(float)choiceFactor);
		}
			// Calculate the probability
		HashMap<Integer, Float> hyperProbs = new HashMap<Integer, Float>();
		float prob;
		System.out.println("Probabilities:");
		for(Model<TermType> model : hypergames) {
			choiceFactor = choiceFactors.get(model.getActionPathHash());
			prob = ((float)choiceFactor/invChoiceFactorSum);
			hyperProbs.put(model.getActionPathHash(), prob);
			System.out.println(model.getActionPathHash() + ": " + prob);
		}
		System.out.println();

		// Calculate expected move value
		System.out.println("Possible Moves:");
		for(MoveInterface<TermType> move : possibleMoves) {
			System.out.println(move.toString());
		}
		System.out.println();

		// Calculate weighted expected move value

		// Return the move with the greatest weighted expected value


		return randomMoveSelection(possibleMoves);
	}

	/**
	 * @todo: This only keeps 1 node in memory so it won't overflow the stack, but it may take O(b^(stepNum)) time worst-case, which is infeasible
	 *
	 * @param model
	 * @param step
	 */
	public int forwardHypergame(Model<TermType> model, int step) {
//		System.out.println("UPDATING HYPERGAME");
		// Update the model using a random joint move
		StateInterface<TermType, ?> state = model.getCurrentState();
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, actionTracker.get(step - 1), model.getActionPathHash()));
		JointMove<TermType> jointAction = null;
		int numPossibleJointMoves = possibleJointMoves.size();
		if(numPossibleJointMoves > 0) {
			int i = random.nextInt(numPossibleJointMoves);
			jointAction = (JointMove<TermType>) possibleJointMoves.get(i);
		}

		// If there are no valid moves from this state, then backtrack and try again
		if (jointAction == null) {
//			System.out.println("FOUND A DEAD END: "  + step);
			// Get move that got to this state and add to bad move set
			JointMove<TermType> lastAction = model.getLastAction();
			model.backtrack();

			// Add move to bad move set
			int backtrackedModelHash = model.getActionPathHash();
			if (badMovesTracker.containsKey(backtrackedModelHash)) {
				Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
				badJointActions.add(lastAction);
			} else {
				Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
				badJointActions.add(lastAction);
				badMovesTracker.put(backtrackedModelHash, badJointActions);
			}

			return step - 1;
		} else {
			// Run the update
			model.updateGameplayTracker(step, null, jointAction, state, role, numPossibleJointMoves);

			// Check if new model matches expected percepts, else backtrack
			if (!model.getLatestExpectedPercepts().equals(perceptTracker.get(step))) {
//				System.out.println("DOES NOT MATCH PERCEPTS: "  + step);
				// Backtrack
				model.backtrack();

				// Add move to bad move set
				int backtrackedModelHash = model.getActionPathHash();
				if (badMovesTracker.containsKey(backtrackedModelHash)) {
					Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
					badJointActions.add(jointAction);
				} else {
					Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
					badJointActions.add(jointAction);
					badMovesTracker.put(backtrackedModelHash, badJointActions);
				}

				// Try again
				return step;
			} else {
				return step + 1;
			}
		}
	}

	/**
	 * Gets a random joint move given the action matches the action taken in the last step
	 *
	 * @param state
	 * @return
	 */
	public JointMoveInterface<TermType> getRandomJointMove(StateInterface<TermType, ?> state, int actionPathHash, int step) {
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, actionTracker.get(step - 1), actionPathHash));
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
	 * @param state
	 * @return
	 */
	public Collection<JointMoveInterface<TermType>> computeJointMoves(StateType state, MoveInterface<TermType> action, int actionPathHash) {
		// compute legal moves for all roles
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
//		System.out.println("legal joint moves size: " + jointMoves.size());
		ArrayList<JointMoveInterface<TermType>> cleanedJointMoves = new ArrayList<JointMoveInterface<TermType>>(jointMoves);
		if(badMovesTracker.containsKey(actionPathHash)) {
//			System.out.println("legal joint moves before size: " + jointMoves.size());
			cleanedJointMoves.removeAll(badMovesTracker.get(actionPathHash));
//			System.out.println("legal joint moves after size: " + jointMoves.size());
		}
		if(currentlyInUseMoves.containsKey(actionPathHash)) {
			cleanedJointMoves.removeAll(currentlyInUseMoves.get(actionPathHash));
		}
		return cleanedJointMoves;
	}
}
