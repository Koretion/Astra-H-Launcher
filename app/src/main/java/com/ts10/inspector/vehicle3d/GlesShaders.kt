package com.ts10.inspector.vehicle3d

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

internal const val VERTEX_SHADER = """
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUv;
void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorld = world.xyz;
    vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
    vUv = aUv;
    gl_Position = uViewProj * world;
}
"""

// Lightweight PBR-like shading: hemisphere ambient, key and fill lights, Blinn-Phong highlight,
// Schlick fresnel with a sky/ground reflection, emissive lamps, ACES tone mapping and sRGB output.
internal const val FRAGMENT_SHADER = """
precision mediump float;
uniform vec4 uBaseColor;
uniform vec3 uEmissive;
uniform float uRoughness;
uniform float uMetallic;
uniform float uHasTexture;
uniform sampler2D uTexture;
uniform vec3 uEye;
uniform vec3 uSky;
uniform vec3 uGround;
uniform float uMirror;
uniform float uFloorY;
uniform float uReflect;
uniform float uFadeHeight;
uniform vec3 uBackground;
uniform vec3 uFloor;
uniform vec2 uFloorCenter;
uniform vec2 uFloorRadius;
uniform float uFadeBottom;
uniform float uCabin;
uniform vec3 uCabinMin;
uniform vec3 uCabinMax;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUv;
void main() {
    // The reflection is shaded at the real (unmirrored) point as seen from the mirrored eye.
    vec3 world = vWorld;
    vec3 nrm = vNormal;
    if (uMirror > 0.5) {
        world.y = 2.0 * uFloorY - world.y;
        nrm.y = -nrm.y;
    }
    vec3 n = normalize(nrm);
    vec3 v = normalize(uEye - world);
    if (dot(n, v) < 0.0) n = -n;
    vec4 base = uBaseColor;
    if (uHasTexture > 0.5) {
        vec4 t = texture2D(uTexture, vUv);
        base *= vec4(pow(t.rgb, vec3(2.2)), t.a);
    }
    vec3 key = normalize(vec3(-0.45, 0.85, 0.3));
    vec3 fill = normalize(vec3(0.6, 0.4, -0.7));
    float rough = clamp(uRoughness, 0.05, 1.0);
    float shininess = min(2.0 / (rough * rough * rough * rough) , 600.0);
    vec3 h = normalize(key + v);
    float ndl = max(dot(n, key), 0.0);
    float ndf = max(dot(n, fill), 0.0);
    vec3 hemi = mix(uGround, uSky, n.y * 0.5 + 0.5);
    vec3 f0 = mix(vec3(0.04), base.rgb, uMetallic);
    float fv = pow(1.0 - max(dot(n, v), 0.0), 5.0);
    vec3 fresnel = f0 + (vec3(1.0) - f0) * fv;
    vec3 r = reflect(-v, n);
    vec3 env = mix(uGround, uSky * 1.25, smoothstep(-0.15, 0.35, r.y));
    vec3 diffuse = base.rgb * (1.0 - uMetallic) * (hemi * 0.6 + ndl * 1.15 + ndf * 0.3);
    float spec = pow(max(dot(n, h), 0.0), shininess) * (shininess + 8.0) / 25.0 * ndl;
    vec3 specular = fresnel * (spec * 1.4 + env * (1.0 - rough) * 0.85);
    vec3 inside = smoothstep(uCabinMin, uCabinMin + 0.06, world) * (1.0 - smoothstep(uCabinMax - 0.06, uCabinMax, world));
    float occlusion = 1.0 - 0.7 * uCabin * inside.x * inside.y * inside.z;
    vec3 color = (diffuse + specular) * occlusion + uEmissive;
    color = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
    vec3 srgb = pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2));
    if (uMirror > 0.5) {
        // Same spot as FLOOR_FRAGMENT_SHADER so the faded reflection melts into the floor.
        float d = length((vWorld.xz - uFloorCenter) / uFloorRadius);
        // gl_FragCoord.y is 0 at the bottom edge of the frame: nothing may end in a hard cut there.
        float screen = smoothstep(0.0, uFadeBottom, gl_FragCoord.y);
        vec3 floorColor = mix(uBackground, uFloor, (1.0 - smoothstep(0.15, 1.0, d)) * screen);
        float depth = 1.0 - smoothstep(0.0, uFadeHeight, uFloorY - vWorld.y);
        float fade = depth * uReflect * screen * screen;
        srgb = mix(floorColor, srgb, fade);
    }
    gl_FragColor = vec4(srgb, base.a);
}
"""

internal const val FLOOR_FRAGMENT_SHADER = """
precision mediump float;
uniform vec3 uBackground;
uniform vec3 uFloor;
uniform float uFadeBottom;
varying vec2 vLocal;
void main() {
    float d = length(vLocal);
    float screen = smoothstep(0.0, uFadeBottom, gl_FragCoord.y);
    gl_FragColor = vec4(mix(uBackground, uFloor, (1.0 - smoothstep(0.15, 1.0, d)) * screen), 1.0);
}
"""

internal const val SHADOW_VERTEX_SHADER = """
attribute vec2 aPosition;
uniform mat4 uViewProj;
varying vec2 vLocal;
void main() {
    vLocal = aPosition;
    gl_Position = uViewProj * vec4(aPosition, 0.0, 1.0);
}
"""

internal const val SHADOW_FRAGMENT_SHADER = """
precision mediump float;
uniform float uStrength;
varying vec2 vLocal;
void main() {
    float d = length(vLocal);
    gl_FragColor = vec4(0.0, 0.0, 0.0, (1.0 - smoothstep(0.45, 1.0, d)) * uStrength);
}
"""

internal const val ROAD_VERTEX_SHADER = """
attribute vec2 aPosition;
uniform mat4 uViewProj;
uniform mat4 uModel;
varying vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPosition, 0.0, 1.0);
    vWorld = world.xyz;
    gl_Position = uViewProj * world;
}
"""

// Lanes follow the 2D scene: boundary 0 is the left edge, the car stays centred in its lane,
// exterior edges are red, a two-way centre line is yellow and inner lines are white.
internal const val ROAD_FRAGMENT_SHADER = """
precision mediump float;
uniform vec3 uAsphalt;
uniform vec3 uLine;
uniform vec3 uEdge;
uniform vec3 uActive;
uniform float uOpacity;
uniform float uFrontZ;
uniform float uCarX;
uniform float uCurve;
uniform float uLanes;
uniform float uLaneIndex;
uniform float uTravel;
uniform float uStyles[7];
uniform float uCenterYellow;
uniform float uFadeBottom;
varying vec3 vWorld;
const float W = 3.5;
void main() {
    float ahead = uFrontZ - vWorld.z;
    float bendDistance = max(ahead, 0.0);
    float x = vWorld.x - uCarX - uCurve * bendDistance * bendDistance;
    float aa = 0.03 + bendDistance * 0.006;
    float rightEdge = (uLaneIndex + 0.5) * W;
    float leftEdge = rightEdge - uLanes * W;
    float onRoad = smoothstep(leftEdge - 0.7 - aa, leftEdge - 0.7, x) *
        (1.0 - smoothstep(rightEdge + 0.7, rightEdge + 0.7 + aa, x));
    vec3 color = mix(uAsphalt, uActive, (1.0 - smoothstep(W * 0.5 - aa, W * 0.5, abs(x))) * 0.2);
    float alpha = onRoad * 0.92;
    for (int i = 0; i <= 6; i++) {
        float fi = float(i);
        if (fi > uLanes + 0.5) break;
        float style = uStyles[i];
        if (style < 0.5) continue;
        float bx = leftEdge + fi * W;
        bool exterior = i == 0 || fi > uLanes - 0.5;
        float hw = exterior ? 0.09 : 0.06;
        float line = 1.0 - smoothstep(hw, hw + aa, abs(x - bx));
        if (style > 2.5) {
            line = max(1.0 - smoothstep(0.05, 0.05 + aa, abs(x - bx + 0.12)),
                1.0 - smoothstep(0.05, 0.05 + aa, abs(x - bx - 0.12)));
        } else if (style > 1.5) {
            line *= step(fract((ahead + uTravel) / 9.0), 0.4);
        }
        vec3 lineColor = uLine;
        if (i == 0 && uCenterYellow > 0.5) {
            lineColor = vec3(1.0, 0.79, 0.16);
        } else if (exterior) {
            lineColor = uEdge;
        }
        color = mix(color, lineColor, line);
        alpha = max(alpha, line);
    }
    float fade = (1.0 - smoothstep(16.0, 46.0, ahead)) * smoothstep(-11.0, -4.0, ahead);
    float screen = smoothstep(0.0, uFadeBottom, gl_FragCoord.y);
    gl_FragColor = vec4(color, alpha * fade * screen * uOpacity);
}
"""

internal const val POOL_FRAGMENT_SHADER = """
precision mediump float;
uniform vec3 uColor;
uniform float uStrength;
varying vec2 vLocal;
void main() {
    float a = 1.0 - smoothstep(0.0, 1.0, length(vLocal));
    a = a * a * uStrength;
    gl_FragColor = vec4(uColor * a, a);
}
"""

internal const val BEAM_VERTEX_SHADER = """
attribute vec3 aPosition;
attribute float aAlong;
uniform mat4 uViewProj;
uniform float uMirror;
uniform float uFloorY;
varying float vAlong;
varying float vHeight;
void main() {
    vec3 p = aPosition;
    vHeight = p.y - uFloorY;
    if (uMirror > 0.5) p.y = 2.0 * uFloorY - p.y;
    vAlong = aAlong;
    gl_Position = uViewProj * vec4(p, 1.0);
}
"""

// Additive light cone: brightest just ahead of the lamp, gone at its end, cut at the ground.
internal const val BEAM_FRAGMENT_SHADER = """
precision mediump float;
uniform vec3 uColor;
uniform float uStrength;
varying float vAlong;
varying float vHeight;
void main() {
    if (vHeight < 0.0) discard;
    float a = pow(1.0 - vAlong, 1.8) * smoothstep(0.0, 0.06, vAlong) * uStrength;
    gl_FragColor = vec4(uColor * a, a);
}
"""

// Detected road users: flat-lit boxes, fading with distance like the road they stand on.
internal const val BOX_VERTEX_SHADER = """
attribute vec3 aPosition;
attribute vec3 aNormal;
uniform mat4 uViewProj;
uniform mat4 uModel;
varying vec3 vNormal;
varying vec3 vWorld;
void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorld = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    gl_Position = uViewProj * world;
}
"""

internal const val BOX_FRAGMENT_SHADER = """
precision mediump float;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uEmissive;
uniform float uFrontZ;
uniform float uFadeBottom;
varying vec3 vNormal;
varying vec3 vWorld;
void main() {
    vec3 n = normalize(vNormal);
    float diffuse = max(dot(n, normalize(vec3(-0.35, 0.85, 0.45))), 0.0);
    vec3 color = mix(uColor * (0.42 + 0.58 * diffuse), uColor, uEmissive);
    float ahead = uFrontZ - vWorld.z;
    float fade = 1.0 - smoothstep(16.0, 46.0, ahead);
    float screen = smoothstep(0.0, uFadeBottom, gl_FragCoord.y);
    gl_FragColor = vec4(color, uAlpha * fade * screen);
}
"""
