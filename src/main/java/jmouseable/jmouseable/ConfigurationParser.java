package jmouseable.jmouseable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jmouseable.jmouseable.Command.*;

public class ConfigurationParser {

    public static Configuration parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        ComboMoveDuration defaultComboMoveDuration = defaultComboMoveDuration();
        Pattern modeKeyPattern = Pattern.compile("([^.]+-mode)\\.([^.]+)(\\.([^.]+))?");
        Map<String, Mode> modeByName = new HashMap<>();
        Set<String> modeNameReferences = new HashSet<>();
        for (String line : lines) {
            if (line.startsWith("#"))
                continue;
            if (!line.matches("[^=]+=[^=]+")) {
                throw new IllegalArgumentException("Invalid property key=value: " + line);
            }
            String propertyKey = line.split("=")[0];
            String propertyValue = line.split("=")[1];
            if (propertyKey.equals("default-combo-move-duration-millis")) {
                defaultComboMoveDuration = parseComboMoveDuration(propertyValue);
                continue;
            }
            Matcher matcher = modeKeyPattern.matcher(propertyKey);
            if (!matcher.matches())
                continue;
            String modeName = matcher.group(1);
            Mode mode = modeByName.computeIfAbsent(modeName, name -> newMode(modeName));
            Map<Combo, List<Command>> commandsByCombo = mode.comboMap().commandsByCombo();
            String group2 = matcher.group(2);
            switch (group2) {
                case "mouse" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid mouse configuration: " + propertyKey);
                    double initialVelocity = matcher.group(4).equals("initial-velocity") ?
                            Double.parseDouble(propertyValue) :
                            mode.mouse().initialVelocity();
                    double maxVelocity = matcher.group(4).equals("max-velocity") ?
                            Double.parseDouble(propertyValue) :
                            mode.mouse().maxVelocity();
                    double acceleration = matcher.group(4).equals("acceleration") ?
                            Double.parseDouble(propertyValue) :
                            mode.mouse().acceleration();
                    modeByName.put(modeName, new Mode(modeName, mode.comboMap(),
                            new Mouse(initialVelocity, maxVelocity, acceleration),
                            mode.wheel(), mode.attach(), mode.timeout(),
                            mode.indicator()));
                }
                case "wheel" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid wheel configuration: " + propertyKey);
                    double acceleration = matcher.group(4).equals("acceleration") ?
                            Double.parseDouble(propertyValue) :
                            mode.wheel().acceleration();
                    double initialVelocity = matcher.group(4).equals("initial-velocity") ?
                            Double.parseDouble(propertyValue) :
                            mode.wheel().initialVelocity();
                    double maxVelocity = matcher.group(4).equals("max-velocity") ?
                            Double.parseDouble(propertyValue) :
                            mode.wheel().maxVelocity();
                    modeByName.put(modeName,
                            new Mode(modeName, mode.comboMap(), mode.mouse(),
                                    new Wheel(initialVelocity, maxVelocity, acceleration),
                                    mode.attach(), mode.timeout(), mode.indicator()));
                }
                case "attach" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid attach configuration: " + propertyKey);
                    int gridRowCount = matcher.group(4).equals("grid-row-count") ?
                            Integer.parseUnsignedInt(propertyValue) :
                            mode.attach().gridRowCount();
                    int gridColumnCount = matcher.group(4).equals("grid-row-count") ?
                            Integer.parseUnsignedInt(propertyValue) :
                            mode.attach().gridColumnCount();
                    modeByName.put(modeName,
                            new Mode(modeName, mode.comboMap(), mode.mouse(), mode.wheel(),
                                    new Attach(gridRowCount, gridColumnCount), mode.timeout(), mode.indicator()));
                }
                case "to" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid to configuration: " + propertyKey);
                    String newModeName = matcher.group(4);
                    modeNameReferences.add(newModeName);
                    addCommand(commandsByCombo, propertyValue,
                            new ChangeMode(newModeName), defaultComboMoveDuration);
                }
                case "timeout" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid timeout configuration: " + propertyKey);
                    ModeTimeout modeTimeout = switch (matcher.group(4)) {
                        case "duration-millis" ->
                                new ModeTimeout(parseDuration(propertyValue),
                                        mode.timeout() == null ? null :
                                                mode.timeout().nextModeName());
                        case "next-mode" -> new ModeTimeout(
                                mode.timeout() == null ? null : mode.timeout().duration(),
                                propertyValue);
                        default -> throw new IllegalArgumentException(
                                "Invalid timeout configuration: " + propertyKey);
                    };
                    if (modeTimeout.nextModeName() != null)
                        modeNameReferences.add(modeTimeout.nextModeName());
                    modeByName.put(modeName,
                            new Mode(modeName, mode.comboMap(), mode.mouse(),
                                    mode.wheel(), mode.attach(), modeTimeout,
                                    mode.indicator()));
                }
                case "indicator" -> {
                    if (matcher.group(4) == null)
                        throw new IllegalArgumentException(
                                "Invalid indicator configuration: " + propertyKey);
                    Indicator indicator = switch (matcher.group(4)) {
                        case "enabled" ->
                                new Indicator(Boolean.parseBoolean(propertyValue),
                                        mode.indicator().idleHexColor(),
                                        mode.indicator().moveHexColor(),
                                        mode.indicator().wheelHexColor(),
                                        mode.indicator().mousePressHexColor(),
                                        mode.indicator().nonComboKeyPressHexColor());
                        case "idle-color" -> {
                            checkColorFormat(propertyValue);
                            yield new Indicator(mode.indicator().enabled(), propertyValue,
                                    mode.indicator().moveHexColor(),
                                    mode.indicator().wheelHexColor(),
                                    mode.indicator().mousePressHexColor(),
                                    mode.indicator().nonComboKeyPressHexColor());
                        }
                        case "move-color" -> {
                            checkColorFormat(propertyValue);
                            yield new Indicator(mode.indicator().enabled(),
                                    mode.indicator().idleHexColor(), propertyValue,
                                    mode.indicator().wheelHexColor(),
                                    mode.indicator().mousePressHexColor(),
                                    mode.indicator().nonComboKeyPressHexColor());
                        }
                        case "wheel-color" -> {
                            checkColorFormat(propertyValue);
                            yield new Indicator(mode.indicator().enabled(),
                                    mode.indicator().idleHexColor(),
                                    mode.indicator().moveHexColor(), propertyValue,
                                    mode.indicator().mousePressHexColor(),
                                    mode.indicator().nonComboKeyPressHexColor());
                        }
                        case "mouse-press-color" -> {
                            checkColorFormat(propertyValue);
                            yield new Indicator(mode.indicator().enabled(),
                                    mode.indicator().idleHexColor(),
                                    mode.indicator().moveHexColor(),
                                    mode.indicator().wheelHexColor(), propertyValue,
                                    mode.indicator().nonComboKeyPressHexColor());
                        }
                        case "non-combo-key-press-color" -> {
                            checkColorFormat(propertyValue);
                            yield new Indicator(mode.indicator().enabled(),
                                    mode.indicator().idleHexColor(),
                                    mode.indicator().moveHexColor(),
                                    mode.indicator().wheelHexColor(),
                                    mode.indicator().mousePressHexColor(), propertyValue);
                        }
                        default -> throw new IllegalArgumentException(
                                "Invalid indicator configuration: " + propertyKey);
                    };
                    modeByName.put(modeName,
                            new Mode(modeName, mode.comboMap(), mode.mouse(),
                                    mode.wheel(), mode.attach(), mode.timeout(),
                                    indicator));
                }
                // @formatter:off
                    case "start-move-up" -> addCommand(commandsByCombo, propertyValue, new StartMoveUp(), defaultComboMoveDuration);
                    case "start-move-down" -> addCommand(commandsByCombo, propertyValue, new StartMoveDown(), defaultComboMoveDuration);
                    case "start-move-left" -> addCommand(commandsByCombo, propertyValue, new StartMoveLeft(), defaultComboMoveDuration);
                    case "start-move-right" -> addCommand(commandsByCombo, propertyValue, new StartMoveRight(), defaultComboMoveDuration);

                    case "stop-move-up" -> addCommand(commandsByCombo, propertyValue, new StopMoveUp(), defaultComboMoveDuration);
                    case "stop-move-down" -> addCommand(commandsByCombo, propertyValue, new StopMoveDown(), defaultComboMoveDuration);
                    case "stop-move-left" -> addCommand(commandsByCombo, propertyValue, new StopMoveLeft(), defaultComboMoveDuration);
                    case "stop-move-right" -> addCommand(commandsByCombo, propertyValue, new StopMoveRight(), defaultComboMoveDuration);

                    case "press-left" -> addCommand(commandsByCombo, propertyValue, new PressLeft(), defaultComboMoveDuration);
                    case "press-middle" -> addCommand(commandsByCombo, propertyValue, new PressMiddle(), defaultComboMoveDuration);
                    case "press-right" -> addCommand(commandsByCombo, propertyValue, new PressRight(), defaultComboMoveDuration);

                    case "release-left" -> addCommand(commandsByCombo, propertyValue, new ReleaseLeft(), defaultComboMoveDuration);
                    case "release-middle" -> addCommand(commandsByCombo, propertyValue, new ReleaseMiddle(), defaultComboMoveDuration);
                    case "release-right" -> addCommand(commandsByCombo, propertyValue, new ReleaseRight(), defaultComboMoveDuration);

                    case "start-wheel-up" -> addCommand(commandsByCombo, propertyValue, new StartWheelUp(), defaultComboMoveDuration);
                    case "start-wheel-down" -> addCommand(commandsByCombo, propertyValue, new StartWheelDown(), defaultComboMoveDuration);
                    case "start-wheel-left" -> addCommand(commandsByCombo, propertyValue, new StartWheelLeft(), defaultComboMoveDuration);
                    case "start-wheel-right" -> addCommand(commandsByCombo, propertyValue, new StartWheelRight(), defaultComboMoveDuration);

                    case "stop-wheel-up" -> addCommand(commandsByCombo, propertyValue, new StopWheelUp(), defaultComboMoveDuration);
                    case "stop-wheel-down" -> addCommand(commandsByCombo, propertyValue, new StopWheelDown(), defaultComboMoveDuration);
                    case "stop-wheel-left" -> addCommand(commandsByCombo, propertyValue, new StopWheelLeft(), defaultComboMoveDuration);
                    case "stop-wheel-right" -> addCommand(commandsByCombo, propertyValue, new StopWheelRight(), defaultComboMoveDuration);

                    case "attach-up" -> addCommand(commandsByCombo, propertyValue, new AttachUp(), defaultComboMoveDuration);
                    case "attach-down" -> addCommand(commandsByCombo, propertyValue, new AttachDown(), defaultComboMoveDuration);
                    case "attach-left" -> addCommand(commandsByCombo, propertyValue, new AttachLeft(), defaultComboMoveDuration);
                    case "attach-right" -> addCommand(commandsByCombo, propertyValue, new AttachRight(), defaultComboMoveDuration);
                    // @formatter:on
                default -> throw new IllegalArgumentException(
                        "Invalid configuration: " + propertyKey);
            }
        }
        // Verify mode name references are valid.
        for (String modeNameReference : modeNameReferences)
            if (!modeByName.containsKey(modeNameReference))
                throw new IllegalStateException(
                        "Definition of mode " + modeNameReference + " is missing");
        for (Mode mode : modeByName.values()) {
            if (mode.timeout() != null && (mode.timeout().duration() == null ||
                                           mode.timeout().nextModeName() == null))
                throw new IllegalStateException(
                        "Definition of mode timeout for " + mode.name() +
                        " is incomplete");
        }
        return new Configuration(new ModeMap(Set.copyOf(modeByName.values())));
    }

    private static void checkColorFormat(String propertyValue) {
        if (!propertyValue.matches("^#?([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$"))
            throw new IllegalArgumentException("Invalid hex color: " + propertyValue);
    }

    private static ComboMoveDuration parseComboMoveDuration(String string) {
        String[] split = string.split("-");
        return new ComboMoveDuration(
                Duration.ofMillis(Integer.parseUnsignedInt(split[0])),
                Duration.ofMillis(Integer.parseUnsignedInt(split[1])));
    }

    private static Duration parseDuration(String string) {
        return Duration.ofMillis(Integer.parseUnsignedInt(string));
    }

    private static ComboMoveDuration defaultComboMoveDuration() {
        return new ComboMoveDuration(Duration.ZERO, Duration.ofMillis(150));
    }

    private static Mode newMode(String modeName) {
        return new Mode(modeName, new ComboMap(new HashMap<>()),
                new Mouse(200, 1000, 1500), new Wheel(500, 1000, 500), new Attach(1, 1),
                null, new Indicator(false, null, null, null, null, null));
    }

    private static void addCommand(Map<Combo, List<Command>> commandsByCombo,
                                   String multiComboString, Command command,
                                   ComboMoveDuration defaultComboMoveDuration) {
        List<Combo> combos = Combo.multiCombo(multiComboString, defaultComboMoveDuration);
        for (Combo combo : combos)
            commandsByCombo.computeIfAbsent(combo, combo1 -> new ArrayList<>())
                           .add(command);
    }

}
