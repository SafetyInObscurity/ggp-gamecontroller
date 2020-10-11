package tud.gamecontroller.players.OPExpansionAnytimeHyperPlayer;

import java.util.ArrayList;

public class Node {

    // Instance Variables
    private double value; // The value of this node from the opponent's perspective
    private int actionPathHash; // The action-path leading to this state
    private double relLikelihood; // The relative quality of the node compared to the other options from the opponent's perspective
    private ArrayList<Node> children; // The nodes below it
    private Node parent; // The parent node

    /**
     * Instantiates a Node object with 0 children
     */
    public Node(int actionPathHash) {
        this.value = -1.0;
        this.relLikelihood = -1.0;
        this.actionPathHash = actionPathHash;
        this.children = new ArrayList<Node>();
        this.parent = null;
    }

    public double getValue() { return this.value; }
    public double getRelLikelihood() { return this.relLikelihood; }
    public int getActionPathHash() { return this.actionPathHash; }
    public Node getParent() { return (this.parent); }
    public ArrayList<Node> getChildren() { return this.children; }
    public Node getChild(int actionPathHash) {
        for (Node node : this.children) {
            if(node.getActionPathHash() == actionPathHash) {
                return node;
            }
        }
        return null;
    }

    public void setValue(double value) {
        this.value = value;
    }
    public void setRelLikelihood(double likelihood) {
        this.relLikelihood = likelihood;
    }
    public void setParent(Node parent) {
        this.parent = parent;
    }

    /**
     * Adds this child to this Node's children
     *
     * @param child A Node to add as a child to this Node
     */
    public void addChild(Node child) {
        boolean contained = false;
        for (Node node : this.children) {
            if(node.getActionPathHash() == child.getActionPathHash()) {
                contained = true;
            }
        }
        if(!contained) {
            child.setParent(this);
            this.children.add(child);
        }
    }

//    /**
//     * Removes the child from this Node's children
//     *
//     * @param child A Node to remove from this Node's children
//     */
//    public void removeChild(Node child) {
//        if(child.getParent().equals(this)) child.setParent(null);
//        this.children.remove(child);
//    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\nactionPathHash: ").append(this.actionPathHash);
        stringBuilder.append("\nvalue: ").append(this.value);
        stringBuilder.append("\nchildren: ");
        for (Node child : this.children) {
            stringBuilder.append("\n\t").append(child.getActionPathHash());
        }
        stringBuilder.append("\n");
        return stringBuilder.toString();
    }

}

