package dev.lyo.hortay.ui.media

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Luma-key shader effect for Telegram web's WebM custom-emojis.
 *
 * Why we need it: Telegram's `t.me/i/emoji/<id>.json` endpoint serves WebM
 * stickers pre-rendered as `yuv420p` (no alpha-channel) for browser
 * compatibility. The original sticker DOES carry alpha, but it's encoded as a
 * sidecar VP9 stream via Matroska BlockAdditional which standard `MediaCodec`
 * decoders ignore — so we receive an opaque RGB plane with the original
 * transparent regions baked against `srgb(0,0,0)` (verified against multiple
 * t.me emoji ids on 2026-05-06: corners are `srgb(0,0,0)`, `Maximum block
 * additional ID: 1`, and `Video alpha mode: 1` in the MKV header).
 *
 * The shader reproduces what Telegram Web does in CSS via
 * `mix-blend-mode: lighten` over the same WebM source: for every pixel,
 * derive alpha from the maximum colour component (`max(r, g, b)`), so black
 * pixels become fully transparent and saturated colours stay opaque. Mid-tones
 * — the antialiased outline of the glyph — blend smoothly into whatever is
 * behind the [TextureView]. The colour channels stay as-is: Telegram's bake
 * formula is `rgb_real · alpha + bg · (1 - alpha)` with `bg = 0`, which
 * simplifies to `rgb_real · alpha` — i.e. the source is ALREADY premultiplied,
 * so emitting `vec4(c.rgb, a)` from this shader produces correctly
 * premultiplied output for the downstream `TextureView` blit (which expects
 * premultiplied alpha when `isOpaque = false`).
 *
 * Performance: 100×100 (sticker resolution) × 60 fps × ~30 frames = trivial
 * GPU load. Runs on the same EGL context ExoPlayer's
 * `MediaCodecVideoRenderer` already uses, no extra context-switching.
 */
@UnstableApi
class LumaKeyEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LumaKeyShaderProgram(useHdr)
}

@UnstableApi
private class LumaKeyShaderProgram(useHdr: Boolean) :
    BaseGlShaderProgram(useHdr, /* texturePoolCapacity */ 1) {

    private val program: GlProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex */ 0)
            program.bindAttributesAndUniforms()
            // Two triangles → fullscreen quad. The vertex coordinates
            // come from `getNormalizedCoordinateBounds()`; the texture
            // coordinates are derived in the vertex shader from the
            // position so we don't need a separate aTexCoord attribute.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first */ 0, /* count */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            // Logged at media3 level; nothing actionable here.
        }
    }

    private companion object {
        // Standard fullscreen-quad vertex shader. The output texcoord is derived
        // from the position so callers don't need to bind a second attribute
        // buffer — `getNormalizedCoordinateBounds()` already covers the full
        // -1..1 range, mapped to 0..1 for texture sampling.
        private const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Fragment shader — see class doc for the math derivation.
        private const val FRAGMENT_SHADER = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float a = max(c.r, max(c.g, c.b));
  gl_FragColor = vec4(c.rgb, a);
}
"""
    }
}
