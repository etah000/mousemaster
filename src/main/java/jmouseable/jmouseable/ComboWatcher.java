package jmouseable.jmouseable;

import jmouseable.jmouseable.ComboMove.PressComboMove;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ComboWatcher {

    private static final Logger logger = LoggerFactory.getLogger(ComboWatcher.class);

    private final ModeManager modeManager;
    private final CommandRunner commandRunner;
    private ComboPreparation comboPreparation;
    private ComboMoveDuration previousComboMoveDuration;
    private List<ComboWaitingForLastMoveToComplete> combosWaitingForLastMoveToComplete = new ArrayList<>();

    /**
     * Do not start a new combo preparation if there are on going non-eaten pressed keys.
     * A non-eaten pressed key should prevent other combos: the combo containing the non-eaten pressed key must
     * be completed, or be interrupted ({@link #interrupt()}, or the non-eaten pressed key must be released.
     */
    private Map<Key, Set<Combo>> focusedCombos = new HashMap<>();
    private Set<Key> currentlyPressedComboKeys = new HashSet<>();

    public ComboWatcher(ModeManager modeManager, CommandRunner commandRunner) {
        this.modeManager = modeManager;
        this.commandRunner = commandRunner;
        this.comboPreparation = ComboPreparation.empty();
    }

    public void update(double delta) {
        if (modeManager.pollCurrentModeTimedOut())
            interrupt();
        List<ComboWaitingForLastMoveToComplete> completeCombos = new ArrayList<>();
        for (ComboWaitingForLastMoveToComplete comboWaitingForLastMoveToComplete : combosWaitingForLastMoveToComplete) {
            comboWaitingForLastMoveToComplete.remainingWait -= delta;
            if (comboWaitingForLastMoveToComplete.remainingWait < 0)
                completeCombos.add(comboWaitingForLastMoveToComplete);
        }
        List<Command> commandsToRun = longestComboCommandsLastAndDeduplicate(completeCombos.stream()
                                                                                           .map(ComboWaitingForLastMoveToComplete::comboAndCommands)
                                                                                           .toList());
        commandsToRun.forEach(commandRunner::run);
        combosWaitingForLastMoveToComplete.removeAll(completeCombos);
    }

    public PressKeyEventProcessing keyEvent(KeyEvent event) {
        if (!combosWaitingForLastMoveToComplete.isEmpty())
            combosWaitingForLastMoveToComplete.clear();
        KeyEvent previousEvent = comboPreparation.events().isEmpty() ? null :
                comboPreparation.events().getLast();
        if (previousEvent != null &&
            !previousComboMoveDuration.isRespected(previousEvent.time(), event.time()))
            comboPreparation = ComboPreparation.empty();
        if (event.isRelease()) {
            // The corresponding press event was part of a combo otherwise this method would
            // not have been called.
            currentlyPressedComboKeys.remove(event.key());
        }
        comboPreparation.events().add(event);
        boolean mustBeEaten = false;
        boolean partOfCombo = false;
        List<ComboAndCommands> comboAndCommandsToRun = new ArrayList<>();
        Mode currentMode = modeManager.currentMode();
        ComboMoveDuration newComboDuration = null;
        Set<Combo> allFocusedCombos = focusedCombos.values()
                                                   .stream()
                                                   .flatMap(Collection::stream)
                                                   .collect(Collectors.toSet());
        for (Map.Entry<Combo, List<Command>> entry : currentMode.comboMap()
                                                                .commandsByCombo()
                                                                .entrySet()) {
            Combo combo = entry.getKey();
            int matchingMoveCount = comboPreparation.matchingMoveCount(combo.sequence());
            if (matchingMoveCount == 0) {
                if (combo.sequence().moves().isEmpty() &&
                    !combo.precondition().isEmpty()) {
                    if (!combo.precondition().satisfied(currentlyPressedComboKeys)) {
                        continue;
                    }
                }
            }
            else {
                if (!allFocusedCombos.isEmpty() && !allFocusedCombos.contains(combo))
                    continue;
                if (!combo.precondition().isEmpty()) {
                    if (!combo.precondition().satisfied(currentlyPressedComboKeys))
                        continue;
                }
                ComboMove currentMove =
                        combo.sequence().moves().get(matchingMoveCount - 1);
                boolean currentMoveMustBeEaten =
                        currentMove instanceof PressComboMove pressComboMove &&
                        pressComboMove.eventMustBeEaten();
                mustBeEaten |= currentMoveMustBeEaten;
                if (!currentMoveMustBeEaten && currentMove.isPress())
                    focusedCombos.computeIfAbsent(currentMove.key(),
                            key -> new HashSet<>()).add(combo);
                partOfCombo = true;
                if (newComboDuration == null) {
                    newComboDuration = currentMove.duration();
                }
                else {
                    if (newComboDuration.min().compareTo(currentMove.duration().min()) >
                        0)
                        newComboDuration =
                                new ComboMoveDuration(currentMove.duration().min(),
                                        newComboDuration.max());
                    if (newComboDuration.max().compareTo(currentMove.duration().max()) <
                        0)
                        newComboDuration = new ComboMoveDuration(newComboDuration.min(),
                                currentMove.duration().max());
                }
            }
            boolean preparationComplete =
                    matchingMoveCount == combo.sequence().moves().size();
            if (!preparationComplete)
                continue;
            focusedCombos.values().forEach(combos -> combos.remove(combo));
            ComboAndCommands comboAndCommands =
                    new ComboAndCommands(combo, entry.getValue());
            ComboMove comboLastMove = combo.sequence().moves().isEmpty() ? null :
                    combo.sequence().moves().getLast();
            if (comboLastMove != null && !comboLastMove.duration().min().equals(Duration.ZERO)) {
                combosWaitingForLastMoveToComplete.add(
                        new ComboWaitingForLastMoveToComplete(comboAndCommands,
                                comboLastMove.duration().min().toNanos() / 1e9d));
            }
            else {
                comboAndCommandsToRun.add(comboAndCommands);
            }
        }
        if (newComboDuration != null)
            previousComboMoveDuration = newComboDuration;
        List<Command> commandsToRun = longestComboCommandsLastAndDeduplicate(comboAndCommandsToRun);
        logger.debug("currentMode = " + currentMode.name() + ", comboPreparation = " +
                     comboPreparation + ", partOfCombo = " + partOfCombo +
                     ", mustBeEaten = " + mustBeEaten +
                     ", commandsToRun = " + commandsToRun + ", focusedCombos = " +
                     focusedCombos);
        commandsToRun.forEach(commandRunner::run);
        if (!partOfCombo) {
            comboPreparation = ComboPreparation.empty();
            if (event.isRelease())
                focusedCombos.remove(event.key());
        }
        else {
            if (event.isPress())
                currentlyPressedComboKeys.add(event.key());
        }
        focusedCombos.remove(event.key());
        // Remove focused combos that are not in the new mode (if mode was changed),
        // as they will not be completed anymore.
        for (Set<Combo> focusedCombosForKey : focusedCombos.values()) {
            focusedCombosForKey.removeIf(combo -> !modeManager.currentMode()
                                                              .comboMap()
                                                              .commandsByCombo()
                                                              .containsKey(combo));
        }
        return event.isRelease() ? null :
                PressKeyEventProcessing.of(partOfCombo, mustBeEaten);
    }

    /**
     * Assuming the following configuration:
     * - +up: start move up
     * - -up|+up -up +up: stop move up
     * - +up -up +up: start wheel up
     * When up is pressed, the move starts. Then, when up is released then pressed,
     * the wheel starts.
     * The 3 combos are completed, but we ultimately want the move to stop,
     * i.e. the stop move command (+up -up +up) should be run after the
     * start move command (+up).
     * Also deduplicate commands: if start-move-up is +up|#rightctrl +up: holding rightctrl
     * then up should not trigger two commands.
     */
    private List<Command> longestComboCommandsLastAndDeduplicate(List<ComboAndCommands> commandsToRun) {
        return commandsToRun.stream()
                            .sorted(Comparator.comparing(ComboAndCommands::combo,
                                    Comparator.comparing(Combo::sequence,
                                            Comparator.comparingInt(
                                                    sequence -> sequence.moves()
                                                                        .size()))))
                            .map(ComboAndCommands::commands)
                            .flatMap(Collection::stream)
                            .distinct()
                            .toList();
    }

    public void interrupt() {
        logger.debug("Interrupting combos, comboPreparation = " + comboPreparation +
                     ", combosWaitingForLastMoveToComplete = " +
                     combosWaitingForLastMoveToComplete + ", focusedCombos = " +
                     focusedCombos);
        comboPreparation = ComboPreparation.empty();
        combosWaitingForLastMoveToComplete.clear();
        focusedCombos.clear();
        currentlyPressedComboKeys.clear();
    }

    private static final class ComboWaitingForLastMoveToComplete {
        private final ComboAndCommands comboAndCommands;
        private double remainingWait;

        private ComboWaitingForLastMoveToComplete(ComboAndCommands comboAndCommands,
                                                  double remainingWait) {
            this.comboAndCommands = comboAndCommands;
            this.remainingWait = remainingWait;
        }

        public ComboAndCommands comboAndCommands() {
            return comboAndCommands;
        }

        @Override
        public String toString() {
            return "ComboWaitingForLastMoveToComplete[" + "comboAndCommands=" +
                   comboAndCommands + ", remainingWait=" + remainingWait + ']';
        }
    }

    private record ComboAndCommands(Combo combo, List<Command> commands) {
    }

}
