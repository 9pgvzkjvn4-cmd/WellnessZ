package com.wellnessz.e01.referral;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Body-map regions (front/back) used by referral forms and the {@code bodyPart} case attribute. */
public final class BodyMap {

    public record Region(String code, String label, String side) {
    }

    public static final List<Region> REGIONS;
    public static final Set<String> CODES;

    static {
        List<Region> r = new ArrayList<>();
        String[][] front = {{"HEAD", "Head / face"}, {"EYES", "Eyes"}, {"NECK", "Neck"}, {"CHEST", "Chest"}, {"ABDOMEN", "Abdomen"},
                {"SHOULDER_L", "Left shoulder"}, {"SHOULDER_R", "Right shoulder"}, {"ARM_L", "Left arm"}, {"ARM_R", "Right arm"},
                {"ELBOW_L", "Left elbow"}, {"ELBOW_R", "Right elbow"}, {"WRIST_L", "Left wrist"}, {"WRIST_R", "Right wrist"},
                {"HAND_L", "Left hand / fingers"}, {"HAND_R", "Right hand / fingers"}, {"HIP_L", "Left hip"}, {"HIP_R", "Right hip"},
                {"KNEE_L", "Left knee"}, {"KNEE_R", "Right knee"}, {"LEG_L", "Left leg"}, {"LEG_R", "Right leg"},
                {"ANKLE_L", "Left ankle"}, {"ANKLE_R", "Right ankle"}, {"FOOT_L", "Left foot"}, {"FOOT_R", "Right foot"}};
        String[][] back = {{"UPPER_BACK", "Upper back"}, {"LOWER_BACK", "Lower back"}, {"BACK_OF_HEAD", "Back of head"},
                {"BUTTOCKS", "Buttocks"}, {"CALF_L", "Left calf"}, {"CALF_R", "Right calf"}};
        for (String[] f : front) {
            r.add(new Region(f[0], f[1], "FRONT"));
        }
        for (String[] b : back) {
            r.add(new Region(b[0], b[1], "BACK"));
        }
        REGIONS = List.copyOf(r);
        CODES = REGIONS.stream().map(Region::code).collect(Collectors.toUnmodifiableSet());
    }

    private BodyMap() {
    }

    /** Coarse body part used for duplicate detection / repeat-visit flags (left/right collapsed). */
    public static String primary(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        String first = regions.getFirst();
        return first.endsWith("_L") || first.endsWith("_R") ? first.substring(0, first.length() - 2) : first;
    }
}
