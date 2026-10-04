package id.avalon.models;

public enum Role {

    MERLIN(true),
    PERCIVAL(true),
    LOYAL_SERVANT(true),

    ASSASSIN(false),
    MORGANA(false),
    MORDRED(false),
    OBERON(false),
    MINION_OF_MORDRED(false);

    private final boolean good;

    Role(boolean good) {
        this.good = good;
    }

    public boolean isGood() {
        return good;
    }

    public boolean isEvil() {
        return !good;
    }
}
