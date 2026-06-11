package com.agentcraft.terminal.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vertically centre the book &amp; quill (the in-hand notebook).
 *
 * <p>Vanilla anchors the book GUI 2&nbsp;px from the top of the screen — only
 * {@code getLeft()} centres it horizontally, {@code getTop()} just returns the
 * constant {@code 2}. At higher GUI scales that pools all the empty space at the
 * bottom, so the book looks shoved up against the top edge.
 *
 * <p>{@code getTop()} is the single source of the book's vertical origin: the
 * decompiled class shows {@code init()}, {@code getDoneButtonY()} (page/done
 * buttons), {@code render()} and {@code renderBackground()} all read it. So
 * redirecting it to the centred value moves the background, page text, the
 * {@code EditBoxWidget} and the buttons together — and because those are real
 * child widgets, their click and hover hit-boxes follow automatically. No mouse
 * coordinate offsetting needed.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {

    // Never merged into the target (Mixin ignores constructors); present only so
    // this can extend Screen and read the inherited public `height` field.
    private BookEditScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "getTop", at = @At("HEAD"), cancellable = true)
    private void omo$centerVertically(CallbackInfoReturnable<Integer> cir) {
        // Mirror getLeft()'s (dimension - 192) / 2, clamped so we never rise
        // above the vanilla 2 px top on a short window.
        cir.setReturnValue(Math.max(2, (this.height - 192) / 2));
    }
}
