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
import tud.gamecontroller.players.StatesTracker;
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
		Weighted based on number of nodes
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
	private Model<TermType> initialModel;
	private int initialModelHash;

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
		initialModel = new Model<TermType>();
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
			Collection<TermType> initialPercepts = perceptTracker.get(stepNum);
			StateInterface<TermType, ?> initialState = match.getGame().getInitialState();
			initialModel.updateGameplayTracker(stepNum, initialPercepts, null, initialState, role, 1);
			initialModelHash = initialModel.getActionPathHash();

			hypergames.add(initialModel);

			// Get legal moves from this model
			legalMoves = new HashSet<MoveInterface<TermType>>(initialModel.computeLegalMoves(role));
		} else {
			// For each model in the the current hypergames set, update it with a random joint action that matches player's last action and branch by the branching factor
			ArrayList<Model<TermType>> currentHypergames = new ArrayList<Model<TermType>>(hypergames);
			for (Model<TermType> model : currentHypergames) {
				// Save a copy of the model
				System.out.println();
				System.out.println();
				System.out.println("Model HASH: " + model.getActionPathHash());
				Model<TermType> cloneModel = new Model<TermType>(model);
				int previousActionPathHash = model.getPreviousActionPathHash();
				int currActionPathHash = model.getActionPathHash();
				JointMove<TermType> previousAction = model.getLastAction();

				// Forward the model
				int step = model.getActionPath().size();
				while(step < stepNum + 1) {
					step = forwardHypergame(model, step);
					if(model.isInitalModel() || model.getActionPathHash() == initialModelHash) break;
				}
				// If the hypergame has gone all the way back to the initial state then remove it
				if(model.isInitalModel() || model.getActionPathHash() == initialModelHash) {
					hypergames.remove(model);
					continue;
				}
				System.out.println("Chosen Move: " + model.getLastAction());

				// Keep track of moves in use
				HashSet<JointMove<TermType>> inuse = new HashSet<JointMove<TermType>>();
					// First add the new move to the currently inuse
				if(currentlyInUseMoves.containsKey(currActionPathHash)){
					inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(currActionPathHash);
					inuse.add(model.getLastAction());
				} else {
					inuse.add(model.getLastAction());
					currentlyInUseMoves.put(currActionPathHash, inuse);
				}
				System.out.println("Added move to inuse: ( " + currActionPathHash + " , " + model.getLastAction() + " )");
				// Remove the previous state-action pair from inuse
				if(currentlyInUseMoves.containsKey(previousActionPathHash)) {
					inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(previousActionPathHash);
					inuse.remove(previousAction);
				}
				System.out.println("Removed move from inuse: ( " + previousActionPathHash + " , " + previousAction + " )");

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

						System.out.println();
						System.out.println();
						System.out.println("Branch Model HASH: " + newModel.getActionPathHash());

						// Forward the new model
						step = newModel.getActionPath().size();
						while(step < stepNum + 1) {
							step = forwardHypergame(newModel, step);
							if(newModel.isInitalModel() || newModel.getActionPathHash() == initialModelHash) {
								System.out.println();
								System.out.println("IS INITIAL MODEL");
								break;
							}
						}
						if(newModel.isInitalModel() || newModel.getActionPathHash() == initialModelHash) {
							System.out.println("STOP BRANCHING");
							System.out.println();
							keepBranching = false;
							break;
						}
						System.out.println("Chosen Move: " + newModel.getLastAction());

						// Add to hypergames set and get legal moves
						hypergames.add(newModel);
						legalMovesInState = new HashSet<MoveInterface<TermType>>(newModel.computeLegalMoves(role));
						legalMoves.addAll(legalMovesInState);

						// Keep track of moves in use
						// First add the new move to the currently inuse
						if(currentlyInUseMoves.containsKey(currActionPathHash)){
							inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(currActionPathHash);
							inuse.add(newModel.getLastAction());
						} else {
							inuse.add(newModel.getLastAction());
							currentlyInUseMoves.put(currActionPathHash, inuse);
						}
						System.out.println("Added move to inuse: ( " + currActionPathHash + " , " + newModel.getLastAction() + " )");
						// Remove the previous state-action pair from inuse
						if(currentlyInUseMoves.containsKey(previousActionPathHash)) {
							inuse = (HashSet<JointMove<TermType>>) currentlyInUseMoves.get(previousActionPathHash);
							inuse.remove(previousAction);
						}
						System.out.println("Removed move from inuse: ( " + previousActionPathHash + " , " + previousAction + " )");
					} else break;
				}
			}
		}

		// Print all models
		printHypergames();

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
	 * @todo: Why even include the invChoiceFactorSum when all of the CFs will be divided by it
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
		System.out.println("Number of possible actions path for each model");
		for(Model<TermType> model : hypergames) {
			// Print some debugging info
			System.out.println("Model: " + model.getActionPathHash() + " ->" + model.getNumberOfPossibleActionsPath());

			// Calculate the choice factor based on the likelihood of this path being chosen
			choiceFactor = model.getNumberOfPossibleActions();
			choiceFactors.put(model.getActionPathHash(), choiceFactor);
			invChoiceFactorSum += (float)(1.0/(float)choiceFactor);
		}
		System.out.println();

		// Calculate the probability
		HashMap<Integer, Float> hyperProbs = new HashMap<Integer, Float>();
		float prob;
		System.out.println("Probabilities:");
		for(Model<TermType> model : hypergames) {
			choiceFactor = choiceFactors.get(model.getActionPathHash());
			prob = ((1/(float)choiceFactor)/invChoiceFactorSum);
			hyperProbs.put(model.getActionPathHash(), prob);
			System.out.println(model.getActionPathHash() + ": " + prob);
			System.out.println("\t inv choice factor: " + (1/(float)choiceFactor));
			System.out.println("\t inv choice factor sum: " + invChoiceFactorSum);
		}
		System.out.println();

		// Calculate expected move value for each hypergame
		System.out.println("Calculating move value for each hypergame:");
		int numProbes = 10;
		HashMap<Integer, Float> weightedExpectedValuePerMove = new HashMap<Integer, Float>();
		HashMap<Integer, MoveInterface<TermType>> moveHashMap = new HashMap<Integer, MoveInterface<TermType>>();
		for(Model<TermType> model : hypergames) {
			System.out.println(model.getActionPathHash());
			StateInterface<TermType, ?> currState = model.getCurrentState();
			for(MoveInterface<TermType> move : possibleMoves) {
				moveHashMap.put(move.hashCode(), move);
				System.out.println("\t" + move.toString());
				// Calculate the the expected value for each move using monte carlo simulation
				float expectedValue = simulateMove(currState, move, numProbes);
				System.out.println("\t\t" + "Expected Value: " + expectedValue);

				// Calculate the weighted expected value for each move
				float weightedExpectedValue = expectedValue * hyperProbs.get(model.getActionPathHash());
				System.out.println("\t\t" + "Weighted Expected Value: " + weightedExpectedValue);

				// Add expected value to hashmap
				if(!weightedExpectedValuePerMove.containsKey(move.hashCode())) {
					weightedExpectedValuePerMove.put(move.hashCode(), weightedExpectedValue);
				} else {
					float prevWeightedExpectedValue = weightedExpectedValuePerMove.get(move.hashCode());
					weightedExpectedValuePerMove.replace(move.hashCode(), prevWeightedExpectedValue + weightedExpectedValue);
				}
			}
			System.out.println();
		}
		System.out.println();
		System.out.println();

		// Return the move with the greatest weighted expected value
		Iterator<HashMap.Entry<Integer, Float>> it = weightedExpectedValuePerMove.entrySet().iterator();
		float maxVal = Float.MIN_VALUE;
		MoveInterface<TermType> bestMove = null;
		while(it.hasNext()){
			HashMap.Entry<Integer, Float> mapElement = (HashMap.Entry<Integer, Float>)it.next();
			float val = mapElement.getValue();
			if(val > maxVal) {
				bestMove = moveHashMap.get(mapElement.getKey());
				maxVal = val;
			}
			System.out.println("Move " + moveHashMap.get(mapElement.getKey()) + " has expected value of: " + mapElement.getValue());
		}

		return bestMove;
	}

	/**
	 * Get the expected result of a move using monte carlo simulation
	 *
	 * @param move
	 * @return
	 */
	public float simulateMove(StateInterface<TermType, ?> state, MoveInterface<TermType> move, int numProbes) {
		int expectedOutcome = 0;
		for (int i = 0; i < numProbes; i++) {
			// Repeatedly select random joint moves until a terminal state is reached
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
	 * @todo: This only keeps 1 node in memory so it won't overflow the stack, but it may take O(b^(stepNum)) time worst-case, which is infeasible
	 *
	 * @param model
	 * @param step
	 */
	public int forwardHypergame(Model<TermType> model, int step) {
//		System.out.println("UPDATING HYPERGAME");
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
		System.out.println("Num possible moves: " + numPossibleJointMoves);
		System.out.println("Num possible clean moves: " + numCleanJointMoves);

		// If there are no valid moves from this state, then backtrack and try again
		if (jointAction == null) {
//			System.out.println("FOUND A DEAD END: "  + step);
			// Get move that got to this state and add to bad move set
			JointMove<TermType> lastAction = model.getLastAction();
			model.backtrack();

			// Add move to bad move set if there are no other active moves from this point
			int backtrackedModelHash = model.getActionPathHash();
			System.out.println("No Valid Moves:");
			if(!currentlyInUseMoves.containsKey(backtrackedModelHash) || (currentlyInUseMoves.get(backtrackedModelHash).size() == 0)) {
				if (badMovesTracker.containsKey(backtrackedModelHash)) {
					Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
					badJointActions.add(lastAction);
				} else {
					Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
					badJointActions.add(lastAction);
					badMovesTracker.put(backtrackedModelHash, badJointActions);
				}
				System.out.println("Added move to bad move tracker: ( " + backtrackedModelHash + " , " + lastAction + " )");
			} else {
				System.out.println("NOT added move to bad move tracker");
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
				System.out.println("Tried move but didn't match percepts:");
				System.out.println("Considering move: " + jointAction);
				if (badMovesTracker.containsKey(backtrackedModelHash)) {
					Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
					badJointActions.add(jointAction);
				} else {
					Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
					badJointActions.add(jointAction);
					badMovesTracker.put(backtrackedModelHash, badJointActions);
				}
				System.out.println("Added move to bad move tracker: ( " + backtrackedModelHash + " , " + jointAction + " )");

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
	 * @param state
	 * @return
	 */
	public Collection<JointMoveInterface<TermType>> computeJointMoves(StateType state, MoveInterface<TermType> action) {
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
		return jointMoves;
	}

	public void cleanJointMoves(ArrayList<JointMoveInterface<TermType>> jointMoves, int actionPathHash) {
		//		System.out.println("legal joint moves size: " + jointMoves.size());
		if(badMovesTracker.containsKey(actionPathHash)) {
//			System.out.println("legal joint moves before size: " + jointMoves.size());
			jointMoves.removeAll(badMovesTracker.get(actionPathHash));
//			System.out.println("legal joint moves after size: " + jointMoves.size());
			System.out.println("For Hash: " + actionPathHash);
			System.out.println("Bad moves: " + badMovesTracker.get(actionPathHash));
		} else {
			System.out.println("For Hash: " + actionPathHash);
			System.out.println("Bad moves: NONE");
		}
		if(currentlyInUseMoves.containsKey(actionPathHash)) {
			System.out.println("Inuse moves: " + currentlyInUseMoves.get(actionPathHash));
			jointMoves.removeAll(currentlyInUseMoves.get(actionPathHash));
		} else {
			System.out.println("Inuse moves: NONE");
		}
	}
}
