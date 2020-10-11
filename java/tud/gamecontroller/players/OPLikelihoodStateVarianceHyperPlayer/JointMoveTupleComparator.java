package tud.gamecontroller.players.OPLikelihoodStateVarianceHyperPlayer;

import tud.gamecontroller.game.JointMoveInterface;
import tud.gamecontroller.term.TermInterface;

import java.util.Comparator;

public class JointMoveTupleComparator<TermType extends TermInterface> implements Comparator<Tuple<Double, JointMoveInterface<TermType>>> {

    // Overriding compare()method of Comparator
    // for descending order of cgpa
    public int compare(Tuple<Double, JointMoveInterface<TermType>> m1, Tuple<Double, JointMoveInterface<TermType>> m2) {
        if (m1.getA() < m2.getA())
            return 1;
        else if (m1.getA() > m2.getA())
            return -1;
        return 0;
    }
}
