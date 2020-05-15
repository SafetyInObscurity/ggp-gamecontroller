package tud.gamecontroller.players.HyperPlayer;

import tud.gamecontroller.game.StateInterface;
import tud.gamecontroller.game.impl.JointMove;
import tud.gamecontroller.term.TermInterface;

import java.util.Collection;

public class StateTrackerTriple<TermType extends TermInterface> {

    private final Collection<TermType> percepts;
    private final JointMove<TermType> jointAction;
    private final StateInterface<TermType, ?> state;

    public StateTrackerTriple(Collection<TermType> percepts, JointMove<TermType> jointAction, StateInterface<TermType, ?> state) {
        this.percepts = percepts;
        this.jointAction = jointAction;
        this.state = state;
    }

    public Collection<TermType> getPercepts() {
        return percepts;
    }
    public JointMove<TermType> getJointAction() {
        return jointAction;
    }
    public StateInterface<TermType, ?> getState() {
        return state;
    }
}
