package com.worldgit.util;

import com.worldgit.model.Branch;

/**
 * 分支标签与显示名格式化工具。
 */
public final class BranchDisplayUtil {

    public static final int MAX_LABEL_LENGTH = 20;
    private static final int SHORT_ID_LENGTH = 8;
    private static final int ACTION_BAR_LABEL_LENGTH = 12;

    private BranchDisplayUtil() {
    }

    public static String shortId(String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return "-";
        }
        return branchId.substring(0, Math.min(SHORT_ID_LENGTH, branchId.length()));
    }

    public static boolean hasLabel(String label) {
        return label != null && !label.isBlank();
    }

    public static String labelText(Branch branch) {
        return branch == null ? "未设置标签" : labelText(branch.label());
    }

    public static String labelText(String label) {
        return hasLabel(label) ? label.trim() : "未设置标签";
    }

    public static String displayName(Branch branch) {
        if (branch == null) {
            return "未设置标签 [-]";
        }
        return displayName(branch.id(), branch.label());
    }

    public static String displayName(String branchId, String label) {
        return labelText(label) + " [" + shortId(branchId) + "]";
    }

    public static String actionBarName(Branch branch) {
        if (branch == null) {
            return "-";
        }
        String label = labelText(branch);
        if (label.length() > ACTION_BAR_LABEL_LENGTH) {
            label = label.substring(0, ACTION_BAR_LABEL_LENGTH - 1) + "…";
        }
        return label + " [" + shortId(branch.id()) + "]";
    }
}
