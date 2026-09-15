// Generates the field marker block and selector item textures.
//
// The assets are 16x16 PNGs committed to the repository; this script exists so they
// can be regenerated (and reviewed as code) instead of being binary blobs nobody can
// inspect. It writes them with Node's zlib only - no image library to install.
//
// Usage: node scripts/generate-field-textures.mjs
//
// Palette:
//   corner marker  dark slate body, cyan corner bracket, faint border
//   centre marker  dark slate body, white cross, cyan centre pad
//   selector item  transparent, oak handle, cyan diamond head, white crosshair

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SIZE = 16;
const ASSETS = join(
    dirname(fileURLToPath(import.meta.url)),
    '..',
    'src',
    'main',
    'resources',
    'assets',
    'mini_drone_system_mod',
    'textures'
);

const CORNER_BODY = [46, 56, 74, 255];
const CORNER_EDGE = [96, 112, 138, 255];
const CYAN = [56, 214, 226, 255];
const WHITE = [238, 244, 250, 255];
const WOOD = [130, 96, 62, 255];
const WOOD_DARK = [92, 66, 40, 255];
const CLEAR = [0, 0, 0, 0];

function createCanvas(fill) {
    const pixels = Buffer.alloc(SIZE * SIZE * 4);
    for (let index = 0; index < SIZE * SIZE; index++) {
        pixels.set(fill, index * 4);
    }
    return pixels;
}

function setPixel(pixels, x, y, color) {
    if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
        return;
    }
    pixels.set(color, (y * SIZE + x) * 4);
}

function fillRect(pixels, x0, y0, x1, y1, color) {
    for (let y = y0; y <= y1; y++) {
        for (let x = x0; x <= x1; x++) {
            setPixel(pixels, x, y, color);
        }
    }
}

// ---------------------------------------------------------------- PNG encoding

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c;
    }
    return table;
})();

function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) {
        crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length, 0);
    const typeAndData = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(typeAndData), 0);
    return Buffer.concat([length, typeAndData, crc]);
}

function encodePng(pixels) {
    const header = Buffer.alloc(13);
    header.writeUInt32BE(SIZE, 0);
    header.writeUInt32BE(SIZE, 4);
    header[8] = 8; // bit depth
    header[9] = 6; // colour type: RGBA
    header[10] = 0;
    header[11] = 0;
    header[12] = 0;

    const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE);
    for (let y = 0; y < SIZE; y++) {
        raw[y * (SIZE * 4 + 1)] = 0; // filter: none
        pixels.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
    }

    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', header),
        chunk('IDAT', deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0))
    ]);
}

// ------------------------------------------------------------------- the three

function cornerMarker() {
    const pixels = createCanvas(CORNER_BODY);
    fillRect(pixels, 0, 0, SIZE - 1, 0, CORNER_EDGE);
    fillRect(pixels, 0, SIZE - 1, SIZE - 1, SIZE - 1, CORNER_EDGE);
    fillRect(pixels, 0, 0, 0, SIZE - 1, CORNER_EDGE);
    fillRect(pixels, SIZE - 1, 0, SIZE - 1, SIZE - 1, CORNER_EDGE);
    // A bracket in the top-left corner reads as "this is a corner" without needing
    // four different rotations of the same texture.
    fillRect(pixels, 3, 3, 3, 12, CYAN);
    fillRect(pixels, 3, 3, 12, 3, CYAN);
    fillRect(pixels, 6, 6, 6, 9, WHITE);
    fillRect(pixels, 6, 6, 9, 6, WHITE);
    return pixels;
}

function centreMarker() {
    const pixels = createCanvas(CORNER_BODY);
    fillRect(pixels, 0, 0, SIZE - 1, 0, CORNER_EDGE);
    fillRect(pixels, 0, SIZE - 1, SIZE - 1, SIZE - 1, CORNER_EDGE);
    fillRect(pixels, 0, 0, 0, SIZE - 1, CORNER_EDGE);
    fillRect(pixels, SIZE - 1, 0, SIZE - 1, SIZE - 1, CORNER_EDGE);
    // Crosshair plus a solid pad in the middle: the pad is what the origin is put on.
    fillRect(pixels, 7, 3, 8, 12, WHITE);
    fillRect(pixels, 3, 7, 12, 8, WHITE);
    fillRect(pixels, 6, 6, 9, 9, CYAN);
    fillRect(pixels, 7, 7, 8, 8, WHITE);
    return pixels;
}

function selectorItem() {
    const pixels = createCanvas(CLEAR);
    // Handle running from the bottom-left to the middle.
    for (let step = 2; step < 10; step++) {
        setPixel(pixels, step, SIZE - 1 - step, WOOD);
        setPixel(pixels, step + 1, SIZE - 1 - step, WOOD_DARK);
        setPixel(pixels, step, SIZE - 2 - step, WOOD_DARK);
    }
    // Head: a small diamond, so it reads as a tool rather than a stick.
    setPixel(pixels, 11, 4, CYAN);
    setPixel(pixels, 12, 3, CYAN);
    setPixel(pixels, 13, 3, CYAN);
    setPixel(pixels, 12, 2, CYAN);
    setPixel(pixels, 12, 4, WHITE);
    setPixel(pixels, 12, 3, WHITE);
    // Crosshair above the head: the measuring part of the tool.
    setPixel(pixels, 12, 1, WHITE);
    setPixel(pixels, 12, 5, WHITE);
    setPixel(pixels, 10, 3, WHITE);
    setPixel(pixels, 14, 3, WHITE);
    return pixels;
}

// A quad-rotor seen from the side, carrying a downward arrow: "put the drone here".
function dronePlacementItem() {
    const pixels = createCanvas(CLEAR);
    const BODY = [214, 222, 232, 255];
    const ROTOR = CYAN;
    // Rotors.
    fillRect(pixels, 2, 3, 5, 3, ROTOR);
    fillRect(pixels, 10, 3, 13, 3, ROTOR);
    setPixel(pixels, 3, 4, ROTOR);
    setPixel(pixels, 12, 4, ROTOR);
    // Airframe: two arms meeting at a body in the middle.
    fillRect(pixels, 4, 4, 5, 5, BODY);
    fillRect(pixels, 10, 4, 11, 5, BODY);
    fillRect(pixels, 6, 5, 9, 6, BODY);
    fillRect(pixels, 7, 7, 8, 8, BODY);
    // Landing point: a short arrow pointing down at where it will sit.
    fillRect(pixels, 7, 10, 8, 12, WHITE);
    setPixel(pixels, 6, 12, WHITE);
    setPixel(pixels, 9, 12, WHITE);
    setPixel(pixels, 5, 13, WHITE);
    setPixel(pixels, 10, 13, WHITE);
    fillRect(pixels, 4, 15, 11, 15, BODY);
    return pixels;
}

const textures = [
    ['block/field_corner.png', cornerMarker()],
    ['block/field_center.png', centreMarker()],
    ['item/field_selector.png', selectorItem()],
    ['item/drone_placement.png', dronePlacementItem()]
];

for (const [relativePath, pixels] of textures) {
    const target = join(ASSETS, relativePath);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, encodePng(pixels));
    console.log(`wrote ${relativePath} (${SIZE}x${SIZE})`);
}
