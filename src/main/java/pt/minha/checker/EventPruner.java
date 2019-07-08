package pt.minha.checker;

public interface EventPruner {
    // Should return the number of removed events
    long prune();
}
