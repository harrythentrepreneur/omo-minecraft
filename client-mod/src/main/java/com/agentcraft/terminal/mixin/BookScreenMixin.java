package com.agentcraft.terminal.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vertically centre read-only books (signed written books and the agent
 * lectern logs). Same seam as {@link BookEditScreenMixin}: {@code BookScreen}
 * also positions everything through a private {@code getTop()} that vanilla
 * pins to the top of the screen while {@code getLeft()} centres horizontally.
 * Redirecting {@code getTop()} to the centred value moves the background, text
 * and the page-turn / close buttons together.
 */
@Mixin(BookScreen.class)
public abstract class BookScreenMixin extends Screen {

    private BookScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "getTop", at = @At("HEAD"), cancellable = true)
    private void omo$centerVertically(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(2, (this.height - 192) / 2));
    }
}
