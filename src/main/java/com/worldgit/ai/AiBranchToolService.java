package com.worldgit.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.ProtectionManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.model.Branch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

public final class AiBranchToolService {

    private final BranchManager branchManager;
    private final WorldManager worldManager;
    private final ProtectionManager protectionManager;
    private final MainThreadBridge mainThreadBridge;

    public AiBranchToolService(
            BranchManager branchManager,
            WorldManager worldManager,
            ProtectionManager protectionManager,
            MainThreadBridge mainThreadBridge
    ) {
        this.branchManager = Objects.requireNonNull(branchManager, "分支管理器不能为空");
        this.worldManager = Objects.requireNonNull(worldManager, "世界管理器不能为空");
        this.protectionManager = Objects.requireNonNull(protectionManager, "保护管理器不能为空");
        this.mainThreadBridge = Objects.requireNonNull(mainThreadBridge, "主线程桥不能为空");
    }

    public AiToolRegistry createRegistry() {
        AiToolRegistry registry = new AiToolRegistry();
        registry.register(new AiToolDefinition(
                "get_block",
                "读取单个坐标的方块信息，返回材质和 blockData。",
                pointSchema(false),
                this::getBlock
        ));
        registry.register(new AiToolDefinition(
                "scan_box_summary",
                "读取一个盒状区域的摘要信息，返回材质统计、空气占比、少量样本方块。优先用于大范围观察和规划。",
                boxSchema(false),
                this::scanBoxSummary
        ));
        registry.register(new AiToolDefinition(
                "place_block",
                "在单个坐标放置方块。必须先观察再施工。",
                pointSchema(true),
                this::placeBlock
        ));
        registry.register(new AiToolDefinition(
                "break_block",
                "破坏单个坐标的方块。必须先观察再施工。",
                pointSchema(false),
                this::breakBlock
        ));
        registry.register(new AiToolDefinition(
                "get_blocks_in_box",
                "读取一个盒状区域内的方块信息。区域体积必须在限制内。",
                boxSchema(false),
                this::getBlocksInBox
        ));
        registry.register(new AiToolDefinition(
                "place_blocks_in_box",
                "在一个盒状区域内批量放置同一种方块。区域体积必须在限制内，且必须先观察再施工。",
                boxSchema(true),
                this::placeBlocksInBox
        ));
        registry.register(new AiToolDefinition(
                "break_blocks_in_box",
                "批量破坏盒状区域内的方块。区域体积必须在限制内，且必须先观察再施工。",
                boxSchema(false),
                this::breakBlocksInBox
        ));
        registry.register(new AiToolDefinition(
                "apply_block_jsonl",
                "提交 JSONL 批量改块。每行一个 JSON 对象，必须包含 material 和区域。区域可用 x/y/z 单点，或 minX/minY/minZ/maxX/maxY/maxZ 盒区。material=AIR 表示清空。每行都会校验是否在分支范围内。",
                jsonlSchema(),
                this::applyBlockJsonl
        ));
        return registry;
    }

    private JsonObject getBlock(JsonObject arguments, AiExecutionContext context) {
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        Branch branch = requireEditableBranch(context);
        ensureInsideBranch(branch, x, y, z);

        JsonObject result = mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            Block block = world.getBlockAt(x, y, z);
            JsonObject payload = success("get_block", branch);
            payload.add("block", serializeBlock(block));
            return payload;
        });
        context.markObserved();
        return result;
    }

    private JsonObject placeBlock(JsonObject arguments, AiExecutionContext context) {
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        String materialName = requiredString(arguments, "material");
        String blockDataString = optionalString(arguments, "blockData");
        Branch branch = requireEditableBranch(context);
        context.ensureObservedBeforeMutation();
        ensureInsideBranch(branch, x, y, z);

        return mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            Material material = requireMaterial(materialName, false);
            Block target = world.getBlockAt(x, y, z);
            BlockData targetData = createTargetBlockData(material, blockDataString, false);
            int changedCount = wouldChange(target, targetData) ? 1 : 0;
            context.ensureBlockChangeBudget(changedCount);
            if (changedCount > 0) {
                target.setBlockData(targetData, false);
                context.recordBlockChanges(changedCount);
            }
            JsonObject payload = success("place_block", branch);
            payload.addProperty("changedCount", changedCount);
            payload.add("block", serializeBlock(target));
            return payload;
        });
    }

    private JsonObject breakBlock(JsonObject arguments, AiExecutionContext context) {
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        Branch branch = requireEditableBranch(context);
        context.ensureObservedBeforeMutation();
        ensureInsideBranch(branch, x, y, z);

        return mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            Block target = world.getBlockAt(x, y, z);
            int changedCount = target.getType() == Material.AIR ? 0 : 1;
            context.ensureBlockChangeBudget(changedCount);
            if (changedCount > 0) {
                target.setType(Material.AIR, false);
                context.recordBlockChanges(changedCount);
            }
            JsonObject payload = success("break_block", branch);
            payload.addProperty("changedCount", changedCount);
            payload.add("block", serializeBlock(target));
            return payload;
        });
    }

    private JsonObject getBlocksInBox(JsonObject arguments, AiExecutionContext context) {
        Box box = readBox(arguments, context);
        Branch branch = requireEditableBranch(context);
        ensureBoxInsideBranch(branch, box);

        JsonObject result = mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            JsonArray blocks = new JsonArray();
            forEachBoxBlock(world, box, block -> blocks.add(serializeBlock(block)));
            JsonObject payload = success("get_blocks_in_box", branch);
            payload.add("box", serializeBox(box));
            payload.addProperty("blockCount", blocks.size());
            payload.add("blocks", blocks);
            return payload;
        });
        context.markObserved();
        return result;
    }

    private JsonObject scanBoxSummary(JsonObject arguments, AiExecutionContext context) {
        Box box = readBox(arguments, context);
        Branch branch = requireEditableBranch(context);
        ensureBoxInsideBranch(branch, box);

        JsonObject result = mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            Map<String, Integer> materialCounts = new LinkedHashMap<>();
            JsonArray sampleBlocks = new JsonArray();
            MutableCounter nonAirCounter = new MutableCounter();
            MutableCounter totalCounter = new MutableCounter();

            forEachBoxBlock(world, box, block -> {
                totalCounter.increment();
                String materialName = block.getType().name();
                materialCounts.merge(materialName, 1, Integer::sum);
                if (block.getType() != Material.AIR) {
                    nonAirCounter.increment();
                    if (sampleBlocks.size() < 12) {
                        sampleBlocks.add(serializeBlock(block));
                    }
                } else if (sampleBlocks.size() < 4) {
                    sampleBlocks.add(serializeBlock(block));
                }
            });

            JsonObject payload = success("scan_box_summary", branch);
            payload.add("box", serializeBox(box));
            payload.addProperty("blockCount", totalCounter.value());
            payload.addProperty("nonAirCount", nonAirCounter.value());
            payload.addProperty("airCount", totalCounter.value() - nonAirCounter.value());
            payload.addProperty("uniqueMaterialCount", materialCounts.size());
            payload.add("materials", serializeMaterialCounts(materialCounts, totalCounter.value()));
            payload.add("sampleBlocks", sampleBlocks);
            return payload;
        });
        context.markObserved();
        return result;
    }

    private JsonObject placeBlocksInBox(JsonObject arguments, AiExecutionContext context) {
        Box box = readBox(arguments, context);
        String materialName = requiredString(arguments, "material");
        String blockDataString = optionalString(arguments, "blockData");
        Branch branch = requireEditableBranch(context);
        context.ensureObservedBeforeMutation();
        ensureBoxInsideBranch(branch, box);

        return mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            Material material = requireMaterial(materialName, false);
            BlockData targetData = createTargetBlockData(material, blockDataString, false);
            MutableCounter counter = new MutableCounter();
            forEachBoxBlock(world, box, block -> {
                if (wouldChange(block, targetData)) {
                    counter.increment();
                }
            });
            context.ensureBlockChangeBudget(counter.value());
            if (counter.value() > 0) {
                forEachBoxBlock(world, box, block -> {
                    if (wouldChange(block, targetData)) {
                        block.setBlockData(targetData, false);
                    }
                });
                context.recordBlockChanges(counter.value());
            }
            JsonObject payload = success("place_blocks_in_box", branch);
            payload.add("box", serializeBox(box));
            payload.addProperty("changedCount", counter.value());
            payload.addProperty("material", material.name());
            payload.addProperty("blockData", targetData.getAsString());
            return payload;
        });
    }

    private JsonObject breakBlocksInBox(JsonObject arguments, AiExecutionContext context) {
        Box box = readBox(arguments, context);
        Branch branch = requireEditableBranch(context);
        context.ensureObservedBeforeMutation();
        ensureBoxInsideBranch(branch, box);

        return mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            MutableCounter counter = new MutableCounter();
            forEachBoxBlock(world, box, block -> {
                if (block.getType() != Material.AIR) {
                    counter.increment();
                }
            });
            context.ensureBlockChangeBudget(counter.value());
            if (counter.value() > 0) {
                forEachBoxBlock(world, box, block -> {
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR, false);
                    }
                });
                context.recordBlockChanges(counter.value());
            }
            JsonObject payload = success("break_blocks_in_box", branch);
            payload.add("box", serializeBox(box));
            payload.addProperty("changedCount", counter.value());
            return payload;
        });
    }

    private JsonObject applyBlockJsonl(JsonObject arguments, AiExecutionContext context) {
        String jsonl = requiredString(arguments, "jsonl");
        Branch branch = requireEditableBranch(context);
        context.ensureObservedBeforeMutation();
        List<JsonlPlacement> placements = parseJsonlPlacements(jsonl);
        validateJsonlPlacements(branch, placements);

        return mainThreadBridge.call(() -> {
            World world = requireBranchWorld(branch);
            int changedCount = 0;
            JsonArray applied = new JsonArray();
            for (JsonlPlacement placement : placements) {
                Material material = requireMaterial(placement.materialName(), true);
                BlockData targetData = createTargetBlockData(material, placement.blockDataString(), true);
                MutableCounter counter = new MutableCounter();
                forEachBoxBlock(world, placement.box(), block -> {
                    if (wouldChange(block, targetData)) {
                        applyBlockMutation(block, targetData);
                        counter.increment();
                    }
                });
                changedCount += counter.value();
                applied.add(serializeJsonlPlacement(placement, material, targetData, counter.value()));
            }
            context.recordBlockChangesUnchecked(changedCount);
            JsonObject payload = success("apply_block_jsonl", branch);
            payload.addProperty("lineCount", placements.size());
            payload.addProperty("changedCount", changedCount);
            payload.add("placements", applied);
            return payload;
        });
    }

    private Branch requireEditableBranch(AiExecutionContext context) {
        Branch branch = branchManager.requireBranch(context.branch().id());
        if (!branch.hasRegion()) {
            throw new IllegalStateException("目标分支没有有效编辑区域");
        }
        if (!worldManager.isBranchWorld(branch.worldName())) {
            throw new IllegalStateException("目标世界不是合法 branch world");
        }
        if (branch.worldName().equals(branch.mainWorld())) {
            throw new IllegalStateException("严禁通过 AI 修改 main world");
        }
        if (!branchManager.canModifyBranch(context.playerUuid(), branch)) {
            throw new IllegalStateException("你无权操作该分支");
        }
        if (!branchManager.isAiEditableBranch(branch)) {
            throw new IllegalStateException("该分支当前不是可编辑状态，AI 不能继续施工");
        }
        return branch;
    }

    private World requireBranchWorld(Branch branch) {
        World world = worldManager.createBranchWorld(branch.worldName());
        if (world == null) {
            throw new IllegalStateException("分支世界未加载");
        }
        if (protectionManager.isMainWorld(world)) {
            throw new IllegalStateException("严禁通过 AI 修改 main world");
        }
        if (!worldManager.isBranchWorld(world)) {
            throw new IllegalStateException("目标世界不是 branch world");
        }
        return world;
    }

    private void ensureInsideBranch(Branch branch, int x, int y, int z) {
        if (x < branch.minX() || x > branch.maxX()
                || y < branch.minY() || y > branch.maxY()
                || z < branch.minZ() || z > branch.maxZ()) {
            throw new IllegalStateException("坐标超出分支可编辑范围");
        }
    }

    private void ensureBoxInsideBranch(Branch branch, Box box) {
        ensureInsideBranch(branch, box.minX, box.minY, box.minZ);
        ensureInsideBranch(branch, box.maxX, box.maxY, box.maxZ);
    }

    private void validateJsonlPlacements(Branch branch, List<JsonlPlacement> placements) {
        if (placements.isEmpty()) {
            throw new IllegalStateException("jsonl 不能为空");
        }
        for (JsonlPlacement placement : placements) {
            ensureBoxInsideBranch(branch, placement.box());
        }
    }

    private Box readBox(JsonObject arguments, AiExecutionContext context) {
        int minX = requiredInt(arguments, "minX");
        int minY = requiredInt(arguments, "minY");
        int minZ = requiredInt(arguments, "minZ");
        int maxX = requiredInt(arguments, "maxX");
        int maxY = requiredInt(arguments, "maxY");
        int maxZ = requiredInt(arguments, "maxZ");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalStateException("区域坐标无效：min 不能大于 max");
        }
        Box box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        context.ensureBoxLimit(box.volume());
        return box;
    }

    private JsonObject success(String toolName, Branch branch) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("tool", toolName);
        payload.addProperty("branchId", branch.id());
        payload.addProperty("worldName", branch.worldName());
        payload.addProperty("status", branch.status().dbValue());
        return payload;
    }

    private JsonObject serializeBlock(Block block) {
        JsonObject payload = new JsonObject();
        payload.addProperty("x", block.getX());
        payload.addProperty("y", block.getY());
        payload.addProperty("z", block.getZ());
        payload.addProperty("material", block.getType().name());
        payload.addProperty("blockData", block.getBlockData().getAsString());
        return payload;
    }

    private JsonObject serializeBox(Box box) {
        JsonObject payload = new JsonObject();
        payload.addProperty("minX", box.minX);
        payload.addProperty("minY", box.minY);
        payload.addProperty("minZ", box.minZ);
        payload.addProperty("maxX", box.maxX);
        payload.addProperty("maxY", box.maxY);
        payload.addProperty("maxZ", box.maxZ);
        payload.addProperty("volume", box.volume());
        return payload;
    }

    private JsonArray serializeMaterialCounts(Map<String, Integer> materialCounts, int totalCount) {
        JsonArray materials = new JsonArray();
        materialCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(16)
                .forEach(entry -> {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("material", entry.getKey());
                    payload.addProperty("count", entry.getValue());
                    payload.addProperty("ratio", totalCount <= 0 ? 0.0D : ((double) entry.getValue()) / totalCount);
                    materials.add(payload);
                });
        return materials;
    }

    private void forEachBoxBlock(World world, Box box, BlockConsumer consumer) {
        for (int x = box.minX; x <= box.maxX; x++) {
            for (int y = box.minY; y <= box.maxY; y++) {
                for (int z = box.minZ; z <= box.maxZ; z++) {
                    consumer.accept(world.getBlockAt(x, y, z));
                }
            }
        }
    }

    private Material requireMaterial(String materialName, boolean allowAir) {
        if (materialName == null || materialName.isBlank()) {
            throw new IllegalStateException("material 不能为空");
        }
        Material material = Material.matchMaterial(materialName.trim());
        if (material == null) {
            material = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
        }
        if (material == null || !material.isBlock()) {
            throw new IllegalStateException("无效方块材质: " + materialName);
        }
        if (!allowAir && material == Material.AIR) {
            throw new IllegalStateException("place 工具不允许直接放置 AIR，请使用 break 工具");
        }
        return material;
    }

    private BlockData createTargetBlockData(Material material, String blockDataString, boolean allowAir) {
        if (material == Material.AIR) {
            if (blockDataString != null && !blockDataString.isBlank()) {
                throw new IllegalStateException("AIR 不允许提供 blockData");
            }
            if (!allowAir) {
                throw new IllegalStateException("当前工具不允许使用 AIR");
            }
            return material.createBlockData();
        }
        if (blockDataString != null && !blockDataString.isBlank()) {
            BlockData blockData = Bukkit.createBlockData(blockDataString);
            if (blockData.getMaterial() != material) {
                throw new IllegalStateException("blockData 与 material 不匹配");
            }
            return blockData;
        }
        return material.createBlockData();
    }

    private boolean wouldChange(Block block, BlockData targetData) {
        if (targetData.getMaterial() == Material.AIR) {
            return block.getType() != Material.AIR;
        }
        return !block.getBlockData().getAsString().equals(targetData.getAsString());
    }

    private int requiredInt(JsonObject arguments, String fieldName) {
        if (!arguments.has(fieldName) || arguments.get(fieldName).isJsonNull()) {
            throw new IllegalStateException("缺少参数: " + fieldName);
        }
        try {
            return arguments.get(fieldName).getAsInt();
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("参数必须是整数: " + fieldName);
        }
    }

    private String requiredString(JsonObject arguments, String fieldName) {
        String value = optionalString(arguments, fieldName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少参数: " + fieldName);
        }
        return value;
    }

    private String optionalString(JsonObject arguments, String fieldName) {
        if (!arguments.has(fieldName) || arguments.get(fieldName).isJsonNull()) {
            return null;
        }
        return arguments.get(fieldName).getAsString();
    }

    private List<JsonlPlacement> parseJsonlPlacements(String jsonl) {
        List<JsonlPlacement> placements = new ArrayList<>();
        String[] lines = jsonl.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isBlank()) {
                continue;
            }
            placements.add(parseJsonlPlacementLine(line, index + 1));
        }
        return placements;
    }

    private JsonlPlacement parseJsonlPlacementLine(String line, int lineNumber) {
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("第 " + lineNumber + " 行必须是 JSON 对象");
            }
            JsonObject object = parsed.getAsJsonObject();
            String material = requiredString(object, "material");
            String blockData = optionalString(object, "blockData");

            boolean hasPoint = hasIntField(object, "x") || hasIntField(object, "y") || hasIntField(object, "z");
            boolean hasBox = hasIntField(object, "minX") || hasIntField(object, "minY") || hasIntField(object, "minZ")
                    || hasIntField(object, "maxX") || hasIntField(object, "maxY") || hasIntField(object, "maxZ");
            if (hasPoint && hasBox) {
                throw new IllegalStateException("第 " + lineNumber + " 行不能同时使用点坐标和盒坐标");
            }

            Box box;
            if (hasBox) {
                int minX = requiredInt(object, "minX");
                int minY = requiredInt(object, "minY");
                int minZ = requiredInt(object, "minZ");
                int maxX = requiredInt(object, "maxX");
                int maxY = requiredInt(object, "maxY");
                int maxZ = requiredInt(object, "maxZ");
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    throw new IllegalStateException("第 " + lineNumber + " 行区域坐标无效：min 不能大于 max");
                }
                box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
            } else {
                int x = requiredInt(object, "x");
                int y = requiredInt(object, "y");
                int z = requiredInt(object, "z");
                box = new Box(x, y, z, x, y, z);
            }
            return new JsonlPlacement(lineNumber, box, material, blockData);
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException("第 " + lineNumber + " 行不是合法 JSON", exception);
        }
    }

    private boolean hasIntField(JsonObject arguments, String fieldName) {
        return arguments.has(fieldName) && !arguments.get(fieldName).isJsonNull();
    }

    private JsonObject pointSchema(boolean includeMaterial) {
        JsonObject schema = objectSchema();
        JsonObject properties = new JsonObject();
        properties.add("x", integerSchema("X 坐标"));
        properties.add("y", integerSchema("Y 坐标"));
        properties.add("z", integerSchema("Z 坐标"));
        JsonArray required = requiredArray("x", "y", "z");
        if (includeMaterial) {
            properties.add("material", stringSchema("方块材质，如 STONE"));
            properties.add("blockData", nullableStringSchema("可选，完整 blockData 字符串；不需要时传 null"));
            required.add("material");
            required.add("blockData");
        }
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private JsonObject jsonlSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = new JsonObject();
        properties.add("jsonl", stringSchema("""
                JSONL 文本。每行一个 JSON 对象。
                单点示例：{"x":1,"y":64,"z":1,"material":"STONE"}
                区域示例：{"minX":1,"minY":64,"minZ":1,"maxX":5,"maxY":67,"maxZ":5,"material":"GLASS"}
                如需清空可使用 material=AIR。
                """.stripIndent().trim()));
        schema.add("properties", properties);
        schema.add("required", requiredArray("jsonl"));
        return schema;
    }

    private JsonObject boxSchema(boolean includeMaterial) {
        JsonObject schema = objectSchema();
        JsonObject properties = new JsonObject();
        properties.add("minX", integerSchema("区域最小 X"));
        properties.add("minY", integerSchema("区域最小 Y"));
        properties.add("minZ", integerSchema("区域最小 Z"));
        properties.add("maxX", integerSchema("区域最大 X"));
        properties.add("maxY", integerSchema("区域最大 Y"));
        properties.add("maxZ", integerSchema("区域最大 Z"));
        JsonArray required = requiredArray("minX", "minY", "minZ", "maxX", "maxY", "maxZ");
        if (includeMaterial) {
            properties.add("material", stringSchema("方块材质，如 STONE"));
            properties.add("blockData", nullableStringSchema("可选，完整 blockData 字符串；不需要时传 null"));
            required.add("material");
            required.add("blockData");
        }
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    private JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private JsonObject nullableStringSchema(String description) {
        JsonObject schema = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("null");
        schema.add("type", types);
        schema.addProperty("description", description);
        return schema;
    }

    private JsonArray requiredArray(String... fieldNames) {
        JsonArray array = new JsonArray();
        for (String fieldName : fieldNames) {
            array.add(fieldName);
        }
        return array;
    }

    private JsonObject serializeJsonlPlacement(
            JsonlPlacement placement,
            Material material,
            BlockData targetData,
            int changedCount
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("lineNumber", placement.lineNumber());
        payload.add("box", serializeBox(placement.box()));
        payload.addProperty("material", material.name());
        payload.addProperty("blockData", targetData.getAsString());
        payload.addProperty("changedCount", changedCount);
        return payload;
    }

    private void applyBlockMutation(Block block, BlockData targetData) {
        if (targetData.getMaterial() == Material.AIR) {
            block.setType(Material.AIR, false);
            return;
        }
        block.setBlockData(targetData, false);
    }

    @FunctionalInterface
    private interface BlockConsumer {
        void accept(Block block);
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        private long volume() {
            return (long) (maxX - minX + 1)
                    * (long) (maxY - minY + 1)
                    * (long) (maxZ - minZ + 1);
        }
    }

    private static final class MutableCounter {
        private int value;

        private void increment() {
            value++;
        }

        private int value() {
            return value;
        }
    }

    private record JsonlPlacement(
            int lineNumber,
            Box box,
            String materialName,
            String blockDataString
    ) {
    }
}
