package jmouseable.jmouseable;

import java.util.Set;
import java.util.stream.Collectors;

public record ComboPrecondition(Set<Set<Key>> mustNotBePressedKeySets,
                                Set<Set<Key>> mustBePressedKeySets) {

    public boolean isEmpty() {
        return mustNotBePressedKeySets.isEmpty() && mustBePressedKeySets.isEmpty();
    }

    public boolean satisfied(Set<Key> currentlyPressedKeys) {
        for (Set<Key> mustNotBePressedKeySet : mustNotBePressedKeySets) {
            if (currentlyPressedKeys.containsAll(mustNotBePressedKeySet))
                return false;
        }
        if (mustBePressedKeySets.isEmpty())
            return true;
        for (Set<Key> mustBePressedKeySet : mustBePressedKeySets) {
            if (currentlyPressedKeys.containsAll(mustBePressedKeySet))
                return true;
        }
        return false;
    }

    public boolean isMustBePressedKey(Key key) {
        for (Set<Key> mustBePressedKeySet : mustBePressedKeySets) {
            if (mustBePressedKeySet.contains(key))
                return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.join(" ", "^{" + keySetsToString(mustNotBePressedKeySets) + "}",
                "_{" + keySetsToString(mustBePressedKeySets) + "}");
    }

    private static String keySetsToString(Set<Set<Key>> keySets) {
        return keySets.stream()
                      .map(keySet -> keySet.stream()
                                           .map(Key::name)
                                           .collect(Collectors.joining(" ")))
                      .collect(Collectors.joining("|"));
    }
}
