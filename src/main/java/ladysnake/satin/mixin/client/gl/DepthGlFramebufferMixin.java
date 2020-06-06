/*
 * Satin
 * Copyright (C) 2019-2020 Ladysnake
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; If not, see <https://www.gnu.org/licenses>.
 */
package ladysnake.satin.mixin.client.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import ladysnake.satin.Satin;
import ladysnake.satin.api.experimental.ReadableDepthFramebuffer;
import ladysnake.satin.config.OptionalFeature;
import ladysnake.satin.config.SatinFeatures;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.TextureUtil;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.spongepowered.asm.mixin.injection.At.Shift.AFTER;

@Mixin(Framebuffer.class)
public abstract class DepthGlFramebufferMixin implements ReadableDepthFramebuffer {
    @Shadow @Final public boolean useDepthAttachment;

    @Shadow public int depthAttachment;
    @Shadow public int textureWidth;
    @Shadow public int textureHeight;

    @Shadow public int fbo;
    private static final OptionalFeature SATIN$READABLE_DEPTH_FRAMEBUFFERS = SatinFeatures.getInstance().readableDepthFramebuffers;

    @Shadow public abstract void beginWrite(boolean boolean_1);

    private int satin$depthTexture = -1;
    private int satin$actualDepthTexture = -99;
    private int satin$stillDepthTexture = -1;

    @Inject(
            method = "initFbo",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;framebufferTexture2D(IIIII)V", shift = AFTER, ordinal = 1)
    )
    private void initFbo(int width, int height, boolean flushErrors, CallbackInfo ci) {
        if (this.useDepthAttachment)
            if (SatinFeatures.getInstance().readableDepthFramebuffers.isActive()) {
                // Delete the depth render buffer, it will not be used
                GlStateManager.deleteFramebuffers(this.depthAttachment);
                this.depthAttachment = -1;
                this.satin$depthTexture = satin$setupDepthTexture();

                GlStateManager.framebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, this.satin$depthTexture, 0);
            }
            this.satin$stillDepthTexture = satin$setupDepthTexture();
    }

    private int satin$setupDepthTexture() {
        int shadowMap = GL11.glGenTextures();
        RenderSystem.bindTexture(shadowMap);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager.texImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, this.textureWidth, this.textureHeight, 0,GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE, null);
        return shadowMap;
    }

    @Inject(method = "delete", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/client/gl/Framebuffer;depthAttachment:I"))
    private void delete(CallbackInfo ci) {
        this.satin$deleteDepthTexture();
        if (this.satin$stillDepthTexture > -1) {
            // delete texture
            TextureUtil.method_24957(this.satin$depthTexture);
            this.satin$depthTexture = -1;
        }
        this.satin$actualDepthTexture = -99;
    }

    private void satin$deleteDepthTexture() {
        if (this.satin$depthTexture > -1) {
            TextureUtil.method_24957(this.satin$depthTexture);
            this.satin$depthTexture = -1;
        }
    }

    @Unique
    private static int getBoundFramebuffer() {
        return GlStateManager.getInteger(GL_FRAMEBUFFER_BINDING);
    }

    @Override
    public int getCurrentDepthTexture() {
        // < -1 means it hasn't been initialized yet
        if (this.satin$actualDepthTexture < -1) {
            int actualDepthTexture;
            int boundFbo = getBoundFramebuffer();
            if (boundFbo != this.fbo) {
                throw new IllegalStateException("Framebuffer must be bound before querying its depth texture for the first time");
            }

            // Get the type of depth attachment. In vanilla it should be GL_RENDERBUFFER, with our injections it should be GL_TEXTURE
            int attachmentObjectType = ARBFramebufferObject.glGetFramebufferAttachmentParameteri(
                    GL_FRAMEBUFFER,
                    GL_DEPTH_ATTACHMENT,
                    ARBFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
            );
            if (!this.useDepthAttachment || !SATIN$READABLE_DEPTH_FRAMEBUFFERS.isConfigEnabled()) {
                // if we aren't using depth, there will be no depth texture
                actualDepthTexture = -1;
                Satin.LOGGER.error("[Satin] A mod has queried a depth texture for a framebuffer that has none");
            } else if (attachmentObjectType != GL_TEXTURE) {
                // there is no depth texture attached! figure out what's going on
                String type;
                switch (attachmentObjectType) {
                    case GL30.GL_RENDERBUFFER: type = "GL_RENDERBUFFER"; break;
                    case GL30.GL_NONE: type = "GL_NONE"; break;
                    case GL_FRAMEBUFFER_DEFAULT: type = "GL_FRAMEBUFFER_DEFAULT"; break;
                    default: type = "UNKNOWN (" + attachmentObjectType + ")";
                }
                // Our framebuffer replacement is not doing its job
                Satin.LOGGER.error("[Satin] A framebuffer has no readable depth attachment (expected a texture attachment, got {} in {})", type, this.getClass().getName());
                actualDepthTexture = -1;
            } else {
                // there is a depth texture attached
                actualDepthTexture = ARBFramebufferObject.glGetFramebufferAttachmentParameteri(
                        GL_FRAMEBUFFER,
                        GL_DEPTH_ATTACHMENT,
                        ARBFramebufferObject.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
                );
                if (actualDepthTexture != this.satin$depthTexture) {
                    Satin.LOGGER.warn("[Satin] A framebuffer has a depth texture different from the one assigned");
                    // Free our own depth texture, since it is not used
                    this.satin$deleteDepthTexture();
                }
            }
            this.satin$actualDepthTexture = actualDepthTexture;
        }
        return this.satin$actualDepthTexture;
    }

    @Override
    public int getStillDepthMap() {
        return this.satin$stillDepthTexture;
    }

    @Override
    public void freezeDepthMap() {
        if (SATIN$READABLE_DEPTH_FRAMEBUFFERS.isActive()) {
            this.beginWrite(false);
            RenderSystem.bindTexture(this.satin$stillDepthTexture);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, this.textureWidth, this.textureHeight);
        }
    }
}
