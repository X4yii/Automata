
package com.x4yi.automata.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import com.x4yi.x4ui.client.gui.component.GuiButton;
import com.x4yi.x4ui.client.gui.component.GuiLabel;
import com.x4yi.x4ui.client.gui.component.GuiPanel;
import com.x4yi.x4ui.client.gui.component.GuiScrollPanel;
import com.x4yi.x4ui.client.gui.component.GuiTextInput;
import com.x4yi.x4ui.client.gui.component.GuiToggle;
import com.x4yi.x4ui.client.gui.component.GuiVirtualList;
import com.x4yi.x4ui.client.gui.input.KeyCodes;
import com.x4yi.x4ui.common.State;

import net.minecraft.entity.EntityList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.client.gui.AutomatonGuiState.SquadmateEntry;

@SideOnly(Side.CLIENT)
public final class BehaviorSquadTabView {

    private final GuiScrollPanel root;
    private final GuiTextInput squadChannelInput;
    private final GuiTextInput filterSearchInput;
    private final GuiButton whitelistButton;
    private final GuiVirtualList<EntityFilterEntry> filterVirtualList;
    private final GuiTextInput nameInput;
    private final GuiTextInput skinInput;
    private final GuiLabel squadCountLabel;
    private final GuiVirtualList<SquadmateEntry> squadList;
    private final AutomatonGuiState state;
    private String lastSyncedSquad;
    private String lastSyncedName;
    private String lastSyncedSkin;

    public BehaviorSquadTabView(
            AutomatonGuiState state,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        this.state = state;
        this.root = AutomataUiTheme.createTabScroll();

        GuiPanel squadCard = AutomataUiTheme.makeCard(tr("gui.automata.squad.title", "Squad Coordination"), 4);
        squadCard.add(makeSyncedToggle(
                "Squad Cooperation",
                state.squadCoopState,
                () -> state.getTaskConfig().isSquadCooperation(),
                "automata:toggle_squad",
                sendAction,
                refreshSnapshot));

        GuiPanel chanRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        this.squadChannelInput = chanRow.add(new GuiTextInput(0, 0, 0, 18), input -> {
            input.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinWidth(48);
            input.setPlaceholder(tr("gui.automata.squad.channel_placeholder", "Squad channel..."));
            input.setSubmitKey(KeyCodes.ENTER);
            input.onEnter(() -> submitSquadChannel(sendAction, refreshSnapshot));
        });

        chanRow.add(GuiButton.of(
                tr("gui.automata.squad.apply", "Apply"),
                () -> submitSquadChannel(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY),
                btn -> btn.withSize(42, 18));

        chanRow.add(GuiButton.of("x", () -> squadChannelInput.setText("")).withVariant(GuiButton.Variant.DANGER),
                btn -> btn.withSize(18, 18));
        squadCard.add(chanRow);

        this.squadCountLabel = squadCard.add(GuiLabel.of("Nearby Squadmates: 0", AutomataUiTheme.COLOR_MUTED));

        this.squadList = squadCard.add(GuiVirtualList.of(
                54,
                16,
                entry -> GuiLabel.of("", AutomataUiTheme.COLOR_TEXT).withPercentWidth(1.0F),
                (comp, entry) -> {
                    GuiLabel lbl = (GuiLabel) comp;
                    String status = entry.downed
                            ? "DOWN"
                            : String.format("%.0f/%.0f HP", entry.health, entry.maxHealth);
                    lbl.setText(String.format(
                            "%s | %s | %s | %.0fm",
                            entry.name,
                            entry.job,
                            status,
                            entry.distance));
                    lbl.setColor(entry.downed ? AutomataUiTheme.COLOR_RED : AutomataUiTheme.COLOR_TEXT);
                }));
        root.add(squadCard);

        GuiPanel filterCard = AutomataUiTheme.makeCard("Entity Targeting Filter", 4);
        GuiPanel searchRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);

        this.filterSearchInput = searchRow.add(new GuiTextInput(0, 0, 0, 18), input -> {
            input.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinWidth(48);
            input.setPlaceholder("Search entity id...");
            input.setSubmitKey(KeyCodes.ENTER);
            input.setOnTextChanged(q -> rebuildFilterList());
        });

        this.whitelistButton = searchRow.add(GuiButton.of(
                state.whitelistState.get() ? "Whitelist" : "Blacklist",
                () -> {
                    sendAction.accept("automata:toggle_whitelist", null);
                    refreshSnapshot.run();
                }).withVariant(GuiButton.Variant.PRIMARY));
        whitelistButton.withSize(56, 18);
        whitelistButton.bindState(state.whitelistState, wl ->
                whitelistButton.setText(Boolean.TRUE.equals(wl) ? "Whitelist" : "Blacklist"));

        searchRow.add(GuiButton.of("Clear", () -> {
            sendAction.accept("automata:clear_filters", null);
            refreshSnapshot.run();
        }).withVariant(GuiButton.Variant.DANGER), btn -> btn.withSize(38, 18));
        filterCard.add(searchRow);

        this.filterVirtualList = filterCard.add(GuiVirtualList.of(
                96,
                20,
                entry -> GuiButton.of("", () -> {}).withSize(0, 18).withPercentWidth(1.0F),
                (comp, entry) -> {
                    GuiButton btn = (GuiButton) comp;
                    btn.setText((entry.selected ? "[x] " : "[ ] ") + entry.id);
                    btn.setSelected(entry.selected);
                    btn.setOnClick(() -> {
                        NBTTagCompound data = new NBTTagCompound();
                        data.setString("entityId", entry.id);
                        sendAction.accept("automata:toggle_filter_entity", data);
                        refreshSnapshot.run();
                    });
                }));
        root.add(filterCard);

        GuiPanel moveCard = AutomataUiTheme.makeCard("Movement & Appearance", 4);
        moveCard.add(makeSyncedToggle(
                "Allow Break-Through",
                state.breakThroughState,
                () -> state.getTaskConfig().isAllowBreakThrough(),
                "automata:toggle_terrain",
                sendAction,
                refreshSnapshot));
        moveCard.add(makeSyncedToggle(
                "Allow Parkour Jumps",
                state.parkourState,
                () -> state.getTaskConfig().isAllowParkour(),
                "automata:toggle_parkour",
                sendAction,
                refreshSnapshot));
        moveCard.add(makeSyncedToggle(
                "Allow Pillar / Bridge",
                state.scaffoldingState,
                () -> state.getTaskConfig().isAllowScaffolding(),
                "automata:toggle_scaffolding",
                sendAction,
                refreshSnapshot));
        moveCard.add(makeSyncedToggle(
                "Slim Model (Alex)",
                state.slimState,
                () -> Boolean.TRUE.equals(state.slimState.get()),
                "automata:toggle_slim",
                sendAction,
                refreshSnapshot));

        GuiPanel nameRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        this.nameInput = nameRow.add(new GuiTextInput(0, 0, 0, 18), input -> {
            input.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinWidth(48);
            input.setPlaceholder(tr("gui.automata.squad.name_placeholder", "Automaton name (.name)..."));
            input.setSubmitKey(KeyCodes.ENTER);
            input.onEnter(() -> submitName(sendAction, refreshSnapshot));
        });

        nameRow.add(GuiButton.of(
                tr("gui.automata.squad.apply", "Apply"),
                () -> submitName(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY),
                btn -> btn.withSize(42, 18));

        nameRow.add(GuiButton.of("x", () -> nameInput.setText("")).withVariant(GuiButton.Variant.DANGER),
                btn -> btn.withSize(18, 18));
        moveCard.add(nameRow);

        GuiPanel skinRow = GuiPanel.row(4)
                .withHugContent(true)
                .withPercentWidth(1.0F);
        this.skinInput = skinRow.add(new GuiTextInput(0, 0, 0, 18), input -> {
            input.withPercentWidth(-1.0F).withFlex(1.0F, 1.0F).withMinWidth(48);
            input.setPlaceholder(tr("gui.automata.squad.skin_placeholder", "Skin username..."));
            input.setSubmitKey(KeyCodes.ENTER);
            input.onEnter(() -> submitSkin(sendAction, refreshSnapshot));
        });

        skinRow.add(GuiButton.of(
                tr("gui.automata.squad.apply", "Apply"),
                () -> submitSkin(sendAction, refreshSnapshot)).withVariant(GuiButton.Variant.PRIMARY),
                btn -> btn.withSize(42, 18));

        skinRow.add(GuiButton.of("x", () -> skinInput.setText("")).withVariant(GuiButton.Variant.DANGER),
                btn -> btn.withSize(18, 18));
        moveCard.add(skinRow);
        root.add(moveCard);

        state.addSnapshotListener(this::syncFromState);
        syncFromState();
    }

    private GuiToggle makeSyncedToggle(
            String label,
            State<Boolean> boundState,
            BooleanSupplier currentVal,
            String actionId,
            BiConsumer<String, NBTTagCompound> sendAction,
            Runnable refreshSnapshot) {
        GuiToggle toggle = new GuiToggle(0, 0, 0, 18, label, Boolean.TRUE.equals(boundState.get()), val -> {
            if (!state.isApplyingSnapshot() && val != currentVal.getAsBoolean()) {
                sendAction.accept(actionId, null);
                refreshSnapshot.run();
            }
        });
        toggle.withPercentWidth(1.0F);
        toggle.bindState(boundState);
        return toggle;
    }

    private void submitSquadChannel(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("channel", squadChannelInput.getText().trim());
        sendAction.accept("automata:set_squad_channel", data);
        refreshSnapshot.run();
    }

    private void submitName(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("name", nameInput.getText().trim());
        sendAction.accept("automata:set_name", data);
        refreshSnapshot.run();
    }

    private void submitSkin(BiConsumer<String, NBTTagCompound> sendAction, Runnable refreshSnapshot) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("skin", skinInput.getText().trim());
        sendAction.accept("automata:set_skin", data);
        refreshSnapshot.run();
    }

    private void rebuildFilterList() {
        String query = filterSearchInput.getText().trim().toLowerCase(Locale.ROOT);
        Set<String> active = state.getTaskConfig().getEntityFilterIds();
        List<String> ids = new ArrayList<>();
        for (ResourceLocation rl : EntityList.getEntityNameList()) {
            String id = rl.toString();
            if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query)) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparing((String id) -> !active.contains(id)).thenComparing(Comparator.naturalOrder()));
        List<EntityFilterEntry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            entries.add(new EntityFilterEntry(id, active.contains(id)));
        }
        filterVirtualList.setItems(entries);
    }

    private void syncFromState() {
        String nextSquad = state.getTaskConfig().getSquadChannel();
        if (!squadChannelInput.isFocused() && !Objects.equals(nextSquad, lastSyncedSquad)) {
            squadChannelInput.setText(nextSquad);
            squadChannelInput.setCursorPosition(0);
            lastSyncedSquad = nextSquad;
        }
        String nextName = state.getAutomatonName();
        if (!nameInput.isFocused() && !Objects.equals(nextName, lastSyncedName)) {
            nameInput.setText(nextName);
            nameInput.setCursorPosition(0);
            lastSyncedName = nextName;
        }
        String nextSkin = state.getSkinName();
        if (!skinInput.isFocused() && !Objects.equals(nextSkin, lastSyncedSkin)) {
            skinInput.setText(nextSkin);
            skinInput.setCursorPosition(0);
            lastSyncedSkin = nextSkin;
        }
        List<SquadmateEntry> mates = state.getSquadmates();
        squadCountLabel.setText("Nearby Squadmates: " + mates.size());
        squadList.setItems(mates);
        rebuildFilterList();
    }

    private static String tr(String key, String fallback) {
        return net.minecraft.client.resources.I18n.hasKey(key)
                ? net.minecraft.client.resources.I18n.format(key)
                : fallback;
    }

    public GuiScrollPanel getRoot() {
        return root;
    }

    public static final class EntityFilterEntry {
        public final String id;
        public final boolean selected;

        public EntityFilterEntry(String id, boolean selected) {
            this.id = id;
            this.selected = selected;
        }
    }
}
