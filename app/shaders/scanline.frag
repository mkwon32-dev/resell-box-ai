#version 460 core
precision highp float;

#include <flutter/runtime_effect.glsl>

uniform vec2 uSize;       // canvas size in px
uniform float uProgress;  // 0..1 scan-line position (top -> bottom)
uniform sampler2D uImage; // captured photo

out vec4 fragColor;

void main() {
  vec2 uv = FlutterFragCoord().xy / uSize;

  float lineY = uProgress;
  float d = uv.y - lineY;           // signed distance to the scan line
  float band = 0.045;               // influence band around the line
  float w = 1.0 - smoothstep(0.0, band, abs(d));

  // Chromatic displacement near the line: R and B sampled slightly apart.
  float shift = 0.006 * w;
  float r = texture(uImage, vec2(uv.x + shift, uv.y)).r;
  float g = texture(uImage, uv).g;
  float b = texture(uImage, vec2(uv.x - shift, uv.y)).b;
  vec3 color = vec3(r, g, b);

  // Area not yet swept stays dimmed; swept area normal exposure.
  float unswept = step(lineY, uv.y);
  color *= mix(1.0, 0.45, unswept * (1.0 - w));

  // The line itself: thin warm-white core with soft falloff.
  float core = 1.0 - smoothstep(0.0, 0.0035, abs(d));
  float glow = (1.0 - smoothstep(0.0, band, abs(d))) * 0.22;
  vec3 lineColor = vec3(0.95, 0.94, 0.92);
  color += lineColor * (core * 0.9 + glow);

  fragColor = vec4(color, 1.0);
}
