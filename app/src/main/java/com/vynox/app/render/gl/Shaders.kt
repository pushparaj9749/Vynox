package com.vynox.app.render.gl

/**
 * All GLSL used by the compositor.
 *
 * The pipeline is: content -> [mask] -> [effect chain] -> composite with blend
 * mode onto the scene. Effects of the "colour" family are fused into a single
 * [GRADE] pass; blur/sharpen/glow need extra passes.
 */
object Shaders {

    const val VERTEX_QUAD = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        uniform mat3 uMatrix;
        varying vec2 vTexCoord;
        void main() {
            vec3 p = uMatrix * vec3(aPosition, 1.0);
            gl_Position = vec4(p.xy, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    /** Straight texture copy (used for images, text, shapes and blits). */
    const val FRAG_TEXTURE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform float uOpacity;
        varying vec2 vTexCoord;
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord);
            gl_FragColor = vec4(c.rgb, c.a) * uOpacity;
        }
    """.trimIndent()

    /** Video frames decoded through a SurfaceTexture (external OES sampler). */
    const val FRAG_VIDEO = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        uniform mat3 uTexMatrix;
        uniform float uOpacity;
        varying vec2 vTexCoord;
        void main() {
            vec2 uv = (uTexMatrix * vec3(vTexCoord, 1.0)).xy;
            vec4 c = texture2D(uTexture, uv);
            gl_FragColor = vec4(c.rgb, c.a) * uOpacity;
        }
    """.trimIndent()

    /** Solid colour fill (backgrounds, placeholders). */
    const val FRAG_SOLID = """
        precision mediump float;
        uniform vec4 uColor;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    /**
     * Mask generation: analytic shapes with feathered edges.
     * uShape: 0 = rectangle, 1 = ellipse, 2 = path texture.
     */
    const val FRAG_MASK = """
        precision mediump float;
        uniform int uShape;
        uniform sampler2D uPath;
        uniform vec2 uCenter;
        uniform vec2 uHalfSize;
        uniform float uRotation;
        uniform float uFeather;
        uniform float uOpacity;
        uniform float uInvert;
        uniform vec2 uCanvasSize;
        varying vec2 vTexCoord;

        float roundedBox(vec2 p, vec2 b, float r) {
            vec2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        void main() {
            vec2 pixel = vTexCoord * uCanvasSize;
            vec2 delta = pixel - uCenter;
            float c = cos(uRotation);
            float s = sin(uRotation);
            vec2 local = vec2(delta.x * c + delta.y * s, -delta.x * s + delta.y * c);

            float dist;
            if (uShape == 0) {
                dist = roundedBox(local, uHalfSize, 0.0);
            } else if (uShape == 1) {
                float rx = max(uHalfSize.x, 0.0001);
                float ry = max(uHalfSize.y, 0.0001);
                dist = (length(local / vec2(rx, ry)) - 1.0) * min(rx, ry);
            } else {
                float a = texture2D(uPath, vTexCoord).a;
                gl_FragColor = vec4(1.0, 1.0, 1.0, a * uOpacity);
                return;
            }

            float feather = max(uFeather, 0.0001);
            float alpha = 1.0 - smoothstep(-feather, feather, dist);
            alpha = mix(alpha, 1.0 - alpha, uInvert);
            gl_FragColor = vec4(1.0, 1.0, 1.0, alpha * uOpacity);
        }
    """.trimIndent()

    /** Applies a mask texture to a layer: result = layer * maskAlpha (with mode). */
    const val FRAG_MASK_APPLY = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform sampler2D uMask;
        uniform int uMode;      // 0 add, 1 subtract, 2 intersect
        uniform float uFeather;
        varying vec2 vTexCoord;
        void main() {
            vec4 src = texture2D(uTexture, vTexCoord);
            float m = texture2D(uMask, vTexCoord).a;
            if (uMode == 1) m = 1.0 - m;
            gl_FragColor = vec4(src.rgb, src.a * clamp(m, 0.0, 1.0));
        }
    """.trimIndent()

    /**
     * Fused colour grading pass: brightness, contrast, saturation, hue,
     * lightness, exposure, temperature, tint, invert, vignette and opacity.
     * Neutral values leave the image untouched.
     */
    const val FRAG_GRADE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uHue;
        uniform float uLightness;
        uniform float uExposure;
        uniform float uTemperature;
        uniform float uGreenTint;
        uniform float uInvert;
        uniform float uOpacity;
        uniform vec3 uTintColor;
        uniform float uTintAmount;
        uniform float uVignetteAmount;
        uniform float uVignetteSize;
        uniform float uVignetteRoundness;
        varying vec2 vTexCoord;

        vec3 rgb2hsl(vec3 color) {
            float maxc = max(max(color.r, color.g), color.b);
            float minc = min(min(color.r, color.g), color.b);
            float l = (maxc + minc) * 0.5;
            float h = 0.0;
            float s = 0.0;
            float d = maxc - minc;
            if (d > 0.0001) {
                s = l > 0.5 ? d / (2.0 - maxc - minc) : d / (maxc + minc);
                if (maxc == color.r) h = (color.g - color.b) / d + (color.g < color.b ? 6.0 : 0.0);
                else if (maxc == color.g) h = (color.b - color.r) / d + 2.0;
                else h = (color.r - color.g) / d + 4.0;
                h /= 6.0;
            }
            return vec3(h, s, l);
        }

        float hue2rgb(float p, float q, float t) {
            if (t < 0.0) t += 1.0;
            if (t > 1.0) t -= 1.0;
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
            if (t < 1.0 / 2.0) return q;
            if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
            return p;
        }

        vec3 hsl2rgb(vec3 hsl) {
            if (hsl.y < 0.0001) return vec3(hsl.z);
            float q = hsl.z < 0.5 ? hsl.z * (1.0 + hsl.y) : hsl.z + hsl.y - hsl.z * hsl.y;
            float p = 2.0 * hsl.z - q;
            return vec3(hue2rgb(p, q, hsl.x + 1.0 / 3.0), hue2rgb(p, q, hsl.x), hue2rgb(p, q, hsl.x - 1.0 / 3.0));
        }

        void main() {
            vec4 src = texture2D(uTexture, vTexCoord);
            vec3 color = src.rgb;

            // Exposure (stops) then brightness (additive)
            color *= exp2(uExposure);
            color += uBrightness;

            // Contrast around mid grey
            color = (color - 0.5) * (1.0 + uContrast) + 0.5;

            // Hue / saturation / lightness in HSL
            vec3 hsl = rgb2hsl(clamp(color, 0.0, 1.0));
            hsl.x = fract(hsl.x + uHue / 360.0);
            hsl.y = clamp(hsl.y * uSaturation, 0.0, 1.0);
            hsl.z = clamp(hsl.z + uLightness, 0.0, 1.0);
            color = hsl2rgb(hsl);

            // White balance
            color.r += uTemperature * 0.15;
            color.b -= uTemperature * 0.15;
            color.g += uGreenTint * 0.1;

            // Invert
            color = mix(color, 1.0 - color, uInvert);

            // Solid tint blend
            color = mix(color, uTintColor, clamp(uTintAmount, 0.0, 1.0));

            // Vignette
            vec2 centered = vTexCoord - 0.5;
            float round = mix(1.0, 2.0, clamp(uVignetteRoundness, 0.0, 1.0));
            float dist = pow(pow(abs(centered.x) * 2.0, round) + pow(abs(centered.y) * 2.0, round), 1.0 / round);
            float vig = 1.0 - clamp(uVignetteAmount, 0.0, 1.0) *
                smoothstep(uVignetteSize, uVignetteSize + 0.6, dist);
            color *= vig;

            gl_FragColor = vec4(clamp(color, 0.0, 1.0), src.a) * uOpacity;
        }
    """.trimIndent()

    /** Separable gaussian blur (radius in pixels, direction (1,0) or (0,1)). */
    const val FRAG_BLUR = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform vec2 uDirection;
        uniform float uRadius;
        varying vec2 vTexCoord;
        void main() {
            float radius = max(uRadius, 0.0001);
            float sigma = radius * 0.5;
            vec4 sum = vec4(0.0);
            float total = 0.0;
            for (int i = -8; i <= 8; i++) {
                float offset = float(i) * (radius / 8.0);
                float w = exp(-(offset * offset) / (2.0 * sigma * sigma));
                vec2 uv = vTexCoord + uDirection * uTexelSize * offset;
                sum += texture2D(uTexture, uv) * w;
                total += w;
            }
            gl_FragColor = sum / max(total, 0.0001);
        }
    """.trimIndent()

    /** Unsharp-mask style sharpening. */
    const val FRAG_SHARPEN = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uAmount;
        uniform float uRadius;
        varying vec2 vTexCoord;
        void main() {
            vec4 center = texture2D(uTexture, vTexCoord);
            vec2 step = uTexelSize * max(uRadius, 0.5);
            vec4 blur = (
                texture2D(uTexture, vTexCoord + vec2(step.x, 0.0)) +
                texture2D(uTexture, vTexCoord - vec2(step.x, 0.0)) +
                texture2D(uTexture, vTexCoord + vec2(0.0, step.y)) +
                texture2D(uTexture, vTexCoord - vec2(0.0, step.y))
            ) * 0.25;
            gl_FragColor = center + (center - blur) * uAmount * 4.0;
        }
    """.trimIndent()

    /** Bright pass for glow. */
    const val FRAG_GLOW_BRIGHT = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform float uThreshold;
        varying vec2 vTexCoord;
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord);
            float lum = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
            float factor = smoothstep(uThreshold, uThreshold + 0.25, lum);
            gl_FragColor = vec4(c.rgb * factor, c.a * factor);
        }
    """.trimIndent()

    /** Adds the blurred glow back over the original layer. */
    const val FRAG_GLOW_COMBINE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform sampler2D uGlow;
        uniform float uIntensity;
        uniform vec3 uColor;
        varying vec2 vTexCoord;
        void main() {
            vec4 base = texture2D(uTexture, vTexCoord);
            vec4 glow = texture2D(uGlow, vTexCoord);
            vec3 added = base.rgb + glow.rgb * uColor * uIntensity;
            gl_FragColor = vec4(clamp(added, 0.0, 1.0), max(base.a, glow.a * min(uIntensity, 1.0)));
        }
    """.trimIndent()

    /**
     * Composites a layer onto the scene using a real blend equation.
     * uMode: 0 normal, 1 multiply, 2 screen, 3 overlay, 4 darken, 5 lighten,
     *        6 add, 7 difference, 8 exclusion, 9 soft light
     */
    const val FRAG_BLEND = """
        precision mediump float;
        uniform sampler2D uBackdrop;
        uniform sampler2D uSource;
        uniform float uOpacity;
        uniform int uMode;
        varying vec2 vTexCoord;

        vec3 overlay(vec3 b, vec3 s) {
            return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
        }

        vec3 softLight(vec3 b, vec3 s) {
            return mix(
                2.0 * b * s + b * b * (1.0 - 2.0 * s),
                sqrt(clamp(b, 0.0, 1.0)) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s),
                step(0.5, s)
            );
        }

        void main() {
            vec4 dst = texture2D(uBackdrop, vTexCoord);
            vec4 src = texture2D(uSource, vTexCoord);
            float sa = clamp(src.a * uOpacity, 0.0, 1.0);
            if (sa <= 0.0) {
                gl_FragColor = dst;
                return;
            }
            vec3 b = dst.rgb;
            vec3 s = src.rgb;
            vec3 blended;
            if (uMode == 1) blended = b * s;
            else if (uMode == 2) blended = 1.0 - (1.0 - b) * (1.0 - s);
            else if (uMode == 3) blended = overlay(b, s);
            else if (uMode == 4) blended = min(b, s);
            else if (uMode == 5) blended = max(b, s);
            else if (uMode == 6) blended = min(b + s, vec3(1.0));
            else if (uMode == 7) blended = abs(b - s);
            else if (uMode == 8) blended = b + s - 2.0 * b * s;
            else if (uMode == 9) blended = softLight(b, s);
            else blended = s;

            vec3 result = mix(b, blended, sa);
            gl_FragColor = vec4(result, clamp(dst.a + sa, 0.0, 1.0));
        }
    """.trimIndent()
}
