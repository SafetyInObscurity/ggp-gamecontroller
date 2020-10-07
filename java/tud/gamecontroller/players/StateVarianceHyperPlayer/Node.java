package tud.gamecontroller.players.StateVarianceHyperPlayer;

import java.util.ArrayList;

public class Node {

    // Instance Variables
    private int value;
    private int actionPathHash;
    private ArrayList<Node> children;

    /**
     * Instantiates a Node object with 0 children
     */
    public Node(int actionPathHash) {
        this.value = 0;
        this.actionPathHash = actionPathHash;
        this.children = new ArrayList<Node>();
    }

    public int getValue() { return this.value; }
    public int getActionPathHash() { return this.actionPathHash; }
    public ArrayList<Node> getChildren() { return this.children; }
    public Node getChild(int actionPathHash) {
        for (Node node : this.children) {
            if(node.getActionPathHash() == actionPathHash) {
                return node;
            }
        }
        return null;
    }

    public void setValue(int value) {
        this.value = value;
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
            this.children.add(child);
        }
    }

    /**
     * Removes the child from this Node's children
     *
     * @param child A Node to remove from this Node's children
     */
    public void removeChild(Node child) {
        this.children.remove(child);
    }

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

