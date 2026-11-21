# XMB assets for the Insignia launcher

The files in `assets/xmb/` adapt the visual and navigation assets from
[Sanan Goyal's XMB TV Home](https://github.com/sanangoyal/XBM-TV-Home), reference
commit `4a4396588da9bfd34897b6c06699dd83929ff3c2`.

## Included files

| Path under `assets/xmb/` | Contents | Size |
| --- | --- | ---: |
| `rodin.ttf` | Embedded SCE-PS3 Rodin LATIN font converted from WOFF2 to TrueType; 324 mapped glyphs | 38,260 bytes |
| `icons/*.png` | 125 transparent 256 × 256 glyphs, source aliases and category aliases | 1,983,134 bytes |
| `logos/*.png` | Eight transparent 192 × 192 monochrome app marks | 39,095 bytes |
| `sounds/*.wav` | `cursor`, `category`, `decide`, `cancel`; original 44.1 kHz mono WAVs | 10,596 bytes |
| `bg_blue.mp4` | Optimized 1280 × 720 blue ribbon loop | 4,857,355 bytes |
| `bg_blue_g.png` | Original 17 × 9 blue gradient image; intended to be scaled | 240 bytes |
| `bg_blue_r.png` | 1280 × 720 blue ribbon still | 411,832 bytes |
| `bg_dark.mp4` | Optimized 1280 × 720 neutral charcoal ribbon loop | 1,028,446 bytes |
| `bg_dark_g.png` | Neutral 17 × 9 dark gradient image; intended to be scaled | 151 bytes |
| `bg_dark_r.png` | Matching neutral 1280 × 720 dark ribbon still | 40,479 bytes |
| `icon-aliases.json` | Mapping from reference aliases and category names to source glyph IDs | See file |
| `notices/` | Reference MIT license, asset ownership notice and readable asset README | See files |

No ambient music is included.

## Dark background adaptation

The `bg_dark.*` media adapts the reference `android/assets/bg_black.mp4`
and `bg_black_g.png` / `bg_black_r.png`. The reference black ribbon is
desaturated to neutral grayscale, its intensity reduced to 64%, and a
subtle charcoal gradient is added. The gradient uses equal RGB channels
at levels 4–12 out of 255, avoiding blue or purple tint. The result keeps
the original quiet moving ribbon and particles visible beneath white
text while leaving most of the screen close to black.

The initial adaptation used 1920 × 1080 H.264 High video. Version 1.0.3
optimizes both theme loops as described below; neither has an audio track.
The static PNG uses the same grayscale/gradient transformation. Decoding
a preview frame verifies that its red, green and blue channels match
exactly. A decoded frame with sample white Rodin text was visually
inspected under `work/launcher/`. Blue retains its reference palette and motion.

## Background performance encoding (1.0.3)

Both 20-second loops retain their original 24 fps motion and palette, scaled
with Lanczos to 1280 × 720. Encode with libx264 Main level 3.1, yuv420p,
one reference frame, no B-frames, 48-frame GOP, MP4 fast-start, and no audio.
The settings are `-preset fast -crf 20` for Blue and `-crf 18` for Dark,
with `-refs 1 -bf 0 -g 48 -keyint_min 48 -sc_threshold 0` for both.
The matching ribbon PNGs are scaled to 1280 × 720; 17 × 9 gradients stay unchanged.
Only background media is scaled. The native menu text and glyphs render at
1920 × 1080. The smallest blue particles become slightly softer; comparison
frames preserve the overall ribbon, colors and gradients. Dark is visually close.
Decoded pixels per frame fall by 55.6%; this is not a measured overall speedup.

## Native glyph names

Use these category filenames: `settings`, `photo`, `music`, `video`, `tv`,
`game`, `network`, `friends` (append `.png`). The original 58 `g1_*`, `g2_*`
and `g3_*` filenames and all 37 aliases in the reference `tools/mk.py` also
exist as actual PNG files. The 21 inline template glyphs are included,
including `src_hdmi`, `src_cat`, `src_tv`, `src_av`, `ln_main`, `ln_info`
and `pw_home`. `info`, `globe` and `users` are convenience aliases.

The category `photo` uses the camera glyph. `tv` and `network` remove the
separate settings-wrench path from the original monitor and globe glyphs.
The original source glyphs and `Layer_*` aliases retain their source shapes.

## Conversion and verification

- Read glyph paths and bounds from `web/src/z16/icons.json`. Its `vb` value
  fixes the exported `g1_25.svg` music glyph's incorrect viewBox.
- Extract the inline SVG groups from `web/xmb.tpl.html`.
- Render vectors using `@resvg/resvg-js`, then center their alpha bounds on
  256 × 256 transparent canvases. A small, baked alpha-edge highlight and
  shadow approximates the reference glass material without runtime SVG
  filters. These PNGs preserve white as the main glyph color.
- Convert the template's embedded WOFF2 using FontTools with its WOFF flavor
  removed, saving a standard TrueType font.
- Copy the four WAV files unchanged; optimize the blue/dark backgrounds as below.
- Verify every raster is nonempty RGBA and the expected dimensions. Inspect
  a contact sheet of the native category/action aliases, including music,
  TV, network, HDMI, Settings and Amazon Home.

Build dependencies and intermediate SVGs/contact sheets are under the
parent workspace's `work/theme-*` and `work/xmb-assets-*` directories;
they are not runtime APK dependencies.

## App marks and labels

`netflix.png`, `primevideo.png` and `youtube.png` are rendered from the
reference's single-color `assets/logos/` SVGs. `hulu.png` is rendered white
from the Simple Icons vector at commit
`a6e6540395abb660a2e201cd216cf0059210d658`:
<https://raw.githubusercontent.com/simple-icons/simple-icons/a6e6540395abb660a2e201cd216cf0059210d658/icons/hulu.svg>.
Simple Icons data is CC0; the marks belong to their respective owners.

`disney.png` uses the actual Disney+ script wordmark, plus sign and curved
arc from Disney's original public SVG on its BAMTech CDN:
<https://dmgz-splash-static.bamgrid.com/splash/sections/splash/builds/bb20cef8ccc3f839dc95308eec4206a669a424eb_1576017518933/fonts/logo-disneyplus.svg>.
The URL is preserved in the original 2020 Simple Icons Disney+ issue:
<https://github.com/simple-icons/simple-icons/issues/2309>. The SVG's vector
paths are rendered entirely white; its unused hidden background-image
reference is removed. The transparent result is 192 × 192 pixels and
8,035 bytes. No Disney font or raster square background is bundled.

`spotify.png` and `youtubemusic.png` use the original paths from Simple Icons
at the same pinned commit `a6e6540395abb660a2e201cd216cf0059210d658`:
<https://raw.githubusercontent.com/simple-icons/simple-icons/a6e6540395abb660a2e201cd216cf0059210d658/icons/spotify.svg>
and
<https://raw.githubusercontent.com/simple-icons/simple-icons/a6e6540395abb660a2e201cd216cf0059210d658/icons/youtubemusic.svg>.
Rendered white on transparent 192 × 192 PNGs with 8 px margins. The paths are
unchanged; Simple Icons' CC0 license is retained in `assets/xmb/notices/`.

## Retained reference notices

The reference's original code, generated background videos/images and
documentation are MIT licensed, copyright © 2026 Sanan Goyal. The full
license is retained at `assets/xmb/notices/XMB-TV-Home-LICENSE.txt`.

As stated by the reference `ASSETS.md`, the SCE-PS3 Rodin font, XMB menu
glyphs, navigation sounds and ribbon styling originate with or are modeled
on Sony Interactive Entertainment's PlayStation 3 XMB. Its MIT license
does not grant rights to that third-party material. The reference includes
it for personal, non-commercial fan-project use. App marks belong to their
respective owners. PlayStation, PS3 and XMB are Sony trademarks; this
launcher is not affiliated with or endorsed by Sony or the app brands.

The original asset notice is retained verbatim at
`assets/xmb/notices/XMB-TV-Home-ASSETS.md`, alongside its readable asset
README. It also identifies the reference system glyphs as Phosphor / MIT
and Material Symbols / Apache-2.0.

## HBO Max mark (1.0.7)

`logos/hbomax.png` renders the unchanged Pure White RGB stacked SVG from the
official [HBO Max logo guidelines](https://brand.hbomax.com/logos), suite
`HBO Max Logo Suite_062325`, member
`One-Color_Secondary Use/RGB/Pure White/HBO Max_Logo_Pure White_RGB_052225.svg`.
The original vector is retained in `tools/asset-sources/hbomax.svg`, with its
SHA256 and reproduction instructions in the adjacent README. It is centered
at 176px width on a transparent 192x192 canvas using @resvg/resvg-js; original
white paths and cutouts are preserved, with no opaque square or app-tile backdrop.
The mark belongs to HBO/Warner Bros. Discovery. This personal launcher claims
no affiliation, endorsement, trademark ownership or additional asset license.
