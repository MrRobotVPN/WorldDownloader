/*
 * This file is part of World Downloader: A mod to make backups of your
 * multiplayer worlds.
 * http://www.minecraftforum.net/forums/mapping-and-modding/minecraft-mods/2520465
 *
 * Copyright (c) 2014 nairol, cubic72
 * Copyright (c) 2017-2018 Pokechu22, julialy
 *
 * This project is licensed under the MMPLv2.  The full text of the MMPL can be
 * found in LICENSE.md, or online at https://github.com/iopleke/MMPLv2/blob/master/LICENSE.md
 * For information about this the MMPLv2, see http://stopmodreposts.org/
 *
 * Do not redistribute (in modified or unmodified form) without prior permission.
 */
package wdl.handler;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import junit.framework.ComparisonFailure;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.IngameGui;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.gui.NewChatGui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.play.ServerPlayNetHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import wdl.MaybeMixinTest;
import wdl.ReflectionUtils;
import wdl.TestWorld;
import wdl.ducks.INetworkNameable;
import wdl.versioned.VersionedFunctions;


import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.mockito.AdditionalAnswers;

import com.mojang.authlib.GameProfile;

import junit.framework.ComparisonFailure;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import wdl.MaybeMixinTest;
import wdl.ReflectionUtils;
import wdl.TestWorld;
import wdl.ducks.INetworkNameable;
import wdl.versioned.VersionedFunctions;

/**
 * Base logic shared between all tests that use blocks.
 *
 * Ensures that the {@link Bootstrap} is initialized, so that classes such as
 * {@link Blocks} can be used.
 */
public abstract class AbstractWorldBehaviorTest extends MaybeMixinTest {
	private static final Logger LOGGER = LogManager.getLogger();

	protected TestWorld.ClientWorld clientWorld;
	protected TestWorld.ServerWorld serverWorld;
	/** Player entities.  Both have valid, empty inventories. */
	protected ClientPlayerEntity clientPlayer;
	protected ServerPlayerEntity serverPlayer;

	protected Minecraft mc;

	/**
	 * Creates a mock world, returning air for blocks and null for TEs.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	protected void makeMockWorld() {
		mc = mock(Minecraft.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
		ReflectionUtils.findAndSetPrivateField(null, Minecraft.class, Minecraft.class, mc);

		doAnswer(AdditionalAnswers.<Screen>answerVoid(screen -> {
			if (screen instanceof GuiContainer) {
				clientPlayer.openContainer = ((GuiContainer)screen).inventorySlots;
			} else {
				clientPlayer.openContainer = clientPlayer.inventoryContainer;
			}
			mc.currentScreen = screen;
		})).when(mc).displayGuiScreen(any());
		when(mc.isCallingFromMinecraftThread()).thenReturn(true);
		mc.ingameGUI = mock(IngameGui.class);
		when(mc.ingameGUI.getChatGUI()).thenReturn(mock(NewChatGui.class));

		clientWorld = TestWorld.makeClient();
		serverWorld = TestWorld.makeServer();

		ClientPlayNetHandler nhpc = new ClientPlayNetHandler(mc, new Screen() {}, null, new GameProfile(UUID.randomUUID(), "ClientPlayer"));
		ReflectionUtils.findAndSetPrivateField(nhpc, ClientWorld.class, clientWorld);
		clientPlayer = VersionedFunctions.makePlayer(mc, clientWorld, nhpc,
				mock(ClientPlayerEntity.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS))); // Use a mock for the rest of the defaults
		mc.player = clientPlayer;
		Class gameRendererClass = VersionedFunctions.getGameRendererClass();
		Object mockGameRenderer = mock(gameRendererClass);
		ReflectionUtils.findAndSetPrivateField(mc, Minecraft.class, gameRendererClass, mockGameRenderer); // UGLY WORKAROUND, see getGameRendererClass for more info
		// We need to use the constructor, as otherwise getMapInstanceIfExists will fail (it cannot be mocked due to the return type)
		when(mc.gameRenderer.getMapItemRenderer()).thenReturn(
				mock(MapItemRenderer.class, withSettings().useConstructor(new Object[] {null})));
		mc.world = clientWorld;

		mc.gameSettings = VersionedFunctions.createNewGameSettings();

		ServerPlayNetHandler nhps = mock(ServerPlayNetHandler.class);
		doAnswer(AdditionalAnswers.<IPacket<ClientPlayNetHandler>>answerVoid(
				packet -> packet.processPacket(nhpc)))
				.when(nhps).sendPacket(any());
		serverPlayer = new ServerPlayerEntity(mock(MinecraftServer.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)),
				serverWorld, new GameProfile(UUID.randomUUID(), "ServerPlayer"),
				mock(PlayerInteractionManager.class));
		serverPlayer.connection = nhps;

		serverPlayer.inventory = new PlayerInventory(serverPlayer);
		clientPlayer.world = clientWorld;
		serverPlayer.world = serverWorld;
	}

	/**
	 * Puts the given block into the mock worlds at the given position.
	 *
	 * @param pos The position
	 * @param block The block to put
	 */
	protected void placeBlockAt(BlockPos pos, Block block) {
		placeBlockAt(pos, block.getDefaultState());
	}

	/**
	 * Puts the given block into the mock worlds at the given position.
	 *
	 * @param pos The position
	 * @param block The block to put
	 */
	protected void placeBlockAt(BlockPos pos, BlockState state) {
		clientWorld.setBlockState(pos, state);
		serverWorld.setBlockState(pos, state);
	}

	/**
	 * Puts the given block into the mock worlds at the given position.
	 *
	 * @param pos The position
	 * @param block The block to put
	 * @param facing The direction to place the block from
	 */
	protected void placeBlockAt(BlockPos pos, Block block, Direction facing) {
		clientWorld.placeBlockAt(pos, block, clientPlayer, facing);
		serverWorld.placeBlockAt(pos, block, serverPlayer, facing);
	}

	/**
	 * Compares the two compounds, raising an assertion error if they do not match.
	 *
	 * @param expected The expected NBT
	 * @param actual The actual NBT
	 */
	protected void assertSameNBT(CompoundNBT expected, CompoundNBT actual) {
		// Don't use real AssertionError, but instead use a special JUnit one,
		// which has an interactive comparison tool
		if (!expected.equals(actual)) {
			throw new ComparisonFailure("Mismatched NBT!", VersionedFunctions.nbtString(expected), VersionedFunctions.nbtString(actual));
		}
	}

	/**
	 * Checks that mixins were applied, and if not, then the test will be ignored
	 * (not failed).
	 *
	 * @see org.junit.Assume
	 */
	protected static void assumeMixinsApplied() {
		boolean applied = INetworkNameable.class.isAssignableFrom(Inventory.class);
		if (!applied) {
			LOGGER.warn("Mixins were not applied; skipping this test");
		}
		assumeTrue("Expected mixins to be applied", applied);
	}

	@After
	public void resetState() {
		// Just to avoid dangling references to mocks
		clientWorld = null;
		serverWorld = null;
		clientPlayer = null;
		serverPlayer = null;
		mc = null;
		ReflectionUtils.findAndSetPrivateField(null, Minecraft.class, Minecraft.class, null);
	}
}