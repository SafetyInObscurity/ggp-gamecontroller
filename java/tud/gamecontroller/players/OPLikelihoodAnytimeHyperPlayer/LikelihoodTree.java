package tud.gamecontroller.players.OPLikelihoodAnytimeHyperPlayer;

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
        this.root.setRelLikelihood(1.0);
    }

    public Node getRoot() { return this.root; }
    public Node getNode(ArrayDeque<Integer> actionPathHashPath) {
        if(actionPathHashPath.isEmpty()) {
            return null;
        }

        Node child = this.root;
        for (Integer actionPathHash : actionPathHashPath) {
            if(actionPathHash == child.getActionPathHash()) continue;
            child = child.getChild(actionPathHash);
        }
        return child;
    }

    /**
     * Returns the relative likelihood of each path being chosen by an opponent that acts optimally
     *
     * @param actionPathHashPath The full path from initial state to current state from a hypergame model
     * @return The choice factor of a given action path
     */
    public double getRelativeLikelihood(ArrayDeque<Integer> actionPathHashPath) {
        Node child = getRoot();
        double likelihood = child.getRelLikelihood() == 0 ? 1 : child.getRelLikelihood();
//        System.out.println(likelihood);
        for (Integer actionPathHash : actionPathHashPath) {
            if(actionPathHash == child.getActionPathHash()) continue;
            child = child.getChild(actionPathHash);
//            System.out.println(child.getRelLikelihood());
            if(child.getRelLikelihood() > 0) {
                likelihood *= child.getRelLikelihood();
            } else if (child.getRelLikelihood() == 0) return 0.0;
        }
        return likelihood;
    }

    public void updateRelLikelihood(Node node) {
        double totalValue = 0.0;
        if(node != null && node.getChildren() != null && node.getChildren().size() > 0) {
            for (Node child : node.getChildren()) {
                totalValue += child.getValue();
            }
            if (totalValue > 0) {
                for (Node child : node.getChildren()) {
                    child.setRelLikelihood(child.getValue() > 0.0 ? (((double) child.getValue()) / totalValue) : 0.0);
                }
            }
        }
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
            stringLine.append("\nNode ").append(node.getActionPathHash()).append(" has a value of ").append(node.getValue()).append(" and a likelihood of ").append(node.getRelLikelihood());
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

