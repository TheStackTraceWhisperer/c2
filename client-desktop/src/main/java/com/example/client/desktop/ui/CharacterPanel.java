package com.example.client.desktop.ui;

import com.example.client.desktop.net.ClientState;
import com.example.common.protocol.JobId;
import com.example.common.protocol.NationId;
import com.example.common.protocol.RaceId;
import com.example.lobby.dto.CharacterSummary;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;

import java.util.List;

@Prototype
public class CharacterPanel {

    private static final String[] RACE_LABELS = {
            "Hume Male", "Hume Female", "Elvaan Male", "Elvaan Female",
            "Tarutaru Male", "Tarutaru Female", "Mithra", "Galka"
    };
    private static final String[] SIZE_LABELS = {"Small", "Medium", "Large"};
    private static final String[] JOB_LABELS = {
            "Warrior", "Monk", "White Mage", "Black Mage", "Red Mage", "Thief"
    };
    private static final String[] NATION_LABELS = {"San d'Oria", "Bastok", "Windurst"};

    // Create form fields
    private boolean showCreateForm = false;
    private final ImString newName = new ImString("", 32);
    private final ImInt raceIdx = new ImInt(0);
    private final ImInt sizeIdx = new ImInt(1);
    private final ImInt faceNum = new ImInt(1);
    private final ImBoolean faceB = new ImBoolean(false);
    private final ImInt jobIdx = new ImInt(0);
    private final ImInt nationIdx = new ImInt(0);

    // Intents
    @Getter private boolean createSubmitted;
    @Getter private boolean refreshRequested;
    @Getter private boolean signOutRequested;
    @Getter private Long selectCharacterId;
    @Getter private Long deleteCharacterId;
    @Getter private boolean playRequested;

    public void render(ClientState clientState) {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(760, 560, ImGuiCond.Once);
        ImGui.begin("Character Select");

        if (clientState.getLastErrorMessage() != null) {
            ImGui.textColored(1.0f, 0.2f, 0.2f, 1.0f, "Error: " + clientState.getLastErrorMessage());
            ImGui.separator();
        }

        if (ImGui.button("Refresh")) refreshRequested = true;
        ImGui.sameLine();
        if (ImGui.button("Sign Out")) signOutRequested = true;

        ImGui.separator();
        if (!showCreateForm) {
            if (ImGui.button("Create Character")) showCreateForm = true;
        } else {
            renderCreateForm();
        }

        ImGui.separator();
        ImGui.text("Characters:");

        List<CharacterSummary> characters = clientState.getCharacterList();
        if (characters.isEmpty()) {
            ImGui.textDisabled("No characters found.");
        } else {
            for (CharacterSummary c : characters) {
                String raceLabel = RaceId.toLabel(c.raceId());
                String jobAbbr = JobId.toAbbreviation(c.mainJobId());
                String nationLabel = NationId.toLabel(c.nationId());

                ImGui.text(String.format("%s  [%s]  Lvl %d %s  (%s)  Zone: %d",
                        c.name(), raceLabel, c.level(), jobAbbr, nationLabel, c.zoneId()));
                ImGui.sameLine();

                if (ImGui.smallButton("Select##" + c.characterId())) {
                    selectCharacterId = c.characterId();
                }
                ImGui.sameLine();
                if (ImGui.smallButton("Delete##" + c.characterId())) {
                    deleteCharacterId = c.characterId();
                }
            }
        }

        Long selectedId = clientState.getSelectedCharacterId();
        if (selectedId != null) {
            ImGui.separator();
            if (ImGui.button("Play")) playRequested = true;
            ImGui.sameLine();
            ImGui.text("Ready (ID=" + selectedId + ")");
        }

        ImGui.end();
    }

    private void renderCreateForm() {
        ImGui.text("New Character");
        ImGui.inputText("Name", newName);
        ImGui.combo("Race", raceIdx, RACE_LABELS);

        short raceId = getRaceId();
        int forcedSize = RaceId.forcedSizeFor(raceId);
        if (forcedSize >= 0) {
            sizeIdx.set(forcedSize);
            ImGui.text("Size: " + SIZE_LABELS[forcedSize] + " (fixed)");
        } else {
            ImGui.combo("Size", sizeIdx, SIZE_LABELS);
        }

        ImGui.inputInt("Face (1-8)", faceNum);
        ImGui.sameLine();
        ImGui.checkbox("Variant B", faceB);
        ImGui.combo("Job", jobIdx, JOB_LABELS);
        ImGui.combo("Nation", nationIdx, NATION_LABELS);

        if (ImGui.button("Create")) createSubmitted = true;
        ImGui.sameLine();
        if (ImGui.button("Cancel")) showCreateForm = false;
    }

    // Create form accessors
    public String getNewName() {
        return newName.get();
    }

    public short getRaceId() {
        return (short) (raceIdx.get() + 1);
    }

    public short getGender() {
        return RaceId.genderFor(getRaceId());
    }

    public short getSizeId() {
        return (short) sizeIdx.get();
    }

    public short getFaceId() {
        int clamped = Math.clamp(faceNum.get(), 1, 8);
        return (short) ((clamped - 1) + (faceB.get() ? 8 : 0));
    }

    public short getHairId() {
        return (short) 0;
    }

    public short getJobId() {
        return (short) (jobIdx.get() + 1);
    }

    public short getNationId() {
        return (short) nationIdx.get();
    }

    public String getNationName() {
        return NATION_LABELS[nationIdx.get()];
    }

    public void clearIntents() {
        createSubmitted = false;
        refreshRequested = false;
        signOutRequested = false;
        selectCharacterId = null;
        deleteCharacterId = null;
        playRequested = false;
    }

    public void hideCreateForm() {
        showCreateForm = false;
        newName.set("");
    }
}
