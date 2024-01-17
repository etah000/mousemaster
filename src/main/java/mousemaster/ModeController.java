package mousemaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class ModeController implements GridListener, PositionHistoryListener {

    private static final Logger logger = LoggerFactory.getLogger(ModeController.class);

    private final ModeMap modeMap;
    private final MouseController mouseController;
    private final MouseState mouseState;
    private final KeyboardState keyboardState;
    private final List<ModeListener> listeners;
    private boolean currentModeCursorHidden;
    private Mode currentMode;
    private final Deque<Mode> modeHistoryStack = new ArrayDeque<>();
    private double modeTimeoutTimer;
    private double hideCursorIdleTimer;
    private boolean justSnappedToGrid;
    private boolean justCycledPosition;

    public ModeController(ModeMap modeMap, MouseController mouseController,
                          MouseState mouseState, KeyboardState keyboardState,
                          List<ModeListener> listeners) {
        this.modeMap = modeMap;
        this.mouseController = mouseController;
        this.mouseState = mouseState;
        this.keyboardState = keyboardState;
        this.listeners = listeners;
    }

    public void update(double delta) {
        if (keyboardState.pressingUnhandledKeysOnly()) {
            if (currentMode.modeAfterPressingUnhandledKeysOnly() != null) {
                logger.debug("All currently pressed keys are unhandled, switching to " +
                             currentMode.modeAfterPressingUnhandledKeysOnly());
                switchMode(currentMode.modeAfterPressingUnhandledKeysOnly());
                return;
            }
        }
        boolean mouseIdling = !mouseState.moving() && !mouseState.pressing() &&
                              !mouseState.wheeling() && !justSnappedToGrid &&
                              !justCycledPosition;
        boolean mustResetHideCursorTimeout = !mouseIdling;
        boolean mustResetModeTimeout = currentMode.timeout().onlyIfIdle() && !mouseIdling;
        justSnappedToGrid = false;
        justCycledPosition = false;
        if (mustResetHideCursorTimeout) {
            resetHideCursorTimer();
            resetCurrentModeCursorHidden();
        }
        else {
            if (currentMode.hideCursor().enabled() && !currentModeCursorHidden) {
                hideCursorIdleTimer -= delta;
                if (hideCursorIdleTimer <= 0) {
                    logger.debug("Hide cursor timer for " + currentMode.name() +
                                 " has elapsed");
                    currentModeCursorHidden = true;
                    mouseController.hideCursor();
                }
            }
        }
        if (mustResetModeTimeout) {
            resetModeTimeoutTimer();
        }
        else {
            if (currentMode.timeout().enabled()) {
                modeTimeoutTimer -= delta;
                if (modeTimeoutTimer <= 0) {
                    logger.debug("Current " + currentMode.name() +
                                 " has timed out, switching to " +
                                 currentMode.timeout().modeName());
                    listeners.forEach(ModeListener::modeTimedOut);
                    switchMode(currentMode.timeout().modeName());
                }
            }
        }
    }

    public void switchMode(String newModeName) {
        Mode newMode;
        Mode previousMode = modeHistoryStack.peek();
        if (newModeName.equals(Mode.PREVIOUS_MODE_FROM_HISTORY_STACK_IDENTIFIER)) {
            if (previousMode == null)
                throw new IllegalStateException(
                        "Unable to switch to previous mode as the mode history stack is empty");
            newMode = previousMode;
        }
        else
            newMode = modeMap.get(newModeName);
        if (modeHistoryStack.contains(newMode)) {
            while (!newMode.equals(modeHistoryStack.peek()))
                modeHistoryStack.poll();
            modeHistoryStack.poll();
        }
        else if (currentMode != null && currentMode.pushModeToHistoryStack() &&
                 !modeHistoryStack.contains(currentMode))
            modeHistoryStack.push(currentMode);
        currentMode = newMode;
        resetCurrentModeCursorHidden();
        resetHideCursorTimer();
        resetModeTimeoutTimer();
        listeners.forEach(listener -> listener.modeChanged(newMode));
    }

    private void resetCurrentModeCursorHidden() {
        if (currentModeCursorHidden) {
            if (!currentMode.hideCursor().enabled() ||
                !currentMode.hideCursor().idleDuration().equals(Duration.ZERO)) {
                mouseController.showCursor();
                currentModeCursorHidden = false;
            }
        }
        else {
            if (currentMode.hideCursor().enabled() &&
                currentMode.hideCursor().idleDuration().equals(Duration.ZERO)) {
                mouseController.hideCursor();
                currentModeCursorHidden = true;
            }
        }
    }

    private void resetModeTimeoutTimer() {
        if (currentMode.timeout().enabled())
            modeTimeoutTimer = currentMode.timeout().duration().toNanos() / 1e9d;
    }

    private void resetHideCursorTimer() {
        if (currentMode.hideCursor().enabled())
            hideCursorIdleTimer =
                    currentMode.hideCursor().idleDuration().toNanos() / 1e9d;
    }

    @Override
    public void snappedToGrid() {
        justSnappedToGrid = true;
    }

    @Override
    public void cycledPosition() {
        justCycledPosition = true;
    }
}
