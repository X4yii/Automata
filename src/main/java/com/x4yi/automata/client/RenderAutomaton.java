
package com.x4yi.automata.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.x4yi.automata.entity.EntityAutomaton;

@SideOnly(Side.CLIENT)
public class RenderAutomaton extends RenderBiped<EntityAutomaton> {

    private static final ResourceLocation STEVE_SKIN = new ResourceLocation("textures/entity/steve.png");
    private static final ResourceLocation ALEX_SKIN = new ResourceLocation("textures/entity/alex.png");

    private static final int MAX_SKIN_CACHE_SIZE = 256;
    private static final Map<String, ResourceLocation> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PENDING_LOOKUPS = new ConcurrentHashMap<>();
    private static final ExecutorService SKIN_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Automata-SkinLookup");
        t.setDaemon(true);
        return t;
    });

    private static final float PLAYER_RENDER_SCALE = 0.9375F;
    private static final float AUTOMATON_RENDER_SCALE = PLAYER_RENDER_SCALE;
    private static final java.lang.reflect.Method AQUA_SET_SWIM_ANIMATION = resolveAquaSwimMethod();

    @Nullable
    private static java.lang.reflect.Method resolveAquaSwimMethod() {
        try {
            Class<?> iface = Class.forName("com.fuzs.aquaacrobatics.client.model.IModelBipedSwimming");
            return iface.getMethod("setSwimAnimation", float.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private final ModelPlayer steveModel = new ModelPlayer(0.0F, false);
    private final ModelPlayer alexModel = new ModelPlayer(0.0F, true);

    public RenderAutomaton(RenderManager renderManager) {
        super(renderManager, new ModelPlayer(0.0F, false), 0.5F);
        addLayer(new LayerBipedArmor(this));
        addLayer(new LayerHeldItem(this));
    }

    @Override
    public void doRender(EntityAutomaton entity, double x, double y, double z, float entityYaw, float partialTicks) {
        ModelPlayer model = entity.isSlimModel() ? alexModel : steveModel;
        this.mainModel = model;
        setModelVisibilities(entity, model);
        if (AQUA_SET_SWIM_ANIMATION != null && AQUA_SET_SWIM_ANIMATION.getDeclaringClass().isInstance(model)) {
            try {
                AQUA_SET_SWIM_ANIMATION.invoke(model, entity.getSwimAnimation(partialTicks));
            } catch (Throwable ignored) {
            }
        }
        double renderY = entity.isSneaking() ? y - 0.125D : y;
        super.doRender(entity, x, renderY, z, entityYaw, partialTicks);
        if (entity.getNeededTool() != EntityAutomaton.NEEDED_TOOL_NONE) {
            renderNeededToolIndicator(entity, x, renderY, z);
        }
    }

    private void renderNeededToolIndicator(EntityAutomaton entity, double x, double y, double z) {
        ItemStack toolStack = pickDisplayToolStack(entity, entity.getNeededTool());
        if (toolStack.isEmpty()) {
            return;
        }
        double bob = Math.sin((entity.ticksExisted) * 0.15D) * 0.05D;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y + entity.height + 0.65D + bob, z);
        GlStateManager.rotate(-this.renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(this.renderManager.playerViewX, 1.0F, 0.0F, 0.0F);

        GlStateManager.pushMatrix();
        GlStateManager.translate(-0.14F, 0.0F, 0.0F);
        GlStateManager.scale(0.45F, 0.45F, 0.45F);
        Minecraft.getMinecraft().getRenderItem().renderItem(toolStack, ItemCameraTransforms.TransformType.GUI);
        GlStateManager.popMatrix();

        FontRenderer font = getFontRendererFromRenderManager();
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.14F, 0.14F, 0.0F);
        GlStateManager.scale(-0.025F, -0.025F, 0.025F);
        GlStateManager.disableLighting();
        font.drawStringWithShadow("?", -font.getStringWidth("?") / 2.0F, -4.0F, 0xFFFF55);
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();

        GlStateManager.popMatrix();
    }

    private static ItemStack pickDisplayToolStack(EntityAutomaton entity, byte toolType) {
        int variant = (int) ((entity.ticksExisted / 16L + entity.getEntityId()) % 5L);
        Item item = switch (toolType) {
            case EntityAutomaton.NEEDED_TOOL_PICKAXE -> switch (variant) {
                case 0 -> Items.WOODEN_PICKAXE;
                case 1 -> Items.STONE_PICKAXE;
                case 2 -> Items.IRON_PICKAXE;
                case 3 -> Items.GOLDEN_PICKAXE;
                default -> Items.DIAMOND_PICKAXE;
            };
            case EntityAutomaton.NEEDED_TOOL_AXE -> switch (variant) {
                case 0 -> Items.WOODEN_AXE;
                case 1 -> Items.STONE_AXE;
                case 2 -> Items.IRON_AXE;
                case 3 -> Items.GOLDEN_AXE;
                default -> Items.DIAMOND_AXE;
            };
            case EntityAutomaton.NEEDED_TOOL_SHOVEL -> switch (variant) {
                case 0 -> Items.WOODEN_SHOVEL;
                case 1 -> Items.STONE_SHOVEL;
                case 2 -> Items.IRON_SHOVEL;
                case 3 -> Items.GOLDEN_SHOVEL;
                default -> Items.DIAMOND_SHOVEL;
            };
            case EntityAutomaton.NEEDED_TOOL_HOE -> switch (variant) {
                case 0 -> Items.WOODEN_HOE;
                case 1 -> Items.STONE_HOE;
                case 2 -> Items.IRON_HOE;
                case 3 -> Items.GOLDEN_HOE;
                default -> Items.DIAMOND_HOE;
            };
            case EntityAutomaton.NEEDED_TOOL_SWORD -> switch (variant) {
                case 0 -> Items.WOODEN_SWORD;
                case 1 -> Items.STONE_SWORD;
                case 2 -> Items.IRON_SWORD;
                case 3 -> Items.GOLDEN_SWORD;
                default -> Items.DIAMOND_SWORD;
            };
            default -> null;
        };
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override
    protected void preRenderCallback(EntityAutomaton entitylivingbaseIn, float partialTickTime) {
        GlStateManager.scale(AUTOMATON_RENDER_SCALE, AUTOMATON_RENDER_SCALE, AUTOMATON_RENDER_SCALE);
    }

    private static void setModelVisibilities(EntityAutomaton entity, ModelPlayer model) {
        model.setVisible(true);
        model.isSneak = entity.isSneaking();
        ModelBiped.ArmPose mainPose = resolveArmPose(entity, EnumHand.MAIN_HAND);
        ModelBiped.ArmPose offPose = resolveArmPose(entity, EnumHand.OFF_HAND);
        if (entity.getPrimaryHand() == EnumHandSide.RIGHT) {
            model.rightArmPose = mainPose;
            model.leftArmPose = offPose;
        } else {
            model.rightArmPose = offPose;
            model.leftArmPose = mainPose;
        }
    }

    private static ModelBiped.ArmPose resolveArmPose(EntityAutomaton entity, EnumHand hand) {
        ItemStack stack = entity.getHeldItem(hand);
        if (stack.isEmpty()) {
            return ModelBiped.ArmPose.EMPTY;
        }
        if (entity.getItemInUseCount() > 0 && entity.getActiveHand() == hand) {
            EnumAction action = stack.getItemUseAction();
            if (action == EnumAction.BLOCK) {
                return ModelBiped.ArmPose.BLOCK;
            }
            if (action == EnumAction.BOW) {
                return ModelBiped.ArmPose.BOW_AND_ARROW;
            }
        }
        return ModelBiped.ArmPose.ITEM;
    }

    @Override
    protected void applyRotations(EntityAutomaton entityLiving, float ageInTicks, float rotationYaw, float partialTicks) {
        super.applyRotations(entityLiving, ageInTicks, rotationYaw, partialTicks);
        if (entityLiving.isDowned()) {
            GlStateManager.translate(0.0F, 0.12F, 0.0F);
            GlStateManager.rotate(90.0F, 1.0F, 0.0F, 0.0F);
            GlStateManager.translate(0.0F, -0.75F, 0.0F);
            return;
        }
        float swimAnim = entityLiving.getSwimAnimation(partialTicks);
        if (swimAnim > 0.0F) {
            float pitch = entityLiving.isInWater() ? -90.0F - entityLiving.rotationPitch : -90.0F;
            float angle = pitch * swimAnim;
            GlStateManager.rotate(angle, 1.0F, 0.0F, 0.0F);
            if (entityLiving.isSwimming()) {
                GlStateManager.translate(0.0F, -1.0F, 0.3F);
            }
        }
    }

    @Nullable
    @Override
    protected ResourceLocation getEntityTexture(EntityAutomaton entity) {
        String skinName = entity.getSkinName();
        if (skinName != null && !skinName.isEmpty()) {
            ResourceLocation cached = SKIN_CACHE.get(skinName);
            if (cached != null) {
                return cached;
            }
            requestPlayerSkin(skinName);
        }
        return entity.isSlimModel() ? ALEX_SKIN : STEVE_SKIN;
    }

    private static void cacheSkin(String username, ResourceLocation location) {
        if (SKIN_CACHE.size() >= MAX_SKIN_CACHE_SIZE) {
            SKIN_CACHE.clear();
        }
        SKIN_CACHE.put(username, location);
    }

    private static void requestPlayerSkin(String username) {
        if (PENDING_LOOKUPS.putIfAbsent(username, Boolean.TRUE) != null) {
            return;
        }
        SKIN_EXECUTOR.submit(() -> {
            try {
                GameProfile profile = TileEntitySkull.updateGameProfile(new GameProfile(null, username));
                if (profile != null && profile.isComplete()) {
                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        try {
                            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures =
                                    Minecraft.getMinecraft().getSkinManager().loadSkinFromCache(profile);
                            if (textures.containsKey(MinecraftProfileTexture.Type.SKIN)) {
                                ResourceLocation loc = Minecraft.getMinecraft().getSkinManager().loadSkin(
                                        textures.get(MinecraftProfileTexture.Type.SKIN),
                                        MinecraftProfileTexture.Type.SKIN);
                                cacheSkin(username, loc);
                            } else {
                                Minecraft.getMinecraft().getSkinManager().loadProfileTextures(
                                        profile,
                                        (typeIn, location, profileTexture) -> {
                                            if (typeIn == MinecraftProfileTexture.Type.SKIN) {
                                                cacheSkin(username, location);
                                            }
                                        },
                                        true);
                            }
                        } finally {
                            PENDING_LOOKUPS.remove(username);
                        }
                    });
                    return;
                }
                cacheSkin(username, DefaultPlayerSkin.getDefaultSkinLegacy());
            } catch (Exception ignored) {
                cacheSkin(username, DefaultPlayerSkin.getDefaultSkinLegacy());
            } finally {
                if (SKIN_CACHE.containsKey(username)) {
                    PENDING_LOOKUPS.remove(username);
                }
            }
        });
    }
}

