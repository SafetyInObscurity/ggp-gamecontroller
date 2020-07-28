package tud.gamecontroller.players.OPAnytimeHyperPlayer;

@SuppressWarnings("unchecked")
public class Tuple<A, B> {

    private A a;
    private B b;

    Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public A getA() { return this.a; }
    public B getB() { return this.b; }
}
