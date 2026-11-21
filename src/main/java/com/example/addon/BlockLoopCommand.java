package com.example.addon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;

public class BlockLoopCommand extends Command {
    private final BlockLoopModule module;

    public BlockLoopCommand(BlockLoopModule module) {
        super("blockloop", "Steuert den BlockLoop.");
        this.module = module;
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("add").executes(context -> {
            module.selectTarget();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("start").executes(context -> {
            module.startLoop();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("stop").executes(context -> {
            module.stopLoop();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(context -> {
            module.clearBlocks();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("list").executes(context -> {
            var blocks = module.getBlocks();
            info("Gespeicherte Blöcke: " + blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                BlockPos pos = blocks.get(i);
                info("#" + (i + 1) + ": " + pos.toShortString());
            }
            return SINGLE_SUCCESS;
        }));
    }
}
