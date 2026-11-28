package com.example.addon;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BlockLoopModule extends Module {
    private static final int CATEGORY_COUNT = 4;

    private static final class PathPoint {
        final BlockPos walk;
        BlockPos look;

        PathPoint(BlockPos walk, BlockPos look) {
            this.walk = walk;
            this.look = look;
        }
    }

    // ---- Zustand ----
    private final List<List<PathPoint>> categories = new ArrayList<>();
    private int activeCategory = 0;
    private int currentPoint = 0;
    private boolean running = false;
    private boolean walking = true;
    private boolean goalSet = false;
    private int lookTicks = 0;
    private int postLookWaitTicks = 0;
    private boolean lookFinished = false;
    private int announcedPoint = -1;
    private boolean haveLastPos = false;
    private boolean writingSetting = false;

    private double lastX, lastZ, lastPlayerY;
    private boolean initialCenterPending;
    private boolean descentFreeze;
    private float descentYaw, descentPitch;
    private double postDescentDistance;

    // ---- Einstellungen ----
    private final SettingGroup sgControl = settings.createGroup("Steuerung");
    private final SettingGroup[] sgCategories = new SettingGroup[CATEGORY_COUNT];

    private final Setting<Boolean> rightClickHold;
    private final Setting<Boolean> autoBreak;
    private final Setting<Boolean> freezeOnDescent;
    private final Setting<Boolean> freezeWhenTargetLower;
    private final Setting<Double> descentHoldDistance;
    private final Setting<Boolean> baritoneFreeLook;
    private final Setting<Integer> selectedCategory;
    private final Setting<Double> rotationSpeed;
    private final Setting<Double> rotationSmoothing;
    private final Setting<Keybind> saveWalkKey;
    private final Setting<Keybind> saveLookKey;
    private final Setting<Keybind> toggleLoopKey;

    private final Setting<String>[] categoryNames;
    private final Setting<List<String>>[] savedPoints;
    private final Setting<Double>[] lookWaitSeconds;

    @SuppressWarnings("unchecked")
    public BlockLoopModule() {
        super(AddonTemplate.CATEGORY, "block-loop",
            "Abarbeitung der gespeicherten Punkte in fester Reihenfolge: Laufen + Stehen, Angucken, 0,9 Sekunden warten, nächster Punkt.");

        categoryNames = (Setting<String>[]) new Setting[CATEGORY_COUNT];
        savedPoints = (Setting<List<String>>[]) new Setting[CATEGORY_COUNT];
        lookWaitSeconds = (Setting<Double>[]) new Setting[CATEGORY_COUNT];

        for (int i = 0; i < CATEGORY_COUNT; i++) categories.add(new ArrayList<>());

        rightClickHold = sgControl.add(new BoolSetting.Builder()
            .name("right-click-hold")
            .description("Beim Warten am Look-Punkt Rechtsklick dauerhaft gedrückt halten.")
            .defaultValue(false)
            .build());

        autoBreak = sgControl.add(new BoolSetting.Builder()
            .name("auto-break")
            .description("Baut den angesehenen Block direkt ab, auch bei offenem Menü (ESC, Inventar). Funktioniert nur auf Servern, im Einzelspieler pausiert das Spiel im Menü.")
            .defaultValue(true)
            .build());

        freezeOnDescent = sgControl.add(new BoolSetting.Builder()
            .name("freeze-on-descent")
            .description("Beim Runtergehen/Fallen die Blickrichtung festhalten, statt der Bewegung zu folgen.")
            .defaultValue(true)
            .build());

        freezeWhenTargetLower = sgControl.add(new BoolSetting.Builder()
            .name("freeze-when-target-lower")
            .description("Blickrichtung schon festhalten, sobald das Ziel tiefer liegt als du. Sonst erst, wenn du wirklich fällst.")
            .defaultValue(true)
            .build());

        descentHoldDistance = sgControl.add(new DoubleSetting.Builder()
            .name("descent-hold-distance")
            .description("Wie viele Blöcke nach der Landung die Blickrichtung noch festgehalten wird.")
            .defaultValue(3.0)
            .min(0.0)
            .max(10.0)
            .sliderRange(0.0, 10.0)
            .build());

        baritoneFreeLook = sgControl.add(new BoolSetting.Builder()
            .name("baritone-free-look")
            .description("Baritone dreht nur serverseitig, nicht deine Kamera (verhindert Ruckeln). Wird beim Loop-Start gesetzt.")
            .defaultValue(true)
            .build());

        selectedCategory = sgControl.add(new IntSetting.Builder()
            .name("selected-category")
            .description("Aktive Kategorie 1-4.")
            .defaultValue(1)
            .min(1)
            .max(CATEGORY_COUNT)
            .sliderRange(1, CATEGORY_COUNT)
            .build());

        rotationSpeed = sgControl.add(new DoubleSetting.Builder()
            .name("rotation-speed")
            .description("Maximale Drehung pro Tick beim sanften Ausrichten.")
            .defaultValue(10.0)
            .min(0.0)
            .max(20.0)
            .sliderRange(0.0, 20.0)
            .build());

        rotationSmoothing = sgControl.add(new DoubleSetting.Builder()
            .name("rotation-smoothing")
            .description("Wie weich die Kamera zum Ziel dreht.")
            .defaultValue(0.5)
            .min(0.0)
            .max(2.0)
            .sliderRange(0.0, 2.0)
            .build());

        saveWalkKey = sgControl.add(new KeybindSetting.Builder()
            .name("save-walk-stand")
            .description("Speichert die aktuelle Blockposition als Lauf + Stehen Punkt.")
            .defaultValue(Keybind.none())
            .action(this::saveWalkPoint)
            .build());

        saveLookKey = sgControl.add(new KeybindSetting.Builder()
            .name("save-look")
            .description("Speichert den aktuell angesehenen Block als Angucken-Ziel für den zuletzt gespeicherten Punkt.")
            .defaultValue(Keybind.none())
            .action(this::saveLookPoint)
            .build());

        toggleLoopKey = sgControl.add(new KeybindSetting.Builder()
            .name("toggle-loop")
            .description("Startet/stoppt das Abfahren der ausgewählten Kategorie.")
            .defaultValue(Keybind.none())
            .action(this::toggleLoop)
            .build());

        String[] defaultNames = {"Eisinsel", "Pirateninsel", "Wüsteninsel", "Dschungelinsel"};

        for (int i = 0; i < CATEGORY_COUNT; i++) {
            final int index = i;
            sgCategories[i] = settings.createGroup("Kategorie " + (i + 1));

            categoryNames[i] = sgCategories[i].add(new StringSetting.Builder()
                .name("name")
                .description("Name der Kategorie. Kann jederzeit geändert werden.")
                .defaultValue(defaultNames[i])
                .build());

            savedPoints[i] = sgCategories[i].add(new StringListSetting.Builder()
                .name("saved-points")
                .description("Lauf + Stehen und Angucken Punkte dieser Kategorie. Der Reset unten löscht alles dieser Kategorie.")
                .defaultValue(new ArrayList<>())
                .onChanged(list -> {
                    if (writingSetting) return;
                    loadCategory(index);
                    if (index == activeCategory && categories.get(index).isEmpty()) {
                        stopLoop();
                        resetTraversal();
                    }
                })
                .build());

            lookWaitSeconds[i] = sgCategories[i].add(new DoubleSetting.Builder()
                .name("wait-time")
                .description("Wartezeit nach dem Angucken in Sekunden (0,0 bis 10,0).")
                .defaultValue(0.7)
                .min(0.0)
                .max(10.0)
                .sliderRange(0.0, 10.0)
                .build());
        }
    }

    // ---- Lebenszyklus ----

    @Override
    public void onActivate() {
        syncActiveCategory();
        currentPoint = 0;
        running = false;
        walking = true;
        goalSet = false;
        lookTicks = 0;
        postLookWaitTicks = 0;
        lookFinished = false;
        announcedPoint = -1;
        haveLastPos = false;
        info("Block Loop bereit. Aktive Kategorie: %d (%s).", activeCategory + 1, categoryNames[activeCategory].get());
    }

    @Override
    public void onDeactivate() {
        releaseRightClick();
        stopLoop();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        syncActiveCategory();
        if (!running) return;

        List<PathPoint> list = categories.get(activeCategory);
        if (list.isEmpty()) {
            info("Kategorie %d ist leer.", activeCategory + 1);
            stopLoop();
            return;
        }
        if (currentPoint >= list.size()) currentPoint = 0;

        PathPoint point = list.get(currentPoint);
        if (announcedPoint != currentPoint) {
            announcedPoint = currentPoint;
            info("Punkt %d/%d: Laufen + Stehen zu %s.", currentPoint + 1, list.size(), point.walk.toShortString());
        }

        if (walking) tickWalking(point, player);
        else tickLooking(point, player);
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        // Gefrorene Blickrichtung schon vor dem Spieler-Tick setzen, damit die Kamera nicht zwischen zwei Werten ruckelt.
        LocalPlayer player = mc.player;
        if (player == null || !running || !walking || !descentFreeze) return;
        player.setYRot(descentYaw);
        player.setXRot(descentPitch);
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        // Beim Start einmal direkt zur Blockmitte des aktuellen Angucken-Ziels drehen.
        if (running && initialCenterPending) {
            LocalPlayer player = mc.player;
            List<PathPoint> current = categories.get(activeCategory);
            if (player != null && currentPoint < current.size()) {
                BlockPos look = current.get(currentPoint).look;
                if (look != null) {
                    player.setYRot(yawTo(look, player));
                    player.setXRot(pitchTo(look, player));
                    initialCenterPending = false;
                }
            }
        }

        // LED-Markierung der Angucken-Blöcke.
        for (PathPoint point : categories.get(activeCategory)) {
            if (point.look != null) {
                event.renderer.box(point.look,
                    new SettingColor(0, 255, 255, 0),
                    new SettingColor(0, 255, 255, 255),
                    ShapeMode.Lines, 0);
            }
        }
    }

    // ---- Laufen / Angucken ----

    private void tickWalking(PathPoint point, LocalPlayer player) {
        double dx = point.walk.getX() + 0.5 - player.getX();
        double dy = point.walk.getY() + 0.5 - player.getEyeY();
        double dz = point.walk.getZ() + 0.5 - player.getZ();
        double distSq = dx * dx + dz * dz;

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();

        if (distSq <= 1.0 && Math.abs(dy) <= 2.0) {
            if (goalSet) goalProcess.setGoal(null);
            goalSet = false;
            walking = false;
            lookTicks = 0;
            postLookWaitTicks = 0;
            lookFinished = false;
            descentFreeze = false;
            postDescentDistance = 0.0;
            info("Punkt %d/%d erreicht -> Angucken wird aktiviert.", currentPoint + 1, categories.get(activeCategory).size());

            lastPlayerY = player.getEyeY();
            haveLastPos = true;
            return;
        }

        if (!goalSet) {
            goalProcess.setGoalAndPath(new GoalBlock(point.walk));
            goalSet = true;
            haveLastPos = false;
        }

        if (haveLastPos) updateViewWhileWalking(point, player);
        lastX = player.getX();
        lastZ = player.getZ();
        lastPlayerY = player.getEyeY();
        haveLastPos = true;
    }

    private void tickLooking(PathPoint point, LocalPlayer player) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        ICustomGoalProcess goalProcess = baritone.getCustomGoalProcess();
        if (goalSet) goalProcess.setGoal(null);
        goalSet = false;

        smoothLookAt(point.look, player);
        lookTicks++;

        int size = categories.get(activeCategory).size();
        if (lookTicks == 1) {
            info("Punkt %d/%d: Angucken -> %s.", currentPoint + 1, size, point.look.toShortString());
        }

        float yawDiff = Math.abs(wrap(yawTo(point.look, player) - player.getYRot()));
        float pitchDiff = Math.abs(pitchTo(point.look, player) - player.getXRot());

        if (!lookFinished && yawDiff <= 2.0f && pitchDiff <= 2.0f) {
            lookFinished = true;
            postLookWaitTicks = 0;
            info("Punkt %d/%d: Angucken fertig -> eingestellte Wartezeit warten.", currentPoint + 1, size);
        }

        if (!lookFinished) return;

        tickRightClick(point, player);
        postLookWaitTicks++;

        if (postLookWaitTicks >= getLookWaitTicks()) {
            info("Punkt %d/%d: Wartezeit vorbei -> nächster Punkt.", currentPoint + 1, size);
            mc.options.keyAttack.setDown(false);
            advancePoint();
        }
    }

    private void advancePoint() {
        releaseRightClick();
        currentPoint++;
        if (currentPoint >= categories.get(activeCategory).size()) {
            currentPoint = 0;
            info("Kategorie %d komplett abgefahren -> wieder von vorne.", activeCategory + 1);
        }
        walking = true;
        goalSet = false;
        lookTicks = 0;
        postLookWaitTicks = 0;
        lookFinished = false;
        announcedPoint = -1;
        haveLastPos = false;
        descentFreeze = false;
        postDescentDistance = 0.0;
    }

    /**
     * Beim Laufen in Bewegungsrichtung drehen. Beim Runtergehen oder Fallen wird die Blickrichtung
     * dagegen festgehalten (und noch ein Stück nach der Landung), damit die Kamera nicht herumzuckt.
     */
    private void updateViewWhileWalking(PathPoint point, LocalPlayer player) {
        double dx = player.getX() - lastX;
        double dz = player.getZ() - lastZ;
        double dy = player.getEyeY() - lastPlayerY;

        boolean dropping = dy < -0.03;
        boolean targetLower = freezeWhenTargetLower.get() && point.walk.getY() < player.blockPosition().getY();

        if (freezeOnDescent.get() && (dropping || targetLower)) {
            if (!descentFreeze) {
                descentYaw = player.getYRot();
                descentPitch = player.getXRot();
                descentFreeze = true;
            }
            postDescentDistance = 0.0;
            player.setYRot(descentYaw);
            player.setXRot(descentPitch);
            return;
        }

        if (descentFreeze) {
            postDescentDistance += Math.sqrt(dx * dx + dz * dz);
            if (postDescentDistance < descentHoldDistance.get()) {
                player.setYRot(descentYaw);
                player.setXRot(descentPitch);
                return;
            }
            descentFreeze = false;
        }

        if (dx * dx + dz * dz >= 4.0E-4) {
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            rotateYaw((float) targetYaw, player);
        }
    }

    // ---- Rotation ----

    private void smoothLookAt(BlockPos pos, LocalPlayer player) {
        rotateYawPitch(yawTo(pos, player), pitchTo(pos, player), player);
    }

    private float yawTo(BlockPos pos, LocalPlayer player) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    private float pitchTo(BlockPos pos, LocalPlayer player) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0E-4)));
    }

    private float smoothStep(float delta) {
        float step = delta * rotationSmoothing.get().floatValue();
        float max = Math.max(0.01f, rotationSpeed.get().floatValue());
        if (Math.abs(step) > max) step = Math.copySign(max, step);
        if (Math.abs(delta) > 0.05f && Math.abs(step) < 0.05f) step = Math.copySign(0.05f, delta);
        return step;
    }

    private void rotateYaw(float targetYaw, LocalPlayer player) {
        float yaw = player.getYRot();
        player.setYRot(yaw + smoothStep(wrap(targetYaw - yaw)));
    }

    private void rotateYawPitch(float targetYaw, float targetPitch, LocalPlayer player) {
        rotateYaw(targetYaw, player);
        float pitch = player.getXRot();
        float newPitch = pitch + smoothStep(targetPitch - pitch);
        player.setXRot(Math.max(-90.0f, Math.min(90.0f, newPitch)));
    }

    private float wrap(float angle) {
        return Mth.wrapDegrees(angle);
    }

    // ---- Punkte speichern / laden ----

    public void saveWalkPoint() {
        if (mc.player == null) return;
        syncActiveCategory();
        BlockPos pos = mc.player.blockPosition();

        List<PathPoint> list = categories.get(activeCategory);
        list.add(new PathPoint(pos, pos));
        writeCategory(activeCategory);
        info("Laufen + Stehen gespeichert: Kategorie %d (%s) -> %s. Punkt %d.",
            activeCategory + 1, categoryNames[activeCategory].get(), pos.toShortString(), list.size());
    }

    public void saveLookPoint() {
        syncActiveCategory();
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) {
            info("Kein Block angesehen.");
            return;
        }

        List<PathPoint> list = categories.get(activeCategory);
        if (list.isEmpty()) {
            info("Bitte zuerst einen Laufen + Stehen Punkt speichern.");
            return;
        }

        PathPoint last = list.get(list.size() - 1);
        last.look = blockHit.getBlockPos().immutable();
        writeCategory(activeCategory);
        info("Angucken gespeichert: Kategorie %d (%s) -> %s für Punkt %d.",
            activeCategory + 1, categoryNames[activeCategory].get(), last.look.toShortString(), list.size());
    }

    private void writeCategory(int index) {
        List<String> stored = savedPoints[index].get();
        stored.clear();
        writingSetting = true;
        for (PathPoint point : categories.get(index)) stored.add(encode(point));
        savedPoints[index].onChanged();
        writingSetting = false;
    }

    private String encode(PathPoint point) {
        return "Laufen + Stehen: " + point.walk.toShortString() + " | Angucken: " + point.look.toShortString();
    }

    private PathPoint decode(String text) {
        if (text == null) return null;
        String[] parts = text.split("\\|");

        if (parts.length == 1) {
            BlockPos pos = parsePos(parts[0]);
            return pos == null ? null : new PathPoint(pos, pos);
        }

        String walkPart = parts[0];
        String lookPart = parts[1];
        int i = walkPart.indexOf(':');
        int j = lookPart.indexOf(':');
        if (i >= 0) walkPart = walkPart.substring(i + 1);
        if (j >= 0) lookPart = lookPart.substring(j + 1);

        BlockPos walk = parsePos(walkPart);
        BlockPos look = parsePos(lookPart);
        if (walk == null) return null;
        if (look == null) look = walk;
        return new PathPoint(walk, look);
    }

    private BlockPos parsePos(String text) {
        String[] parts = text.trim().replace(" ", "").split(",");
        if (parts.length < 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void syncActiveCategory() {
        int selected = selectedCategory.get();
        int index = Math.max(1, Math.min(CATEGORY_COUNT, selected)) - 1;

        if (index != activeCategory) {
            activeCategory = index;
            loadCategory(activeCategory);
            resetTraversal();
            info("Aktive Kategorie: %d (%s).", activeCategory + 1, categoryNames[activeCategory].get());
        } else if (categories.get(activeCategory).isEmpty() && !savedPoints[activeCategory].get().isEmpty()) {
            loadCategory(activeCategory);
        }
    }

    private void loadCategory(int index) {
        List<PathPoint> list = categories.get(index);
        list.clear();
        for (String text : savedPoints[index].get()) {
            PathPoint point = decode(text);
            if (point != null) list.add(point);
        }
    }

    private void resetTraversal() {
        currentPoint = 0;
        walking = true;
        goalSet = false;
        lookTicks = 0;
        postLookWaitTicks = 0;
        lookFinished = false;
        announcedPoint = -1;
        haveLastPos = false;
        initialCenterPending = true;
        descentFreeze = false;
        postDescentDistance = 0.0;
    }

    // ---- Steuerung (auch vom Command genutzt) ----

    public void toggleLoop() {
        syncActiveCategory();
        if (running) {
            stopLoop();
            info("Block Loop gestoppt.");
            return;
        }
        if (categories.get(activeCategory).isEmpty()) {
            info("Kategorie %d ist leer.", activeCategory + 1);
            return;
        }
        resetTraversal();
        applyBaritoneFreeLook();
        running = true;
        info("Block Loop gestartet: Kategorie %d (%s), %d Punkte.",
            activeCategory + 1, categoryNames[activeCategory].get(), categories.get(activeCategory).size());
    }

    private void applyBaritoneFreeLook() {
        try {
            BaritoneAPI.getSettings().freeLook.value = baritoneFreeLook.get();
        } catch (Throwable ignored) {
        }
    }

    public void startLoop() {
        if (!running) toggleLoop();
    }

    public void stopLoop() {
        try {
            if (goalSet) BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
        } catch (Throwable ignored) {
        }
        running = false;
        goalSet = false;
        walking = true;
        lookTicks = 0;
        if (mc.options != null) mc.options.keyAttack.setDown(false);
    }

    public void clearBlocks() {
        syncActiveCategory();
        List<PathPoint> list = categories.get(activeCategory);
        int removed = list.size();
        list.clear();
        savedPoints[activeCategory].reset();
        if (running) stopLoop();
        info("Kategorie %d (%s) zurückgesetzt. %d Punkte gelöscht.",
            activeCategory + 1, categoryNames[activeCategory].get(), removed);
    }

    public void selectTarget() {
        saveWalkPoint();
    }

    public List<BlockPos> getBlocks() {
        syncActiveCategory();
        List<BlockPos> result = new ArrayList<>();
        for (PathPoint point : categories.get(activeCategory)) result.add(point.walk);
        return result;
    }

    private int getLookWaitTicks() {
        return (int) Math.round(lookWaitSeconds[activeCategory].get() * 20.0);
    }

    // ---- Klicks ----

    private void tickRightClick(PathPoint point, LocalPlayer player) {
        mc.options.keyUse.setDown(rightClickHold.get());

        if (autoBreak.get()) {
            // Direkt abbauen, ohne Taste und ohne Fadenkreuz: läuft auch bei offenem Menü.
            mc.options.keyAttack.setDown(false);
            if (mc.gameMode != null && mc.level != null
                && mc.gameMode.continueDestroyBlock(point.look, Direction.DOWN)) {
                player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            // Altes Verhalten: Angriffstaste halten (wirkt nur ohne offenes Menü).
            mc.options.keyAttack.setDown(true);
            if (mc.gameMode != null) mc.gameMode.continueDestroyBlock(point.look, Direction.DOWN);
        }
    }

    private void releaseRightClick() {
        if (mc.options != null) mc.options.keyUse.setDown(false);
    }
}
