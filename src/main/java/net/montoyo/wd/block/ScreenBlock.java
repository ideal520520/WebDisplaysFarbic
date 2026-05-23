package net.montoyo.wd.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.item.ItemLinker;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;

public class ScreenBlock extends Block implements EntityBlock {
    public ScreenBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ScreenBlockEntity screen) {
            Item item = stack.getItem();

            if (item == WDRegistries.CONFIGURATOR) {
                BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                // Client GUI opening is handled in ClientInit via UseBlockCallback
                // to avoid importing client-only classes on the server
                return ItemInteractionResult.SUCCESS;
            }

            if (item == WDRegistries.LINKER) {
                BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                ItemLinker.onRightClickScreen(player, pos, side);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScreenBlockEntity screen) {
                screen.onDestroy();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
