package tud.gamecontroller.players.CheatHyperPlayer;

import tud.gamecontroller.term.TermInterface;

import java.util.ArrayDeque;

/**
 *  LikelihoodTree holds the likelihood of each state ocurring in a game tree assuming the opponent chooses moves
 *  randomly from a uniform distribution
 *
 * @author Michael Dorrell
 * @version 1.0
 * @since 1.0
 */
public class LikelihoodTree<TermType extends TermInterface> {

    /**
     * Nodes are the basic components of a tree and are what hold the information
     * Each node holds an integer representing the number of possible states from this tree and the branches from it
     */
    // Instance Variables
    private Node root;

    /**
     * Creates the root of a likelihood tree from an initial action path hash
     *
     * @param initialActionPathHash The initial action path hash of the game
     */
    public LikelihoodTree(int initialActionPathHash) {
        this.root = new Node(initialActionPathHash);
    }

    public Node getRoot() { return this.root; }
    public Node getNode(ArrayDeque<Integer> actionPathHashPath) {
        if(actionPathHashPath.isEmpty()) {
            return null;
        }

        Node child = getRoot();
        for (Integer actionPathHash : actionPathHashPath) {
            if(actionPathHash == child.getActionPathHash()) continue;
            child = child.getChild(actionPathHash);
        }
        return child;
    }

    /**
     * Returns the number of choices that could have been made to lead the path to this state
     *
     * @param actionPathHashPath The full path from initial state to current state from a hypergame model
     * @return The choice factor of a given action path
     */
    public double getChoiceFactor(ArrayDeque<Integer> actionPathHashPath) {
        Node child = getRoot();
        double value = child.getValue() == 0 ? 1 : child.getValue();
//        System.out.println(value);
        for (Integer actionPathHash : actionPathHashPath) {
            if(actionPathHash == child.getActionPathHash()) continue;
            child = child.getChild(actionPathHash);
//            System.out.println(child.getValue());
            if(child.getValue() > 0) {
                value *= child.getValue();
            }
        }
//        System.out.println();
        return value;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        StringBuilder stringLine;
        ArrayDeque<Node> unvisited = new ArrayDeque<Node>();
        unvisited.addLast(getRoot());
        stringBuilder.append("\n").append("LikelihoodTree Nodes:");
        Node node;
        while(!unvisited.isEmpty()) {
            node = unvisited.removeFirst();
            stringLine = new StringBuilder();
            stringLine.append("\n").append(node.getActionPathHash()).append(" has ").append(node.getValue()).append(" children:");
            for (Node child : node.getChildren()) {
                unvisited.addLast(child);
                stringLine.append("\n\t").append(child.getActionPathHash());
            }
            stringBuilder.append(stringLine.toString());
        }
        stringBuilder.append("\n");
        return stringBuilder.toString();
    }

}

