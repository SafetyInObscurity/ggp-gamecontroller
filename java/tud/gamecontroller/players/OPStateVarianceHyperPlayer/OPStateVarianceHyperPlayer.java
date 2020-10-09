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

package tud.gamecontroller.players.OPStateVarianceHyperPlayer;

import tud.auxiliary.CrossProductMap;
import tud.gamecontroller.ConnectionEstablishedNotifier;
import tud.gamecontroller.GDLVersion;
import tud.gamecontroller.game.*;
import tud.gamecontroller.game.impl.JointMove;
import tud.gamecontroller.players.LocalPlayer;
import tud.gamecontroller.term.TermInterface;

import java.io.*;
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
					The move chosen isn't the same move as was chosen for a previous update/branch from the initial state
				Ensure percepts match, then add to hypergame set up to limit
			For all updates and branches, add the legal moves to a set
		Select a move from the legal moves set

	Move Selection:
		Weighted based on likelihood of each hypergame using uniform opponent modelling
 */

/**
 * HyperPlayer is an agent that can play imperfect information and non-deterministic two player extensive-form games
 * with perfect recall by holding many 'hypergames' as models that may represent the true state of the game in perfect
 * information representation. It then calculates the best move for each hypergame weighted against the likelihood of
 * it representing the true state of the game and returns the moves with the greatest weighted expected payoff.
 *
 * This variant uses a central datastructure - LikelihoodTree - to track the likelihood of each state.
 *
 * This variant
 *
 * Implements the algorithm described in Michael Schofield, Timothy Cerexhe and Michael Thielscher's HyperPlay paper
 * with some alteration to the backtracking
 * @see "https://staff.cdms.westernsydney.edu.au/~dongmo/GTLW/Michael_Tim.pdf"
 *
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class OPStateVarianceHyperPlayer<
	TermType extends TermInterface,
	StateType extends StateInterface<TermType, ? extends StateType>> extends LocalPlayer<TermType, StateType>  {

	// Logging variables
	private String matchID;
	private String gameName;
	private String roleName;

	// Hyperplay variables
	private Random random;
	private int numHyperGames = 4; // The maximum number of hypergames allowable
	private int numHyperBranches = 4; // The amount of branches allowed
	private HashMap<Integer, Collection<JointMove<TermType>>> currentlyInUseMoves; // Tracks all of the moves that are currently in use from each state
	private int depth; // Tracks the number of simulations run @todo: name better
	private int maxNumProbes = 4; // @todo: probably remove later
	private int stepNum; // Tracks the steps taken
	private int nextStepNum; // Tracks the steps taken
	private HashMap<Integer, MoveInterface<TermType>> actionTracker; // Tracks the action actually taken at each step by the player (from 0)
	private HashMap<Integer, MoveInterface<TermType>> expectedActionTracker; // Tracks the move taken by the player at each step (from 0)
	private HashMap<Integer, Collection<TermType>> perceptTracker; // Tracks the percepts seen at each step by the player (from 0)
	private HashMap<Integer, Collection<JointMove<TermType>>> badMovesTracker; // Tracks the invalid moves from each perfect-information state
	private ArrayList<Model<TermType>> hypergames; // Holds a set of possible models for the hypergame
	private StateInterface<TermType, ?> initialState; // Holds the initial state
	private LikelihoodTree<TermType> likelihoodTree;
	private int backtrackingDepth = 1;
	private RoleInterface<TermType> opponentRole;
	private HashSet<Integer> likelihoodTreeExpansionTracker;
	private HashMap<Integer, PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>>> moveSelectOrderMap;
	private HashMap<Integer, ArrayList<Tuple<Double, JointMoveInterface<TermType>>>> moveSelectMap;
	private double likelihoodPowerFactor = 1.0;
	private boolean shouldBranch = false;
	private int numTimesMovesSimulated = 0;
	private int numTimesHypergameForward = 0;
	private int numOPProbes = 8; // The number of probes used for opponent modelling -> NOT USED FOR THIS VARIANT SINCE IT HAS ACCESS TO THE TRUE DISTRIBUTION
	private int invPlaytimeFactor = 10;

	private HashMap<Integer, MoveInterface<TermType>> moveForStepBlacklist; // Any valid hypergame at this step must NOT allow the move contained here
	private HashMap<Integer, MoveInterface<TermType>> moveForStepWhitelist; // Any valid hypergame at this step MUST allow the move contained here

	private long timeLimit; // The total amount of time that can be
	private long stateUpdateTimeLimit; // The total amount of time that can be used to update the state
	private long startTime;
	private long timeexpired;
	private static final long PREFERRED_PLAY_BUFFER = 1000; // 1 second buffer before end of game to select optimal move

	public OPStateVarianceHyperPlayer(String name, GDLVersion gdlVersion) {
		super(name, gdlVersion);
		random = new Random();

		// Override settings with config file
		try {
			BufferedReader csvReader = new BufferedReader(new FileReader("java/tud/gamecontroller/players/agentConfig/" + this.getName() + ".config"));
			String row;
			while ((row = csvReader.readLine()) != null) {
				String[] data = row.split(":");
				if(data[0].equals("numHyperGames")) numHyperGames = Integer.parseInt(data[1]);
				else if(data[0].equals("numHyperBranches")) numHyperBranches = Integer.parseInt(data[1]);
				else if(data[0].equals("maxNumProbes")) maxNumProbes = Integer.parseInt(data[1]);
				else if(data[0].equals("numOPProbes")) numOPProbes = Integer.parseInt(data[1]);
				else if(data[0].equals("backtrackingDepth")) backtrackingDepth = Integer.parseInt(data[1]);
				else if(data[0].equals("likelihoodPowerFactor")) likelihoodPowerFactor = Double.parseDouble(data[1]);
				else if(data[0].equals("shouldBranch")) shouldBranch = Boolean.parseBoolean(data[1]);
				else if(data[0].equals("invPlaytimeFactor")) invPlaytimeFactor = Integer.parseInt(data[1]);
			}
			csvReader.close();
		}  catch (IOException e) {
			System.out.println(this.getName() + ": NO CONFIG FILE FOUND");
		}
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
		expectedActionTracker = new HashMap<Integer, MoveInterface<TermType>>();
		perceptTracker = new HashMap<Integer, Collection<TermType>>();
		badMovesTracker = new HashMap<Integer, Collection<JointMove<TermType>>>();
		currentlyInUseMoves = new HashMap<Integer, Collection<JointMove<TermType>>>();
		hypergames = new ArrayList<Model<TermType>>();
		likelihoodTree = new LikelihoodTree<TermType>(0);
		stepNum = 0;
		nextStepNum = 0;
		timeLimit = (this.match.getPlayclock()*1000 - PREFERRED_PLAY_BUFFER);
		stateUpdateTimeLimit = (this.match.getPlayclock()*1000)/invPlaytimeFactor; // Can use 10% of the playclock to update the state
		moveSelectOrderMap = new HashMap<Integer, PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>>>();
		moveSelectMap = new HashMap<Integer, ArrayList<Tuple<Double, JointMoveInterface<TermType>>>>();

		moveForStepBlacklist = new HashMap<Integer, MoveInterface<TermType>>();
		moveForStepWhitelist = new HashMap<Integer, MoveInterface<TermType>>();

		likelihoodTreeExpansionTracker = new  HashSet<Integer>();
		for(RoleInterface<TermType> currRole: match.getGame().getOrderedRoles()) {
			if(currRole != this.role) {
				opponentRole = currRole;
			}
		}

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
		nextStepNum++;
		notifyStartRunning();
		notifier.connectionEstablished();
		numTimesMovesSimulated = 0;
		numTimesHypergameForward = 0;
		if(stepNum > 0) {
			if(lastMoveTimeout) { // If the player timed out last turn, update the stepnum and clear currentlyInUseMoves
				if(stepNum + 1 < nextStepNum) {
					stepNum++;
				}
				currentlyInUseMoves.clear();
				expectedActionTracker.put(stepNum - 1, null);
				System.out.println("*************************************************************************");
				System.out.println("*************************************************************************");
				System.out.println("*********************** TIMED OUT! LAST TURN ****************************");
				System.out.println("*************************************************************************");
				System.out.println("*************************************************************************");
			}
			perceptTracker.put(stepNum, (Collection<TermType>) seesTerms); // Puts the percepts in the map at the current step
			actionTracker.put(stepNum - 1, (MoveInterface<TermType>) priorMove); // Note: This won't get the final move made
			moveForStepWhitelist.put(stepNum - 1, (MoveInterface<TermType>) priorMove);
		}
		MoveInterface<TermType> move = getNextMove();

		// Add move to expectations
		expectedActionTracker.put(stepNum, move);

		notifyStopRunning();
		stepNum++;
		lastMoveTimeout = false;
		return move;
	}

	/**
	 * Returns the agent's next move by first updating and branching each hypergame and using these to calculate the
	 * move with the greatest probability of a good outcome
	 *
	 * @return A legal move
	 */
	public MoveInterface<TermType> getNextMove() {
		startTime =  System.currentTimeMillis();
		timeexpired = 0;
		boolean wasIllegal = false;

		HashSet<MoveInterface<TermType>> legalMoves = new HashSet<MoveInterface<TermType>>();
		HashSet<MoveInterface<TermType>> legalMovesInState = null;

		// If it is the first step, then create the first hypergame with the initial state
		double choiceFactor;
		if(stepNum == 0) {
			// Create first model to represent the empty state
			Model<TermType> model = new Model<TermType>();
			Collection<TermType> initialPercepts = perceptTracker.get(stepNum);
			initialState = match.getGame().getInitialState();
			model.updateGameplayTracker(stepNum, initialPercepts, null, initialState, role, 1);
			likelihoodTree = new LikelihoodTree<TermType>(model.getActionPathHash());
			likelihoodTree.getRoot().setValue(100.0);

			hypergames.add(model);

			// Get legal moves from this model
			legalMoves = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role, match));
			model.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMoves));
		} else {
			ArrayList<Model<TermType>> currentHypergames = new ArrayList<Model<TermType>>(hypergames);
			// Check if the move made last round actually matches the move made
			if(expectedActionTracker.get(stepNum - 1) != null && !expectedActionTracker.get(stepNum - 1).equals(actionTracker.get(stepNum - 1))) {
				wasIllegal = true;
//				System.out.println("Expected to take action " + expectedActionTracker.get(stepNum - 1) + " but actually took action " + actionTracker.get(stepNum - 1));
				moveForStepBlacklist.put(stepNum - 1, expectedActionTracker.get(stepNum - 1));
			}

				// Print to verify
//				System.out.println();
//				System.out.println("moveForStepBlacklist.get(stepNum - 1): " + (moveForStepBlacklist.get(stepNum - 1)));
//				System.out.println("moveForStepBlacklist: " + (moveForStepBlacklist));
//				System.out.println("moveForStepWhitelist.get(stepNum - 1): " + (moveForStepWhitelist.get(stepNum - 1)));
//				System.out.println("moveForStepWhitelist: " + (moveForStepWhitelist));
//				System.out.println();

				// @todo: Wrap with a method
				for (Model<TermType> model : currentHypergames) {
					HashSet<MoveInterface<TermType>> possibleMoves = model.getPossibleMovesAtStep(stepNum - 1);

//					System.out.println("model.getActionPathHash(): " + model.getActionPathHash());
//					System.out.println("model.getPossibleMovesAtStep(): " + model.getPossibleMovesAtStep());
//					System.out.println("model.getPossibleMovesAtStep(stepNum - 1): " + possibleMoves);
//					System.out.println();

					// Find all hypergames that allowed that move and remove them
					if(possibleMoves.contains(moveForStepBlacklist.get(stepNum - 1))) {
						System.out.println("Removed model " + model.getActionPathHash() + " because contained blacklisted move");
						// Update path @todo: Should this be done?
//						Node node = likelihoodTree.getNode(model.getActionPathHashPath());
//						if(node != null) {
//							Node parent = node.getParent();
//							node.setValue(0.0);
//
////							System.out.println("before");
////							System.out.println(likelihoodTree.toString());
//
//							likelihoodTree.updateRelLikelihood(parent);
//
////							System.out.println("after");
////							System.out.println(likelihoodTree.toString());
//
//						}
//						// Backtrack & add to bad move tracker
//						model.backtrack();
//
////						System.out.println("before");
////						System.out.println(badMovesTracker);
//
//						updateBadMoveTracker(model.getActionPathHash(), model.getLastAction(), model.getActionPathHashPath());

//						System.out.println("after");
//						System.out.println(badMovesTracker);

						hypergames.remove(model);
					}
					// Find all hypergames that didn't allow the true move used and remove them
					 else if(!possibleMoves.contains(moveForStepWhitelist.get(stepNum - 1))) {
						System.out.println("Removed model " + model.getActionPathHash() + " because did not contain whitelisted move");
						// Update path
//						Node node = likelihoodTree.getNode(model.getActionPathHashPath());
//						if(node != null) {
//							Node parent = node.getParent();
//							node.setValue(0.0);
//
////							System.out.println("before");
////							System.out.println(likelihoodTree.toString());
//
//							likelihoodTree.updateRelLikelihood(parent);
//
////							System.out.println("after");
////							System.out.println(likelihoodTree.toString());
//
//						}
//						// Backtrack & add to bad move tracker
//						model.backtrack();
//
////						System.out.println("before");
////						System.out.println(badMovesTracker);
//
//						updateBadMoveTracker(model.getActionPathHash(), model.getLastAction(), model.getActionPathHashPath());

//						System.out.println("after");
//						System.out.println(badMovesTracker);

						hypergames.remove(model);
					}
//					 else if(model.getLastProb() == 0.0) { // Remove if no chance of it being the model from last turn
//					 	hypergames.remove(model);
//					}
				}
				// @todo: Shouldn't I also add these as bad moves? Probably not, since it's already covered by a few checks so as long as it's sufficiently resourced, there will be no advantage
				System.out.println("Removed " + (currentHypergames.size() - hypergames.size()) + " out of " + currentHypergames.size() + " hypergames");

			// Search for hypergames if there are none left
			while(hypergames.size() == 0) {
				System.out.println(this.getName() + ": Trying to find another path");
				// Create first model to represent the empty state
				Model<TermType> model = new Model<TermType>();
				Collection<TermType> initialPercepts = perceptTracker.get(0);
				model.updateGameplayTracker(0, initialPercepts, null, initialState, role, 1);

				int step = 1;
				int maxStep = step;
				int i = 0;
				int j = 1;
//			System.out.println("stepNum: " + stepNum);
				while(step < stepNum + 1) {
//				System.out.println("\tRan " + i + " times on step " + j);
					i++;
					step = forwardHypergame(model, step , true);
					if(step < maxStep) break;
					if(step > maxStep) {
						i = 0;
						j++;
					}
					maxStep = Math.max(step, maxStep);
				}
				if(step < maxStep - 1) continue;

				hypergames.add(model);

				// Get legal moves from this model
				legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role, match));
				model.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMovesInState));
				legalMoves.addAll(legalMovesInState);
			}

			// For each model in the the current hypergames set, update it with a random joint action that matches player's last action and branch by the branching factor
			currentHypergames = new ArrayList<Model<TermType>>(hypergames);
			for (Model<TermType> model : currentHypergames) {

//				System.out.println("UPDATING");
				// Save a copy of the model
				Model<TermType> cloneModel = new Model<TermType>(model);
				int previousActionPathHash = model.getPreviousActionPathHash();
				int currActionPathHash = model.getActionPathHash();
				JointMove<TermType> previousAction = model.getLastAction();

				// Forward the model
				int step = model.getActionPath().size();
				while(step < stepNum + 1) {
//					System.out.println("forwarding update");
					step = forwardHypergame(model, step, false);
//					System.out.println(step);
					if(step < stepNum - backtrackingDepth || step == 0) break;
				}
				// If the hypergame has gone through all possible updates from the current state, then remove it from the set of hypergames
				/* This can be done without checking if future states are in use since this is updating the state, rather than branching
				 	Therefore: No states can be beyond this one from the same node
				 */
				if(step < stepNum - backtrackingDepth || step == 0) {
					// Add state to bad move tracker
//					if(step > 1) {
//						updateBadMoveTracker(model.getPreviousActionPathHash(), model.getLastAction(), model.getActionPathHashPath());
//					}

					// Remove model
					hypergames.remove(model);
					continue;
				}

				// Keep track of moves in use
				// @todo: wrap with a method
				if(currentlyInUseMoves.containsKey(model.getPreviousActionPathHash())) {
					Collection<JointMove<TermType>> inUseMoveSet = currentlyInUseMoves.get(model.getPreviousActionPathHash());
					inUseMoveSet.add(model.getLastAction());
				} else {
					Collection<JointMove<TermType>> inUseMoveSet = new HashSet<JointMove<TermType>>();
					inUseMoveSet.add(model.getLastAction());
					currentlyInUseMoves.put(model.getPreviousActionPathHash(), inUseMoveSet);
				}

				// Remove if 0Porbability
				// @todo: This will be done only if there is more than 1 hypergames [to handle case where this is the only possible update]
				choiceFactor = likelihoodTree.getRelativeLikelihood(model.getActionPathHashPath());
				if(choiceFactor <= 0 && hypergames.size() > 1) {
					System.out.println("UPDATE CHOICE FACTOR < 0.0 and > 1 remaining");
					hypergames.remove(model);
					continue;
				}

				// Get legal moves
				legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role, match));
				model.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMovesInState));
				legalMoves.addAll(legalMovesInState);

				// Branch the clone of the model
				boolean keepBranching = shouldBranch;
				for(int i = 0 ; i < numHyperBranches - 1; i++) {
					if(hypergames.size() < numHyperGames && keepBranching) {
//						System.out.println("BRANCHING");

						// Clone the model
						Model<TermType> newModel = new Model<TermType>(cloneModel);
						previousActionPathHash = newModel.getPreviousActionPathHash();
						currActionPathHash = newModel.getActionPathHash();
						previousAction = newModel.getLastAction();

						// Forward the new model
						step = newModel.getActionPath().size();
						while(step < stepNum + 1) {
//							System.out.println("forwarding branch");
							step = forwardHypergame(newModel, step, false);
//							System.out.println(step);
							if(step < stepNum - backtrackingDepth || step == 0) break;
						}
						// If the hypergame has gone through all possible updates from the current state, then break and don't add it to the hyperset
						/* If this occurs on a branch then there must be a successful state after the current state, but not enough to branch
							Therefore no need to discard the current state yet
						 */
						if(step < stepNum - backtrackingDepth || step == 0) {
							keepBranching = false;
							break;
						}

						// Keep track of moves in use
						if(currentlyInUseMoves.containsKey(newModel.getPreviousActionPathHash())) {
							Collection<JointMove<TermType>> inUseMoveSet = currentlyInUseMoves.get(newModel.getPreviousActionPathHash());
							inUseMoveSet.add(newModel.getLastAction());
						} else {
							Collection<JointMove<TermType>> inUseMoveSet = new HashSet<JointMove<TermType>>();
							inUseMoveSet.add(newModel.getLastAction());
							currentlyInUseMoves.put(newModel.getPreviousActionPathHash(), inUseMoveSet);
						}

						// @todo: This will be done only if there is more than 0 hypergames [since this is optional]
						choiceFactor = likelihoodTree.getRelativeLikelihood(model.getActionPathHashPath());
						if(choiceFactor <= 0 && hypergames.size() > 0) {
							System.out.println("UPDATE CHOICE FACTOR < 0.0 and > 0 remaining");
							hypergames.remove(model);
							continue;
						}

						// Add to hypergames set and get legal moves
						hypergames.add(newModel);

						// Get legal moves
						legalMovesInState = new HashSet<MoveInterface<TermType>>(newModel.computeLegalMoves(role, match));
						newModel.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMovesInState));
						legalMoves.addAll(legalMovesInState);
					} else break;
				}
			}
		}

		// Flush all in-use moves since they're only used for the update
//		System.out.println();
//		System.out.println("currentlyInUseMoves before clearing: " + currentlyInUseMoves);
//		System.out.println();

//		System.out.println("FINISHED UPDATING/BRANCHING STATE");

		// If no hypergames left, then run until one exists
		System.out.println(this.getName() + ": Number of hypergames after updating: " + hypergames.size());
		if(stepNum > 0) {
			while (canSearchMore()) {
//		while(hypergames.size() == 0) {
				System.out.println(this.getName() + ": Trying to find another path");
				// Create first model to represent the empty state
				Model<TermType> model = new Model<TermType>();
				Collection<TermType> initialPercepts = perceptTracker.get(0);
				model.updateGameplayTracker(0, initialPercepts, null, initialState, role, 1);

				int step = 1;
				int maxStep = step;
//			System.out.println("stepNum: " + stepNum);
				while (step < stepNum + 1) {
//				System.out.println("\tRan " + i + " times on step " + j);
					step = forwardHypergame(model, step, true);
					if (step < maxStep - backtrackingDepth || step == 0) break;
					maxStep = Math.max(step, maxStep);
				}
				if (step < maxStep - backtrackingDepth) continue;
				else if (step == 0) break;

				if(currentlyInUseMoves.containsKey(model.getPreviousActionPathHash())) {
					Collection<JointMove<TermType>> inUseMoveSet = currentlyInUseMoves.get(model.getPreviousActionPathHash());
					inUseMoveSet.add(model.getLastAction());
				} else {
					Collection<JointMove<TermType>> inUseMoveSet = new HashSet<JointMove<TermType>>();
					inUseMoveSet.add(model.getLastAction());
					currentlyInUseMoves.put(model.getPreviousActionPathHash(), inUseMoveSet);
				}

				// @todo: This will be done only if there is more than 0 hypergames [since this is optional]
				choiceFactor = likelihoodTree.getRelativeLikelihood(model.getActionPathHashPath());
				if(choiceFactor <= 0 && hypergames.size() > 0) {
					System.out.println("UPDATE CHOICE FACTOR < 0.0 and > 0 remaining");
					hypergames.remove(model);
					continue;
				}

				hypergames.add(model);

				legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role, match));
				model.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMovesInState));
				legalMoves.addAll(legalMovesInState);
			}
			System.out.println(this.getName() + ": Number of hypergames after searching more: " + hypergames.size());

			// Filter to ensure the hypergames have the correct variance
			if(hypergames.size() > numHyperGames) {
				hypergames = filterByVariance(hypergames);
			}
			System.out.println(this.getName() + ": Number of hypergames after reducing variance: " + hypergames.size());
		}
		// If there is more than 1 hypergame then check if the first hypergame has a prob > 0
		if(hypergames.size() > 1) {
			if(likelihoodTree.getRelativeLikelihood(hypergames.get(0).getActionPathHashPath()) <= 0 ) hypergames.remove(0);
		}

		currentlyInUseMoves.clear();

		//Calculate how long the update took
		long endTime =  System.currentTimeMillis();
		long updateTime = endTime - startTime;

		// Print all models
//		System.out.println();
//		printHypergames();
//		System.out.println();
//		for(Model<TermType> model: hypergames) {
//			System.out.println(model.toString());
//		}
//		System.out.println();
//		System.out.println("Likelihood Tree: " + likelihoodTree.toString());
//		System.out.println();
//		System.out.println("badMovesTracker: " + badMovesTracker);
//		System.out.println();
//		System.out.println("stepNum: " + stepNum);
//		System.out.println();

		// Select a move
		long selectStartTime =  System.currentTimeMillis();
		MoveInterface<TermType> bestMove = null;
		if(!legalMoves.isEmpty()) {
			//			System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
			//			for (Model<TermType> model : hypergames) {
			//				System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%");
			//				System.out.println(model.toString());
			//				System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%");
			//			}
			//			System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
			//			System.out.println("legalMoves: " + legalMoves);

			Iterator<MoveInterface<TermType>> iter = legalMoves.iterator();
			bestMove = iter.next();
			if (legalMoves.size() > 1) {
				bestMove = anytimeMoveSelection(legalMoves);
			}
		}
		long selectEndTime =  System.currentTimeMillis();
		long selectTime = selectEndTime - selectStartTime;

		// Print move to file
		try {
			FileWriter myWriter = new FileWriter("matches/" + matchID + ".csv", true);
			myWriter.write(matchID + "," + gameName + "," + stepNum + "," + roleName + "," + name + "," + hypergames.size() + "," + depth + "," + updateTime + "," + selectTime + "," + bestMove + "," + wasIllegal + "," + numTimesMovesSimulated + "," + numTimesHypergameForward + "\n");
			myWriter.close();
		} catch (IOException e) {
			System.err.println("An error occurred.");
			e.printStackTrace();
		}

//		System.out.println();
//		System.out.println("legalMoves: " + legalMoves);
//		System.out.println("bestMove: " + bestMove);
//		System.out.println();

		return bestMove;
	}

	public ArrayList<Model<TermType>> filterByVariance(ArrayList<Model<TermType>> hypergameList) {
		ArrayList<Model<TermType>> filteredHypergameList = new ArrayList<Model<TermType>>();

		// Find the most likely model
		// @todo: Reduce all of this looping to a single loop
			// Calculate relative probabilities of each hypergame
		HashMap<Integer, Double> choiceFactors = new HashMap<Integer, Double>();
		double choiceFactor;
		double choiceFactorSum = 0;
		for(Model<TermType> model : hypergames) {
			choiceFactor = likelihoodTree.getRelativeLikelihood(model.getActionPathHashPath());
			double treecf = model.getNumberOfPossibleActions();
			choiceFactors.put(model.getActionPathHash(), choiceFactor);
			choiceFactorSum += choiceFactor;
		}
		HashMap<Integer, Double> hyperProbs = new HashMap<Integer, Double>();
		double prob;
		for(Model<TermType> model : hypergames) {
			choiceFactor = choiceFactors.get(model.getActionPathHash());
			prob = choiceFactorSum > 0.0 ? ( choiceFactor / choiceFactorSum ) : 1.0;
			hyperProbs.put(model.getActionPathHash(), prob);
		}
		// Find the most likely model
		System.out.println();
		Model<TermType> mostLikelyModel = hypergameList.get(0);
		double mostLikelyProb = hyperProbs.get(mostLikelyModel.getActionPathHash());
		for (Model<TermType> model : hypergameList) {
			System.out.println("Hypergame " + model.getActionPathHash() + " has a probability of " + hyperProbs.get(model.getActionPathHash()));
			if(hyperProbs.get(model.getActionPathHash()) > mostLikelyProb) {
				mostLikelyProb = hyperProbs.get(model.getActionPathHash());
				mostLikelyModel = model;
			}
		}
		System.out.println();

		// Select based on variance
			// First select based on likelihood
		ArrayList<Model<TermType>> hypergameListClone = new ArrayList<Model<TermType>>(hypergameList);
		filteredHypergameList.add(mostLikelyModel);
		hypergameListClone.remove(mostLikelyModel);
		System.out.println();
		System.out.println("Chose model " + mostLikelyModel.getActionPathHash() + " as the most likely model");
		System.out.println();
		ArrayList<HashSet<TermType>> statePropsInModels = new ArrayList<HashSet<TermType>>();
		HashSet<TermType> statePropsInModel = new HashSet<TermType>((Collection<TermType>) mostLikelyModel.getCurrentState(match).getFluents());
		statePropsInModels.add(statePropsInModel);
		while(filteredHypergameList.size() < numHyperGames) {
			ArrayList<Model<TermType>> mostVariedModels = new ArrayList<Model<TermType>>();
			int mostVariedVariance = -1;
			// Calculate game with greatest variance from those seen so far
			for(Model<TermType> model : hypergameListClone) {
				// Sum all differences
				int variance = 0;
				// Loop over all current models and spot any differences
				for(HashSet<TermType> stateProps : statePropsInModels) {
					Iterator<TermType> it = (Iterator<TermType>) model.getCurrentState(match).getFluents().iterator();
					int numMatches = 0;
					while (it.hasNext())
					{
						if(stateProps.contains(it.next())) {
							numMatches++;
						} else {
							variance++;
						}
					}
					variance += (stateProps.size() - numMatches);
				}
				System.out.println("Model " + model.getActionPathHash() + " has variance of " + variance);

				// If variance is greater, then add to list
				if(variance > mostVariedVariance) {
					mostVariedModels.clear();
					mostVariedModels.add(model);
					mostVariedVariance = variance;
				} else if (variance == mostVariedVariance) {
					mostVariedModels.add(model);
				}
			}

			// If multiple, select based on probability
			Model<TermType> chosenModel = null;
			if(mostVariedModels.size() > 1) {
				// Get one with highest probability
				chosenModel = mostVariedModels.get(0);
				mostLikelyProb = hyperProbs.get(chosenModel.getActionPathHash());
				System.out.println();
				for (Model<TermType> model : mostVariedModels) {
					System.out.println("TOP RUNNER: Hypergame " + model.getActionPathHash() + " has a probability of " + hyperProbs.get(model.getActionPathHash()));
					if(hyperProbs.get(model.getActionPathHash()) > mostLikelyProb) {
						mostLikelyProb = hyperProbs.get(model.getActionPathHash());
						chosenModel = model;
					}
				}
				System.out.println();
			} else {
				chosenModel = mostVariedModels.get(0);
			}

			System.out.println();
			System.out.println("Chose model " + chosenModel.getActionPathHash() + " as the model with greatest variance/probability");
			System.out.println();

			// Add model to filtered list and remove from cloned list
			filteredHypergameList.add(chosenModel);
			hypergameListClone.remove(chosenModel);
			statePropsInModel = new HashSet<TermType>((Collection<TermType>) chosenModel.getCurrentState(match).getFluents());
			statePropsInModels.add(statePropsInModel);
		}

		// Print chosen models
		int count = 0;
		for(Model<TermType> model : filteredHypergameList) {
			System.out.println(count + ": Model " + model.getActionPathHash());
			count++;
		}

		// Return final selection
		return filteredHypergameList;
	}

	public boolean canSearchMore() {
		// If at time limit, then timeout
		if(System.currentTimeMillis() - startTime > (this.match.getPlayclock()*1000)) {
			//			System.out.println("TOTAL TIMEOUT");
			return false;
		}
		if(hypergames.size() == 0) {
			//			System.out.println("NO GAMES, MUST SEARCH MORE");
			return true; // If no games, search until 1
		}
		if(hypergames.size() >= (numHyperGames * 2)) { // If already at max then stop searching
			//			System.out.println("REACHED MAX");
			return false;
		}
		// NOT if tree has been fully searched
		// If tree has been fully searched, then the inital state will have 0 valid actions
		else {
			ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) initialState, actionTracker.get(0), this.role));
			removeBadMoves(possibleJointMoves, 31); // @todo: Fix this with a reasonable value
			removeInUseMoves(possibleJointMoves, 31);
			int numCleanJointMoves = possibleJointMoves.size();
			if(numCleanJointMoves <= 0) {
				//				System.out.println("Tried all moves from root");
				return false;
			}
			else {
				//				 NOT if searched enough
				if(System.currentTimeMillis() - startTime > stateUpdateTimeLimit) {
					//					System.out.println("STATE UPDATE TIMEOUT");
					return false;
				}
				//				 Else return true
				else {
					//					System.out.println("Can search more");
					return true;
				}
			}
		}
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
	 * anytimeMoveSelection calculates the optimal move to select given a set of possible moves and a bag of hypergames
	 * with increasing accuracy by running more simulations as time permits
	 *
	 * @param possibleMoves A set of possible moves
	 * @return An approximation of the optimal move from known information
	 */
	public MoveInterface<TermType> anytimeMoveSelection(HashSet<MoveInterface<TermType>> possibleMoves) {
		// Calculate P(HG)
		// Calculate inverse choice factor sum
		HashMap<Integer, Double> choiceFactors = new HashMap<Integer, Double>();
		double choiceFactor;
		double treecf;
		double choiceFactorSum = 0;
		double invChoiceFactorSum = 0;
		Collection<Model<TermType>> zeroProbHypergames = new ArrayList<Model<TermType>>();
		for(Model<TermType> model : hypergames) {
			choiceFactor = likelihoodTree.getRelativeLikelihood(model.getActionPathHashPath());
	//			if(choiceFactor <= 0.0) {
	//				zeroProbHypergames.add(model);
	//			}
	//			else {

			treecf = model.getNumberOfPossibleActions(); // Use for comparison when using non-uniform opponent modelling
	//				if(choiceFactor != treecf) {
	//					System.out.println("NO MATCH");
	//					System.out.println("Current choice factor: " + choiceFactor);
	//					System.out.println("likelihoodTree choice factor: " + treecf);
	//					System.exit(0);
	//				}
			choiceFactors.put(model.getActionPathHash(), choiceFactor);
			choiceFactorSum += choiceFactor;
			invChoiceFactorSum += 1.0 / treecf;
	//			}
		}
	//		hypergames.removeAll(zeroProbHypergames);// @todo: this may reduce to 0 so keep an eye out for it

	//		System.out.println();

		// Calculate the probability of each hypergame
		HashMap<Integer, Double> hyperProbs = new HashMap<Integer, Double>();
		HashMap<Integer, Double> hyperProbsOrig = new HashMap<Integer, Double>();
		double prob;
		double choiceProb;
	//		System.out.println();
	//		System.out.println("There are " + hypergames.size() + " games remaining");
//			System.out.println("choiceFactorSum: " + choiceFactorSum);
		for(Model<TermType> model : hypergames) {
			choiceFactor = choiceFactors.get(model.getActionPathHash());
			treecf = model.getNumberOfPossibleActions();
			prob = choiceFactorSum > 0.0 ? ( choiceFactor / choiceFactorSum ) : 1.0;
			choiceProb = ( ( 1.0 / treecf ) / invChoiceFactorSum );
//				System.out.println("Model " + model.getActionPathHash() + " has choiceFactor: " + choiceFactor);
				System.out.println("Model " + model.getActionPathHash() + " has prob: " + prob);
//				System.out.println("Model " + model.getActionPathHash() + " has choiceProb: " + choiceProb);
	//			if(prob != choiceProb) {
	//				System.out.println("NO MATCH");
	//				System.exit(0);
	//			}
			hyperProbs.put(model.getActionPathHash(), prob);
			model.setLastProb(prob);
			hyperProbsOrig.put(model.getActionPathHash(), choiceProb);
		}
			System.out.println();

		// Calculate expected move value for each hypergame until almost out of time
		HashMap<Integer, Double> weightedExpectedValuePerMove = new HashMap<Integer, Double>();
		HashMap<Integer, Double> weightedExpectedValuePerMoveOrig = new HashMap<Integer, Double>();
		HashMap<Integer, MoveInterface<TermType>> moveHashMap = new HashMap<Integer, MoveInterface<TermType>>();
		HashMap<Integer, Double> moveCountMap = new HashMap<Integer, Double>();
		depth = 0;
		Model<TermType> tempModel;
		StateInterface<TermType, ?> currState;
		while(System.currentTimeMillis() - startTime < timeLimit && depth < maxNumProbes) { // @todo: May need to add break points at the end of each move calc and each hypergame calc
//			System.out.println("Depth: " + depth);
			for (Model<TermType> model : hypergames) {
//				if(hyperProbs.get(model.getActionPathHash()) == 0.0) continue; // Continue if the prob of the hypergame is 0
//				System.out.println("\tModel: " + model.getActionPathHash());
				for (MoveInterface<TermType> move : possibleMoves) {
					if(System.currentTimeMillis() - startTime > timeLimit) {
						//						System.out.println("Had to Break 1");
						break;
					}
					tempModel = new Model<TermType>(model);
					currState = tempModel.getCurrentState(match);
					if(!moveHashMap.containsKey(move.hashCode())) moveHashMap.put(move.hashCode(), move);

					// Calculate the the expected value for each move using monte carlo simulation
					double expectedValue = 0.0;
					if(model.getPossibleMovesAtStep(stepNum).contains(move)) {
						expectedValue = anytimeSimulateMove(currState, move, role);
//						System.out.println("model: " + model.getActionPathHash() + " does contain move " + move + " with expected value " + expectedValue);
					}
//					else {
//						System.out.println("model: " + model.getActionPathHash() + " does NOT!!! contain move " + move + " with expected value " + expectedValue);
//					}

					// Calculate the weighted expected value for each move
					double likelihood = hyperProbs.get(model.getActionPathHash());
					double likelihoodOrig = hyperProbsOrig.get(model.getActionPathHash());
					double weightedExpectedValue = expectedValue * Math.pow(likelihood, likelihoodPowerFactor);
					double weightedExpectedValueOrig = expectedValue * Math.pow(likelihoodOrig, likelihoodPowerFactor);

					// Add expected value to hashmap
					if (!weightedExpectedValuePerMove.containsKey(move.hashCode())) {
						weightedExpectedValuePerMove.put(move.hashCode(), weightedExpectedValue);
						moveCountMap.put(move.hashCode(), 1.0);
					} else {
						double prevWeightedExpectedValue = weightedExpectedValuePerMove.get(move.hashCode());
						double count = moveCountMap.get(move.hashCode());
						moveCountMap.replace(move.hashCode(), count + 1.0);
						weightedExpectedValuePerMove.replace(move.hashCode(), ((count * prevWeightedExpectedValue) + weightedExpectedValue) / (count + 1.0));
					}
					if (!weightedExpectedValuePerMoveOrig.containsKey(move.hashCode())) {
						weightedExpectedValuePerMoveOrig.put(move.hashCode(), weightedExpectedValueOrig);
					} else {
						double prevWeightedExpectedValueOrig = weightedExpectedValuePerMoveOrig.get(move.hashCode());
						double count = moveCountMap.get(move.hashCode());
						weightedExpectedValuePerMoveOrig.replace(move.hashCode(), (((count - 1.0) * prevWeightedExpectedValueOrig) + weightedExpectedValueOrig) / count);
					}
				}
				if(System.currentTimeMillis() - startTime > timeLimit) { // @todo: make look better
					//					System.out.println("Had to Break 2");
					break;
				}
			}
			depth++;
		}
		System.out.println("Ran " + depth + " simulations TOTAL");

		// Return the move with the greatest weighted expected value
		long startFinalCalcTime =  System.currentTimeMillis();

		// Write the moveset to a file
		MoveInterface<TermType> bestMove = null;
		try {
			// Create the file
			new File("matches/op_move_distribution/" + matchID).mkdirs();
			FileWriter myWriter = new FileWriter("matches/op_move_distribution/" + matchID + "/" + stepNum +  ".csv", false);

			Iterator<HashMap.Entry<Integer, Double>> it = weightedExpectedValuePerMove.entrySet().iterator();
			double maxVal = -(Double.MAX_VALUE);
			System.out.println("Opponent Modelling:");
			while(it.hasNext()){
				HashMap.Entry<Integer, Double> mapElement = (HashMap.Entry<Integer, Double>)it.next();
				Double val = mapElement.getValue();
				System.out.println("value of move " + moveHashMap.get(mapElement.getKey()) + " is " + val);
				if(val > maxVal) {
					bestMove = moveHashMap.get(mapElement.getKey());
					maxVal = val;
				}
				myWriter.write(moveHashMap.get(mapElement.getKey()) + "," + (val/maxNumProbes) + "\n");
			}
			myWriter.close();

			new File("matches/orig_move_distribution/" + matchID).mkdirs();
			myWriter = new FileWriter("matches/orig_move_distribution/" + matchID + "/" + stepNum +  ".csv", false);
			System.out.println();
			System.out.println("Original Values:");
			it = weightedExpectedValuePerMoveOrig.entrySet().iterator();
			while(it.hasNext()){
				HashMap.Entry<Integer, Double> mapElement = (HashMap.Entry<Integer, Double>)it.next();
				Double val = mapElement.getValue();
				System.out.println("value of move " + moveHashMap.get(mapElement.getKey()) + " is " + val);
				myWriter.write(moveHashMap.get(mapElement.getKey()) + "," + (val/maxNumProbes) + "\n");
			}

			myWriter.close();
		} catch (IOException e) {
			System.err.println("An error occurred.");
			e.printStackTrace();
		}


//		System.out.println("bestMove " + bestMove);
		long endFinalCalcTime =  System.currentTimeMillis();
		long updateTime = endFinalCalcTime - startFinalCalcTime;
	//		System.out.println("Took " + updateTime + " ms to run final calc");


		return bestMove;
	}

	/**
	 * Get the expected result of a move from a given state using a single monte carlo simulation
	 *
	 * @param state - The current state of the game
	 * @param move - The first move to be tried
	 * @return The statistical expected result of a move
	 */
	public double anytimeSimulateMove(StateInterface<TermType, ?> state, MoveInterface<TermType> move, RoleInterface<TermType> role) {
		double expectedOutcome;
		// Repeatedly select random joint moves until a terminal state is reached
		StateInterface<TermType, ?> currState = state;
		JointMoveInterface<TermType> randJointMove;
		boolean isFirstMove = true;
		while(!currState.isTerminal()) {
			if(isFirstMove) {
				randJointMove = getRandomJointMove(currState, move, role);
				if (randJointMove == null) System.exit(0);
				isFirstMove = false;
			} else {
				randJointMove = getRandomJointMove(currState);
			}
			currState = currState.getSuccessor(randJointMove);
		}
		expectedOutcome = currState.getGoalValue(role);
		numTimesMovesSimulated++;
		return expectedOutcome;
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
	public int forwardHypergame(Model<TermType> model, int step, boolean flag) {
		numTimesHypergameForward++;
		// Update the model using a random joint move
			// Get all possible moves and remove the known bad moves
		StateInterface<TermType, ?> state = model.getCurrentState(match);
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, actionTracker.get(step - 1), this.role));
//		System.out.println("possibleJointMoves: " + possibleJointMoves);
		int numPossibleJointMoves = possibleJointMoves.size();

//		System.out.println();
//		System.out.println("actual possibleJointMoves: " + possibleJointMoves);
		removeBadMoves(possibleJointMoves, model.getActionPathHash());

//		System.out.println("possibleJointMoves NOBAD: " + possibleJointMoves);

		// Update the value of the current state in the likelihood tree
//		System.out.println();
//		System.out.println("Tree : " + likelihoodTree.toString());
//		System.out.println("root: " + likelihoodTree.getRoot());
//		System.out.println("rood node: " + likelihoodTree.getRoot().toString());
//		System.out.println("Action Path Hash: " + model.getActionPathHashPath());
//		System.out.println("Getting node: " + likelihoodTree.getNode(model.getActionPathHashPath()));
//		System.out.println("Num possibilities: " + possibleJointMoves.size());
//		System.out.println();

//		System.out.println("Num possibilities: " + possibleJointMoves.size());
//		System.out.println("Updated node: " + likelihoodTree.getNode(model.getActionPathHashPath()));

//		System.exit(0);

		int numNOBADJointMoves = possibleJointMoves.size();
		removeInUseMoves(possibleJointMoves, model.getActionPathHash());
//		System.out.println("possibleJointMoves NOBAD NOINUSE: " + possibleJointMoves);

		// If the node has not been expanded yet, then expand it
		if(!likelihoodTreeExpansionTracker.contains(model.getActionPathHash())) {
			Node node = likelihoodTree.getNode(model.getActionPathHashPath());

			// Run MCS simulations on each valid move to calculate its relative value
			MoveInterface<TermType> move;
			Node child;
			double expectedValue;
			double totalValue = 0.0;
			PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>> moveQueue = new PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>>(possibleJointMoves.size(), new JointMoveTupleComparator());
			ArrayList<Tuple<Double, JointMoveInterface<TermType>>> moveList = new ArrayList<Tuple<Double, JointMoveInterface<TermType>>>();
			System.out.println("Moves: " + possibleJointMoves.size());
			System.out.println("numTimesMovesSimulated BEFORE: " + numTimesMovesSimulated);
			for (JointMoveInterface<TermType> jointMove : possibleJointMoves) {
				// Use this move
				move = jointMove.get(opponentRole);
				expectedValue = 0;
				for(int i = 0 ; i < numOPProbes ; i++) {
					expectedValue += anytimeSimulateMove(state, move, opponentRole);
				}
				expectedValue = expectedValue/numOPProbes;
				totalValue += expectedValue;

				// Expand the node
				model.getActionPath().push((JointMove<TermType>)jointMove);
				child = new Node(model.getActionPath().hashCode());
				child.setValue(expectedValue);
				node.addChild(child);
				model.getActionPath().pop();

				// Add the move to the map
				Tuple<Double, JointMoveInterface<TermType>> tuple = new Tuple<Double, JointMoveInterface<TermType>>(expectedValue, jointMove);
				moveQueue.add(tuple);
				moveList.add(tuple);
			}
			for(Node likelihoodChild : node.getChildren()) {
				likelihoodChild.setRelLikelihood(likelihoodChild.getValue() > 0.0 ? ((double)likelihoodChild.getValue()) / totalValue : 0.0);
			}
			System.out.println("numTimesMovesSimulated AFTER: " + numTimesMovesSimulated);

			// Add node to set of explored nodes AND add priority queue to map
			likelihoodTreeExpansionTracker.add(model.getActionPathHash());
			moveSelectOrderMap.put(model.getActionPathHash(), moveQueue);
			moveSelectMap.put(model.getActionPathHash(), moveList);
		}

//		// Select an action - THIS USES THE DETERMINISTIC METHOD
//		JointMove<TermType> jointAction = null;
//		PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>> jointMoveQueue = new PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>>(moveSelectOrderMap.get(model.getActionPathHash()));
//		Collection<JointMove<TermType>> inUse  = currentlyInUseMoves.get(model.getActionPathHash());
////		System.out.println();
////		System.out.println(jointMoveQueue);
//		while(!jointMoveQueue.isEmpty()) {
//			JointMoveInterface<TermType> jointMove =  jointMoveQueue.poll().getB();
////			System.out.println("POLLED");
//			if(inUse == null || !inUse.contains(jointMove)) {
//				jointAction = (JointMove<TermType>)jointMove;
//				break;
//			}
//		}
		// Select an action - THIS USES THE PROBABILISTIC METHOD
		JointMove<TermType> jointAction = null;
		ArrayList<Tuple<Double, JointMoveInterface<TermType>>> jointMoveList = new ArrayList<Tuple<Double, JointMoveInterface<TermType>>>(moveSelectMap.get(model.getActionPathHash()));
		// Only consider valid moves
		ArrayList<Tuple<Double, JointMoveInterface<TermType>>> disallowedItems = new ArrayList<Tuple<Double, JointMoveInterface<TermType>>>();
		for(Tuple<Double, JointMoveInterface<TermType>> tup : jointMoveList) {
			if(!possibleJointMoves.contains((JointMove<TermType>)tup.getB())) disallowedItems.add(tup);
		}
		jointMoveList.removeAll(disallowedItems);
//		System.out.println("jointMoveList: " + jointMoveList);
//		System.out.println("possibleJointMoves" + possibleJointMoves);
		// Select based on the relative probability of each move
			// Get the sum of probabilities
		double sumWeight = 0;
		for (Tuple<Double, JointMoveInterface<TermType>> tup : jointMoveList){
			sumWeight += tup.getA();
		}
			// Choose a random item
		int randIndex = -1;
		double rand = random.nextDouble() * sumWeight;
			// Cycle through the list until you the random number is < 0
		Tuple<Double, JointMoveInterface<TermType>> tup;
		for (int i = 0 ; i < jointMoveList.size() ; i++) {
			tup = jointMoveList.get(i);
			rand -= tup.getA();
			if(rand <= 0.0) {
				randIndex = i;
				break;
			}
		}
		if(randIndex != -1) jointAction = (JointMove<TermType>)jointMoveList.get(randIndex).getB();
//		System.out.println("Chose to expand move: " + jointAction);

//		while(!jointMoveQueue.isEmpty()) {
//			JointMoveInterface<TermType> jointMove =  jointMoveQueue.poll().getB();
////			System.out.println("POLLED");
//			if(inUse == null || !inUse.contains(jointMove)) {
//				jointAction = (JointMove<TermType>)jointMove;
//				break;
//			}
//		}
//		System.out.println();
//		System.out.println(moveSelectOrderMap.get(model.getActionPathHash()));

//		System.out.println();
//		for(Integer i : likelihoodTreeExpansionTracker) {
//			System.out.println("\t" + i.toString());
//		}
//		System.out.println();

//		System.out.println("cleaned possibleJointMoves: " + possibleJointMoves);
//		System.out.println("chosen action: " + jointAction);
//		System.out.println();

		// If there are no valid moves from this state, then backtrack and try again
		if (jointAction == null) {
			// Get move that got to this state and add to bad move set
			JointMove<TermType> lastAction = model.getLastAction();
			model.backtrack();

			// Add move to bad move set if there are no other active moves from this point
//			System.out.println("hash: " + model.getActionPathHash() + " tried " + jointAction + " but FAILED because null");

			if(numNOBADJointMoves == 0) {
//				System.out.println("ADDED " + lastAction + " TO BAD MOVES LIST");
				updateBadMoveTracker(model.getActionPathHash(), lastAction, model.getActionPathHashPath());
			} else {
//				System.out.println("ADDED " + lastAction + " TO --IN-USE-- MOVES LIST");
				// Add it to inuse
				if(currentlyInUseMoves.containsKey(model.getActionPathHash())) {
					Collection<JointMove<TermType>> inUseMoveSet = currentlyInUseMoves.get(model.getActionPathHash());
					inUseMoveSet.add(lastAction);
				} else {
					Collection<JointMove<TermType>> inUseMoveSet = new HashSet<JointMove<TermType>>();
					inUseMoveSet.add(lastAction);
					currentlyInUseMoves.put(model.getActionPathHash(), inUseMoveSet);
				}
//				updateBadMoveTracker(model.getActionPathHash(), lastAction, model.getActionPathHashPath()); // @todo: does this even make sense if it's null?
			}

			return step - 1;
		} else {
			// If a valid move could be found, update the state
//			System.out.println("UPDATE GAMEPLAY TRACKER");
			model.updateGameplayTracker(step, null, jointAction, state, role, numPossibleJointMoves);

			// Check if new model does not match expected percepts
			if (!model.getLatestExpectedPercepts().equals(perceptTracker.get(step))) {
				// Update path
//				Node node = likelihoodTree.getNode(model.getActionPathHashPath()); //@todo: does this help?? IT DOES NOT!!!
//				if(node != null) {
//					Node parent = node.getParent();
//					//					System.out.println("parent.getActionPathHash(): " + parent.getActionPathHash());
//					node.setValue(0.0);
//					//					System.out.println("node after: " + node);
//					likelihoodTree.updateRelLikelihood(parent);
//				}

				// Backtrack
				model.backtrack();

//				System.out.println();
//				System.out.println("Latest actionpath hash: " + model.getActionPathHash());
//				System.out.println("Actionpath hash path: " + model.getActionPathHashPath());
//				System.out.println();

				// Add move to bad move set
//				System.out.println("hash: " + model.getActionPathHash() + " tried " + jointAction + " but FAILED because mismatch");
				updateBadMoveTracker(model.getActionPathHash(), jointAction, model.getActionPathHashPath());

				// Try again
				return step;
			} else if(step < stepNum) { // Check for blacklisted/whitelisted moves
				// See if the legal moves contained have any blacklisted moves
				HashSet<MoveInterface<TermType>> legalMovesInState = new HashSet<MoveInterface<TermType>>(model.computeLegalMoves(role, match));
				model.addLegalMoves(stepNum, new HashSet<MoveInterface<TermType>>(legalMovesInState));
				// Find all hypergames that allowed that move and remove them
				boolean containsInvalidMoves = false;
//				System.out.println("step: " + step);
//				System.out.println("legalMovesInState: " + legalMovesInState);
//				System.out.println("moveForStepBlacklist.get(step): " + moveForStepBlacklist.get(step));
//				System.out.println("moveForStepWhitelist.get(step): " + moveForStepWhitelist.get(step));
				if(moveForStepBlacklist.containsKey(step) && legalMovesInState.contains(moveForStepBlacklist.get(step))) {
//					System.out.println("Removed model " + model.getActionPathHash() + " because contained blacklisted move");
					containsInvalidMoves = true;
				}
				// Find all hypergames that didn't allow the true move used and remove them
				if(moveForStepWhitelist.containsKey(step) && !legalMovesInState.contains(moveForStepWhitelist.get(step))) {
//					System.out.println("Removed model " + model.getActionPathHash() + " because did not contain whitelisted move");
					containsInvalidMoves = true;
				}
				if(containsInvalidMoves) {
					// Backtrack
					model.backtrack();

					// Add move to bad move set
//					System.out.println("hash: " + model.getActionPathHash() + " tried " + jointAction + " but FAILED because it contained an invalid move/did not contain THE valid move");
					updateBadMoveTracker(model.getActionPathHash(), jointAction, model.getActionPathHashPath());

					// Try again
					return step;
				} else {
					// Else this is a valid move
//					System.out.println("hash: " + model.getPreviousActionPathHash() + " tried " + jointAction + " SUCCESS!");


					return step + 1;
				}
			} else {
				// Else this is a valid move
//				System.out.println("hash: " + model.getPreviousActionPathHash() + " tried " + jointAction + " SUCCESS!");


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
	public void updateBadMoveTracker(int backtrackedModelHash, JointMove<TermType> badMove, ArrayDeque<Integer> actionPathHashPath) {
		if (badMovesTracker.containsKey(backtrackedModelHash)) {
			Collection<JointMove<TermType>> badJointActions = badMovesTracker.get(backtrackedModelHash);
			badJointActions.add(badMove);
		} else {
			Collection<JointMove<TermType>> badJointActions = new ArrayList<JointMove<TermType>>();
			badJointActions.add(badMove);
			badMovesTracker.put(backtrackedModelHash, badJointActions);
		}

		PriorityQueue<Tuple<Double, JointMoveInterface<TermType>>> jointMoveQueue = moveSelectOrderMap.get(backtrackedModelHash);
		Iterator<Tuple<Double, JointMoveInterface<TermType>>> it = jointMoveQueue.iterator();
		Tuple<Double, JointMoveInterface<TermType>> tuple = null;
		while(it.hasNext()) {
			tuple = it.next();
//			System.out.println("checking tuple: " + tuple);
			if(tuple.getB().equals(badMove)) {
//				System.out.println("FOUND MATCH: " + tuple);
				break;
			}
		}
		if(tuple != null) {
//			System.out.println("BEFORE: " + jointMoveQueue);
			jointMoveQueue.remove(tuple);
//			System.out.println("REMOVED TUPLE: " + tuple);
//			System.out.println("AFTER: " + moveSelectOrderMap.get(backtrackedModelHash));
		}

		// Decrement the value at the node
//		Node node = likelihoodTree.getNode(actionPathHashPath);
//		if(node != null) {
//			System.out.println("DECREMENTED " + backtrackedModelHash + " for the move " + badMove + " with actionPathHashPath " + actionPathHashPath);
//			node.setValue(Math.max(node.getValue() - 1, 0));
//		}
	}

	/**
	 * Gets a random joint move given the action matches the action taken in the last step
	 *
	 * @param state - The current state
	 * @param action - The action that the player will take
	 * @return A random joint move
	 */
	public JointMoveInterface<TermType> getRandomJointMove(StateInterface<TermType, ?> state, MoveInterface<TermType> action, RoleInterface<TermType> role) {
		ArrayList<JointMoveInterface<TermType>> possibleJointMoves = new ArrayList<JointMoveInterface<TermType>>(computeJointMoves((StateType) state, action, role));
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
	public Collection<JointMoveInterface<TermType>> computeJointMoves(StateType state, MoveInterface<TermType> action, RoleInterface<TermType> positionedRole) {
		// compute legal moves for all roles such that the action matches for the player's role
		HashMap<RoleInterface<TermType>, Collection<? extends MoveInterface<TermType>>> legalMovesMap = new HashMap<RoleInterface<TermType>, Collection<? extends MoveInterface<TermType>>>();
		for(RoleInterface<TermType> role: match.getGame().getOrderedRoles()) {
			if(role == positionedRole) {
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
	 * Removes all moves in the bad move Tracker
	 *
	 * @param jointMoves - A list of joint moves
	 * @param actionPathHash - The action-path hash from which to consider which moves are invalid
	 */
	public void removeBadMoves(ArrayList<JointMoveInterface<TermType>> jointMoves, int actionPathHash) {
		if(badMovesTracker.containsKey(actionPathHash)) {
			jointMoves.removeAll(badMovesTracker.get(actionPathHash));
		}
	}

	/**
	 * Removes all moves in the in-use moves tracker
	 *
	 * @param jointMoves - A list of joint moves
	 * @param actionPathHash - The action-path hash from which to consider which moves are invalid
	 */
	public void removeInUseMoves(ArrayList<JointMoveInterface<TermType>> jointMoves, int actionPathHash) {
		if(currentlyInUseMoves.containsKey(actionPathHash)) {
			jointMoves.removeAll(currentlyInUseMoves.get(actionPathHash));
		}
	}
}
